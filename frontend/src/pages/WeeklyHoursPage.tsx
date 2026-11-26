import { ErrorNotice, Field, Form } from '../ui';
import { PageHeader } from '../components/PageHeader';
import { WorklogSheet, sheetColumn } from '../components/WorklogSheet';
import { CompanyAttendanceViews } from './CompanyAttendanceNavigation';

interface WeeklyRow {
  employeeId: string; employeeNo: string; employeeName: string; weekStart: string; weekEnd: string;
  actualMillis: number; approvedOvertimeMillis: number; unknownDays: number; status: 'OVER' | 'NEAR';
}

export interface WeeklyHours { month: string; rows: WeeklyRow[]; truncated: boolean; error: string | null }

const hours = (millis: number) => `${(millis / 3_600_000).toFixed(1)}시간`;

export function WeeklyHoursPage({ model: m }: { model: WeeklyHours }) {
  return <>
    <PageHeader title="회사 근무현황" />
    <CompanyAttendanceViews active="weekly-hours" />
    <Form action="/company/attendance" method="get" className="wl-querybar">
      <input type="hidden" name="view" value="weekly-hours" />
      <Field label="조회 월" name="month" type="month" value={m.month} required />
      <div className="wl-querybar-actions"><button type="submit" className="t-btn wl-query-submit">조회</button></div>
    </Form>
    <ErrorNotice message={m.error} />
    {!m.error && <section className="wl-section">
      <p className="t-subtle">실근로 합계가 48시간 이상인 주를 보여 줍니다. 확인 전 일수가 있으면 확인된 날만의 합계입니다.{m.truncated && ' · 1000건까지 표시'}</p>
      <WorklogSheet
        label="주 52시간 점검 목록"
        title={`${m.month} 주 52시간`}
        rows={m.rows}
        getRowId={(row) => row.employeeId + row.weekStart}
        scope="all"
        emptyMessage="48시간 이상 근무한 주가 없습니다."
        columns={[
          { colId: 'employeeNo', headerName: '사번', valueGetter: ({ data }) => data?.employeeNo, minWidth: 110, pinned: 'left' },
          { colId: 'employeeName', headerName: '이름', valueGetter: ({ data }) => data?.employeeName, minWidth: 120 },
          sheetColumn<WeeklyRow>({ colId: 'week', headerName: '주간', valueGetter: ({ data }) => data?.weekStart, minWidth: 150 },
            (row) => <a href={'/company/attendance?' + new URLSearchParams({ employeeId: row.employeeId, month: m.month })}>
              {row.weekStart.slice(5)}~{row.weekEnd.slice(5)}</a>),
          { colId: 'actual', headerName: '실근로 합계', valueGetter: ({ data }) => data && hours(data.actualMillis), minWidth: 130 },
          { colId: 'overtime', headerName: '인정 연장', valueGetter: ({ data }) => data && hours(data.approvedOvertimeMillis), minWidth: 120 },
          sheetColumn<WeeklyRow>({ colId: 'status', headerName: '상태', valueGetter: ({ data }) => data?.status, minWidth: 120 },
            (row) => row.status === 'OVER' ? <span className="t-tag red">52시간 초과</span> : <span className="t-tag amber">임박</span>),
          { colId: 'unknown', headerName: '확인 전', valueGetter: ({ data }) => data?.unknownDays ? `${data.unknownDays}일` : '-', minWidth: 100 },
        ]}
      />
    </section>}
  </>;
}
