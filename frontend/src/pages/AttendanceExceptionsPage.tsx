import { ErrorNotice, Field, Form, SelectField } from '../ui';
import { PageHeader } from '../components/PageHeader';
import { WorklogSheet, sheetColumn } from '../components/WorklogSheet';
import { CompanyAttendanceViews } from './CompanyAttendanceNavigation';
import { AttendanceTag } from './AttendancePeriodPage';

const kinds = { ALL: '전체', LATE: '지각', EARLY_LEAVE: '조퇴', ABSENT: '결근' } as const;

interface ExceptionRow { employeeId: string; employeeNo: string; employeeName: string; workDate: string; label: string }

export interface AttendanceExceptions {
  month: string;
  kind: keyof typeof kinds;
  counts: { late: number; earlyLeave: number; absent: number };
  rows: ExceptionRow[];
  truncated: boolean;
  error: string | null;
}

export function AttendanceExceptionsPage({ model: m }: { model: AttendanceExceptions }) {
  const name = m.kind === 'ALL' ? '지각·조퇴·결근' : kinds[m.kind];
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
      <p className="t-subtle">지각 {m.counts.late} · 조퇴 {m.counts.earlyLeave} · 결근 {m.counts.absent}{m.truncated && ' · 1000건까지 표시'}</p>
      <WorklogSheet
        label="지각·조퇴·결근 목록"
        title={`${m.month} ${name}`}
        rows={m.rows}
        getRowId={(row) => row.employeeId + row.workDate}
        scope="all"
        emptyMessage={`조회된 ${name} 건이 없습니다.`}
        columns={[
          { colId: 'employeeNo', headerName: '사번', valueGetter: ({ data }) => data?.employeeNo, minWidth: 110, pinned: 'left' },
          { colId: 'employeeName', headerName: '이름', valueGetter: ({ data }) => data?.employeeName, minWidth: 120 },
          sheetColumn<ExceptionRow>({ colId: 'workDate', headerName: '근무일', valueGetter: ({ data }) => data?.workDate, minWidth: 130 },
            (row) => <a href={'/company/attendance?' + new URLSearchParams({ employeeId: row.employeeId, month: m.month })}>{row.workDate}</a>),
          sheetColumn<ExceptionRow>({ colId: 'label', headerName: '근태', valueGetter: ({ data }) => data?.label, minWidth: 160 },
            (row) => <AttendanceTag label={row.label} />),
        ]}
      />
    </section>}
  </>;
}
