import { Client } from '@stomp/stompjs';
import { api, ApiError } from './api';
import { acceptProgress, isTerminal, normalizeStatus, validProgress } from './judge';
import type { JudgeProgress } from './types';

export type Connection = 'connecting' | 'live' | 'polling' | 'offline' | 'idle';
export interface TrackerCallbacks {
  onProgress: (progress: JudgeProgress) => void;
  onConnection: (connection: Connection) => void;
  onError: (error: unknown) => void;
}

export function trackJudge(judgeId: string, callbacks: TrackerCallbacks) {
  let stopped = false;
  let previous: JudgeProgress | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let failures = 0;
  let live = false;
  const controller = new AbortController();
  const socketUrl = new URL('/ws/websocket', window.location.href);
  socketUrl.protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const client = new Client({
    brokerURL: socketUrl.href,
    connectHeaders: api.csrf(),
    reconnectDelay: 0,
    connectionTimeout: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    debug: () => {},
    onConnect: () => {
      if (stopped) return;
      live = true;
      callbacks.onConnection('live');
      client.subscribe('/topic/progress/' + judgeId, message => {
        try { consume(JSON.parse(message.body) as JudgeProgress); } catch {}
      });
    },
    onWebSocketClose: () => {
      live = false;
      if (!stopped) callbacks.onConnection('polling');
    },
    onStompError: () => { live = false; if (!stopped) callbacks.onConnection('polling'); },
  });

  function stop() {
    if (stopped) return;
    stopped = true;
    clearTimeout(timer);
    controller.abort();
    void client.deactivate();
  }

  function consume(progress: JudgeProgress) {
    if (stopped || !acceptProgress(previous, progress)) return;
    previous = progress;
    callbacks.onProgress(progress);
    if (isTerminal(progress) || normalizeStatus(progress.status) === 'CREATED') {
      callbacks.onConnection('idle');
      stop();
    }
  }

  async function poll() {
    try {
      const progress = await api.status(judgeId, controller.signal);
      if (stopped) return;
      if (!validProgress(progress)) throw new ApiError(0, 'status');
      failures = 0;
      callbacks.onConnection(live ? 'live' : 'polling');
      consume(progress);
    } catch (error) {
      if (stopped) return;
      failures += 1;
      callbacks.onConnection('offline');
      callbacks.onError(error);
      if (error instanceof ApiError && [401, 403, 404].includes(error.status) || failures >= 5) {
        stop();
        return;
      }
    }
    if (!stopped) timer = setTimeout(poll, failures ? Math.min(1500 * 2 ** failures, 15000) : live ? 8000 : 1800);
  }

  callbacks.onConnection('connecting');
  client.activate();
  void poll();
  return stop;
}
