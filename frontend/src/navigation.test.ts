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
  it('5개 대표메뉴와 승인된 26개 실제 경로를 3단계로 제공한다', () => {
    expect(getNavigation(allRoles).map(group => group.label)).toEqual(['내 업무', '결재', '인사·조직', '근태 운영', '시스템 설정']);
    expect(links(allRoles).map(item => item.href)).toEqual([
      '/me/profile', '/me/attendance', '/me/schedule', '/me/worktime', '/me/requests',
      '/approvals', '/approvals/completed',
      '/admin/employees', '/admin/organizations', '/admin/organization-heads', '/company/organizations', '/admin/access',
      '/company/attendance', '/company/requests', '/admin/attendance', '/admin/requests', '/admin/leave-balances', '/admin/schedules', '/admin/calendar',
      '/admin/approval-routes', '/admin/approval-route-assignments', '/admin/attendance-types', '/admin/common-codes', '/admin/work-policies', '/admin/networks', '/admin/audit',
    ]);
    expect(new Set(links(allRoles).map(item => item.id)).size).toBe(26);
    expect(links(allRoles).some(item => item.href.includes('?') || item.href === '/me/today')).toBe(false);
  });

  it('일반 사원은 본인 업무만 보고 허용 자식이 없는 부모는 제거한다', () => {
    expect(getNavigation(['EMPLOYEE']).map(group => group.label)).toEqual(['내 업무']);
    expect(links(['EMPLOYEE'])).toHaveLength(5);
    expect(getNavigation([])).toEqual([]);
  });

  it('대표와 결재 담당의 독립 역할을 기존 서버 권한대로 유지한다', () => {
    expect(links(['COMPANY_REPRESENTATIVE']).map(item => item.href)).toEqual(['/company/organizations', '/company/attendance', '/company/requests']);
    expect(links(['APPROVER']).map(item => item.href)).toEqual(['/approvals', '/approvals/completed']);
  });

  it.each([
    ['org/personnel', '/admin/employees/e-1/personnel', {}, '/admin/employees', '인사정보 등록'],
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
    expect(getNavigationContext('request/detail', '/requests/r-1', { admin: true })?.href).toBe('/admin/requests');
    expect(getNavigationContext('request/detail', '/requests/r-1', { navigationFrom: 'https://evil.test/', admin: true })?.href).toBe('/admin/requests');
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
