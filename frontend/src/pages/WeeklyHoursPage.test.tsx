// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { IdentityPages } from './IdentityPages';
import { CompanyAttendanceViews } from './CompanyAttendanceNavigation';

const csrf = { token: 'test', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const hour = 3_600_000;
const model = {
  month: '2026-10', truncated: false, error: null,
  rows: [
    { employeeId: 'employee-a', employeeNo: 'E01', employeeName: '합성 김', weekStart: '2026-10-05', weekEnd: '2026-10-11',
      actualMillis: 53 * hour, approvedOvertimeMillis: 9 * hour, unknownDays: 0, status: 'OVER' },
    { employeeId: 'employee-b', employeeNo: 'E02', employeeName: '합성 이', weekStart: '2026-09-28', weekEnd: '2026-10-04',
      actualMillis: 49.5 * hour, approvedOvertimeMillis: 0, unknownDays: 1, status: 'NEAR' },
  ],
};
function show(data: Record<string, unknown> = { weeklyHours: model, canViewCheckout: true }) {
  return render(<IdentityPages page="company/attendance" data={data} csrf={csrf} reload={vi.fn()} />);
}
afterEach(cleanup);

it('주별 실근로·인정 연장·상태 태그를 보이고 주간은 그 사원의 월별 근무현황으로 간다', async () => {
  show();
  expect((screen.getByLabelText('조회 월') as HTMLInputElement).value).toBe('2026-10');
  expect(screen.getByText(/실근로 합계가 48시간 이상인 주를 보여 줍니다/)).toBeTruthy();
  const grid = await screen.findByRole('grid', { name: '주 52시간 점검 목록' });
  await waitFor(() => expect(within(grid).getByText('52시간 초과')).toBeTruthy());
  expect(within(grid).getAllByRole('columnheader').map(h => h.textContent?.trim())).toEqual(
    expect.arrayContaining(['사번', '이름', '주간', '실근로 합계', '인정 연장', '상태', '확인 전']));
  expect(within(grid).getByText('52시간 초과').className).toContain('red');
  expect(within(grid).getByText('임박').className).toContain('amber');
  expect(within(grid).getByText('53.0시간')).toBeTruthy();
  expect(within(grid).getByText('9.0시간')).toBeTruthy();
  expect(within(grid).getByText('49.5시간')).toBeTruthy();
  expect(within(grid).getByText('1일')).toBeTruthy();
  expect(within(grid).getByRole('link', { name: '10-05~10-11' }).getAttribute('href'))
    .toBe('/company/attendance?employeeId=employee-a&month=2026-10');
  expect(screen.getByRole('link', { name: '주 52시간' }).getAttribute('aria-current')).toBe('page');
});

it('빈 목록·잘린 목록·조회 오류를 알린다', async () => {
  show({ weeklyHours: { ...model, rows: [] }, canViewCheckout: true });
  await waitFor(() => expect(screen.getByText('48시간 이상 근무한 주가 없습니다.')).toBeTruthy());
  cleanup();
  show({ weeklyHours: { ...model, truncated: true }, canViewCheckout: true });
  expect(screen.getByText(/1000건까지 표시/)).toBeTruthy();
  cleanup();
  show({ weeklyHours: { ...model, rows: [], error: '조회 월을 확인해 주세요.' }, canViewCheckout: true });
  expect(screen.getByText('조회 월을 확인해 주세요.')).toBeTruthy();
  expect(screen.queryByRole('grid')).toBeNull();
});

it('회사 근무현황 탭에 주 52시간이 있다', () => {
  render(<CompanyAttendanceViews />);
  expect(screen.getByRole('link', { name: '주 52시간' }).getAttribute('href')).toBe('/company/attendance?view=weekly-hours');
});
