import { useState } from 'react';
import { ArrowLeft, ArrowUpRight, Braces, Layers3, Search } from 'lucide-react';
import { examples, type CodeExample } from '../lib/examples';
import { Button } from '../components/ui';

export function ExamplesPage({ onLoad, onWorkspace }: { onLoad: (example: CodeExample) => void; onWorkspace: () => void }) {
  const [query, setQuery] = useState('');
  const filtered = examples.filter(example => (example.title + example.category + example.description).includes(query.trim()));
  return <><div className="page-heading" data-reveal><div><h1>示例模板</h1><p>示例仅填入编辑器，不会自动运行。</p></div><Button onClick={onWorkspace}><ArrowLeft size={16} />回到代码</Button></div>
    <div className="examples-toolbar" data-reveal><span><Layers3 size={18} />示例 <small>{examples.length}</small></span><label className="table-search"><Search size={16} /><input aria-label="搜索示例" placeholder="搜索示例…" value={query} onChange={event => setQuery(event.target.value)} /></label></div>
    <div className="examples-grid">{filtered.map((example, index) => <article className="example-card" key={example.id} data-reveal><div className={'example-art example-art-' + example.id} aria-hidden="true"><span className="example-art-grid" /><span className="example-art-symbol">{example.id === 'sum' ? 'a + b' : example.id === 'overflow' ? '2³¹' : '[1, 2, 3]'}</span><span className="example-art-code"><Braces size={20} /></span><span className="example-art-index">0{index + 1}</span></div><div className="example-card-content"><span className="example-category">{example.category}<span>{example.difficulty}</span></span><h2>{example.title}</h2><p>{example.description}</p><button className="text-button" onClick={() => onLoad(example)}>使用这个示例 <ArrowUpRight size={16} /></button></div></article>)}</div>
    {!filtered.length && <div className="table-empty">没有找到匹配的示例，换个关键词试试。</div>}

  </>;
}
