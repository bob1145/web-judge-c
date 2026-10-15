import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defaultDraft, DRAFT_KEY, readDraft, readJudgeId, saveDraft, saveJudgeId } from './storage';

describe('local drafts', () => {
  beforeEach(() => { localStorage.clear(); sessionStorage.clear(); });
  it('migrates legacy code and settings without replacing the user draft', () => {
    localStorage.setItem('userCode', 'original code');
    localStorage.setItem('memoryLimit', '512');
    localStorage.setItem('stopOnFirstNonAc', 'false');
    expect(readDraft()).toMatchObject({ userCode: 'original code', memoryLimitMb: 512, stopOnFirstNonAc: false });
  });
  it('persists all code roles and settings', () => {
    const draft = { ...defaultDraft, userCode: 'draft', specialJudgeCode: 'checker', useSpecialJudge: true };
    expect(saveDraft(draft)).toBe(true);
    expect(readDraft()).toEqual(draft);
  });
  it('rejects wrong field types and broken JSON', () => {
    localStorage.setItem(DRAFT_KEY, JSON.stringify({ userCode: 17, testCases: 'invalid', generatorCode: 'valid' }));
    expect(readDraft()).toMatchObject({ userCode: '', testCases: 100, generatorCode: 'valid' });
    localStorage.setItem(DRAFT_KEY, '{');
    expect(readDraft()).toEqual(defaultDraft);
  });
  it('retains task ID but never trusts cached progress', () => {
    localStorage.setItem('cppJudge.lastJudgeId', 'task-original');
    localStorage.setItem('cppJudge.lastProgress', JSON.stringify({ status: 'COMPLETED' }));
    expect(readJudgeId()).toBe('task-original');
    saveJudgeId('task-original');
    expect(sessionStorage.getItem('cppJudge.lastJudgeId')).toBe('task-original');
    expect(localStorage.getItem('cppJudge.lastProgress')).toBeNull();
    saveJudgeId(null);
    expect(readJudgeId()).toBeNull();
  });
  it('degrades gracefully when storage is disabled', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota'); });
    expect(saveDraft(defaultDraft)).toBe(false);
  });
});
