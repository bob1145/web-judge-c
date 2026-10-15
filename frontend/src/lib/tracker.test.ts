import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { trackJudge, type TrackerCallbacks } from './tracker';
import { api } from './api';
import type { JudgeProgress } from './types';

const socket = vi.hoisted(() => ({ config: {} as Record<string, any>, handler: null as ((message: { body: string }) => void) | null, deactivate: vi.fn() }));
vi.mock('@stomp/stompjs', () => ({ Client: class {
  constructor(config: Record<string, unknown>) { socket.config = config; }
  activate() {}
  deactivate() { socket.deactivate(); return Promise.resolve(); }
  subscribe(_destination: string, handler: typeof socket.handler) { socket.handler = handler; }
} }));
vi.mock('./api', async importOriginal => {
  const original = await importOriginal<typeof import('./api')>();
  return { ...original, api: { ...original.api, status: vi.fn(), csrf: () => ({ 'X-CSRF-TOKEN': 'test-token' }) } };
});
const callbacks = (): TrackerCallbacks => ({ onProgress: vi.fn(), onConnection: vi.fn(), onError: vi.fn() });

describe('task progress channels', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.mocked(api.status).mockReset(); socket.deactivate.mockClear(); });
  afterEach(() => vi.useRealTimers());
  it('never overlaps polls and aborts pending work on cleanup', async () => {
    let resolveStatus!: (value: JudgeProgress) => void;
    vi.mocked(api.status).mockReturnValueOnce(new Promise(resolve => { resolveStatus = resolve; }));
    const events = callbacks();
    const stop = trackJudge('original', events);
    await vi.advanceTimersByTimeAsync(12000);
    expect(api.status).toHaveBeenCalledTimes(1);
    const signal = vi.mocked(api.status).mock.calls[0][1];
    stop();
    expect(signal?.aborted).toBe(true);
    resolveStatus({ status: 'RUNNING', progress: 50 });
    await vi.advanceTimersByTimeAsync(15000);
    expect(events.onProgress).not.toHaveBeenCalled();
    expect(api.status).toHaveBeenCalledTimes(1);
  });
  it('processes terminal socket frames immediately and rejects late stale responses', async () => {
    vi.mocked(api.status).mockResolvedValue({ status: 'RUNNING', progress: 10, revision: 1 });
    const events = callbacks();
    const stop = trackJudge('original', events);
    await vi.advanceTimersByTimeAsync(0);
    socket.config.onConnect();
    expect(socket.config.connectHeaders).toEqual({ 'X-CSRF-TOKEN': 'test-token' });
    socket.handler?.({ body: JSON.stringify({ status: 'COMPLETED', progress: 100, revision: 3 }) });
    socket.handler?.({ body: JSON.stringify({ status: 'RUNNING', progress: 20, revision: 2 }) });
    expect(events.onProgress).toHaveBeenLastCalledWith({ status: 'COMPLETED', progress: 100, revision: 3 });
    expect(socket.deactivate).toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(30000);
    expect(api.status).toHaveBeenCalledTimes(1);
    stop();
  });
  it('restored CREATED tasks stop tracking and never auto-start', async () => {
    vi.mocked(api.status).mockResolvedValue({ status: 'CREATED', progress: 0 });
    const events = callbacks();
    trackJudge('original', events);
    await vi.advanceTimersByTimeAsync(30000);
    expect(api.status).toHaveBeenCalledTimes(1);
    expect(events.onProgress).toHaveBeenCalledWith({ status: 'CREATED', progress: 0 });
    expect(events.onConnection).toHaveBeenLastCalledWith('idle');
  });
  it('falls back to sequential polling if the socket closes', async () => {
    vi.mocked(api.status).mockResolvedValue({ status: 'RUNNING', progress: 10 });
    const events = callbacks();
    const stop = trackJudge('original', events);
    socket.config.onWebSocketClose();
    await vi.advanceTimersByTimeAsync(1900);
    expect(events.onConnection).toHaveBeenCalledWith('polling');
    expect(api.status).toHaveBeenCalledTimes(2);
    stop();
  });
});
