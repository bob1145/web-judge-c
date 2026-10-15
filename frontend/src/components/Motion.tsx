import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';

gsap.registerPlugin(useGSAP);
const MotionContext = createContext({ enabled: true, toggle: () => {} });
export const useMotion = () => useContext(MotionContext);

export function MotionProvider({ children }: { children: ReactNode }) {
  const [enabled, setEnabled] = useState(() => !window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  useEffect(() => {
    const media = window.matchMedia('(prefers-reduced-motion: reduce)');
    const change = () => setEnabled(!media.matches);
    media.addEventListener('change', change);
    return () => media.removeEventListener('change', change);
  }, []);
  return <MotionContext.Provider value={{ enabled, toggle: () => setEnabled(value => !value) }}><div data-motion={enabled ? 'on' : 'off'}>{children}</div></MotionContext.Provider>;
}

export function Reveal({ children, identity }: { children: ReactNode; identity: string }) {
  const container = useRef<HTMLDivElement>(null);
  const { enabled } = useMotion();
  useGSAP(() => {
    if (!enabled) return;
    gsap.from('[data-reveal]', { y: 16, autoAlpha: 0, duration: 0.5, stagger: 0.055, ease: 'power2.out', clearProps: 'all' });
  }, { scope: container, dependencies: [identity, enabled], revertOnUpdate: true });
  return <div ref={container} className="view-content">{children}</div>;
}

export function HeroArt({ className = '' }: { className?: string }) {
  const container = useRef<HTMLDivElement>(null);
  const image = useRef<HTMLImageElement>(null);
  const { enabled } = useMotion();
  useGSAP(() => {
    if (!enabled || !container.current || !window.matchMedia('(pointer: fine)').matches) return;
    const element = container.current;
    const moveX = gsap.quickTo(image.current, 'x', { duration: 0.8, ease: 'power3.out' });
    const moveY = gsap.quickTo(image.current, 'y', { duration: 0.8, ease: 'power3.out' });
    const move = (event: PointerEvent) => {
      const rect = element.getBoundingClientRect();
      moveX((event.clientX - rect.left - rect.width / 2) * 0.045);
      moveY((event.clientY - rect.top - rect.height / 2) * 0.045);
    };
    const reset = () => { moveX(0); moveY(0); };
    element.addEventListener('pointermove', move);
    element.addEventListener('pointerleave', reset);
    return () => { element.removeEventListener('pointermove', move); element.removeEventListener('pointerleave', reset); };
  }, { scope: container, dependencies: [enabled], revertOnUpdate: true });
  return <div ref={container} className={'hero-art ' + className}><img ref={image} src={import.meta.env.BASE_URL + 'images/judge-illustration.svg'} width="680" height="470" alt="代码窗口与绿色校验标记组成的立体插画" draggable="false" /></div>;
}

export function ProgressBar({ value }: { value: number }) {
  const fill = useRef<HTMLSpanElement>(null);
  const { enabled } = useMotion();
  const percent = Math.min(100, Math.max(0, value));
  useGSAP(() => { gsap.to(fill.current, { scaleX: percent / 100, duration: enabled ? 0.6 : 0, ease: 'power2.out', overwrite: true }); }, { dependencies: [percent, enabled], scope: fill });
  return <div className="progress-track" role="progressbar" aria-label="任务进度" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}><span ref={fill} style={{ transform: 'scaleX(0)' }} /></div>;
}
