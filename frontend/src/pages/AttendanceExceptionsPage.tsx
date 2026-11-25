import { ErrorNotice, Field, Form, SelectField } from '../ui';
import { PageHeader } from '../components/PageHeader';
import { CompanyAttendanceViews } from './CompanyAttendanceNavigation';

const kinds = { ALL: '전체', LATE: '지각', EARLY_LEAVE: '조퇴', ABSENT: '결근' } as const;

export interface AttendanceExceptions {
  month: string;
  kind: keyof typeof kinds;
  counts: { late: number; earlyLeave: number; absent: number };
  rows: { employeeId: string; employeeNo: string; employeeName: string; workDate: string; label: string }[];
  error: string | null;
}

export function AttendanceExceptionsPage({ model: m }: { model: AttendanceExceptions }) {
  return <>
    <PageHeader title="회사 근무현황" />
    <CompanyAttendanceViews active="exceptions" />
    <Form action="/company/attendance" method="get" className="wl-querybar">
      <input type="hidden" name="view" value="exceptions" />
      <Field label="조회 월" name="month" type="month" value={m.month} required />
      <SelectField label="구분" name="kind" value={m.kind}>
        {Object.entries(kinds).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
      </SelectField>
      <div className="wl-querybar-actions"><button type="submit" className="t-btn wl-query-submit">조회</button></div>
    </Form>
    <ErrorNotice message={m.error} />
    {!m.error && <section className="wl-section">
      <p className="t-subtle">지각 {m.counts.late} · 조퇴 {m.counts.earlyLeave} · 결근 {m.counts.absent}</p>
      {m.rows.length === 0 ? <p role="status">{m.month}에 조회된 {m.kind === 'ALL' ? '지각·조퇴·결근' : kinds[m.kind]}이 없습니다.</p>
        : <div className="table-scroll"><table>
          <thead><tr><th>사번</th><th>이름</th><th>근무일</th><th>근태</th></tr></thead>
          <tbody>{m.rows.map(row => <tr key={row.employeeId + row.workDate}>
            <td>{row.employeeNo}</td><td>{row.employeeName}</td>
            <td><a href={'/company/attendance?' + new URLSearchParams({ employeeId: row.employeeId, month: m.month })}>{row.workDate}</a></td>
            <td>{row.label}</td>
          </tr>)}</tbody>
        </table></div>}
    </section>}
  </>;
}
