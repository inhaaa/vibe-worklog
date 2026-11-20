// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { App } from '../App';
import { AttendanceListBackLink, attendanceListReturn } from './CompanyAttendanceNavigation';

const profile = {
  employeeNo: 'E001', employeeName: '김직원', companyName: '합성회사', email: 'employee@example.test',
  joinedOn: '2026-10-01', leftOn: null, organizationName: '개발팀', scheduleName: '고정 근무',
  approverName: '박승인', approverEligible: true,
  roles: ['EMPLOYEE', 'ATTENDANCE_ADMIN', 'COMPANY_REPRESENTATIVE', 'APPROVER'],
  asOf: '2026-10-26T15:00:00Z',
};
const organization = { id: 'o-1', code: 'DEV', name: '개발팀' };
const employee = { id: 'e-1', employeeNo: 'E001', employeeName: '김직원', organizationName: '개발팀' };
const roleMember = { employeeId: 'e-1', employeeNo: 'E001', employeeName: '김직원', organizationName: '개발팀',
  version: 1, available: true, editable: true, restriction: null };
const unavailableMember = { ...roleMember, employeeId: 'e-2', employeeNo: 'E002', employeeName: '박직원',
  available: false, editable: false, restriction: '계정 비활성' };
const roleDirectory = { roles: [{ code: 'APPROVER', label: '결재 담당', editable: true, memberCount: 54 }],
  role: 'APPROVER', members: [roleMember, unavailableMember], actorEmployeeId: 'admin',
  q: '', page: 0, hasNext: false, total: 2 };
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
  it('직원이 기존 배정 기준을 펼쳤을 때 신청서별 결재선과 구별해 확인한다',async()=>{
    const view=await show('workspace/profile',{profile:{...profile,approvalPreview:{eligible:true,approverName:'이팀장',routeName:'팀 결재'}}});
    fireEvent.click(view.getByRole('button',{name:'기존 배정 기준'}));
    expect(view.getByText(/이팀장/)).toBeTruthy();
    expect(view.getByText(/신청서를 작성할 때/)).toBeTruthy();
  });
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

  it('선택 권한의 검색·페이지 조건과 기존 단건 권한 관리 연결을 제공한다', async () => {
    const view = await show('org/access-list', { roleDirectory: { ...roleDirectory, q: '김 & 개발', page: 1, hasNext: true } });
    expect((view.getByLabelText('사번·이름 검색') as HTMLInputElement).value).toBe('김 & 개발');
    expect(view.getByRole('link', { name: '김직원 권한 관리' }).getAttribute('href')).toBe('/admin/employees/e-1/access');
    const next = new URL(view.getByRole('link', { name: '다음' }).getAttribute('href')!, location.origin);
    expect(next.searchParams.get('q')).toBe('김 & 개발');
    expect(next.searchParams.get('page')).toBe('2');
    expect(next.searchParams.get('role')).toBe('APPROVER');
    expect(view.container.querySelector('main')?.textContent).toContain('계정 비활성');
    expect(view.container.querySelector('main form[method="post"]')).toBeNull();
    expect(view.getByRole('grid', { name: '권한 사원 시트' })).toBeTruthy();
    expect(view.container.querySelector('[row-id="e-2"]')?.textContent).toContain('박직원');
  });

  it('권한 검색 결과가 없으면 빈 상태를 표시한다', async () => {
    const view = await show('org/access-list', { roleDirectory: { ...roleDirectory, members: [], total: 0 } });
    expect(view.getByText('선택한 권한의 사원이 없습니다.')).toBeTruthy();
    expect(view.queryByRole('link', { name: '다음' })).toBeNull();
    expect(view.queryByRole('link', { name: '이전' })).toBeNull();
  });

  it('권한 시트 정렬·표시 필터 후에도 해당 사원의 관리 링크를 보존한다', async () => {
    const view = await show('org/access-list', {
      roleDirectory: { ...roleDirectory, members: [unavailableMember, roleMember] },
    });
    const grid = view.getByRole('grid', { name: '권한 사원 시트' });
    fireEvent.click(within(grid).getByRole('columnheader', { name: /이름/ }).querySelector('.ag-header-cell-label')!);
    await waitFor(() => expect(grid.querySelector('[row-id]')?.getAttribute('row-id')).toBe('e-1'));
    fireEvent.change(view.getByLabelText('권한 사원 시트 현재 페이지 필터'), { target: { value: '비활성' } });
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

  it('담당자에게만 회사 근무현황의 퇴근 누락 조회 진입점을 제공한다', async () => {
    const representative = await show('company/attendance', { companyAttendance: attendance });
    expect(representative.queryByRole('link', { name: '퇴근 누락' })).toBeNull();
    expect(representative.queryByRole('link', { name: '월말 확인 대상' })).toBeNull();
    representative.unmount();
    const admin = await show('company/attendance', { companyAttendance: attendance, canViewCheckout: true });
    expect(admin.getByRole('link', { name: '퇴근 누락' }).getAttribute('href')).toBe('/company/attendance?view=checkout');
    expect(admin.getByRole('link', { name: '월말 확인 대상' }).getAttribute('href')).toBe('/company/attendance?view=month-end');
  });

  it('정정이 반영된 날의 원천·유효 기록과 복귀 조건을 유지한다', async () => {
    const date = '2026-10-26';
    const view = await show('company/attendance', { canViewCheckout: true, checkoutOperations: {
      date, latestDate: '2026-10-27', organizationId: 'o-1', options: [organization], q: '김 & 개발',
      status: 'CORRECTED', page: 1, hasPrevious: true, hasNext: true, error: null,
      rows: [{ employeeId: 'e-1', employeeNo: 'E001', employeeName: '김직원', organizationName: '개발팀', workDate: date,
        scheduledEnd: '22:00', workMode: 'SELECTIVE', sourceIn: '2026-10-26T00:00:00Z', sourceOut: null, effectiveIn: '2026-10-26T00:00:00Z',
        effectiveOut: '2026-10-26T09:00:00Z', requestId: 'r-1', requestState: 'APPROVED', recordState: 'CORRECTED',
        resultState: 'RECALCULATION_REQUIRED', actualWorkMillis: null, calculatedAt: null, resultReason: null }],
    } });
    expect(view.getByLabelText('근무일').getAttribute('max')).toBe('2026-10-27');
    expect((view.getByLabelText('정정 상태') as HTMLSelectElement).value).toBe('CORRECTED');
    expect(view.getByText('재집계 필요')).toBeTruthy();
    const row = view.getByText('E001 · 김직원').closest('details')!;
    expect(row.textContent).toContain('미기록');
    expect(row.textContent).toContain('선택 가능 종료22:00');
    expect(row.textContent).not.toContain('예정 퇴근');
    expect(row.textContent).toContain('미확정');
    fireEvent.click(row.querySelector('summary')!);
    const requestLink = new URL(within(row).getByText('정정 상세').getAttribute('href')!, location.origin);
    expect(requestLink.pathname).toBe('/requests/r-1');
    const calculationLink = new URL(within(row).getByText('근태 집계·Excel').getAttribute('href')!, location.origin);
    expect(calculationLink.pathname).toBe('/admin/attendance');
    expect(calculationLink.searchParams.get('workDate')).toBe(date);
    const back = new URL(calculationLink.searchParams.get('returnTo')!, location.origin);
    expect(back.pathname).toBe('/company/attendance');
    expect(Object.fromEntries(back.searchParams)).toEqual({
      view: 'checkout', date, organizationId: 'o-1', q: '김 & 개발', status: 'CORRECTED', page: '1',
    });
    expect(requestLink.searchParams.get('returnTo')).toBe(calculationLink.searchParams.get('returnTo'));
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

  it('회사 일별 현황에서 재집계 전에도 승인 휴가와 취소 효력을 바로 표시한다', async () => {
    const stale = { ...attendance.days[0], actualWorkMillis: 0, status: 'REVIEW_REQUIRED', current: false };
    const view = await show('company/attendance', { companyAttendance: { ...attendance, days: [
      { ...stale, workDate: '2026-10-26', leaveDays: 0.5, paidLeaveMillis: 14400000 },
      { ...stale, workDate: '2026-10-27', leaveDays: 0, paidLeaveMillis: 0 },
      { ...stale, workDate: '2026-10-28', leaveDays: null, paidLeaveMillis: null },
    ] } });
    const row = view.container.querySelector('main [row-id="2026-10-26"]')!;
    expect(row.textContent).toContain('미산정·재집계 필요');
    expect(row.querySelector('[col-id="leaveDays"]')?.textContent).toBe('0.5');
    expect(view.container.querySelector('[row-id="2026-10-27"] [col-id="leaveDays"]')?.textContent).toBe('0');
    expect(view.container.querySelector('[row-id="2026-10-28"] [col-id="leaveDays"]')?.textContent).toBe('미확정');
    expect(row.querySelector('[col-id="paidLeaveMillis"]')?.textContent).toBe('4:00:00');
    expect(view.container.querySelector('[row-id="2026-10-27"] [col-id="paidLeaveMillis"]')?.textContent).toBe('0:00:00');
    expect(view.container.querySelector('[row-id="2026-10-28"] [col-id="paidLeaveMillis"]')?.textContent).toBe('미확정');
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
    for (const text of ['E001', '김직원', '개발팀', '종일 연차', '승인', '유효', '조회연도 신청일량 (일)', '2026-10-26 00:00:00']) expect(main.textContent).toContain(text);
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

it('월말 확인 대상은 하루의 여러 사유를 한 행에 모으고 상세 복귀와 다음 페이지에 조건을 보존한다', async () => {
  const model = { month: '2026-10', latestMonth: '2026-11', organizationId: organization.id,
    options: [organization], q: '김 & 개발', reason: 'UNCONFIRMED', page: 1, hasNext: true, hasPrevious: true,
    rows: [{ ...employee, employeeId: employee.id, workDate: '2026-10-26', resultId: 'result-id',
      pendingRequestId: 'correction-id', reasons: ['UNCONFIRMED','PENDING_CORRECTION'] }], error: null };
  const view = await show('company/attendance', { monthEndReview: model, canViewCheckout: true });
  const main = within(view.container.querySelector('main')!);
  expect(main.getByRole('link', {name:'월말 확인 대상'}).getAttribute('aria-current')).toBe('page');
  expect((main.getByLabelText('조회 월') as HTMLInputElement).value).toBe('2026-10');
  const row = main.getByRole('listitem', {name: /2026-10-26.*E001/});
  expect(within(row).getByText('근무 확인 필요')).toBeTruthy();
  expect(within(row).getByText('정정 결재 대기')).toBeTruthy();
  for (const [label, path] of [['정정 상세','/requests/correction-id'], ['계산 근거','/worktime/results/result-id'], ['근태 집계','/admin/attendance']]) {
    const href = new URL(within(row).getByRole('link', {name:label}).getAttribute('href')!, 'https://example.test');
    expect(href.pathname).toBe(path);
    const back = new URL(href.searchParams.get('returnTo')!, href.origin);
    expect(Object.fromEntries(back.searchParams)).toEqual({view:'month-end', month:model.month, organizationId:organization.id,
      q:model.q, reason:model.reason, page:'1'});
  }
  expect(main.getByRole('link', {name:'다음'}).getAttribute('href')).toContain('page=2');
  view.unmount();
  const empty = await show('company/attendance', { monthEndReview: {...model, rows:[], page:0, hasNext:false, hasPrevious:false} });
  expect(empty.getByText('선택한 조건에 해당하는 월말 확인 대상이 없습니다.')).toBeTruthy();
});

it('상세 복귀 링크는 허용된 월말 조회 조건만 사용한다', () => {
  const back = '/company/attendance?view=month-end&month=2026-10&q=%EA%B9%80+%26+%EC%9D%B4&reason=STALE&page=1';
  const view = render(<AttendanceListBackLink href={back} />);
  expect(view.getByRole('link', { name: '월말 확인 목록으로' }).getAttribute('href')).toBe(back);
  for (const invalid of ['//example.test', back + '&page=2', back + '&extra=true', back + '#outside',
    back.replace('2026-10','2026-13'), back.replace('STALE','UNKNOWN')]) {
    expect(attendanceListReturn(invalid)).toBeNull();
  }
});
