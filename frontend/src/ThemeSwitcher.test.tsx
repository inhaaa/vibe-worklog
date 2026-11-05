// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { App } from './App';
import type { PageEnvelope } from './types';

const csrf = { token: 'synthetic-csrf', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const workspace: PageEnvelope = {
  page: 'org/access-list', csrf,
  data: {
    workspacePrincipal: { roles: ['EMPLOYEE', 'ATTENDANCE_ADMIN'] },
    workspaceEmployee: { employeeName: '합성담당자', companyName: '합성회사' },
    accessRows: [], q: '', page: 0, hasNext: false,
  },
};
const login: PageEnvelope = { page: 'login', data: { localDemo: false }, csrf };

beforeEach(() => {
  window.history.replaceState(null, '', '/admin/access');
  localStorage.clear();
  delete document.documentElement.dataset.theme;
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  localStorage.clear();
  delete document.documentElement.dataset.theme;
  window.history.replaceState(null, '', '/');
});

it('차콜을 기본으로 표시하고 테마 변경은 업무 권한과 선택 메뉴를 보존한다', () => {
  render(<App initial={workspace} />);
  const theme = screen.getByRole('combobox', { name: '화면 테마' }) as HTMLSelectElement;
  const role = screen.getByRole('combobox', { name: '업무 권한' }) as HTMLSelectElement;
  expect(theme.value).toBe('charcoal');
  expect(document.documentElement.dataset.theme).toBe('charcoal');
  expect(Array.from(theme.options, option => option.textContent)).toEqual(['차콜', '블루']);
  const selectedRole = role.value;
  fireEvent.change(theme, { target: { value: 'blue' } });
  expect(document.documentElement.dataset.theme).toBe('blue');
  expect(localStorage.getItem('worklog-theme')).toBe('blue');
  expect(role.value).toBe(selectedRole);
  expect(screen.getByRole('link', { name: '권한 관리' }).getAttribute('aria-current')).toBe('page');
});

it('업무 화면에서 선택한 테마를 공개 로그인 화면에서도 이어서 사용한다', () => {
  const view = render(<App initial={workspace} />);
  fireEvent.change(screen.getByRole('combobox', { name: '화면 테마' }), { target: { value: 'blue' } });
  view.unmount();
  window.history.replaceState(null, '', '/login');
  render(<App initial={login} />);
  expect((screen.getByRole('combobox', { name: '화면 테마' }) as HTMLSelectElement).value).toBe('blue');
  expect(document.documentElement.dataset.theme).toBe('blue');
  expect(screen.getByRole('button', { name: '로그인' })).toBeTruthy();
});

it('알 수 없는 저장 값은 차콜 기본값으로 복구한다', () => {
  localStorage.setItem('worklog-theme', 'unknown');
  render(<App initial={login} />);
  expect((screen.getByRole('combobox', { name: '화면 테마' }) as HTMLSelectElement).value).toBe('charcoal');
  expect(document.documentElement.dataset.theme).toBe('charcoal');
});

it('브라우저 저장소 읽기와 쓰기가 차단되어도 테마를 변경할 수 있다', () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
  render(<App initial={workspace} />);
  const theme = screen.getByRole('combobox', { name: '화면 테마' }) as HTMLSelectElement;
  expect(theme.value).toBe('charcoal');
  fireEvent.change(theme, { target: { value: 'blue' } });
  expect(theme.value).toBe('blue');
  expect(document.documentElement.dataset.theme).toBe('blue');
});
