import { useCallback, useEffect, useRef, useState } from 'react';
import type { MouseEvent, ReactNode, SubmitEvent } from 'react';
import type { PageEnvelope, PageProps } from './types';
import { CsrfContext, Form } from './ui';
import { encodeForm, fetchPage, PageDownload, PageRequestError } from './transport';
import { AdminPages } from './pages/AdminPages';
import { OvertimePage } from './pages/OvertimePage';
import { WorkflowPages } from './pages/WorkflowPages';
import { AuthPages } from './pages/AuthPages';
import { CorePages } from './pages/CorePages';
import { WorkspaceHome, useWorkspaceHomeRefresh } from './pages/WorkspaceHome';
import { CompanySetupGuide } from './pages/CompanySetupGuide';
import { formatRoles, IdentityPages } from './pages/IdentityPages';
import { OrgSheetPages } from './pages/OrgSheetPages';
import { OnboardingPage } from './pages/OnboardingPage';
import { ApprovalRoutePages } from './pages/ApprovalRoutePages';
import { RequestFormPage } from './pages/RequestFormPage';
import { CodePages } from './pages/CodePages';
import { AnnualRequestPage, LeaveAttributionPage } from './pages/AnnualRequestPage';
import { leaveOperationsReturn } from './pages/LeaveOperationsNavigation';
import { PersonnelPage } from './pages/PersonnelPage';
import { EmployeeHeader } from './components/EmployeeHeader';
import type { EmployeeHeaderData } from './components/EmployeeHeader';
import { Breadcrumbs, WorkspaceNavigation } from './components/WorkspaceNavigation';
import { getNavigation, getNavigationContext, getWorkspaceMenuRoles, getWorkspaceRoles, selectWorkspaceRole } from './navigation';
import { Modal } from './components/Modal';
import { ThemeSwitcher } from './ThemeSwitcher';
import { DemoAccountSwitcher } from './components/DemoAccountSwitcher';
import './WorkspacePolish.css';
import './design/tokens.css';
import './design/ui.css';
import './design/shell.css';
import './design/request.css';
import './design/workspace.css';
import './design/screens.css';

function isTaskUrl(url: URL) {
  if (url.pathname === '/admin/employees/onboarding' || url.pathname === '/admin/employees/bulk-edit') return false;
  return /^\/admin\/employees\/[^/]+(?:\/(?:access|schedule))?$/.test(url.pathname)
    || /^\/admin\/(?:organizations|schedules)\/[^/]+$/.test(url.pathname)
    || (url.pathname === '/admin/organization-heads' && url.searchParams.has('organizationId') && url.searchParams.get('chart') !== 'true')
    || /^\/(?:me\/requests\/(?:new|correction)|admin\/requests\/exceptions\/new|admin\/approval-route-assignments)$/.test(url.pathname)
    || (url.pathname === '/admin/work-policies' && url.searchParams.has('employeeId'))
    || /^\/requests\/[^/]+$/.test(url.pathname)
    || /^\/admin\/leave-balances\/buckets\/[^/]+(?:\/attributions)?$/.test(url.pathname)
    || /^\/worktime\/results\/[^/]+$/.test(url.pathname);
}

interface BackgroundPage { envelope: PageEnvelope; url: string; revision: number; position: number }

function shouldRefreshLeaveList(saved: BackgroundPage, detail: PageEnvelope) {
  if (saved.envelope.page !== 'request/list' || detail.page !== 'request/detail'
      || detail.data.admin !== true || typeof detail.data.leaveReturnTo !== 'string'
      || !leaveOperationsReturn(detail.data.leaveReturnTo)) return false;
  const url = new URL(saved.url);
  return leaveOperationsReturn(url.pathname + url.search) !== null;
}

const publicPages = new Set([
  'login',
  'demo',
  'invite',
  'password-reset',
  'operator/login',
  'operator/tenants',
  'org/problem',
  'error',
]);
const titles: Record<string, string> = {
  today: '출퇴근 기록',
  'workspace/home': '홈',
  'org/personnel': '인사기본',
  'org/personnel-home': '인사기본',
  'workspace/profile': '내 정보',
  'workspace/schedule': '내 근무 일정',
  'org/employees': '인사정보 일괄관리',
  'org/employee': '직원 상세',
  'org/organizations': '조직 관리',
  'org/organization': '조직 상세',
  'org/organization-heads': '조직장 배정',
  'org/approval-routes': '결재선 관리',
  'org/request-forms': '신청서코드 관리',
  'org/approval-route-assignments': '결재선 배정',
  'org/common-codes': '공통코드 관리',
  'org/attendance-types': '근태코드 관리',
  'org/access': '계정·권한',
  'org/access-list': '권한 관리',
  'company/attendance': '회사 근무현황',
  'company/requests': '회사 신청 현황',
  'company/organizations': '조직도',
  'schedule/list': '근무 설정',
  'schedule/form': '근무 템플릿',
  'schedule/detail': '근무 템플릿',
  'schedule/assignment': '근무 배정',
  'clock/networks': '허용 네트워크',
  'clock/calendar': '근무일·휴일',
  'clock/day': '출퇴근 내역',
  'clock/history': '출퇴근 내역',
  'clock/period': '출퇴근 기간 조회',
  'attendance/follow-ups': '내 근태 보완 요청',
  'org/onboarding': '입사 예정 관리',
  'overtime/employee': '연장근무 신청',
  'overtime/admin': '연장근무 신청현황',
  'request/mine': '내 근태현황·신청',
  'request/attributions': '기초 묶음 귀속연도',
  'request/detail': '신청 상세',
  'request/correction': '기록 정정 신청',
  'request/balances': '연차 관리',
  'request/ledger': '연차 원장',
  'request/annual-cycle': '연차 부여·이월·소멸',
  'request/personal-ledger': '내 연차 원장',
  'request/list': '신청 목록',
  'worktime/month': '근로시간',
  'worktime/result': '계산 근거',
  'worktime/policies': '계산 정책',
  'worktime/audit': '변경 이력',
  'worktime/audit-detail': '감사 상세',
  login: '로그인',
  demo: 'WorkLog',
  invite: '초대 수락',
  'password-reset': '비밀번호 재설정',
  'operator/login': '운영자 로그인',
  'operator/tenants': '고객사 온보딩',
};
function Icon({ name }: { name: string }) {
  const paths: Record<string, string> = {
    home: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
    calendar: 'M3 5h18v16H3z M16 3v4M8 3v4M3 11h18M8 15h2M14 15h2',
    people:
      'M3 21v-3a6 6 0 0 1 12 0v3M16 5a3 3 0 0 1 0 6M18 15a5 5 0 0 1 3 5 M6 8a3 3 0 1 0 6 0a3 3 0 1 0-6 0',
    building: 'M5 3h14v18H5z M9 7h1M14 7h1M9 11h1M14 11h1M10 21v-5h4v5',
    clock: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18 M12 7v5l3 2',
    settings: 'M4 7h16M4 17h16M8 4v6M16 14v6',
    chart: 'M4 4v16h16M8 15v-4M13 15V7M18 15v-6',
    menu: 'M4 6h16M4 12h16M4 18h16',
    chevron: 'm9 5 7 7-7 7',
  };
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d={paths[name] ?? 'M5 3h14v18H5z m3 9 3 3 5-6 M9 6h6'} />
    </svg>
  );
}
function Workspace({
  data,
  page,
  pageUrl,
  title,
  children,
  busy,
}: {
  data: Record<string, unknown>;
  page: string;
  pageUrl: string;
  title: string;
  children: ReactNode;
  busy: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const employee = data.workspaceEmployee as
    { employeeName: string; companyName: string } | undefined;
  const roles = (data.workspacePrincipal as { roles: string[] } | undefined)?.roles ?? [];
  const roleOptions = getWorkspaceRoles(roles);
  const demo = data.workspaceDemo === true;
  const workspaceName = demo ? 'WorkLog 데모' : (employee?.companyName ?? '워크스페이스');
  const [selectedRole, setSelectedRole] = useState(() => {
    const href = getNavigationContext(page, pageUrl, data)?.href;
    return roleOptions.find(role => getNavigation([role.value]).some(group => group.children.some(section => section.children.some(item => item.href === href))))?.value
      ?? (demo && roles.includes('ATTENDANCE_ADMIN') ? 'ATTENDANCE_ADMIN' : undefined);
  });
  const activeRole = selectWorkspaceRole(roles, selectedRole);
  const employeeHeader = data.employeeHeader as EmployeeHeaderData | undefined;
  const headerEmployee = employeeHeader ?? employee;
  const brand = (
    <a className="t-brand-button t-wordmark" href="/me/home" aria-label="WorkLog 홈으로"
      aria-current={page === 'workspace/home' ? 'page' : undefined} onClick={() => setOpen(false)}>
      <span className="t-logo" aria-hidden="true">W</span>
      <span>WorkLog<span className="t-brand-sub">Work, in rhythm.</span></span>
    </a>
  );
  useEffect(() => {
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('keydown', close);
    return () => document.removeEventListener('keydown', close);
  }, []);
  return (
    <div className="t-app-shell" data-menu-open={String(open)} data-sidebar-collapsed={String(collapsed)}>
      <a className="t-skip-link" href="#workspace-content" onClick={event => { event.preventDefault(); document.getElementById('workspace-content')?.focus(); }}>본문으로 건너뛰기</a>
      <aside className="t-sidebar" id="workspace-menu" aria-label="주 메뉴">
        {brand}
        <div className="t-workspace-name">
          <span className="t-workspace-icon">T</span>
          <span>
            {workspaceName}
            <small>
              {formatRoles(roles)}
            </small>
          </span>
        </div>
        <WorkspaceNavigation roles={getWorkspaceMenuRoles(roles, activeRole)} page={page} data={data} pathname={pageUrl} onNavigate={() => setOpen(false)} />
        <div className="t-sidebar-bottom">
          {demo && (
            <>
              <span className="t-demo-label">{workspaceName}</span>
              <p>합성 자료로 기능을 확인하세요.</p>
              <a href="/demo">소개 화면으로</a>
            </>
          )}
          <p>Asia/Seoul 기준</p>
        </div>
      </aside>
      <header className="t-topbar">
        <div className="t-breadcrumb">
          <button
            type="button"
            className="t-mobile-menu"
            aria-label={open ? '업무 메뉴 닫기' : '업무 메뉴 열기'}
            aria-controls="workspace-menu"
            aria-expanded={open}
            onClick={() => setOpen(!open)}
          >
            <Icon name="menu" />
          </button>
          <button
            type="button"
            className="t-sidebar-toggle"
            aria-label={collapsed ? '사이드 메뉴 열기' : '사이드 메뉴 닫기'}
            aria-controls="workspace-menu"
            aria-expanded={!collapsed}
            onClick={() => setCollapsed(!collapsed)}
          >
            <Icon name="menu" />
          </button>
          {brand}
          <Breadcrumbs page={page} data={data} pathname={pageUrl} />
        </div>
        <div className="t-profile">
          <ThemeSwitcher />
          {roleOptions.length > 0 && <label className="t-role-picker">
            <span>업무 권한</span>
            <select aria-label="업무 권한" value={activeRole} onChange={event => setSelectedRole(selectWorkspaceRole(roles, event.target.value))}>
              {roleOptions.map(role => <option key={role.value} value={role.value}>{role.label}</option>)}
            </select>
          </label>}
          <a className="t-profile-copy" href="/me/profile">
            <span>{employee?.employeeName ?? '직원'}</span>
            <small>{workspaceName}</small>
          </a>
          <span className="t-avatar blue" aria-hidden="true">
            {employee?.employeeName?.slice(0, 1) ?? 'W'}
          </span>
          {demo && <DemoAccountSwitcher busy={busy} currentEmployeeNo={location.pathname.startsWith('/me/') ? employeeHeader?.employeeNo : undefined} />}
          <Form action="/logout">
            <button className="t-btn t-logout">로그아웃</button>
          </Form>
        </div>
      </header>
      <main className="t-main" id="workspace-content" tabIndex={-1} aria-label={title}>
        {page === 'workspace/home' && !demo && roles.includes('ATTENDANCE_ADMIN') && (
          <CompanySetupGuide adminSelected={activeRole === 'ATTENDANCE_ADMIN'}
            selectAdmin={() => setSelectedRole('ATTENDANCE_ADMIN')} />
        )}
        {page !== 'workspace/home' && page !== 'org/personnel-home' && !data.adminPersonnel && headerEmployee && (
          <EmployeeHeader variant={page === 'workspace/profile' || page === 'org/personnel' ? 'card' : 'line'}
            employee={demo ? { ...headerEmployee, companyName: workspaceName } : headerEmployee} />
        )}
        {children}
      </main>
    </div>
  );
}
function Content(props: PageProps) {
  if (props.page === 'workspace/home') return <WorkspaceHome data={props.data} busy={props.busy} />;
  return (
    <>
      <AuthPages {...props} />
      <CorePages {...props} />
      <IdentityPages {...props} />
      <OrgSheetPages {...props} />
      {props.page === 'org/onboarding' && <OnboardingPage data={props.data} />}
      <ApprovalRoutePages {...props} />
      {props.page === 'org/request-forms' && <RequestFormPage data={props.data} />}
      <CodePages {...props} />
      <AnnualRequestPage {...props} />
      <LeaveAttributionPage {...props} />
      <PersonnelPage {...props} />
      <AdminPages {...props} />
      <OvertimePage {...props} />
      <WorkflowPages {...props} />
    </>
  );
}

export function App({ initial }: { initial: PageEnvelope }) {
  const [envelope, setEnvelope] = useState<PageEnvelope>(() => ['request/detail', 'worktime/result'].includes(initial.page) && typeof history.state?.worklogNavigationFrom === 'string'
    ? { ...initial, data: { ...initial.data, navigationFrom: history.state.worklogNavigationFrom } } : initial);
  const [revision, setRevision] = useState(0);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [background, setBackground] = useState<BackgroundPage | null>(null);
  const [modalWorking, setModalWorking] = useState(false);
  const backgroundRef = useRef(background);
  backgroundRef.current = background;
  const currentPage = useRef({ envelope, revision });
  currentPage.current = { envelope, revision };
  const modalDirty = useRef(false);
  const skipPageFocus = useRef<PageEnvelope | null>(null);
  const serial = useRef(0);
  const posting = useRef(false);
  const pending = useRef<AbortController | null>(null);
  const currentUrl = useRef(location.href);
  const navigationFrom = useRef<string | undefined>(undefined);
  const activeUrl = new URL(currentUrl.current);
  navigationFrom.current = getNavigationContext(envelope.page, activeUrl.pathname + activeUrl.search, envelope.data)?.href;
  const historyPosition = useRef<number>(Number.isInteger(history.state?.worklogPosition) ? history.state.worklogPosition : 0);
  const restoringHistory = useRef(false);
  const historyState = (position: number, source?: string) => ({ ...(history.state && typeof history.state === 'object' ? history.state : {}), worklogPosition: position, worklogNavigationFrom: source ?? null });
  const workspace = !publicPages.has(envelope.page);
  const hasUnsaved = () => backgroundRef.current
    ? modalDirty.current || Boolean(document.querySelector('.t-modal [data-unsaved-changes="true"]'))
    : Boolean(document.querySelector('[data-unsaved-changes="true"]'));
  const hasPendingSave = useCallback(() => posting.current || Boolean(document.querySelector('[data-modal-busy="true"]')), []);
  const restoreBackground = useCallback(() => {
    const saved = backgroundRef.current;
    if (!saved) return;
    ++serial.current;
    pending.current?.abort();
    currentUrl.current = saved.url;
    historyPosition.current = saved.position;
    skipPageFocus.current = saved.envelope;
    setEnvelope(saved.envelope);
    setRevision(saved.revision);
    backgroundRef.current = null;
    setBackground(null);
    modalDirty.current = false;
    setBusy(false);
  }, []);
  const closeModal = useCallback(() => {
    if (posting.current || !backgroundRef.current || document.querySelector('.t-modal [data-modal-busy="true"]')) return;
    if ((modalDirty.current || document.querySelector('.t-modal [data-unsaved-changes="true"]')) &&
      !window.confirm('저장하지 않은 변경이 있습니다. 편집 내용을 버리고 닫을까요?')) return;
    modalDirty.current = false;
    const saved = backgroundRef.current;
    if (shouldRefreshLeaveList(saved, currentPage.current.envelope)) {
      if (location.href !== saved.url) history.back();
      return;
    }
    restoreBackground();
    // 모달 안의 이동은 같은 이력 항목을 교체하므로 한 번만 돌아간다.
    if (location.href !== saved.url) history.back();
  }, [restoreBackground]);
  const navigate = useCallback(
    async (
      url: string,
      options: RequestInit = {},
      historyMode: 'push' | 'replace' | 'none' = 'push',
      discardConfirmed = false,
      openAsTask = true,
    ) => {
      if (hasPendingSave() && options.method?.toUpperCase() !== 'POST') {
        setNotice('저장 결과를 확인하고 있습니다. 처리가 끝난 뒤 이동해 주세요.');
        return;
      }
      if (!discardConfirmed && options.method?.toUpperCase() !== 'POST' &&
          hasUnsaved() &&
          !window.confirm('저장하지 않은 변경이 있습니다. 편집 내용을 버리고 이동할까요?')) {
        if (location.href !== currentUrl.current) history.replaceState(null, '', currentUrl.current);
        return;
      }
      if (historyMode !== 'none' && location.href !== currentUrl.current)
        history.replaceState(null, '', currentUrl.current);
      const requestId = ++serial.current;
      pending.current?.abort();
      const controller = new AbortController();
      pending.current = controller;
      setBusy(true);
      setNotice('');
      try {
        const sourceMenu = historyMode === 'none' ? history.state?.worklogNavigationFrom : navigationFrom.current;
        const result = await fetchPage(url, { ...options, signal: controller.signal });
        if (requestId !== serial.current) return;
        const formError = options.method?.toUpperCase() === 'POST' && result.status >= 400;
        const headChartUrl = result.envelope.data.headChartUrl;
        const previewUrl = result.envelope.data.headPreview && typeof headChartUrl === 'string' ? headChartUrl : result.url;
        const destination = new URL(formError ? currentUrl.current : previewUrl, location.href);
        const requested = new URL(url, location.href);
        if (destination.pathname === requested.pathname && destination.search === requested.search)
          destination.hash = requested.hash;
        if (destination.origin !== location.origin)
          throw new PageRequestError(400, '이동할 주소를 확인할 수 없습니다.');
        const activeBackground = backgroundRef.current;
        const isPost = options.method?.toUpperCase() === 'POST';
        const task = !result.envelope.data.settlementReturn && !publicPages.has(result.envelope.page) && (isTaskUrl(destination) ||
          Boolean(activeBackground && formError));
        if (activeBackground && isPost && !formError && !publicPages.has(result.envelope.page)) {
          // 저장된 값은 배경 목록에도 반영하고, 갱신 중 권한 회수 역시 검사한다.
          let refreshFailed = false;
          try {
            const refreshed = destination.href === activeBackground.url ? result
              : await fetchPage(activeBackground.url, { signal: controller.signal });
            if (requestId !== serial.current) return;
            if (publicPages.has(refreshed.envelope.page)) throw new PageRequestError(401, '로그인 상태를 다시 확인해 주세요.');
            const receipt = result.envelope.data.annual as Record<string, unknown> | undefined;
            const annual = refreshed.envelope.data.annual as Record<string, unknown> | undefined;
            const refreshedEnvelope = receipt?.createdId && annual ? { ...refreshed.envelope,
              data: { ...refreshed.envelope.data, annual: { ...annual, createdId: receipt.createdId, outsideYear: receipt.outsideYear } } } : refreshed.envelope;
            const updated = { ...activeBackground, envelope: refreshedEnvelope, revision: activeBackground.revision + 1 };
            backgroundRef.current = updated;
            setBackground(updated);
          } catch (error) {
            if (requestId !== serial.current || controller.signal.aborted) return;
            if (error instanceof PageRequestError && ([401, 403].includes(error.status) || error.reauthenticate)) throw error;
            refreshFailed = true;
          }
          modalDirty.current = false;
          setNotice(refreshFailed ? '저장은 완료했습니다. 목록 새로고침에 실패했으니 닫은 뒤 다시 조회해 주세요.' : '저장했습니다. 최신 내용을 반영했습니다.');
          if (!task) {
            restoreBackground();
            history.back();
            return;
          }
        } else if (!activeBackground && task && !isPost && openAsTask && historyMode !== 'none' && !currentPage.current.envelope.data.compose &&
          !publicPages.has(currentPage.current.envelope.page) &&
          (currentPage.current.envelope.page !== result.envelope.page || Boolean(result.envelope.data.compose))) {
          const saved = { ...currentPage.current, url: currentUrl.current, position: historyPosition.current };
          backgroundRef.current = saved;
          setBackground(saved);
          modalDirty.current = false;
        } else if (activeBackground && !task) {
          backgroundRef.current = null;
          setBackground(null);
          modalDirty.current = false;
        }
        const sharedDetail = ['request/detail', 'worktime/result'].includes(result.envelope.page);
        const detailSource = sharedDetail && typeof sourceMenu === 'string' ? sourceMenu : undefined;
        if (activeBackground && task && historyMode !== 'none') {
          history.replaceState(historyState(historyPosition.current, detailSource), '', destination.href);
        } else if (historyMode === 'push' && !formError) {
          historyPosition.current += 1;
          history.pushState(historyState(historyPosition.current, detailSource), '', destination.href);
        } else if (historyMode === 'replace') history.replaceState(historyState(historyPosition.current, detailSource), '', destination.href);
        else if (historyMode === 'none' && Number.isInteger(history.state?.worklogPosition)) historyPosition.current = history.state.worklogPosition;
        currentUrl.current = destination.href;
        setEnvelope(detailSource ? { ...result.envelope, data: { ...result.envelope.data, navigationFrom: detailSource } } : result.envelope);
        const refreshingHome = historyMode === 'replace' && currentPage.current.envelope.page === 'workspace/home'
          && result.envelope.page === 'workspace/home';
        if (!refreshingHome) setRevision((value) => value + 1);
        modalDirty.current = formError;
      } catch (error) {
        if (requestId !== serial.current || controller.signal.aborted) return;
        if (error instanceof PageDownload) {
          error.save();
          setNotice('파일을 다운로드했습니다.');
        } else {
          const message =
            error instanceof Error
              ? error.message
              : '요청을 처리할 수 없습니다. 다시 시도해 주세요.';
          if (
            error instanceof PageRequestError &&
            ([401, 403].includes(error.status) || error.reauthenticate)
          ) {
            backgroundRef.current = null;
            setBackground(null);
            modalDirty.current = false;
            setEnvelope({
              page: 'org/problem',
              data: { message },
              csrf: { token: '', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' },
            });
            setRevision((value) => value + 1);
          } else {
            if (historyMode === 'none') history.replaceState(null, '', currentUrl.current);
            setNotice(message);
          }
        }
      } finally {
        if (requestId === serial.current) setBusy(false);
      }
    },
    [hasPendingSave, restoreBackground],
  );
  useEffect(() => {
    history.replaceState(historyState(historyPosition.current, navigationFrom.current), '', currentUrl.current);
    const back = () => {
      if (restoringHistory.current) {
        restoringHistory.current = false;
        return;
      }
      const saved = backgroundRef.current;
      if (saved && document.querySelector('.t-modal [data-modal-busy="true"]')) {
        const targetPosition = history.state?.worklogPosition;
        const delta = Number.isInteger(targetPosition) ? historyPosition.current - targetPosition : 0;
        if (delta) { restoringHistory.current = true; history.go(delta); }
        else history.pushState(historyState(historyPosition.current), '', currentUrl.current);
        return;
      }
      if (saved && location.href === saved.url && !hasPendingSave()) {
        if (hasUnsaved() && !window.confirm('저장하지 않은 변경이 있습니다. 편집 내용을 버리고 닫을까요?')) {
          restoringHistory.current = true;
          history.forward();
          return;
        }
        if (shouldRefreshLeaveList(saved, currentPage.current.envelope)) void navigate(saved.url, {}, 'none', true);
        else restoreBackground();
        return;
      }
      const current = new URL(currentUrl.current);
      if (current.pathname === location.pathname && current.search === location.search) {
        currentUrl.current = location.href;
        if (!hasPendingSave()) {
          ++serial.current;
          pending.current?.abort();
          setBusy(false);
        }
        return;
      }
      if (hasPendingSave()) {
        history.pushState(null, '', currentUrl.current);
        setNotice('저장 결과를 확인하고 있습니다. 처리가 끝난 뒤 이동해 주세요.');
        return;
      }
      if (hasUnsaved() &&
          !window.confirm('저장하지 않은 변경이 있습니다. 편집 내용을 버리고 이동할까요?')) {
        const targetPosition = history.state?.worklogPosition;
        const delta = Number.isInteger(targetPosition) ? historyPosition.current - targetPosition : 0;
        if (delta) {
          restoringHistory.current = true;
          history.go(delta);
        } else {
          historyPosition.current += 1;
          history.pushState(historyState(historyPosition.current), '', currentUrl.current);
        }
        return;
      }
      void navigate(location.href, {}, 'none', true);
    };
    window.addEventListener('popstate', back);
    return () => {
      window.removeEventListener('popstate', back);
      pending.current?.abort();
    };
  }, [hasPendingSave, navigate, restoreBackground]);

  const refreshHome = useCallback(() => {
    // 모달을 닫으면 배경 화면이 브라우저의 뒤로 가기보다 먼저 복원될 수 있다.
    if (backgroundRef.current || posting.current || location.href !== currentUrl.current) return false;
    void navigate('/me/home', {}, 'replace');
    return true;
  }, [navigate]);
  useWorkspaceHomeRefresh(!busy && !background && envelope.page === 'workspace/home' ? envelope.data : undefined, refreshHome);

  useEffect(() => {
    const dialog = document.querySelector('.t-modal');
    if (!background || !dialog) { setModalWorking(false); return; }
    const update = () => setModalWorking(Boolean(dialog.querySelector('[data-modal-busy="true"]')));
    update();
    const observer = new MutationObserver(update);
    observer.observe(dialog, { subtree: true, attributes: true, attributeFilter: ['data-modal-busy'], childList: true });
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (modalDirty.current || dialog.querySelector('[data-unsaved-changes="true"], [data-modal-busy="true"]')) event.preventDefault();
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => { observer.disconnect(); window.removeEventListener('beforeunload', beforeUnload); };
  }, [background, revision]);
  useEffect(() => {
    document.title = `${titles[envelope.page] ?? 'WorkLog'} · WorkLog`;
    document.body.className = workspace
      ? 'workspace-body'
      : envelope.page === 'demo'
        ? 'intro-body'
        : '';
    // 모달 커밋의 effect가 닫기 클릭 뒤에 늦게 실행돼도 건너뛰기 표식을 가로채거나 배경 제목으로 초점을 옮기지 않도록,
    // 복원한 배경 화면의 실행만 건너뛰고 닫기 처리가 이미 바꾼 ref 대신 이 렌더의 background로 판단한다.
    if (skipPageFocus.current === envelope) { skipPageFocus.current = null; return; }
    const focusRoot = background ? document.querySelector('.t-modal') : document;
    const errorField = focusRoot?.querySelector<HTMLElement>('[data-error-focus="true"]:not(:disabled)');
    if (errorField) {
      errorField.focus({ preventScroll: true });
      return;
    }
    if (!background && location.hash) {
      try {
        const target = document.getElementById(decodeURIComponent(location.hash.slice(1)));
        if (target) {
          target.scrollIntoView?.({ block: 'start' });
          target.focus({ preventScroll: true });
          return;
        }
      } catch {
        /* 잘못 인코딩된 fragment는 제목으로 초점을 옮긴다. */
      }
    }
    if (background) {
      if (!focusRoot?.contains(document.activeElement)) document.querySelector<HTMLElement>('.t-modal-title')?.focus({ preventScroll: true });
      return;
    }
    const heading = document.querySelector<HTMLElement>('main h1');
    if (heading) {
      heading.tabIndex = -1;
      heading.focus({ preventScroll: true });
    }
  }, [envelope.page, revision, workspace]);
  const click = (event: MouseEvent<HTMLDivElement>) => {
    const anchor = (event.target as Element).closest?.('a');
    if (
      !anchor ||
      event.defaultPrevented ||
      event.button !== 0 ||
      event.metaKey ||
      event.ctrlKey ||
      event.shiftKey ||
      event.altKey ||
      anchor.target ||
      anchor.hasAttribute('download')
    )
      return;
    const url = new URL(anchor.href, location.href);
    if (
      url.origin !== location.origin ||
      !['http:', 'https:'].includes(url.protocol) ||
      url.pathname.endsWith('/download')
    )
      return;
    if (url.pathname === location.pathname && url.search === location.search && url.hash) return;
    event.preventDefault();
    void navigate(url.href, {}, 'push', false, !anchor.closest('.t-sidebar, .t-topbar'));
  };
  const submit = (event: SubmitEvent<HTMLDivElement>) => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement) || event.defaultPrevented || form.hasAttribute('data-native-submit')) return;
    const native = event.nativeEvent as globalThis.SubmitEvent;
    const submitter = native.submitter as HTMLButtonElement | null;
    const url = new URL(submitter?.getAttribute('formaction') || form.action, location.href);
    if (url.origin !== location.origin) return;
    event.preventDefault();
    if (hasPendingSave()) return;
    const method = (submitter?.getAttribute('formmethod') || form.method || 'get').toUpperCase();
    const formData = new FormData(form);
    if (submitter?.name) formData.append(submitter.name, submitter.value);
    const body = encodeForm(formData);
    if (method === 'GET') {
      url.search = body.toString();
      void navigate(url.href);
      return;
    }
    posting.current = true;
    const controls = Array.from(
      event.currentTarget.querySelectorAll<HTMLButtonElement | HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(
        'form button,form input,form select,form textarea',
      ),
    ).filter((control) => !control.disabled);
    controls.forEach((control) => {
      control.disabled = true;
    });
    form.querySelectorAll<HTMLInputElement>('input[type="password"]').forEach((input) => {
      input.value = '';
    });
    void navigate(url.href, { method, body }).finally(() => {
      posting.current = false;
      controls.forEach((control) => {
        control.disabled = false;
      });
    });
  };
  const props: PageProps = {
    ...envelope,
    busy,
    reload: () => {
      void navigate(currentUrl.current, {}, 'replace');
    },
  };
  const base = background?.envelope ?? envelope;
  const content = (
    <div key={background?.revision ?? revision}>
      <Content {...props} {...base} busy={background ? false : busy} />
    </div>
  );
  return (
    <CsrfContext value={envelope.csrf}>
      <div id="tna-app" onClick={click} onSubmit={submit} aria-busy={busy}>
        {notice && !background && (
          <p className="notice" role="alert">
            {notice}
          </p>
        )}
        {busy && (
          <p className="t-sr-only" role="status">
            처리 중입니다.
          </p>
        )}
        {workspace ? (
          <Workspace
            data={base.data}
            page={base.page}
            pageUrl={background?.url ?? currentUrl.current}
            title={titles[base.page] ?? '업무'}
            busy={busy}
          >
            {content}
          </Workspace>
        ) : (
          <>
            <div className="t-public-tools"><ThemeSwitcher /></div>
            {content}
          </>
        )}
        {background && <Modal title={Boolean(envelope.data.compose) ? '근태 신청' : titles[envelope.page] ?? '업무 상세'} busy={busy || modalWorking} onClose={closeModal}
          footer={envelope.data.compose ? false : undefined}>
          {notice && <p className="notice" role="alert">{notice}</p>}
          <div key={revision} onChange={event => {
            const form = (event.target as Element).closest('form');
            if (form && form.method.toLowerCase() !== 'get') modalDirty.current = true;
          }}>
            <Content {...props} onClose={closeModal} />
          </div>
        </Modal>}
      </div>
    </CsrfContext>
  );
}
