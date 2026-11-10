import { useEffect, useId, useRef, useState } from 'react';
import type { ReactNode } from 'react';

interface PageHeaderProps {
  title: ReactNode;
  /** 화면 설명. 문서에는 남기고 도움말 버튼을 눌렀을 때만 보인다. */
  help?: ReactNode;
  /** 화면 단위 동작(링크·버튼). */
  actions?: ReactNode;
}

export function PageHeader({ title, help, actions }: PageHeaderProps) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const root = useRef<HTMLElement>(null);
  const button = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      button.current?.focus();
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  return (
    <header ref={root} className="wl-pagehead">
      <div className="wl-pagehead-title">
        <h1>{title}</h1>
        {help && <>
          <button ref={button} type="button" className="wl-help-button" aria-label="도움말"
            aria-expanded={open} aria-controls={panelId} onClick={() => setOpen(!open)}>
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <circle cx="12" cy="12" r="8.5" />
              <path d="M9.6 9.4a2.5 2.5 0 0 1 4.8.9c0 1.7-2.4 2.2-2.4 3.4M12 16.8h.01" />
            </svg>
          </button>
          <div id={panelId} className="wl-help-panel" hidden={!open}>{help}</div>
        </>}
      </div>
      {actions && <div className="wl-pagehead-actions">{actions}</div>}
    </header>
  );
}
