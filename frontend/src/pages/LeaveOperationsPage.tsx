import { ErrorNotice, Field, Form, SelectField, fmtDateTime } from '../ui';
import { PageHeader } from '../components/PageHeader';
import { SectionHeader } from '../components/SectionHeader';
import './LeaveOperationsPage.css';

const path = '/admin/requests';
const statuses = { PENDING: '대기', APPROVED: '승인', REJECTED: '반려', ALL: '전체' } as const;
type Status = keyof typeof statuses;
type RequestKind = 'LEAVE_DAY' | 'CANCEL_LEAVE';

interface LeaveRow {
  id: string;
  employeeNo: string;
  employeeName: string;
  organizationName: string | null;
  leaveTypeName: string;
  requestKind: RequestKind;
  from: string;
  to: string;
  quantityDays: number;
  status: Exclude<Status, 'ALL'> | 'WITHDRAWN';
  effectStatus: string;
  approverName: string | null;
  decidedByName: string | null;
  submittedAt: string;
  decidedAt: string | null;
  originalRequestId: string | null;
}

export interface LeaveOperations {
  status: Status;
  leaveTypeCode: string | null;
  requestKind: RequestKind | '' | null;
  from: string | null;
  to: string | null;
  organizationId: string | null;
  q: string;
  page: number;
  hasNext: boolean;
  asOf: string;
  leaveTypes: { code: string; name: string }[];
  organizations: { id: string; code: string; name: string }[];
  counts: Record<Status, number>;
  rows: LeaveRow[];
  error: string | null;
}

function listHref(model: LeaveOperations, status = model.status, page = model.page) {
  const query = new URLSearchParams({ scope: 'LEAVE', status, page: String(page) });
  for (const key of ['leaveTypeCode', 'requestKind', 'from', 'to', 'organizationId', 'q'] as const) {
    const value = model[key];
    if (value) query.set(key, value);
  }
  return `${path}?${query}`;
}

function requestHref(id: string, returnTo: string) {
  return `/requests/${encodeURIComponent(id)}?${new URLSearchParams({ returnTo })}`;
}

function LeaveOperationRow({ row, returnTo }: { row: LeaveRow; returnTo: string }) {
  const status = row.status === 'WITHDRAWN' ? '철회' : statuses[row.status];
  const processed = row.status === 'APPROVED' || row.status === 'REJECTED';
  const effective = row.requestKind === 'LEAVE_DAY' && row.effectStatus === 'EFFECTIVE';
  const canceled = row.requestKind === 'LEAVE_DAY' && row.effectStatus === 'CANCELED';
  return <li>
    <details data-request-id={row.id} className="leave-operations-row">
      <summary className="leave-operations-grid">
        <strong className="leave-operations-employee">{row.employeeNo} · {row.employeeName}</strong>
        <span className="leave-operations-secondary" data-label="현재 소속">{row.organizationName ?? '소속 미등록'}</span>
        <span className="leave-operations-type">{row.leaveTypeName}<small>{row.requestKind === 'CANCEL_LEAVE' ? '취소 신청' : '휴가 신청'}</small></span>
        <span className="leave-operations-period">{row.from}{row.from !== row.to && ` ~ ${row.to}`}<small>{row.quantityDays}일</small></span>
        <span className="leave-operations-status">
          <strong>{status}</strong>
          {(effective || canceled) && <small className="leave-operations-effect">{canceled ? '취소됨' : '유효'}</small>}
        </span>
        <span className="leave-operations-secondary" data-label={processed ? '처리자' : '결재자'}>
          {processed ? (row.decidedByName ?? '미확인') : row.status === 'PENDING' ? (row.approverName ?? '미확인') : '—'}
        </span>
        <span className="leave-operations-secondary leave-operations-time" data-label="접수·처리">
          <span><small>접수</small> <time dateTime={row.submittedAt}>{fmtDateTime(row.submittedAt)}</time></span>
          {processed && <span><small>처리</small> {row.decidedAt ? <time dateTime={row.decidedAt}>{fmtDateTime(row.decidedAt)}</time> : '미확인'}</span>}
        </span>
        <span className="leave-operations-expand" aria-hidden="true" />
      </summary>
      <div className="actions leave-operations-actions">
        <a className="t-btn" href={requestHref(row.id, returnTo)}>신청 상세</a>
        {row.originalRequestId && <a className="t-btn" href={requestHref(row.originalRequestId, returnTo)}>원래 휴가 신청</a>}
      </div>
    </details>
  </li>;
}

export function LeaveOperationsPage({ model: m }: { model: LeaveOperations }) {
  const returnTo = listHref(m);
  return <>
    <PageHeader title="휴가 신청 현황" />
    <Form key={returnTo} action={path} method="get" className="wl-querybar">
      <input type="hidden" name="scope" value="LEAVE" />
      <input type="hidden" name="status" value={m.status} />
      <SelectField label="휴가 종류" name="leaveTypeCode" value={m.leaveTypeCode}>
        <option value="">전체 휴가 종류</option>
        {m.leaveTypes.map(option => <option key={option.code} value={option.code}>{option.name}</option>)}
      </SelectField>
      <SelectField label="신청 구분" name="requestKind" value={m.requestKind}>
        <option value="">전체 신청 구분</option><option value="LEAVE_DAY">휴가 신청</option><option value="CANCEL_LEAVE">취소 신청</option>
      </SelectField>
      <div className="leave-operations-dates" role="group" aria-label="휴가 대상 기간">
        <Field label="시작일" name="from" type="date" value={m.from} />
        <Field label="종료일" name="to" type="date" value={m.to} />
      </div>
      <SelectField label="현재 소속" name="organizationId" value={m.organizationId}>
        <option value="">전체 조직</option>
        {m.organizations.map(org => <option key={org.id} value={org.id}>{org.code} · {org.name}</option>)}
      </SelectField>
      <Field label="사번 또는 이름" name="q" value={m.q} maxLength={100} />
      <div className="wl-querybar-actions">
        <a className="t-btn wl-query-reset" href={`${path}?scope=LEAVE`}>초기화</a>
        <button type="submit" className="t-btn wl-query-submit">조회</button>
      </div>
    </Form>
    <p className="t-subtle">조회 기준일 {m.asOf} (서울)</p>
    <ErrorNotice message={m.error} />
    {!m.error && <section className="wl-section leave-operations-list">
      <SectionHeader title="신청 목록" />
      <nav className="leave-operations-tabs" aria-label="휴가 신청 상태">
        {Object.entries(statuses).map(([key, label]) => {
          const status = key as Status;
          return <a key={status} href={listHref(m, status, 0)} aria-current={m.status === status ? 'page' : undefined}>
            {label} <span>{m.counts[status]}</span>
          </a>;
        })}
      </nav>
      {m.rows.length === 0
        ? <p>선택한 조건에 맞는 {m.status === 'ALL' ? '' : `${statuses[m.status]} `}휴가 신청이 없습니다.</p>
        : <>
          <div className="leave-operations-grid leave-operations-columns" aria-hidden="true">
            <span>사번·이름</span><span>현재 소속</span><span>휴가 종류·구분</span><span>대상 기간·일수</span>
            <span>상태·효력</span><span>결재자·처리자</span><span>접수·처리 시각 (서울)</span>
          </div>
          <ul className="leave-operations-rows">
            {m.rows.map(row => <LeaveOperationRow key={row.id} row={row} returnTo={returnTo} />)}
          </ul>
        </>}
      {(m.page > 0 || m.hasNext) && <nav className="actions pagination" aria-label="목록 페이지">
        {m.page > 0 && <a href={listHref(m, m.status, m.page - 1)}>이전</a>}
        <span>{m.page + 1}페이지</span>
        {m.hasNext && <a href={listHref(m, m.status, m.page + 1)}>다음</a>}
      </nav>}
    </section>}
  </>;
}
