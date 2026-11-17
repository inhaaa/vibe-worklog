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
      item('attendance', '출퇴근 기록', '/me/attendance', employee, 'clock/history', 'clock/day', 'clock/period', 'today'),
      item('schedule', '근무 일정', '/me/schedule', employee, 'workspace/schedule'),
      item('worktime', '근로시간', '/me/worktime', employee, 'worktime/month'),
      item('correction', '출퇴근 정정 신청', '/me/requests/correction', employee, 'request/correction'),
    ] },
    { id: 'my-requests', label: '휴가·신청', children: [
      item('new-leave', '휴가 신청', '/me/requests/new?kind=LEAVE_DAY', employee),
      item('requests', '내 신청 이력', '/me/requests', employee, 'request/mine'),
    ] },
  ] },
  { id: 'approvals', label: '결재', children: [
    { id: 'approval-inbox', label: '결재함', children: [
      item('pending-approvals', '결재 대기', '/approvals', ['APPROVER'], 'request/list'),
      item('completed-approvals', '내가 처리한 결재', '/approvals/completed', ['APPROVER'], 'request/list'),
    ] },
  ] },
  { id: 'people', label: '인사관리', children: [
    { id: 'personnel', label: '사원', children: [
      item('employees', '인사기본', '/admin/employees', admin, 'org/personnel-home', 'org/employee', 'org/personnel'),
      item('employee-bulk-edit', '인사정보 일괄관리', '/admin/employees/bulk-edit', admin, 'org/employees'),
      item('employee-onboarding', '입사 예정 관리', '/admin/employees/onboarding', admin, 'org/onboarding'),
    ] },
  ] },
  { id: 'organizations', label: '조직관리', children: [
    { id: 'organization', label: '조직', children: [
      item('organizations', '조직 관리', '/admin/organizations', admin, 'org/organizations', 'org/organization'),
      item('organization-heads', '조직장 배정', '/admin/organization-heads?chart=true', admin, 'org/organization-heads'),
      item('organization-chart', '조직도', '/company/organizations', company, 'company/organizations'),
    ] },
  ] },
  { id: 'operations', label: '근태 운영', children: [
    { id: 'company-status', label: '회사 현황', children: [
      item('company-attendance', '회사 근무현황', '/company/attendance', company, 'company/attendance'),
      item('admin-correction', '출퇴근 정정 현황', '/admin/requests?scope=CORRECTION', admin),
      item('company-correction', '회사 정정 현황', '/company/requests?scope=CORRECTION', ['COMPANY_REPRESENTATIVE']),
      item('attendance-report', '근태 집계·Excel', '/admin/attendance', admin, 'worktime/month'),
    ] },
    { id: 'work-operations', label: '근무 기준', children: [
      item('schedules', '근무 설정', '/admin/schedules', admin, 'schedule/list', 'schedule/form', 'schedule/detail', 'schedule/assignment'),
      item('calendar', '근무일·휴일', '/admin/calendar', admin, 'clock/calendar'),
      item('attendance-types', '근태코드 관리', '/admin/attendance-types', admin, 'org/attendance-types'),
      item('work-policies', '계산 정책', '/admin/work-policies', admin, 'worktime/policies'),
    ] },
  ] },
  { id: 'leave', label: '휴가관리', children: [
    { id: 'leave-operations', label: '신청·연차', children: [
      item('admin-leave', '휴가 신청 현황', '/admin/requests?scope=LEAVE', admin),
      item('company-leave', '회사 휴가 신청 현황', '/company/requests?scope=LEAVE', ['COMPANY_REPRESENTATIVE']),
      item('leave-balances', '연차 관리', '/admin/leave-balances', admin, 'request/balances', 'request/ledger', 'request/attributions'),
    ] },
  ] },
  { id: 'settings', label: '시스템 설정', children: [
    { id: 'access', label: '계정·공통코드', children: [
      item('account-access', '권한 관리', '/admin/access', admin, 'org/access-list', 'org/access'),
      item('common-codes', '공통코드 관리', '/admin/common-codes', admin, 'org/common-codes'),
    ] },
    { id: 'approval-settings', label: '결재 설정', children: [
      item('approval-routes', '결재선 관리', '/admin/approval-routes', admin, 'org/approval-routes'),
      item('approval-assignments', '결재선 배정', '/admin/approval-route-assignments', admin, 'org/approval-route-assignments'),
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
  'org/employee': '사원 상세', 'org/personnel': '사원 인사정보', 'org/organization': '조직 상세',
  'org/access': '계정·권한', 'schedule/form': '근무 템플릿 등록', 'schedule/detail': '근무 템플릿',
  'schedule/assignment': '근무 배정', 'request/detail': '신청 상세', 'request/correction': '기록 정정 신청',
  'request/ledger': '연차 원장', 'request/attributions': '기초 묶음 귀속연도',
  'worktime/result': '계산 근거', 'worktime/audit-detail': '감사 상세',
};
const legacyRequests = {
  '/admin/requests': { label: '고객사 신청 현황', roles: admin },
  '/company/requests': { label: '회사 신청 현황', roles: company },
};
function requestHref(path: string, scope: unknown): string {
  return scope === 'LEAVE' || scope === 'CORRECTION' ? `${path}?scope=${scope}` : path;
}

export function getNavigationContext(page: string, location: string, data: Record<string, unknown> = {}): NavigationContext | undefined {
  const { pathname, searchParams } = new URL(location, 'https://worklog.invalid');
  let href: string | undefined;
  const roles = (data.workspacePrincipal as { roles?: string[] } | undefined)?.roles;
  const requestedSource = typeof data.navigationFrom === 'string' ? data.navigationFrom : '';
  const legacySource = legacyRequests[requestedSource as keyof typeof legacyRequests];
  const source = !roles || (legacySource && legacySource.roles.some(role => roles.includes(role))) ||
    getNavigation(roles).some(group => group.children.some(section => section.children.some(leaf => leaf.href === requestedSource)))
    ? requestedSource : '';
  if (page === 'request/detail') {
    const request = data.request as { targetEmployeeId?: string; status?: string; kind?: string } | undefined;
    const self = data.employeeHeader as { employeeId?: string } | undefined;
    const mine = data.ownRequest === true || data.canWithdraw === true || data.canCancel === true || (self?.employeeId != null && self.employeeId === request?.targetEmployeeId);
    const sourcePath = source.split('?')[0];
    href = ['/me/requests', '/approvals', '/approvals/completed', '/admin/requests', '/company/requests'].includes(sourcePath)
      ? source : mine ? '/me/requests' : data.canDecide === true ? '/approvals' : data.admin === true
        ? requestHref('/admin/requests', request?.kind === 'ATTENDANCE_CORRECTION' ? 'CORRECTION' : 'LEAVE')
        : request?.status && request.status !== 'PENDING' ? '/approvals/completed' : '/approvals';
  } else if (page === 'request/list') {
    href = pathname === '/admin/requests' ? requestHref(pathname, data.requestScope ?? searchParams.get('scope'))
      : pathname === '/approvals/completed' || data.completed === true ? '/approvals/completed' : '/approvals';
  } else if (page === 'company/requests') {
    const model = data.companyRequests as { scope?: string } | undefined;
    href = requestHref('/company/requests', model?.scope ?? searchParams.get('scope'));
  } else if (page === 'request/correction' && (pathname.startsWith('/admin/') || data.exceptional === true)) {
    href = '/admin/requests?scope=CORRECTION';
  } else if (page === 'request/mine' && pathname === '/me/requests/new') {
    href = searchParams.get('kind') === 'ATTENDANCE_CORRECTION' ? '/me/requests/correction' : '/me/requests/new?kind=LEAVE_DAY';
  } else if (page === 'worktime/result') {
    href = ['/me/worktime', '/admin/attendance', '/company/attendance'].includes(source)
      ? source : data.admin === true ? '/admin/attendance' : '/me/worktime';
  } else if (page === 'worktime/month') {
    href = pathname.startsWith('/admin/attendance') || data.admin === true ? '/admin/attendance' : '/me/worktime';
  }
  const detailTitle = detailTitles[page];
  const legacy = legacyRequests[href as keyof typeof legacyRequests];
  if (href && legacy) return {
    groupId: 'operations', sectionId: 'company-status', itemId: 'legacy-requests', href, detailTitle,
    breadcrumbs: [{ label: '근태 운영' }, { label: '회사 현황' }, { label: legacy.label, href: detailTitle ? href : undefined },
      ...(detailTitle ? [{ label: detailTitle }] : [])],
  };
  for (const group of navigation) for (const section of group.children) for (const leaf of section.children) {
    if (href ? leaf.href !== href : !leaf.pages.includes(page)) continue;
    return { groupId: group.id, sectionId: section.id, itemId: leaf.id, href: leaf.href, detailTitle,
      breadcrumbs: [{ label: group.label }, { label: section.label }, { label: leaf.label, href: detailTitle ? leaf.href : undefined }, ...(detailTitle ? [{ label: detailTitle }] : [])] };
  }
  return undefined;
}
