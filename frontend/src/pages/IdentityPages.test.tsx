// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { App } from '../App';

const profile = {
  employeeNo: 'E001', employeeName: '김직원', companyName: '합성회사', email: 'employee@example.test',
  joinedOn: '2026-10-01', leftOn: null, organizationName: '개발팀', scheduleName: '고정 근무',
  approverName: '박승인', approverEligible: true,
  roles: ['EMPLOYEE', 'ATTENDANCE_ADMIN', 'COMPANY_REPRESENTATIVE', 'APPROVER'],
  asOf: '2026-10-26T15:00:00Z',
};
const organization = { id: 'o-1', code: 'DEV', name: '개발팀' };
const employee = { id: 'e-1', employeeNo: 'E001', employeeName: '김직원', organizationName: '개발팀' };
const attendance = {
  month: '2026-10', asOf: profile.asOf, organizationId: 'o-1', employeeId: 'e-1', q: '김 & 개발', page: 1, hasNext: true,
  organizations: [organization], employees: [employee], selectedEmployee: employee,
  days: [{ workDate: '2026-10-26', scheduledMillis: 28800000, actualWorkMillis: 28800000, leaveDays: 0, status: 'CONFIRMED', current: true }],
};
const requests = {
  year: 2026, asOf: profile.asOf, organizationId: 'o-1', status: 'APPROVED', page: 1, hasNext: true,
  organizations: [organization],
  requests: [{ id: 'r-1', employeeNo: 'E001', employeeName: '김직원', organizationName: '개발팀', kind: 'LEAVE_DAY', status: 'APPROVED', fromDate: '2026-10-26', toDate: '2026-10-26', quantityDays: 1, effectStatus: 'EFFECTIVE', submittedAt: '2026-10-25T15:00:00Z' }],
};

async function show(page: string, data: Record<string, unknown>) {
  const view = render(<App initial={{ page, data: {
    workspacePrincipal: { roles: ['EMPLOYEE', 'COMPANY_REPRESENTATIVE'] },
    workspaceEmployee: { employeeName: '김직원', companyName: '합성회사' }, ...data,
  }, csrf: { token: 'csrf', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' } }} />);
  await waitFor(() => {
    for (const sheet of view.container.querySelectorAll('.w-sheet'))
      expect(sheet.querySelector('[row-id], .ag-overlay-no-rows-center')).not.toBeNull();
  });
  return view;
}
afterEach(cleanup);

it('인사정보 7개 탭을 읽기 전용으로 표시하고 키보드로 이동한다', async () => {
  const view = await show('workspace/profile', { profile, personalDetails: { employeeId: 'e-1', version: 0, form: {}, entries: [], appointments: [] } });
  const tabs = view.getAllByRole('tab');
  expect(tabs.map(tab => tab.textContent)).toEqual(['기본사항', '발령내역', '가족사항', '학력사항', '자격사항', '어학사항', '주소·연락처']);
  expect(view.getByRole('tabpanel').textContent).not.toContain('주민등록번호');
  expect(view.container.textContent).not.toContain('개인 정보와 입사 정보를 한눈에 확인하세요.');
  expect(view.getByRole('tabpanel').textContent).toContain('인정경력');
  expect(view.getByRole('tabpanel').textContent).toContain('2026-10-01');
  fireEvent.keyDown(tabs[0], { key: 'ArrowRight' });
  expect(tabs[1].getAttribute('aria-selected')).toBe('true');
  expect(view.getByRole('tabpanel').textContent).toContain('등록된 발령내역이 없습니다.');
  fireEvent.click(tabs[2]);
  expect(view.getByRole('tabpanel').textContent).toContain('등록된 가족사항이 없습니다.');
  expect(view.container.querySelector('main form')).toBeNull();
});

describe('내 정보·권한 목록·회사 현황', () => {
  it('내 정보는 인사 탭만 보이고 계정·배정·역할 정보는 보이지 않는다', async () => {
    const view = await show('workspace/profile', { profile: { ...profile, organizationHeadName: '김조직장',
      approvalPreview: { source: 'ORGANIZATION_ROUTE', routeName: '팀 결재', approverName: null, eligible: false, message: '조직장 공석' } },
      personalDetails: { employeeId: 'e-1', version: 0, form: {}, entries: [], appointments: [] } });
    const main = view.container.querySelector('main')!;
    expect(view.getByRole('heading', { name: '내 정보' })).toBeTruthy();
    for (const text of ['계정과 업무 배정', 'employee@example.test', '김조직장', '박승인', '조회 기준', '공통 · 관리자', '고정 근무', '팀 결재', '조직장 공석'])
      expect(main.textContent).not.toContain(text);
    expect(view.getAllByRole('tab')).toHaveLength(7);
  });

  it('권한 목록의 검색·페이지 조건과 기존 단건 권한 관리 연결을 제공한다', async () => {
    const view = await show('org/access-list', { accessRows: [{ employeeId: 'e-1', employeeNo: 'E001', employeeName: '김직원', roles: profile.roles, active: true }, { employeeId: 'e-2', employeeNo: 'E002', employeeName: '박직원', roles: ['EMPLOYEE'], active: false }], q: '김 & 개발', page: 1, hasNext: true });
    expect((view.getByLabelText('사번·이름 검색') as HTMLInputElement).value).toBe('김 & 개발');
    expect(view.getByRole('link', { name: '김직원 권한 관리' }).getAttribute('href')).toBe('/admin/employees/e-1/access');
    const next = new URL(view.getByRole('link', { name: '다음' }).getAttribute('href')!, location.origin);
    expect(next.searchParams.get('q')).toBe('김 & 개발');
    expect(next.searchParams.get('page')).toBe('2');
    expect(view.container.querySelector('main')?.textContent).toContain('공통 · 관리자 · 대표 · 결재 담당 가능');
    expect(view.container.querySelector('main')?.textContent).toContain('비활성');
    expect(view.container.querySelector('main form[method="post"]')).toBeNull();
    expect(view.getByRole('grid', { name: '계정별 권한' })).toBeTruthy();
    expect(view.container.querySelector('[row-id="e-2"]')?.textContent).toContain('박직원');
  });

  it('권한 검색 결과가 없으면 빈 상태를 표시한다', async () => {
    const view = await show('org/access-list', { accessRows: [], q: '', page: 0, hasNext: false });
    expect(view.getByText('조회된 계정이 없습니다.')).toBeTruthy();
    expect(view.queryByRole('link', { name: '다음' })).toBeNull();
    expect(view.queryByRole('link', { name: '이전' })).toBeNull();
  });

  it('권한 시트 정렬·표시 필터 후에도 해당 사원의 관리 링크를 보존한다', async () => {
    const view = await show('org/access-list', {
      accessRows: [
        { employeeId: 'e-2', employeeNo: 'E002', employeeName: '박직원', roles: ['EMPLOYEE'], active: false },
        { employeeId: 'e-1', employeeNo: 'E001', employeeName: '김직원', roles: profile.roles, active: true },
      ], q: '', page: 0, hasNext: false,
    });
    const grid = view.getByRole('grid', { name: '계정별 권한' });
    fireEvent.click(within(grid).getByRole('columnheader', { name: /이름/ }).querySelector('.ag-header-cell-label')!);
    await waitFor(() => expect(grid.querySelector('[row-id]')?.getAttribute('row-id')).toBe('e-1'));
    fireEvent.change(view.getByLabelText('계정별 권한 현재 페이지 필터'), { target: { value: '비활성' } });
    await waitFor(() => expect(view.queryByRole('link', { name: '김직원 권한 관리' })).toBeNull());
    expect(view.getByRole('link', { name: '박직원 권한 관리' }).getAttribute('href')).toBe('/admin/employees/e-2/access');
    expect(grid.querySelector('[row-id="e-2"]')).not.toBeNull();
  });

  it('회사 근무현황에서 월·현재 조직·사원 조건과 페이지를 유지한다', async () => {
    const view = await show('company/attendance', { companyAttendance: attendance });
    expect((view.getByLabelText('기준 월 (서울)') as HTMLInputElement).value).toBe('2026-10');
    expect((view.getByLabelText('현재 조직 (서울 오늘)') as HTMLSelectElement).value).toBe('o-1');
    const selected = new URL(view.getByRole('link', { name: 'E001 · 김직원' }).getAttribute('href')!, location.origin);
    expect(selected.searchParams.get('employeeId')).toBe('e-1');
    expect(selected.searchParams.get('month')).toBe('2026-10');
    expect(selected.searchParams.get('q')).toBe('김 & 개발');
    expect(selected.searchParams.get('organizationId')).toBe('o-1');
    const next = new URL(view.getByRole('link', { name: '다음' }).getAttribute('href')!, location.origin);
    expect(next.searchParams.get('page')).toBe('2');
    expect(next.searchParams.get('month')).toBe('2026-10');
    expect(view.container.querySelector('main')?.textContent).toContain('8:00:00');
    expect(view.container.querySelector('main')?.textContent).not.toContain('밀리초');
    expect(view.container.querySelector('main form[method="post"]')).toBeNull();
    expect(view.container.querySelector('main a[href^="/admin/"]')).toBeNull();
    expect(view.getByRole('grid', { name: '회사 현황 대상 사원' })).toBeTruthy();
    expect(view.getByRole('grid', { name: '사원 일별 근무현황' })).toBeTruthy();
  });

  it('선택한 사원의 월만 변경하면 해당 사원으로 조회한다', async () => {
    const second = { ...employee, id: 'e-2', employeeNo: 'E002', employeeName: '박직원' };
    const view = await show('company/attendance', { companyAttendance: {
      ...attendance, q: '', employeeId: second.id, selectedEmployee: second, employees: [employee, second],
    } });
    fireEvent.change(view.getByLabelText('기준 월 (서울)'), { target: { value: '2026-11' } });
    const form = view.container.querySelector('form[action="/company/attendance"]') as HTMLFormElement;
    const submitted = new FormData(form);
    expect(submitted.get('month')).toBe('2026-11');
    expect(submitted.get('employeeId')).toBe('e-2');
    expect(submitted.get('organizationId')).toBe('o-1');
    expect(submitted.get('q')).toBe('');
  });

  it('조직을 변경하면 선택 사원을 비워 새 조직의 첫 사원으로 조회한다', async () => {
    const view = await show('company/attendance', { companyAttendance: {
      ...attendance, organizations: [organization, { id: 'o-2', code: 'HR', name: '인사팀' }],
    } });
    const form = view.container.querySelector('form[action="/company/attendance"]') as HTMLFormElement;
    expect(new FormData(form).get('employeeId')).toBe('e-1');
    fireEvent.change(view.getByLabelText('현재 조직 (서울 오늘)'), { target: { value: 'o-2' } });
    const submitted = new FormData(form);
    expect(submitted.get('employeeId')).toBe('');
    expect(submitted.get('organizationId')).toBe('o-2');
  });

  it('검색어를 변경하면 선택 사원을 비워 새 검색 결과의 첫 사원으로 조회한다', async () => {
    const view = await show('company/attendance', { companyAttendance: attendance });
    const form = view.container.querySelector('form[action="/company/attendance"]') as HTMLFormElement;
    expect(new FormData(form).get('employeeId')).toBe('e-1');
    fireEvent.change(view.getByLabelText('사번·이름 검색'), { target: { value: '박직원' } });
    const submitted = new FormData(form);
    expect(submitted.get('employeeId')).toBe('');
    expect(submitted.get('q')).toBe('박직원');
    expect((view.getByLabelText('사번·이름 검색') as HTMLInputElement).maxLength).toBe(100);
  });

  it('회사 일별 현황의 미산정·오래된 결과를 정상 0시간으로 표시하지 않는다', async () => {
    const view = await show('company/attendance', { companyAttendance: { ...attendance, days: [{ workDate: '2026-10-26', scheduledMillis: 28800000, actualWorkMillis: 0, leaveDays: 0, status: 'REVIEW_REQUIRED', current: false }] } });
    const row = view.container.querySelector('main [row-id="2026-10-26"]')!;
    expect(row.textContent).toContain('미산정·재집계 필요');
    expect(row.textContent?.match(/미확정/g)).toHaveLength(1);
    expect(row.querySelector('[col-id="scheduledMillis"]')?.textContent).toBe('-');
    expect(row.querySelector('[col-id="actualWorkMillis"]')?.textContent).toBe('-');
    expect(row.textContent).not.toContain('0:00:00');
  });

  it('회사 근무현황은 표시 문자열이 아닌 실제 근로시간 값으로 정렬한다', async () => {
    const view = await show('company/attendance', { companyAttendance: {
      ...attendance,
      days: [
        { ...attendance.days[0], workDate: '2026-10-27', actualWorkMillis: 36000000 },
        { ...attendance.days[0], workDate: '2026-10-26', actualWorkMillis: 28800000 },
      ],
    } });
    const grid = view.getByRole('grid', { name: '사원 일별 근무현황' });
    fireEvent.click(within(grid).getByRole('columnheader', { name: /실근로/ }).querySelector('.ag-header-cell-label')!);
    await waitFor(() => expect(grid.querySelector('[row-id]')?.getAttribute('row-id')).toBe('2026-10-26'));
    expect(grid.textContent).toContain('10:00:00');
    expect(grid.textContent).toContain('8:00:00');
  });

  it('선택할 사원과 일별 결과가 없을 때 조회 범위를 분명히 표시한다', async () => {
    const view = await show('company/attendance', { companyAttendance: { ...attendance, employees: [], selectedEmployee: null, employeeId: null, days: [], hasNext: false, page: 0 } });
    expect(view.getByText('조회할 사원이 없습니다.')).toBeTruthy();
    expect(view.getByText('사원을 선택하면 해당 월의 일별 현황을 볼 수 있습니다.')).toBeTruthy();
  });

  it('회사 신청은 연도·현재 조직·상태를 유지하고 조회연도 수량만 안전하게 표시한다', async () => {
    const view = await show('company/requests', { companyRequests: { ...requests, requests: requests.requests.map((request) => ({ ...request, reason: '민감 사유', email: 'private@example.test', clientIp: '192.0.2.1' })) } });
    expect((view.getByLabelText('조회연도 (서울)') as HTMLInputElement).value).toBe('2026');
    expect((view.getByLabelText('신청 상태') as HTMLSelectElement).value).toBe('APPROVED');
    const next = new URL(view.getByRole('link', { name: '다음' }).getAttribute('href')!, location.origin);
    for (const [name, value] of Object.entries({ year: '2026', organizationId: 'o-1', status: 'APPROVED', page: '2' }))
      expect(next.searchParams.get(name)).toBe(value);
    const main = view.container.querySelector('main')!;
    for (const text of ['E001', '김직원', '개발팀', '종일 연차', '승인', '유효', '조회연도 신청일량 (DAY)', '2026-10-26 00:00:00']) expect(main.textContent).toContain(text);
    for (const text of ['민감 사유', 'private@example.test', '192.0.2.1']) expect(main.textContent).not.toContain(text);
    expect(main.querySelector('a[href^="/requests/"],form[method="post"]')).toBeNull();
    expect(view.getByRole('grid', { name: '회사 신청 목록' })).toBeTruthy();
    expect(main.querySelector('[row-id="r-1"]')?.textContent).toContain('종일 연차');
  });

  it('회사 신청 목록의 빈 상태를 표시한다', async () => {
    const view = await show('company/requests', { companyRequests: { ...requests, requests: [], page: 0, hasNext: false } });
    expect(view.getByText('조회된 신청이 없습니다.')).toBeTruthy();
  });
});
