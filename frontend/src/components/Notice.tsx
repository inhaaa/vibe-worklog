import type { ReactNode } from 'react';

/** 안내(설계 7.8): g50 바탕에 정보 아이콘과 한 문장. */
export function Notice({ children }: { children: ReactNode }) {
  return (
    <p className="wl-notice">
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><circle cx="12" cy="12" r="8.5" /><path d="M12 11v5.5M12 7.6h.01" /></svg>
      <span>{children}</span>
    </p>
  );
}
