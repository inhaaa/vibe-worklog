// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { PAGE_ACCEPT } from '../transport';
import { IdentityPages } from './IdentityPages';
import { stubOpenMonth } from './monthEndTestSupport';

const csrf = { token: 'csrf-test', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const base = '/admin/calculations/month-end';
const first = { employeeId: 'employee-a', employeeNo: '101', employeeName: '한사원', workDate: '2026-10-01',
  organizationName: '개발팀', reasons: ['UNCALCULATED'], resultId: null, pendingRequestId: null,
  checkoutFollowUpEligible: true };
const second = { ...first, workDate: '2026-10-02', reasons: ['STALE', 'PENDING_CORRECTION'], pendingRequestId: 'correction' };
const third = { ...first, employeeId: 'employee-b', employeeNo: '102', employeeName: '김사원' };
const month = { month: '2026-10', latestMonth: '2026-11', organizationId: 'abec29f1-6064-4422-b7b7-bdd8229538e9',
  options: [], q: '사원 & 팀', reason: 'ALL', page: 1, hasNext: true, hasPrevious: true, rows: [first, second, third], error: null };
const filters = { month: month.month, organizationId: month.organizationId, q: month.q, reason: month.reason, page: month.page };
const selected = month.rows.map(({ employeeId, workDate }) => ({ employeeId, workDate }));
const preview = { previewToken: 'checked-inputs', weekCount: 2, rows: month.rows.map(
  ({ employeeId, employeeNo, employeeName, workDate, reasons }) => ({
    employeeId, employeeNo, employeeName, workDate, reasons, weekStart: '2026-09-28', weekEnd: '2026-10-04',
  })) };
const receipt = { batchId: 'batch-id', weekCount: 2, rows: preview.rows.map((row, index) => ({
  employeeId: row.employeeId, employeeNo: row.employeeNo, employeeName: row.employeeName, workDate: row.workDate,
  resultId: 'result-' + index, actualWorkMillis: [0, null, 28_800_000][index], reasons: index === 1 ? ['PENDING_CORRECTION'] : [],
})) };
const json = (body: unknown, status = 200, media = 'application/json') =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': media } });
function data(model = month, roles = ['ATTENDANCE_ADMIN']) {
  return { monthEndReview: model, canViewCheckout: true, workspacePrincipal: { roles } };
}
const refreshed = () => json({ page: 'company/attendance', data: data({ ...month, rows: [] }), csrf }, 200, PAGE_ACCEPT);
function page(model = month, roles = ['ATTENDANCE_ADMIN']) {
  return <IdentityPages page="company/attendance" data={data(model, roles)} csrf={csrf} reload={vi.fn()} />;
}
const button = (name: string) => screen.getByRole<HTMLButtonElement>('button', { name });
const checkbox = (name: string) => screen.getByRole<HTMLInputElement>('checkbox', { name });
const all = () => checkbox('현재 페이지 전체 선택');
function body(send: ReturnType<typeof vi.fn<typeof fetch>>, index: number) {
  return JSON.parse(String(send.mock.calls[index][1]?.body));
}
function expectFilters(href: string) {
  const list = new URL(href, 'https://example.test');
  expect(list.pathname).toBe('/company/attendance');
  expect(Object.fromEntries(list.searchParams)).toEqual({ ...filters, page: '1', view: 'month-end' });
}
async function start() {
  await screen.findByText('마감 전');
  fireEvent.click(all());
  fireEvent.click(button('선택 재집계'));
}
async function execute() {
  const input = await screen.findByRole('textbox', { name: '재집계 사유' });
  fireEvent.change(input, { target: { value: '  월말 확정 자료 대조  ' } });
  fireEvent.click(button('재집계 실행'));
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it('사원·날짜를 선택해 인접 월이 포함된 주와 현재 사유를 보고, 검증한 이유와 한 명령으로 한 번 실행한다', async () => {
  let finish!: (value: Response) => void;
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(json(preview))
    .mockImplementationOnce(() => new Promise(resolve => { finish = resolve; })).mockResolvedValueOnce(refreshed());
  stubOpenMonth(send);
  render(page());
  await screen.findByText('마감 전');
  expect(button('선택 재집계').disabled).toBe(true);
  fireEvent.click(checkbox('101 한사원 2026-10-01 선택'));
  expect(all().indeterminate).toBe(true);
  fireEvent.click(all());
  expect(all().checked).toBe(true);
  fireEvent.click(all());
  expect(button('선택 재집계').disabled).toBe(true);
  await start();
  const review = within(await screen.findByRole('region', { name: '재집계 미리보기' }));
  expect(send).toHaveBeenCalledTimes(1);
  expect(send.mock.calls[0][0]).toBe(base + '/preview');
  expect(body(send, 0)).toEqual({ ...filters, rows: selected });
  expect(review.getAllByRole('listitem')).toHaveLength(3);
  expect(review.getByText(/사원별 주간 집계 2건/)).toBeTruthy();
  for (const row of review.getAllByRole('listitem')) {
    expect(row.textContent).toContain('2026-09-28');
    expect(row.textContent).toContain('2026-10-04');
  }
  expect(review.getByText(/정정 결재 대기/)).toBeTruthy();
  const reason = review.getByRole<HTMLInputElement>('textbox', { name: '재집계 사유' });
  expect(reason.tagName).toBe('INPUT');
  expect(review.getByText(/한 줄.*1~500자/)).toBeTruthy();
  for (const invalid of ['   ', '\u2003', '가'.repeat(501), '사유\t확인', '사유\u0085확인', '사유\uD800']) {
    fireEvent.change(reason, { target: { value: invalid } });
    expect(button('재집계 실행').disabled).toBe(true);
  }
  fireEvent.change(reason, { target: { value: '😀'.repeat(500) } });
  expect(button('재집계 실행').disabled).toBe(false);
  expect(reason.maxLength < 0 || reason.maxLength >= reason.value.length).toBe(true);
  fireEvent.change(reason, { target: { value: '\u001c\u2003월말 확정 자료 대조\u2003 ' } });
  const executeButton = button('재집계 실행');
  fireEvent.click(executeButton); fireEvent.click(executeButton);
  expect(send).toHaveBeenCalledTimes(2);
  expect(send.mock.calls[1][0]).toBe(base);
  expect(body(send, 1)).toEqual({ ...filters, rows: selected, previewToken: preview.previewToken,
    calculationReason: '월말 확정 자료 대조', commandId: expect.stringMatching(/^[a-f0-9-]{36}$/i) });
  for (const [, options] of send.mock.calls) {
    expect(options).toMatchObject({ method: 'POST', credentials: 'same-origin' });
    const headers = new Headers(options?.headers);
    expect(headers.get(csrf.headerName)).toBe(csrf.token);
    expect(headers.get('Accept')).toMatch(/^application\/json,/);
    expect(headers.get('Accept')).toContain(PAGE_ACCEPT);
  }
  await act(async () => finish(json(receipt)));
  const result = within(await screen.findByRole('region', { name: '재집계 결과' }));
  expect(result.getByText(/사원별 주간 집계 2건/)).toBeTruthy();
  const rows = result.getAllByRole('listitem');
  expect(rows).toHaveLength(3);
  expect(rows[0].textContent).toContain('0시간');
  expect(rows[0].textContent).toContain('해소');
  expect(rows[1].textContent).toContain('미확정');
  expect(rows[1].textContent).toContain('정정 결재 대기');
  expect(rows[2].textContent).toContain('8:00:00');
  rows.forEach((row, index) => {
    const href = new URL(within(row).getByRole('link', { name: '일 결과 상세' }).getAttribute('href')!, 'https://example.test');
    expect(href.pathname).toBe('/worktime/results/result-' + index);
    expectFilters(href.searchParams.get('returnTo')!);
  });
  await screen.findByText('선택한 조건에 해당하는 월말 확인 대상이 없습니다.');
  expectFilters(String(send.mock.calls[2][0]));
  expect(new Headers(send.mock.calls[2][1]?.headers).get('Accept')).toBe(PAGE_ACCEPT);
  fireEvent.click(result.getByRole('button', { name: '결과 확인' }));
  expect(screen.queryByRole('heading', { name: '재집계 결과' })).toBeNull();
});

it('단건 보완과 일괄 작업의 선택이 겹치지 않고 필터·페이지·권한이 바뀌면 선택을 초기화한다', async () => {
  stubOpenMonth();
  const view = render(page());
  await screen.findByText('마감 전');
  fireEvent.click(screen.getAllByRole('button', { name: '보완 요청' })[0]);
  expect(all().disabled).toBe(true);
  fireEvent.click(button('취소'));
  fireEvent.click(all());
  expect(screen.getAllByRole<HTMLButtonElement>('button', { name: '보완 요청' }).every(item => item.disabled)).toBe(true);
  fireEvent.change(screen.getByLabelText('사번 또는 이름'), { target: { value: '다른 검색' } });
  expect(all().checked).toBe(false);
  expect(button('선택 재집계').disabled).toBe(true);
  view.rerender(page({ ...month, page: 2 }));
  await screen.findByText('마감 전');
  expect(all().checked).toBe(false);
  fireEvent.click(all());
  view.rerender(page({ ...month, q: '변경' }));
  await screen.findByText('마감 전');
  expect(all().checked).toBe(false);
  view.rerender(page(month, ['EXECUTIVE', 'EMPLOYEE']));
  expect(screen.queryByRole('checkbox')).toBeNull();
  expect(screen.queryByRole('button', { name: '선택 재집계' })).toBeNull();
});

it('인접 월이 마감된 주는 열린 날짜만 계산한다고 안내하고 서버가 돌려준 범위를 그대로 표시한다', async () => {
  const day = { ...first, workDate: '2024-03-01' };
  stubOpenMonth(vi.fn<typeof fetch>().mockResolvedValueOnce(json({ previewToken: 'clipped', weekCount: 1,
    rows: [{ ...day, weekStart: '2024-03-01', weekEnd: '2024-03-03' }] })));
  render(page({ ...month, month: '2024-03', rows: [day] })); await start();
  const review = within(await screen.findByRole('region', { name: '재집계 미리보기' }));
  expect(review.getByText(/인접 월이 마감.*열린 날짜만/)).toBeTruthy();
  expect(review.getByText('2024-03-01 ~ 2024-03-03')).toBeTruthy();
});

it.each(['연결 중단', '서버 오류', '불완전한 결과'])('%s 뒤에는 같은 사유·명령·대상으로만 재시도한다', async failure => {
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(json(preview))
    .mockImplementationOnce(async () => {
      if (failure === '연결 중단') throw new TypeError('offline');
      return failure === '서버 오류' ? json({}, 503) : json({ ...receipt, rows: receipt.rows.slice(1) });
    }).mockResolvedValueOnce(json(receipt)).mockResolvedValueOnce(refreshed());
  stubOpenMonth(send);
  render(page()); await start(); await execute();
  const retry = await screen.findByRole('button', { name: '같은 요청 다시 보내기' });
  expect(all().disabled).toBe(true);
  const reason = screen.getByRole<HTMLInputElement>('textbox', { name: '재집계 사유' });
  expect(reason.readOnly || reason.disabled).toBe(true);
  fireEvent.click(retry);
  await screen.findByRole('heading', { name: '재집계 결과' });
  expect(send.mock.calls[1][1]?.body).toBe(send.mock.calls[2][1]?.body);
});

it('근거 충돌 시 기존 실행을 닫고 새 미리보기와 명령으로 실행한다', async () => {
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(json(preview))
    .mockResolvedValueOnce(json({ message: '근거가 변경되었습니다.' }, 409))
    .mockResolvedValueOnce(json({ ...preview, previewToken: 'fresh-inputs' }))
    .mockResolvedValueOnce(json(receipt)).mockResolvedValueOnce(refreshed());
  stubOpenMonth(send);
  render(page()); await start(); await execute();
  const next = await screen.findByRole('button', { name: '다시 미리보기' });
  expect(screen.queryByRole('button', { name: '재집계 실행' })).toBeNull();
  fireEvent.click(next);
  await execute();
  await screen.findByRole('heading', { name: '재집계 결과' });
  expect(send.mock.calls[2][0]).toBe(base + '/preview');
  expect(body(send, 2)).toEqual(body(send, 0));
  expect(body(send, 3).previewToken).toBe('fresh-inputs');
  expect(body(send, 3).commandId).not.toBe(body(send, 1).commandId);
});

it('목록 갱신 중과 실패 후에는 이전 목록·페이지와 빈 목록 안내를 숨기고 결과를 유지한 채 목록만 다시 조회한다', async () => {
  let failRefresh!: (error: Error) => void;
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(json(preview)).mockResolvedValueOnce(json(receipt))
    .mockImplementationOnce(() => new Promise<Response>((_, reject) => { failRefresh = reject; }))
    .mockResolvedValueOnce(refreshed());
  stubOpenMonth(send);
  render(page()); await start(); await execute();
  const result = within(await screen.findByRole('region', { name: '재집계 결과' }));
  const onlyResults = () => {
    expect(result.getAllByRole('link', { name: '일 결과 상세' })).toHaveLength(3);
    expect(screen.queryByRole('list', { name: '월말 확인 사원과 근무일' })).toBeNull();
    expect(screen.queryByRole('navigation', { name: '확인 대상 페이지' })).toBeNull();
    expect(screen.queryByRole('checkbox', { name: '현재 페이지 전체 선택' })).toBeNull();
    expect(screen.queryByRole('link', { name: '근태 집계' })).toBeNull();
    expect(screen.queryByText('선택한 조건에 해당하는 월말 확인 대상이 없습니다.')).toBeNull();
  };
  await result.findByRole('status');
  onlyResults();
  await act(async () => failRefresh(new TypeError('offline')));
  await result.findByRole('alert');
  onlyResults();
  fireEvent.click(await result.findByRole('button', { name: '목록 다시 조회' }));
  await screen.findByText('선택한 조건에 해당하는 월말 확인 대상이 없습니다.');
  expect(result.getAllByRole('link', { name: '일 결과 상세' })).toHaveLength(3);
  expect(send.mock.calls.filter(([url]) => url === base)).toHaveLength(1);
});

it('재집계 사유가 422로 거절되면 미리보기와 원래 입력을 보존하고 고친 사유로 실행한다', async () => {
  const message = '재집계 사유은(는) 공백이 아닌 500자 이내로 입력해 주세요.';
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(json(preview)).mockResolvedValueOnce(json({ message }, 422))
    .mockResolvedValueOnce(json(receipt)).mockResolvedValueOnce(refreshed());
  stubOpenMonth(send);
  render(page()); await start(); await execute();
  expect((await screen.findByRole('alert')).textContent).toBe(message);
  const review = within(screen.getByRole('region', { name: '재집계 미리보기' }));
  const input = review.getByRole<HTMLInputElement>('textbox', { name: '재집계 사유' });
  expect(input.value).toBe('  월말 확정 자료 대조  ');
  expect(input.readOnly).toBe(false);
  fireEvent.change(input, { target: { value: '  \u00A0입력 검토\u00A0  ' } });
  fireEvent.click(button('재집계 실행'));
  await screen.findByRole('heading', { name: '재집계 결과' });
  expect(send.mock.calls[2][0]).toBe(base);
  expect(body(send, 2)).toMatchObject({ previewToken: preview.previewToken, calculationReason: '\u00A0입력 검토\u00A0' });
  expect(body(send, 2).commandId).not.toBe(body(send, 1).commandId);
});

it.each(['미리보기', '실행'])('%s 중 재인증을 요청하는 503이 오면 이전 사원 자료와 재시도를 숨기고 로그인으로 안내한다', async stage => {
  const expired = json({ page: 'org/problem', csrf, data: { reauthenticate: true, message: '인증 상태를 확인할 수 없습니다.' } }, 503, PAGE_ACCEPT);
  const send = vi.fn<typeof fetch>();
  if (stage === '실행') send.mockResolvedValueOnce(json(preview));
  send.mockResolvedValueOnce(expired);
  stubOpenMonth(send);
  render(page()); await start();
  if (stage === '실행') await execute();
  expect((await screen.findByRole('alert')).textContent).toMatch(/다시 로그인/);
  expect(screen.getByRole('link', { name: '다시 로그인' }).getAttribute('href')).toBe('/login');
  expect(screen.queryByRole('list', { name: '월말 확인 사원과 근무일' })).toBeNull();
  expect(screen.queryByRole('button', { name: '같은 요청 다시 보내기' })).toBeNull();
  expect(screen.queryByRole('button', { name: '다시 미리보기' })).toBeNull();
});

it('결과 목록을 재조회할 때 권한이 회수되면 담당자 결과와 이전 목록을 숨긴다', async () => {
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(json(preview)).mockResolvedValueOnce(json(receipt))
    .mockResolvedValueOnce(json({ page: 'org/problem', csrf, data: { message: '권한 회수' } }, 403, PAGE_ACCEPT));
  stubOpenMonth(send);
  render(page()); await start(); await execute();
  expect((await screen.findByRole('alert')).textContent).toMatch(/권한/);
  expect(screen.queryByRole('list', { name: '월말 확인 사원과 근무일' })).toBeNull();
  expect(screen.queryByRole('heading', { name: '재집계 결과' })).toBeNull();
  expect(screen.queryByRole('button', { name: '재집계 실행' })).toBeNull();
});
