// @vitest-environment jsdom

import { fireEvent, render, within } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { PersonnelPage } from './PersonnelPage';

const csrf = { token: 'token', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };

it('searches for company employees and distinguishes matching names', async () => {
  const request = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response(JSON.stringify({
    page: 0, hasNext: false, rows: [
      { employeeId: 'one', employeeNo: '71001', employeeName: '합성 동명이인', organizationName: '운영팀', status: 'WORKING' },
      { employeeId: 'two', employeeNo: '72001', employeeName: '합성 동명이인', organizationName: '개발팀', status: 'LEFT' },
    ],
  }), { headers: { 'Content-Type': 'application/json' } }));
  try {
    const view = render(<PersonnelPage page="org/personnel-home" data={{}} csrf={csrf} reload={() => {}} />);
    fireEvent.change(view.getByRole('searchbox', { name: '사원 검색' }), { target: { value: '동명이인' } });
    fireEvent.click(view.getByRole('button', { name: '조회' }));
    const results = await view.findByRole('list', { name: '사원 검색 결과' });
    expect(within(results).getByRole('link', { name: /71001.*운영팀/ }).getAttribute('href')).toBe('/admin/employees/one/personnel');
    expect(within(results).getByRole('link', { name: /72001.*개발팀.*퇴사/ }).getAttribute('href')).toBe('/admin/employees/two/personnel');
    expect(request).toHaveBeenCalledTimes(1);
  } finally {
    request.mockRestore();
  }
});
