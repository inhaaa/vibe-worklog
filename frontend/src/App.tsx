import { useCallback, useEffect, useRef, useState } from 'react';
import type { MouseEvent, ReactNode, SubmitEvent } from 'react';
import type { PageEnvelope, PageProps } from './types';
import { CsrfContext, Form } from './ui';
import { encodeForm, fetchPage, PageDownload, PageRequestError } from './transport';
import { AdminPages } from './pages/AdminPages';
import { WorkflowPages } from './pages/WorkflowPages';
import { AuthPages } from './pages/AuthPages';
import { CorePages } from './pages/CorePages';
import { formatRoles, IdentityPages } from './pages/IdentityPages';

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
  today: '홈',
  'workspace/profile': '내 정보',
  'workspace/schedule': '내 근무 일정',
  'org/employees': '직원 목록',
  'org/employee': '직원 상세',
  'org/organizations': '조직 관리',
  'org/organization': '조직 상세',
  'org/access': '계정·권한',
  'org/access-list': '권한 관리',
  'company/attendance': '회사 근무현황',
  'company/requests': '회사 신청 현황',
  'schedule/list': '근무 설정',
  'schedule/form': '근무 템플릿',
  'schedule/detail': '근무 템플릿',
  'schedule/assignment': '근무 배정',
  'clock/networks': '허용 네트워크',
  'clock/calendar': '근무일·휴일',
  'clock/day': '출퇴근 내역',
  'clock/history': '출퇴근 내역',
  'request/mine': '휴가·신청',
  'request/detail': '신청 상세',
  'request/correction': '기록 정정 신청',
  'request/balances': '연차 관리',
  'request/ledger': '연차 원장',
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
const navigation = [
  {
    group: '내 업무',
    roles: ['EMPLOYEE'],
    items: [
      ['/me/today', '홈', 'home'],
      ['/me/profile', '내 정보', 'people'],
      ['/me/schedule', '내 근무 일정', 'calendar'],
      ['/me/requests', '휴가·신청', 'request'],
      ['/me/worktime', '근로시간', 'clock'],
    ],
  },
  { group: '승인 업무', roles: ['APPROVER'], items: [['/approvals', '신청 승인', 'request']] },
  {
    group: '회사 현황',
    roles: ['ATTENDANCE_ADMIN', 'COMPANY_REPRESENTATIVE'],
    items: [
      ['/company/attendance', '회사 근무현황', 'chart'],
      ['/company/requests', '회사 신청 현황', 'request'],
    ],
  },
  {
    group: '관리 업무',
    roles: ['ATTENDANCE_ADMIN'],
    items: [
      ['/admin/employees', '직원 목록', 'people'],
      ['/admin/organizations', '조직 관리', 'building'],
      ['/admin/access', '권한 관리', 'people'],
      ['/admin/schedules', '근무 설정', 'settings'],
      ['/admin/calendar', '근무일·휴일', 'calendar'],
      ['/admin/networks', '허용 네트워크', 'settings'],
      ['/admin/leave-balances', '연차 관리', 'request'],
      ['/admin/requests', '전체 신청', 'request'],
      ['/admin/attendance', '근태 현황·Excel', 'chart'],
      ['/admin/work-policies', '계산 정책', 'settings'],
      ['/admin/audit', '변경 이력', 'chart'],
    ],
  },
];
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
  title,
  children,
}: {
  data: Record<string, unknown>;
  title: string;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const employee = data.workspaceEmployee as
    { employeeName: string; companyName: string } | undefined;
  const roles = (data.workspacePrincipal as { roles: string[] } | undefined)?.roles ?? [];
  const demo = data.workspaceDemo === true;
  useEffect(() => {
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('keydown', close);
    return () => document.removeEventListener('keydown', close);
  }, []);
  return (
    <div className="t-app-shell" data-menu-open={String(open)}>
      <aside className="t-sidebar" id="workspace-menu" aria-label="주 메뉴">
        <a className="t-brand-button t-wordmark" href={demo ? '/demo' : '/me/today'}>
          <span className="t-logo" aria-hidden="true">
            W
          </span>
          <span>
            WorkLog<span className="t-brand-sub">Work, in rhythm.</span>
          </span>
        </a>
        <div className="t-workspace-name">
          <span className="t-workspace-icon">T</span>
          <span>
            {employee?.companyName ?? '워크스페이스'}
            <small>
              {formatRoles(roles)}
            </small>
          </span>
        </div>
        <nav aria-label="업무 메뉴">
          {navigation
            .filter((group) => group.roles.some((role) => roles.includes(role)))
            .map((group) => (
              <div key={group.group}>
                <p className="t-nav-group">{group.group}</p>
                {group.items.map(([href, label, icon]) => (
                  <a
                    key={href}
                    href={href}
                    className="t-nav-item"
                    aria-current={location.pathname === href ? 'page' : undefined}
                    onClick={() => setOpen(false)}
                  >
                    <Icon name={icon} />
                    {label}
                  </a>
                ))}
              </div>
            ))}
        </nav>
        <div className="t-sidebar-bottom">
          {demo && (
            <>
              <span className="t-demo-label">로컬 데모 워크스페이스</span>
              <p>합성 자료로 기능을 확인하세요.</p>
              <a href="/demo">← 소개 화면으로</a>
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
          <span>워크스페이스</span>
          <Icon name="chevron" />
          <strong>{title}</strong>
        </div>
        <div className="t-profile">
          {demo && <span className="t-demo-pill">DEMO</span>}
          <a className="t-profile-copy" href="/me/profile">
            <span>{employee?.employeeName ?? '직원'}</span>
            <small>{employee?.companyName ?? '워크스페이스'}</small>
          </a>
          <span className="t-avatar blue" aria-hidden="true">
            {employee?.employeeName?.slice(0, 1) ?? 'W'}
          </span>
          <Form action="/logout">
            <button className="t-btn t-logout">로그아웃</button>
          </Form>
        </div>
      </header>
      <main className="t-main">{children}</main>
    </div>
  );
}
function Content(props: PageProps) {
  return (
    <>
      <AuthPages {...props} />
      <CorePages {...props} />
      <IdentityPages {...props} />
      <AdminPages {...props} />
      <WorkflowPages {...props} />
    </>
  );
}

export function App({ initial }: { initial: PageEnvelope }) {
  const [envelope, setEnvelope] = useState(initial);
  const [revision, setRevision] = useState(0);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const serial = useRef(0);
  const posting = useRef(false);
  const pending = useRef<AbortController | null>(null);
  const currentUrl = useRef(location.href);
  const workspace = !publicPages.has(envelope.page);
  const navigate = useCallback(
    async (
      url: string,
      options: RequestInit = {},
      historyMode: 'push' | 'replace' | 'none' = 'push',
    ) => {
      if (posting.current && options.method?.toUpperCase() !== 'POST') {
        setNotice('저장 결과를 확인하고 있습니다. 처리가 끝난 뒤 이동해 주세요.');
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
        const result = await fetchPage(url, { ...options, signal: controller.signal });
        if (requestId !== serial.current) return;
        const formError = options.method?.toUpperCase() === 'POST' && result.status >= 400;
        const destination = new URL(formError ? currentUrl.current : result.url, location.href);
        const requested = new URL(url, location.href);
        if (destination.pathname === requested.pathname && destination.search === requested.search)
          destination.hash = requested.hash;
        if (destination.origin !== location.origin)
          throw new PageRequestError(400, '이동할 주소를 확인할 수 없습니다.');
        if (historyMode === 'push' && !formError) history.pushState(null, '', destination.href);
        else if (historyMode === 'replace') history.replaceState(null, '', destination.href);
        currentUrl.current = destination.href;
        setEnvelope(result.envelope);
        setRevision((value) => value + 1);
      } catch (error) {
        if (requestId !== serial.current || controller.signal.aborted) return;
        if (error instanceof PageDownload) {
          const objectUrl = URL.createObjectURL(error.blob);
          const link = document.createElement('a');
          link.href = objectUrl;
          link.download = error.filename;
          link.click();
          setTimeout(() => URL.revokeObjectURL(objectUrl), 0);
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
    [],
  );
  useEffect(() => {
    const back = () => {
      const current = new URL(currentUrl.current);
      if (current.pathname === location.pathname && current.search === location.search) {
        currentUrl.current = location.href;
        if (!posting.current) {
          ++serial.current;
          pending.current?.abort();
          setBusy(false);
        }
        return;
      }
      if (posting.current) {
        history.pushState(null, '', currentUrl.current);
        setNotice('저장 결과를 확인하고 있습니다. 처리가 끝난 뒤 이동해 주세요.');
        return;
      }
      void navigate(location.href, {}, 'none');
    };
    window.addEventListener('popstate', back);
    return () => {
      window.removeEventListener('popstate', back);
      pending.current?.abort();
    };
  }, [navigate]);
  useEffect(() => {
    document.title = `${titles[envelope.page] ?? 'WorkLog'} · WorkLog`;
    document.body.className = workspace
      ? 'workspace-body'
      : envelope.page === 'demo'
        ? 'intro-body'
        : '';
    if (location.hash) {
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
    void navigate(url.href);
  };
  const submit = (event: SubmitEvent<HTMLDivElement>) => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement) || event.defaultPrevented) return;
    const native = event.nativeEvent as globalThis.SubmitEvent;
    const submitter = native.submitter as HTMLButtonElement | null;
    const url = new URL(submitter?.getAttribute('formaction') || form.action, location.href);
    if (url.origin !== location.origin) return;
    event.preventDefault();
    if (posting.current) return;
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
      form.querySelectorAll<HTMLButtonElement | HTMLInputElement>(
        'button:not([type="button"]),input[type="submit"]',
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
    reload: () => {
      void navigate(currentUrl.current, {}, 'replace');
    },
  };
  const content = (
    <div key={revision}>
      <Content {...props} />
    </div>
  );
  return (
    <CsrfContext value={envelope.csrf}>
      <div id="tna-app" onClick={click} onSubmit={submit} aria-busy={busy}>
        {notice && (
          <p className="notice error" role="alert">
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
            key={envelope.page}
            data={envelope.data}
            title={titles[envelope.page] ?? '업무'}
          >
            {content}
          </Workspace>
        ) : (
          content
        )}
      </div>
    </CsrfContext>
  );
}
