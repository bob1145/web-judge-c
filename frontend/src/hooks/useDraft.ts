import { useEffect, useRef, useState } from 'react';
import { readDraft, saveDraft } from '../lib/storage';
import type { WorkspaceDraft } from '../lib/types';

export function useDraft() {
  const [draft, setDraft] = useState<WorkspaceDraft>(readDraft);
  const [saved, setSaved] = useState(true);
  const latest = useRef(draft);
  latest.current = draft;
  useEffect(() => {
    setSaved(false);
    const timer = setTimeout(() => setSaved(saveDraft(draft)), 450);
    return () => clearTimeout(timer);
  }, [draft]);
  useEffect(() => {
    const flush = () => saveDraft(latest.current);
    const visibility = () => { if (document.visibilityState === 'hidden') flush(); };
    window.addEventListener('pagehide', flush);
    window.addEventListener('beforeunload', flush);
    document.addEventListener('visibilitychange', visibility);
    return () => { flush(); window.removeEventListener('pagehide', flush); window.removeEventListener('beforeunload', flush); document.removeEventListener('visibilitychange', visibility); };
  }, []);
  function update<Key extends keyof WorkspaceDraft>(key: Key, value: WorkspaceDraft[Key]) {
    setDraft(current => ({ ...current, [key]: value }));
  }
  return { draft, setDraft, update, saved };
}
