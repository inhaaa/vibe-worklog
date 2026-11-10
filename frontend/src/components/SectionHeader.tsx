import type { ReactNode } from 'react';

/** 구역 머리: 제목(15/700)·총 건수 | 구역 동작. 시트·목록·폼 구역이 함께 쓴다. */
export function SectionHeader({ title, count, actions }: { title?: ReactNode; count?: number; actions?: ReactNode }) {
  return <div className="wl-section-head">
    <div className="wl-section-title">
      {title && <h2>{title}</h2>}
      {count !== undefined && <span className="wl-count">총 <b>{count.toLocaleString('ko-KR')}</b>건</span>}
    </div>
    {actions && <div className="wl-section-actions">{actions}</div>}
  </div>;
}
