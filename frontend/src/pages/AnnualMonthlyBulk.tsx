import { useRef, useState } from 'react';
import { commandReason } from '../commandReason';
import { PageRequestError, requestJson } from '../transport';
import type { CsrfToken } from '../types';
import { FormRow, FormTable, fmtDate } from '../ui';
import { AnnualLeaveTable } from './AnnualLeaveTable';
import type { MonthlyBulkDecision, MonthlyBulkPreview } from './annualLeaveTypes';

const base = '/admin/leave-balances/annual-cycle';
const decisionNames: Record<MonthlyBulkDecision, string> = { FULL: '개근', NOT_FULL: '미개근', NEEDS_REVIEW: '확인 필요' };

export function AnnualMonthlyBulk({ csrf, publicDemo }: { csrf: CsrfToken; publicDemo: boolean }) {
  const [preview, setPreview] = useState<MonthlyBulkPreview>();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ text: string; error?: boolean }>();
  const running = useRef(false);
  const checked = commandReason(reason);
  const decided = preview?.rows.filter(row => row.decision !== 'NEEDS_REVIEW').length ?? 0;
  async function run(action: () => Promise<void>) {
    if (running.current) return;
    running.current = true; setBusy(true); setMessage(undefined);
    try { await action(); }
    catch (failure) {
      setMessage({ error: true, text: failure instanceof PageRequestError && failure.status < 500 ? failure.message
        : '서버의 처리 결과를 확인할 수 없습니다. 월 연차 미리보기를 다시 확인하세요.' });
    } finally { running.current = false; setBusy(false); }
  }
  async function load() {
    setPreview(undefined);
    setPreview(await requestJson<MonthlyBulkPreview>(base + '/monthly-preview', { csrf, body: {} }));
  }
  const apply = () => run(async () => {
    await requestJson<{ id: string }>(base + '/monthly-apply', { csrf, body: { token: preview!.token, reason: checked.trimmed, commandId: crypto.randomUUID() } });
    setReason('');
    await load();
    setMessage({ text: `월 연차 ${decided}건을 원장에 반영했습니다.` });
  });
  return <section className="wl-section" aria-labelledby="monthly-bulk-title">
    <h2 id="monthly-bulk-title">입사 첫해 월 연차 일괄 반영</h2>
    <p className="hint">발생일이 지났지만 판정하지 않은 월을 일일 근태로 판정합니다. 결근이 없으면 개근, 결근이 있으면 미개근이고, 근태가 확정되지 않은 달은 반영하지 않습니다.
      '기초 잔여에 이미 포함' 판정은 화면 위의 '입사·퇴사 연차 정산'에서 합니다.</p>
    <div className="wl-form-actions"><button type="button" className="t-btn" disabled={busy} onClick={() => void run(load)}>
      {busy && !preview ? '조회 중…' : '월 연차 미리보기'}</button></div>
    {preview && <>
      <ul className="annual-review-counts"><li>개근 {preview.fullCount}건</li><li>미개근 {preview.notFullCount}건</li>
        {preview.reviewCount > 0 && <li>확인 필요 {preview.reviewCount}건</li>}</ul>
      {preview.rows.length ? <AnnualLeaveTable label="월 연차 자동 판정"
        columns={['사원', '입사일', '근무월', '산정기간', '소정', '결근', '판정 불가', '자동 판정']}
        rows={preview.rows.map(row => ({ id: row.employeeId + row.period.monthNumber, cells: [
          <><strong>{row.employeeName}</strong><small>{row.employeeNo}</small></>, fmtDate(row.joinedOn), row.period.monthNumber + '개월차',
          `${fmtDate(row.period.periodStart)} ~ ${fmtDate(row.period.periodEnd)}`, row.period.scheduledDays + '일',
          (row.period.absenceDays ?? 0) + '일', row.period.unresolvedDays + '일',
          <><strong>{decisionNames[row.decision]}</strong><small>{row.finding}</small></>,
        ] }))} /> : <p>판정할 월 연차가 없습니다.</p>}
      {preview.canPost && !publicDemo && <form className="wl-form" aria-labelledby="monthly-bulk-title" onSubmit={event => { event.preventDefault(); void apply(); }}>
        <FormTable columns={2}><FormRow label="월 연차 반영 사유" htmlFor="monthly-bulk-reason" required>
          <input id="monthly-bulk-reason" className="t-input" type="text" required value={reason} disabled={busy}
            onChange={event => { setReason(event.target.value); setMessage(undefined); }} />
        </FormRow></FormTable>
        <div className="wl-form-actions"><button type="submit" className="t-btn primary" disabled={busy || !checked.validReason}>
          {busy ? '반영 중…' : `개근·미개근 ${decided}건 반영`}</button></div>
      </form>}
    </>}
    {message && <p className={message.error ? 'notice error' : 'notice'} role={message.error ? 'alert' : 'status'}>{message.text}</p>}
  </section>;
}
