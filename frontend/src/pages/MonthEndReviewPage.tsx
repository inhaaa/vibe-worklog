import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchPage, PageRequestError } from '../transport';
import type { CsrfToken } from '../types';
import { CheckoutFollowUpForm } from './CheckoutFollowUpForm';
import { followUpStatusNames, type CheckoutFollowUp } from './CheckoutFollowUps';
import { ErrorNotice, Field, Form, SelectField } from '../ui';
import { PageHeader } from '../components/PageHeader';
import { CompanyAttendanceViews, monthCloseSource, monthEndReasons } from './CompanyAttendanceNavigation';
import { dayKey, MonthEndBatch, type BatchSelection } from './MonthEndBatch';
import { MonthClosePanel, type MonthCloseMode } from './MonthClosePanel';
import { monthEndAccessDenied } from './monthEndApi';
import './MonthEndReviewPage.css';

type Reason = keyof typeof monthEndReasons;

interface ReviewRow {
  employeeId: string;
  employeeNo: string;
  employeeName: string;
  organizationName: string | null;
  workDate: string;
  reasons: Reason[];
  resultId: string | null;
  pendingRequestId: string | null;
  checkoutFollowUpEligible?: boolean;
  checkoutFollowUp?: CheckoutFollowUp | null;
}

export interface MonthEndReview {
  month: string;
  latestMonth: string;
  organizationId: string | null;
  options: { id: string; code: string; name: string }[];
  q: string;
  reason: Reason | 'ALL';
  page: number;
  hasPrevious: boolean;
  hasNext: boolean;
  rows: ReviewRow[];
  error: string | null;
}

const path = '/company/attendance';
function listHref(model: MonthEndReview, page = model.page, source: string | null = null) {
  const search = new URLSearchParams({ view: 'month-end', month: model.month, q: model.q, reason: model.reason, page: String(page) });
  if (model.organizationId) search.set('organizationId', model.organizationId);
  if (source) search.set('source', source);
  return path + '?' + search;
}

export function canManageMonthEnd(data: Record<string, unknown>) {
  const roles = (data.workspacePrincipal as { roles?: string[] } | undefined)?.roles;
  return data.canViewCheckout === true && roles?.includes('ATTENDANCE_ADMIN') === true;
}

interface EmployeeDayProps {
  row: ReviewRow;
  returnTo: string;
  canRequest: boolean;
  csrf: CsrfToken;
  reload: () => void;
  active: string | null;
  setActive: (row: string | null) => void;
  selected: boolean;
  onSelect: () => void;
  selectionLocked: boolean;
  followUpLocked: boolean;
}

function EmployeeDay({ row, returnTo, canRequest, csrf, reload, active, setActive, selected, onSelect,
  selectionLocked, followUpLocked }: EmployeeDayProps) {
  const trigger = useRef<HTMLButtonElement>(null);
  const key = dayKey(row);
  const followUp = row.checkoutFollowUp;
  const requestId = row.pendingRequestId ?? followUp?.approvalRequestId;
  const eligible = canRequest && row.checkoutFollowUpEligible === true && !row.pendingRequestId
    && (!followUp || followUp.status === 'RESOLVED');
  const back = new URLSearchParams({ returnTo });
  const worktime = new URLSearchParams({ employeeId: row.employeeId, month: row.workDate.slice(0, 7), workDate: row.workDate, returnTo });
  return <li className="month-review-row" aria-label={row.workDate + ' ' + row.employeeNo + ' ' + row.employeeName}>
    {canRequest ? <label className="month-review-select">
      <input type="checkbox" aria-label={[row.employeeNo, row.employeeName, row.workDate, '선택'].join(' ')}
        checked={selected} disabled={selectionLocked} onChange={onSelect} />
      <time dateTime={row.workDate}>{row.workDate}</time>
    </label> : <time dateTime={row.workDate}>{row.workDate}</time>}
    <div><strong>{row.employeeNo} · {row.employeeName}</strong><small>{row.organizationName ?? '소속 미등록'}</small></div>
    <div className="month-review-reasons">
      {row.reasons.filter(reason => !followUp || monthEndReasons[reason] !== followUpStatusNames[followUp.status])
        .map(reason => <span key={reason}>{monthEndReasons[reason]}</span>)}
      {followUp && <><strong className="checkout-follow-up-state">{followUpStatusNames[followUp.status]}</strong>
        <span className="checkout-follow-up-note">{followUp.reason}</span></>}
    </div>
    <div className="actions">
      <a className="t-btn" href={'/admin/attendance?' + worktime}>근태 집계</a>
      {row.resultId && <a className="t-btn" href={'/worktime/results/' + encodeURIComponent(row.resultId) + '?' + back}>계산 근거</a>}
      {requestId && <a className="t-btn" href={'/requests/' + encodeURIComponent(requestId) + '?' + back}>
        {followUp?.status === 'REQUESTED' && !row.pendingRequestId ? '이전 정정 상세' : '정정 상세'}</a>}
      {eligible && <button className="t-btn" type="button" ref={trigger} aria-expanded={active === key}
        disabled={active !== null || followUpLocked} onClick={() => setActive(key)}>보완 요청</button>}
    </div>
    {eligible && active === key && <CheckoutFollowUpForm employee={row} csrf={csrf} reload={reload}
      close={() => { setActive(null); requestAnimationFrame(() => trigger.current?.focus()); }} />}
  </li>;
}

export function MonthEndReviewPage({ model, canRequest, csrf, reload }: {
  model: MonthEndReview; canRequest: boolean; csrf: CsrfToken; reload: () => void;
}) {
  const [active, setActive] = useState<string | null>(null);
  const [updated, setUpdated] = useState<{ model: MonthEndReview; csrf: CsrfToken }>();
  const [accessLost, deny] = useState<PageRequestError>();
  const [listCurrent, setListCurrent] = useState(true);
  const [selection, setSelection] = useState(new Set<string>());
  const [target, setTarget] = useState<BatchSelection>();
  const [queryChanged, setQueryChanged] = useState(false);
  const [monthClose, setMonthClose] = useState<MonthCloseMode>('unknown');
  const [closureList, setClosureList] = useState<'current' | 'loading' | 'failed'>('current');
  const selectAll = useRef<HTMLInputElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const m = updated?.model ?? model;
  const token = updated?.csrf ?? csrf;
  const source = monthCloseSource(m.month);
  const returnTo = listHref(m, m.page, source);
  const chosen = m.rows.filter(row => selection.has(dayKey(row)));
  const all = m.rows.length > 0 && chosen.length === m.rows.length;
  const filterLocked = Boolean(target) || monthClose === 'working';
  const selectionLocked = active !== null || Boolean(target) || queryChanged || monthClose !== 'open';

  useEffect(() => {
    if (selectAll.current) selectAll.current.indeterminate = chosen.length > 0 && !all;
  }, [chosen.length, all]);

  const refresh = useCallback(async (signal: AbortSignal) => {
    const { envelope, status } = await fetchPage(returnTo, { signal });
    if (signal.aborted) return;
    if (envelope.page === 'login') throw new PageRequestError(401, '다시 로그인해 주세요.');
    if (envelope.page === 'company/attendance' && !canManageMonthEnd(envelope.data)) {
      throw new PageRequestError(403, '월말 업무 권한을 확인해 주세요.');
    }
    const next = envelope.data.monthEndReview as MonthEndReview | undefined;
    if (status >= 400 || envelope.page !== 'company/attendance' || !next || next.error
      || !Array.isArray(next.rows) || listHref(next, next.page, source) !== returnTo) throw new Error('월말 목록을 갱신하지 못했습니다.');
    setUpdated({ model: next, csrf: envelope.csrf });
    setListCurrent(true);
    setSelection(new Set());
  }, [returnTo, source]);

  useEffect(() => {
    if (closureList !== 'loading') return;
    const controller = new AbortController();
    refresh(controller.signal).then(() => {
      if (!controller.signal.aborted) setClosureList('current');
    }).catch(failure => {
      if (controller.signal.aborted) return;
      if (monthEndAccessDenied(failure)) deny(failure);
      else setClosureList('failed');
    });
    return () => controller.abort();
  }, [closureList, refresh]);

  function toggle(row: ReviewRow) {
    if (selectionLocked) return;
    setSelection(current => {
      const next = new Set(current);
      const key = dayKey(row);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  }
  function closeBatch(confirmed: boolean) {
    setTarget(undefined);
    requestAnimationFrame(() => (confirmed ? heading.current : trigger.current)?.focus());
  }

  if (accessLost) {
    const signIn = accessLost.reauthenticate || accessLost.status === 401;
    return <><PageHeader title="회사 근무현황" />
      <ErrorNotice message={signIn ? '로그인 상태를 확인할 수 없습니다. 다시 로그인해 주세요.' : '월말 업무 권한을 확인해 주세요.'} />
      {signIn ? <a className="t-btn" href="/login">다시 로그인</a>
        : <button className="t-btn" type="button" onClick={reload}>목록 다시 조회</button>}
    </>;
  }
  return <>
    <PageHeader title="회사 근무현황" />
    <CompanyAttendanceViews active="month-end" />
    <Form action={path} method="get" className="wl-querybar" onChange={event => {
      const fields = new FormData(event.currentTarget);
      setQueryChanged(fields.get('month') !== m.month || (fields.get('organizationId') || null) !== m.organizationId
        || String(fields.get('q') ?? '').trim() !== m.q || fields.get('reason') !== m.reason);
      setSelection(new Set());
    }}>
      <input type="hidden" name="view" value="month-end" />
      <Field label="조회 월" name="month" type="month" value={m.month} max={m.latestMonth} disabled={filterLocked} required />
      <SelectField label="근무일 당시 소속" name="organizationId" value={m.organizationId} disabled={filterLocked}>
        <option value="">전체 조직</option>
        {m.options.map(org => <option key={org.id} value={org.id}>{org.code} · {org.name}</option>)}
      </SelectField>
      <Field label="사번 또는 이름" name="q" value={m.q} maxLength={100} disabled={filterLocked} />
      <SelectField label="확인 사유" name="reason" value={m.reason} disabled={filterLocked}>
        <option value="ALL">전체 사유</option>
        {Object.entries(monthEndReasons).map(([key,label]) => <option key={key} value={key}>{label}</option>)}
      </SelectField>
      <div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit" disabled={filterLocked}>조회</button></div>
    </Form>
    {queryChanged && <p className="t-subtle" role="status">변경한 조건으로 조회해 주세요. 현재 목록은 {m.month} 기준입니다.</p>}
    <ErrorNotice message={m.error} />
    {!m.error && canRequest && <MonthClosePanel month={m.month} source={source ?? returnTo} csrf={token} onMode={setMonthClose} deny={deny}
      onStatusChanged={() => { setListCurrent(false); setClosureList('loading'); }}
      otherWork={active !== null || Boolean(target) || queryChanged || chosen.length > 0 || !listCurrent} />}
    {!m.error && <section className="wl-section month-review">
      <h2 ref={heading} id="month-end-results" tabIndex={-1}>월말 확인 대상</h2>
      {source && <a className="t-btn" href={source + '#month-close-title'}>월 마감 점검으로 돌아가기</a>}
      {closureList === 'loading' && <p role="status">같은 조건으로 최신 월말 목록을 조회하고 있습니다.</p>}
      {closureList === 'failed' && <><ErrorNotice message="최신 월말 확인 대상 목록을 불러오지 못했습니다. 다시 조회해 주세요." />
        <button className="t-btn" type="button" onClick={() => setClosureList('loading')}>월말 목록 다시 조회</button></>}
      {m.month === m.latestMonth && <p className="t-subtle">이번 달은 어제 근무일까지 조회합니다.</p>}
      {listCurrent && canRequest && m.rows.length > 0 && <div className="month-review-selection">
        <label className="month-review-select"><input ref={selectAll} type="checkbox" checked={all} disabled={selectionLocked}
          onChange={() => setSelection(all ? new Set() : new Set(m.rows.map(dayKey)))} />현재 페이지 전체 선택</label>
        {!target && <>
          <span role="status">선택 {chosen.length}건</span>
          <button ref={trigger} className="t-btn primary" type="button" disabled={selectionLocked || !chosen.length}
            onClick={() => setTarget({ month: m.month, organizationId: m.organizationId, q: m.q, reason: m.reason, page: m.page,
              rows: chosen.map(({ employeeId, workDate }) => ({ employeeId, workDate })) })}>선택 재집계</button>
        </>}
      </div>}
      {canRequest && target && <MonthEndBatch target={target} csrf={token} returnTo={returnTo}
        close={closeBatch} onCalculated={() => setListCurrent(false)} refresh={refresh} deny={deny} reload={reload} />}
      {listCurrent && (m.rows.length === 0 ? <p>선택한 조건에 해당하는 월말 확인 대상이 없습니다.</p> : <>
        <div className="month-review-row month-review-columns" aria-hidden="true"><span>근무일</span><span>사원·당시 소속</span><span>확인 사유</span><span>상세</span></div>
        <ul className="month-review-list" aria-label="월말 확인 사원과 근무일">
          {m.rows.map(row => <EmployeeDay key={dayKey(row)} row={row} returnTo={returnTo}
            canRequest={canRequest} csrf={token} reload={reload} active={active} setActive={setActive}
            selected={selection.has(dayKey(row))} onSelect={() => toggle(row)} selectionLocked={selectionLocked}
            followUpLocked={selectionLocked || chosen.length > 0} />)}
        </ul>
      </>)}
      {listCurrent && (m.hasPrevious || m.hasNext) && <nav className="actions pagination" aria-label="확인 대상 페이지">
        {m.hasPrevious && (filterLocked ? <span aria-disabled="true">이전</span> : <a href={listHref(m,m.page - 1,source)}>이전</a>)}
        <span>{m.page + 1}페이지</span>
        {m.hasNext && (filterLocked ? <span aria-disabled="true">다음</span> : <a href={listHref(m,m.page + 1,source)}>다음</a>)}
      </nav>}
    </section>}
  </>;
}
