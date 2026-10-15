import type { CaseResult, JudgeProgress, JudgeRequest, WorkspaceDraft } from './types';

const aliases: Record<string, string> = { CE: 'COMPILATION_ERROR', COMPILE_ERROR: 'COMPILATION_ERROR', COMPILATION_FAILED: 'COMPILATION_ERROR', SE: 'SYSTEM_ERROR', OLE: 'OUTPUT_LIMIT_EXCEEDED' };
const active = new Set(['QUEUED', 'PENDING', 'COMPILING', 'RUNNING']);
const terminal = new Set(['COMPLETED', 'AC', 'WA', 'TLE', 'MLE', 'RE', 'OUTPUT_LIMIT_EXCEEDED', 'COMPILATION_ERROR', 'SYSTEM_ERROR', 'SECURITY_VIOLATION', 'SANDBOX_UNAVAILABLE', 'CANCELLED', 'STALE', 'BUDGET_EXCEEDED']);
export const statusLabels: Record<string, string> = {
  CREATED: '待启动', QUEUED: '排队中', PENDING: '准备中', COMPILING: '编译中', RUNNING: '对拍中', COMPLETED: '已完成',
  AC: '通过', WA: '答案错误', PE: '格式错误', TLE: '运行超时', MLE: '内存超限', RE: '运行错误', OUTPUT_LIMIT_EXCEEDED: '输出超限',
  COMPILATION_ERROR: '编译失败', SYSTEM_ERROR: '系统错误', SECURITY_VIOLATION: '安全限制', SANDBOX_UNAVAILABLE: '沙箱不可用',
  CANCELLED: '已取消', STALE: '任务已过期', BUDGET_EXCEEDED: '执行预算耗尽',
};
export function normalizeStatus(status: string) {
  const normalized = String(status || '').trim().replace(/[\s-]+/g, '_').toUpperCase();
  return aliases[normalized] || normalized;
}
export const isTerminal = (progress: JudgeProgress | null) => !!progress && terminal.has(normalizeStatus(progress.status));
export const isActive = (progress: JudgeProgress | null) => !!progress && active.has(normalizeStatus(progress.status));
export const statusLabel = (status: string) => statusLabels[normalizeStatus(status)] || '未知状态';
export const isAccepted = (result: CaseResult) => normalizeStatus(result.status) === 'AC';

export function validProgress(value: JudgeProgress): boolean {
  return !!value && typeof value.status === 'string' && Number.isFinite(value.progress)
    && (active.has(normalizeStatus(value.status)) || terminal.has(normalizeStatus(value.status)) || normalizeStatus(value.status) === 'CREATED');
}

export function acceptProgress(previous: JudgeProgress | null, incoming: JudgeProgress): boolean {
  if (!validProgress(incoming) || isTerminal(previous)) return false;
  if (previous?.revision != null && (incoming.revision == null || incoming.revision <= previous.revision)) return false;
  return true;
}

export function progressStats(progress: JudgeProgress | null) {
  const results = progress?.results || [];
  const summary = progress?.summary;
  const completed = summary?.completedCases ?? results.length;
  const accepted = summary?.ac ?? results.filter(isAccepted).length;
  return { completed, accepted, failed: Math.max(0, completed - accepted), total: summary?.totalCases ?? results.length };
}

export function visibleResults(progress: JudgeProgress | null): CaseResult[] {
  if (!progress) return [];
  if (progress.results?.length) return progress.results;
  const cases = [...(progress.summary?.failureSamples || []), ...(progress.summary?.slowSamples || [])];
  return Array.from(new Map(cases.map(result => [result.caseNumber, result])).values()).sort((first, second) => first.caseNumber - second.caseNumber);
}

export type ResultSort = { field: 'caseNumber' | 'timeUsed' | 'memoryUsed'; direction: 'ascending' | 'descending' };

export function sortResults(results: readonly CaseResult[], sort: ResultSort): CaseResult[] {
  return [...results].sort((first, second) => {
    const difference = first[sort.field] - second[sort.field];
    return (sort.direction === 'ascending' ? difference : -difference) || first.caseNumber - second.caseNumber;
  });
}

export function validateDraft(draft: WorkspaceDraft): string | null {
  if (!draft.generatorCode.trim()) return '请先填写数据生成器代码。';
  if (!draft.userCode.trim()) return '请先填写待测程序。';
  if (draft.useSpecialJudge ? !draft.specialJudgeCode.trim() : !draft.bruteForceCode.trim()) return draft.useSpecialJudge ? '请填写 Special Judge 代码。' : '请填写参考程序。';
  if (!Number.isInteger(draft.testCases) || draft.testCases < 1 || draft.testCases > 2147483647) return '测试数量必须是有效正整数。';
  if (!Number.isSafeInteger(draft.timeLimit) || draft.timeLimit < 1) return '时间限制必须是正整数，单位为毫秒。';
  if (!Number.isSafeInteger(draft.memoryLimitMb) || draft.memoryLimitMb < 1 || draft.memoryLimitMb > 1048576) return '请输入有效的内存限制。';
  if (!/^(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(draft.precision.trim()) || !Number.isFinite(Number(draft.precision))) return '比较精度应为非负数，例如 0 或 1e-9。';
  return null;
}

export function toRequest(draft: WorkspaceDraft): JudgeRequest {
  const { memoryLimitMb, ...payload } = draft;
  return {
    ...payload,
    memoryLimit: memoryLimitMb * 1024 * 1024,
    precision: draft.precision.trim(),
    bruteForceCode: draft.useSpecialJudge ? '' : draft.bruteForceCode,
    specialJudgeCode: draft.useSpecialJudge ? draft.specialJudgeCode : '',
  };
}

export function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes)) return '—';
  return bytes >= 1048576 ? (bytes / 1048576).toLocaleString('zh-CN', { maximumFractionDigits: 1 }) + ' MB' : (bytes / 1024).toLocaleString('zh-CN', { maximumFractionDigits: 1 }) + ' KB';
}
