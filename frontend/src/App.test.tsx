// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import type { PageEnvelope } from './types';

const csrf = { token: 'a+b/c=csrf-token', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const login: PageEnvelope = { page: 'login', data: { localDemo: false }, csrf };
const problem = (message: string): PageEnvelope => ({
  page: 'org/problem',
  data: { message },
  csrf,
});
const privateEmployee = {
  id: 'e-1',
  employeeNo: 'PRIVATE-007',
  displayName: '민감직원',
  joinedOn: '2026-10-01',
  leftOn: null,
  active: true,
  version: 3,
};
const employees: PageEnvelope = {
  page: 'org/employees',
  csrf,
  data: {
    workspacePrincipal: { roles: ['EMPLOYEE', 'ATTENDANCE_ADMIN'] },
    workspaceEmployee: { employeeName: '민감직원', companyName: '합성회사' },
    employees: [privateEmployee],
    selectedEmployee: privateEmployee,
    employeeOrganizations: {},
    options: [],
    orgNames: {},
    q: '',
    organizationId: null,
    status: 'all',
    page: 0,
    hasNext: false,
    pageEmployeeCount: 1,
    pageActiveCount: 1,
    pageInactiveCount: 0,
    form: {
      commandId: 'create-command',
      employeeNo: '',
      displayName: '',
      joinedOn: null,
      leftOn: null,
      active: true,
      expectedVersion: null,
      reason: '',
    },
  },
};
const snapshot = {
  employeeId: 'e-1',
  workDate: '2026-10-26',
  events: [],
  effectiveEvents: [],
  schedule: null,
  inputRevision: 0,
  reviewRevision: 0,
  confirmation: null,
  dayKind: 'UNCONFIRMED',
  sourceFingerprint: 'source',
};
const today = {
  page: 'today',
  csrf,
  data: {
    workspacePrincipal: { roles: ['EMPLOYEE'] },
    workspaceEmployee: { employeeName: '민감직원', companyName: '합성회사' },
    day: snapshot,
    eventTimes: {},
    inCommand: 'in-command',
    outCommand: 'out-command',
    confirmationForm: {
      workDate: '2026-10-26',
      baseInputRevision: 0,
      expectedReviewRevision: 0,
      sourceFingerprint: 'source',
      commandId: 'confirm-command',
      presenceAsWork: false,
      breaksMatch: false,
    },
    workspaceHome: {
      employee: { employeeName: '민감직원', companyName: '합성회사' },
      today: {
        snapshot,
        requests: [],
        today: true,
        selected: true,
        timeline: [],
        scheduleText: '근무 일정 미배정',
        calendarText: '캘린더 미확인',
        confirmationText: '실제 근로 미확인',
        inTime: '미기록',
        outTime: '미기록',
      },
      week: [],
      availableLeaveDays: null,
      pendingRequests: 0,
      recentRequests: [],
    },
  },
} satisfies PageEnvelope;

function homeOn(workDate: string, inRecordedAt: string | null = null): PageEnvelope {
  const home = today.data.workspaceHome;
  return {
    ...today,
    page: 'workspace/home',
    data: {
      ...today.data,
      workspaceHome: { ...home, today: { ...home.today, snapshot: { ...snapshot, workDate }, inRecordedAt } },
      clockInCommand: inRecordedAt ? undefined : `${workDate}-in`,
      clockOutCommand: `${workDate}-out`,
    },
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}

function response(envelope: PageEnvelope, path: string, status = 200) {
  const result = new Response(JSON.stringify(envelope), {
    status,
    headers: { 'Content-Type': 'application/vnd.worklog.page+json' },
  });
  Object.defineProperty(result, 'url', { value: new URL(path, window.location.href).href });
  return result;
}

function assertPageRequest(options: RequestInit | undefined) {
  expect(options).toMatchObject({ credentials: 'same-origin', cache: 'no-store' });
  expect(new Headers(options?.headers).get('Accept')).toBe('application/vnd.worklog.page+json');
}

beforeEach(() => {
  window.history.replaceState(null, '', '/initial');
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function(this: HTMLDialogElement) { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function(this: HTMLDialogElement) { this.open = false; } });
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.history.replaceState(null, '', '/');
});

describe('React 앱 탐색과 제출', () => {
  it('첫 담당자가 홈에서 회사 초기 설정을 열면 담당자 메뉴로 전환하고 첫 출근에 필요한 화면을 순서대로 안내한다', () => {
    window.history.replaceState(null, '', '/me/home');
    render(<App initial={{ ...today, page: 'workspace/home', data: { ...today.data,
      workspacePrincipal: { roles: ['EMPLOYEE', 'ATTENDANCE_ADMIN'] },
    } }} />);
    const role = screen.getByRole('combobox', { name: '업무 권한' }) as HTMLSelectElement;
    expect(role.value).toBe('EMPLOYEE');
    const toggle = screen.getByRole('button', { name: '회사 초기 설정' });
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(toggle);
    expect(role.value).toBe('ATTENDANCE_ADMIN');
    const guide = within(screen.getByRole('navigation', { name: '회사 초기 설정 순서' }));
    expect(guide.getAllByRole('link').map(link => link.getAttribute('href'))).toEqual([
      '/admin/organizations', '/admin/schedules?tab=codes', '/admin/schedules?tab=groups', '/admin/calendar',
      '/admin/networks', '/admin/employees/onboarding', '/admin/access?role=APPROVER',
      '/admin/approval-routes', '/admin/request-forms',
    ]);
    fireEvent.change(role, { target: { value: 'EMPLOYEE' } });
    expect(screen.queryByRole('navigation', { name: '회사 초기 설정 순서' })).toBeNull();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
  });

  it.each([
    [['EMPLOYEE'], false], [['EMPLOYEE', 'APPROVER', 'COMPANY_REPRESENTATIVE'], false],
    [['EMPLOYEE', 'ATTENDANCE_ADMIN'], true],
  ] as const)('보유 권한 %j, 데모 %s에서는 회사 초기 설정을 제공하지 않는다', (roles, workspaceDemo) => {
    render(<App initial={{ ...today, page: 'workspace/home', data: { ...today.data,
      workspaceDemo, workspacePrincipal: { roles },
    } }} />);
    expect(screen.queryByRole('button', { name: '회사 초기 설정' })).toBeNull();
  });

  it('상단 보유 권한 선택은 로그인 사원을 유지하며 업무 메뉴만 전환한다', () => {
    const fetcher = vi.fn<typeof fetch>();
    vi.stubGlobal('fetch', fetcher);
    render(<App initial={{ ...today, data: { ...today.data,
      workspacePrincipal: { roles: ['EMPLOYEE', 'APPROVER', 'ATTENDANCE_ADMIN'] },
    } }} />);
    const select = screen.getByRole('combobox', { name: '업무 권한' });
    fireEvent.change(select, { target: { value: 'ATTENDANCE_ADMIN' } });
    expect(screen.getByRole('link', { name: '권한 관리', hidden: true })).toBeTruthy();
    expect(screen.queryByRole('link', { name: '결재 대기', hidden: true })).toBeNull();
    fireEvent.change(select, { target: { value: 'APPROVER' } });
    expect(screen.getByRole('link', { name: '결재 대기', hidden: true })).toBeTruthy();
    expect(screen.queryByRole('link', { name: '권한 관리', hidden: true })).toBeNull();
    expect(screen.getByRole('link', { name: '민감직원 합성회사' })).toBeTruthy();
    expect(screen.queryByRole('option', { name: '대표' })).toBeNull();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it('별도 홈 항목 없이 3단계 업무 메뉴를 제공한다', () => {
    render(<App initial={today} />);
    expect(screen.queryByRole('link', { name: '홈' })).toBeNull();
    const group = screen.getByRole('button', { name: /내 업무/ });
    if (group.getAttribute('aria-expanded') === 'false') fireEvent.click(group);
    expect(screen.getByRole('button', { name: /근태관리/ })).toBeTruthy();
  });

  it('공통 사원 맥락 줄은 한 줄에 사번·소속·직급·직책·직무·입사일과 재직 상태를 표시한다', () => {
    const view = render(<App initial={{ ...today, data: { ...today.data, employeeHeader: {
      employeeId: 'e-2', employeeNo: 'E002', employeeName: '선택사원', companyName: '합성회사', organizationName: '개발팀',
      joinedOn: '2024-01-02', birthDate: '1991-03-04', gradeName: '선임', positionName: '팀원', jobName: '개발',
      employmentStatus: 'WORKING', photoUrl: null, asOf: '2026-11-02T01:00:00Z',
    } } }} />);
    const header = view.getByRole('region', { name: '사원 인사정보' });
    for (const value of ['선택사원', 'E002', '개발팀', '선임', '팀원', '개발', '2024-01-02', '재직'])
      expect(header.textContent).toContain(value);
    expect(header.textContent).not.toContain('1991-03-04');
    expect(header.querySelector('nav')).toBeNull();
    expect(view.queryByRole('navigation', { name: '내 업무 바로가기' })).toBeNull();
    expect(view.queryByRole('navigation', { name: '선택 사원 업무' })).toBeNull();
  });
  it('공유 신청 상세에 뒤로 돌아오면 진입했던 업무 메뉴를 복원한다', async () => {
    window.history.replaceState({ worklogPosition: 0 }, '', '/requests/r-1');
    const detail: PageEnvelope = { page: 'request/detail', csrf, data: {
      workspacePrincipal: { roles: ['EMPLOYEE', 'APPROVER', 'ATTENDANCE_ADMIN'] },
      workspaceEmployee: today.data.workspaceEmployee, navigationFrom: '/company/requests',
      request: { id: 'r-1', targetEmployeeName: '대상', kind: 'LEAVE_DAY', fromDate: '2026-11-02', toDate: '2026-11-02', status: 'APPROVED', effectStatus: 'EFFECTIVE', quantityDays: 1, approverName: '담당', reason: '합성', version: 0 },
      detail: { days: [], history: [], correction: null }, candidates: [], pageCommand: 'cmd', admin: true, canDecide: false, canWithdraw: false, canCancel: false,
    } };
    const returned = { ...detail, data: { ...detail.data, navigationFrom: undefined } };
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValueOnce(response(problem('다른 화면'), '/me/profile')).mockResolvedValueOnce(response(returned, '/requests/r-1')));
    render(<App initial={detail} />);
    fireEvent.click(screen.getByRole('link', { name: '민감직원 합성회사' }));
    await screen.findByText('다른 화면');
    act(() => window.history.back());
    await screen.findByRole('heading', { name: '신청 상세' });
    expect(screen.getByRole('navigation', { name: '현재 위치' }).textContent).toContain('회사 신청 현황');
  });
  it('정정 신청 상세 모달을 열어도 배경에 정정 현황 메뉴와 경로를 유지한다', async () => {
    const listPath = '/admin/requests?scope=CORRECTION';
    const detailPath = '/requests/r-correction';
    const title = '출퇴근 정정 현황';
    const request = { id: 'r-correction', targetEmployeeName: '대상', kind: 'ATTENDANCE_CORRECTION',
      fromDate: '2026-11-02', toDate: '2026-11-02', status: 'APPROVED', effectStatus: 'EFFECTIVE',
      quantityDays: 0, approverName: '담당', reason: '합성', version: 0 };
    const context = { workspacePrincipal: employees.data.workspacePrincipal, workspaceEmployee: employees.data.workspaceEmployee };
    const detail: PageEnvelope = { page: 'request/detail', csrf, data: {
      ...context, request, detail: { days: [], history: [], correction: null }, candidates: [], admin: true, pageCommand: 'cmd',
    } };
    window.history.replaceState(null, '', listPath);
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(response(detail, detailPath)));
    const view = render(<App initial={{ page: 'request/list', csrf, data: { ...context, title, requestScope: 'CORRECTION', requests: [request] } }} />);

    fireEvent.click(await screen.findByRole('link', { name: '출퇴근·휴게 정정' }));
    await screen.findByRole('dialog');
    expect(location.pathname).toBe(detailPath);
    expect(view.container.querySelector(`#workspace-menu a[href="${listPath}"]`)?.getAttribute('aria-current')).toBe('page');
    expect(view.container.querySelector('.t-location-breadcrumbs [aria-current="page"]')?.textContent).toBe(title);
  });

  it('인사기본의 입사 예정 관리는 독립 페이지로 연다', async () => {
    const path = '/admin/employees/onboarding';
    const result: PageEnvelope = { page: 'org/onboarding', csrf, data: {
      workspacePrincipal: employees.data.workspacePrincipal, workspaceEmployee: employees.data.workspaceEmployee,
      onboarding: { today: '2026-11-17', rows: [], page: 0, organizations: [], scheduleVersions: [], form: { commandId: 'cmd' } },
    } };
    window.history.replaceState(null, '', '/admin/employees');
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(response(result, path)));
    render(<App initial={{ ...employees, data: { ...employees.data,
      employeeSheet: { commandId: 'sheet-command', reason: '', rows: [], latestRows: [], errors: [] },
    } }} />);

    fireEvent.click(within(screen.getByRole('main')).getByRole('link', { name: '입사 예정 관리' }));
    await screen.findByRole('heading', { name: '입사 예정 관리', level: 1 });
    expect(location.pathname).toBe(path);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('사번 변경은 데모 상단에서만 표시하고 열기 전에는 계정을 조회하지 않는다', () => {
    const fetcher = vi.fn<typeof fetch>();
    vi.stubGlobal('fetch', fetcher);
    const view = render(<App initial={{ ...today, data: { ...today.data, workspaceDemo: true } }} />);
    const switchAccount = screen.getByRole('button', { name: '사번 변경' });
    expect(switchAccount.getAttribute('aria-haspopup')).toBe('dialog');
    expect(switchAccount.nextElementSibling).toBe(screen.getByRole('button', { name: '로그아웃' }).closest('form'));
    expect(fetcher).not.toHaveBeenCalled();
    view.unmount();
    render(<App initial={today} />);
    expect(screen.queryByRole('button', { name: '사번 변경' })).toBeNull();
  });
  it('데모 업무 화면의 상단과 사이드바에는 하나의 데모 이름을 사용한다', () => {
    const workspaceEmployee = { employeeName: '담당자', companyName: '로컬 합성 데모' };
    const view = render(<App initial={{ ...today, data: { ...today.data, workspaceEmployee, workspaceDemo: true } }} />);

    expect(screen.getByRole('link', { name: /담당자\s*WorkLog 데모/ })).toBeTruthy();
    expect(within(screen.getByRole('complementary', { name: '주 메뉴' })).getAllByText('WorkLog 데모')).toHaveLength(2);
    expect(within(screen.getByRole('region', { name: '사원 인사정보' })).getByText('WorkLog 데모')).toBeTruthy();
    expect(screen.queryByText('로컬 합성 데모')).toBeNull();
    expect(screen.queryByText('로컬 데모 워크스페이스')).toBeNull();

    view.unmount();
    render(<App initial={today} />);
    expect(screen.getByRole('link', { name: /민감직원\s*합성회사/ })).toBeTruthy();
    expect(screen.queryByText('WorkLog 데모')).toBeNull();
  });
  it('데모 홈은 보유한 담당자 권한을 기본 선택하고 실제 업무 경로와 일반 홈의 선택은 유지한다', () => {
    const data = { ...today.data, workspaceDemo: true, workspacePrincipal: { roles: ['EMPLOYEE', 'ATTENDANCE_ADMIN'] } };
    window.history.replaceState(null, '', '/me/home');
    const view = render(<App initial={{ ...today, page: 'workspace/home', data }} />);
    expect((screen.getByRole('combobox', { name: '업무 권한' }) as HTMLSelectElement).value).toBe('ATTENDANCE_ADMIN');
    expect(screen.getByRole('link', { name: '권한 관리', hidden: true })).toBeTruthy();
    view.unmount();
    window.history.replaceState(null, '', '/me/attendance');
    const attendance = render(<App initial={{ ...today, data }} />);
    expect((screen.getByRole('combobox', { name: '업무 권한' }) as HTMLSelectElement).value).toBe('EMPLOYEE');
    attendance.unmount();
    const normal = render(<App initial={{ ...today, page: 'workspace/home', data: { ...data, workspaceDemo: false } }} />);
    expect((screen.getByRole('combobox', { name: '업무 권한' }) as HTMLSelectElement).value).toBe('EMPLOYEE');
    normal.unmount();
    render(<App initial={{ ...today, page: 'workspace/home', data: { ...data, workspacePrincipal: { roles: ['EMPLOYEE'] } } }} />);
    expect(screen.queryByRole('option', { name: '담당자' })).toBeNull();
  });
  it('사원 선택만으로 인증을 바꾸지 않고 현재 CSRF로 정상 문서 POST 전환한다', async () => {
    const accounts = [
      { employeeNo: '99999', employeeName: '담당자', roles: ['EMPLOYEE', 'ATTENDANCE_ADMIN'], password: 'synthetic-admin' },
      { employeeNo: '26001', employeeName: '승인자', roles: ['EMPLOYEE', 'APPROVER'], password: 'synthetic-approver' },
    ];
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response({ page: 'demo', csrf: { ...csrf, token: 'do-not-replace-current-csrf' }, data: { demoAccounts: accounts } }, '/demo'));
    vi.stubGlobal('fetch', fetcher);
    window.history.replaceState(null, '', '/me/attendance');
    render(<App initial={{ ...today, data: { ...today.data, workspaceDemo: true, employeeHeader: {
      employeeId: 'e-1', employeeNo: '99999', employeeName: '담당자', companyName: '합성회사', employmentStatus: 'WORKING', joinedOn: '2026-01-01', asOf: '2026-11-09T00:00:00Z',
    } } }} />);
    const trigger = screen.getByRole('button', { name: '사번 변경' });
    trigger.focus();
    fireEvent.click(trigger);
    const dialog = screen.getByRole('dialog', { name: '사번 변경' });
    expect(document.activeElement).toBe(within(dialog).getByRole('heading', { name: '사번 변경' }));
    const select = await within(dialog).findByRole('combobox', { name: '테스트 사원' });
    expect(fetcher.mock.calls[0][0]).toBe('/demo');
    assertPageRequest(fetcher.mock.calls[0][1]);
    expect((select as HTMLSelectElement).value).toBe('99999');
    expect((within(dialog).getByRole('button', { name: '선택한 사원으로 전환' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(select, { target: { value: '26001' } });
    expect(fetcher).toHaveBeenCalledTimes(1);
    const form = within(dialog).getByRole('button', { name: '선택한 사원으로 전환' }).closest('form')!;
    expect(form.action).toBe(new URL('/login', location.href).href);
    expect(form.method).toBe('post');
    const values = new FormData(form);
    expect(values.get('email')).toBe('26001');
    expect(values.get('password')).toBe('synthetic-approver');
    expect(values.get('_csrf')).toBe(csrf.token);
    expect(form.querySelector('input[type="password"]')).toBeNull();
    expect(fireEvent.submit(form)).toBe(true);
    expect(fireEvent.submit(form)).toBe(false);
    expect(fetcher).toHaveBeenCalledTimes(1);
    fireEvent(dialog, new Event('cancel', { cancelable: true }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });
  it('사번 목록 조회를 닫으면 취소하고 늦은 응답은 화면에 남기지 않는다', async () => {
    const pendingAccounts = deferred<Response>();
    const fetcher = vi.fn<typeof fetch>().mockReturnValue(pendingAccounts.promise);
    vi.stubGlobal('fetch', fetcher);
    render(<App initial={{ ...today, data: { ...today.data, workspaceDemo: true } }} />);
    fireEvent.click(screen.getByRole('button', { name: '사번 변경' }));
    expect(within(screen.getByRole('dialog', { name: '사번 변경' })).getByRole('status').textContent).toContain('불러오는 중');
    const signal = fetcher.mock.calls[0][1]?.signal as AbortSignal;
    fireEvent.click(screen.getAllByRole('button', { name: '닫기' })[0]);
    expect(signal.aborted).toBe(true);
    await act(async () => pendingAccounts.resolve(response({ page: 'demo', csrf, data: { demoAccounts: [] } }, '/demo')));
    expect(screen.queryByRole('dialog')).toBeNull();
  });
  it.each(['failed', 'empty', 'unexpected'] as const)('사번 목록 %s 응답은 전환을 막고 다시 조회할 수 있다', async kind => {
    const fetcher = vi.fn<typeof fetch>();
    if (kind === 'failed') fetcher.mockRejectedValueOnce(new Error('offline'));
    else fetcher.mockResolvedValueOnce(response({ page: kind === 'unexpected' ? 'login' : 'demo', csrf, data: { demoAccounts: [] } }, '/demo'));
    fetcher.mockResolvedValueOnce(response({ page: 'demo', csrf, data: { demoAccounts: [{ employeeNo: '26001', employeeName: '승인자', roles: ['EMPLOYEE'], password: 'synthetic' }] } }, '/demo'));
    vi.stubGlobal('fetch', fetcher);
    render(<App initial={{ ...today, data: { ...today.data, workspaceDemo: true } }} />);
    fireEvent.click(screen.getByRole('button', { name: '사번 변경' }));
    await screen.findByRole('alert');
    expect(screen.queryByRole('button', { name: '선택한 사원으로 전환' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 불러오기' }));
    const select = await screen.findByRole('combobox', { name: '테스트 사원' });
    expect((select as HTMLSelectElement).value).toBe('');
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it('다른 사원의 인사 헤더를 현재 로그인 사번으로 오인하지 않고 미저장 취소를 존중한다', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(response({ page: 'demo', csrf, data: { demoAccounts: [{ employeeNo: '26001', employeeName: '조회대상', roles: ['EMPLOYEE'], password: 'synthetic' }] } }, '/demo')));
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    window.history.replaceState(null, '', '/admin/employees');
    const view = render(<App initial={{ ...today, data: { ...today.data, workspaceDemo: true, employeeHeader: {
      employeeId: 'e-2', employeeNo: '26001', employeeName: '조회대상', companyName: '합성회사', employmentStatus: 'WORKING', joinedOn: '2026-01-01', asOf: '2026-11-09T00:00:00Z',
    } } }} />);
    fireEvent.click(screen.getByRole('button', { name: '사번 변경' }));
    const select = await screen.findByRole('combobox', { name: '테스트 사원' });
    expect((select as HTMLSelectElement).value).toBe('');
    fireEvent.change(select, { target: { value: '26001' } });
    view.container.querySelector('main')!.setAttribute('data-unsaved-changes', 'true');
    const form = screen.getByRole('button', { name: '선택한 사원으로 전환' }).closest('form')!;
    expect(fireEvent.submit(form)).toBe(false);
    expect(confirm).toHaveBeenCalledOnce();
    expect(location.pathname).toBe('/admin/employees');
    confirm.mockReturnValue(true);
    expect(fireEvent.submit(form)).toBe(true);
    expect(fireEvent.submit(form)).toBe(false);
    const beforeUnload = new Event('beforeunload', { cancelable: true });
    beforeUnload.preventDefault();
    fireEvent(window, beforeUnload);
    await act(async () => new Promise(resolve => setTimeout(resolve, 0)));
    expect(fireEvent.submit(form)).toBe(true);
  });
  it('공통 메뉴와 상단 이름에서 본인 내 정보로 이동할 수 있다', () => {
    render(<App initial={today} />);
    fireEvent.click(screen.getByRole('button', { name: '인사정보' }));
    expect(screen.getByRole('link', { name: '내 정보' }).getAttribute('href')).toBe('/me/profile');
    expect(screen.getByRole('link', { name: '민감직원 합성회사' }).getAttribute('href')).toBe('/me/profile');
  });

  it('상단 WorkLog 로고는 업무 메뉴를 열지 않고 홈으로 이동한다', async () => {
    window.history.replaceState(null, '', '/admin/employees');
    vi.setSystemTime(new Date('2026-10-26T01:00:00Z'));
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response(homeOn('2026-10-26'), '/me/home'));
    vi.stubGlobal('fetch', fetcher);
    render(<App initial={employees} />);
    const menu = screen.getByRole('button', { name: '업무 메뉴 열기' });
    const topbar = within(menu.closest('header')!);
    const sidebar = within(screen.getByRole('complementary', { name: '주 메뉴' }));
    expect(sidebar.getByRole('link', { name: /WorkLog/ }).getAttribute('href')).toBe('/me/home');
    expect(menu.getAttribute('aria-expanded')).toBe('false');
    expect(screen.getByRole('region', { name: '사원 인사정보' }).textContent).toContain('합성회사');
    fireEvent.click(topbar.getByRole('link', { name: 'WorkLog 홈으로' }));
    await screen.findByRole('heading', { name: '민감직원님, 안녕하세요' });
    expect(location.pathname).toBe('/me/home');
    expect(topbar.getByRole('link', { name: 'WorkLog 홈으로' }).getAttribute('aria-current')).toBe('page');
  });

  it('데스크톱 상단 버튼으로 사이드 메뉴를 닫고 다시 연다', () => {
    render(<App initial={today} />);
    const shell = screen.getByRole('complementary', { name: '주 메뉴' }).closest('.t-app-shell')!;
    fireEvent.click(screen.getByRole('button', { name: '사이드 메뉴 닫기' }));
    expect(shell.getAttribute('data-sidebar-collapsed')).toBe('true');
    const reopen = screen.getByRole('button', { name: '사이드 메뉴 열기' });
    expect(reopen.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(reopen);
    expect(shell.getAttribute('data-sidebar-collapsed')).toBe('false');
  });

  it('홈에는 인사 헤더 없이 근무 대시보드만 표시한다', () => {
    vi.setSystemTime(new Date('2026-10-26T01:00:00Z'));
    render(<App initial={homeOn('2026-10-26')} />);
    expect(screen.getByRole('heading', { name: '민감직원님, 안녕하세요' })).toBeTruthy();
    expect(screen.queryByRole('region', { name: '사원 인사정보' })).toBeNull();
  });

  it.each(['서울 자정', '비활성 탭에서 복귀'])('%s에 홈의 근무일과 출퇴근 명령을 새로 받는다', async trigger => {
    window.history.replaceState(null, '', '/me/home?clockError=409');
    const historyLength = window.history.length;
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-12-31T14:59:59Z'));
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response(homeOn('2027-01-01'), '/me/home'));
    vi.stubGlobal('fetch', fetcher);
    const previous = homeOn('2026-12-31', '2026-12-31T00:00:00Z');
    render(<App initial={{ ...previous, data: { ...previous.data, clockError: 409 } }} />);

    expect(screen.getByRole<HTMLButtonElement>('button', { name: '출근 09:00:00' }).disabled).toBe(true);
    expect(screen.getByRole('alert').textContent).toBe('오늘 이미 기록했습니다.');
    expect(fetcher).not.toHaveBeenCalled();
    if (trigger === '서울 자정') {
      await act(async () => { await vi.advanceTimersByTimeAsync(999); });
      expect(fetcher).not.toHaveBeenCalled();
      await act(async () => { await vi.advanceTimersByTimeAsync(1); });
      expect(fetcher).toHaveBeenCalledTimes(1);
    } else {
      visibility.mockReturnValue('hidden');
      fireEvent(document, new Event('visibilitychange'));
      vi.setSystemTime(new Date('2027-01-01T01:00:00Z'));
      expect(fetcher).not.toHaveBeenCalled();
      visibility.mockReturnValue('visible');
    }
    await act(async () => {
      fireEvent(document, new Event('visibilitychange'));
      fireEvent.focus(window);
    });

    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(new URL(String(fetcher.mock.calls[0][0]), location.href).pathname).toBe('/me/home');
    expect(new URL(String(fetcher.mock.calls[0][0]), location.href).search).toBe('');
    assertPageRequest(fetcher.mock.calls[0][1]);
    expect(history.length).toBe(historyLength);
    const attendance = within(screen.getByRole('region', { name: '오늘 근무' }));
    expect(attendance.queryByRole('alert')).toBeNull();
    expect(attendance.getByText('01.01').getAttribute('datetime')).toBe('2027-01-01');
    for (const [name, kind] of [['출근', 'in'], ['퇴근', 'out']]) {
      const button = attendance.getByRole<HTMLButtonElement>('button', { name });
      expect(button.disabled).toBe(false);
      expect(new FormData(button.form!).get('commandId')).toBe(`2027-01-01-${kind}`);
    }
  });

  it('대표는 회사 현황을 조회하며 관리자·결재 기능을 자동으로 얻지 않는다', () => {
    const view = render(<App initial={{
      ...today,
      data: { ...today.data, workspacePrincipal: { roles: ['EMPLOYEE', 'COMPANY_REPRESENTATIVE'] } },
    }} />);
    fireEvent.change(screen.getByRole('combobox', { name: '업무 권한' }), { target: { value: 'COMPANY_REPRESENTATIVE' } });
    fireEvent.click(screen.getByRole('button', { name: '근태 운영' }));
    fireEvent.click(screen.getByRole('button', { name: '회사 현황' }));
    expect(screen.getByRole('link', { name: '회사 근무현황' }).getAttribute('href')).toBe('/company/attendance');
    expect(screen.getByRole('link', { name: '회사 정정 현황' }).getAttribute('href')).toBe('/company/requests?scope=CORRECTION');
    fireEvent.click(screen.getByRole('button', { name: '휴가관리' }));
    fireEvent.click(screen.getByRole('button', { name: '신청·연차' }));
    expect(screen.getByRole('link', { name: '회사 휴가 신청 현황' }).getAttribute('href')).toBe('/company/requests?scope=LEAVE');
    expect(screen.queryByRole('link', { name: '권한 관리', hidden: true })).toBeNull();
    expect(screen.queryByRole('link', { name: '결재 대기', hidden: true })).toBeNull();
    expect(view.container.querySelector('.t-workspace-name small')?.textContent).toBe('공통 · 대표');
  });

  it('동시 보유한 역할도 선택한 권한의 메뉴만 표시한다', () => {
    const view = render(<App initial={{
      ...today,
      data: { ...today.data, workspacePrincipal: { roles: ['EMPLOYEE', 'APPROVER', 'ATTENDANCE_ADMIN', 'COMPANY_REPRESENTATIVE'] } },
    }} />);
    expect(view.container.querySelector('.t-workspace-name small')?.textContent).toBe('공통 · 관리자 · 대표 · 결재 담당 가능');
    fireEvent.change(screen.getByRole('combobox', { name: '업무 권한' }), { target: { value: 'ATTENDANCE_ADMIN' } });
    expect(screen.getByRole('link', { name: '권한 관리', hidden: true }).getAttribute('href')).toBe('/admin/access');
    expect(screen.getAllByRole('link', { name: '회사 근무현황', hidden: true })).toHaveLength(1);
    expect(screen.queryByRole('link', { name: '결재 대기', hidden: true })).toBeNull();
  });

  it('로그인 제출에 원래 입력·CSRF를 보존하고 응답 대기 중 이중 제출을 막는다', async () => {
    const pending = deferred<Response>();
    const fetcher = vi.fn<typeof fetch>().mockReturnValue(pending.promise);
    vi.stubGlobal('fetch', fetcher);
    const view = render(<App initial={login} />);
    fireEvent.change(screen.getByLabelText('회사 코드'), { target: { value: 'sample-company' } });
    fireEvent.change(screen.getByLabelText('사번 또는 이메일'), {
      target: { value: 'employee+test@example.test' },
    });
    fireEvent.change(screen.getByLabelText('비밀번호'), {
      target: { value: 'correct horse +&= 1234' },
    });
    const form = view.container.querySelector('form[action="/login"]') as HTMLFormElement;
    const button = screen.getByRole('button', { name: '로그인' }) as HTMLButtonElement;
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(button.disabled).toBe(true);
    const [target, options] = fetcher.mock.calls[0];
    expect(new URL(String(target), window.location.href).pathname).toBe('/login');
    expect(options?.method?.toUpperCase()).toBe('POST');
    assertPageRequest(options);
    expect(options?.body).toBeInstanceOf(URLSearchParams);
    const body = options?.body as URLSearchParams;
    expect(body.get('companyCode')).toBe('sample-company');
    expect(body.get('email')).toBe('employee+test@example.test');
    expect(body.get('password')).toBe('correct horse +&= 1234');
    expect(body.get('_csrf')).toBe(csrf.token);
    await act(async () => {
      pending.resolve(response(problem('로그인 처리 완료'), '/after-login'));
    });
    await screen.findByText('로그인 처리 완료');
  });

  it('일반 링크를 JSON 탐색과 pushState로 처리하고 뒤로 가면 해당 주소를 재조회한다', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response(login, '/login'))
      .mockResolvedValueOnce(response(problem('이전 화면 재조회'), '/initial'));
    vi.stubGlobal('fetch', fetcher);
    const pushed = vi.spyOn(window.history, 'pushState');
    render(<App initial={problem('초기 화면')} />);
    fireEvent.click(screen.getByRole('link', { name: '로그인으로' }));
    await screen.findByRole('heading', { name: '로그인' });
    expect(window.location.pathname).toBe('/login');
    expect(
      pushed.mock.calls.some(
        (call) => new URL(String(call[2]), window.location.href).pathname === '/login',
      ),
    ).toBe(true);
    expect(new URL(String(fetcher.mock.calls[0][0]), window.location.href).pathname).toBe('/login');
    assertPageRequest(fetcher.mock.calls[0][1]);
    act(() => {
      window.history.back();
    });
    await screen.findByText('이전 화면 재조회');
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(window.location.pathname).toBe('/initial');
    expect(new URL(String(fetcher.mock.calls[1][0]), window.location.href).pathname).toBe(
      '/initial',
    );
    assertPageRequest(fetcher.mock.calls[1][1]);
  });

  it('연속 GET의 응답 순서가 뒤집혀도 최신 탐색의 화면과 주소를 유지한다', async () => {
    const earlier = deferred<Response>();
    const latest = deferred<Response>();
    const fetcher = vi
      .fn<typeof fetch>()
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(latest.promise);
    vi.stubGlobal('fetch', fetcher);
    window.history.replaceState(null, '', '/admin/employees');
    render(<App initial={employees} />);
    const first = screen.getByRole('link', { name: '사원 상세·수정' });
    const second = screen.getByRole('link', { name: '계정 초대·역할·승인자 관리' });
    act(() => {
      fireEvent.click(first);
      fireEvent.click(second);
    });
    expect(fetcher).toHaveBeenCalledTimes(2);
    await act(async () => {
      latest.resolve(response(problem('최신 탐색 응답'), '/admin/employees/e-1/access'));
    });
    await screen.findByText('최신 탐색 응답');
    await act(async () => {
      earlier.resolve(response(problem('오래된 탐색 응답'), '/admin/employees/e-1'));
    });
    expect(screen.queryByText('오래된 탐색 응답')).toBeNull();
    expect(screen.getByText('최신 탐색 응답')).toBeTruthy();
    expect(window.location.pathname).toBe('/admin/employees/e-1/access');
    for (const call of fetcher.mock.calls) assertPageRequest(call[1]);
  });

  it.each([
    ['뒤로가기', '503'],
    ['뒤로가기', '네트워크 오류'],
    ['앞으로가기', '503'],
    ['앞으로가기', '네트워크 오류'],
  ] as const)(
    '%s 조회가 %s로 실패하면 편집 화면의 URL과 입력·명령을 유지한다',
    async (direction, failure) => {
      const displayed = '/admin/employees?status=active#employee-detail';
      const target = '/admin/organizations?q=다른조직';
      if (direction === '뒤로가기') {
        window.history.replaceState(null, '', target);
        window.history.pushState(null, '', displayed);
      } else {
        window.history.replaceState(null, '', displayed);
        window.history.pushState(null, '', target);
        await new Promise<void>((resolve) => {
          window.addEventListener('popstate', () => resolve(), { once: true });
          window.history.back();
        });
      }
      const fetcher = vi.fn<typeof fetch>();
      if (failure === '503') fetcher.mockResolvedValue(response(problem('일시 장애'), target, 503));
      else fetcher.mockRejectedValue(new TypeError('Failed to fetch'));
      vi.stubGlobal('fetch', fetcher);
      const view = render(<App initial={employees} />);
      const name = screen.getByLabelText('이름') as HTMLInputElement;
      fireEvent.change(name, { target: { value: '보존할 직원 입력' } });
      const form = view.container.querySelector(
        'form[action="/admin/employees"][method="post"]',
      ) as HTMLFormElement;
      const command = new FormData(form).get('commandId');
      expect(command).toBe('create-command');
      const pushed = vi.spyOn(window.history, 'pushState');
      const replaced = vi.spyOn(window.history, 'replaceState');
      act(() => {
        if (direction === '뒤로가기') window.history.back();
        else window.history.forward();
      });
      await screen.findByRole('alert');
      expect(window.location.href).toBe(new URL(displayed, window.location.origin).href);
      expect(replaced).toHaveBeenCalledWith(null, '', window.location.href);
      expect(pushed).not.toHaveBeenCalled();
      expect(screen.getByLabelText('이름')).toBe(name);
      expect(name.value).toBe('보존할 직원 입력');
      expect(new FormData(form).get('commandId')).toBe(command);
      expect(fetcher).toHaveBeenCalledTimes(1);
      assertPageRequest(fetcher.mock.calls[0][1]);
    },
  );

  it.each(['503', '네트워크 오류'] as const)(
    '일반 링크 조회가 %s로 실패하면 기존 주소·입력을 유지하고 히스토리를 바꾸지 않는다',
    async (failure) => {
      const displayed = '/admin/employees?status=active#employee-detail';
      window.history.replaceState(null, '', displayed);
      const fetcher = vi.fn<typeof fetch>();
      if (failure === '503')
        fetcher.mockResolvedValue(response(problem('일시 장애'), '/admin/employees/e-1', 503));
      else fetcher.mockRejectedValue(new TypeError('Failed to fetch'));
      vi.stubGlobal('fetch', fetcher);
      render(<App initial={employees} />);
      fireEvent.change(screen.getByLabelText('이름'), { target: { value: '보존할 직원 입력' } });
      const pushed = vi.spyOn(window.history, 'pushState');
      const replaced = vi.spyOn(window.history, 'replaceState');
      fireEvent.click(screen.getByRole('link', { name: '사원 상세·수정' }));
      await screen.findByRole('alert');
      expect(window.location.href).toBe(new URL(displayed, window.location.origin).href);
      expect((screen.getByLabelText('이름') as HTMLInputElement).value).toBe('보존할 직원 입력');
      expect(pushed).not.toHaveBeenCalled();
      expect(replaced).not.toHaveBeenCalled();
    },
  );

  it.each([409, 422, 503, '네트워크 오류', '저장 성공'] as const)(
    '뒤로가기 조회 중 표시 폼 POST는 먼저 주소를 복구하고 %s 결과를 유지한다',
    async (outcome) => {
      const displayed = '/admin/employees?status=active#employee-detail';
      const earlier = deferred<Response>();
      const saving = deferred<Response>();
      const fetcher = vi
        .fn<typeof fetch>()
        .mockReturnValueOnce(earlier.promise)
        .mockReturnValueOnce(saving.promise);
      vi.stubGlobal('fetch', fetcher);
      window.history.replaceState(null, '', '/admin/organizations');
      window.history.pushState(null, '', displayed);
      const view = render(<App initial={employees} />);
      fireEvent.change(screen.getByLabelText('이름'), { target: { value: '보존할 직원 입력' } });
      act(() => window.history.back());
      await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
      const pushed = vi.spyOn(window.history, 'pushState');
      const replaced = vi.spyOn(window.history, 'replaceState');
      fireEvent.submit(
        view.container.querySelector('form[action="/admin/employees"][method="post"]')!,
      );
      expect(window.location.href).toBe(new URL(displayed, window.location.origin).href);
      expect(replaced).toHaveBeenCalledWith(null, '', window.location.href);
      expect(pushed).not.toHaveBeenCalled();
      expect(fetcher.mock.calls[0][1]?.signal?.aborted).toBe(true);
      expect(fetcher.mock.calls[1][1]?.method).toBe('POST');
      const body = fetcher.mock.calls[1][1]?.body as URLSearchParams;
      expect(body.get('commandId')).toBe('create-command');
      expect(body.get('displayName')).toBe('보존할 직원 입력');
      const invalid: PageEnvelope = {
        ...employees,
        data: {
          ...employees.data,
          form: { ...(employees.data.form as object), displayName: '보존할 직원 입력' },
          error: '저장 입력을 확인해 주세요.',
        },
      };
      await act(async () => {
        if (outcome === '네트워크 오류') saving.reject(new TypeError('Failed to fetch'));
        else if (outcome === '저장 성공')
          saving.resolve(response(problem('저장 완료'), '/admin/employees/e-2'));
        else if (outcome === 503)
          saving.resolve(response(problem('저장 일시 장애'), '/admin/employees', 503));
        else saving.resolve(response(invalid, '/admin/employees', outcome));
      });
      if (outcome === '저장 성공') {
        await screen.findByText('저장 완료');
        expect(window.location.pathname).toBe('/admin/employees/e-2');
        expect(pushed).toHaveBeenCalledTimes(1);
      } else {
        await screen.findByRole('alert');
        expect(window.location.href).toBe(new URL(displayed, window.location.origin).href);
        expect((screen.getByLabelText('이름') as HTMLInputElement).value).toBe('보존할 직원 입력');
        const form = view.container.querySelector(
          'form[action="/admin/employees"][method="post"]',
        ) as HTMLFormElement;
        expect(new FormData(form).get('commandId')).toBe('create-command');
        expect(pushed).not.toHaveBeenCalled();
      }
      const resultUrl = window.location.href;
      await act(async () => {
        earlier.resolve(response(problem('취소된 이전 조회'), '/admin/organizations'));
      });
      expect(window.location.href).toBe(resultUrl);
      expect(screen.queryByText('취소된 이전 조회')).toBeNull();
    },
  );

  it.each(['503', '네트워크 오류'] as const)(
    '뒤로가기 조회 중 기존 화면의 링크가 %s로 실패해도 표시 주소·입력을 유지한다',
    async (failure) => {
      const displayed = '/admin/employees?status=active#employee-detail';
      const earlier = deferred<Response>();
      const fetcher = vi.fn<typeof fetch>().mockReturnValueOnce(earlier.promise);
      if (failure === '503')
        fetcher.mockResolvedValueOnce(response(problem('일시 장애'), '/admin/employees/e-1', 503));
      else fetcher.mockRejectedValueOnce(new TypeError('Failed to fetch'));
      vi.stubGlobal('fetch', fetcher);
      window.history.replaceState(null, '', '/admin/organizations');
      window.history.pushState(null, '', displayed);
      render(<App initial={employees} />);
      fireEvent.change(screen.getByLabelText('이름'), { target: { value: '보존할 직원 입력' } });
      act(() => window.history.back());
      await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
      const pushed = vi.spyOn(window.history, 'pushState');
      const replaced = vi.spyOn(window.history, 'replaceState');
      fireEvent.click(screen.getByRole('link', { name: '사원 상세·수정' }));
      await screen.findByRole('alert');
      expect(window.location.href).toBe(new URL(displayed, window.location.origin).href);
      expect(replaced).toHaveBeenCalledTimes(1);
      expect(pushed).not.toHaveBeenCalled();
      expect((screen.getByLabelText('이름') as HTMLInputElement).value).toBe('보존할 직원 입력');
      await act(async () => {
        earlier.resolve(response(problem('취소된 이전 조회'), '/admin/organizations'));
      });
      expect(window.location.href).toBe(new URL(displayed, window.location.origin).href);
      expect(screen.queryByText('취소된 이전 조회')).toBeNull();
    },
  );

  it('뒤로가기 조회 중 표시 주소로 앞으로 가면 이전 GET을 취소하고 편집 상태를 유지한다', async () => {
    const displayed = '/admin/employees?status=active#employee-detail';
    const earlier = deferred<Response>();
    const fetcher = vi.fn<typeof fetch>().mockReturnValue(earlier.promise);
    vi.stubGlobal('fetch', fetcher);
    window.history.replaceState(null, '', '/admin/organizations');
    window.history.pushState(null, '', displayed);
    const view = render(<App initial={employees} />);
    fireEvent.change(screen.getByLabelText('이름'), { target: { value: '보존할 직원 입력' } });
    act(() => window.history.back());
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    expect(view.container.querySelector('#tna-app')?.getAttribute('aria-busy')).toBe('true');
    act(() => window.history.forward());
    await waitFor(() => expect(window.location.href).toBe(new URL(displayed, location.origin).href));
    expect(fetcher.mock.calls[0][1]?.signal?.aborted).toBe(true);
    expect(view.container.querySelector('#tna-app')?.getAttribute('aria-busy')).toBe('false');
    await act(async () => {
      earlier.resolve(response(problem('취소된 이전 조회'), '/admin/organizations'));
    });
    expect(window.location.href).toBe(new URL(displayed, window.location.origin).href);
    expect((screen.getByLabelText('이름') as HTMLInputElement).value).toBe('보존할 직원 입력');
    expect(screen.queryByText('취소된 이전 조회')).toBeNull();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('POST 대기 중 같은 문서의 hash popstate가 저장 요청을 취소하지 않는다', async () => {
    const saving = deferred<Response>();
    const fetcher = vi.fn<typeof fetch>().mockReturnValue(saving.promise);
    vi.stubGlobal('fetch', fetcher);
    window.history.replaceState(null, '', '/admin/employees');
    const view = render(<App initial={employees} />);
    const form = view.container.querySelector('form[action="/admin/employees"][method="post"]')!;
    fireEvent.submit(form);
    await act(async () => {
      window.history.pushState(null, '', '#employee-detail');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    expect(fetcher.mock.calls[0][1]?.signal?.aborted).toBe(false);
    expect(view.container.querySelector('#tna-app')?.getAttribute('aria-busy')).toBe('true');
    fireEvent.submit(form);
    expect(fetcher).toHaveBeenCalledTimes(1);
    await act(async () => saving.resolve(response(employees, '/admin/employees')));
  });

  it('오래된 뒤로가기 실패가 나중에 완료한 탐색의 URL을 복구하지 않는다', async () => {
    const earlier = deferred<Response>();
    const fetcher = vi
      .fn<typeof fetch>()
      .mockReturnValueOnce(earlier.promise)
      .mockResolvedValueOnce(response(problem('최신 탐색 응답'), '/admin/employees/e-1/access'));
    vi.stubGlobal('fetch', fetcher);
    window.history.replaceState(null, '', '/admin/organizations');
    window.history.pushState(null, '', '/admin/employees');
    render(<App initial={employees} />);
    act(() => window.history.back());
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('link', { name: '계정 초대·역할·승인자 관리' }));
    await screen.findByText('최신 탐색 응답');
    const replaced = vi.spyOn(window.history, 'replaceState');
    await act(async () => {
      earlier.resolve(response(problem('오래된 일시 장애'), '/admin/organizations', 503));
    });
    expect(window.location.pathname).toBe('/admin/employees/e-1/access');
    expect(screen.getByText('최신 탐색 응답')).toBeTruthy();
    expect(screen.queryByText('오래된 일시 장애')).toBeNull();
    expect(replaced).not.toHaveBeenCalled();
  });

  it.each([401, 403, 503])(
    '뒤로가기 %s 인증 오류는 이전 개인정보 화면을 복구하지 않는다',
    async (status) => {
      const ended: PageEnvelope = {
        ...problem('다시 로그인해 주세요.'),
        data: {
          message: '다시 로그인해 주세요.',
          ...(status === 503 ? { reauthenticate: true } : {}),
        },
      };
      const fetcher = vi
        .fn<typeof fetch>()
        .mockResolvedValue(response(ended, '/admin/organizations', status));
      vi.stubGlobal('fetch', fetcher);
      window.history.replaceState(null, '', '/admin/organizations');
      window.history.pushState(null, '', '/admin/employees');
      render(<App initial={employees} />);
      const replaced = vi.spyOn(window.history, 'replaceState');
      await act(async () => {
        const navigated = new Promise<void>(resolve => window.addEventListener('popstate', () => resolve(), { once: true }));
        window.history.back();
        await navigated;
      });
      await screen.findByRole('alert');
      expect(window.location.pathname).toBe('/admin/organizations');
      expect(document.body.textContent).not.toContain('민감직원');
      expect(document.body.textContent).not.toContain('PRIVATE-007');
      expect(screen.queryByRole('table')).toBeNull();
      expect(replaced).not.toHaveBeenCalled();
    },
  );

  it('403이면 이전 직원 목록과 헤더의 개인정보를 지우고 안전한 오류를 표시한다', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        response(
          problem('권한이 회수되었습니다. 다시 로그인해 주세요.'),
          '/admin/employees/e-1',
          403,
        ),
      );
    vi.stubGlobal('fetch', fetcher);
    window.history.replaceState(null, '', '/admin/employees');
    render(<App initial={employees} />);
    expect(document.body.textContent).toContain('민감직원');
    expect(document.body.textContent).toContain('PRIVATE-007');
    fireEvent.click(screen.getByRole('link', { name: '사원 상세·수정' }));
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toMatch(/권한|로그인/);
    await waitFor(() => {
      expect(document.body.textContent).not.toContain('민감직원');
      expect(document.body.textContent).not.toContain('PRIVATE-007');
      expect(screen.queryByRole('table')).toBeNull();
    });
    assertPageRequest(fetcher.mock.calls[0][1]);
  });

  it('같은 문서의 hash popstate는 서버 재조회나 입력 초기화를 하지 않는다', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(response(employees, '/admin/employees#employee-detail'));
    vi.stubGlobal('fetch', fetcher);
    window.history.replaceState(null, '', '/admin/employees');
    render(<App initial={employees} />);
    fireEvent.change(screen.getByLabelText('이름'), { target: { value: '보존할 등록 입력' } });
    await act(async () => {
      window.history.pushState(null, '', '#employee-detail');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    expect(fetcher).not.toHaveBeenCalled();
    expect((screen.getByLabelText('이름') as HTMLInputElement).value).toBe('보존할 등록 입력');
  });

  it('직원 선택 응답의 fragment 대상으로 렌더 후 스크롤한다', async () => {
    const descriptor = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView');
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      writable: true,
      value() {},
    });
    const scroll = vi.spyOn(HTMLElement.prototype, 'scrollIntoView');
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(response(employees, '/admin/employees?employeeId=e-1#employee-detail'));
    vi.stubGlobal('fetch', fetcher);
    window.history.replaceState(null, '', '/admin/employees');
    try {
      const view = render(<App initial={employees} />);
      const selected = await waitFor(() => {
        const link = view.container.querySelector<HTMLAnchorElement>('a[href*="employeeId=e-1"]');
        expect(link).not.toBeNull();
        return link!;
      });
      fireEvent.click(selected);
      await waitFor(() => {
        expect(scroll.mock.contexts).toContain(document.getElementById('employee-detail'));
      });
      expect(window.location.hash).toBe('#employee-detail');
      expect(fetcher).toHaveBeenCalledTimes(1);
    } finally {
      scroll.mockRestore();
      if (descriptor) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', descriptor);
      else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView');
    }
  });

  it.each(['일반 링크', '상태 새로 고침', 'popstate'] as const)(
    '출근 POST 대기 중 %s 이동은 변경 요청을 취소하거나 중복 제출을 허용하지 않는다',
    async (action) => {
      const pending = deferred<Response>();
      const fetcher = vi
        .fn<typeof fetch>()
        .mockReturnValueOnce(pending.promise)
        .mockResolvedValue(response(problem('차단되지 않은 탐색'), '/me/schedule'));
      vi.stubGlobal('fetch', fetcher);
      window.history.replaceState(null, '', '/me/today');
      const view = render(<App initial={today} />);
      const form = view.container
        .querySelector('input[name="eventKind"][value="IN"]')!
        .closest('form')!;
      try {
        fireEvent.submit(form);
        const postSignal = fetcher.mock.calls[0][1]?.signal;
        if (action === '일반 링크')
          fireEvent.click(screen.getByRole('link', { name: '내 근무 일정 →' }));
        else if (action === '상태 새로 고침') {
          fireEvent.click(screen.getByText('환경 정보'));
          fireEvent.click(screen.getByRole('button', { name: '상태 새로 고침', hidden: true }));
        } else {
          await act(async () => {
            window.history.pushState(null, '', '/me/schedule');
            window.dispatchEvent(new PopStateEvent('popstate'));
          });
        }
        expect(postSignal?.aborted).toBe(false);
        expect(fetcher).toHaveBeenCalledTimes(1);
        expect(window.location.pathname).toBe('/me/today');
        fireEvent.submit(form);
        expect(fetcher).toHaveBeenCalledTimes(1);
        expect(
          (screen.getByRole('button', { name: '출근 기록' }) as HTMLButtonElement).disabled,
        ).toBe(true);
      } finally {
        await act(async () => {
          pending.resolve(response(today, '/me/today'));
        });
      }
    },
  );

  it('재인증을 요구하는 503은 종료된 세션의 개인정보 화면을 제거한다', async () => {
    const ended = {
      ...problem('세션을 확인할 수 없습니다. 다시 로그인해 주세요.'),
      data: { message: '세션을 확인할 수 없습니다. 다시 로그인해 주세요.', reauthenticate: true },
    };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(response(ended, '/admin/employees/e-1', 503));
    vi.stubGlobal('fetch', fetcher);
    window.history.replaceState(null, '', '/admin/employees');
    render(<App initial={employees} />);
    fireEvent.click(screen.getByRole('link', { name: '사원 상세·수정' }));
    await screen.findByRole('alert');
    await waitFor(() => {
      expect(document.body.textContent).not.toContain('민감직원');
      expect(document.body.textContent).not.toContain('PRIVATE-007');
      expect(screen.queryByRole('table')).toBeNull();
    });
  });

  it.each([409, 422])('수정 POST의 %s는 편집 입력과 기존 GET 주소·히스토리를 유지한다', async (status) => {
    const form = {
      ...privateEmployee,
      commandId: 'change-command',
      expectedVersion: 3,
      reason: '',
    };
    const detail: PageEnvelope = {
      page: 'org/employee',
      csrf,
      data: {
        workspacePrincipal: { roles: ['ATTENDANCE_ADMIN'] },
        workspaceEmployee: { employeeName: '담당자', companyName: '합성회사' },
        employee: privateEmployee,
        form,
        options: [],
        orgNames: {},
        assignments: [],
        assignmentForm: {
          commandId: 'assignment-command',
          expectedVersion: 3,
          organizationId: null,
          validFrom: null,
          reason: '',
        },
      },
    };
    const invalid: PageEnvelope = {
      ...detail,
      data: {
        ...detail.data,
        form: { ...form, displayName: '편집한 이름', reason: '업무상 정정' },
        error: '입력 내용을 다시 확인해 주세요.',
      },
    };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(response(invalid, '/admin/employees/e-1/changes', status));
    vi.stubGlobal('fetch', fetcher);
    window.history.replaceState(null, '', '/admin/employees/e-1');
    const pushed = vi.spyOn(window.history, 'pushState');
    const view = render(<App initial={detail} />);
    fireEvent.change(screen.getByLabelText('이름'), { target: { value: '편집한 이름' } });
    fireEvent.change(screen.getByLabelText('변경 사유'), { target: { value: '업무상 정정' } });
    fireEvent.submit(view.container.querySelector('form[action="/admin/employees/e-1/changes"]')!);
    await screen.findByText('입력 내용을 다시 확인해 주세요.');
    expect((screen.getByLabelText('이름') as HTMLInputElement).value).toBe('편집한 이름');
    expect((screen.getByLabelText('변경 사유') as HTMLTextAreaElement).value).toBe('업무상 정정');
    expect(window.location.pathname).toBe('/admin/employees/e-1');
    expect(pushed).not.toHaveBeenCalled();
  });
});
