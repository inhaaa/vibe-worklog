// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { App } from './App';
import type { PageEnvelope } from './types';

const workspace: PageEnvelope = {
  page: 'org/access-list',
  csrf: { token: 'synthetic-csrf', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' },
  data: {
    workspacePrincipal: { roles: ['ATTENDANCE_ADMIN'] },
    workspaceEmployee: { employeeName: '합성담당자', companyName: '합성회사' },
    roleDirectory: { roles: [{ code: 'ATTENDANCE_ADMIN', label: '담당자', editable: true, memberCount: 0 }],
      role: 'ATTENDANCE_ADMIN', members: [], q: '', page: 0, hasNext: false, total: 0 },
  },
};

beforeEach(() => window.history.replaceState(null, '', '/admin/access'));
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/'); });

it('첫 링크로 업무 메뉴를 건너뛰고 추가 조회 없이 본문에 초점을 둔다', () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const { container } = render(<App initial={workspace} />);
  const skip = screen.getByRole('link', { name: '본문으로 건너뛰기' });
  const main = screen.getByRole('main', { name: '권한 관리' });
  expect(container.querySelector('a')).toBe(skip);
  expect(skip.getAttribute('href')).toBe(`#${main.id}`);
  expect(main.tabIndex).toBe(-1);
  skip.focus();
  fireEvent.click(skip);
  expect(document.activeElement).toBe(main);
  expect(fetcher).not.toHaveBeenCalled();
});
