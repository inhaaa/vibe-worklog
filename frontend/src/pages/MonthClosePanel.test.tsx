// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { PAGE_ACCEPT } from '../transport';
import { IdentityPages } from './IdentityPages';

const path = '/admin/attendance/month-close';
const csrf = { token: 'test-csrf', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const organization = { id: '2266031f-88f4-49b0-b426-69cd8cab1183', code: 'DEV', name: '개발팀' };
const row = { employeeId: 'employee-a', employeeNo: '101', employeeName: '한사원', workDate: '2026-10-13',
  organizationName: '개발팀', reasons: ['UNCONFIRMED'], resultId: 'result-a', pendingRequestId: null, checkoutFollowUpEligible: true };
const review = { month: '2026-10', latestMonth: '2026-11', organizationId: organization.id, options: [organization],
  q: '한 & 팀', reason: 'UNCONFIRMED', page: 2, hasNext: true, hasPrevious: true, rows: [row], error: null };
const open = { month: review.month, status: 'OPEN', version: 0, pastMonth: true, history: [] };
const check = { month: review.month, status: 'OPEN', version: 0, pastMonth: true, employeeCount: 3, reviewDays: 0,
  uncalculatedDays: 0, staleDays: 0, unconfirmedDays: 0, pendingCorrectionDays: 0, pendingRequests: 0, openFollowUps: 0, canClose: true };
const closure = { month: review.month, status: 'CLOSED', version: 1, changedAt: '2026-11-18T23:10:20Z',
  actorName: '박담당', reason: '전사 근태 대조' };
const closed = { ...open, status: 'CLOSED', version: 1, history: [closure] };
const reopening = { ...closure, status: 'OPEN', version: 2, reason: '소급 정정 검토', changedAt: '2026-11-19T02:00:00Z' };
const asJson = (body: unknown, status = 200, media = 'application/json') =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': media } });
function install(send: typeof fetch, readList: typeof fetch = async () => listResponse(review)) {
  vi.stubGlobal('fetch', (url: RequestInfo | URL, options?: RequestInit) => {
    if (String(url).startsWith('/admin/attendance/month-close/export-options?')) {
      return Promise.resolve(asJson({ month: review.month, version: 1, departments: [] }));
    }
    return String(url).startsWith('/company/attendance?') ? readList(url, options) : send(url, options);
  });
}
function listResponse(model: typeof review) {
  return asJson({ page: 'company/attendance', csrf, data: { monthEndReview: model, canViewCheckout: true,
    workspacePrincipal: { roles: ['ATTENDANCE_ADMIN'] } } }, 200, PAGE_ACCEPT);
}
function page(model = review, roles = ['ATTENDANCE_ADMIN']) {
  return <IdentityPages page="company/attendance" data={{ monthEndReview: model, canViewCheckout: true,
    workspacePrincipal: { roles } }} csrf={csrf} reload={vi.fn()} />;
}
it('담당자는 월말 확인 대상이 없는 달에도 같은 월의 사원별 선택근무 정산으로 이동한다', async () => {
  install(async () => asJson(open));
  render(page({ ...review, rows: [] }));
  await screen.findByText('마감 전');
  expect(screen.getByRole('link', { name: '사원별 선택근무 정산' }).getAttribute('href'))
    .toBe('/admin/attendance?month=2026-10#selective-settlements');
});
const button = (name: string) => screen.getByRole<HTMLButtonElement>('button', { name });
const edit = () => button('보완 요청');
const all = () => screen.getByRole<HTMLInputElement>('checkbox', { name: '현재 페이지 전체 선택' });
const body = (send: ReturnType<typeof vi.fn<typeof fetch>>, index: number) => JSON.parse(String(send.mock.calls[index][1]?.body));
async function startCheck() {
  await screen.findByText('마감 전');
  fireEvent.click(button('회사 전체 마감 점검'));
}
function typeReason(label: string, value: string) {
  fireEvent.change(screen.getByRole('textbox', { name: label }), { target: { value } });
}
function filteredDetails() {
  const detail = new URL(screen.getByRole('link', { name: '계산 근거' }).getAttribute('href')!, 'https://example.test');
  const returnTo = new URL(detail.searchParams.get('returnTo')!, 'https://example.test');
  expect(Object.fromEntries(returnTo.searchParams)).toEqual({ view: 'month-end', month: review.month,
    organizationId: organization.id, reason: review.reason, q: review.q, page: '2' });
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it('담당자만 월 상태를 조회하고 상태 조회가 실패해도 기존 읽기·필터·페이지는 유지한다', async () => {
  const send = vi.fn<typeof fetch>().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(asJson(open));
  install(send);
  const view = render(page(review, ['EMPLOYEE', 'COMPANY_REPRESENTATIVE']));
  expect(send).not.toHaveBeenCalled();
  expect(screen.queryByRole('region', { name: '월 마감 관리' })).toBeNull();
  filteredDetails();
  view.rerender(page());
  expect(edit().disabled).toBe(true);
  expect(all().disabled).toBe(true);
  fireEvent.click(await screen.findByRole('button', { name: '마감 상태 다시 조회' }));
  filteredDetails();
  await screen.findByText('마감 전');
  expect(edit().disabled).toBe(false);
  expect(all().disabled).toBe(false);
  expect(send.mock.calls.map(([url]) => url)).toEqual([path + '?month=2026-10', path + '?month=2026-10']);
  fireEvent.change(screen.getByLabelText('조회 월'), { target: { value: '2026-09' } });
  expect(button('회사 전체 마감 점검').disabled).toBe(true);
});

it('월 전환 뒤 도착한 이전 월 응답이 현재 달 상태·조회권을 바꾸지 않는다', async () => {
  let finish!: (response: Response) => void;
  const send = vi.fn<typeof fetch>().mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }))
    .mockResolvedValueOnce(asJson({ ...open, month: '2026-11', pastMonth: false }));
  install(send);
  const view = render(page());
  view.rerender(page({ ...review, month: '2026-11', rows: [] }));
  await screen.findByText('마감 전');
  await act(async () => finish(asJson({ data: { message: '이전 조회 권한 없음' } }, 403, PAGE_ACCEPT)));
  expect(screen.getByText('마감 전')).toBeTruthy();
  expect(screen.getByText(/2026-12-01부터 마감/)).toBeTruthy();
  expect(screen.queryByRole('alert')).toBeNull();
});

it('회사 전체 점검 건수로 차단 사유를 확인하며 빈 페이지나 0건의 필터 결과로 마감을 허용하지 않는다', async () => {
  const blocked = { ...check, employeeCount: 12, reviewDays: 5, uncalculatedDays: 3, staleDays: 1,
    unconfirmedDays: 2, pendingCorrectionDays: 1, pendingRequests: 4, openFollowUps: 2, canClose: false };
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(asJson(open)).mockResolvedValueOnce(asJson(blocked));
  install(send);
  render(page({ ...review, rows: [] })); await startCheck();
  expect(body(send, 1)).toEqual({ month: review.month });
  expect(send.mock.calls[1][0]).toBe(path + '/check');
  const result = within(await screen.findByRole('region', { name: '회사 전체 점검 결과' }));
  for (const [name, value] of Object.entries({ '점검 사원': 12, '확인할 근무일': 5, '미산정': 3, '재집계 필요': 1,
    '근무 확인 필요': 2, '정정 결재 대기': 1, '결재 대기 신청': 4, '진행 중 보완 요청': 2 })) {
    expect(result.getByText(name).parentElement?.textContent).toContain(String(value));
  }
  expect(screen.queryByRole('button', { name: '월 마감 확정' })).toBeNull();
  const metrics = [
    ['확인할 근무일', 5, 'reason', 'ALL'], ['미산정', 3, 'reason', 'UNCALCULATED'],
    ['재집계 필요', 1, 'reason', 'STALE'], ['근무 확인 필요', 2, 'reason', 'UNCONFIRMED'],
    ['정정 결재 대기', 1, 'reason', 'PENDING_CORRECTION'], ['결재 대기 신청', 4, 'kind', 'PENDING_REQUEST'],
    ['진행 중 보완 요청', 2, 'kind', 'OPEN_FOLLOW_UP'],
  ] as const;
  expect(result.getAllByRole('link')).toHaveLength(metrics.length);
  for (const [name, count, field, value] of metrics) {
    const href = result.getByRole('link', { name: `${name} ${count}건 목록 보기` }).getAttribute('href')!;
    const url = new URL(href, 'https://example.test');
    expect(url.pathname).toBe('/company/attendance');
    const origin = new URL(url.searchParams.get('source')!, url.origin);
    expect(Object.fromEntries(origin.searchParams)).toEqual({ view: 'month-end', month: review.month,
      q: review.q, reason: review.reason, organizationId: organization.id, page: '2' });
    url.searchParams.delete('source');
    expect(Object.fromEntries(url.searchParams)).toEqual({ view: field === 'reason' ? 'month-end' : 'close-blockers',
      month: review.month, [field]: value, page: '0' });
  }
});

it('0건과 점검 사원 수는 링크를 제공하지 않는다', async () => {
  install(vi.fn<typeof fetch>().mockResolvedValueOnce(asJson(open)).mockResolvedValueOnce(asJson(check)));
  render(page()); await startCheck();
  const result = within(await screen.findByRole('region', { name: '회사 전체 점검 결과' }));
  expect(result.queryAllByRole('link')).toHaveLength(0);
  expect(result.getByText('3명')).toBeTruthy();
  expect(result.getAllByText('0건')).toHaveLength(7);
});

it('재직기간 밖에 남은 보완 요청의 근무일과 사번으로 인사정보를 찾아간다', async () => {
  install(vi.fn<typeof fetch>().mockResolvedValueOnce(asJson(open)).mockResolvedValueOnce(asJson({
    ...check, employeeCount: 0, openFollowUps: 1, canClose: false,
    outOfEmploymentFollowUp: { employeeNo: 'A & B', workDate: '2026-10-31' },
  })));
  render(page({ ...review, rows: [] }));
  await startCheck();
  const link = await screen.findByRole('link', { name: '사번 A & B 재직기간 확인' });
  expect(link.getAttribute('href')).toBe('/admin/employees/bulk-edit?q=A%20%26%20B');
  expect(screen.getByText(/2026-10-31 보완 요청일이 재직기간 밖/)).toBeTruthy();
  expect(screen.queryByRole('button', { name: '월 마감 확정' })).toBeNull();
});

it('담당자가 점검한 월과 버전으로 한 번 마감하고, 마감한 월의 상세와 동일 조건을 유지한 채 사유를 남겨 재개한다', async () => {
  let finish!: (response: Response) => void;
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(asJson(open)).mockResolvedValueOnce(asJson(check))
    .mockImplementationOnce(() => new Promise(resolve => { finish = resolve; })).mockResolvedValueOnce(asJson(closed))
    .mockResolvedValueOnce(asJson(reopening)).mockResolvedValueOnce(asJson({ ...open, version: 2, history: [reopening, closure] }));
  install(send);
  render(page()); await startCheck();
  const input = await screen.findByRole<HTMLInputElement>('textbox', { name: '월 마감 사유' });
  for (const reason of ['   ', '사유\t확인', '가'.repeat(501)]) {
    fireEvent.change(input, { target: { value: reason } });
    expect(button('월 마감 확정').disabled).toBe(true);
  }
  fireEvent.change(input, { target: { value: '😀'.repeat(500) } });
  expect(button('월 마감 확정').disabled).toBe(false);
  fireEvent.change(input, { target: { value: ' 전사 근태 대조 ' } });
  fireEvent.submit(input.form!); fireEvent.submit(input.form!);
  expect(send).toHaveBeenCalledTimes(3);
  const command = body(send, 2);
  expect(command).toEqual({ month: review.month, action: 'CLOSE', expectedVersion: 0, reason: closure.reason,
    commandId: expect.stringMatching(/^[a-f0-9-]{36}$/i) });
  expect(send.mock.calls[2][0]).toBe(path);
  for (const index of [1, 2]) {
    const init = send.mock.calls[index][1];
    expect(init).toMatchObject({ method: 'POST', credentials: 'same-origin' });
    expect(new Headers(init?.headers).get(csrf.headerName)).toBe(csrf.token);
    expect(new Headers(init?.headers).get('Accept')).toContain(PAGE_ACCEPT);
  }
  await act(async () => finish(asJson(closure)));
  await screen.findByText('마감 완료', { selector: 'strong' });
  expect(edit().disabled).toBe(true);
  expect(all().disabled).toBe(true);
  expect(button('선택 재집계').disabled).toBe(true);
  filteredDetails();
  expect(screen.getByRole('link', { name: '다음' }).getAttribute('href')).toContain('page=3');
  const history = within(screen.getByRole('list', { name: '마감 처리 이력' }));
  expect(history.getByText(closure.reason)).toBeTruthy();
  expect(history.getByText('박담당')).toBeTruthy();
  expect(history.getByText('2026-11-19 08:10:20')).toBeTruthy();
  fireEvent.click(button('월 마감 재개'));
  typeReason('월 재개 사유', reopening.reason);
  fireEvent.click(button('재개 확정'));
  await screen.findByText('마감 전', { selector: 'strong' });
  expect(body(send, 4)).toEqual({ ...command, action: 'REOPEN', expectedVersion: 1, reason: reopening.reason,
    commandId: expect.any(String) });
  expect(body(send, 4).commandId).not.toBe(command.commandId);
  expect(edit().disabled).toBe(false);
  expect(screen.getByText(/다시.*점검.*마감/)).toBeTruthy();
  filteredDetails();
});

it('응답 유실 뒤 사유·명령을 바꾸지 않고 재시도하고 그 사이 다른 담당자가 재개했다면 최신 상태를 따른다', async () => {
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(asJson(open)).mockResolvedValueOnce(asJson(check))
    .mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(asJson(closure))
    .mockResolvedValueOnce(asJson({ ...open, version: 2, history: [reopening, closure] }));
  install(send);
  render(page()); await startCheck();
  await screen.findByLabelText('월 마감 사유');
  typeReason('월 마감 사유', closure.reason);
  fireEvent.click(button('월 마감 확정'));
  const retry = await screen.findByRole('button', { name: '같은 요청 다시 보내기' });
  expect(screen.getByRole<HTMLInputElement>('textbox', { name: '월 마감 사유' }).readOnly).toBe(true);
  expect(all().disabled).toBe(true);
  fireEvent.click(retry);
  await screen.findByText(reopening.reason);
  expect(screen.getByText('마감 전', { selector: 'strong' })).toBeTruthy();
  expect(send.mock.calls[2][1]?.body).toBe(send.mock.calls[3][1]?.body);
  expect(button('회사 전체 마감 점검').disabled).toBe(false);
});

it('다른 담당자의 처리와 충돌하면 새 상태를 읽고 이전 버전으로 재전송할 수 없다', async () => {
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(asJson(open)).mockResolvedValueOnce(asJson(check))
    .mockResolvedValueOnce(asJson({ message: '다른 담당자가 먼저 마감했습니다.' }, 409)).mockResolvedValueOnce(asJson(closed));
  const list = vi.fn<typeof fetch>().mockResolvedValueOnce(listResponse({ ...review, rows: [] }));
  install(send, list);
  render(page()); await startCheck();
  await screen.findByLabelText('월 마감 사유');
  typeReason('월 마감 사유', closure.reason);
  fireEvent.click(button('월 마감 확정'));
  await screen.findByRole('button', { name: '월 마감 재개' });
  expect(screen.getByText(/다른 담당자가 먼저 마감/)).toBeTruthy();
  expect(screen.queryByRole('button', { name: '같은 요청 다시 보내기' })).toBeNull();
  expect(screen.queryByLabelText('월 마감 사유')).toBeNull();
  await screen.findByText('선택한 조건에 해당하는 월말 확인 대상이 없습니다.');
  expect(list).toHaveBeenCalledOnce();
});

it('점검 중 다른 담당자의 재개를 확인하면 이전 목록을 버리고 새 상태와 재집계 사유를 표시한다', async () => {
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(asJson(open))
    .mockResolvedValueOnce(asJson({ ...check, version: 2, reviewDays: 1, staleDays: 1, canClose: false }))
    .mockResolvedValueOnce(asJson({ ...open, version: 2, history: [reopening, closure] }));
  const list = vi.fn<typeof fetch>().mockResolvedValueOnce(listResponse({ ...review, rows: [{ ...row, reasons: ['UNCONFIRMED', 'STALE'] }] }));
  install(send, list);
  render(page()); await startCheck();
  await screen.findByText(reopening.reason);
  await within(screen.getByRole('list', { name: '월말 확인 사원과 근무일' })).findByText('재집계 필요');
  expect(screen.queryByRole('textbox', { name: '월 마감 사유' })).toBeNull();
  expect(list).toHaveBeenCalledOnce();
});

it.each(['점검', '저장'])('%s 도중 다른 월로 이동하면 이전 요청 결과를 현재 달에 표시하지 않는다', async stage => {
  let finish!: (response: Response) => void;
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(asJson(open));
  if (stage === '저장') send.mockResolvedValueOnce(asJson(check));
  send.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }))
    .mockResolvedValueOnce(asJson({ ...open, month: '2026-11', pastMonth: false }));
  install(send);
  const view = render(page()); await startCheck();
  if (stage === '저장') {
    await screen.findByLabelText('월 마감 사유');
    typeReason('월 마감 사유', closure.reason); fireEvent.click(button('월 마감 확정'));
  }
  view.rerender(page({ ...review, month: '2026-11', rows: [] }));
  await screen.findByText(/2026-12-01부터 마감/);
  await act(async () => finish(asJson(stage === '저장' ? closure : check)));
  expect(screen.queryByLabelText('월 마감 사유')).toBeNull();
  expect(screen.queryByText(/근태 마감을 기록/)).toBeNull();
  expect(screen.getByText('마감 전')).toBeTruthy();
});

it('사유가 거절되면 고쳐 보내고, 성공 후 상태 조회가 이전 버전이면 읽기를 유지하며 상태만 다시 확인한다', async () => {
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(asJson(open)).mockResolvedValueOnce(asJson(check))
    .mockResolvedValueOnce(asJson({ message: '마감 사유를 확인해 주세요.' }, 422)).mockResolvedValueOnce(asJson(closure))
    .mockResolvedValueOnce(asJson(open)).mockResolvedValueOnce(asJson(closed));
  install(send);
  render(page()); await startCheck(); await screen.findByLabelText('월 마감 사유');
  typeReason('월 마감 사유', '처음 작성한 사유'); fireEvent.click(button('월 마감 확정'));
  await screen.findByText('마감 사유를 확인해 주세요.');
  expect(screen.getByRole<HTMLInputElement>('textbox', { name: '월 마감 사유' }).value).toBe('처음 작성한 사유');
  typeReason('월 마감 사유', closure.reason); fireEvent.click(button('월 마감 확정'));
  const fresh = await screen.findByRole('button', { name: '마감 상태 다시 조회' });
  expect(all().disabled).toBe(true);
  filteredDetails();
  fireEvent.click(fresh);
  await screen.findByText('마감 완료', { selector: 'strong' });
  expect(body(send, 2).commandId).not.toBe(body(send, 3).commandId);
  expect(send.mock.calls.filter(([url]) => url === path)).toHaveLength(2);
});

it.each([['상태 조회', 403], ['점검', 503], ['재개', 401]] as const)('%s에서 권한이나 인증이 사라지면 사원 목록과 처리 이력을 닫는다', async (stage, code) => {
  const send = vi.fn<typeof fetch>();
  if (stage === '점검') send.mockResolvedValueOnce(asJson(open));
  if (stage === '재개') send.mockResolvedValueOnce(asJson(closed));
  send.mockResolvedValueOnce(asJson({ page: 'org/problem', csrf, data: { reauthenticate: code === 503 } }, code, PAGE_ACCEPT));
  install(send);
  render(page());
  if (stage === '점검') await startCheck();
  if (stage === '재개') {
    fireEvent.click(await screen.findByRole('button', { name: '월 마감 재개' }));
    typeReason('월 재개 사유', reopening.reason); fireEvent.click(button('재개 확정'));
  }
  await screen.findByRole('alert');
  expect(screen.queryByRole('list', { name: '월말 확인 사원과 근무일' })).toBeNull();
  expect(screen.queryByRole('region', { name: '월 마감 관리' })).toBeNull();
  expect(screen.queryByText(closure.reason)).toBeNull();
  if (code !== 403) expect(screen.getByRole('link', { name: '다시 로그인' })).toBeTruthy();
});

it('재개 직후 이전 목록을 숨기고 동일 조건의 조회만 재시도해 새로 생긴 재집계 사유를 표시한다', async () => {
  let rejectRead!: (error: Error) => void;
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(asJson(closed)).mockResolvedValueOnce(asJson(reopening))
    .mockResolvedValueOnce(asJson({ ...open, version: 2, history: [reopening, closure] }));
  const list = vi.fn<typeof fetch>().mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectRead = reject; }))
    .mockResolvedValueOnce(listResponse({ ...review, rows: [{ ...row, reasons: ['STALE', 'UNCONFIRMED'] }] }));
  install(send, list);
  render(page()); fireEvent.click(await screen.findByRole('button', { name: '월 마감 재개' }));
  typeReason('월 재개 사유', reopening.reason); fireEvent.click(button('재개 확정'));
  await screen.findByText(reopening.reason);
  expect(screen.queryByRole('list', { name: '월말 확인 사원과 근무일' })).toBeNull();
  await act(async () => rejectRead(new TypeError('offline')));
  fireEvent.click(await screen.findByRole('button', { name: '월말 목록 다시 조회' }));
  const rows = await screen.findByRole('list', { name: '월말 확인 사원과 근무일' });
  expect(within(rows).getByText('재집계 필요')).toBeTruthy();
  filteredDetails();
  expect(list).toHaveBeenCalledTimes(2);
  expect(list.mock.calls[0][0]).toBe(list.mock.calls[1][0]);
  const query = new URL(String(list.mock.calls[0][0]), 'https://example.test').searchParams;
  expect(Object.fromEntries(query)).toMatchObject({ month: review.month, organizationId: organization.id,
    q: review.q, reason: review.reason, page: '2' });
  expect(send.mock.calls.filter(([url]) => url === path)).toHaveLength(1);
  expect(document.activeElement).toBe(button('회사 전체 마감 점검'));
});
