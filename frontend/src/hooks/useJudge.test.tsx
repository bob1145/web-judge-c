import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from '../lib/api';
import { useJudge } from './useJudge';
import { defaultDraft } from '../lib/storage';
import { toRequest } from '../lib/judge';
import { trackJudge, type TrackerCallbacks } from '../lib/tracker';

vi.mock('../lib/tracker', () => ({ trackJudge: vi.fn(() => vi.fn()) }));
vi.mock('../lib/api', async importOriginal => {
  const original = await importOriginal<typeof import('../lib/api')>();
  return { ...original, api: { ...original.api, create: vi.fn(), start: vi.fn(), status: vi.fn(), cancel: vi.fn() } };
});
const created = { judgeId: 'original', mode: 'trusted-local', requestedCases: 100, maxCasesPerTask: 10000, maxOutputBytesPerCase: 1024, highVolume: false, status: 'CREATED' };
const payload = toRequest(defaultDraft);
function lastTracker(): TrackerCallbacks { return vi.mocked(trackJudge).mock.calls.at(-1)![1]; }
beforeEach(() => { localStorage.clear(); sessionStorage.clear(); vi.clearAllMocks(); });
afterEach(cleanup);

describe('task lifecycle', () => {
  it('creates exactly once even when submit is triggered twice', async () => {
    vi.mocked(api.create).mockResolvedValue(created);
    vi.mocked(api.start).mockResolvedValue(new Response('started'));
    const { result } = renderHook(useJudge);
    await act(async () => { await Promise.all([result.current.submit(payload), result.current.submit(payload)]); });
    expect(api.create).toHaveBeenCalledTimes(1);
    expect(api.start).toHaveBeenCalledTimes(1);
    expect(result.current.judgeId).toBe('original');
  });
  it('reconciles the original ID before retrying a rejected start', async () => {
    vi.mocked(api.create).mockResolvedValue(created);
    vi.mocked(api.start).mockRejectedValueOnce(new ApiError(429, 'start')).mockResolvedValueOnce(new Response('started'));
    vi.mocked(api.status).mockResolvedValue({ status: 'CREATED', progress: 0, revision: 1 });
    const { result } = renderHook(useJudge);
    await act(async () => { await result.current.submit(payload); });
    expect(result.current.error).toContain('配额');
    await act(async () => { await result.current.retry(); });
    expect(api.create).toHaveBeenCalledTimes(1);
    expect(api.start).toHaveBeenNthCalledWith(2, 'original');
    expect(api.status).toHaveBeenCalledTimes(2);
    expect(vi.mocked(api.status).mock.invocationCallOrder[1]).toBeLessThan(vi.mocked(api.start).mock.invocationCallOrder[1]);
  });
  it('does not restart a task that is already running after an uncertain start', async () => {
    sessionStorage.setItem('cppJudge.lastJudgeId', 'original');
    vi.mocked(api.status).mockResolvedValue({ status: 'RUNNING', progress: 20, revision: 2 });
    const { result } = renderHook(useJudge);
    await act(async () => { await result.current.retry(); });
    expect(api.create).not.toHaveBeenCalled();
    expect(api.start).not.toHaveBeenCalled();
    expect(result.current.running).toBe(true);
  });
  it('does not synthesize CANCELLED from an accepted cancel response', async () => {
    sessionStorage.setItem('cppJudge.lastJudgeId', 'original');
    vi.mocked(api.cancel).mockResolvedValue({ judgeId: 'original', accepted: true, status: 'RUNNING', message: '', running: true, queued: false, completedCases: 2 });
    vi.mocked(api.status).mockResolvedValue({ status: 'RUNNING', progress: 20, revision: 2 });
    const { result } = renderHook(useJudge);
    await act(async () => { await result.current.cancel(); });
    expect(result.current.progress?.status).toBe('RUNNING');
    expect(result.current.terminal).toBe(false);
    expect(api.cancel).toHaveBeenCalledWith('original');
    act(() => lastTracker().onProgress({ status: 'CANCELLED', progress: 20, revision: 3 }));
    await waitFor(() => expect(result.current.terminal).toBe(true));
  });
  it('restores server status without trusting cached browser results', () => {
    localStorage.setItem('cppJudge.lastJudgeId', 'original');
    localStorage.setItem('cppJudge.lastProgress', JSON.stringify({ status: 'COMPLETED', progress: 100 }));
    const { result } = renderHook(useJudge);
    expect(result.current.progress).toBeNull();
    expect(trackJudge).toHaveBeenCalledWith('original', expect.any(Object));
  });
  it('preserves ID and exposes an explicit recovery action for an inaccessible task', () => {
    sessionStorage.setItem('cppJudge.lastJudgeId', 'missing');
    const { result } = renderHook(useJudge);
    act(() => lastTracker().onError(new ApiError(404, 'status')));
    expect(result.current.missing).toBe(true);
    expect(result.current.judgeId).toBe('missing');
    act(() => result.current.dismissMissing());
    expect(result.current.judgeId).toBeNull();
  });
});
