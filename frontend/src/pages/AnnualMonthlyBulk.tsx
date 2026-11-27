import { useRef, useState } from 'react';
import { commandReason } from '../commandReason';
import { PageRequestError, requestJson } from '../transport';
import type { CsrfToken } from '../types';
import { FormRow, FormTable, fmtDate } from '../ui';
import { AnnualLeaveTable } from './AnnualLeaveTable';
import type { MonthlyBulkDecision, MonthlyBulkPreview, MonthlyBulkRow } from './annualLeaveTypes';

const base = '/admin/leave-balances/annual-cycle';
const decisionNames: Record<MonthlyBulkDecision, string> = { FULL: '개근', NOT_FULL: '미개근', NEEDS_REVIEW: '확인 필요' };
type Command = { token: string; reason: string; commandId: string; count: number };
const isCount = (value: number) => Number.isSafeInteger(value) && value >= 0;
const isRow = (row: MonthlyBulkRow) => row && row.employeeId && row.employeeNo && row.employeeName && row.decision in decisionNames
  && typeof row.finding === 'string' && Number.isInteger(row.period?.monthNumber);
const isPreview = (result: MonthlyBulkPreview) => result && typeof result.today === 'string'
  && [result.fullCount, result.notFullCount, result.reviewCount].every(isCount) && typeof result.canPost === 'boolean'
  && (!result.canPost || (typeof result.token === 'string' && result.token.length > 0))
  && Array.isArray(result.rows) && result.rows.every(isRow);

export function AnnualMonthlyBulk({ csrf, publicDemo }: { csrf: CsrfToken; publicDemo: boolean }) {
  const [preview, setPreview] = useState<MonthlyBulkPreview>();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState<'preview' | 'apply'>();
  const [retry, setRetry] = useState<Command>();
  const [success, setSuccess] = useState<string>();
  const [error, setError] = useState<string>();
  const [denied, setDenied] = useState(false);
  const running = useRef(false);
  const checked = commandReason(reason);
  const decided = preview?.rows.filter(row => row.decision !== 'NEEDS_REVIEW').length ?? 0;
  const locked = Boolean(busy || retry);
  function start(action: NonNullable<typeof busy>) {
    if (running.current) return false;
    running.current = true; setBusy(action); setError(undefined); setSuccess(undefined);
    return true;
  }
  function finish() { running.current = false; setBusy(undefined); }
  function lostAccess(failure: unknown) {
    if (failure instanceof PageRequestError && (failure.reauthenticate || failure.status === 401 || failure.status === 403)) {
      setPreview(undefined); setRetry(undefined); setDenied(true);
      return true;
    }
    return false;
  }
  async function load() {
    setPreview(undefined);
    try {
      const data = await requestJson<MonthlyBulkPreview>(base + '/monthly-preview', { csrf, body: {} });
      if (!isPreview(data)) throw new Error();
      setPreview(data);
    } catch (failure) {
      if (!lostAccess(failure)) setError(failure instanceof PageRequestError && failure.status < 500
        ? failure.message : '월 연차 미리보기를 불러오지 못했습니다. 다시 미리보기를 실행하세요.');
    }
  }
  async function inspect() {
    if (locked || !start('preview')) return;
    try { await load(); } finally { finish(); }
  }
  async function apply(command: Command) {
    if (publicDemo || !start('apply')) return;
    try {
      try {
        const receipt = await requestJson<{ id: string }>(base + '/monthly-apply', { csrf,
          body: { token: command.token, reason: command.reason, commandId: command.commandId } });
        if (typeof receipt?.id !== 'string' || !receipt.id) throw new Error();
      } catch (failure) {
        if (lostAccess(failure)) return;
        if (failure instanceof PageRequestError && failure.status < 500) {
          setPreview(undefined); setRetry(undefined); setError(failure.message);
        } else {
          setRetry(command);
          setError('서버의 처리 결과를 확인할 수 없습니다. 같은 명령으로 결과를 확인하세요.');
        }
        return;
      }
      setRetry(undefined); setReason('');
      setSuccess(`월 연차 ${command.count}건을 원장에 반영했습니다.`);
      await load();
    } finally { finish(); }
  }
  if (denied) return <section className="wl-section"><p role="alert">로그인 상태와 연차 관리 권한을 다시 확인하세요.</p>
    <a className="t-btn" href="/login">다시 로그인</a></section>;

  return <section className="wl-section" aria-labelledby="monthly-bulk-title">
    <h2 id="monthly-bulk-title">입사 첫해 월 연차 일괄 반영</h2>
    <p className="hint">발생일이 지났지만 판정하지 않은 월을 일일 근태로 판정합니다. 결근이 없으면 개근, 결근이 있으면 미개근이고, 근태가 확정되지 않은 달은 반영하지 않습니다.
      '기초 잔여에 이미 포함' 판정은 화면 위의 '입사·퇴사 연차 정산'에서 합니다.</p>
    <div className="wl-form-actions"><button type="button" className="t-btn" disabled={locked} onClick={() => void inspect()}>
      {busy === 'preview' ? '조회 중…' : '월 연차 미리보기'}</button></div>
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
      {preview.canPost && !publicDemo && <form className="wl-form" aria-labelledby="monthly-bulk-title" onSubmit={event => {
        event.preventDefault();
        void apply(retry ?? { token: preview.token, reason: checked.trimmed, commandId: crypto.randomUUID(), count: decided });
      }}>
        <FormTable columns={2}><FormRow label="월 연차 반영 사유" htmlFor="monthly-bulk-reason" required>
          <input id="monthly-bulk-reason" className="t-input" type="text" required value={reason} disabled={locked}
            onChange={event => { setReason(event.target.value); setError(undefined); setSuccess(undefined); }} />
        </FormRow></FormTable>
        <div className="wl-form-actions"><button type="submit" className="t-btn primary" disabled={Boolean(busy) || (!retry && !checked.validReason)}>
          {retry ? '같은 명령으로 반영 결과 확인' : busy === 'apply' ? '반영 중…' : `개근·미개근 ${decided}건 반영`}</button></div>
      </form>}
    </>}
    {success && <p className="notice" role="status">{success}</p>}
    {error && <p className="notice error" role="alert">{error}</p>}
  </section>;
}
