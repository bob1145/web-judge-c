import { createContext, useContext, useEffect, useId, useRef, useState, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { AlertCircle, Check, LoaderCircle, X } from 'lucide-react';
import { statusLabel, normalizeStatus } from '../lib/judge';

export function Button({ children, className = '', variant = 'secondary', loading, ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'ghost' | 'danger'; loading?: boolean }) {
  return <button type="button" {...props} disabled={props.disabled || loading} className={'button button-' + variant + ' ' + className}>
    {loading && <LoaderCircle size={16} className="spin" aria-hidden="true" />}{children}
  </button>;
}

export function Logo({ compact = false }: { compact?: boolean }) {
  return <span className="brand"><img src={import.meta.env.BASE_URL + 'images/logo.svg'} alt="" width="38" height="38" />
    {!compact && <span className="brand-wordmark">对拍<span>C++ JUDGE</span></span>}
  </span>;
}

export function StatusBadge({ status }: { status: string }) {
  const normalized = normalizeStatus(status);
  const tone = normalized === 'AC' || normalized === 'COMPLETED' ? 'success' : ['CREATED', 'QUEUED', 'PENDING', 'COMPILING', 'RUNNING'].includes(normalized) ? 'info' : normalized === 'CANCELLED' || normalized === 'STALE' ? 'neutral' : 'danger';
  return <span className={'badge badge-' + tone}><span className="status-dot" />{statusLabel(status)}</span>;
}

export function Modal({ title, description, onClose, children, wide = false }: { title: string; description?: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descriptionId = useId();
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const dialog = dialogRef.current;
    dialog?.showModal();
    return () => { dialog?.close(); previous?.focus(); };
  }, []);
  return <dialog ref={dialogRef} className={'modal ' + (wide ? 'modal-wide' : '')} aria-labelledby={titleId} aria-describedby={description ? descriptionId : undefined}
    onCancel={event => { event.preventDefault(); onCloseRef.current(); }} onClick={event => {
      if (event.target === dialogRef.current) {
        const bounds = dialogRef.current.getBoundingClientRect();
        if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) onCloseRef.current();
      }
    }}>
    <header className="modal-header"><div><h2 id={titleId}>{title}</h2>{description && <p id={descriptionId}>{description}</p>}</div>
      <button className="icon-button" aria-label="关闭弹窗" onClick={onClose}><X size={20} /></button></header>
    <div className="modal-body">{children}</div>
  </dialog>;
}

const ToastContext = createContext<(message: string, kind?: 'success' | 'error') => void>(() => {});
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<{ message: string; kind: 'success' | 'error'; id: number } | null>(null);
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(null), toast.kind === 'error' ? 8000 : 4000);
    return () => clearTimeout(timer);
  }, [toast]);
  return <ToastContext.Provider value={(message, kind = 'success') => setToast({ message, kind, id: Date.now() })}>
    {children}<div className="toast-region" aria-live="polite" aria-atomic="true">{toast && <div className={'toast toast-' + toast.kind} key={toast.id}>
      {toast.kind === 'success' ? <Check size={18} /> : <AlertCircle size={18} />}<span>{toast.message}</span><button className="icon-button" aria-label="关闭提示" onClick={() => setToast(null)}><X size={16} /></button>
    </div>}</div>
  </ToastContext.Provider>;
}
export const useToast = () => useContext(ToastContext);

export function ErrorNotice({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return <div className="notice notice-error" role="alert"><AlertCircle size={18} /><div>{children}</div>{action}</div>;
}
