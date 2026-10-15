import { useMemo } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { cpp } from '@codemirror/lang-cpp';
import { EditorView } from '@codemirror/view';
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { tags } from '@lezer/highlight';

const highlight = HighlightStyle.define([
  { tag: tags.keyword, color: '#715477' },
  { tag: [tags.typeName, tags.className], color: '#326979' },
  { tag: [tags.string, tags.character], color: '#95563f' },
  { tag: [tags.number, tags.bool], color: '#296d62' },
  { tag: tags.comment, color: '#5c6b53', fontStyle: 'italic' },
  { tag: tags.function(tags.variableName), color: '#425980' },
  { tag: [tags.operator, tags.punctuation], color: '#59694f' },
  { tag: tags.meta, color: '#496e66' },
]);

export default function CodeEditor({ value, onChange, label, expanded = false }: { value: string; onChange: (value: string) => void; label: string; expanded?: boolean }) {
  const extensions = useMemo(() => [cpp(), syntaxHighlighting(highlight), EditorView.contentAttributes.of({ 'aria-label': label, spellcheck: 'false' }), EditorView.theme({
    '&': { fontSize: '13px', backgroundColor: '#fcfdfa', color: '#303d36' },
    '.cm-content': { fontFamily: '"JetBrains Mono", "Cascadia Code", Consolas, monospace', padding: '18px 0', minHeight: '100%' },
    '.cm-scroller': { overflow: 'auto', lineHeight: '1.85' },
    '.cm-gutters': { backgroundColor: '#fcfdfa', color: '#667561', border: 'none', paddingRight: '12px' },
    '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: '#f0f4ed' },
    '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: '#dceee3' },
    '&.cm-focused': { outline: 'none' },
    '.cm-placeholder': { color: '#617058' },
    '.cm-cursor': { borderLeftColor: '#287a68' },
  })], [label]);
  return <CodeMirror value={value} height={expanded ? '65vh' : '340px'} extensions={extensions} onChange={onChange}
    placeholder="粘贴 C++ 代码…"
    basicSetup={{ lineNumbers: true, foldGutter: true, highlightActiveLine: true, autocompletion: true, bracketMatching: true }} theme="light" />;
}
