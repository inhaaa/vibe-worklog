import { describe, expect, it } from 'vitest';
import { getNavigation, getNavigationContext } from './navigation';

const allRoles = ['EMPLOYEE', 'APPROVER', 'ATTENDANCE_ADMIN', 'COMPANY_REPRESENTATIVE'];
const links = (roles: string[]) => getNavigation(roles).flatMap(group => group.children.flatMap(section => section.children));

describe('업무 메뉴 계약', () => {
  it('역할 선택은 실제 보유한 역할만 인정하며 다른 권한을 추가하지 않는다', () => {
    expect(getNavigation(allRoles, 'APPROVER').map(group => group.label)).toEqual(['내 업무', '결재']);
    expect(getNavigation(['EMPLOYEE'], 'ATTENDANCE_ADMIN').map(group => group.label)).toEqual(['내 업무']);
    expect(getNavigation([], 'ATTENDANCE_ADMIN')).toEqual([]);
  });
  it('업무별 메뉴와 승인된 실제 경로를 3단계로 제공한다', () => {
    expect(getNavigation(allRoles).map(group => group.label)).toEqual(['내 업무', '결재', '인사관리', '조직관리', '근태 운영', '휴가관리', '시스템 설정']);
    expect(links(allRoles).map(item => item.href)).toEqual([
      '/me/profile', '/me/attendance', '/me/schedule', '/me/worktime', '/me/requests/correction', '/me/requests/new?kind=LEAVE_DAY', '/me/requests',
      '/approvals', '/approvals/completed',
      '/admin/employees', '/admin/employees/bulk-edit', '/admin/employees/onboarding',
      '/admin/organizations', '/admin/organization-heads?chart=true', '/company/organizations',
      '/company/attendance', '/admin/requests?scope=CORRECTION', '/company/requests?scope=CORRECTION', '/admin/attendance',
      '/admin/schedules', '/admin/calendar', '/admin/attendance-types', '/admin/work-policies',
      '/admin/requests?scope=LEAVE', '/company/requests?scope=LEAVE', '/admin/leave-balances',
      '/admin/access', '/admin/common-codes', '/admin/request-forms', '/admin/approval-routes', '/admin/approval-route-assignments', '/admin/networks', '/admin/audit',
    ]);
    expect(new Set(links(allRoles).map(item => item.id)).size).toBe(33);
    expect(getNavigationContext('org/request-forms', '/admin/request-forms')?.breadcrumbs.at(-1)?.label).toBe('신청서코드 관리');
    expect(getNavigationContext('org/personnel-home', '/admin/employees')?.href).toBe('/admin/employees');
    expect(getNavigationContext('org/employees', '/admin/employees/bulk-edit')?.href).toBe('/admin/employees/bulk-edit');
    expect(getNavigationContext('org/onboarding', '/admin/employees/onboarding')?.href).toBe('/admin/employees/onboarding');
    expect(getNavigationContext('org/onboarding', '/admin/employees/onboarding')?.breadcrumbs.at(-1)?.label).toBe('입사 예정 관리');
    expect(links(allRoles).some(item => item.href === '/me/today')).toBe(false);
  });

  it('일반 사원은 본인 업무만 보고 허용 자식이 없는 부모는 제거한다', () => {
    expect(getNavigation(['EMPLOYEE']).map(group => group.label)).toEqual(['내 업무']);
    expect(links(['EMPLOYEE'])).toHaveLength(7);
    expect(getNavigation([])).toEqual([]);
  });

  it('대표와 결재 담당의 독립 역할을 기존 서버 권한대로 유지한다', () => {
    expect(links(['COMPANY_REPRESENTATIVE']).map(item => item.href)).toEqual(['/company/organizations', '/company/attendance', '/company/requests?scope=CORRECTION', '/company/requests?scope=LEAVE']);
    expect(links(['APPROVER']).map(item => item.href)).toEqual(['/approvals', '/approvals/completed']);
  });

  it.each([
    ['org/personnel', '/admin/employees/e-1/personnel', {}, '/admin/employees', '사원 인사정보'],
    ['org/access', '/admin/employees/e-1/access', {}, '/admin/access', '계정·권한'],
    ['org/organization', '/admin/organizations/o-1', {}, '/admin/organizations', '조직 상세'],
    ['schedule/detail', '/admin/schedules/s-1', {}, '/admin/schedules', '근무 템플릿'],
    ['schedule/assignment', '/admin/employees/e-1/schedule', {}, '/admin/schedules', '근무 배정'],
    ['request/ledger', '/admin/leave-balances/buckets/b-1', {}, '/admin/leave-balances', '연차 원장'],
    ['request/attributions', '/admin/leave-balances/attributions', {}, '/admin/leave-balances', '기초 묶음 귀속연도'],
    ['worktime/audit-detail', '/admin/audit/a-1', {}, '/admin/audit', '감사 상세'],
    ['worktime/result', '/worktime/results/r-1', { admin: true }, '/admin/attendance', '계산 근거'],
  ])('상세 %s의 조상과 상세 제목을 찾는다', (page, pathname, data, href, detailTitle) => {
    const context = getNavigationContext(page, pathname, data);
    expect(context?.href).toBe(href);
    expect(context?.breadcrumbs).toHaveLength(4);
    expect(context?.breadcrumbs.at(-1)?.label).toBe(detailTitle);
  });

  it('공유 신청 상세는 허용된 진입 문맥을 유지하고 새로고침 때 열람 관계로 결정한다', () => {
    expect(getNavigationContext('request/detail', '/requests/r-1', { navigationFrom: '/approvals/completed', canWithdraw: true })?.href).toBe('/approvals/completed');
    expect(getNavigationContext('request/detail', '/requests/r-1', { canWithdraw: true })?.href).toBe('/me/requests');
    expect(getNavigationContext('request/detail', '/requests/r-1', { ownRequest: true, request: { status: 'REJECTED' } })?.href).toBe('/me/requests');
    expect(getNavigationContext('request/detail', '/requests/r-1', { employeeHeader: { employeeId: 'e-1' }, request: { targetEmployeeId: 'e-1' }, admin: true })?.href).toBe('/me/requests');
    expect(getNavigationContext('request/detail', '/requests/r-1', { canDecide: true })?.href).toBe('/approvals');
    expect(getNavigationContext('request/detail', '/requests/r-1', { admin: true })?.href).toBe('/admin/requests?scope=LEAVE');
    expect(getNavigationContext('request/detail', '/requests/r-1', { navigationFrom: 'https://evil.test/', admin: true })?.href).toBe('/admin/requests?scope=LEAVE');
  });

  it('더 이상 허용되지 않는 진입 메뉴는 사용하지 않는다', () => {
    expect(getNavigationContext('request/detail', '/requests/r-1', {
      navigationFrom: '/admin/requests', ownRequest: true, workspacePrincipal: { roles: ['EMPLOYEE'] },
    })?.href).toBe('/me/requests');
  });

  it('같은 page ID도 본인/관리자와 대기/완료 목록을 구분한다', () => {
    expect(getNavigationContext('worktime/month', '/me/worktime', { admin: false })?.href).toBe('/me/worktime');
    expect(getNavigationContext('worktime/month', '/admin/attendance', { admin: true })?.href).toBe('/admin/attendance');
    expect(getNavigationContext('request/list', '/approvals/completed', { completed: true })?.href).toBe('/approvals/completed');
    expect(getNavigationContext('request/list', '/admin/requests')?.href).toBe('/admin/requests');
    expect(getNavigationContext('error', '/unknown')).toBeUndefined();
  });
});

const hrefs = (roles: string[]) => getNavigation(roles)
  .flatMap(group => group.children.flatMap(section => section.children.map(item => item.href)));

describe('업무별 메뉴', () => {
  it('직원은 본인 기록에서 정정과 휴가 신청을 각각 찾을 수 있다', () => {
    expect(hrefs(['EMPLOYEE'])).toEqual(expect.arrayContaining([
      '/me/attendance', '/me/requests/correction', '/me/requests/new?kind=LEAVE_DAY', '/me/requests',
    ]));
    expect(getNavigationContext('clock/period', '/me/attendance?view=period')?.itemId).toBe('attendance');
    expect(getNavigationContext('request/mine', '/me/requests/new?kind=LEAVE_DAY')?.itemId).toBe('new-leave');
  });

  it('담당자의 휴가·정정 처리와 대표의 읽기 현황을 분리한다', () => {
    const admin = hrefs(['ATTENDANCE_ADMIN']);
    expect(admin).toEqual(expect.arrayContaining([
      '/admin/employees/bulk-edit', '/admin/requests?scope=LEAVE', '/admin/requests?scope=CORRECTION', '/admin/access',
    ]));
    const representative = hrefs(['COMPANY_REPRESENTATIVE']);
    expect(representative).toEqual(expect.arrayContaining([
      '/company/attendance', '/company/requests?scope=LEAVE', '/company/requests?scope=CORRECTION',
    ]));
    expect(representative.some(link => link.startsWith('/admin/'))).toBe(false);
    expect(hrefs(['APPROVER'])).toEqual(['/approvals', '/approvals/completed']);
  });

  it('관리 목록과 상세의 업무 범위를 유지한다', () => {
    const leave = '/admin/requests?scope=LEAVE';
    const correction = '/admin/requests?scope=CORRECTION';
    expect(getNavigationContext('request/list', leave)?.href).toBe(leave);
    expect(getNavigationContext('request/list', correction)?.href).toBe(correction);
    expect(getNavigationContext('request/detail', '/requests/example', {
      navigationFrom: leave, workspacePrincipal: { roles: ['ATTENDANCE_ADMIN'] }, admin: true,
      request: { kind: 'CANCEL_LEAVE' },
    })?.href).toBe(leave);
    expect(getNavigationContext('request/detail', '/requests/example', {
      navigationFrom: leave, workspacePrincipal: { roles: ['EMPLOYEE', 'APPROVER'] }, canDecide: true,
    })?.href).toBe('/approvals');
  });
});
