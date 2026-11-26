// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { NotificationsPage } from './NotificationsPage';

const rows = [
  { id: 'n1', requestId: 'r1', kind: 'APPROVAL_REQUESTED' as const, requesterName: '홍길동', summary: '연차 12-01~12-02', createdAt: '2026-11-26 14:05', read: false },
  { id: 'n2', requestId: 'r2', kind: 'REJECTED' as const, requesterName: '홍길동', summary: '출퇴근 정정 11-20', createdAt: '2026-11-25 09:00', read: true },
];
afterEach(cleanup);

it('알림 목록, 신청 상세 링크, 모두 읽음 폼을 보인다', async () => {
  render(<NotificationsPage rows={rows} />);
  expect(screen.getByRole('heading', { name: '알림' })).toBeTruthy();
  expect((await screen.findByText('결재 요청'))).toBeTruthy();
  await waitFor(() => expect(screen.getByText('반려')).toBeTruthy());
  expect(screen.getByRole('link', { name: '연차 12-01~12-02' }).getAttribute('href')).toBe('/requests/r1');
  expect(screen.getByRole('button', { name: '모두 읽음' }).closest('form')?.getAttribute('action')).toBe('/me/notifications/read-all');
  expect(screen.getByText('안 읽음')).toBeTruthy();
});

it('알림이 없으면 안내만 보이고 모두 읽음은 숨긴다', async () => {
  render(<NotificationsPage rows={[]} />);
  expect(await screen.findByText('받은 알림이 없습니다.')).toBeTruthy();
  expect(screen.queryByRole('button', { name: '모두 읽음' })).toBeNull();
});

it('목록에는 모두 읽은 알림만 있어도 안 읽은 알림 수가 있으면 모두 읽음을 보인다', () => {
  render(<NotificationsPage rows={[rows[1]]} unreadCount={150} />);
  expect(screen.getByRole('button', { name: '모두 읽음' })).toBeTruthy();
});
