// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { PAGE_ACCEPT, PageRequestError } from '../transport';
import { MonthCloseExport } from './MonthCloseExport';

const month = '2026-10';
const version = 3;
const base = '/admin/attendance/month-close';
const xlsx = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const id = '986dfa66-6936-42a8-9072-d423d9a80bb7';
const options = { month, version, departments: [
  { organizationId: id, organizationCode: 'DEV', organizationName: '개발팀' },
  { organizationId: null, organizationCode: null, organizationName: null },
] };
const json = (obj: unknown, status = 200, type = 'application/json') =>
  new Response(JSON.stringify(obj), { status, headers: { 'Content-Type': type } });
const file = (name: string) => new Response(new Uint8Array([80,75,3,4]), {
  headers: { 'Content-Type': xlsx, 'Content-Disposition': 'attachment; filename="' + name + '"' } });
const deny = vi.fn<(error: PageRequestError) => void>();
const refresh = vi.fn();
const saved = vi.fn<(name: string, url: string) => void>();
const createBlob = vi.fn(() => 'blob:worklog-close');
const revoke = vi.fn();
const view = (value = month, ver = version) => <MonthCloseExport month={value} version={ver} deny={deny} onRefresh={refresh} />;
const download = () => screen.getByRole<HTMLButtonElement>('button', { name: 'Excel 다운로드' });
const combo = () => screen.getByRole<HTMLSelectElement>('combobox', { name: '다운로드 범위' });
beforeEach(() => {
  vi.stubGlobal('URL', class extends URL { static createObjectURL = createBlob; static revokeObjectURL = revoke; });
  vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(function(this: HTMLAnchorElement) { saved(this.download,this.href); });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.clearAllMocks(); });

it('해당 마감의 회사·부서·소속 미등록 범위만 제공하고 월과 버전으로 실제 XLSX 첨부를 저장한다', async () => {
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(json(options));
  vi.stubGlobal('fetch',send);
  render(view());
  expect(await screen.findByRole('option',{ name: /개발팀/ })).toBeTruthy();
  expect(screen.getAllByRole('option')).toHaveLength(3);
  expect(combo().selectedOptions[0].textContent).toBe('회사 전체');
  expect(send.mock.calls[0][0]).toBe(base + '/export-options?month=' + month + '&version=3');
  for (const [choice,scope] of [['COMPANY','COMPANY'],[id,'DEPARTMENT'],['UNASSIGNED','UNASSIGNED']] as const) {
    const filename = 'worklog-close-' + month + '-v3-' + scope + '.xlsx';
    send.mockResolvedValueOnce(file(filename));
    fireEvent.change(combo(),{ target: { value: choice } });
    fireEvent.click(download());
    await waitFor(() => expect(saved).toHaveBeenCalledWith(filename,'blob:worklog-close'));
    const [url,init] = send.mock.calls.at(-1)!;
    const target = new URL(String(url),'https://example.test');
    expect(target.pathname).toBe(base + '/export');
    expect(Object.fromEntries(target.searchParams)).toEqual({ month, version: '3', scope, ...(scope === 'DEPARTMENT' ? { organizationId: id } : {}) });
    expect(init).toMatchObject({ credentials: 'same-origin', cache: 'no-store' });
    expect(new Headers(init?.headers).get('Accept')).toContain(xlsx);
    expect(new Headers(init?.headers).get('Accept')).toContain(PAGE_ACCEPT);
  }
  await waitFor(() => expect(revoke).toHaveBeenCalledTimes(3));
});

it.each(['범위', '파일'])('%s 조회 중 재개·재마감되면 이전 버전 파일을 막고 현재 월 상태를 다시 조회한다', async phase => {
  const send = vi.fn<typeof fetch>();
  if (phase === '파일') send.mockResolvedValueOnce(json(options));
  send.mockResolvedValueOnce(json({ message: '마감 상태가 바뀌었습니다.' },409));
  vi.stubGlobal('fetch',send); render(view());
  if (phase === '파일') { await screen.findByRole('option',{ name: /개발팀/ }); fireEvent.click(download()); }
  expect((await screen.findByRole('alert')).textContent).toContain('마감 상태');
  expect(screen.queryByRole<HTMLButtonElement>('button',{ name:'Excel 다운로드' })?.disabled ?? true).toBe(true);
  fireEvent.click(screen.getByRole('button',{ name: '마감 상태 다시 조회' }));
  expect(refresh).toHaveBeenCalledOnce(); expect(saved).not.toHaveBeenCalled();
});

it.each([401,403])('파일 조회 권한이 사라지면 숫자 %s의 오류를 상위 화면에 전달하고 저장하지 않는다', async status => {
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(json(options)).mockResolvedValueOnce(json({
    page: 'org/problem', csrf: {}, data: { message: '권한을 확인해 주세요.', reauthenticate: status === 401 },
  },status,PAGE_ACCEPT));
  vi.stubGlobal('fetch',send); render(view());
  await screen.findByRole('option',{ name: /개발팀/ }); fireEvent.click(download());
  await waitFor(() => expect(deny).toHaveBeenCalledWith(expect.objectContaining({ status, reauthenticate: status === 401 })));
  expect(saved).not.toHaveBeenCalled();
});

it('범위가 사라지면 선택지를 다시 조회하고 로컬 파일 저장이 실패하면 같은 범위로 재시도한다', async () => {
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(json(options)).mockResolvedValueOnce(json({ message: '없음' },404))
    .mockResolvedValueOnce(json({ ...options, departments: [] })).mockResolvedValueOnce(file('company.xlsx'))
    .mockResolvedValueOnce(file('company.xlsx'));
  vi.stubGlobal('fetch',send); render(view());
  await screen.findByRole('option',{ name: /개발팀/ });
  fireEvent.change(combo(),{ target: { value: id } }); fireEvent.click(download());
  fireEvent.click(await screen.findByRole('button',{ name: '다운로드 범위 다시 조회' }));
  await screen.findByRole('option',{ name: '회사 전체' });
  expect(combo().options).toHaveLength(1);
  createBlob.mockImplementationOnce(() => { throw new Error('download unavailable'); });
  fireEvent.click(download());
  await screen.findByRole('alert');
  expect(download().disabled).toBe(false);
  fireEvent.click(download());
  await waitFor(() => expect(saved).toHaveBeenCalledWith('company.xlsx','blob:worklog-close'));
});

it('범위 조회 재시도와 파일 수신 중 중복 클릭을 처리하고 다른 월로 이동하면 늦은 파일을 저장하지 않는다', async () => {
  let finish!: (response: Response) => void;
  const send = vi.fn<typeof fetch>().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(json(options))
    .mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }))
    .mockResolvedValueOnce(json({ month: '2026-09', version: 1, departments: [] }));
  vi.stubGlobal('fetch',send);
  const page = render(view());
  fireEvent.click(await screen.findByRole('button',{ name: '다운로드 범위 다시 조회' }));
  await screen.findByRole('option',{ name: /개발팀/ });
  const button = download(); fireEvent.click(button); fireEvent.click(button);
  expect(send).toHaveBeenCalledTimes(3);
  page.rerender(view('2026-09',1));
  await waitFor(() => expect(send).toHaveBeenCalledTimes(4));
  await act(async () => finish(file('old.xlsx')));
  expect(saved).not.toHaveBeenCalled();
  expect(combo().options).toHaveLength(1);
});
