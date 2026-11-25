// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { IdentityPages } from './IdentityPages';
import { CompanyAttendanceViews } from './CompanyAttendanceNavigation';

const csrf = { token: 'test', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const model = {
  month: '2026-10', kind: 'ALL', counts: { late: 2, earlyLeave: 1, absent: 1 }, truncated: false, error: null,
  rows: [
    { employeeId: 'employee-c', employeeNo: 'E03', employeeName: '합성 박', workDate: '2026-10-07', label: '지각 3분 · 조퇴 10분' },
    { employeeId: 'employee-b', employeeNo: 'E02', employeeName: '합성 이', workDate: '2026-10-06', label: '결근' },
    { employeeId: 'employee-a', employeeNo: 'E01', employeeName: '합성 김', workDate: '2026-10-05', label: '지각 7분' },
  ],
};
function show(data: Record<string, unknown> = { attendanceExceptions: model, canViewCheckout: true }) {
  return render(<IdentityPages page="company/attendance" data={data} csrf={csrf} reload={vi.fn()} />);
}
afterEach(cleanup);

it('요약·구분 선택·시트를 보이고 날짜는 그 사원의 월별 근무현황으로 간다', async () => {
  show();
  expect(screen.getByText('지각 2 · 조퇴 1 · 결근 1')).toBeTruthy();
  expect(screen.queryByText(/1000건까지 표시/)).toBeNull();
  expect((screen.getByLabelText('조회 월') as HTMLInputElement).value).toBe('2026-10');
  const kind = screen.getByLabelText('구분') as HTMLSelectElement;
  expect([...kind.options].map(o => [o.value, o.text])).toEqual(
    [['ALL', '전체'], ['LATE', '지각'], ['EARLY_LEAVE', '조퇴'], ['ABSENT', '결근']]);
  expect(kind.value).toBe('ALL');
  const grid = await screen.findByRole('grid', { name: '지각·조퇴·결근 목록' });
  await waitFor(() => expect(grid.querySelector('[row-id="employee-a2026-10-05"]')).not.toBeNull());
  expect(within(grid).getAllByRole('columnheader').map(h => h.textContent?.trim())).toEqual(
    expect.arrayContaining(['사번', '이름', '근무일', '근태']));
  const tag = within(grid).getByText('결근');
  expect(tag.className).toContain('t-tag');
  expect(within(grid).getByText('지각 3분 · 조퇴 10분').className).toContain('amber');
  expect(within(grid).getByRole('link', { name: '2026-10-05' }).getAttribute('href'))
    .toBe('/company/attendance?employeeId=employee-a&month=2026-10');
  expect(screen.getByRole('link', { name: '지각·조퇴·결근' }).getAttribute('aria-current')).toBe('page');
});

it('잘린 목록과 조회 오류·빈 결과를 알린다', async () => {
  show({ attendanceExceptions: { ...model, truncated: true }, canViewCheckout: true });
  expect(screen.getByText('지각 2 · 조퇴 1 · 결근 1 · 1000건까지 표시')).toBeTruthy();
  cleanup();
  show({ attendanceExceptions: { ...model, rows: [], error: '조회 월을 확인해 주세요.' }, canViewCheckout: true });
  expect(screen.getByText('조회 월을 확인해 주세요.')).toBeTruthy();
  expect(screen.queryByRole('grid')).toBeNull();
  cleanup();
  show({ attendanceExceptions: { ...model, kind: 'LATE', rows: [] }, canViewCheckout: true });
  await waitFor(() => expect(screen.getByText('조회된 지각 건이 없습니다.')).toBeTruthy());
});

it('회사 근무현황 탭에 지각·조퇴·결근이 있다', () => {
  render(<CompanyAttendanceViews />);
  expect(screen.getByRole('link', { name: '지각·조퇴·결근' }).getAttribute('href')).toBe('/company/attendance?view=exceptions');
  expect(screen.getByRole('link', { name: '지각·조퇴·결근' }).getAttribute('aria-current')).toBeNull();
});

it('월별 근무현황의 일별 시트에 근태 열이 있다', async () => {
  const employee = { id: 'e-1', employeeNo: 'E001', employeeName: '김직원', organizationName: '개발팀' };
  const day = { scheduledMillis: 28800000, actualWorkMillis: 28800000, leaveDays: 0, status: 'CALCULATED', current: true };
  show({ companyAttendance: {
    month: '2026-10', asOf: '2026-10-26T15:00:00Z', organizationId: null, employeeId: 'e-1', q: '', page: 0, hasNext: false,
    organizations: [], employees: [employee], selectedEmployee: employee,
    days: [{ ...day, workDate: '2026-10-26', attendanceLabel: '지각 7분' }, { ...day, workDate: '2026-10-27', attendanceLabel: null }],
  } });
  const grid = await screen.findByRole('grid', { name: '사원 일별 근무현황' });
  await waitFor(() => expect(within(grid).getByText('지각 7분').className).toContain('t-tag'));
  expect(within(grid).getAllByRole('columnheader').map(h => h.textContent?.trim())).toContain('근태');
});
