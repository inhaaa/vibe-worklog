import { useId, useLayoutEffect, useRef } from 'react';
import type { KeyboardEvent, ReactNode } from 'react';
import { createPortal } from 'react-dom';
import './Modal.css';

interface ModalProps {
  title: string;
  children: ReactNode;
  onClose: () => void;
  busy?: boolean;
  className?: string;
  footer?: ReactNode;
  /** s 440, m 720, l 920, xl 1200(px). 2단계는 s만 스타일을 둔다. 지정하지 않으면 기존 너비. */
  size?: 's' | 'm' | 'l' | 'xl';
}

export function Modal({ title, children, onClose, busy = false, className = '', footer, size }: ModalProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const titleId = useId();

  useLayoutEffect(() => {
    const dialog = dialogRef.current!;
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const triggerHref = trigger?.getAttribute('href');
    const triggerLabel = trigger?.getAttribute('aria-label');
    const overflow = document.body.style.overflow;
    dialog.showModal();
    document.body.style.overflow = 'hidden';
    titleRef.current?.focus({ preventScroll: true });
    return () => {
      dialog.close();
      document.body.style.overflow = overflow;
      const restored = trigger?.isConnected ? trigger : triggerHref
        ? Array.from(document.querySelectorAll<HTMLAnchorElement>('main a[href]')).find(link =>
          link.getAttribute('href') === triggerHref && link.getAttribute('aria-label') === triggerLabel)
        : null;
      const target = restored ?? document.querySelector<HTMLElement>('main h1');
      if (target?.tagName === 'H1') target.tabIndex = -1;
      target?.focus({ preventScroll: true });
    };
  }, []);

  function trapFocus(event: KeyboardEvent<HTMLDialogElement>) {
    if (event.key !== 'Tab' || event.altKey || event.ctrlKey || event.metaKey) return;
    const focusable = Array.from(event.currentTarget.querySelectorAll<HTMLElement>(
      'a[href], button, input:not([type="hidden"]), select, textarea, summary, [contenteditable="true"], [tabindex]',
    )).filter(element => element.tabIndex >= 0 && !element.matches(':disabled') &&
      !element.closest('[hidden], [inert]') && element.getClientRects().length > 0 &&
      getComputedStyle(element).visibility === 'visible');
    const first = focusable[0], last = focusable[focusable.length - 1];
    if (!first) {
      event.preventDefault();
      titleRef.current?.focus();
    } else if (event.shiftKey
      ? document.activeElement === first || document.activeElement === titleRef.current
      : document.activeElement === last) {
      event.preventDefault();
      (event.shiftKey ? last : first).focus();
    }
  }

  return createPortal(
    <dialog ref={dialogRef} className={`t-modal ${className}`.trim()} data-size={size} aria-labelledby={titleId}
      aria-modal="true" aria-busy={busy} onKeyDown={trapFocus}
      onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}>
      <header className="t-modal-header">
        <h2 id={titleId} ref={titleRef} tabIndex={-1} className="t-modal-title">{title}</h2>
        <button type="button" className="t-btn t-modal-close" aria-label="닫기" disabled={busy} onClick={onClose}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true" focusable="false"><path d="m6 6 12 12M18 6 6 18" /></svg>
        </button>
      </header>
      <div className="t-modal-body">{children}</div>
      <footer className="t-modal-footer">
        {footer ?? <button type="button" className="t-btn t-modal-dismiss" disabled={busy} onClick={onClose}>닫기</button>}
      </footer>
    </dialog>,
    document.getElementById('tna-app') ?? document.body,
  );
}
