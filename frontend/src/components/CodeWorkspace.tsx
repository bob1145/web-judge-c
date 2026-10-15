import { lazy, Suspense, useRef, useState, type KeyboardEvent } from 'react';
import { Check, CheckCheck, Copy, FileCode2, Maximize2, Upload } from 'lucide-react';
import type { CodeKey, WorkspaceDraft } from '../lib/types';
import { Button, Modal, useToast } from './ui';

const CodeEditor = lazy(() => import('./CodeEditor'));
const labels: Record<CodeKey, { title: string; file: string; hint: string }> = {
  generatorCode: { title: '数据生成器', file: 'generator.cpp', hint: '生成一组测试输入，输出到标准输出。' },
  bruteForceCode: { title: '参考程序', file: 'reference.cpp', hint: '输出每组输入的预期答案。' },
  userCode: { title: '待测程序', file: 'solution.cpp', hint: '填写需要验证的程序。' },
  specialJudgeCode: { title: '自定义判题', file: 'checker.cpp', hint: '按服务端 Special Judge 约定校验输出。详见使用指南。' },
};

export function CodeWorkspace({ draft, update, saved }: { draft: WorkspaceDraft; update: <Key extends keyof WorkspaceDraft>(key: Key, value: WorkspaceDraft[Key]) => void; saved: boolean }) {
  const [selected, setSelected] = useState<CodeKey>('userCode');
  const [expanded, setExpanded] = useState(false);
  const [copied, setCopied] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const tablist = useRef<HTMLDivElement>(null);
  const notify = useToast();
  const tabs: CodeKey[] = ['generatorCode', draft.useSpecialJudge ? 'specialJudgeCode' : 'bruteForceCode', 'userCode'];
  const active = tabs.includes(selected) ? selected : tabs[1];
  const metadata = labels[active];
  const code = draft[active];
  async function copy() {
    try { await navigator.clipboard.writeText(code); setCopied(true); setTimeout(() => setCopied(false), 1800); }
    catch { notify('复制失败，请在编辑器中选择代码后复制。', 'error'); }
  }
  async function upload(file?: File) {
    if (!file) return;
    if (file.size > 1024 * 1024) { notify('请选择不超过 1 MB 的源代码文件。', 'error'); return; }
    try {
      const text = await file.text();
      if (text.includes('\0')) throw new Error('Not a text file');
      update(active, text);
      notify('代码已导入 ' + metadata.title);
    } catch { notify('无法读取文件，请选择 UTF-8 文本文件。', 'error'); }
  }
  function tabKey(event: KeyboardEvent<HTMLDivElement>) {
    const index = tabs.indexOf(active);
    const next = event.key === 'ArrowRight' ? (index + 1) % tabs.length : event.key === 'ArrowLeft' ? (index + tabs.length - 1) % tabs.length : event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : -1;
    if (next < 0) return;
    event.preventDefault();
    setSelected(tabs[next]);
    tablist.current?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus();
  }
  const editor = (full = false) => <Suspense fallback={<div className="editor-loading">正在准备编辑器…</div>}><CodeEditor value={code} onChange={value => update(active, value)} label={metadata.title + '代码'} expanded={full} /></Suspense>;
  return <section className="editor-card" aria-label="代码工作区" data-reveal>
    <div className="editor-tabs" ref={tablist} role="tablist" aria-label="选择代码文件" onKeyDown={tabKey}>
      {tabs.map((key, index) => <button key={key} id={'tab-' + key} type="button" role="tab" title={labels[key].hint} aria-selected={active === key} aria-controls="code-panel" tabIndex={active === key ? 0 : -1} className={'editor-tab ' + (active === key ? 'active' : '')} onClick={() => setSelected(key)}>
        <span className="tab-number">0{index + 1}</span>{labels[key].title}{draft[key].trim() && <span className="tab-ready" aria-label="已填写"><Check size={11} /></span>}
      </button>)}
    </div>
    <div className="editor-filebar"><span><FileCode2 size={15} />{metadata.file}</span><div className="editor-actions">
      <input ref={fileInput} type="file" accept=".cpp,.cc,.c,.cxx,.h,.hpp,.txt" className="visually-hidden" aria-label="导入代码文件" tabIndex={-1} onChange={event => { void upload(event.target.files?.[0]); event.target.value = ''; }} />
      <button className="icon-button" aria-label="导入代码文件" title="导入源代码" onClick={() => fileInput.current?.click()}><Upload size={16} /></button>
      <button className="icon-button" aria-label={copied ? '已复制代码' : '复制当前代码'} title="复制代码" onClick={() => void copy()} disabled={!code}>{copied ? <CheckCheck size={16} /> : <Copy size={16} />}</button>
      <button className="icon-button" aria-label="展开代码编辑器" title="专注编辑" onClick={() => setExpanded(true)}><Maximize2 size={16} /></button>
    </div></div>
    <div id="code-panel" role="tabpanel" aria-labelledby={'tab-' + active} tabIndex={0}>{editor()}</div>
    <div className="editor-status"><span><span className={'status-dot ' + (saved ? 'saved' : '')} />{saved ? '草稿已保存在此浏览器' : '保存中或存储不可用'}</span><span>{code ? code.split('\n').length : 0} 行 <span className="status-separator">·</span> UTF-8 <span className="status-separator">·</span> C++</span></div>

    {expanded && <Modal title={metadata.file} description="按 Escape 返回工作台。" onClose={() => setExpanded(false)} wide>{editor(true)}<div className="modal-footer"><Button onClick={() => setExpanded(false)}>完成编辑</Button></div></Modal>}
  </section>;
}
