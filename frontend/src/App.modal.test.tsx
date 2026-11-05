// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import type { PageEnvelope } from './types';

const csrf = { token: 'test-csrf', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const shared = { workspacePrincipal: { roles: ['EMPLOYEE', 'ATTENDANCE_ADMIN'] }, workspaceEmployee: { employeeName: '담당자', companyName: '합성 회사' } };
const list: PageEnvelope = { page: 'org/access-list', csrf, data: { ...shared, q: '직원', page: 0, hasNext: false, accessRows: [{ employeeId: 'e-1', employeeNo: '26001', employeeName: '직원', roles: ['EMPLOYEE'], active: true }] } };
const command = { commandId: 'cmd', expectedVersion: 1, reason: '' };
const access: PageEnvelope = { page: 'org/access', csrf, data: { ...shared, employee: { id: 'e-1', displayName: '직원', active: true }, account: { email: 'test@example.test', active: true, credentialSet: true }, eligibleEmployee: true, rolesForm: { ...command, roles: ['EMPLOYEE'] }, invitationForm: command, approverForm: { ...command, validFrom: '2026-11-04' }, candidates: [], assignments: [], currentAssignment: null } };
function response(envelope: PageEnvelope, path: string, status = 200) {
  const result = new Response(JSON.stringify(envelope), { status, headers: { 'Content-Type': 'application/vnd.worklog.page+json' } });
  Object.defineProperty(result, 'url', { value: new URL(path, location.href).href });
  return result;
}
beforeEach(() => {
  history.replaceState(null, '', '/admin/access?q=직원');
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function(this: HTMLDialogElement) { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function(this: HTMLDialogElement) { this.open = false; } });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
async function open(fetcher = vi.fn<typeof fetch>().mockResolvedValue(response(access, '/admin/employees/e-1/access'))) {
  vi.stubGlobal('fetch', fetcher);
  render(<App initial={list} />);
  const filter = screen.getByLabelText('사번·이름 검색') as HTMLInputElement;
  fireEvent.change(filter, { target: { value: '입력 유지' } });
  const trigger = await screen.findByRole('link', { name: '직원 권한 관리' });
  trigger.focus(); fireEvent.click(trigger);
  return { dialog: await screen.findByRole('dialog'), filter, trigger, fetcher };
}
describe('업무 모달 탐색', () => {
  it('권한 변경을 원래 목록 위에 열고 닫으면 입력과 초점을 복원한다', async () => {
    const { dialog, filter, trigger, fetcher } = await open();
    expect(document.contains(filter)).toBe(true);
    expect(filter.value).toBe('입력 유지');
    expect(within(dialog).getByLabelText('역할 변경 사유')).toBeTruthy();
    fireEvent.click(within(dialog).getAllByRole('button', { name: '닫기' })[0]);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(filter.value).toBe('입력 유지');
    expect(document.activeElement).toBe(trigger);
    await waitFor(() => expect(location.pathname).toBe('/admin/access'));
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it('입력 후 닫기를 취소하면 내용이 남고 Esc로도 확인 후 닫는다', async () => {
    const { dialog } = await open();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    fireEvent.change(within(dialog).getByLabelText('역할 변경 사유'), { target: { value: '권한 조정' } });
    fireEvent.click(within(dialog).getAllByRole('button', { name: '닫기' })[0]);
    expect(confirm).toHaveBeenCalledTimes(1);
    expect((within(dialog).getByLabelText('역할 변경 사유') as HTMLTextAreaElement).value).toBe('권한 조정');
    confirm.mockReturnValue(true); fireEvent(dialog, new Event('cancel', { cancelable: true }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });
  it('검증 오류와 CSRF를 모달에 유지하고 저장 중 닫기를 막는다', async () => {
    let resolve!: (value: Response) => void;
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response(access, '/admin/employees/e-1/access')).mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    const { dialog } = await open(fetcher);
    fireEvent.change(within(dialog).getByLabelText('역할 변경 사유'), { target: { value: '조정' } });
    fireEvent.submit(within(dialog).getByRole('button', { name: '역할 저장' }).closest('form')!);
    expect(String(fetcher.mock.calls[1][1]?.body)).toContain('_csrf=test-csrf');
    expect((within(dialog).getAllByRole('button', { name: '닫기' })[0] as HTMLButtonElement).disabled).toBe(true);
    await act(async () => resolve(response({ ...access, data: { ...access.data, error: '역할을 다시 확인하세요.', rolesForm: { ...command, reason: '조정', roles: ['EMPLOYEE'] } } }, '/admin/employees/e-1/roles', 422)));
    expect(await within(dialog).findByText('역할을 다시 확인하세요.')).toBeTruthy();
    expect((within(dialog).getByLabelText('역할 변경 사유') as HTMLTextAreaElement).value).toBe('조정');
    expect(screen.getByLabelText('사번·이름 검색')).toBeTruthy();
  });
  it('브라우저 뒤로가기는 서버 재조회 없이 작업 모달만 닫는다', async () => {
    const { fetcher } = await open();
    act(() => history.back());
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText('사번·이름 검색')).toBeTruthy();
  });
  it('권한 만료 응답은 모달뿐 아니라 배경의 계정 목록도 제거한다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response(access, '/admin/employees/e-1/access')).mockResolvedValueOnce(new Response('', { status: 403 }));
    const { dialog } = await open(fetcher);
    fireEvent.submit(within(dialog).getByRole('button', { name: '역할 저장' }).closest('form')!);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.queryByLabelText('사번·이름 검색')).toBeNull();
    expect(screen.queryByText('26001')).toBeNull();
  });
  it('저장 성공 후 목록을 새로 읽고 닫으면 변경된 권한을 표시한다', async () => {
    const updated = { ...list, data: { ...list.data, accessRows: [{ employeeId: 'e-1', employeeNo: '26001', employeeName: '직원', roles: ['EMPLOYEE', 'APPROVER'], active: true }] } };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response(access, '/admin/employees/e-1/access'))
      .mockResolvedValueOnce(response(access, '/admin/employees/e-1/access'))
      .mockResolvedValueOnce(response(updated, '/admin/access?q=직원'));
    const { dialog } = await open(fetcher);
    fireEvent.change(within(dialog).getByLabelText('역할 변경 사유'), { target: { value: '업무 배정' } });
    fireEvent.submit(within(dialog).getByRole('button', { name: '역할 저장' }).closest('form')!);
    await waitFor(() => expect((within(dialog).getAllByRole('button', { name: '닫기' })[0] as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(within(dialog).getAllByRole('button', { name: '닫기' })[0]);
    await waitFor(() => expect(location.pathname).toBe('/admin/access'));
    expect(screen.getByText('공통 · 결재 담당 가능')).toBeTruthy();
    const restoredTrigger = await screen.findByRole('link', { name: '직원 권한 관리' });
    await waitFor(() => expect(document.activeElement).toBe(restoredTrigger));
  });
  it('저장은 성공하고 목록 조회만 실패하면 새 폼 버전과 정확한 결과를 유지한다', async () => {
    const saved = { ...access, data: { ...access.data, rolesForm: { ...command, expectedVersion: 2, roles: ['EMPLOYEE', 'APPROVER'] } } };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response(access, '/admin/employees/e-1/access'))
      .mockResolvedValueOnce(response(saved, '/admin/employees/e-1/access'))
      .mockResolvedValueOnce(new Response('', { status: 503 }));
    const { dialog } = await open(fetcher);
    fireEvent.submit(within(dialog).getByRole('button', { name: '역할 저장' }).closest('form')!);
    await within(dialog).findByText(/저장은 완료.*목록/);
    const form = within(dialog).getByRole('button', { name: '역할 저장' }).closest('form')!;
    expect(new FormData(form).get('expectedVersion')).toBe('2');
    expect((within(dialog).getByLabelText('승인자 (APPROVER)') as HTMLInputElement).checked).toBe(true);
  });
});
