// @vitest-environment jsdom
import React from 'react';
import { cleanup, render, within } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { RoleManagementPage } from './RoleManagementPage';

const model = {
  roles: [
    { code: 'EMPLOYEE', label: '공통', editable: false, memberCount: 100 },
    { code: 'ATTENDANCE_ADMIN', label: '담당자', editable: true, memberCount: 72 },
    { code: 'COMPANY_REPRESENTATIVE', label: '대표', editable: true, memberCount: 2 },
    { code: 'APPROVER', label: '결재 담당', editable: true, memberCount: 10 },
  ],
  role: 'ATTENDANCE_ADMIN', actorEmployeeId: 'actor-1', q: '김 & 개발', page: 1, hasNext: true, total: 54, members: [],
};

function show() {
  return render(<RoleManagementPage model={model}><div>사원 시트</div></RoleManagementPage>);
}

afterEach(cleanup);

it('선택한 권한과 부여 인원을 보여주고 검색과 페이지에 권한을 유지한다', () => {
  const view = show();
  const roles = within(view.getByRole('navigation', { name: '권한 목록' }));
  const current = roles.getByRole('link', { name: /담당자.*72/ });
  expect(current.getAttribute('aria-current')).toBe('page');
  const other = new URL(roles.getByRole('link', { name: /결재 담당.*10/ }).getAttribute('href')!, location.origin);
  expect(other.searchParams.get('role')).toBe('APPROVER');
  expect(other.searchParams.get('page')).toBeNull();
  const field = view.getByRole('searchbox', { name: '사번·이름 검색' }) as HTMLInputElement;
  expect(field.value).toBe('김 & 개발');
  expect(new FormData(field.form!).get('role')).toBe('ATTENDANCE_ADMIN');
  expect(view.getByText('검색 결과 54명')).toBeTruthy();
  const next = new URL(view.getByRole('link', { name: '다음' }).getAttribute('href')!, location.origin);
  expect(next.searchParams.get('role')).toBe('ATTENDANCE_ADMIN');
  expect(next.searchParams.get('q')).toBe('김 & 개발');
  expect(next.searchParams.get('page')).toBe('2');
});
