import { useEffect, useRef, useState } from 'react';
import { commandReason } from '../commandReason';
import { PageHeader } from '../components/PageHeader';
import { PageRequestError, requestJson } from '../transport';
import type { CsrfToken } from '../types';
import { FormRow, FormTable, fmtDate } from '../ui';
import { AnnualLeaveTable } from './AnnualLeaveTable';
import { AnnualMonthlyBulk } from './AnnualMonthlyBulk';
import { employmentPath } from './employmentLeaveTypes';
import { basisNames } from './annualLeaveTypes';
import type { AnnualCycleCommand, AnnualCycleModel, AnnualCyclePreview, AnnualCycleRow, AnnualLeaveBasis, AnnualLeaveRule, PolicyCommand } from './annualLeaveTypes';
import './AnnualLeave.css';

const base = '/admin/leave-balances/annual-cycle';
type Write = { kind: 'policy'; command: PolicyCommand } | { kind: 'apply'; command: AnnualCycleCommand; count: number };
type PreviewRequest = { year: number } | { month: string };
type Feedback = { area: 'policy' | 'cycle'; text: string; conflict?: boolean };
const draftRule = (rule: AnnualLeaveRule | null) => ({
  basis: (rule?.basis ?? 'FISCAL') as AnnualLeaveBasis, fiscalStartMonth: String(rule?.fiscalStartMonth ?? ''),
  carryLimitDays: String(rule?.carryLimitDays ?? ''),
});
const isCount = (value: number) => Number.isSafeInteger(value) && value >= 0;
const isDays = (value: number) => Number.isFinite(value) && value >= 0 && Number.isSafeInteger(value * 2);
const isRule = (rule: AnnualLeaveRule) => rule && Number.isSafeInteger(rule.version) && rule.version > 0
  && (rule.basis === 'FISCAL' || rule.basis === 'HIRE_DATE')
  && Number.isInteger(rule.fiscalStartMonth) && rule.fiscalStartMonth >= 1 && rule.fiscalStartMonth <= 12
  && isDays(rule.grantDays) && isDays(rule.carryLimitDays);
const isRow = (row: AnnualCycleRow) => row && row.employeeId && row.employeeNo && row.employeeName
  && (row.decision === 'GRANT' || row.decision === 'NEEDS_REVIEW') && Number.isInteger(row.serviceYear)
  && [row.previousDays, row.grantDays, row.carryDays, row.expireDays].every(isDays);
function isPreview(result: AnnualCyclePreview, request: PreviewRequest) {
  return ('month' in request ? result?.month === request.month : result?.year === request.year) && isRule(result.rule)
    && typeof result.periodStart === 'string' && typeof result.periodEnd === 'string'
    && [result.postedCount, result.existingCount, result.laterCount, result.pendingCount, result.reviewCount].every(isCount)
    && typeof result.canPost === 'boolean' && (!result.canPost || (typeof result.token === 'string' && result.token.length > 0))
    && Array.isArray(result.rows) && result.rows.every(isRow);
}

function ReasonField({ id, label, value, onChange, required, disabled }: {
  id: string; label: string; value: string; onChange: (value: string) => void; required?: boolean; disabled?: boolean;
}) {
  const { length, forbiddenCharacters } = commandReason(value);
  const error = length > 500 ? '앞뒤 공백을 제외하고 500자 이내로 입력하세요.'
    : forbiddenCharacters ? '탭과 제어 문자 없이 한 줄로 입력하세요.' : '';
  return <FormRow label={label} htmlFor={id} required={required}>
    <input id={id} className="t-input" type="text" value={value} required={required} disabled={disabled}
      aria-invalid={error ? true : undefined} aria-describedby={error ? id + '-error' : undefined}
      placeholder={required ? undefined : '원장 반영 시 필수'} onChange={event => onChange(event.target.value)} />
    {error && <small id={id + '-error'} role="alert">{error}</small>}
  </FormRow>;
}

function PreviewResults({ result, today, reviewed, onReview, selectable }: {
  result: AnnualCyclePreview; today: string; reviewed: Set<string>; onReview: (employeeId: string, checked: boolean) => void; selectable: boolean;
}) {
  const hire = result.rule.basis === 'HIRE_DATE';
  const columns = ['사원', '입사일', ...(hire ? ['부여일'] : []), '근속', '출근율', '판정', '전년 잔여', '이월', '소멸', '신규 부여', '반영 후 잔여'];
  return <section className="annual-cycle-preview" aria-labelledby="annual-review-title">
    <h3 id="annual-review-title" tabIndex={-1}>미리보기 결과</h3>
    <p><strong>{fmtDate(result.periodStart)} ~ {fmtDate(result.periodEnd)}</strong></p>
    <p>기준 버전 {result.rule.version} · {hire ? '입사일 기준' : `매년 ${result.rule.fiscalStartMonth}월 1일 시작`} · 이월 한도 {result.rule.carryLimitDays}일</p>
    <ul className="annual-review-counts">
      <li>반영 대상 {result.rows.filter(row => row.decision === 'GRANT').length}명</li>
      {result.reviewCount > 0 && <li>확인 필요 {result.reviewCount}명</li>}
      <li>이미 반영 {result.postedCount}명</li>
      {result.existingCount > 0 && <li>기초 등록으로 제외 {result.existingCount}명</li>}
      {result.laterCount > 0 && <li>후속 연도 부여로 제외 {result.laterCount}명</li>}
      {result.pendingCount > 0 && <li>전기 휴가 결재 대기 {result.pendingCount}명</li>}
    </ul>
    {result.pendingCount > 0 && <p className="hint">전기 휴가 사용·취소가 대기 중인 사원은 결재가 끝난 뒤 다시 미리 보세요.{' '}
      <a href="/admin/requests?scope=LEAVE">휴가 신청 현황</a></p>}
    {result.reviewCount > 0 && <p className="hint">확인 필요인 사원은 근태를 확정한 뒤 다시 미리 보거나, 사정을 확인하고 '법정 일수 부여'를 골라 함께 반영하세요.
      고른 사원은 부여 대상과 같은 법정 일수를 받고 '담당자 확인'으로 기록됩니다. 출근율 80% 미만이라 법정 일수를 주지 않을 사원은 고르지 말고 개인별 연차 관리의 잔여 조정으로 처리합니다.</p>}
    {result.rows.length ? <>
      <AnnualLeaveTable label="사원별 반영 예정" columns={columns}
        rows={result.rows.map(row => ({ id: row.employeeId, cells: [
          <><strong>{row.employeeName}</strong><small>{row.employeeNo}</small></>, fmtDate(row.joinedOn), ...(hire ? [fmtDate(row.grantOn)] : []),
          row.serviceYear + '년차', row.attendanceRate == null ? '—' : row.attendanceRate + '%',
          row.decision === 'GRANT' ? '부여 대상' : <><strong className="annual-review-flag">확인 필요</strong><small>{row.reason}</small>
            {selectable && <label className="annual-review-pick"><input type="checkbox" aria-label={row.employeeName + ' 법정 일수 부여'}
              checked={reviewed.has(row.employeeId)} onChange={event => onReview(row.employeeId, event.target.checked)} />법정 일수 부여</label>}</>,
          row.previousDays + '일', row.carryDays + '일', row.expireDays + '일', row.grantDays + '일', <strong>{row.grantDays + row.carryDays}일</strong>,
        ] }))} />
      <p className="hint">반영 후 잔여는 새 연도 묶음에 기록할 신규 부여와 이월의 합계입니다.</p>
    </> : <p>{result.pendingCount ? '현재 바로 반영할 사원이 없습니다.' : '이 기간에 추가로 반영할 사원이 없습니다.'}</p>}
    {!result.canPost && result.periodStart > today && <p>{result.periodStart}부터 원장에 반영할 수 있습니다.</p>}
  </section>;
}

export function AnnualLeaveCycle({ model, csrf }: { model: AnnualCycleModel; csrf: CsrfToken }) {
  const [rule, setRule] = useState(model.policy);
  const [draft, setDraft] = useState(() => draftRule(model.policy));
  const [policyReason, setPolicyReason] = useState('');
  const [year, setYear] = useState(String(model.year));
  const [month, setMonth] = useState(model.month);
  const [reviewed, setReviewed] = useState<Set<string>>(() => new Set());
  const yearSelected = useRef(model.yearSelected);
  const [reason, setReason] = useState('');
  const [preview, setPreview] = useState<AnnualCyclePreview>();
  const [expiryConfirmed, setExpiryConfirmed] = useState(false);
  const [busy, setBusy] = useState<'policy' | 'preview' | 'apply'>();
  const [retry, setRetry] = useState<Write>();
  const [error, setError] = useState<Feedback>();
  const [success, setSuccess] = useState<Feedback>();
  const [denied, setDenied] = useState(false);
  const running = useRef(false);
  const resultArea = useRef<HTMLDivElement>(null);
  const feedback = useRef<HTMLParagraphElement>(null);
  const locked = Boolean(busy || retry);
  const ruleChanged = !rule || draft.basis !== rule.basis || draft.carryLimitDays === '' || Number(draft.carryLimitDays) !== rule.carryLimitDays
    || (draft.basis === 'FISCAL' && (draft.fiscalStartMonth === '' || Number(draft.fiscalStartMonth) !== rule.fiscalStartMonth));
  const hasYear = Number.isInteger(Number(year)) && Number(year) >= 1900 && Number(year) <= 9998;
  const hire = rule?.basis === 'HIRE_DATE';
  const hasMonth = /^\d{4}-(0[1-9]|1[0-2])$/.test(month) && month <= model.month;
  const canPreview = Boolean(rule && !ruleChanged && (hire ? hasMonth : hasYear) && !locked);
  const selected = preview?.rows.filter(row => row.decision === 'GRANT' || reviewed.has(row.employeeId)) ?? [];
  const expiryDays = selected.reduce((total, row) => total + row.expireDays, 0);
  const canApply = Boolean(preview?.canPost && preview.rows.length && !model.publicDemo);
  const checkedPolicyReason = commandReason(policyReason), checkedReason = commandReason(reason);
  useEffect(() => {
    if (error || denied || success) feedback.current?.focus();
    else if (preview) resultArea.current?.querySelector<HTMLElement>('h3')?.focus();
  }, [preview, error, success, denied]);

  function clearReview() {
    setPreview(undefined);
    setReviewed(new Set());
    setExpiryConfirmed(false);
  }
  function review(employeeId: string, checked: boolean) {
    setReviewed(current => {
      const next = new Set(current);
      if (checked) next.add(employeeId); else next.delete(employeeId);
      return next;
    });
    setExpiryConfirmed(false);
  }
  function inputChanged() {
    clearReview();
    setError(undefined);
    setSuccess(undefined);
  }
  function changeRule(key: keyof typeof draft, value: string) {
    setDraft(current => ({ ...current, [key]: value }));
    inputChanged();
  }
  function start(action: NonNullable<typeof busy>) {
    if (running.current) return false;
    running.current = true;
    setBusy(action);
    setError(undefined);
    setSuccess(undefined);
    return true;
  }
  function finish() { running.current = false; setBusy(undefined); }
  function lostAccess(failure: unknown) {
    if (failure instanceof PageRequestError && (failure.reauthenticate || failure.status === 401 || failure.status === 403)) {
      clearReview(); setRetry(undefined); setSuccess(undefined); setDenied(true);
      return true;
    }
    return false;
  }
  async function readPreview(selected: PreviewRequest) {
    clearReview();
    try {
      const data = await requestJson<AnnualCyclePreview>(base + '/preview', { csrf, body: selected });
      if (!isPreview(data, selected)) throw new Error();
      setPreview(data);
    } catch (failure) {
      if (!lostAccess(failure)) setError({ area: 'cycle', text: failure instanceof PageRequestError
        ? failure.message : '미리보기를 불러오지 못했습니다. 다시 미리보기를 실행하세요.' });
    }
  }
  async function inspect() {
    if (!canPreview || !start('preview')) return;
    try { await readPreview(hire ? { month } : { year: Number(year) }); } finally { finish(); }
  }
  async function write(action: Write) {
    if (model.publicDemo || !start(action.kind)) return;
    try {
      if (action.kind === 'policy') {
        const saved = await requestJson<AnnualLeaveRule>(base + '/policy', { csrf, body: action.command });
        if (!isRule(saved)) throw new Error();
        if (!yearSelected.current) {
          const [calendarYear, calendarMonth] = model.today.split('-').map(Number);
          setYear(String(calendarMonth < saved.fiscalStartMonth ? calendarYear - 1 : calendarYear));
        }
        setRule(saved); setDraft(draftRule(saved)); setPolicyReason('');
        setSuccess({ area: 'policy', text: '회사 연차 기준을 저장했습니다.' });
        clearReview();
      } else {
        const receipt = await requestJson<{ id: string }>(base + '/apply', { csrf, body: action.command });
        if (typeof receipt?.id !== 'string' || !receipt.id) throw new Error();
        setSuccess({ area: 'cycle', text: `${action.count}명의 연차를 원장에 반영했습니다.` });
        setReason('');
        await readPreview(action.command.month ? { month: action.command.month } : { year: action.command.year! });
      }
      setRetry(undefined);
    } catch (failure) {
      if (lostAccess(failure)) return;
      const area = action.kind === 'policy' ? 'policy' : 'cycle';
      if (failure instanceof PageRequestError && failure.status < 500) {
        clearReview(); setRetry(undefined);
        setError({ area, text: failure.message, conflict: failure.status === 409 });
      } else {
        setRetry(action);
        setError({ area, text: '서버의 처리 결과를 확인할 수 없습니다. 같은 명령으로 결과를 확인하세요.' });
      }
    } finally { finish(); }
  }
  function messages(area: Feedback['area']) {
    return <>{success?.area === area && <p className="notice" role="status" tabIndex={-1} ref={!error ? feedback : undefined}>{success.text}</p>}
      {error?.area === area && <p className="notice error" role="alert" tabIndex={-1} ref={feedback}>{error.text}{' '}
        {error.conflict && (area === 'policy' ? <a href={yearSelected.current && hasYear ? base + '?year=' + year : base}>최신 기준 조회</a>
          : '잔여와 기준을 다시 미리 보세요.')}</p>}</>;
  }
  if (denied) return <section className="wl-section"><p ref={feedback} tabIndex={-1} role="alert">로그인 상태와 연차 관리 권한을 다시 확인하세요.</p>
    <a className="t-btn" href="/login">다시 로그인</a></section>;

  return <div className="t-annual-page annual-leave-page" data-modal-busy={busy ? 'true' : undefined}>
    <PageHeader title="연차 부여·이월·소멸" actions={<>
      <a className="t-btn" href={employmentPath}>입사·퇴사 연차 정산</a>
      <a className="t-btn" href="/admin/leave-balances">개인별 연차 관리</a>
    </>} />
    {model.publicDemo && <p className="notice">공개 데모에서는 회사 연차 기준과 미리보기를 조회할 수 있습니다.</p>}
    <section className="wl-section">
      <h2 id="annual-policy-title">회사 연차 기준</h2>
      {!rule && <p className="hint">회사의 연차 부여 기준과 이월 한도를 먼저 설정하세요.</p>}
      <p className="hint">연차 일수는 근로기준법에 따라 사원별로 계산합니다. 1·2년차 15일, 3년 이상 근속하면 2년마다 1일을 더하고 최대 25일입니다. 이월 한도는 사원 한 명 기준 0.5일 단위로 입력합니다.</p>
      <form className="wl-form" aria-labelledby="annual-policy-title" onSubmit={event => {
        event.preventDefault();
        if (retry?.kind === 'policy') { void write(retry); return; }
        if (!ruleChanged || locked || !checkedPolicyReason.validReason || !event.currentTarget.reportValidity()) return;
        void write({ kind: 'policy', command: { expectedVersion: rule?.version ?? 0, basis: draft.basis,
          fiscalStartMonth: draft.basis === 'FISCAL' ? Number(draft.fiscalStartMonth) : null, carryLimitDays: Number(draft.carryLimitDays), reason: checkedPolicyReason.trimmed, commandId: crypto.randomUUID() } });
      }}>
        <fieldset disabled={locked || model.publicDemo} className="annual-fields">
          <FormTable columns={2}>
            <FormRow label="연차 부여 기준" htmlFor="annual-basis" required>
              <select id="annual-basis" className="t-select" required value={draft.basis} onChange={event => changeRule('basis', event.target.value)}>
                {(Object.keys(basisNames) as AnnualLeaveBasis[]).map(basis => <option key={basis} value={basis}>{basisNames[basis]}</option>)}
              </select>
            </FormRow>
            {draft.basis === 'FISCAL' && <FormRow label="회계연도 시작 월" htmlFor="annual-start-month" required>
              <select id="annual-start-month" className="t-select" required value={draft.fiscalStartMonth} onChange={event => changeRule('fiscalStartMonth', event.target.value)}>
                <option value="">월 선택</option>{Array.from({ length: 12 }, (_, i) => <option key={i + 1} value={i + 1}>{i + 1}월 1일</option>)}
              </select>
            </FormRow>}
            <FormRow label="이월 한도 일수" htmlFor="annual-carry-days" required>
              <input type="number" id="annual-carry-days" className="t-input" min="0" max="100000" step="0.5" required value={draft.carryLimitDays}
                onChange={event => changeRule('carryLimitDays', event.target.value)} />
            </FormRow>
            <ReasonField id="annual-policy-reason" label="기준 설정 사유" required value={policyReason}
              onChange={value => { setPolicyReason(value); inputChanged(); }} />
          </FormTable>
        </fieldset>
        <div className="wl-form-actions"><button type="submit" className="t-btn"
          disabled={model.publicDemo || Boolean(busy) || (retry ? retry.kind !== 'policy' : !ruleChanged || !checkedPolicyReason.validReason)}>
          {retry?.kind === 'policy' ? '같은 명령으로 기준 저장 결과 확인' : busy === 'policy' ? '저장 중…' : '기준 저장'}
        </button></div>
        {messages('policy')}
      </form>
    </section>
    <section className="wl-section">
      <h2 id="annual-cycle-title">{hire ? '월별 일괄 처리' : '연도별 일괄 처리'}</h2>
      {hire ? <p className="hint">선택한 달 1일부터 오늘(지난달이면 말일)까지 입사기념일이 지난 사원이 대상입니다.
        직전 1년 출근율이 80% 이상이고 근태가 모두 확정된 사원은 부여 대상이고, 확인 필요 사원은 '법정 일수 부여'를 고른 경우에만 반영합니다. 담당자 본인의 연차는 다른 담당자가 처리합니다.</p>
        : <p className="hint">회계연도 시작일 전에 입사해 시작일에 재직한 사원이 대상이고, 직전 회계연도 출근율로 판정합니다. 담당자 본인의 연차는 다른 담당자가 처리합니다.
        전년 귀속이 확인되고 새 연도 시작 전날 사용이 끝난 잔여를 이월합니다.</p>}
      <form className="wl-form" aria-labelledby="annual-cycle-title" onSubmit={event => { event.preventDefault(); void inspect(); }}>
        <fieldset disabled={locked} className="annual-fields"><FormTable columns={2}>
          {hire ? <FormRow label="대상 월" htmlFor="annual-target-month" required>
            <input id="annual-target-month" className="t-input" type="month" required max={model.month} value={month}
              onChange={event => { setMonth(event.target.value); inputChanged(); }} />
          </FormRow> : <FormRow label="대상 회계연도" htmlFor="annual-target-year" required>
            <input id="annual-target-year" className="t-input" type="number" min="1900" max="9998" required value={year}
              onChange={event => { yearSelected.current = true; setYear(event.target.value); inputChanged(); }} />
          </FormRow>}
          <ReasonField id="annual-post-reason" label="처리 사유" value={reason} disabled={model.publicDemo}
            onChange={value => { setReason(value); setExpiryConfirmed(false); setError(undefined); setSuccess(undefined); }} />
        </FormTable></fieldset>
        {rule && ruleChanged && <p className="hint">변경한 회사 기준을 저장한 뒤 미리 보세요.</p>}
        <div className="wl-form-actions"><button type="submit" className="t-btn" disabled={!canPreview}>
          {busy === 'preview' ? '조회 중…' : '미리보기'}</button></div>
      </form>
      {preview && <div ref={resultArea}><PreviewResults result={preview} today={model.today} reviewed={reviewed} onReview={review}
        selectable={canApply && !locked} /></div>}
      {canApply && preview && <div className="annual-cycle-confirm">
        {expiryDays > 0 && <div><strong>소멸 합계 {expiryDays}일</strong>
          <label><input type="checkbox" checked={expiryConfirmed} disabled={locked}
            onChange={event => setExpiryConfirmed(event.target.checked)} />소멸 대상과 일수를 확인했습니다.</label></div>}
        <button type="button" className="t-btn primary"
          disabled={Boolean(busy) || (!retry && selected.length === 0) || !checkedReason.validReason || (expiryDays > 0 && !expiryConfirmed)}
          onClick={() => void write(retry?.kind === 'apply' ? retry : { kind: 'apply', count: selected.length,
            command: { ...(preview.month ? { month: preview.month } : { year: preview.year }), token: preview.token,
              reason: checkedReason.trimmed, reviewedEmployeeIds: [...reviewed], commandId: crypto.randomUUID() } })}>
          {retry?.kind === 'apply' ? '같은 명령으로 반영 결과 확인' : busy === 'apply' ? '반영 중…' : '원장에 반영'}
        </button>
      </div>}
      {messages('cycle')}
    </section>
    <AnnualMonthlyBulk csrf={csrf} publicDemo={model.publicDemo} />
  </div>;
}
