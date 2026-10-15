import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, errorMessage } from './api';
import { defaultDraft } from './storage';
import { toRequest } from './judge';

const mockFetch = vi.fn();
const jsonResponse = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
beforeEach(() => { vi.stubGlobal('fetch', mockFetch); mockFetch.mockReset(); });
afterEach(() => vi.unstubAllGlobals());
async function bootstrap() {
  mockFetch.mockResolvedValueOnce(jsonResponse({ csrfToken: 'test-csrf', csrfHeader: 'X-CSRF-TOKEN', accountAuthRequired: false }));
  await api.bootstrap();
}

describe('API client', () => {
  it('distinguishes input-only downloads from case and result archives', () => {
    expect(api.downloadUrl('task-original', 2)).toBe('/download/task-original/2');
    expect(api.caseArchiveUrl('task-original', 2)).toBe('/download/task-original/2/archive');
    expect(api.downloadUrl('task-original', 'all')).toBe('/download/task-original/all');
    expect(api.downloadUrl('task-original', 'failed')).toBe('/download/task-original/failed');
    expect(api.caseArchiveUrl('task/name', 2)).toBe('/download/task%2Fname/2/archive');
  });
  it('uses cookies, structured JSON and the server-provided CSRF header', async () => {
    await bootstrap();
    mockFetch.mockResolvedValueOnce(jsonResponse({ judgeId: 'task-original', status: 'CREATED' }));
    await api.create(toRequest(defaultDraft));
    const [url, options] = mockFetch.mock.calls[1];
    expect(url).toBe('/judge');
    expect(options.credentials).toBe('same-origin');
    expect(options.headers.get('Accept')).toBe('application/json');
    expect(options.headers.get('X-CSRF-TOKEN')).toBe('test-csrf');
    expect(options.headers.get('X-Requested-With')).toBe('XMLHttpRequest');
  });
  it('does not trim passwords and refreshes rotated CSRF after login', async () => {
    await bootstrap();
    mockFetch.mockResolvedValueOnce(jsonResponse({ success: true }));
    mockFetch.mockResolvedValueOnce(jsonResponse({ csrfToken: 'rotated', csrfHeader: 'X-CSRF-TOKEN', accountAuthRequired: true }));
    await api.login({ username: 'user', password: ' spaced password ', rememberMe: false });
    expect(JSON.parse(mockFetch.mock.calls[1][1].body).password).toBe(' spaced password ');
    expect(api.csrf()).toEqual({ 'X-CSRF-TOKEN': 'rotated' });
  });
  it.each([401, 403, 404, 409, 429, 503])('distinguishes HTTP %s without reflecting arbitrary server HTML', async status => {
    mockFetch.mockResolvedValueOnce(new Response('<script>secret</script>', { status }));
    await expect(api.status('original')).rejects.toMatchObject({ status, phase: 'status' });
    expect(errorMessage(status, 'status')).not.toContain('script');
  });
  it('treats unknown creation responses as uncertain and never automatically retries', async () => {
    await bootstrap();
    mockFetch.mockResolvedValueOnce(new Response('<html>login</html>'));
    await expect(api.create(toRequest(defaultDraft))).rejects.toMatchObject({ uncertain: true, phase: 'create' });
    expect(mockFetch).toHaveBeenCalledTimes(2);
  });
  it('preserves original ID on start and cancel requests and validates cancellation response', async () => {
    await bootstrap();
    mockFetch.mockResolvedValueOnce(new Response('Judge task started'));
    await api.start('original');
    mockFetch.mockResolvedValueOnce(jsonResponse({ status: 'CANCELLED' }));
    await expect(api.cancel('original')).rejects.toBeInstanceOf(ApiError);
    expect(mockFetch.mock.calls[1][0]).toBe('/judge/start/original');
    expect(mockFetch.mock.calls[2][0]).toBe('/judge/cancel/original');
  });
  it('returns clear creation uncertainty on network failure', async () => {
    await bootstrap();
    mockFetch.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    await expect(api.create(toRequest(defaultDraft))).rejects.toThrow('未自动重新提交');
  });
});
