import { useMemo, useState } from 'react';
import { ArrowUpRight, BookOpen, Code2, FlaskConical, Layers3, Search } from 'lucide-react';
import type { View } from '../lib/types';
import { Modal } from './ui';

const commands: { view: View; title: string; description: string; icon: typeof Code2 }[] = [
  { view: 'workspace', title: '对拍工作台', description: '编辑代码与运行参数', icon: Code2 },
  { view: 'results', title: '运行结果', description: '查看进度与测试点', icon: FlaskConical },
  { view: 'examples', title: '示例模板', description: '从可运行的示例开始', icon: Layers3 },
  { view: 'guide', title: '使用指南', description: '了解流程与快捷键', icon: BookOpen },
];
export function CommandMenu({ onClose, onNavigate }: { onClose: () => void; onNavigate: (view: View) => void }) {
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState(0);
  const filtered = useMemo(() => commands.filter(command => (command.title + command.description).includes(query.trim())), [query]);
  function run(view: View) { onClose(); onNavigate(view); }
  return <Modal title="想去哪里？" onClose={onClose}><div className="command-input"><Search size={20} /><input autoFocus aria-label="搜索功能" placeholder="搜索工作台、示例、使用指南…" value={query} onChange={event => { setQuery(event.target.value); setSelected(0); }} role="combobox" aria-expanded="true" aria-controls="command-options" aria-activedescendant={filtered.length ? 'command-' + filtered[selected]?.view : undefined} onKeyDown={event => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); setSelected(value => filtered.length ? (value + (event.key === 'ArrowDown' ? 1 : filtered.length - 1)) % filtered.length : 0); }
    if (event.key === 'Enter' && filtered[selected]) { event.preventDefault(); run(filtered[selected].view); }
  }} /></div><div className="command-options" id="command-options" role="listbox" aria-label="功能列表">{filtered.map((command, index) => <button type="button" tabIndex={-1} key={command.view} role="option" aria-selected={selected === index} id={'command-' + command.view} className={'command-item ' + (selected === index ? 'selected' : '')} onClick={() => run(command.view)} onPointerMove={() => setSelected(index)}><command.icon size={20} /><span><strong>{command.title}</strong><small>{command.description}</small></span><ArrowUpRight size={16} /></button>)}{!filtered.length && <p className="table-empty">没有找到匹配功能。</p>}</div><p className="command-footer"><kbd>↑</kbd><kbd>↓</kbd> 选择 <kbd>Enter</kbd> 打开 <kbd>Esc</kbd> 关闭</p></Modal>;
}
