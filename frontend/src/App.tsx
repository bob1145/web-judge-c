import { Component, useEffect, useRef, useState, type ReactNode } from 'react';
import { BookOpen, ChevronRight, Code2, FlaskConical, Layers3, LogOut, Search, ShieldCheck, Waves } from 'lucide-react';
import { api, messageOf } from './lib/api';
import type { Bootstrap, SessionInfo, View } from './lib/types';
import type { CodeExample } from './lib/examples';
import { toRequest, validateDraft } from './lib/judge';
import { saveJudgeId } from './lib/storage';
import { useDraft } from './hooks/useDraft';
import { useJudge } from './hooks/useJudge';
import { Button, ErrorNotice, Logo, Modal, ToastProvider, useToast } from './components/ui';
import { MotionProvider, Reveal, useMotion } from './components/Motion';
import { CommandMenu } from './components/CommandMenu';
import { AuthPage } from './pages/AuthPage';
import { WorkspacePage } from './pages/WorkspacePage';
import { ResultsPage } from './pages/ResultsPage';
import { ExamplesPage } from './pages/ExamplesPage';
import { GuidePage } from './pages/GuidePage';

const navigation: { id: View; title: string; icon: typeof Code2 }[] = [
  { id: 'workspace', title: '对拍工作台', icon: Code2 },
  { id: 'results', title: '运行结果', icon: FlaskConical },
  { id: 'examples', title: '示例模板', icon: Layers3 },
  { id: 'guide', title: '使用指南', icon: BookOpen },
];
function initialView(): View {
  const hash = window.location.hash.replace('#', '');
  return navigation.find(item => item.id === hash)?.id || 'workspace';
}

function WorkspaceShell({ session, onLogout }: { session: SessionInfo; onLogout: () => Promise<void> }) {
  const [view, setView] = useState<View>(initialView);
  const [commandOpen, setCommandOpen] = useState(false);
  const [example, setExample] = useState<CodeExample | null>(null);
  const [logoutOpen, setLogoutOpen] = useState(false);
  const [logoutBusy, setLogoutBusy] = useState(false);
  const [online, setOnline] = useState(navigator.onLine);
  const draftState = useDraft();
  const judge = useJudge();
  const motion = useMotion();
  const notify = useToast();
  const runRef = useRef<() => void>(() => {});

  function navigate(next: View) {
    setView(next);
    window.location.hash = next;
    window.scrollTo({ top: 0, behavior: 'instant' });
  }
  function run() {
    if (judge.unresolved) { navigate('results'); return; }
    const error = validateDraft(draftState.draft);
    if (error) { notify(error, 'error'); return; }
    navigate('results');
    void judge.submit(toRequest(draftState.draft));
  }
  runRef.current = run;
  useEffect(() => {
    const keydown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); setCommandOpen(value => !value); }
      if ((event.ctrlKey || event.metaKey) && event.key === 'Enter' && !document.querySelector('dialog[open]')) { event.preventDefault(); runRef.current(); }
    };
    const hashchange = () => setView(initialView());
    const connection = () => setOnline(navigator.onLine);
    window.addEventListener('keydown', keydown);
    window.addEventListener('hashchange', hashchange);
    window.addEventListener('online', connection);
    window.addEventListener('offline', connection);
    return () => { window.removeEventListener('keydown', keydown); window.removeEventListener('hashchange', hashchange); window.removeEventListener('online', connection); window.removeEventListener('offline', connection); };
  }, []);

  function loadExample(candidate: CodeExample) {
    draftState.setDraft(current => ({ ...current, ...candidate.code, useSpecialJudge: false }));
    setExample(null);
    navigate('workspace');
    notify('示例已就绪，修改代码或直接开始对拍。');
  }
  function chooseExample(candidate: CodeExample) {
    if (draftState.draft.generatorCode || draftState.draft.userCode || draftState.draft.bruteForceCode) setExample(candidate);
    else loadExample(candidate);
  }
  async function logout() {
    if (logoutBusy) return;
    setLogoutBusy(true);
    try { await api.logout(); saveJudgeId(null); await onLogout(); }
    catch (error) { notify(messageOf(error), 'error'); setLogoutBusy(false); }
  }
  const currentNavigation = navigation.find(item => item.id === view)!;
  return <div className="app-shell"><a className="skip-link" href="#main-content" onClick={event => { event.preventDefault(); document.getElementById('main-content')?.focus(); }}>跳到主要内容</a><aside className="sidebar"><a className="brand-link" href="#workspace" aria-label="对拍工作台首页"><Logo /></a><nav className="main-navigation" aria-label="主要导航">{navigation.map(item => <a href={'#' + item.id} key={item.id} aria-label={item.title} aria-current={view === item.id ? 'page' : undefined} className={'nav-item ' + (view === item.id ? 'active' : '')} onClick={event => { event.preventDefault(); navigate(item.id); }}><item.icon size={19} strokeWidth={1.7} /><span>{item.title}</span>{item.id === 'results' && judge.running ? <span className="nav-live-dot" aria-label="任务进行中" /> : view === item.id ? <span className="nav-active-mark" /> : null}</a>)}</nav>
    <div className="sidebar-bottom"><div className="sidebar-account"><span className="account-avatar"><ShieldCheck size={21} /></span><div><strong>已验证会话</strong><span>{session.rememberMe ? '保持登录' : '当前设备'}</span></div><button className="icon-button" aria-label="退出登录" title="退出登录" onClick={() => setLogoutOpen(true)}><LogOut size={17} /></button></div></div>
  </aside><div className="main-shell"><header className="topbar"><div className="breadcrumb"><span>工作空间</span><ChevronRight size={14} /><strong>{currentNavigation.title}</strong></div><div className="topbar-actions"><button className="search-trigger" onClick={() => setCommandOpen(true)} aria-label="打开快捷导航"><Search size={16} /><span>快速查找</span><kbd>Ctrl K</kbd></button><span className="topbar-divider" /><button className="icon-button motion-button" aria-label={motion.enabled ? '关闭界面动效' : '开启界面动效'} aria-pressed={motion.enabled} title={motion.enabled ? '关闭界面动效' : '开启动效'} onClick={motion.toggle}><Waves size={19} /></button><button className="topbar-avatar" aria-label="会话信息与退出登录" onClick={() => setLogoutOpen(true)}><ShieldCheck size={18} /></button></div></header>
    <main id="main-content" tabIndex={-1} className="main-content">{!online && <div className="notice notice-warning" role="status">网络已断开。你的草稿仍在本地，任务状态将在恢复连接后同步。</div>}<Reveal identity={view}>
      {view === 'workspace' && <WorkspacePage {...draftState} judge={judge} onRun={run} onExamples={() => navigate('examples')} onResults={() => navigate('results')} />}
      {view === 'results' && <ResultsPage judge={judge} onWorkspace={() => navigate('workspace')} />}
      {view === 'examples' && <ExamplesPage onLoad={chooseExample} onWorkspace={() => navigate('workspace')} />}
      {view === 'guide' && <GuidePage onExamples={() => navigate('examples')} />}
    </Reveal></main>
  </div>
    {commandOpen && <CommandMenu onClose={() => setCommandOpen(false)} onNavigate={navigate} />}
    {example && <Modal title={'使用「' + example.title + '」？'} description="这会替换当前的数据生成器、参考程序与待测程序，并关闭自定义判题。现有任务不会受影响。" onClose={() => setExample(null)}><div className="modal-footer"><Button onClick={() => setExample(null)}>保留当前代码</Button><Button variant="primary" onClick={() => loadExample(example)}>确认加载示例</Button></div></Modal>}
    {logoutOpen && <Modal title="离开工作台？" description="退出后需要重新验证。此浏览器中的草稿会保留；不会主动取消正在运行的任务。" onClose={() => setLogoutOpen(false)}><div className="session-facts"><ShieldCheck size={18} /><span>{session.remainingMinutes != null ? '当前会话剩余约 ' + session.remainingMinutes + ' 分钟' : '会话已通过服务端验证'}</span></div><div className="modal-footer"><Button onClick={() => setLogoutOpen(false)}>留在这里</Button><Button variant="danger" loading={logoutBusy} onClick={() => void logout()}>确认退出</Button></div></Modal>}
  </div>;
}

function Application() {
  const [configuration, setConfiguration] = useState<Bootstrap | null>(null);
  const [session, setSession] = useState<SessionInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setError(null);
    setLoading(true);
    Promise.all([api.bootstrap(controller.signal), api.session(controller.signal)]).then(([config, information]) => {
      if (!controller.signal.aborted) { setConfiguration(config); setSession(information); }
    }).catch(reason => { if (!controller.signal.aborted) { setConfiguration(null); setSession(null); setError(messageOf(reason)); } }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    const expired = () => { setSession(null); setAttempt(value => value + 1); };
    window.addEventListener('judge:unauthorized', expired);
    return () => { controller.abort(); window.removeEventListener('judge:unauthorized', expired); };
  }, [attempt]);
  async function authenticated() {
    const information = await api.session();
    if (!information.valid) throw new Error('Invalid session');
    if (window.location.pathname === '/auth') window.history.replaceState(null, '', '/#workspace');
    setSession(information);
  }
  async function loggedOut() { setSession(null); setAttempt(value => value + 1); }
  if (loading) return <div className="app-loading"><Logo /><div className="loading-line" /><p>正在准备你的工作台…</p></div>;
  return session?.valid ? <WorkspaceShell session={session} onLogout={loggedOut} /> : <AuthPage configuration={configuration} error={error} onRetry={() => setAttempt(value => value + 1)} onAuthenticated={authenticated} />;
}

class AppBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    return this.state.failed ? <main className="app-loading"><Logo /><ErrorNotice>页面暂时无法显示。已保存的草稿不会丢失。</ErrorNotice><Button onClick={() => window.location.reload()}>重新加载</Button></main> : this.props.children;
  }
}

export default function App() {
  return <AppBoundary><MotionProvider><ToastProvider><Application /></ToastProvider></MotionProvider></AppBoundary>;
}
