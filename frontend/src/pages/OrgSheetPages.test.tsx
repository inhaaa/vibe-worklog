// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import type { PageEnvelope } from '../types';

const organization = { id: 'o-1', code: 'DEV', name: '개발팀', parentId: null, active: true, version: 3 };
const employee = { id: 'e-1', employeeNo: 'E001', displayName: '김직원', joinedOn: '2026-10-01', leftOn: null, active: true, version: 7 };
const orgRow = { ...organization, version: undefined, clientRowId: 'row-org', expectedVersion: 3, operation: 'UPDATE' };
const employeeRow = { ...employee, version: undefined, clientRowId: 'row-employee', expectedVersion: 7, operation: 'UPDATE' };
const csrf = { token: 'csrf', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
function page(kind: 'organizations' | 'employees' = 'organizations', overrides: Record<string, unknown> = {}): PageEnvelope {
  const isOrg = kind === 'organizations';
  return { page: `org/${kind}`, csrf, data: {
    workspacePrincipal: { roles: ['EMPLOYEE', 'ATTENDANCE_ADMIN'] },
    workspaceEmployee: { employeeName: '관리자', companyName: '합성회사' },
    organizations: [organization], employees: [employee], selectedEmployee: null, employeeOrganizations: { 'e-1': '개발팀' },
    options: [organization], orgNames: {}, pageEmployeeCount: 1, pageActiveCount: 1, pageInactiveCount: 0,
    q: '개발', page: 1, hasNext: true, organizationId: 'o-1', status: 'active',
    form: { commandId: 'old-command', reason: '', ...employee, ...organization },
    [isOrg ? 'organizationSheet' : 'employeeSheet']: {
      commandId: 'batch-command', reason: '', rows: [isOrg ? orgRow : employeeRow],
      latestRows: [isOrg ? organization : employee], errors: [],
    }, ...overrides,
  } };
}
function show(initial = page()) {
  const view = render(<App initial={initial} />);
  fireEvent.click(view.getByRole('button', { name: '내 업무' }));
  fireEvent.click(view.getByRole('button', { name: '인사정보' }));
  return view;
}
function bulkForm(view: ReturnType<typeof render>, kind = 'organizations') {
  return view.container.querySelector(`form[action="/admin/${kind}/bulk"]`) as HTMLFormElement;
}
function postedRows(view: ReturnType<typeof render>, kind = 'organizations') {
  return JSON.parse(String(new FormData(bulkForm(view, kind)).get('rows'))) as Record<string, unknown>[];
}
function response(body: PageEnvelope, status: number, path = '/admin/organizations/bulk') {
  const value = new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/vnd.worklog.page+json' } });
  Object.defineProperty(value, 'url', { value: new URL(path, location.href).href });
  return value;
}
beforeEach(() => { history.replaceState(null, '', '/admin/organizations?q=개발&page=1'); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe('조직·사원 시트 편집', () => {
  it('도구 모음과 고정 코드·사번을 표시하고 미변경 행은 전송하지 않는다', () => {
    const view = show();
    for (const label of ['조회', '입력', '삭제', '저장', '취소']) expect(view.getByRole('button', { name: label })).toBeTruthy();
    expect((view.getByLabelText('조직 코드 1') as HTMLInputElement).readOnly).toBe(true);
    expect(postedRows(view)).toEqual([]);
    expect((view.getByRole('button', { name: '저장' }) as HTMLButtonElement).disabled).toBe(true);
    expect(new FormData(bulkForm(view)).get('_csrf')).toBe('csrf');
  });

  it('새 행은 저장 전 추가·제거하며 취소는 조회값으로 복원한다', () => {
    const view = show();
    fireEvent.click(view.getByRole('button', { name: '입력' }));
    const code = view.getByLabelText('조직 코드 2') as HTMLInputElement;
    expect(code.readOnly).toBe(false);
    fireEvent.change(code, { target: { value: 'HR' } });
    fireEvent.change(view.getByLabelText('조직 이름 2'), { target: { value: '인사팀' } });
    expect(postedRows(view)[0]).toMatchObject({ operation: 'CREATE', code: 'HR', name: '인사팀' });
    fireEvent.click(view.getByLabelText('2행 선택'));
    fireEvent.click(view.getByRole('button', { name: '삭제' }));
    expect(view.queryByLabelText('조직 코드 2')).toBeNull();
    fireEvent.change(view.getByLabelText('조직 이름 1'), { target: { value: '개발본부' } });
    fireEvent.click(view.getByRole('button', { name: '취소' }));
    expect((view.getByLabelText('조직 이름 1') as HTMLInputElement).value).toBe('개발팀');
    expect(postedRows(view)).toEqual([]);
  });

  it('신규 사번은 선택 입력으로 자동 발급을 안내하고 기존 사번은 읽기 전용이다', () => {
    const view = show(page('employees'));
    expect((view.getByLabelText('사번 1') as HTMLInputElement).readOnly).toBe(true);
    fireEvent.click(view.getByRole('button', { name: '입력' }));
    const number = view.getByLabelText('사번 2') as HTMLInputElement;
    expect(number.required).toBe(false);
    expect(number.readOnly).toBe(false);
    expect(number.placeholder).toBe('비우면 입사연도별 자동 발급');
    expect(view.getByText(/사번을 비우면 입사연도 두 자리와 순번 세 자리로 자동 발급/)).toBeTruthy();
    fireEvent.change(view.getByLabelText('이름 2'), { target: { value: '자동 사원' } });
    fireEvent.change(view.getByLabelText('입사일 2'), { target: { value: '2026-11-03' } });
    expect(postedRows(view, 'employees')[0]).toMatchObject({ employeeNo: '', joinedOn: '2026-11-03', operation: 'CREATE' });
  });

  it('기존 행 삭제는 비활성 예정이고 취소하면 복구된다', () => {
    const view = show();
    fireEvent.click(view.getByLabelText('1행 선택'));
    fireEvent.click(view.getByRole('button', { name: '삭제' }));
    expect(view.getByText('비활성 예정')).toBeTruthy();
    expect(postedRows(view)[0]).toMatchObject({ id: 'o-1', operation: 'DEACTIVATE', expectedVersion: 3 });
    expect(view.getByText(/이력을 보존하고 비활성화/)).toBeTruthy();
    fireEvent.click(view.getByRole('button', { name: '취소' }));
    expect(view.queryByText('비활성 예정')).toBeNull();
  });

  it('변경 행만 보내고 수정 필드·변경 수를 표시한다', () => {
    const view = show(page('organizations', { organizationSheet: {
      commandId: 'batch-command', reason: '', rows: [orgRow, { ...orgRow, id: 'o-2', clientRowId: 'row-2', code: 'HR', name: '인사팀' }],
      latestRows: [organization, { ...organization, id: 'o-2', code: 'HR', name: '인사팀' }], errors: [],
    } }));
    fireEvent.change(view.getByLabelText('조직 이름 1'), { target: { value: '개발본부' } });
    expect(postedRows(view)).toHaveLength(1);
    expect(postedRows(view)[0].name).toBe('개발본부');
    expect(view.getByText('변경 1행')).toBeTruthy();
    expect(view.getByLabelText('조직 이름 1').getAttribute('data-changed')).toBe('true');
    expect(view.container.querySelector('[data-unsaved-changes="true"]')).toBeTruthy();
  });

  it('50행을 넘는 입력을 막고 이유를 알려준다', () => {
    const view = show(page('organizations', { organizationSheet: {
      commandId: 'batch-command', reason: '', rows: Array.from({ length: 49 }, (_, i) => ({ clientRowId: `new-${i}`, operation: 'CREATE', code: `N${i}`, name: '새 조직', parentId: null, active: true })), latestRows: [], errors: [],
    } }));
    fireEvent.click(view.getByRole('button', { name: '입력' }));
    expect((view.getByRole('button', { name: '입력' }) as HTMLButtonElement).disabled).toBe(true);
    expect(view.getByText(/한 번에 최대 50행/)).toBeTruthy();
    expect(postedRows(view)).toHaveLength(50);
  });

  it('사원 시트는 재직·활성만 편집하고 배정·계정 화면으로 연결한다', () => {
    const view = show(page('employees'));
    expect((view.getByLabelText('사번 1') as HTMLInputElement).readOnly).toBe(true);
    fireEvent.change(view.getByLabelText('이름 1'), { target: { value: '김수정' } });
    expect(postedRows(view, 'employees')[0]).toMatchObject({ displayName: '김수정', expectedVersion: 7, joinedOn: '2026-10-01' });
    expect(view.getByRole('link', { name: '김직원 소속·상세' }).getAttribute('href')).toBe('/admin/employees/e-1');
    expect(view.getByRole('link', { name: '김직원 근무 배정' }).getAttribute('href')).toBe('/admin/employees/e-1/schedule');
    const data = new FormData(bulkForm(view, 'employees'));
    expect(data.get('organizationId')).toBe('o-1');
    expect(data.get('status')).toBe('active');
  });

  it('미저장 링크·조회·뒤로가기 이동을 거절하면 현재 편집을 보존한다', () => {
    const fetcher = vi.fn<typeof fetch>(); vi.stubGlobal('fetch', fetcher);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const view = show();
    fireEvent.change(view.getByLabelText('조직 이름 1'), { target: { value: '편집 중' } });
    fireEvent.click(view.getByRole('link', { name: '내 정보' }));
    fireEvent.submit(view.container.querySelector('form[method="get"]')!);
    history.pushState(null, '', '/me/profile');
    fireEvent.popState(window);
    expect(confirm).toHaveBeenCalledTimes(3);
    expect(fetcher).not.toHaveBeenCalled();
    expect(location.pathname).toBe('/admin/organizations');
    expect((view.getByLabelText('조직 이름 1') as HTMLInputElement).value).toBe('편집 중');
  });

  it('불명확한503 재전송은 같은 명령을 유지하고 편집하면 새 명령을 발급한다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response({ page: 'org/problem', csrf, data: { message: '저장 결과 확인 필요' } }, 503));
    vi.stubGlobal('fetch', fetcher);
    const view = show();
    fireEvent.change(view.getByLabelText('조직 이름 1'), { target: { value: '새 이름' } });
    fireEvent.change(view.getByLabelText('일괄 변경 사유'), { target: { value: '업무 검토' } });
    fireEvent.submit(bulkForm(view));
    await waitFor(() => expect(view.getByText('저장 결과 확인 필요')).toBeTruthy());
    const first = (fetcher.mock.calls[0][1]?.body as URLSearchParams).get('commandId');
    fireEvent.submit(bulkForm(view));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect((fetcher.mock.calls[1][1]?.body as URLSearchParams).get('commandId')).toBe(first);
    await act(async () => {});
    fireEvent.change(view.getByLabelText('조직 이름 1'), { target: { value: '다시 수정' } });
    expect(new FormData(bulkForm(view)).get('commandId')).not.toBe(first);
  });

  it('422는 전체 입력·삭제예정·행 식별자를 복원하고 오류 필드로 초점을 보낸다', async () => {
    const initial = page();
    const failed = page('organizations', { organizationSheet: {
      commandId: 'posted-command', reason: '업무 검토', rows: [{ ...orgRow, name: '중복팀', operation: 'DEACTIVATE' }, { clientRowId: 'new-row', operation: 'CREATE', code: 'NEW', name: '신규팀', parentId: null, active: true }],
      latestRows: [organization], errors: [{ clientRowId: 'new-row', field: 'code', message: '조직 코드가 중복됩니다.' }],
    } });
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(response(failed, 422)));
    const view = show(initial);
    fireEvent.change(view.getByLabelText('조직 이름 1'), { target: { value: '중복팀' } });
    fireEvent.submit(bulkForm(view));
    await waitFor(() => expect(view.getByText('조직 코드가 중복됩니다.')).toBeTruthy());
    expect(view.getByText('비활성 예정')).toBeTruthy();
    expect((view.getByLabelText('조직 코드 2') as HTMLInputElement).value).toBe('NEW');
    expect(document.activeElement).toBe(view.getByLabelText('조직 코드 2'));
    expect(new FormData(bulkForm(view)).get('commandId')).toBe('posted-command');
    expect(postedRows(view).map(row => row.clientRowId)).toEqual(['row-org', 'new-row']);
  });

  it('409는 최신값과 편집값을 함께 보여주고 재확인은 최신 버전·새 명령을 사용한다', () => {
    const view = show(page('organizations', { organizationSheet: {
      commandId: 'conflicted-command', reason: '업무 검토', rows: [{ ...orgRow, name: '내 편집값' }],
      latestRows: [{ ...organization, name: '다른 관리자 저장값', version: 4 }],
      errors: [{ clientRowId: 'row-org', field: 'expectedVersion', message: '최신 버전을 확인하세요.' }],
    } }));
    expect(view.getByText('다른 관리자 저장값')).toBeTruthy();
    expect(view.getByText('내 편집값')).toBeTruthy();
    fireEvent.click(view.getByRole('button', { name: '최신값 확인 후 편집 유지' }));
    expect(postedRows(view)[0].expectedVersion).toBe(4);
    expect(new FormData(bulkForm(view)).get('commandId')).not.toBe('conflicted-command');
    expect((view.getByLabelText('조직 이름 1') as HTMLInputElement).value).toBe('내 편집값');
  });

  it('상위 조직·활성 필드 오류도 숨기지 않고 표시한다', () => {
    const view = show(page('organizations', { organizationSheet: {
      commandId: 'batch-command', reason: '검토', rows: [orgRow], latestRows: [organization],
      errors: [{ clientRowId: 'row-org', field: 'parentId', message: '상위 조직 순환입니다.' }, { clientRowId: 'row-org', field: 'active', message: '소속이 남아 있습니다.' }],
    } }));
    expect(view.getByText('상위 조직 순환입니다.')).toBeTruthy();
    expect(view.getByText('소속이 남아 있습니다.')).toBeTruthy();
  });

  it('조직·사원 시트에 현재 조직장·근무를 읽기 전용으로 표시한다', () => {
    const org = show(page('organizations', { organizationHeadsById: { 'o-1': { employeeName: '박조직장', eligible: false, approverEligible: false } } }));
    expect(org.container.querySelector('main')?.textContent).toContain('박조직장 · 재지정 필요');
    cleanup();
    const view = show(page('employees', { employeeContext: { 'e-1': { organizationName: '개발팀', organizationHeadName: '박조직장', organizationHeadEligible: true, organizationHeadApproverEligible: true, scheduleName: '고정 근무' } } }));
    const row = view.container.querySelector('[data-client-row-id="row-employee"]')!;
    expect(row.textContent).toContain('박조직장'); expect(row.textContent).toContain('고정 근무');
    expect(row.querySelector('input[name="organizationId"],input[name="scheduleName"]')).toBeNull();
    expect(postedRows(view, 'employees')).toEqual([]);
  });

  it('저장 중 이동은 손실 확인 없이 차단하고 진행 중 POST를 유지한다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(() => new Promise(() => {})); vi.stubGlobal('fetch', fetcher);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const view = show();
    fireEvent.change(view.getByLabelText('조직 이름 1'), { target: { value: '저장 중' } });
    fireEvent.submit(bulkForm(view));
    fireEvent.click(view.getByRole('link', { name: '내 정보' }));
    expect(confirm).not.toHaveBeenCalled(); expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0][1]?.signal?.aborted).toBe(false);
  });

  it.each(['503', '네트워크 오류'] as const)('POST 대기 중 시트·조회·입력삭제취소를 잠그고 %s 후 상태·재시도 명령을 복구한다', async (failure) => {
    let complete!: (value: Response) => void;
    let reject!: (reason: Error) => void;
    const fetcher = vi.fn<typeof fetch>().mockReturnValue(new Promise<Response>((resolve, fail) => { complete = resolve; reject = fail; }));
    vi.stubGlobal('fetch', fetcher);
    const view = show(page('organizations', { organizationSheet: {
      commandId: 'pending-command', reason: '검토', rows: [orgRow, { ...orgRow, clientRowId: 'pending-deactivate', id: 'o-2', code: 'OLD', name: '비활성 예정 조직', operation: 'DEACTIVATE' }],
      latestRows: [organization, { ...organization, id: 'o-2', code: 'OLD', name: '비활성 예정 조직' }], errors: [],
    } }));
    fireEvent.change(view.getByLabelText('조직 이름 1'), { target: { value: '저장할 편집' } });
    fireEvent.click(view.getByLabelText('1행 선택'));
    const controls = Array.from(view.container.querySelectorAll<HTMLButtonElement | HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>('form button,form input,form select,form textarea'));
    const before = controls.map(control => control.disabled);
    const originalPayload = new FormData(bulkForm(view));
    fireEvent.submit(bulkForm(view));
    const locked = controls.every(control => control.disabled);
    act(() => {
      (view.getByRole('button', { name: '입력' }) as HTMLButtonElement).click();
      (view.getByRole('button', { name: '삭제' }) as HTMLButtonElement).click();
      (view.getByRole('button', { name: '취소' }) as HTMLButtonElement).click();
    });
    await act(async () => failure === '503'
      ? complete(response({ page: 'org/problem', csrf, data: { message: '잠시 후 재시도하세요.' } }, 503))
      : reject(new TypeError('Failed to fetch')));
    expect(locked).toBe(true);
    expect(controls.map(control => control.disabled)).toEqual(before);
    expect((view.getByLabelText('조직 이름 1') as HTMLInputElement).value).toBe('저장할 편집');
    expect((view.getByLabelText('조직 이름 2') as HTMLInputElement).disabled).toBe(true);
    expect(view.queryByLabelText('조직 이름 3')).toBeNull();
    const sent = fetcher.mock.calls[0][1]?.body as URLSearchParams;
    expect(sent.get('rows')).toBe(originalPayload.get('rows'));
    expect(sent.get('commandId')).toBe(originalPayload.get('commandId'));
    expect(sent.get('_csrf')).toBe('csrf');
    await act(async () => fireEvent.submit(bulkForm(view)));
    expect(fetcher).toHaveBeenCalledTimes(2);
    const retried = fetcher.mock.calls[1][1]?.body as URLSearchParams;
    expect(retried.get('commandId')).toBe(sent.get('commandId'));
    expect(retried.get('rows')).toBe(sent.get('rows'));
  });

  it('이동 취소 후 같은 뒤로가기·앞으로가기를 다시 시도할 수 있다', async () => {
    history.replaceState(null, '', '/history-sentinel');
    history.pushState(null, '', '/admin/organizations?q=개발&page=1');
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(response(page('employees'), 200, '/admin/employees'))
      .mockResolvedValueOnce(response(page(), 200, '/admin/organizations?q=개발&page=1'))
      .mockResolvedValueOnce(response(page('employees'), 200, '/admin/employees'));
    vi.stubGlobal('fetch', fetcher);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const view = show();
    fireEvent.click(view.getByRole('link', { name: '사원 관리' }));
    await waitFor(() => expect(view.getByLabelText('이름 1')).toBeTruthy());
    fireEvent.change(view.getByLabelText('이름 1'), { target: { value: '직원 편집' } });
    act(() => history.back());
    await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(location.pathname).toBe('/admin/employees'));
    confirm.mockReturnValueOnce(true);
    act(() => history.back());
    await waitFor(() => expect(view.getByLabelText('조직 이름 1')).toBeTruthy());
    expect(location.pathname).toBe('/admin/organizations');
    expect(new URL(String(fetcher.mock.calls[1][0]), location.origin).pathname).toBe('/admin/organizations');
    expect(new URL(String(fetcher.mock.calls[1][0]), location.origin).searchParams.get('page')).toBe('1');
    fireEvent.change(view.getByLabelText('조직 이름 1'), { target: { value: '조직 편집' } });
    act(() => history.forward());
    await waitFor(() => expect(confirm).toHaveBeenCalledTimes(3));
    await waitFor(() => expect(location.pathname).toBe('/admin/organizations'));
    confirm.mockReturnValueOnce(true);
    act(() => history.forward());
    await waitFor(() => expect(view.getByLabelText('이름 1')).toBeTruthy());
    expect(location.pathname).toBe('/admin/employees');
    expect(fetcher).toHaveBeenCalledTimes(3);
    expect(new URL(String(fetcher.mock.calls[2][0]), location.origin).pathname).toBe('/admin/employees');
  });
});

const headForm = { commandId: 'head-command', organizationId: 'o-1', employeeId: 'e-1', validFrom: '2026-10-27', expectedVersion: 3, reason: '조직장 검토' };
const organizationHeads = { asOf: '2026-10-26T15:00:00Z', today: '2026-10-27', organizationId: 'o-1', organizations: [organization], candidates: [{ id: 'e-1', employeeNo: 'E001', employeeName: '김직원', joinedOn: '2026-10-01', leftOn: null }], history: [{ id: 'head-1', organizationId: 'o-1', employeeId: 'e-2', employeeNo: 'E002', employeeName: '이전조직장', validFrom: '2026-10-01', validTo: '2026-10-27', supersededAt: null, reason: '첫 배정', version: 1, eligible: false, approverEligible: false }], form: headForm };
const companyOrganizations = { asOf: organizationHeads.asOf, today: '2026-10-27', organizationId: 'o-1', q: '김 & 개발', page: 1, hasNext: true,
  organizations: [{ ...organization, headEmployeeNo: 'E002', headEmployeeName: '박조직장', headEligible: true, headApproverEligible: false }, { ...organization, id: 'o-2', code: 'BACK', name: '백엔드팀', parentId: 'o-1', headEmployeeNo: null, headEmployeeName: null, headEligible: false, headApproverEligible: false }],
  selectedOrganization: { ...organization, headEmployeeNo: 'E002', headEmployeeName: '박조직장', headEligible: true, headApproverEligible: false },
  employees: [{ id: 'e-1', employeeNo: 'E001', employeeName: '김직원', organizationName: '개발팀' }],
};
function featurePage(name: string, data: Record<string, unknown>, roles = ['EMPLOYEE', 'ATTENDANCE_ADMIN']) {
  return { page: name, data: { workspacePrincipal: { roles }, workspaceEmployee: { employeeName: '직원', companyName: '합성회사' }, ...data }, csrf };
}
describe('조직장·조직도', () => {
  it('조직장 POST 대기 중 다른 배정·종료 폼 입력도 잠그고 실패하면 복구한다', async () => {
    let complete!: (value: Response) => void;
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockReturnValue(new Promise<Response>(resolve => { complete = resolve; })));
    const view = show(featurePage('org/organization-heads', { organizationHeads }));
    const assignment = view.container.querySelector('form[action="/admin/organization-heads"][method="post"]')!;
    const otherReason = view.getByLabelText('종료 사유') as HTMLTextAreaElement;
    const endButton = view.getByRole('button', { name: '배정 종료' }) as HTMLButtonElement;
    expect(otherReason.disabled).toBe(false); expect(endButton.disabled).toBe(true);
    fireEvent.submit(assignment);
    const otherLocked = otherReason.disabled;
    await act(async () => complete(response({ page: 'org/problem', csrf, data: { message: '다시 시도하세요.' } }, 503)));
    expect(otherLocked).toBe(true);
    expect(otherReason.disabled).toBe(false); expect(endButton.disabled).toBe(true);
    expect(otherReason.value).toBe('조직장 검토');
  });
  it('관리자 조직장 배정과 종료는 별도 POST·버전·적용일·사유를 보낸다', () => {
    const view = show(featurePage('org/organization-heads', { organizationHeads }));
    const assign = view.container.querySelector('form[action="/admin/organization-heads"][method="post"]') as HTMLFormElement;
    const end = view.container.querySelector('form[action="/admin/organization-heads/end"]') as HTMLFormElement;
    expect(assign).toBeTruthy(); expect(end).toBeTruthy();
    const body = new FormData(assign);
    expect(body.get('employeeId')).toBe('e-1'); expect(body.get('expectedVersion')).toBe('3');
    expect(body.get('organizationId')).toBe('o-1'); expect(body.get('commandId')).toBe('head-command');
    expect(new FormData(end).has('employeeId')).toBe(false);
    expect(assign.querySelector('input[type="date"]')?.getAttribute('min')).toBe('2026-10-27');
    expect(view.getByText(/조직장 배정으로 관리·결재 권한/)).toBeTruthy();
    expect(view.getByRole('link', { name: '조직장 배정' }).getAttribute('href')).toBe('/admin/organization-heads');
  });
  it('조직장 이력·부재와 결재 적격성을 구분하고 오류 입력을 보존한다', () => {
    const view = show(featurePage('org/organization-heads', { error: '재직 기간을 확인하세요.', organizationHeads: { ...organizationHeads, form: { ...headForm, employeeId: 'missing', reason: '기존 입력' } } }));
    expect(view.getByText('이전조직장')).toBeTruthy();
    expect(view.getByText('재지정 필요')).toBeTruthy();
    expect(view.getByText('결재 자격 없음')).toBeTruthy();
    expect(view.getByText('재직 기간을 확인하세요.')).toBeTruthy();
    const assign = view.container.querySelector('form[action="/admin/organization-heads"][method="post"]') as HTMLFormElement;
    expect(new FormData(assign).get('employeeId')).toBe('missing');
    expect(new FormData(assign).get('reason')).toBe('기존 입력');
    expect(view.getByText('현재 선택값 (재지정 필요)')).toBeTruthy();
  });
  it('대표 조직도는 계층을 접고 펼치며 현재 조직장과 선택 조직 직원만 읽는다', () => {
    const view = show(featurePage('company/organizations', { companyOrganizations }, ['EMPLOYEE', 'COMPANY_REPRESENTATIVE']));
    expect(view.getByRole('heading', { name: '조직도' })).toBeTruthy();
    const tree = view.getByRole('navigation', { name: '조직 계층' });
    expect(tree.querySelector('details > summary')).toBeTruthy();
    expect(tree.textContent).toContain('박조직장'); expect(tree.textContent).toContain('백엔드팀');
    expect(view.container.querySelector('main')?.textContent).toContain('E001');
    expect(view.container.querySelector('main')?.textContent).toContain('선택 조직의 오늘 유효 소속');
    expect(view.container.querySelector('main form[method="post"]')).toBeNull();
    expect(view.queryByRole('link', { name: '조직장 배정' })).toBeNull();
    expect(view.getByRole('link', { name: '조직도' }).getAttribute('href')).toBe('/company/organizations');
  });
  it('조직도 검색·페이지 조건을 보존하고 조직 선택은 페이지를 초기화한다', () => {
    const view = show(featurePage('company/organizations', { companyOrganizations }));
    const next = new URL(view.getByRole('link', { name: '다음' }).getAttribute('href')!, location.origin);
    expect(next.searchParams.get('q')).toBe('김 & 개발'); expect(next.searchParams.get('organizationId')).toBe('o-1'); expect(next.searchParams.get('page')).toBe('2');
    const link = new URL(view.getByRole('link', { name: 'BACK · 백엔드팀' }).getAttribute('href')!, location.origin);
    expect(link.searchParams.get('organizationId')).toBe('o-2'); expect(link.searchParams.get('page')).toBe('0');
    expect((view.getByLabelText('선택 조직 사번·이름 검색') as HTMLInputElement).value).toBe('김 & 개발');
    expect((view.getByLabelText('선택 조직 사번·이름 검색') as HTMLInputElement).maxLength).toBe(100);
  });
  it('공석·부적격 조직장·직원 없음은 실제 상태를 표시한다', () => {
    const view = show(featurePage('company/organizations', { companyOrganizations: { ...companyOrganizations, selectedOrganization: { ...companyOrganizations.selectedOrganization, headEligible: false }, employees: [], hasNext: false } }));
    const main = view.container.querySelector('main')!;
    expect(main.textContent).toContain('조직장 미배정'); expect(main.textContent).toContain('재지정 필요'); expect(main.textContent).toContain('조회된 직원이 없습니다.');
  });
});
