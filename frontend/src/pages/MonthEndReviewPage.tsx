import { ErrorNotice, Field, Form, SelectField } from '../ui';
import { PageHeader } from '../components/PageHeader';
import { CompanyAttendanceViews, monthEndReasons } from './CompanyAttendanceNavigation';
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
function listHref(model: MonthEndReview, page = model.page) {
  const search = new URLSearchParams({ view: 'month-end', month: model.month, q: model.q, reason: model.reason, page: String(page) });
  if (model.organizationId) search.set('organizationId', model.organizationId);
  return path + '?' + search;
}

function EmployeeDay({ row, returnTo }: { row: ReviewRow; returnTo: string }) {
  const back = new URLSearchParams({ returnTo });
  const worktime = new URLSearchParams({ employeeId: row.employeeId, month: row.workDate.slice(0, 7), workDate: row.workDate, returnTo });
  return <li className="month-review-row" aria-label={row.workDate + ' ' + row.employeeNo + ' ' + row.employeeName}>
    <time dateTime={row.workDate}>{row.workDate}</time>
    <div><strong>{row.employeeNo} · {row.employeeName}</strong><small>{row.organizationName ?? '소속 미등록'}</small></div>
    <div className="month-review-reasons">{row.reasons.map(reason => <span key={reason}>{monthEndReasons[reason]}</span>)}</div>
    <div className="actions">
      <a className="t-btn" href={'/admin/attendance?' + worktime}>근태 집계</a>
      {row.resultId && <a className="t-btn" href={'/worktime/results/' + encodeURIComponent(row.resultId) + '?' + back}>계산 근거</a>}
      {row.pendingRequestId && <a className="t-btn" href={'/requests/' + encodeURIComponent(row.pendingRequestId) + '?' + back}>정정 상세</a>}
    </div>
  </li>;
}

export function MonthEndReviewPage({ model: m }: { model: MonthEndReview }) {
  return <>
    <PageHeader title="회사 근무현황" />
    <CompanyAttendanceViews active="month-end" />
    <Form action={path} method="get" className="wl-querybar">
      <input type="hidden" name="view" value="month-end" />
      <Field label="조회 월" name="month" type="month" value={m.month} max={m.latestMonth} required />
      <SelectField label="근무일 당시 소속" name="organizationId" value={m.organizationId}>
        <option value="">전체 조직</option>
        {m.options.map(org => <option key={org.id} value={org.id}>{org.code} · {org.name}</option>)}
      </SelectField>
      <Field label="사번 또는 이름" name="q" value={m.q} maxLength={100} />
      <SelectField label="확인 사유" name="reason" value={m.reason}>
        <option value="ALL">전체 사유</option>
        {Object.entries(monthEndReasons).map(([key,label]) => <option key={key} value={key}>{label}</option>)}
      </SelectField>
      <div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div>
    </Form>
    <ErrorNotice message={m.error} />
    {!m.error && <section className="wl-section month-review">
      <h2>월말 확인 대상</h2>
      {m.month === m.latestMonth && <p className="t-subtle">이번 달은 어제 근무일까지 조회합니다.</p>}
      {m.rows.length === 0 ? <p>선택한 조건에 해당하는 월말 확인 대상이 없습니다.</p> : <>
        <div className="month-review-row month-review-columns" aria-hidden="true"><span>근무일</span><span>사원·당시 소속</span><span>확인 사유</span><span>상세</span></div>
        <ul className="month-review-list" aria-label="월말 확인 사원과 근무일">
          {m.rows.map(row => <EmployeeDay key={row.workDate + '/' + row.employeeId} row={row} returnTo={listHref(m)} />)}
        </ul>
      </>}
      {(m.hasPrevious || m.hasNext) && <nav className="actions pagination" aria-label="확인 대상 페이지">
        {m.hasPrevious && <a href={listHref(m,m.page - 1)}>이전</a>}
        <span>{m.page + 1}페이지</span>
        {m.hasNext && <a href={listHref(m,m.page + 1)}>다음</a>}
      </nav>}
    </section>}
  </>;
}
