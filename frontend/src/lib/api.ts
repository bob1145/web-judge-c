import type { AuthResponse, Bootstrap, CancelResponse, CaseDetail, CreateResponse, JudgeProgress, JudgeRequest, SessionInfo } from './types';

export type RequestPhase = 'auth' | 'bootstrap' | 'create' | 'start' | 'status' | 'cancel' | 'details' | 'download';

export class ApiError extends Error {
  constructor(public status: number, public phase: RequestPhase, public uncertain = false) {
    super(errorMessage(status, phase, uncertain));
    this.name = 'ApiError';
  }
}

export function errorMessage(status: number, phase: RequestPhase, uncertain = false): string {
  if (status === 404 && phase === 'bootstrap') return '后端尚未提供前端初始化接口，请更新并重启后端。';
  if (status === 404 && phase === 'details') return '测试点数据不可用，可能尚未生成、已过期或无权访问。';
  if (status === 401) return phase === 'auth' ? '验证未通过，请检查登录信息。' : '登录已过期，请重新验证。';
  if (status === 403) return '安全验证未通过，请刷新页面后重试。';
  if (status === 404) return '任务不存在、已过期，或当前账号无权访问。';
  if (status === 409) return '任务状态已变化，请先刷新原任务状态。';
  if (status === 429) return phase === 'auth' ? '尝试过于频繁，请稍后再试。' : '队列或使用配额已满，请稍后重试原任务。';
  if (status === 400 || status === 422) return '参数未被接受，请检查代码、测试数量和资源限制。';
  if (phase === 'create' && uncertain) return '创建结果未知且未取得任务ID；未自动重新提交。请确认后再创建，避免重复任务。';
  if (status >= 500) return '服务暂时不可用，请稍后重试。已有任务将保留。';
  if (phase === 'start' || phase === 'cancel' || phase === 'status') return '连接中断，任务状态暂时未知。已保留原任务，请刷新状态。';
  return '暂时无法连接服务，请检查网络和后端是否启动。';
}

let bootstrap: Bootstrap | null = null;

async function request(path: string, phase: RequestPhase, options: RequestInit = {}): Promise<Response> {
  const headers = new Headers(options.headers);
  headers.set('Accept', 'application/json');
  headers.set('X-Requested-With', 'XMLHttpRequest');
  if (options.body) headers.set('Content-Type', 'application/json');
  if (options.method === 'POST') {
    if (!bootstrap) throw new ApiError(403, phase);
    headers.set(bootstrap.csrfHeader, bootstrap.csrfToken);
  }
  let response: Response;
  try {
    response = await fetch(path, {
      ...options,
      headers,
      credentials: 'same-origin',
      cache: 'no-store',
      signal: options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(20000)]) : AbortSignal.timeout(20000),
    });
  } catch (error) {
    if (options.signal?.aborted) throw error;
    throw new ApiError(0, phase, options.method === 'POST');
  }
  if (!response.ok) {
    if (response.status === 401 && phase !== 'auth') window.dispatchEvent(new Event('judge:unauthorized'));
    throw new ApiError(response.status, phase, phase === 'create' && response.status >= 500);
  }
  return response;
}

async function json<T>(path: string, phase: RequestPhase, options: RequestInit = {}): Promise<T> {
  const response = await request(path, phase, options);
  try {
    return await response.json() as T;
  } catch {
    throw new ApiError(0, phase, options.method === 'POST');
  }
}

export const api = {
  async bootstrap(signal?: AbortSignal) {
    const result = await json<Bootstrap>('/auth/bootstrap', 'bootstrap', { signal });
    if (typeof result.csrfToken !== 'string' || !result.csrfToken || typeof result.csrfHeader !== 'string'
      || typeof result.accountAuthRequired !== 'boolean') throw new ApiError(0, 'bootstrap');
    bootstrap = result;
    return result;
  },
  session: (signal?: AbortSignal) => json<SessionInfo>('/auth/session', 'auth', { signal }),
  async login(credentials: { username?: string; password?: string; accessCode?: string; rememberMe: boolean }) {
    const result = await json<AuthResponse>('/auth/verify', 'auth', { method: 'POST', body: JSON.stringify(credentials) });
    if (result.success !== true) throw new ApiError(401, 'auth');
    await api.bootstrap();
    return result;
  },
  async logout() {
    await request('/auth/logout', 'auth', { method: 'POST' });
    bootstrap = null;
  },
  async create(payload: JudgeRequest) {
    const result = await json<CreateResponse>('/judge', 'create', { method: 'POST', body: JSON.stringify(payload) });
    if (!result.judgeId || typeof result.judgeId !== 'string' || !/^[a-zA-Z0-9-]{1,128}$/.test(result.judgeId)) {
      throw new ApiError(0, 'create', true);
    }
    return result;
  },
  start: (judgeId: string) => request('/judge/start/' + encodeURIComponent(judgeId), 'start', { method: 'POST' }),
  status: (judgeId: string, signal?: AbortSignal) => json<JudgeProgress>('/judge/status/' + encodeURIComponent(judgeId), 'status', { signal }),
  async cancel(judgeId: string) {
    const result = await json<CancelResponse>('/judge/cancel/' + encodeURIComponent(judgeId), 'cancel', { method: 'POST' });
    if (typeof result.accepted !== 'boolean') throw new ApiError(0, 'cancel', true);
    return result;
  },
  details: (judgeId: string, caseNumber: number, signal?: AbortSignal) => json<CaseDetail>('/details/' + encodeURIComponent(judgeId) + '/' + caseNumber, 'details', { signal }),
  downloadUrl: (judgeId: string, kind: 'all' | 'failed' | number) => '/download/' + encodeURIComponent(judgeId) + '/' + kind,
  caseArchiveUrl: (judgeId: string, caseNumber: number) => '/download/' + encodeURIComponent(judgeId) + '/' + caseNumber + '/archive',
  csrf: () => bootstrap ? { [bootstrap.csrfHeader]: bootstrap.csrfToken } : {},
};

export function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : '操作未完成，请稍后重试。';
}
