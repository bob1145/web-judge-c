import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError, messageOf } from '../lib/api';
import { acceptProgress, isActive, isTerminal, normalizeStatus, validProgress } from '../lib/judge';
import { readJudgeId, saveJudgeId } from '../lib/storage';
import { trackJudge, type Connection } from '../lib/tracker';
import type { CreateResponse, JudgeProgress, JudgeRequest } from '../lib/types';

export function useJudge() {
  const [judgeId, setJudgeId] = useState<string | null>(readJudgeId);
  const [progress, setProgress] = useState<JudgeProgress | null>(null);
  const [policy, setPolicy] = useState<CreateResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [connection, setConnection] = useState<Connection>('idle');
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [missing, setMissing] = useState(false);
  const lock = useRef(false);
  const mounted = useRef(true);
  const currentId = useRef(judgeId);
  const accepted = useRef<JudgeProgress | null>(null);
  const stopRef = useRef<(() => void) | null>(null);

  const consume = useCallback((incoming: JudgeProgress) => {
    if (!mounted.current || !acceptProgress(accepted.current, incoming)) return;
    accepted.current = incoming;
    setProgress(incoming);
    setError(null);
    setMissing(false);
    if (isTerminal(incoming)) setNotice(null);
  }, []);

  const watch = useCallback((id: string) => {
    stopRef.current?.();
    stopRef.current = trackJudge(id, {
      onProgress: incoming => { if (currentId.current === id) consume(incoming); },
      onConnection: value => { if (mounted.current && currentId.current === id) setConnection(value); },
      onError: failure => { if (mounted.current && currentId.current === id) { setError(messageOf(failure)); setMissing(failure instanceof ApiError && failure.status === 404); } },
    });
  }, [consume]);

  useEffect(() => {
    mounted.current = true;
    if (currentId.current) watch(currentId.current);
    const reconnect = () => { if (currentId.current && !isTerminal(accepted.current)) watch(currentId.current); };
    window.addEventListener('online', reconnect);
    return () => { mounted.current = false; stopRef.current?.(); window.removeEventListener('online', reconnect); };
  }, [watch]);

  async function reconcile(id: string) {
    const incoming = await api.status(id);
    if (!validProgress(incoming)) throw new ApiError(0, 'status');
    if (mounted.current && currentId.current === id) consume(incoming);
    return incoming;
  }

  async function startOriginal(id: string) {
    if (!mounted.current || currentId.current !== id) return;
    try {
      await api.start(id);
      if (mounted.current) { setNotice('任务已提交，正在等待执行。'); watch(id); }
    } catch (failure) {
      if (!mounted.current) return;
      setError(messageOf(failure));
      try {
        const incoming = await reconcile(id);
        if (isActive(incoming)) watch(id);
        else if (!isTerminal(incoming)) setError(messageOf(failure));
      } catch (statusFailure) { setError(messageOf(statusFailure)); }
    }
  }

  async function submit(payload: JudgeRequest) {
    if (lock.current || currentId.current && !isTerminal(accepted.current)) return;
    lock.current = true;
    setBusy(true);
    setError(null);
    setNotice(null);
    stopRef.current?.();
    try {
      const created = await api.create(payload);
      currentId.current = created.judgeId;
      saveJudgeId(created.judgeId);
      if (!mounted.current) return;
      accepted.current = null;
      setProgress(null);
      setJudgeId(created.judgeId);
      setPolicy(created);
      await startOriginal(created.judgeId);
    } catch (failure) { if (mounted.current) setError(messageOf(failure)); }
    finally { lock.current = false; if (mounted.current) setBusy(false); }
  }

  async function retry() {
    const id = currentId.current;
    if (!id || lock.current) return;
    lock.current = true;
    setBusy(true);
    setError(null);
    try {
      const incoming = await reconcile(id);
      if (normalizeStatus(incoming.status) === 'CREATED') await startOriginal(id);
      else if (!isTerminal(incoming)) watch(id);
    } catch (failure) { if (mounted.current) setError(messageOf(failure)); }
    finally { lock.current = false; if (mounted.current) setBusy(false); }
  }

  async function cancel() {
    const id = currentId.current;
    if (!id || lock.current || isTerminal(accepted.current)) return;
    lock.current = true;
    setBusy(true);
    try {
      const response = await api.cancel(id);
      if (!mounted.current) return;
      setNotice(response.accepted ? '已请求取消，最终状态以任务查询为准。' : '取消未被接受，正在查询最终状态。');
      const incoming = await reconcile(id);
      if (!isTerminal(incoming)) watch(id);
    } catch (failure) { if (mounted.current) { setError(messageOf(failure)); watch(id); } }
    finally { lock.current = false; if (mounted.current) setBusy(false); }
  }

  function refresh() {
    if (currentId.current) watch(currentId.current);
  }

  function dismissMissing() {
    if (!missing) return;
    stopRef.current?.();
    currentId.current = null;
    accepted.current = null;
    saveJudgeId(null);
    setJudgeId(null);
    setProgress(null);
    setPolicy(null);
    setError(null);
    setConnection('idle');
    setMissing(false);
  }

  return { judgeId, progress, policy, busy, connection, error, notice, missing, submit, retry, cancel, refresh, dismissMissing,
    running: isActive(progress), terminal: isTerminal(progress),
    unresolved: !!judgeId && !isTerminal(progress),
  };
}

export type JudgeState = ReturnType<typeof useJudge>;
