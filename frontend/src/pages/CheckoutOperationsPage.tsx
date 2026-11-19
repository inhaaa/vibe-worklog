import { ErrorNotice, Field, Form, SelectField, duration, fmtDateTime, fmtTime } from '../ui';
import { PageHeader } from '../components/PageHeader';
import { SectionHeader } from '../components/SectionHeader';
import { CompanyAttendanceViews } from './CompanyAttendanceNavigation';
import type { WorkMode } from './selectiveSchedule';
import './CheckoutOperationsPage.css';

const path = '/company/attendance';
const recordLabels = { MISSING: '퇴근 누락', PENDING: '결재 대기', CORRECTED: '정정 반영' };
const resultLabels = {
  WORK_CONFIRMATION_REQUIRED: '근무 확인 필요',
  RECALCULATION_REQUIRED: '재집계 필요',
  REVIEW_REQUIRED: '집계 확인 필요',
  CURRENT: '집계 반영',
};
const requestLabels = { PENDING: '결재 대기', APPROVED: '승인', REJECTED: '반려', WITHDRAWN: '철회' };

interface CheckoutRow {
  employeeId: string;
  employeeNo: string;
  employeeName: string;
  organizationName: string | null;
  workDate: string;
  scheduledEnd: string | null;
  workMode?: WorkMode | null;
  sourceIn: string | null;
  sourceOut: string | null;
  effectiveIn: string | null;
  effectiveOut: string | null;
  requestId: string | null;
  requestState: keyof typeof requestLabels | null;
  recordState: keyof typeof recordLabels;
  resultState: keyof typeof resultLabels;
  actualWorkMillis: number | null;
  calculatedAt: string | null;
  resultReason: string | null;
}

export interface CheckoutOperations {
  date: string;
  latestDate: string;
  organizationId: string | null;
  options: { id: string; code: string; name: string }[];
  q: string;
  status: 'ALL' | keyof typeof recordLabels;
  page: number;
  hasPrevious: boolean;
  hasNext: boolean;
  rows: CheckoutRow[];
  error: string | null;
}

function checkoutHref(model: CheckoutOperations, page = model.page) {
  const query = new URLSearchParams({
    view: 'checkout', date: model.date, q: model.q, status: model.status, page: String(page),
  });
  if (model.organizationId) query.set('organizationId', model.organizationId);
  return `${path}?${query}`;
}

function clock(value: string | null) {
  return value ? fmtTime(fmtDateTime(value).slice(11)) : '미기록';
}

function EmployeeCheckout({ row, returnTo }: { row: CheckoutRow; returnTo: string }) {
  const detail = new URLSearchParams({ returnTo });
  const result = new URLSearchParams({
    employeeId: row.employeeId, month: row.workDate.slice(0, 7), workDate: row.workDate, returnTo,
  });
  return <li>
    <details className="checkout-row">
      <summary className="checkout-row-grid">
        <strong>{row.employeeNo} · {row.employeeName}</strong>
        <span className="checkout-secondary" data-label="당시 소속">{row.organizationName ?? '소속 미등록'}</span>
        <span className="checkout-secondary" data-label="원천 출근">{clock(row.sourceIn)}</span>
        <span className="checkout-secondary" data-label="원천 퇴근">{clock(row.sourceOut)}</span>
        <strong className="checkout-effective" data-label="유효 퇴근">{clock(row.effectiveOut)}</strong>
        <span className="checkout-record-state" data-state={row.recordState}>{recordLabels[row.recordState]}</span>
        <span className="checkout-result-state">{resultLabels[row.resultState]}</span>
        <span className="checkout-expand" aria-hidden="true" />
      </summary>
      <div className="checkout-details">
        <dl className="checkout-facts">
          <div><dt>근무일</dt><dd>{row.workDate}</dd></div>
          <div><dt>당시 소속</dt><dd>{row.organizationName ?? '소속 미등록'}</dd></div>
          <div><dt>{row.workMode === 'SELECTIVE' ? '선택 가능 종료' : '예정 퇴근'}</dt><dd>{fmtTime(row.scheduledEnd)}</dd></div>
          <div><dt>원천 출근</dt><dd>{clock(row.sourceIn)}</dd></div>
          <div><dt>원천 퇴근</dt><dd>{clock(row.sourceOut)}</dd></div>
          <div><dt>유효 출근</dt><dd>{clock(row.effectiveIn)}</dd></div>
          <div><dt>유효 퇴근</dt><dd>{clock(row.effectiveOut)}</dd></div>
          <div><dt>최근 정정</dt><dd>{row.requestState ? requestLabels[row.requestState] : '신청 없음'}</dd></div>
          <div><dt>실근로시간</dt><dd>{row.actualWorkMillis == null ? '미확정' : duration(row.actualWorkMillis)}</dd></div>
          <div><dt>집계 시각 (서울)</dt><dd>{row.calculatedAt ? fmtDateTime(row.calculatedAt) : '미확정'}</dd></div>
        </dl>
        {row.resultReason && <p className="t-subtle">{row.resultReason}</p>}
        <div className="actions">
          {row.requestId && <a className="t-btn" href={`/requests/${encodeURIComponent(row.requestId)}?${detail}`}>정정 상세</a>}
          <a className="t-btn" href={`/admin/attendance?${result}`}>근태 집계·Excel</a>
        </div>
      </div>
    </details>
  </li>;
}

export function CheckoutOperationsPage({ model: m }: { model: CheckoutOperations }) {
  const returnTo = checkoutHref(m);
  return <>
    <PageHeader title="회사 근무현황" />
    <CompanyAttendanceViews active="checkout" />
    <Form action={path} method="get" className="wl-querybar">
      <input type="hidden" name="view" value="checkout" />
      <Field label="근무일" name="date" type="date" value={m.date} max={m.latestDate} required />
      <SelectField label="근무일 당시 소속" name="organizationId" value={m.organizationId}>
        <option value="">전체 조직</option>
        {m.options.map(org => <option key={org.id} value={org.id}>{org.code} · {org.name}</option>)}
      </SelectField>
      <Field label="사번 또는 이름" name="q" value={m.q} maxLength={100} />
      <SelectField label="정정 상태" name="status" value={m.status}>
        <option value="ALL">전체</option>
        {Object.entries(recordLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
      </SelectField>
      <div className="wl-querybar-actions"><button type="submit" className="t-btn wl-query-submit">조회</button></div>
    </Form>
    <ErrorNotice message={m.error} />
    {!m.error && <section className="wl-section checkout-list">
      <SectionHeader title="퇴근 누락 현황" />
      {m.rows.length === 0
        ? <p>{m.date}에 선택한 조건으로 조회된 퇴근 누락이 없습니다.</p>
        : <>
          <div className="checkout-row-grid checkout-columns" aria-hidden="true">
            <span>사번·이름</span><span>당시 소속</span><span>원천 출근</span><span>원천 퇴근</span>
            <span>유효 퇴근</span><span>정정 상태</span><span>집계 상태</span>
          </div>
          <ul className="checkout-rows">
            {m.rows.map(row => <EmployeeCheckout key={row.employeeId} row={row} returnTo={returnTo} />)}
          </ul>
        </>}
      {(m.hasPrevious || m.hasNext) && <nav className="actions pagination" aria-label="목록 페이지">
        {m.hasPrevious && <a href={checkoutHref(m, m.page - 1)}>이전</a>}
        <span>{m.page + 1}페이지</span>
        {m.hasNext && <a href={checkoutHref(m, m.page + 1)}>다음</a>}
      </nav>}
    </section>}
  </>;
}
