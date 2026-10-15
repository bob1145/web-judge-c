import { ArrowRight, ArrowUpRight, Check, Layers3 } from 'lucide-react';
import type { WorkspaceDraft } from '../lib/types';
import type { JudgeState } from '../hooks/useJudge';
import { ProgressBar } from '../components/Motion';
import { CodeWorkspace } from '../components/CodeWorkspace';
import { JudgeSettings } from '../components/JudgeSettings';
import { Button, StatusBadge } from '../components/ui';
import { progressStats } from '../lib/judge';

export function WorkspacePage({ draft, update, saved, judge, onRun, onExamples, onResults }: {
  draft: WorkspaceDraft; update: <Key extends keyof WorkspaceDraft>(key: Key, value: WorkspaceDraft[Key]) => void;
  saved: boolean; judge: JudgeState; onRun: () => void; onExamples: () => void; onResults: () => void;
}) {
  const stats = progressStats(judge.progress);
  return <>
    <div className="workspace-heading" id="workspace-code" data-reveal><h1>对拍工作区</h1><button className="text-button" onClick={onExamples}><Layers3 size={15} />示例模板 <ArrowUpRight size={14} /></button></div>
    <div className="workspace-grid"><CodeWorkspace draft={draft} update={update} saved={saved} /><JudgeSettings draft={draft} update={update} judge={judge} onRun={onRun} onResults={onResults} /></div>
    {judge.judgeId && <section className="recent-task" data-reveal>
      <div className="recent-task-title"><h2>当前任务</h2><button className="text-button" onClick={onResults}>查看结果 <ArrowUpRight size={14} /></button></div>
      <div className="recent-task-body"><div className="recent-task-label"><span className="mini-result-icon"><Check size={21} /></span><div><strong>{judge.progress ? <StatusBadge status={judge.progress.status} /> : '正在读取状态…'}</strong>{judge.progress && <p>已完成 {stats.completed} 组，通过 {stats.accepted} 组</p>}</div></div><div className="recent-progress"><span>{Math.round(judge.progress?.progress || 0)}%</span><ProgressBar value={judge.progress?.progress || 0} /></div><Button variant="ghost" onClick={onResults} aria-label="打开当前任务"><ArrowRight size={19} /></Button></div>
    </section>}
  </>;
}
