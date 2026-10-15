import type { WorkspaceDraft } from './types';

export const DRAFT_KEY = 'cppJudge.reactDraft.v1';
export const JUDGE_ID_KEY = 'cppJudge.lastJudgeId';
export const defaultDraft: WorkspaceDraft = {
  generatorCode: '', bruteForceCode: '', userCode: '', specialJudgeCode: '',
  testCases: 100, timeLimit: 1000, memoryLimitMb: 256, precision: '1e-9', stopOnFirstNonAc: true, useSpecialJudge: false,
};

export function readDraft(): WorkspaceDraft {
  try {
    const saved = localStorage.getItem(DRAFT_KEY);
    const draft = { ...defaultDraft };
    const candidate: Record<string, unknown> = saved ? JSON.parse(saved) : {
      generatorCode: localStorage.getItem('generatorCode') || '',
      bruteForceCode: localStorage.getItem('bruteForceCode') || '',
      userCode: localStorage.getItem('userCode') || '',
      specialJudgeCode: localStorage.getItem('specialJudgeCode') || '',
      testCases: Number(localStorage.getItem('testCases') || 100),
      timeLimit: Number(localStorage.getItem('timeLimit') || 1000),
      memoryLimitMb: Number(localStorage.getItem('memoryLimit') || 256),
      precision: localStorage.getItem('precision') || '1e-9',
      stopOnFirstNonAc: localStorage.getItem('stopOnFirstNonAc') !== 'false',
      useSpecialJudge: localStorage.getItem('useSpecialJudge') === 'true',
    };
    for (const key of Object.keys(draft) as (keyof WorkspaceDraft)[]) {
      if (candidate && typeof candidate[key] === typeof draft[key] && (typeof candidate[key] !== 'number' || Number.isFinite(candidate[key]))) {
        Object.assign(draft, { [key]: candidate[key] });
      }
    }
    return draft;
  } catch { return { ...defaultDraft }; }
}

export function saveDraft(draft: WorkspaceDraft): boolean {
  try { localStorage.setItem(DRAFT_KEY, JSON.stringify(draft)); return true; } catch { return false; }
}

export function readJudgeId(): string | null {
  try {
    const value = sessionStorage.getItem(JUDGE_ID_KEY) || localStorage.getItem(JUDGE_ID_KEY);
    return value && /^[a-zA-Z0-9-]{1,128}$/.test(value) ? value : null;
  } catch { return null; }
}

export function saveJudgeId(judgeId: string | null) {
  try {
    if (judgeId) sessionStorage.setItem(JUDGE_ID_KEY, judgeId);
    else sessionStorage.removeItem(JUDGE_ID_KEY);
    localStorage.removeItem(JUDGE_ID_KEY);
    localStorage.removeItem('cppJudge.lastProgress');
  } catch {}
}
