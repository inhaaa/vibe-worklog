export interface NavigationItem {
  id: string;
  label: string;
  href: string;
  roles: readonly string[];
  pages: readonly string[];
}
export interface NavigationSection { id: string; label: string; children: NavigationItem[] }
export interface NavigationGroup { id: string; label: string; children: NavigationSection[] }
export interface NavigationContext {
  groupId: string;
  sectionId: string;
  itemId: string;
  href: string;
  breadcrumbs: { label: string; href?: string }[];
  detailTitle?: string;
}
const employee = ['EMPLOYEE'];
const admin = ['ATTENDANCE_ADMIN'];
const company = ['ATTENDANCE_ADMIN', 'COMPANY_REPRESENTATIVE'];
const item = (id: string, label: string, href: string, roles: string[], ...pages: string[]): NavigationItem => ({ id, label, href, roles, pages });
const navigation: NavigationGroup[] = [
  { id: 'mine', label: '내 업무', children: [
    { id: 'my-personnel', label: '인사정보', children: [item('profile', '내 정보', '/me/profile', employee, 'workspace/profile')] },
    { id: 'my-attendance', label: '근태관리', children: [
      item('attendance', '출퇴근 기록', '/me/attendance', employee, 'clock/history', 'clock/day', 'today'),
      item('schedule', '근무 일정', '/me/schedule', employee, 'workspace/schedule'),
      item('worktime', '근로시간', '/me/worktime', employee, 'worktime/month'),
    ] },
    { id: 'my-requests', label: '휴가·신청', children: [item('requests', '내 근태현황·신청', '/me/requests', employee, 'request/mine', 'request/correction')] },
  ] },
  { id: 'approvals', label: '결재', children: [
    { id: 'approval-inbox', label: '결재함', children: [
      item('pending-approvals', '결재 대기', '/approvals', ['APPROVER'], 'request/list'),
      item('completed-approvals', '내가 처리한 결재', '/approvals/completed', ['APPROVER'], 'request/list'),
    ] },
  ] },
  { id: 'people', label: '인사·조직', children: [
    { id: 'organization', label: '조직·사원', children: [
      item('employees', '사원 관리', '/admin/employees', admin, 'org/employees', 'org/employee', 'org/personnel'),
      item('organizations', '조직 관리', '/admin/organizations', admin, 'org/organizations', 'org/organization'),
      item('organization-heads', '조직장 배정', '/admin/organization-heads', admin, 'org/organization-heads'),
      item('organization-chart', '조직도', '/company/organizations', company, 'company/organizations'),
    ] },
    { id: 'access', label: '계정·권한', children: [item('account-access', '권한 관리', '/admin/access', admin, 'org/access-list', 'org/access')] },
  ] },
  { id: 'operations', label: '근태 운영', children: [
    { id: 'company-status', label: '회사 현황', children: [
      item('company-attendance', '회사 근무현황', '/company/attendance', company, 'company/attendance'),
      item('company-requests', '회사 신청 현황', '/company/requests', company, 'company/requests'),
      item('attendance-report', '근태 집계·Excel', '/admin/attendance', admin, 'worktime/month'),
    ] },
    { id: 'leave-operations', label: '휴가 운영', children: [
      item('all-requests', '전체 신청', '/admin/requests', admin, 'request/list'),
      item('leave-balances', '연차 관리', '/admin/leave-balances', admin, 'request/balances', 'request/ledger', 'request/attributions'),
    ] },
    { id: 'work-operations', label: '근무 운영', children: [
      item('schedules', '근무 설정', '/admin/schedules', admin, 'schedule/list', 'schedule/form', 'schedule/detail', 'schedule/assignment'),
      item('calendar', '근무일·휴일', '/admin/calendar', admin, 'clock/calendar'),
    ] },
  ] },
  { id: 'settings', label: '시스템 설정', children: [
    { id: 'approval-settings', label: '결재 설정', children: [
      item('approval-routes', '결재선 관리', '/admin/approval-routes', admin, 'org/approval-routes'),
      item('approval-assignments', '결재선 배정', '/admin/approval-route-assignments', admin, 'org/approval-route-assignments'),
    ] },
    { id: 'work-standards', label: '업무 기준', children: [
      item('attendance-types', '근태코드 관리', '/admin/attendance-types', admin, 'org/attendance-types'),
      item('common-codes', '공통코드 관리', '/admin/common-codes', admin, 'org/common-codes'),
      item('work-policies', '계산 정책', '/admin/work-policies', admin, 'worktime/policies'),
    ] },
    { id: 'security', label: '보안·이력', children: [
      item('networks', '허용 네트워크', '/admin/networks', admin, 'clock/networks'),
      item('audit', '변경 이력', '/admin/audit', admin, 'worktime/audit', 'worktime/audit-detail'),
    ] },
  ] },
];

const workspaceRoles = [
  { value: 'EMPLOYEE', label: '직원' }, { value: 'ATTENDANCE_ADMIN', label: '담당자' },
  { value: 'APPROVER', label: '승인자' }, { value: 'COMPANY_REPRESENTATIVE', label: '대표' },
];
export const getWorkspaceRoles = (roles: readonly string[]) => workspaceRoles.filter(role => roles.includes(role.value));
export const selectWorkspaceRole = (roles: readonly string[], selected?: string) =>
  getWorkspaceRoles(roles).find(role => role.value === selected)?.value ?? getWorkspaceRoles(roles)[0]?.value;
export const getWorkspaceMenuRoles = (roles: readonly string[], selected?: string) =>
  roles.filter(role => role === 'EMPLOYEE' || role === selectWorkspaceRole(roles, selected));

export function getNavigation(roles: readonly string[], selectedRole?: string): NavigationGroup[] {
  const visibleRoles = selectedRole === undefined ? roles : getWorkspaceMenuRoles(roles, selectedRole);
  return navigation.map(group => ({ ...group, children: group.children
    .map(section => ({ ...section, children: section.children.filter(child => child.roles.some(role => visibleRoles.includes(role))) }))
    .filter(section => section.children.length > 0) }))
    .filter(group => group.children.length > 0);
}

const detailTitles: Record<string, string> = {
  'org/employee': '사원 상세', 'org/personnel': '인사정보 등록', 'org/organization': '조직 상세',
  'org/access': '계정·권한', 'schedule/form': '근무 템플릿 등록', 'schedule/detail': '근무 템플릿',
  'schedule/assignment': '근무 배정', 'request/detail': '신청 상세', 'request/correction': '기록 정정 신청',
  'request/ledger': '연차 원장', 'request/attributions': '기초 묶음 귀속연도',
  'worktime/result': '계산 근거', 'worktime/audit-detail': '감사 상세',
};
const requestSources = ['/me/requests', '/approvals', '/approvals/completed', '/admin/requests', '/company/requests'];

export function getNavigationContext(page: string, pathname: string, data: Record<string, unknown> = {}): NavigationContext | undefined {
  let href: string | undefined;
  const roles = (data.workspacePrincipal as { roles?: string[] } | undefined)?.roles;
  const source = typeof data.navigationFrom === 'string' && (!roles || getNavigation(roles).some(group => group.children.some(section => section.children.some(leaf => leaf.href === data.navigationFrom))))
    ? data.navigationFrom : undefined;
  if (page === 'request/detail') {
    const request = data.request as { targetEmployeeId?: string; status?: string } | undefined;
    const self = data.employeeHeader as { employeeId?: string } | undefined;
    const mine = data.ownRequest === true || data.canWithdraw === true || data.canCancel === true || (self?.employeeId != null && self.employeeId === request?.targetEmployeeId);
    href = source && requestSources.includes(source)
      ? source : mine ? '/me/requests' : data.canDecide === true ? '/approvals' : data.admin === true ? '/admin/requests'
        : request?.status && request.status !== 'PENDING' ? '/approvals/completed' : '/approvals';
  } else if (page === 'request/list') {
    href = pathname.startsWith('/admin/requests') ? '/admin/requests'
      : pathname.startsWith('/approvals/completed') || data.completed === true ? '/approvals/completed' : '/approvals';
  } else if (page === 'request/correction' && (pathname.startsWith('/admin/') || data.exceptional === true)) {
    href = '/admin/requests';
  } else if (page === 'worktime/result') {
    href = source && ['/me/worktime', '/admin/attendance', '/company/attendance'].includes(source)
      ? source : data.admin === true ? '/admin/attendance' : '/me/worktime';
  } else if (page === 'worktime/month') {
    href = pathname.startsWith('/admin/attendance') || data.admin === true ? '/admin/attendance' : '/me/worktime';
  }
  for (const group of navigation) for (const section of group.children) for (const leaf of section.children) {
    if (href ? leaf.href !== href : !leaf.pages.includes(page)) continue;
    const detailTitle = detailTitles[page];
    return { groupId: group.id, sectionId: section.id, itemId: leaf.id, href: leaf.href, detailTitle,
      breadcrumbs: [{ label: group.label }, { label: section.label }, { label: leaf.label, href: detailTitle ? leaf.href : undefined }, ...(detailTitle ? [{ label: detailTitle }] : [])] };
  }
  return undefined;
}
