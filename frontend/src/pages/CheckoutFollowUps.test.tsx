// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from '../App';
import { getNavigationContext } from '../navigation';
import { HomeCheckoutFollowUps, MyCheckoutFollowUps } from './CheckoutFollowUps';
import { IdentityPages } from './IdentityPages';

const csrf = { token: 'csrf-test', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const selected = { employeeId: 'employee-a', employeeNo: '101', employeeName: '한사원', workDate: '2026-11-06',
  organizationName: '업무팀', reasons: ['UNCONFIRMED'], resultId: null, pendingRequestId: null,
  checkoutFollowUpEligible: true, checkoutFollowUp: null };
const summary = { id: 'task-1', workDate: selected.workDate, reason: '누락된 퇴근 시각을 확인해 주세요.', status: 'REQUESTED',
  resultId: null, approvalRequestId: null };
const month = {month:'2026-11', latestMonth:'2026-11', organizationId:'org-a', options: [{id:'org-a',code:'01',name:'업무팀'}],
  q:'한사원', reason:'UNCONFIRMED', page:2, hasNext:false, hasPrevious:true, rows:[selected], error: null};
function manager(rows: object[] = [selected], roles = ['ATTENDANCE_ADMIN']) {
  const reload = vi.fn();
  const view = render(<IdentityPages page="company/attendance" csrf={csrf} reload={reload}
    data={{ monthEndReview: { ...month, rows }, canViewCheckout: true, workspacePrincipal: { roles } }} />);
  return {view, reload};
}
const owner = (rows: object[], page = 0, hasNext = false) => <IdentityPages page="attendance/follow-ups" csrf={csrf} reload={() => {}}
  data={{checkoutFollowUps: {rows, page, hasNext}, workspacePrincipal:{roles:['EMPLOYEE']}}} />;
function openComposer() {
  fireEvent.click(screen.getByRole('button', {name:'보완 요청'}));
  return within(screen.getByRole('form', {name:'퇴근 누락 보완 요청'}));
}
const accepted = () => new Response(JSON.stringify({id:'task-created'}), {status:200, headers:{'Content-Type':'application/json'}});

afterEach(() => {cleanup(); vi.unstubAllGlobals();});

it('담당자는 해당 사원과 근무일을 확인한 뒤 CSRF를 포함해 한 번 요청하고 같은 월말 목록을 다시 조회한다', async () => {
  let finish!: (response: Response) => void;
  const send = vi.fn<typeof fetch>(() => new Promise(resolve => {finish = resolve;}));
  vi.stubGlobal('fetch', send);
  const {reload} = manager([selected, {...selected,employeeId:'other', employeeNo:'102',employeeName:'다른사원',checkoutFollowUpEligible:false}]);
  const form = openComposer();
  expect(form.getByText(/한사원/)).toBeTruthy();
  expect(form.getByText(new RegExp(selected.workDate))).toBeTruthy();
  const reason = form.getByRole('textbox', {name:'보완 사유'}) as HTMLTextAreaElement;
  expect(reason.maxLength).toBe(500);
  fireEvent.change(reason, {target:{value:'   '}});
  expect((form.getByRole('button', {name:'요청 보내기'}) as HTMLButtonElement).disabled).toBe(true);
  fireEvent.change(reason, {target:{value:'  실제 퇴근 시각 확인  '}});
  const element = screen.getByRole('form', {name:'퇴근 누락 보완 요청'});
  fireEvent.submit(element); fireEvent.submit(element);
  expect(send).toHaveBeenCalledTimes(1);
  const [endpoint, options] = send.mock.calls[0];
  expect(endpoint).toBe('/admin/attendance/follow-ups');
  expect(options?.method).toBe('POST');
  expect(new Headers(options?.headers).get(csrf.headerName)).toBe(csrf.token);
  expect(JSON.parse(String(options?.body))).toEqual({employeeId:'employee-a', workDate:'2026-11-06',
    reason:'실제 퇴근 시각 확인',commandId:expect.stringMatching(/^[0-9a-f-]{36}$/i)});
  expect(reload).not.toHaveBeenCalled();
  await act(async () => finish(accepted()));
  await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
});

it('통신 중단 후 같은 내용과 명령으로 결과를 다시 확인한다', async () => {
  const send = vi.fn<typeof fetch>().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(accepted());
  vi.stubGlobal('fetch', send);
  const {reload} = manager();
  const form = openComposer();
  const input = form.getByRole('textbox', {name:'보완 사유'}) as HTMLTextAreaElement;
  fireEvent.change(input,{target:{value:'시각 확인'}});
  fireEvent.click(form.getByRole('button',{name:'요청 보내기'}));
  const retry = await form.findByRole('button',{name:'같은 요청 다시 확인'});
  expect(input.readOnly || input.disabled).toBe(true);
  fireEvent.click(retry);
  await waitFor(() => expect(reload).toHaveBeenCalledOnce());
  expect(send.mock.calls[1][1]?.body).toEqual(send.mock.calls[0][1]?.body);
});

it('상태가 바뀌면 최신 목록을 다시 확인하고, 기존 요청과 권한이 없는 행에서는 보낼 수 없다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({message:'이미 정정 신청이 있습니다.'}),{status:409})));
  const {view, reload} = manager([selected, {...selected, employeeId:'employee-b',workDate:'2026-11-05', checkoutFollowUpEligible:false,
    checkoutFollowUp:{...summary,status:'AWAITING_CALCULATION',approvalRequestId:'approved-request'}}]);
  expect(screen.getByText('재집계 대기')).toBeTruthy();
  expect(screen.getByRole('link',{name:'정정 상세'}).getAttribute('href')).toContain('/requests/approved-request?');
  const form = openComposer();
  fireEvent.change(form.getByRole('textbox'),{target:{value:'시각 확인'}});
  fireEvent.click(form.getByRole('button',{name:'요청 보내기'}));
  await screen.findByText('이미 정정 신청이 있습니다.');
  fireEvent.click(form.getByRole('button',{name:'목록 새로고침'}));
  expect(reload).toHaveBeenCalledOnce();
  view.unmount();
  manager([selected], ['EMPLOYEE']);
  expect(screen.queryByRole('button',{name:'보완 요청'})).toBeNull();
});

it('직원 목록에서 상태별로 해당 근무일과 원신청을 연결하고 열린 건을 페이지 단위로 조회한다', () => {
  const rows=[summary,
    {...summary,id:'task-2',workDate:'2026-11-05',status:'PENDING_APPROVAL', approvalRequestId:'pending-request'},
    {...summary,id:'task-3',workDate:'2026-11-04',status:'NEEDS_CONFIRMATION',approvalRequestId:'approved'},
    {...summary,id:'task-4',workDate:'2026-11-03',status:'AWAITING_CALCULATION',approvalRequestId:'approved-2'}];
  const view=render(owner(rows, 1, true));
  expect(screen.getByRole('heading',{level:1,name:'내 근태 보완 요청'})).toBeTruthy();
  const items = screen.getAllByRole('listitem');
  expect(items).toHaveLength(4);
  expect(within(items[0]).getByText(summary.reason)).toBeTruthy();
  expect(within(items[0]).getByRole('link',{name:'정정 신청'}).getAttribute('href')).toBe('/me/requests/correction?date=2026-11-06');
  expect(within(items[1]).getByText('정정 결재 대기')).toBeTruthy();
  expect(within(items[1]).getByRole('link',{name:'정정 상세'}).getAttribute('href')).toBe('/requests/pending-request');
  expect(within(items[1]).queryByRole('link',{name:'정정 신청'})).toBeNull();
  expect(within(items[2]).getByRole('link',{name:'근무 확인'}).getAttribute('href')).toBe('/me/attendance?date=2026-11-04');
  expect(within(items[3]).getByText('재집계 대기')).toBeTruthy();
  expect(within(items[3]).queryByRole('link',{name:'정정 신청'})).toBeNull();
  expect(screen.getByRole('link',{name:'이전'}).getAttribute('href')).toBe('/me/attendance/follow-ups?page=0');
  expect(screen.getByRole('link',{name:'다음'}).getAttribute('href')).toBe('/me/attendance/follow-ups?page=2');
  view.rerender(owner([]));
  expect(screen.getByText('처리할 근태 보완 요청이 없습니다.')).toBeTruthy();
  expect(getNavigationContext('attendance/follow-ups','/me/attendance/follow-ups')?.breadcrumbs.at(-1)?.label).toBe('내 근태 보완 요청');
});

it.each([
  { page: '홈', View: HomeCheckoutFollowUps },
  { page: '전체 목록', View: MyCheckoutFollowUps },
])('$page에서 근무 확인이 필요한 직원은 같은 날짜의 근무 확인과 정정 신청 중 선택할 수 있다', ({ View }) => {
  const task = { ...summary, status: 'NEEDS_CONFIRMATION' as const, workDate: '2026-11-04', approvalRequestId: 'approved' };
  render(<View model={{ rows: [task], page: 0, hasNext: false }} />);
  const row = within(screen.getByRole('listitem'));
  expect(row.getByRole('link', { name: '근무 확인' }).getAttribute('href')).toBe('/me/attendance?date=2026-11-04');
  expect(row.getByRole('link', { name: '정정 신청' }).getAttribute('href')).toBe('/me/requests/correction?date=2026-11-04');
  expect(row.getByRole('link', { name: '정정 상세' }).getAttribute('href')).toBe('/requests/approved');
});

it('반려·철회 후 이전 정정 상세로 사유를 확인하고 같은 날짜 정정을 다시 신청할 수 있다', () => {
  render(owner([{...summary,approvalRequestId:'rejected-request'}]));
  expect(screen.getByRole('link',{name:'이전 정정 상세'}).getAttribute('href')).toBe('/requests/rejected-request');
  expect(screen.getByRole('link',{name:'정정 신청'}).getAttribute('href')).toBe('/me/requests/correction?date=2026-11-06');
});

it('이미 해소한 날짜에 누락이 다시 발생하면 담당자가 새 보완 요청을 만들 수 있다', () => {
  manager([{...selected, checkoutFollowUp:{...summary,status:'RESOLVED',approvalRequestId:'older',resultId:'old-result'}}]);
  expect(openComposer().getByRole('textbox',{name:'보완 사유'})).toBeTruthy();
});

it('일반 앱에서 보낸 뒤 검색어·조직·월·사유·페이지를 담은 주소로 다시 조회해 서버에 저장된 상태를 표시한다', async () => {
  const path = '/company/attendance?' + new URLSearchParams({view:'month-end',q:month.q,organizationId:month.organizationId,
    month:month.month,reason:month.reason,page:String(month.page)});
  const before=location.pathname+location.search;
  history.replaceState(null,'',path);
  try {
    const data={monthEndReview:month, canViewCheckout:true,workspacePrincipal:{roles:['EMPLOYEE','ATTENDANCE_ADMIN']},
      workspaceEmployee:{employeeName:'합성 담당자',companyName:'합성 회사'}};
    const updated={page:'company/attendance',csrf,data:{...data,monthEndReview:{...month,rows:[{...selected,
      checkoutFollowUpEligible:false, checkoutFollowUp:{...summary,reason:'동일 조건 재조회'}}]}}};
    const fetched = new Response(JSON.stringify(updated), {headers:{'Content-Type':'application/vnd.worklog.page+json'}});
    Object.defineProperty(fetched,'url',{value:location.href});
    const send=vi.fn<typeof fetch>().mockResolvedValueOnce(accepted()).mockResolvedValueOnce(fetched);
    vi.stubGlobal('fetch',send);
    render(<App initial={{page:'company/attendance',csrf,data}} />);
    const form=openComposer();
    fireEvent.change(form.getByRole('textbox'),{target:{value:'동일 조건 재조회'}});
    fireEvent.click(form.getByRole('button',{name:'요청 보내기'}));
    await screen.findByText('직원 확인 대기');
    expect(send).toHaveBeenCalledTimes(2);
    expect(send.mock.calls[1][0]).toBe(location.origin+path);
    expect(location.pathname+location.search).toBe(path);
    expect(screen.queryByRole('button',{name:'보완 요청'})).toBeNull();
  } finally {history.replaceState(null,'',before);}
});
