import { useEffect, useRef, useState } from 'react';
import { commandReason } from '../commandReason';
import { PageHeader } from '../components/PageHeader';
import { PageRequestError, requestJson } from '../transport';
import type { CsrfToken } from '../types';
import { FormRow, FormTable, fmtDate } from '../ui';
import { AnnualLeaveTable } from './AnnualLeaveTable';
import { employmentPath } from './employmentLeaveTypes';
import type { AnnualCycleCommand, AnnualCycleModel, AnnualCyclePreview, AnnualLeaveRule, PolicyCommand } from './annualLeaveTypes';
import './AnnualLeave.css';

const base = '/admin/leave-balances/annual-cycle';
type Write = { kind: 'policy'; command: PolicyCommand } | { kind: 'apply'; command: AnnualCycleCommand; count: number };
type Feedback = { area: 'policy' | 'cycle'; text: string; conflict?: boolean };
const draftRule = (rule: AnnualLeaveRule | null) => ({
  fiscalStartMonth: String(rule?.fiscalStartMonth ?? ''), grantDays: String(rule?.grantDays ?? ''), carryLimitDays: String(rule?.carryLimitDays ?? ''),
});
const isCount = (value: number) => Number.isSafeInteger(value) && value >= 0;
const isDays = (value: number) => Number.isFinite(value) && value >= 0 && Number.isSafeInteger(value * 2);
const isRule = (rule: AnnualLeaveRule) => rule && Number.isSafeInteger(rule.version) && rule.version > 0
  && Number.isInteger(rule.fiscalStartMonth) && rule.fiscalStartMonth >= 1 && rule.fiscalStartMonth <= 12
  && isDays(rule.grantDays) && isDays(rule.carryLimitDays);
function isPreview(result: AnnualCyclePreview, year: number) {
  return result?.year === year && isRule(result.rule) && typeof result.periodStart === 'string' && typeof result.periodEnd === 'string'
    && [result.postedCount, result.existingCount, result.laterCount, result.pendingCount].every(isCount) && typeof result.canPost === 'boolean'
    && (!result.canPost || (typeof result.token === 'string' && result.token.length > 0))
    && Array.isArray(result.rows) && result.rows.every(row => row && row.employeeId && row.employeeNo && row.employeeName
      && [row.previousDays, row.grantDays, row.carryDays, row.expireDays].every(isDays));
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

function PreviewResults({ result, today }: { result: AnnualCyclePreview; today: string }) {
  return <section className="annual-cycle-preview" aria-labelledby="annual-review-title">
    <h3 id="annual-review-title" tabIndex={-1}>미리보기 결과</h3>
    <p><strong>{fmtDate(result.periodStart)} ~ {fmtDate(result.periodEnd)}</strong></p>
    <p>기준 버전 {result.rule.version} · 매년 {result.rule.fiscalStartMonth}월 1일 시작 · 부여 {result.rule.grantDays}일 · 이월 한도 {result.rule.carryLimitDays}일</p>
    <ul className="annual-review-counts">
      <li>반영 대상 {result.rows.length}명</li><li>이미 반영 {result.postedCount}명</li>
      {result.existingCount > 0 && <li>기초 등록으로 제외 {result.existingCount}명</li>}
      {result.laterCount > 0 && <li>후속 연도 부여로 제외 {result.laterCount}명</li>}
      {result.pendingCount > 0 && <li>전기 휴가 결재 대기 {result.pendingCount}명</li>}
    </ul>
    {result.pendingCount > 0 && <p className="hint">전기 휴가 사용·취소가 대기 중인 사원은 결재가 끝난 뒤 다시 미리 보세요.{' '}
      <a href="/admin/requests?scope=LEAVE">휴가 신청 현황</a></p>}
    {result.rows.length ? <>
      <AnnualLeaveTable label="사원별 반영 예정" columns={['사원', '전년 잔여', '이월', '소멸', '신규 부여', '반영 후 잔여']}
        rows={result.rows.map(row => ({ id: row.employeeId, cells: [
          <><strong>{row.employeeName}</strong><small>{row.employeeNo}</small></>, row.previousDays + '일', row.carryDays + '일',
          row.expireDays + '일', row.grantDays + '일', <strong>{row.grantDays + row.carryDays}일</strong>,
        ] }))} />
      <p className="hint">반영 후 잔여는 새 연도 묶음에 기록할 신규 부여와 이월의 합계입니다.</p>
    </> : <p>{result.pendingCount ? '현재 바로 반영할 사원이 없습니다.' : '이 연도에 추가로 반영할 사원이 없습니다.'}</p>}
    {!result.canPost && result.periodStart > today && <p>{result.periodStart}부터 원장에 반영할 수 있습니다.</p>}
  </section>;
}

export function AnnualLeaveCycle({ model, csrf }: { model: AnnualCycleModel; csrf: CsrfToken }) {
  const [rule, setRule] = useState(model.policy);
  const [draft, setDraft] = useState(() => draftRule(model.policy));
  const [policyReason, setPolicyReason] = useState('');
  const [year, setYear] = useState(String(model.year));
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
  const ruleChanged = !rule || (Object.keys(draft) as (keyof typeof draft)[])
    .some(key => draft[key] === '' || Number(draft[key]) !== rule[key]);
  const hasYear = Number.isInteger(Number(year)) && Number(year) >= 1900 && Number(year) <= 9998;
  const canPreview = Boolean(rule && !ruleChanged && hasYear && !locked);
  const expiryDays = preview?.rows.reduce((total, row) => total + row.expireDays, 0) ?? 0;
  const canApply = Boolean(preview?.canPost && preview.rows.length && !model.publicDemo);
  const checkedPolicyReason = commandReason(policyReason), checkedReason = commandReason(reason);
  useEffect(() => {
    if (error || denied || success) feedback.current?.focus();
    else if (preview) resultArea.current?.querySelector<HTMLElement>('h3')?.focus();
  }, [preview, error, success, denied]);

  function clearReview() {
    setPreview(undefined);
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
  async function readPreview(selectedYear: number) {
    clearReview();
    try {
      const data = await requestJson<AnnualCyclePreview>(base + '/preview', { csrf, body: { year: selectedYear } });
      if (!isPreview(data, selectedYear)) throw new Error();
      setPreview(data);
    } catch (failure) {
      if (!lostAccess(failure)) setError({ area: 'cycle', text: failure instanceof PageRequestError
        ? failure.message : '미리보기를 불러오지 못했습니다. 다시 미리보기를 실행하세요.' });
    }
  }
  async function inspect() {
    if (!canPreview || !start('preview')) return;
    try { await readPreview(Number(year)); } finally { finish(); }
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
        await readPreview(action.command.year);
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
      {!rule && <p className="hint">회사의 회계연도와 연차 일수를 먼저 설정하세요.</p>}
      <p className="hint">연간 부여 일수와 이월 한도는 사원 한 명을 기준으로 0.5일 단위로 입력합니다.</p>
      <form className="wl-form" aria-labelledby="annual-policy-title" onSubmit={event => {
        event.preventDefault();
        if (retry?.kind === 'policy') { void write(retry); return; }
        if (!ruleChanged || locked || !checkedPolicyReason.validReason || !event.currentTarget.reportValidity()) return;
        void write({ kind: 'policy', command: { expectedVersion: rule?.version ?? 0, fiscalStartMonth: Number(draft.fiscalStartMonth),
          grantDays: Number(draft.grantDays), carryLimitDays: Number(draft.carryLimitDays), reason: checkedPolicyReason.trimmed, commandId: crypto.randomUUID() } });
      }}>
        <fieldset disabled={locked || model.publicDemo} className="annual-fields">
          <FormTable columns={2}>
            <FormRow label="회계연도 시작 월" htmlFor="annual-start-month" required>
              <select id="annual-start-month" className="t-select" required value={draft.fiscalStartMonth} onChange={event => changeRule('fiscalStartMonth', event.target.value)}>
                <option value="">월 선택</option>{Array.from({ length: 12 }, (_, i) => <option key={i + 1} value={i + 1}>{i + 1}월 1일</option>)}
              </select>
            </FormRow>
            <FormRow label="연간 부여 일수" htmlFor="annual-grant-days" required>
              <input type="number" id="annual-grant-days" className="t-input" min="0" max="100000" step="0.5" required value={draft.grantDays}
                onChange={event => changeRule('grantDays', event.target.value)} />
            </FormRow>
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
      <h2 id="annual-cycle-title">연도별 일괄 처리</h2>
      <p className="hint">회계연도 시작일에 재직한 사원이 대상입니다. 담당자 본인의 연차는 다른 담당자가 처리합니다.
        전년 귀속이 확인되고 새 연도 시작 전날 사용이 끝난 잔여를 이월합니다.</p>
      <form className="wl-form" aria-labelledby="annual-cycle-title" onSubmit={event => { event.preventDefault(); void inspect(); }}>
        <fieldset disabled={locked} className="annual-fields"><FormTable columns={2}>
          <FormRow label="대상 회계연도" htmlFor="annual-target-year" required>
            <input id="annual-target-year" className="t-input" type="number" min="1900" max="9998" required value={year}
              onChange={event => { yearSelected.current = true; setYear(event.target.value); inputChanged(); }} />
          </FormRow>
          <ReasonField id="annual-post-reason" label="처리 사유" value={reason} disabled={model.publicDemo}
            onChange={value => { setReason(value); setExpiryConfirmed(false); setError(undefined); setSuccess(undefined); }} />
        </FormTable></fieldset>
        {rule && ruleChanged && <p className="hint">변경한 회사 기준을 저장한 뒤 미리 보세요.</p>}
        <div className="wl-form-actions"><button type="submit" className="t-btn" disabled={!canPreview}>
          {busy === 'preview' ? '조회 중…' : '미리보기'}</button></div>
      </form>
      {preview && <div ref={resultArea}><PreviewResults result={preview} today={model.today} /></div>}
      {canApply && preview && <div className="annual-cycle-confirm">
        {expiryDays > 0 && <div><strong>소멸 합계 {expiryDays}일</strong>
          <label><input type="checkbox" checked={expiryConfirmed} disabled={locked}
            onChange={event => setExpiryConfirmed(event.target.checked)} />소멸 대상과 일수를 확인했습니다.</label></div>}
        <button type="button" className="t-btn primary" disabled={Boolean(busy) || !checkedReason.validReason || (expiryDays > 0 && !expiryConfirmed)}
          onClick={() => void write(retry?.kind === 'apply' ? retry : { kind: 'apply', count: preview.rows.length,
            command: { year: preview.year, token: preview.token, reason: checkedReason.trimmed, commandId: crypto.randomUUID() } })}>
          {retry?.kind === 'apply' ? '같은 명령으로 반영 결과 확인' : busy === 'apply' ? '반영 중…' : '원장에 반영'}
        </button>
      </div>}
      {messages('cycle')}
    </section>
  </div>;
}
