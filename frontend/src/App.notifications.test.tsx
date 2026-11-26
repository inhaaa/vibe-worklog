// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import type { PageEnvelope } from './types';

const csrf = { token: 't', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const scope = { workspaceEmployee: { employeeName: '합성', companyName: '합성회사' }, workspacePrincipal: { roles: ['EMPLOYEE', 'APPROVER'] } };
const show = (count: number) => render(<App initial={{ page: 'workspace/home', csrf, data: {
  workspaceEmployee: { employeeName: '합성', companyName: '합성회사' },
  workspacePrincipal: { roles: ['EMPLOYEE'] }, workspaceNotifications: count } }} />);
afterEach(cleanup);

it('상단 종에 안 읽은 알림 수를 보인다', () => {
  show(3);
  expect(screen.getByRole('link', { name: '알림 3건 안 읽음' }).getAttribute('href')).toBe('/me/notifications');
  expect(screen.getByText('3')).toBeTruthy();
});
it('99건 넘으면 99+, 모두 읽었으면 배지를 숨긴다', () => {
  show(100);
  expect(screen.getByText('99+')).toBeTruthy();
  expect(screen.getByRole('link', { name: '알림 99건 이상 안 읽음' })).toBeTruthy();
  cleanup();
  show(0);
  expect(screen.getByRole('link', { name: '알림' }).textContent).toBe('');
});

describe('알림 화면에서 연 결재 상세', () => {
  const row = (read: boolean) => ({ id: 'n1', requestId: 'r1', kind: 'APPROVAL_REQUESTED', requesterName: '합성 직원',
    summary: '출퇴근 정정 11-26', createdAt: '2026-11-26 16:43', read });
  const list = (unread: number): PageEnvelope => ({ page: 'notification/list', csrf, data: { ...scope, workspaceNotifications: unread,
    notifications: [row(unread === 0)] } });
  const detail: PageEnvelope = { page: 'request/detail', csrf, data: { ...scope, workspaceNotifications: 0, admin: false,
    pageCommand: 'command-1', candidates: [],
    request: { id: 'r1', targetEmployeeName: '합성 직원', version: 0, kind: 'ATTENDANCE_CORRECTION', status: 'PENDING',
      effectStatus: 'NONE', fromDate: '2026-11-26', toDate: '2026-11-26', approverName: '합성 결재자', reason: '합성 사유',
      submittedAt: '2026-11-26T07:43:00Z' },
    detail: { days: [], history: [], correction: null, attendancePolicy: null } } };
  const response = (model: PageEnvelope, href: string) => {
    const result = new Response(JSON.stringify(model), { status: 200, headers: { 'Content-Type': 'application/vnd.worklog.page+json' } });
    Object.defineProperty(result, 'url', { value: new URL(href, window.location.origin).href });
    return result;
  };
  beforeEach(() => {
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.open = true; } });
    Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.open = false; } });
  });
  afterEach(() => { vi.unstubAllGlobals(); history.replaceState(null, '', '/'); });

  it('상세를 열면 종 건수를 바로 줄이고, 닫으면 알림 목록을 다시 불러온다', async () => {
    history.replaceState(null, '', '/me/notifications');
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(response(detail, '/requests/r1'))
      .mockResolvedValueOnce(response(list(0), '/me/notifications'));
    vi.stubGlobal('fetch', fetcher);
    render(<App initial={list(1)} />);
    const bell = () => document.querySelector('.t-notification-bell')?.getAttribute('aria-label');
    expect(bell()).toBe('알림 1건 안 읽음');
    fireEvent.click(await screen.findByRole('link', { name: '출퇴근 정정 11-26' }));
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(bell()).toBe('알림'));

    fireEvent.click(within(dialog).getAllByRole('button', { name: '닫기' })[0]);
    await waitFor(() => expect(fetcher.mock.calls.at(-1)?.[0]).toBe(new URL('/me/notifications', location.origin).href));
    await waitFor(() => expect(screen.queryByText('안 읽음')).toBeNull());
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
