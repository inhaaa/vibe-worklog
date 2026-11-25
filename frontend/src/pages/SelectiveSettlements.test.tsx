// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from '../App';
import { WorkflowPages } from './WorkflowPages';

// 일별·주간 표는 별도 테스트에서 검증한다. 여기서는 앱 탐색과 정산 카드의 실제 요청을 실행한다.
vi.mock('../components/WorklogSheet', async importOriginal => ({
  ...await importOriginal<typeof import('../components/WorklogSheet')>(),
  WorklogSheet: () => null,
}));

const csrf = { token: 'test', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const requestId = 'a04c0654-fd50-4268-9caf-8a6f86efa713';
function day(workDate: string, reasons: string[], employeeAction: string, adminAction: string, requestId: string | null = null) {
  return { workDate, reasons, employeeAction, adminAction, requestId };
}
const confirmation = day('2026-12-29', ['WORK_UNCONFIRMED', 'BREAK_UNCONFIRMED'], 'REVIEW', 'REVIEW');
const correction = day('2026-12-30', ['MISSING_CLOCK_OUT'], 'CORRECT', 'REVIEW');
const period = { settlementPeriod: 'ISO_WEEK', fromDate: '2026-12-28', toDate: '2027-01-03', appliedDays: 3,
  targetMinutes: 1350, confirmedWorkMillis: 39_600_000, paidLeaveMillis: 13_500_000,
  remainingMillis: 27_900_000, differenceMillis: null, unconfirmedDays: 1,
  unconfirmedDates: [confirmation], upcomingDays: 0,
  warnings: ['SELECTIVE_CORE_REVIEW', 'SELECTIVE_WINDOW_REVIEW', 'LEAVE_WORK_CONFLICT'] };
const report = { month: '2027-01', employeeId: 'e1', employeeNo: 'E01', employeeName: '가상 사원', periods: [period] };
function monthModel(admin = false, employeeId = report.employeeId, month = report.month) {
  return { admin, employeeId, employees: [{ id: employeeId, employeeNo: 'E01', displayName: '가상 사원' }],
    overview: { month, days: [], weeks: [], summary: { result: { confirmedWorkMillis: 0 }, pendingDays: 0 } } };
}
function monthPage(admin = false, employeeId = report.employeeId, month = report.month) {
  return <WorkflowPages page="worktime/month" csrf={csrf} reload={() => {}} data={monthModel(admin, employeeId, month)} />;
}
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  history.replaceState(null, '', '/');
});

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
      remainingMillis: 900_000, differenceMillis: -900_000, unconfirmedDays: 0, unconfirmedDates: [], warnings: [] }] }));
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

it('미확정 일수가 남은 이유를 직원이 구분할 수 있다', async () => {
  const warnings = ['BREAK_UNCONFIRMED', 'PENDING_CORRECTION', 'CALENDAR_UNCONFIRMED', 'NOT_CALCULATED', 'STALE_RESULT'];
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ ...report, periods: [{ ...period, warnings }] })));
  render(monthPage());
  const heading = await screen.findByText('2026-12-28 ~ 2027-01-03');
  const list = within(heading.closest('article')!);
  for (const label of [/실제 휴게/, /정정 신청/, /회사 달력/, /아직 계산/, /재집계/]) {
    expect(list.getByText(label)).toBeTruthy();
  }
});

it('이전 마감의 재직일 근거를 확인할 수 없으면 담당자에게 대상 월과 재마감 안내를 표시한다', async () => {
  const message = '2026-12 마감 당시 재직일을 확인할 수 없습니다. 근태 담당자가 해당 월을 재개해 다시 마감해 주세요.';
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ message }, { status: 503 })));
  render(monthPage(true));
  expect((await screen.findByRole('alert')).textContent).toContain(message);
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

const weeklySource = '/me/worktime?month=2027-01&settlementPeriod=ISO_WEEK&settlementFrom=2026-12-28';
const employeeId = '37dbe5e6-0a40-475e-9764-a4c5eb9702cd';
const adminSource = '/admin/attendance?employeeId=' + employeeId
  + '&month=2027-01&settlementPeriod=ISO_WEEK&settlementFrom=2026-12-28';

it('월경계 주의 오늘까지 미확정 일수를 열고 근무 기록에서 뒤로 가면 같은 기간을 다시 조회한다', async () => {
  history.replaceState(null, '', '/me/worktime?month=2027-01');
  const overview = { page: 'worktime/month', data: monthModel(), csrf };
  let remainingDates = [confirmation, correction];
  const fetcher = vi.fn<typeof fetch>(async input => {
    const url = new URL(String(input), location.href);
    if (url.pathname === '/worktime/selective-settlements') return Response.json({ ...report, periods: [{
      ...period, unconfirmedDays: remainingDates.length + 1, unconfirmedDates: remainingDates, upcomingDays: 1,
    }] });
    if (url.pathname === '/me/worktime') return Response.json(overview);
    if (url.pathname === '/me/attendance') return Response.json({ page: 'org/problem', csrf, data: { message: '가상 날짜 확인 화면' } });
    throw new Error(url.pathname);
  });
  vi.stubGlobal('fetch', fetcher);
  render(<App initial={overview} />);
  const count = await screen.findByRole('link', { name: '미확정 2일' });
  expect(count.getAttribute('href')).toBe(weeklySource);
  expect(count.getAttribute('aria-expanded')).toBe('false');
  expect(screen.getByText('예정 근무일').nextElementSibling?.textContent).toBe('1일');
  expect(screen.queryByRole('heading', { name: '미확정 날짜' })).toBeNull();
  await act(async () => { fireEvent.click(count); });
  expect(location.pathname + location.search).toBe(weeklySource);
  expect(fetcher.mock.calls.map(([input]) => new URL(String(input), location.href).pathname)).toEqual([
    '/worktime/selective-settlements', '/me/worktime', '/worktime/selective-settlements',
  ]);
  const list = within(await screen.findByRole('region', { name: '미확정 날짜' }));
  expect(list.getAllByRole('listitem').map(row => row.querySelector('time')?.dateTime)).toEqual(['2026-12-29', '2026-12-30']);
  const row = within(list.getAllByRole('listitem')[0]);
  expect(row.getByText(/실제 근로시간/)).toBeTruthy();
  expect(row.getByText(/실제 휴게시간/)).toBeTruthy();
  const confirm = row.getByRole('link', { name: confirmation.workDate + ' 근무 기록 보기' });
  expect(confirm.getAttribute('href')).toBe('/me/attendance?' + new URLSearchParams({ date: confirmation.workDate, settlementReturn: weeklySource }));
  expect(row.getByText(confirmation.workDate).closest('a')).toBe(confirm);
  await act(async () => { fireEvent.click(row.getByText(confirmation.workDate)); });
  await screen.findByText('가상 날짜 확인 화면');
  expect(location.pathname).toBe('/me/attendance');
  expect(new URLSearchParams(location.search).get('date')).toBe(confirmation.workDate);
  remainingDates = [correction];
  await act(async () => {
    const returned = new Promise<void>(resolve => window.addEventListener('popstate', () => resolve(), { once: true }));
    history.back();
    await returned;
  });
  expect(location.pathname + location.search).toBe(weeklySource);
  const restored = within(await screen.findByRole('region', { name: '미확정 날짜' }));
  expect(screen.getByRole('link', { name: '미확정 1일' }).getAttribute('aria-expanded')).toBe('true');
  expect(restored.queryByText(confirmation.workDate)).toBeNull();
  expect(restored.getByRole('link', { name: correction.workDate + ' 정정 신청' }).getAttribute('href')).toBe('/me/requests/correction?'
    + new URLSearchParams({ date: correction.workDate, mode: 'full', settlementReturn: weeklySource }));
  remainingDates = [];
  await act(async () => { fireEvent.click(restored.getByRole('button', { name: '정산 다시 조회' })); });
  expect(await screen.findByText('처리할 미확정 날짜가 없습니다.')).toBeTruthy();
  expect(screen.getByRole('link', { name: '미확정 0일' }).getAttribute('aria-expanded')).toBe('true');
  expect(screen.getByText('예정 근무일').nextElementSibling?.textContent).toBe('1일');
  expect(location.pathname + location.search).toBe(weeklySource);
});

it.each([false, true])('같은 날짜의 사유와 조회 권한에 맞는 처리 링크를 표시한다 (담당자 %s)', async admin => {
  const source = admin ? adminSource : weeklySource;
  history.replaceState(null, '', source);
  const dates = [
    day('2026-12-28', ['NOT_CALCULATED'], 'NONE', 'RECALCULATE'),
    day('2026-12-29', ['CALENDAR_UNCONFIRMED'], 'NONE', 'CALENDAR'),
    day('2026-12-30', ['PENDING_CORRECTION'], 'REQUEST', 'REQUEST', requestId),
    day('2026-12-31', ['INVALID_CLOCK_ORDER'], 'REVIEW', 'REVIEW'),
    day('2027-01-01', ['MISSING_CLOCK_OUT', 'DAY_IN_PROGRESS'], 'NONE', 'REVIEW'),
    day('2027-01-02', ['MONTH_CLOSED', 'MISSING_CLOCK_OUT'], 'NONE', 'NONE'),
    day('2027-01-03', ['WORK_UNCONFIRMED'], 'CONFIRM', 'REVIEW'),
  ];
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async input => {
    const url = new URL(String(input), location.href);
    if (url.pathname.endsWith('/selective-settlements')) return Response.json({ ...report, employeeId,
      periods: [{ ...period, unconfirmedDays: dates.length, unconfirmedDates: dates, appliedDays: dates.length }] });
    if (url.pathname === '/admin/attendance') return Response.json({ page: 'org/problem', csrf, data: { message: '가상 대상일 재집계 화면' } });
    throw new Error(url.pathname);
  }));
  render(<App initial={{ page: 'worktime/month', data: monthModel(admin, employeeId), csrf }} />);
  const list = within(await screen.findByRole('region', { name: '미확정 날짜' }));
  const rows = list.getAllByRole('listitem').map(element => within(element));
  const href = (i: number, name: string) => {
    const link = rows[i].getByRole('link', { name: dates[i].workDate + ' ' + name });
    expect(rows[i].getByText(dates[i].workDate).closest('a')).toBe(link);
    return new URL(link.getAttribute('href')!, location.href);
  };
  expect(href(2, '신청 진행 확인').pathname).toBe('/requests/' + requestId);
  for (const link of list.getAllByRole('link')) {
    expect(new URL(link.getAttribute('href')!, location.href).searchParams.get('settlementReturn')).toBe(source);
  }
  if (admin) {
    const calculate = href(0, '대상일 재집계');
    expect(calculate.pathname + calculate.search + calculate.hash).toBe('/admin/attendance?' + new URLSearchParams({
      employeeId, month: '2026-12', workDate: '2026-12-28', settlementReturn: source,
    }) + '#selective-day-calculation');
    expect(href(1, '휴일 관리 확인').pathname).toBe('/admin/holidays');
    expect(href(1, '휴일 관리 확인').searchParams.get('year')).toBe('2026');
    expect(href(3, '근무일 검토').hash).toBe('#selective-day-review');
    expect(href(3, '근무일 검토').searchParams.get('employeeId')).toBe(employeeId);
    expect(list.queryByRole('link', { name: /근무 확인$/ })).toBeNull();
  } else {
    expect(rows[0].getByText('담당자 확인 대기')).toBeTruthy();
    expect(rows[1].queryByRole('link')).toBeNull();
    expect(href(3, '근무 기록 보기').pathname).toBe('/me/attendance');
    expect(href(3, '근무 기록 보기').searchParams.get('date')).toBe('2026-12-31');
    expect(rows[4].getByText(/퇴근 후/)).toBeTruthy();
    expect(rows[4].queryByRole('link')).toBeNull();
    expect(rows[6].getByText('자동 확인 대기(새벽 집계)')).toBeTruthy();
    expect(rows[6].queryByRole('link')).toBeNull();
  }
  expect(rows[5].getByText('조회 전용')).toBeTruthy();
  expect(rows[5].queryByRole('link')).toBeNull();
  if (admin) {
    await act(async () => { fireEvent.click(rows[0].getByText(dates[0].workDate)); });
    await screen.findByText('가상 대상일 재집계 화면');
    expect(location.pathname).toBe('/admin/attendance');
    expect([...new URLSearchParams(location.search)]).toEqual([
      ['employeeId', employeeId], ['month', '2026-12'], ['workDate', dates[0].workDate], ['settlementReturn', adminSource],
    ]);
    expect(location.hash).toBe('#selective-day-calculation');
  }
});

it('월 정산과 주 정산의 시작일이 같아도 주소의 기간과 조회한 사원이 맞는 카드만 펼친다', async () => {
  const month = '2027-02', fromDate = '2027-02-01';
  const periods = [
    { ...period, fromDate, toDate: '2027-02-07', unconfirmedDates: [day('2027-02-01', ['BREAK_UNCONFIRMED'], 'CONFIRM', 'REVIEW')] },
    { ...period, settlementPeriod: 'CALENDAR_MONTH', fromDate, toDate: '2027-02-28',
      unconfirmedDates: [day('2027-02-10', ['PENDING_CORRECTION'], 'REQUEST', 'REQUEST', requestId)] },
  ];
  const source = '/admin/attendance?employeeId=' + employeeId + '&month=2027-02&settlementPeriod=CALENDAR_MONTH&settlementFrom=2027-02-01';
  history.replaceState(null, '', source);
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(Response.json({ ...report, month, employeeId, periods }))));
  const view = render(monthPage(true, employeeId, month));
  const selected = within(await screen.findByRole('region', { name: '미확정 날짜' }));
  expect(selected.getByText('2027-02-10')).toBeTruthy();
  expect(selected.queryByText('2027-02-01')).toBeNull();
  expect(screen.getAllByRole('link', { name: '미확정 1일' }).map(link => link.getAttribute('aria-expanded'))).toEqual(['false', 'true']);
  view.unmount();
  history.replaceState(null, '', source.replace(employeeId, '다른-사원'));
  render(monthPage(true, employeeId, month));
  await screen.findAllByRole('link', { name: '미확정 1일' });
  expect(screen.queryByRole('region', { name: '미확정 날짜' })).toBeNull();
});

it('공유 주소의 쿼리 순서가 달라도 선택 기간을 복원하고 처리 링크에는 표준 복귀 주소를 사용한다', async () => {
  history.replaceState(null, '', '/admin/attendance?settlementFrom=2026-12-28&month=2027-01&settlementPeriod=ISO_WEEK&employeeId=' + employeeId);
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(Response.json({ ...report, employeeId }))));
  const view = render(monthPage(true, employeeId));
  const selected = within(await screen.findByRole('region', { name: '미확정 날짜' }));
  expect(document.activeElement).toBe(selected.getByRole('heading', { name: '미확정 날짜' }));
  const detail = selected.getByRole('link', { name: confirmation.workDate + ' 근무일 검토' });
  expect(new URL(detail.getAttribute('href')!, location.href).searchParams.get('settlementReturn')).toBe(adminSource);
  view.unmount();
  history.replaceState(null, '', adminSource + '&employeeId=' + employeeId);
  render(monthPage(true, employeeId));
  await screen.findByRole('link', { name: '미확정 1일' });
  expect(screen.queryByRole('region', { name: '미확정 날짜' })).toBeNull();
});
