// @vitest-environment jsdom
import { act, cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { WorkflowPages } from './WorkflowPages';

const csrf = { token: 'test', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const period = { settlementPeriod: 'ISO_WEEK', fromDate: '2026-12-28', toDate: '2027-01-03', appliedDays: 3,
  targetMinutes: 1350, confirmedWorkMillis: 39_600_000, paidLeaveMillis: 13_500_000,
  remainingMillis: 27_900_000, differenceMillis: null, unconfirmedDays: 1,
  warnings: ['SELECTIVE_CORE_REVIEW', 'SELECTIVE_WINDOW_REVIEW', 'LEAVE_WORK_CONFLICT'] };
const report = { month: '2027-01', employeeId: 'e1', employeeNo: 'E01', employeeName: '가상 사원', periods: [period] };
function monthPage(admin = false, employeeId = report.employeeId) {
  return <WorkflowPages page="worktime/month" csrf={csrf} reload={() => {}} data={{ admin, employeeId,
    employees: [{ id: employeeId, employeeNo: 'E01', displayName: '가상 사원' }],
    overview: { month: report.month, days: [], weeks: [], summary: { result: { confirmedWorkMillis: 0 }, pendingDays: 0 } } }} />;
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it('본인 월 조회는 연도 경계를 넘는 정산 주 전체와 확인된 부분합·미확정·확인 사유를 표시한다', async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json(report));
  vi.stubGlobal('fetch', fetcher);
  render(monthPage());
  const scope = within(await screen.findByRole('region', { name: '선택근무 정산' }));
  await scope.findByText('2026-12-28 ~ 2027-01-03');
  expect(fetcher.mock.calls[0][0]).toBe('/worktime/selective-settlements?month=2027-01');
  const value = (label: string) => scope.getByText(label).nextElementSibling?.textContent;
  expect(value('기간 목표')).toBe('22:30:00');
  expect(value('확인 실근로')).toBe('11:00:00');
  expect(value('승인 유급휴가')).toBe('3:45:00');
  expect(value('정산 차이')).toBe('미확정');
  expect(value('미확정 일수')).toBe('1일');
  expect(scope.getByText(/코어시간.*확인/)).toBeTruthy();
  expect(scope.getByText(/선택 가능 시간.*확인/)).toBeTruthy();
  expect(scope.getByText(/종일 휴가와 출퇴근 기록이 겹칩니다/)).toBeTruthy();
});

it('담당자 조회는 선택 사원만 요청하고 사원이 바뀐 뒤 도착한 이전 응답을 표시하지 않는다', async () => {
  let respond!: (value: Response) => void;
  const fetcher = vi.fn().mockReturnValueOnce(new Promise<Response>(resolve => { respond = resolve; }))
    .mockResolvedValueOnce(Response.json({ ...report, employeeId: 'e2', employeeName: '다른 사원', periods: [{ ...period,
      settlementPeriod: 'CALENDAR_MONTH', fromDate: '2027-01-01', toDate: '2027-01-31', targetMinutes: 900,
      remainingMillis: 900_000, differenceMillis: -900_000, unconfirmedDays: 0, warnings: [] }] }));
  vi.stubGlobal('fetch', fetcher);
  const view = render(monthPage(true));
  view.rerender(monthPage(true, 'e2'));
  const scope = within(await screen.findByRole('region', { name: '선택근무 정산' }));
  await scope.findByText('2027-01-01 ~ 2027-01-31');
  expect(scope.getByText('정산 차이').nextElementSibling?.textContent).toBe('부족 0:15:00');
  expect(fetcher.mock.calls.map(([url]) => url)).toEqual(['e1', 'e2'].map(id =>
    '/admin/attendance/month-close/selective-settlements?month=2027-01&employeeId=' + id));
  await act(async () => respond(Response.json(report)));
  expect(scope.queryByText('2026-12-28 ~ 2027-01-03')).toBeNull();
});

it('진행 중에는 현재 확인분 기준 남은 시간을 표시하고 기준이 미확정인 주에는 0을 표시하지 않는다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ ...report, periods: [period, {
    ...period, fromDate: '2027-01-04', toDate: '2027-01-10', targetMinutes: null, paidLeaveMillis: null, remainingMillis: null,
  }] })));
  render(monthPage());
  const first = (await screen.findByText('2026-12-28 ~ 2027-01-03')).closest('article')!;
  const second = screen.getByText('2027-01-04 ~ 2027-01-10').closest('article')!;
  const remaining = (scope: HTMLElement) => within(scope).getByText('현재 확인분 기준 남은 시간').nextElementSibling?.textContent;
  expect(remaining(first)).toBe('7:45:00');
  expect(within(first).getByText('정산 차이').nextElementSibling?.textContent).toBe('미확정');
  expect(remaining(second)).toBe('미확정');
});
