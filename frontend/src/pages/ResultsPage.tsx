import { useMemo, useState } from 'react';
import { ArrowDown, ArrowDownToLine, ArrowLeft, ArrowRight, ArrowUp, ArrowUpDown, CheckCheck, ChevronLeft, ChevronRight, CircleDot, Clock3, Copy, FileSearch, ListFilter, RefreshCw, Search, Square, Timer, TriangleAlert } from 'lucide-react';
import type { JudgeState } from '../hooks/useJudge';
import type { CaseResult } from '../lib/types';
import { api } from '../lib/api';
import { formatBytes, isAccepted, normalizeStatus, progressStats, sortResults, visibleResults, type ResultSort } from '../lib/judge';
import { Button, ErrorNotice, Modal, StatusBadge, useToast } from '../components/ui';
import { ProgressBar } from '../components/Motion';
import { CaseDetails } from '../components/CaseDetails';

const PAGE_SIZE = 50;

function SortHeader({ field, label, sort, onSort }: { field: ResultSort['field']; label: string; sort: ResultSort; onSort: (sort: ResultSort) => void }) {
  const active = sort.field === field;
  const direction = active ? (sort.direction === 'ascending' ? 'descending' : 'ascending') : (field === 'caseNumber' ? 'ascending' : 'descending');
  const action = '按' + label + (direction === 'ascending' ? '升序' : '降序') + '排序';
  const Icon = active ? (sort.direction === 'ascending' ? ArrowUp : ArrowDown) : ArrowUpDown;
  return <th scope="col" aria-sort={active ? sort.direction : undefined}><button type="button" className="table-sort-button" aria-label={action} title={action} onClick={() => onSort({ field, direction })}>{label}<Icon size={13} aria-hidden="true" /></button></th>;
}

export function ResultsPage({ judge, onWorkspace }: { judge: JudgeState; onWorkspace: () => void }) {
  const [filter, setFilter] = useState('all');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState<ResultSort>({ field: 'caseNumber', direction: 'ascending' });
  const [selected, setSelected] = useState<CaseResult | null>(null);
  const [cancelOpen, setCancelOpen] = useState(false);
  const notify = useToast();
  const stats = progressStats(judge.progress);
  const cases = useMemo(() => visibleResults(judge.progress), [judge.progress]);
  const filtered = useMemo(() => cases.filter(result => (filter === 'all' || (filter === 'failed' ? !isAccepted(result) : isAccepted(result))) && String(result.caseNumber).includes(query.trim())), [cases, filter, query]);
  const sorted = useMemo(() => sortResults(filtered, sort), [filtered, sort]);
  const pageCount = Math.max(1, Math.ceil(sorted.length / PAGE_SIZE));
  const currentPage = Math.min(page, pageCount - 1);
  const results = sorted.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE);
  const percent = Math.round(Math.max(0, Math.min(100, judge.progress?.progress || 0)));
  const hasSamplesOnly = !!judge.progress?.summary && !judge.progress.results?.length;
  function changeSort(next: ResultSort) {
    setSort(next);
    setPage(0);
  }
  async function copyId() {
    try { await navigator.clipboard.writeText(judge.judgeId || ''); notify('任务 ID 已复制'); }
    catch { notify('无法复制，请手动选择任务 ID。', 'error'); }
  }
  return <>
    <div className="page-heading" data-reveal><div><h1>运行结果</h1></div><Button onClick={onWorkspace}><ArrowLeft size={16} />回到代码</Button></div>
    {judge.error && <ErrorNotice action={judge.judgeId && <Button onClick={judge.refresh}>刷新状态</Button>}>{judge.error}</ErrorNotice>}
    {judge.missing && <div className="notice notice-warning">该引用已无法访问。<Button onClick={judge.dismissMissing}>移除失效引用</Button></div>}
    {judge.notice && <div className="notice notice-info" role="status">{judge.notice}</div>}
    {!judge.judgeId ? <section className="results-empty panel" data-reveal><div className="empty-orbit"><FileSearch size={42} strokeWidth={1.2} /><span /></div><h2>暂无任务</h2><p>提交代码后在此查看结果。</p><Button variant="primary" onClick={onWorkspace}>返回工作台 <ArrowRight size={16} /></Button></section> : <>
      <section className="run-overview panel" data-reveal><div className="run-overview-top"><div className="run-title"><div className={'run-icon ' + (judge.running ? 'is-running' : '')}>{judge.running ? <RefreshCw size={22} /> : judge.terminal && !stats.failed ? <CheckCheck size={24} /> : <CircleDot size={24} />}</div><div><h2>当前对拍任务 {judge.progress && <StatusBadge status={judge.progress.status} />}</h2><button className="task-id" onClick={() => void copyId()} title="复制完整任务 ID">{judge.judgeId}<Copy size={12} /></button></div></div><div className="run-controls"><Button variant="ghost" onClick={judge.refresh} disabled={judge.busy}><RefreshCw size={15} />刷新</Button>{judge.unresolved && <Button variant="danger" disabled={judge.busy} onClick={() => setCancelOpen(true)}><Square size={13} />取消任务</Button>}</div></div>
        <div className="progress-caption"><span>{judge.progress ? normalizeStatus(judge.progress.status) === 'CREATED' ? '任务已创建，等待启动' : judge.terminal ? '任务已结束' : '正在运行测试' : '正在查询原任务状态'}</span><span><strong>{percent}</strong>%</span></div><ProgressBar value={percent} />
        <div className="progress-footnote"><span className={'connection-label connection-' + judge.connection}><span className="status-dot" />{{ idle: '状态已同步', connecting: '正在连接', live: '实时连接', polling: '自动刷新中', offline: '连接中断 · 保留最后状态' }[judge.connection]}</span><span>{stats.completed.toLocaleString()} / {(judge.progress?.summary?.totalCases ?? judge.policy?.requestedCases ?? (judge.terminal ? stats.total : 0)).toLocaleString()} 组</span></div>
        {judge.progress?.message && <pre className="task-message">{judge.progress.message.slice(0, 6000)}</pre>}
        {judge.unresolved && !judge.running && <div className="pending-actions"><Button variant="primary" loading={judge.busy} onClick={() => void judge.retry()}><RefreshCw size={15} />查询并重试原任务</Button><small>先核实状态，不会重复创建任务。</small></div>}
        {judge.progress?.summary?.stoppedReason && <div className="notice notice-warning">停止原因：{judge.progress.summary.stoppedReason}</div>}
      </section>
      <div className="stats-grid" data-reveal>{[
        { label: '已完成测试', value: stats.completed, unit: '组', icon: <CheckCheck size={18} />, tone: 'neutral' },
        { label: '通过测试', value: stats.accepted, unit: 'AC', icon: <CircleDot size={18} />, tone: 'green' },
        { label: '发现问题', value: stats.failed, unit: '组', icon: <TriangleAlert size={18} />, tone: 'coral' },
        { label: '首个失败点', value: judge.progress?.summary?.firstFailedCase ?? cases.find(result => !isAccepted(result))?.caseNumber ?? '—', unit: '', icon: <Timer size={18} />, tone: 'blue' },
      ].map(stat => <div className={'stat-card stat-' + stat.tone} key={stat.label}><span>{stat.label}{stat.icon}</span><strong>{typeof stat.value === 'number' ? stat.value.toLocaleString() : stat.value}<small>{stat.unit}</small></strong></div>)}</div>
      <section className="panel result-table-panel" data-reveal><div className="table-toolbar"><div><h2>测试点明细</h2><p>{hasSamplesOnly ? '仅对已返回的失败与慢速样本排序。' : '点击任意测试点，查看输入与输出对比。'}</p></div><div className="download-actions">{judge.terminal && !!judge.progress?.results?.length && <a className="button button-secondary" href={api.downloadUrl(judge.judgeId, 'all')} title="全部已判测试点的 .in / .out / .ans（如已生成）" download><ArrowDownToLine size={15} />全部已判数据</a>}{judge.terminal && stats.failed > 0 && <a className="button button-secondary" href={api.downloadUrl(judge.judgeId, 'failed')} download><ArrowDownToLine size={15} />失败数据</a>}</div></div>
        <div className="table-filters"><div className="filter-buttons" aria-label="测试结果筛选"><ListFilter size={16} />{[['all', '全部'], ['failed', '仅失败'], ['accepted', '仅通过']].map(([value, label]) => <button key={value} aria-pressed={filter === value} className={filter === value ? 'active' : ''} onClick={() => { setFilter(value); setPage(0); }}>{label}</button>)}</div><label className="table-search"><Search size={15} /><input aria-label="搜索测试点编号" placeholder="搜索测试点编号" value={query} onChange={event => { setQuery(event.target.value); setPage(0); }} /></label></div>
        <div className="table-scroll"><table className="results-table"><thead><tr><SortHeader field="caseNumber" label="测试点" sort={sort} onSort={changeSort} /><th scope="col">运行结果</th><SortHeader field="timeUsed" label="耗时" sort={sort} onSort={changeSort} /><SortHeader field="memoryUsed" label="内存" sort={sort} onSort={changeSort} /><th scope="col"><span className="visually-hidden">操作</span></th></tr></thead><tbody>{results.map(result => <tr key={result.caseNumber}><th scope="row"><span className="case-index"># {result.caseNumber.toString().padStart(2, '0')}</span></th><td><StatusBadge status={result.status} /></td><td><Clock3 size={13} />{result.timeUsed} <span className="cell-unit">ms</span></td><td>{formatBytes(result.memoryUsed * 1024)}</td><td><button className="text-button" onClick={() => setSelected(result)} aria-label={'查看测试点 ' + result.caseNumber + ' 详情'}>查看详情 <ArrowRight size={14} /></button></td></tr>)}</tbody></table></div>
        {!results.length && <div className="table-empty"><FileSearch size={26} /><p>{cases.length ? '没有符合筛选条件的测试点。' : judge.terminal ? '服务端未返回逐点结果。请查看任务摘要。' : '等待第一组测试结果…'}</p></div>}
        <div className="table-pagination"><span>共 {filtered.length.toLocaleString()} {hasSamplesOnly ? '个样本' : '个测试点'} · 每页 {PAGE_SIZE} 条</span><div><Button variant="ghost" aria-label="上一页" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}><ChevronLeft size={16} /></Button><span>{currentPage + 1} / {pageCount}</span><Button variant="ghost" aria-label="下一页" disabled={currentPage + 1 >= pageCount} onClick={() => setPage(currentPage + 1)}><ChevronRight size={16} /></Button></div></div>
      </section>
      {judge.policy && <div className="policy-note"><span>服务端策略：{judge.policy.mode}</span><span>任务上限 {judge.policy.maxCasesPerTask.toLocaleString()} 组</span><span>单点输出上限 {formatBytes(judge.policy.maxOutputBytesPerCase)}</span>{judge.policy.highVolume && <span>大批量摘要模式</span>}</div>}
      {selected && <CaseDetails key={judge.judgeId + ':' + selected.caseNumber} judgeId={judge.judgeId} result={selected} onClose={() => setSelected(null)} />}
    </>}
    {cancelOpen && <Modal title="停止这次对拍？" description="取消请求会交给服务端处理，最终状态以查询结果为准。已完成的测试点会保留。" onClose={() => setCancelOpen(false)}><div className="modal-footer"><Button onClick={() => setCancelOpen(false)}>继续运行</Button><Button variant="danger" onClick={() => { setCancelOpen(false); void judge.cancel(); }}>确认取消任务</Button></div></Modal>}
  </>;
}
