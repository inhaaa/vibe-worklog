import { useId } from 'react';
import { Modal } from './Modal';

export interface SheetChangeCounts { create: number; update: number; deactivate: number }

/** 저장할 변경 행을 신규·수정·비활성화로 센다. */
export function sheetChangeCounts(rows: readonly { id?: string | null; operation: string }[]): SheetChangeCounts {
  const create = rows.filter(row => row.operation === 'CREATE' || !row.id).length;
  const deactivate = rows.filter(row => row.operation === 'DEACTIVATE').length;
  return { create, update: rows.length - create - deactivate, deactivate };
}

/**
 * 일괄 저장 확인 창. 사유 칸과 저장 버튼은 HTML form 속성으로 시트 폼에 붙는다.
 * 제출 필드·검증·commandId·50행 제한·충돌 차단은 시트 폼의 제출 처리를 그대로 탄다.
 */
export function SheetSaveDialog({ formId, label, reason, error, counts, busy = false, onReasonChange, onClose }: {
  formId: string; label: string; reason: string; error?: string; counts: SheetChangeCounts; busy?: boolean;
  onReasonChange: (value: string) => void; onClose: () => void;
}) {
  const id = useId();
  const total = counts.create + counts.update + counts.deactivate;
  return <Modal title="변경 내용 저장" size="s" busy={busy} onClose={onClose} footer={<>
    <button type="button" className="t-btn" disabled={busy} onClick={onClose}>취소</button>
    <button type="submit" form={formId} className="t-btn primary" disabled={busy}>{total}건 저장</button>
  </>}>
    <ul className="wl-save-counts" aria-label="저장할 변경">
      {counts.create > 0 && <li className="t-tag green">신규 {counts.create}건</li>}
      {counts.update > 0 && <li className="t-tag blue">수정 {counts.update}건</li>}
      {counts.deactivate > 0 && <li className="t-tag red">비활성화 {counts.deactivate}건</li>}
    </ul>
    <div className="wl-save-reason">
      <label className="wl-required" htmlFor={`${id}-reason`}>{label}</label>
      <textarea id={`${id}-reason`} className="t-input" form={formId} name="reason" value={reason} rows={4} maxLength={500} required disabled={busy}
        aria-invalid={error ? true : undefined} aria-describedby={error ? `${id}-error ${id}-count` : `${id}-count`}
        data-error-focus={error ? 'true' : undefined} onChange={event => onReasonChange(event.target.value)} />
      <div className="wl-save-reason-meta">
        {error
          ? <p id={`${id}-error`} className="wl-field-error">{error}</p>
          : <p>변경한 모든 행에 적용됩니다. 500자 이내로 입력하고 불필요한 개인정보는 적지 마세요.</p>}
        <span id={`${id}-count`} className="wl-char-count">{reason.length}/500</span>
      </div>
    </div>
  </Modal>;
}
