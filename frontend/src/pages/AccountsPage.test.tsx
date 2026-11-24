// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AccountsPage } from './AccountsPage';
import type { AccountDirectory } from './AccountsPage';

const csrf = { token: 't', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const JSON_HEADERS = { 'Content-Type': 'application/json' };
const model: AccountDirectory = {
  q: '', status: 'all', page: 0, hasNext: false,
  statuses: [{ value: 'all', label: '전체' }, { value: 'NONE', label: '계정 없음' }],
  items: [
    { employeeId: 'e1', employeeNo: '19001', displayName: '가', organizationName: '개발팀', state: 'NONE', label: '계정 없음', self: false },
    { employeeId: 'e2', employeeNo: '19002', displayName: '나', organizationName: '개발팀', state: 'LOCKED', label: '잠김(5회 실패)', self: false },
  ],
};

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('AccountsPage', () => {
  it('enables only the action that fits every selected employee', () => {
    render(<AccountsPage model={model} csrf={csrf} reload={() => undefined} />);
    fireEvent.click(screen.getByLabelText('19001 가 선택'));
    expect((screen.getByRole('button', { name: '계정 생성' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('처리 사유'), { target: { value: '개통' } });
    expect((screen.getByRole('button', { name: '계정 생성' }) as HTMLButtonElement).disabled).toBe(false);
    expect((screen.getByRole('button', { name: '비밀번호 초기화' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByLabelText('19002 나 선택'));
    expect((screen.getByRole('button', { name: '계정 생성' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('posts the selected ids and reloads, or shows each employee error', async () => {
    const reload = vi.fn();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify({ message: '선택한 사원 중 처리할 수 없는 사원이 있습니다.', errors: [{ employeeId: 'e2', message: '현재 재직 중인 활성 사원만 관리할 수 있습니다.' }] }), { status: 422, headers: JSON_HEADERS }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ count: 1 }), { status: 200, headers: JSON_HEADERS }));
    render(<AccountsPage model={model} csrf={csrf} reload={reload} />);
    fireEvent.click(screen.getByLabelText('19002 나 선택'));
    fireEvent.change(screen.getByLabelText('처리 사유'), { target: { value: '잠금 해제' } });
    fireEvent.click(screen.getByRole('button', { name: '비밀번호 초기화' }));
    expect(await screen.findByText('19002 나: 현재 재직 중인 활성 사원만 관리할 수 있습니다.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '비밀번호 초기화' }));
    await waitFor(() => expect(reload).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[1];
    expect(url).toBe('/admin/accounts/reset');
    expect(JSON.parse(String((init as RequestInit).body))).toMatchObject({ reason: '잠금 해제', employeeIds: ['e2'] });
  });

  it('asks to sign in again when the response is a login redirect or not JSON', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('<html>login</html>', { status: 200, headers: { 'Content-Type': 'text/html' } }));
    render(<AccountsPage model={model} csrf={csrf} reload={() => undefined} />);
    fireEvent.click(screen.getByLabelText('19001 가 선택'));
    fireEvent.change(screen.getByLabelText('처리 사유'), { target: { value: '개통' } });
    fireEvent.click(screen.getByRole('button', { name: '계정 생성' }));
    expect((await screen.findByRole('alert')).textContent).toContain('다시 실행');
  });

  it('shows the login prompt on 403', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('', { status: 403 }));
    render(<AccountsPage model={model} csrf={csrf} reload={() => undefined} />);
    fireEvent.click(screen.getByLabelText('19001 가 선택'));
    fireEvent.change(screen.getByLabelText('처리 사유'), { target: { value: '개통' } });
    fireEvent.click(screen.getByRole('button', { name: '계정 생성' }));
    expect((await screen.findByRole('alert')).textContent).toContain('로그인 상태와 권한');
  });
});
