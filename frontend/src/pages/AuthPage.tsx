import { useRef, useState } from 'react';
import { ArrowRight, Eye, EyeOff, Fingerprint, LockKeyhole, ShieldCheck } from 'lucide-react';
import { api, messageOf } from '../lib/api';
import type { Bootstrap } from '../lib/types';
import { Button, ErrorNotice, Logo } from '../components/ui';
import { HeroArt, Reveal } from '../components/Motion';

export function AuthPage({ configuration, error, onRetry, onAuthenticated }: { configuration: Bootstrap | null; error: string | null; onRetry: () => void; onAuthenticated: () => Promise<void> }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [accessCode, setAccessCode] = useState('');
  const [rememberMe, setRememberMe] = useState(false);
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const lock = useRef(false);
  const accountMode = configuration?.accountAuthRequired;
  async function login(event: React.FormEvent) {
    event.preventDefault();
    if (lock.current || !configuration) return;
    lock.current = true;
    setBusy(true);
    setFailure(null);
    try {
      await api.login(accountMode ? { username: username.trim(), password, rememberMe } : { accessCode: accessCode.trim(), rememberMe });
      setPassword(''); setAccessCode('');
      await onAuthenticated();
    } catch (reason) { setFailure(messageOf(reason)); }
    finally { lock.current = false; setBusy(false); }
  }
  return <main className="auth-page"><div className="auth-story"><a href="/" className="brand-link" aria-label="对拍首页"><Logo /></a>
    <Reveal identity="auth"><div className="auth-story-content" data-reveal><HeroArt className="auth-illustration" /></div></Reveal>

  </div><section className="auth-form-side" aria-labelledby="auth-heading"><div className="auth-form-container"><div className="auth-icon"><Fingerprint size={27} strokeWidth={1.5} /></div><h1 id="auth-heading">登录</h1>
    {error && <ErrorNotice action={<Button onClick={onRetry}>重试连接</Button>}>{error}</ErrorNotice>}
    <form onSubmit={event => void login(event)}>
      {accountMode ? <><label className="field-label" htmlFor="username">用户名</label><input id="username" className="input auth-input" autoComplete="username" required value={username} onChange={event => setUsername(event.target.value)} placeholder="输入用户名" maxLength={128} />
        <label className="field-label" htmlFor="password">密码</label><div className="password-field"><LockKeyhole size={18} /><input id="password" className="input auth-input" type={visible ? 'text' : 'password'} autoComplete="current-password" required value={password} onChange={event => setPassword(event.target.value)} placeholder="输入密码" /><button type="button" className="icon-button" aria-label={visible ? '隐藏密码' : '显示密码'} onClick={() => setVisible(value => !value)}>{visible ? <EyeOff size={18} /> : <Eye size={18} />}</button></div></> : <><label className="field-label" htmlFor="access-code">访问码</label><div className="password-field"><LockKeyhole size={18} /><input id="access-code" className="input auth-input" type={visible ? 'text' : 'password'} autoComplete="current-password" required value={accessCode} onChange={event => setAccessCode(event.target.value)} placeholder="输入你的访问码" maxLength={512} /><button type="button" className="icon-button" aria-label={visible ? '隐藏访问码' : '显示访问码'} onClick={() => setVisible(value => !value)}>{visible ? <EyeOff size={18} /> : <Eye size={18} />}</button></div></>}
      <label className="remember-label"><input type="checkbox" checked={rememberMe} onChange={event => setRememberMe(event.target.checked)} />在此设备保持登录</label>
      {failure && <ErrorNotice>{failure}</ErrorNotice>}
      <Button type="submit" className="auth-submit" variant="primary" loading={busy} disabled={!configuration}>{busy ? '正在验证' : '进入工作台'}{!busy && <ArrowRight size={18} />}</Button>
    </form><div className="auth-security"><ShieldCheck size={17} /><span>密码与访问码不会写入本地存储。</span></div><div className="auth-help">还没有{accountMode ? '账号' : '访问码'}？请联系服务管理员。</div>
  </div></section></main>;
}

