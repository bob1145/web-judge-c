import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

async function mockSession(page: Page, authenticated = true, account = false) {
  await page.route('**/auth/bootstrap', route => route.fulfill({ json: { csrfToken: 'e2e-fixture-token', csrfHeader: 'X-CSRF-TOKEN', accountAuthRequired: account } }));
  await page.route('**/auth/session', route => route.fulfill({ json: { valid: authenticated, remainingMinutes: 1440 } }));
}
async function loadExample(page: Page) {
  await page.getByRole('link', { name: '示例模板', exact: true }).click();
  await page.getByRole('button', { name: '使用这个示例' }).first().click();
  await expect(page.getByRole('heading', { name: '对拍工作区' })).toBeVisible();
}

test('workspace renders locally, navigates by keyboard and preserves drafts', async ({ page }) => {
  await mockSession(page);
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '对拍工作区' })).toBeVisible();
  await expect(page.getByRole('link', { name: '对拍工作台首页' }).locator('img')).toBeVisible();
  await expect(page.locator('.hero-banner, .sidebar-inspiration, .workflow-strip, .workspace-footer, .recent-task')).toHaveCount(0);
  await expect(page.getByRole('main')).not.toContainText('让每一次提交');
  await loadExample(page);
  await expect(page.locator('.cm-editor')).toContainText('long long first, second');
  await page.getByRole('spinbutton', { name: '测试数量 组', exact: true }).fill('27');
  await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem('cppJudge.reactDraft.v1') || '{}').testCases)).toBe(27);
  await page.reload();
  await expect(page.locator('#test-cases')).toHaveValue('27');
  await expect(page.locator('.cm-editor')).toContainText('long long first, second');
  await page.keyboard.press('Control+k');
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('combobox').fill('使用指南');
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: '使用指南' })).toBeVisible();
  await page.getByText('如何比较浮点数？', { exact: true }).click();
  await expect(page.getByText('在高级设置中调整比较精度。', { exact: false })).toBeVisible();
});

test('structured create, fallback progress, failures and downloads use the real route contract', async ({ page }) => {
  await mockSession(page);
  const requests: { url: string; body: any; csrf: string | undefined }[] = [];
  await page.route('**/judge', async route => {
    requests.push({ url: route.request().url(), body: route.request().postDataJSON(), csrf: route.request().headers()['x-csrf-token'] });
    await route.fulfill({ json: { judgeId: 'fixture-judge', status: 'CREATED', mode: 'trusted-local', requestedCases: 100, maxCasesPerTask: 10000, maxOutputBytesPerCase: 1048576, highVolume: false } });
  });
  await page.route('**/judge/start/fixture-judge', route => route.fulfill({ body: 'Judge task started' }));
  await page.route('**/judge/status/fixture-judge', route => route.fulfill({ json: { status: 'COMPLETED', progress: 100, revision: 7, results: [{ caseNumber: 1, status: 'AC', timeUsed: 12, memoryUsed: 1024 }, { caseNumber: 2, status: 'WA', timeUsed: 16, memoryUsed: 2048 }] } }));
  await page.route('**/details/fixture-judge/2', route => route.fulfill({ json: { input: '2 3', correctOutput: '5', userOutput: '6', inputTruncated: false, correctOutputTruncated: false, userOutputTruncated: false } }));
  await page.goto('/');
  await loadExample(page);
  await page.getByRole('button', { name: '开始对拍' }).click();
  await expect(page.getByRole('button', { name: '查看测试点 2 详情' })).toBeVisible();
  expect(requests).toHaveLength(1);
  expect(requests[0].csrf).toBe('e2e-fixture-token');
  expect(requests[0].body.memoryLimit).toBe(268435456);
  expect(requests[0].body.precision).toBe('1e-9');
  await page.getByRole('button', { name: '仅失败', exact: true }).click();
  await expect(page.getByRole('button', { name: '查看测试点 1 详情' })).toHaveCount(0);
  await page.getByRole('button', { name: '查看测试点 2 详情' }).click();
  await expect(page.getByRole('columnheader', { name: '参考答案（.ans）' })).toBeVisible();
  await expect(page.getByRole('columnheader', { name: '待测输出（.out）' })).toBeVisible();
  await expect(page.locator('.diff-table .different')).toHaveCount(1);
  await expect(page.getByRole('link', { name: '下载测试点', exact: true })).toHaveAttribute('href', '/download/fixture-judge/2/archive');
  await expect(page.getByRole('link', { name: '下载输入 .in', exact: true })).toHaveAttribute('href', '/download/fixture-judge/2');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '查看测试点 2 详情' })).toBeFocused();
  await expect(page.getByRole('link', { name: '失败数据' })).toHaveAttribute('href', '/download/fixture-judge/failed');
  await expect(page.getByRole('link', { name: '全部已判数据' })).toHaveAttribute('href', '/download/fixture-judge/all');
});

test('early failure keeps every completed concurrent case visible without inventing the rest', async ({ page }) => {
  await mockSession(page);
  await page.addInitScript(() => sessionStorage.setItem('cppJudge.lastJudgeId', 'stopped-fixture'));
  await page.route('**/judge/status/stopped-fixture', route => route.fulfill({ json: {
    status: 'WA', progress: 100, revision: 5,
    results: [
      { caseNumber: 2, status: 'WA', timeUsed: 40, memoryUsed: 0 },
      { caseNumber: 4, status: 'AC', timeUsed: 60, memoryUsed: 0 },
      { caseNumber: 1, status: 'AC', timeUsed: 45, memoryUsed: 0 },
      { caseNumber: 3, status: 'AC', timeUsed: 50, memoryUsed: 0 },
    ],
    summary: { totalCases: 100, completedCases: 4, ac: 3, wa: 1, tle: 0, mle: 0, re: 0, systemError: 0, outputLimitExceeded: 0, firstFailedCase: 2, failureSamples: [], slowSamples: [], stoppedReason: 'Stopped after first non-AC test case' },
  } }));
  await page.goto('/#results');
  await expect(page.locator('.results-table .case-index')).toHaveText(['# 01', '# 02', '# 03', '# 04']);
  await expect(page.locator('.progress-footnote')).toContainText('4 / 100 组');
  await expect(page.getByRole('link', { name: '全部已判数据' })).toHaveAttribute('href', '/download/stopped-fixture/all');
  await page.getByRole('button', { name: '仅失败', exact: true }).click();
  await expect(page.locator('.results-table .case-index')).toHaveText(['# 02']);
  await page.getByRole('button', { name: '全部', exact: true }).click();
  await expect(page.locator('.results-table .case-index')).toHaveCount(4);
});

test('result sorting applies before pagination and preserves filtering and search', async ({ page }) => {
  await mockSession(page);
  await page.addInitScript(() => sessionStorage.setItem('cppJudge.lastJudgeId', 'sorting-fixture'));
  const results = Array.from({ length: 65 }, (_item, index) => ({
    caseNumber: index + 1, status: index % 2 ? 'WA' : 'AC', timeUsed: (index + 1) * 10, memoryUsed: (65 - index) * 128,
  })).reverse();
  await page.route('**/judge/status/sorting-fixture', route => route.fulfill({ json: { status: 'COMPLETED', progress: 100, revision: 1, results } }));
  await page.goto('/#results');
  const rows = page.locator('.results-table .case-index');
  await expect(rows.first()).toHaveText('# 01');
  await page.getByRole('button', { name: '下一页', exact: true }).click();
  await expect(rows.first()).toHaveText('# 51');
  await page.getByRole('button', { name: '按耗时降序排序' }).click();
  await expect(rows.first()).toHaveText('# 65');
  await expect(rows).toHaveCount(50);
  await expect(page.getByRole('button', { name: '上一页', exact: true })).toBeDisabled();
  await expect(page.locator('thead th').nth(2)).toHaveAttribute('aria-sort', 'descending');
  await page.getByRole('button', { name: '按耗时升序排序' }).focus();
  await page.keyboard.press('Enter');
  await expect(rows.first()).toHaveText('# 01');
  await expect(page.locator('thead th').nth(2)).toHaveAttribute('aria-sort', 'ascending');
  await page.getByRole('button', { name: '按内存降序排序' }).click();
  await expect(rows.first()).toHaveText('# 01');
  await expect(page.locator('thead th').nth(3)).toHaveAttribute('aria-sort', 'descending');
  await expect(page.locator('th[aria-sort]')).toHaveCount(1);
  await page.getByRole('button', { name: '按内存升序排序' }).click();
  await expect(rows.first()).toHaveText('# 65');
  await page.getByRole('button', { name: '仅失败', exact: true }).click();
  await expect(rows.first()).toHaveText('# 64');
  await page.getByRole('textbox', { name: '搜索测试点编号' }).fill('6');
  await expect(rows).toHaveText(['# 64', '# 62', '# 60', '# 56', '# 46', '# 36', '# 26', '# 16', '# 06']);
  await page.getByRole('button', { name: '按测试点升序排序' }).click();
  await expect(rows.first()).toHaveText('# 06');
  await page.getByRole('textbox', { name: '搜索测试点编号' }).fill('');
  await page.getByRole('button', { name: '全部', exact: true }).click();
  await expect(rows.first()).toHaveText('# 01');
  await page.getByRole('button', { name: '按测试点降序排序' }).click();
  await expect(rows.first()).toHaveText('# 65');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: '按内存降序排序' }).click();
  await expect(rows.first()).toHaveText('# 01');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test('new progress keeps the selected result sort', async ({ page }) => {
  await mockSession(page);
  await page.addInitScript(() => sessionStorage.setItem('cppJudge.lastJudgeId', 'live-sorting-fixture'));
  let progress = { status: 'RUNNING', progress: 66, revision: 1, results: [
    { caseNumber: 1, status: 'AC', timeUsed: 2, memoryUsed: 512 },
    { caseNumber: 2, status: 'AC', timeUsed: 10, memoryUsed: 256 },
  ] };
  await page.route('**/judge/status/live-sorting-fixture', route => route.fulfill({ json: progress }));
  await page.goto('/#results');
  await page.getByRole('button', { name: '按耗时降序排序' }).click();
  await expect(page.locator('.case-index').first()).toHaveText('# 02');
  progress = { status: 'COMPLETED', progress: 100, revision: 2, results: [...progress.results, { caseNumber: 3, status: 'AC', timeUsed: 100, memoryUsed: 128 }] };
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  await expect(page.locator('.case-index')).toHaveText(['# 03', '# 02', '# 01']);
  await expect(page.locator('thead th').nth(2)).toHaveAttribute('aria-sort', 'descending');
});

for (const width of [1440, 1024, 768, 390, 320]) {
  test('responsive workspace at ' + width + 'px has no horizontal overflow', async ({ page }) => {
    await mockSession(page);
    await page.setViewportSize({ width, height: width < 600 ? 844 : 900 });
    await page.goto('/');
    await expect(page.locator('.cm-editor')).toBeVisible();
    expect((await page.locator('.editor-card').boundingBox())?.y).toBeLessThan(200);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await expect(page.getByRole('link', { name: '示例模板', exact: true })).toBeVisible();
    await page.getByRole('link', { name: '示例模板', exact: true }).click();
    await expect(page.getByRole('heading', { name: '示例模板' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });
}

test('all principal views satisfy automated WCAG checks', async ({ page }) => {
  await mockSession(page);
  await page.goto('/');
  await loadExample(page);
  for (const view of ['对拍工作台', '运行结果', '示例模板', '使用指南']) {
    await page.getByRole('link', { name: view, exact: true }).click();
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
    expect(results.violations, view + ': ' + JSON.stringify(results.violations.map(issue => ({ id: issue.id, nodes: issue.nodes.map(node => node.target) })))).toEqual([]);
  }
});

test('account authentication keeps spaces in passwords and submits once', async ({ page }) => {
  await mockSession(page, false, true);
  let calls = 0;
  let password = '';
  await page.route('**/auth/verify', async route => {
    calls += 1;
    password = route.request().postDataJSON().password;
    await route.fulfill({ status: 401, json: { success: false, message: '<img src=x onerror=alert(1)>' } });
  });
  await page.goto('/auth');
  await page.getByLabel('用户名', { exact: true }).fill('tester');
  await page.getByLabel('密码', { exact: true }).fill(' spaced password ');
  await page.getByLabel('密码', { exact: true }).press('Enter');
  await expect(page.getByRole('alert')).toContainText('验证未通过');
  expect(calls).toBe(1);
  expect(password).toBe(' spaced password ');
  await expect(page.locator('img[src="x"]')).toHaveCount(0);
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(results.violations).toEqual([]);
});


test('restored created task retries the same ID and never creates another task', async ({ page }) => {
  await mockSession(page);
  await page.addInitScript(() => sessionStorage.setItem('cppJudge.lastJudgeId', 'original-fixture'));
  let starts = 0;
  let creates = 0;
  await page.route('**/judge', route => { creates++; return route.fulfill({ status: 500 }); });
  await page.route('**/judge/status/original-fixture', route => route.fulfill({ json: { status: 'CREATED', progress: 0, revision: 1 } }));
  await page.route('**/judge/start/original-fixture', route => { starts++; return route.fulfill({ status: 429, json: { code: 'QUEUE_FULL' } }); });
  await page.goto('/#results');
  await expect(page.getByText('任务已创建，等待启动', { exact: true })).toBeVisible();
  expect(starts).toBe(0);
  await page.getByRole('button', { name: '查询并重试原任务' }).click();
  await expect(page.getByRole('alert')).toContainText('队列或使用配额已满');
  expect(starts).toBe(1);
  expect(creates).toBe(0);
  await expect(page.locator('.task-id')).toContainText('original-fixture');
});

test('high-volume summaries keep bounded samples and hide unavailable full archives', async ({ page }) => {
  await mockSession(page);
  await page.addInitScript(() => sessionStorage.setItem('cppJudge.lastJudgeId', 'summary-fixture'));
  await page.route('**/judge/status/summary-fixture', route => route.fulfill({ json: {
    status: 'COMPLETED', progress: 100, revision: 8,
    summary: { totalCases: 100000, completedCases: 100000, ac: 99920, wa: 80, tle: 0, mle: 0, re: 0, systemError: 0, outputLimitExceeded: 0, firstFailedCase: 1,
      failureSamples: Array.from({ length: 80 }, (_, index) => ({ caseNumber: index + 1, status: 'WA', timeUsed: index + 1, memoryUsed: 2048 })), slowSamples: [] },
  } }));
  await page.goto('/#results');
  await expect(page.getByText('仅对已返回的失败与慢速样本排序。')).toBeVisible();
  await expect(page.locator('.results-table tbody tr')).toHaveCount(50);
  await expect(page.getByRole('link', { name: '全部已判数据', exact: true })).toHaveCount(0);
  await expect(page.getByRole('link', { name: '失败数据', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '下一页', exact: true }).click();
  await expect(page.locator('.results-table tbody tr')).toHaveCount(30);
  await page.getByRole('button', { name: '按耗时降序排序' }).click();
  await expect(page.locator('.results-table tbody tr')).toHaveCount(50);
  await expect(page.locator('.case-index').first()).toHaveText('# 80');
  await expect(page.locator('.table-pagination')).toContainText('共 80 个样本');
  await page.getByRole('button', { name: '下一页', exact: true }).click();
  await expect(page.locator('.case-index').first()).toHaveText('# 30');
  const audit = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(audit.violations).toEqual([]);
});
