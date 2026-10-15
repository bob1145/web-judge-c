import { useEffect, useMemo, useState } from 'react';
import { Copy, Download, FileInput, GitCompareArrows, LoaderCircle } from 'lucide-react';
import { api, messageOf } from '../lib/api';
import { compareOutputs } from '../lib/diff';
import type { CaseDetail, CaseResult } from '../lib/types';
import { Button, ErrorNotice, Modal, StatusBadge, useToast } from './ui';

const MAX_PREVIEW_CHARS = 18000;

export function CaseDetails({ judgeId, result, onClose }: { judgeId: string; result: CaseResult; onClose: () => void }) {
  const [details, setDetails] = useState<CaseDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [differenceOnly, setDifferenceOnly] = useState(result.status !== 'AC');
  const [attempt, setAttempt] = useState(0);
  const notify = useToast();
  useEffect(() => {
    const controller = new AbortController();
    setError(null); setDetails(null);
    api.details(judgeId, result.caseNumber, controller.signal).then(value => { if (!controller.signal.aborted) setDetails(value); }).catch(reason => { if (!controller.signal.aborted) setError(messageOf(reason)); });
    return () => controller.abort();
  }, [judgeId, result.caseNumber, attempt]);
  const comparison = useMemo(() => compareOutputs(details?.correctOutput || '', details?.userOutput || '', differenceOnly), [details, differenceOnly]);
  async function copyInput() {
    try { await navigator.clipboard.writeText(details?.input || ''); notify('已复制当前输入预览'); }
    catch { notify('复制失败，请手动选择文本。', 'error'); }
  }
  const clipped = details && (details.inputTruncated || details.userOutputTruncated || details.correctOutputTruncated || (details.input || '').length > MAX_PREVIEW_CHARS || comparison.truncated);
  return <Modal title={'测试点 #' + result.caseNumber.toString().padStart(2, '0')} description="输入、参考答案与待测输出。" onClose={onClose} wide>
    <div className="detail-meta"><StatusBadge status={result.status} /><span>{result.timeUsed} ms</span><span>{(result.memoryUsed / 1024).toFixed(2)} MB</span><a className="button button-secondary" href={api.caseArchiveUrl(judgeId, result.caseNumber)} title="ZIP：输入 .in、待测输出 .out、参考答案 .ans（如已生成）" download><Download size={15} />下载测试点</a></div>
    {error && <ErrorNotice action={<Button onClick={() => setAttempt(value => value + 1)}>重试</Button>}>{error}</ErrorNotice>}
    {!details && !error && <div className="loading-state"><LoaderCircle className="spin" />正在读取测试点…</div>}
    {details && <><section className="detail-input"><div className="detail-block-heading"><h3><FileInput size={16} />输入（.in）</h3><div className="download-actions"><a className="icon-button" href={api.downloadUrl(judgeId, result.caseNumber)} aria-label="下载输入 .in" title="仅下载输入 .in" download><Download size={15} /></a><button className="icon-button" aria-label="复制输入数据" onClick={() => void copyInput()}><Copy size={15} /></button></div></div><pre>{(details.input || '').slice(0, MAX_PREVIEW_CHARS) || '（空输入）'}</pre></section>
      <div className="diff-heading"><h3><GitCompareArrows size={17} />输出对比</h3><label className="checkbox-label"><input type="checkbox" checked={differenceOnly} onChange={event => setDifferenceOnly(event.target.checked)} />只看差异</label></div>
      <div className="diff-container"><table className="diff-table"><thead><tr><th scope="col">行</th><th scope="col">参考答案（.ans）</th><th scope="col">待测输出（.out）</th></tr></thead><tbody>{comparison.rows.map(line => <tr className={line.different ? 'different' : ''} key={line.number}><td>{line.number}</td><td><pre>{line.expected ?? '（缺失行）'}</pre></td><td><pre>{line.actual ?? '（缺失行）'}</pre>{line.different && <span className="visually-hidden">与预期不同</span>}</td></tr>)}</tbody></table>
        {differenceOnly && !comparison.hasDifferences && <p className="diff-empty">服务端返回的预览范围内没有逐行差异。</p>}</div>
      {clipped && <div className="notice notice-warning">当前内容为截断预览；下载测试点可查看完整输入、待测输出与参考答案（如已生成）。</div>}
    </>}
  </Modal>;
}
