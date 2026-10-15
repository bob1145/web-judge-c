import { ArrowRight, ChevronDown, CircleHelp, Play, SlidersHorizontal } from 'lucide-react';
import type { WorkspaceDraft } from '../lib/types';
import type { JudgeState } from '../hooks/useJudge';
import { Button } from './ui';

export function JudgeSettings({ draft, update, judge, onRun, onResults }: { draft: WorkspaceDraft; update: <Key extends keyof WorkspaceDraft>(key: Key, value: WorkspaceDraft[Key]) => void; judge: JudgeState; onRun: () => void; onResults: () => void }) {
  return <section className="settings-card" aria-labelledby="settings-heading" data-reveal>
    <div className="section-card-heading"><h3 id="settings-heading"><SlidersHorizontal size={17} />对拍设置</h3><span className="subtle-label">按需调整</span></div>
    <div className="settings-fields">
      <label className="field-label" htmlFor="test-cases">测试数量 <span className="field-unit">组</span></label>
      <input id="test-cases" className="input number-input" type="number" min={1} step={1} value={draft.testCases || ''} onChange={event => update('testCases', Number(event.target.value))} />
      <div className="case-presets" aria-label="测试数量快捷选择">{[10, 100, 1000].map(count => <button type="button" key={count} aria-pressed={draft.testCases === count} className={draft.testCases === count ? 'selected' : ''} onClick={() => update('testCases', count)}>{count.toLocaleString()} 组</button>)}</div>
      <div className="field-columns"><div><label className="field-label" htmlFor="time-limit">时间限制 <span className="field-unit">ms</span></label><input id="time-limit" type="number" min={1} step={100} className="input" value={draft.timeLimit || ''} onChange={event => update('timeLimit', Number(event.target.value))} /></div>
        <div><label className="field-label" htmlFor="memory-limit">内存限制 <span className="field-unit">MB</span></label><input id="memory-limit" type="number" min={1} className="input" value={draft.memoryLimitMb || ''} onChange={event => update('memoryLimitMb', Number(event.target.value))} /></div></div>
      <div className="switch-row"><label htmlFor="stop-on-error"><span>遇到错误就停止</span><small>停止新增测试，已启动的会完成</small></label><input id="stop-on-error" type="checkbox" role="switch" className="switch" checked={draft.stopOnFirstNonAc} onChange={event => update('stopOnFirstNonAc', event.target.checked)} /></div>
      <details className="advanced-settings"><summary>高级设置 <ChevronDown size={16} /></summary><div className="advanced-fields">
        <label className="field-label" htmlFor="precision">比较精度 <span title="0 表示精确比较；例如 1e-9 表示允许浮点误差。"><CircleHelp size={14} /></span></label><input className="input" id="precision" value={draft.precision} onChange={event => update('precision', event.target.value)} aria-describedby="precision-hint" /><small id="precision-hint" className="field-hint">0 为精确比较，浮点数可使用 1e-9。</small>
        <div className="switch-row"><label htmlFor="special-judge"><span>Special Judge</span><small>使用自定义判题程序</small></label><input className="switch" id="special-judge" type="checkbox" role="switch" checked={draft.useSpecialJudge} onChange={event => update('useSpecialJudge', event.target.checked)} /></div>
      </div></details>
    </div>
    <div className="run-actions"><Button variant="primary" className="run-button" loading={judge.busy} onClick={judge.unresolved ? onResults : onRun}>
      {!judge.busy && (judge.unresolved ? <ArrowRight size={17} /> : <Play size={17} fill="currentColor" />)}{judge.busy ? '正在提交' : judge.unresolved ? '查看当前任务' : '开始对拍'}{!judge.unresolved && <kbd>Ctrl ↵</kbd>}
    </Button><p>资源上限以当前服务端策略为准。</p></div>
  </section>;
}
