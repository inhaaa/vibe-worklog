// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { IdentityPages } from './IdentityPages';
import { CompanyAttendanceViews } from './CompanyAttendanceNavigation';

const csrf = { token: 'test', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const model = {
  month: '2026-10', kind: 'ALL', counts: { late: 1, earlyLeave: 0, absent: 1 }, error: null,
  rows: [
    { employeeId: 'employee-b', employeeNo: 'E02', employeeName: '합성 이', workDate: '2026-10-06', label: '결근' },
    { employeeId: 'employee-a', employeeNo: 'E01', employeeName: '합성 김', workDate: '2026-10-05', label: '지각 7분' },
  ],
};
function show(data: Record<string, unknown> = { attendanceExceptions: model, canViewCheckout: true }) {
  return render(<IdentityPages page="company/attendance" data={data} csrf={csrf} reload={vi.fn()} />);
}
afterEach(cleanup);

it('요약·구분 선택·표를 보이고 날짜는 그 사원의 월별 근무현황으로 간다', () => {
  show();
  expect(screen.getByText('지각 1 · 조퇴 0 · 결근 1')).toBeTruthy();
  expect((screen.getByLabelText('조회 월') as HTMLInputElement).value).toBe('2026-10');
  const kind = screen.getByLabelText('구분') as HTMLSelectElement;
  expect([...kind.options].map(o => [o.value, o.text])).toEqual(
    [['ALL', '전체'], ['LATE', '지각'], ['EARLY_LEAVE', '조퇴'], ['ABSENT', '결근']]);
  expect(kind.value).toBe('ALL');
  const rows = screen.getAllByRole('row');
  expect(within(rows[0]).getAllByRole('columnheader').map(h => h.textContent)).toEqual(['사번', '이름', '근무일', '근태']);
  expect(within(rows[1]).getByText('E02')).toBeTruthy();
  expect(within(rows[1]).getByText('결근')).toBeTruthy();
  expect(within(rows[2]).getByText('지각 7분')).toBeTruthy();
  expect(screen.getByRole('link', { name: '2026-10-05' }).getAttribute('href'))
    .toBe('/company/attendance?employeeId=employee-a&month=2026-10');
  expect(screen.getByRole('link', { name: '지각·조퇴·결근' }).getAttribute('aria-current')).toBe('page');
});

it('조회 오류와 빈 결과를 알린다', () => {
  show({ attendanceExceptions: { ...model, rows: [], error: '조회 월을 확인해 주세요.' }, canViewCheckout: true });
  expect(screen.getByText('조회 월을 확인해 주세요.')).toBeTruthy();
  expect(screen.queryByRole('table')).toBeNull();
  cleanup();
  show({ attendanceExceptions: { ...model, kind: 'LATE', rows: [] }, canViewCheckout: true });
  expect(screen.getByRole('status').textContent).toContain('없습니다');
});

it('회사 근무현황 탭에 지각·조퇴·결근이 있다', () => {
  render(<CompanyAttendanceViews />);
  expect(screen.getByRole('link', { name: '지각·조퇴·결근' }).getAttribute('href')).toBe('/company/attendance?view=exceptions');
  expect(screen.getByRole('link', { name: '지각·조퇴·결근' }).getAttribute('aria-current')).toBeNull();
});
