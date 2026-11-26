// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { App } from './App';

const csrf = { token: 't', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
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
