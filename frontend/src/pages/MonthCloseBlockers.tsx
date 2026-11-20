import { PageHeader } from '../components/PageHeader';
import { CompanyAttendanceViews, monthCloseKinds, monthCloseListHref, monthCloseSource } from './CompanyAttendanceNavigation';
import { requestKindName } from './leavePart';

interface BlockerRow {
  id: string;
  employeeId: string;
  employeeNo: string;
  employeeName: string;
  kind: string;
  fromDate: string;
  toDate: string;
  outOfEmployment: boolean;
}

export interface MonthCloseBlockers {
  month: string;
  kind: keyof typeof monthCloseKinds;
  page: number;
  total: number;
  hasNext: boolean;
  rows: BlockerRow[];
}

function BlockerRowView({ row, pending, month, returnTo }: { row: BlockerRow; pending: boolean; month: string; returnTo: string }) {
  const dateRange = row.fromDate !== row.toDate;
  let label = '진행 중';
  if (pending) {
    label = row.kind === 'LEAVE_DAY' ? '휴가 신청' : row.kind === 'CANCEL_LEAVE' ? '휴가 취소' : requestKindName(row.kind);
  }
  const detail = pending ? '/requests/' + encodeURIComponent(row.id) + '?' + new URLSearchParams({ returnTo })
    : '/admin/attendance?' + new URLSearchParams({ employeeId: row.employeeId, month, workDate: row.fromDate, returnTo });
  return <li className="month-review-row">
    <div><time dateTime={row.fromDate}>{row.fromDate}</time>{dateRange && <> ~ <time dateTime={row.toDate}>{row.toDate}</time></>}</div>
    <strong>{row.employeeNo} · {row.employeeName}</strong>
    <div>{label}</div>
    <div className="actions">{!pending && row.outOfEmployment ? <span>재직기간 밖 근무일입니다. 사번과 날짜로 재직기간을 확인해 주세요.</span>
      : <a className="t-btn" href={detail}>{pending ? '신청 상세' : '근태 집계'}</a>}</div>
  </li>;
}

export function MonthCloseBlockersPage({ model: m }: { model: MonthCloseBlockers }) {
  const title = monthCloseKinds[m.kind];
  const source = monthCloseSource(m.month);
  const check = source ?? '/company/attendance?' + new URLSearchParams({ view: 'month-end', month: m.month });
  const returnTo = monthCloseListHref(m.month, m.kind, m.page, source);
  const pending = m.kind === 'PENDING_REQUEST';
  return <>
    <PageHeader title="회사 근무현황" />
    <CompanyAttendanceViews active="month-end" />
    <section className="wl-section month-review">
      <div className="month-close-blockers-heading">
        <h2>{title}</h2>
        <a className="t-btn" href={check + '#month-close-title'}>월 마감 점검으로 돌아가기</a>
      </div>
      <p className="t-subtle">{m.month} · 회사 전체 · 현재 {m.total}건</p>
      {m.rows.length ? <>
        <div className="month-review-row month-review-columns" aria-hidden="true">
          <span>{pending ? '신청 대상 기간' : '근무일'}</span><span>사원</span><span>{pending ? '신청 종류' : '처리 상태'}</span><span>상세</span>
        </div>
        <ul className="month-review-list" aria-label={title}>
          {m.rows.map(row => <BlockerRowView key={row.id} row={row} pending={pending} month={m.month} returnTo={returnTo} />)}
        </ul>
      </> : <p role="status">{m.total > 0 ? '이 페이지에 표시할 항목이 없습니다.' : '현재 ' + title + '이 없습니다.'}</p>}
      {(m.page > 0 || m.hasNext) && <nav className="actions pagination" aria-label="미처리 목록 페이지">
        {m.page > 0 && <a href={monthCloseListHref(m.month, m.kind, m.page - 1, source)}>이전</a>}
        <span>{m.page + 1}페이지</span>
        {m.hasNext && <a href={monthCloseListHref(m.month, m.kind, m.page + 1, source)}>다음</a>}
      </nav>}
    </section>
  </>;
}
