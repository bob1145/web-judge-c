import { describe, expect, it } from 'vitest';
import { acceptProgress, formatBytes, isTerminal, normalizeStatus, progressStats, sortResults, toRequest, validateDraft, visibleResults, type ResultSort } from './judge';
import { defaultDraft } from './storage';
import { examples } from './examples';
import { compareOutputs } from './diff';
import type { CaseResult, JudgeProgress } from './types';

const validDraft = { ...defaultDraft, ...examples[0].code };
describe('judge contract', () => {
  it('uses milliseconds, bytes and preserves decimal precision text', () => {
    const request = toRequest({ ...validDraft, memoryLimitMb: 256, precision: '1e-9' });
    expect(request.memoryLimit).toBe(268435456);
    expect(request.timeLimit).toBe(1000);
    expect(request.precision).toBe('1e-9');
    expect(request.stopOnFirstNonAc).toBe(true);
    expect(request.specialJudgeCode).toBe('');
    expect(request).not.toHaveProperty('memoryLimitMb');
  });
  it('submits only the selected comparator', () => {
    expect(toRequest({ ...validDraft, useSpecialJudge: true, specialJudgeCode: 'checker' })).toMatchObject({ bruteForceCode: '', specialJudgeCode: 'checker', useSpecialJudge: true });
  });
  it.each(['CE', 'COMPILE_ERROR', 'COMPILATION_FAILED', 'SE', 'OLE', 'SECURITY_VIOLATION', 'SANDBOX_UNAVAILABLE', 'OUTPUT_LIMIT_EXCEEDED', 'STALE', 'CANCELLED', 'BUDGET_EXCEEDED', 'WA', 'AC', 'COMPLETED'])('recognizes terminal status %s without a results array', status => {
    expect(isTerminal({ status, progress: 0 })).toBe(true);
  });
  it('normalizes status separators', () => { expect(normalizeStatus(' compile-error ')).toBe('COMPILATION_ERROR'); });
  it('rejects stale revisions and terminal regressions', () => {
    const previous = { status: 'RUNNING', progress: 50, revision: 7 };
    expect(acceptProgress(previous, { status: 'RUNNING', progress: 20, revision: 6 })).toBe(false);
    expect(acceptProgress(previous, { status: 'RUNNING', progress: 60 })).toBe(false);
    expect(acceptProgress(previous, { status: 'COMPLETED', progress: 100, revision: 8 })).toBe(true);
    expect(acceptProgress({ status: 'COMPLETED', progress: 100, revision: 8 }, { status: 'RUNNING', progress: 90, revision: 9 })).toBe(false);
    expect(acceptProgress(null, { status: 'unknown', progress: 0 })).toBe(false);
  });
  it('handles high-volume summary with no results and deduplicates samples', () => {
    const sample = { caseNumber: 7000, status: 'WA', timeUsed: 2, memoryUsed: 1024 };
    const progress: JudgeProgress = { status: 'COMPLETED', progress: 100, summary: { totalCases: 100000, completedCases: 7000, ac: 6999, wa: 1, tle: 0, mle: 0, re: 0, systemError: 0, outputLimitExceeded: 0, failureSamples: [sample], slowSamples: [sample], firstFailedCase: 7000 } };
    expect(progressStats(progress)).toEqual({ completed: 7000, accepted: 6999, failed: 1, total: 100000 });
    expect(visibleResults(progress)).toEqual([sample]);
  });
  it('does not fabricate results for an empty task', () => { expect(visibleResults(null)).toEqual([]); expect(progressStats(null).completed).toBe(0); });
  it.each(['-1', 'NaN', 'Infinity', 'hello', '1e9999'])('rejects invalid precision %s', precision => { expect(validateDraft({ ...validDraft, precision })).not.toBeNull(); });
  it('validates required code, integer counts and numeric limits', () => {
    expect(validateDraft(validDraft)).toBeNull();
    expect(validateDraft(defaultDraft)).toContain('生成器');
    expect(validateDraft({ ...validDraft, testCases: 1.5 })).toContain('正整数');
    expect(validateDraft({ ...validDraft, timeLimit: NaN })).toContain('时间');
    expect(validateDraft({ ...validDraft, useSpecialJudge: true })).toContain('Special Judge');
    expect(formatBytes(1048576)).toBe('1 MB');
  });
});

describe('result sorting', () => {
  const results: CaseResult[] = [
    { caseNumber: 3, status: 'WA', timeUsed: 1000, memoryUsed: 1024 },
    { caseNumber: 1, status: 'AC', timeUsed: 2, memoryUsed: 512 },
    { caseNumber: 4, status: 'AC', timeUsed: 0, memoryUsed: 0 },
    { caseNumber: 2, status: 'WA', timeUsed: 10, memoryUsed: 2048 },
    { caseNumber: 5, status: 'AC', timeUsed: 10, memoryUsed: 512 },
  ];
  const scenarios: { sort: ResultSort; expected: number[] }[] = [
    { sort: { field: 'caseNumber', direction: 'ascending' }, expected: [1, 2, 3, 4, 5] },
    { sort: { field: 'caseNumber', direction: 'descending' }, expected: [5, 4, 3, 2, 1] },
    { sort: { field: 'timeUsed', direction: 'ascending' }, expected: [4, 1, 2, 5, 3] },
    { sort: { field: 'timeUsed', direction: 'descending' }, expected: [3, 2, 5, 1, 4] },
    { sort: { field: 'memoryUsed', direction: 'ascending' }, expected: [4, 1, 5, 3, 2] },
    { sort: { field: 'memoryUsed', direction: 'descending' }, expected: [2, 3, 1, 5, 4] },
  ];
  it.each(scenarios)('sorts $sort.field $sort.direction numerically with case-number ties', ({ sort, expected }) => {
    expect(sortResults(results, sort).map(result => result.caseNumber)).toEqual(expected);
  });
  it('does not reorder or replace the source results', () => {
    const source = Object.freeze([...results]);
    const sorted = sortResults(source, { field: 'timeUsed', direction: 'descending' });
    expect(sorted).not.toBe(source);
    expect(source.map(result => result.caseNumber)).toEqual([3, 1, 4, 2, 5]);
    expect(sorted[0]).toBe(source[0]);
  });
  it('keeps an empty result list empty', () => {
    expect(sortResults([], { field: 'memoryUsed', direction: 'descending' })).toEqual([]);
  });
});

describe('bounded output comparison', () => {
  it('finds late mismatches without truncating before comparison', () => {
    const prefix = 'same\n'.repeat(8000);
    const comparison = compareOutputs(prefix + 'expected', prefix + 'actual', true);
    expect(comparison.rows).toEqual([{ number: 8001, expected: 'expected', actual: 'actual', different: true }]);
  });
  it('limits rendered rows and cells and reports truncation', () => {
    expect(compareOutputs('first\n'.repeat(10000), 'second\n'.repeat(10000), true).rows).toHaveLength(200);
    const comparison = compareOutputs('a'.repeat(10000), 'b', true);
    expect(comparison.rows[0].expected).toHaveLength(2000);
    expect(comparison.truncated).toBe(true);
  });
  it('normalizes CRLF while preserving missing lines', () => {
    expect(compareOutputs('a\r\nb', 'a\nb', true).rows).toEqual([]);
    expect(compareOutputs('a\nb', 'a', true).rows[0].actual).toBeUndefined();
  });
});
