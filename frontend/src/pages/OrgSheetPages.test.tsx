// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, fireEvent, render, waitFor, within } from '@testing-library/react';
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
async function show(initial = page()) {
  const view = render(<App initial={initial} />);
  await Promise.all(Array.from(view.container.querySelectorAll('.w-sheet[role="region"]')).map(sheet =>
    view.findByRole('grid', { name: sheet.getAttribute('aria-label')! })));
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
async function openCell(view: ReturnType<typeof render>, label: string) {
  let control = await view.findByLabelText(label);
  if (!control.matches('input,select')) {
    fireEvent.click(control, { detail: 1 });
    control = await waitFor(() => {
      const editor = view.getByLabelText(label);
      expect(editor.matches('input,select')).toBe(true);
      return editor;
    });
  }
  return control as HTMLInputElement | HTMLSelectElement;
}
async function changeCell(view: ReturnType<typeof render>, label: string, value: string) {
  const control = await openCell(view, label);
  fireEvent.change(control, { target: { value } });
  fireEvent.keyDown(control, { key: 'Enter' });
  await waitFor(() => expect(control.isConnected).toBe(false));
  return control;
}
function cellValue(view: ReturnType<typeof render>, label: string) {
  const cell = view.getByLabelText(label);
  return cell instanceof HTMLInputElement || cell instanceof HTMLSelectElement ? cell.value : cell.textContent;
}
async function save(view: ReturnType<typeof render>, reason?: string) {
  fireEvent.click(view.getByRole('button', { name: '저장' }));
  const dialog = await view.findByRole('dialog', { name: '변경 내용 저장' });
  if (reason !== undefined) fireEvent.change(within(dialog).getByLabelText('일괄 변경 사유'), { target: { value: reason } });
  fireEvent.click(within(dialog).getByRole('button', { name: /건 저장$/ }));
  return dialog;
}
beforeEach(() => {
  history.replaceState(null, '', '/admin/organizations?q=개발&page=1');
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value(this: HTMLDialogElement) { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value(this: HTMLDialogElement) { this.open = false; } });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe('조직·사원 시트 편집', () => {
  it('인사기본 시트에서 입사 예정 관리와 선택 사원 Excel을 열고 확정 입사일을 편집하지 않는다', async () => {
    const view = render(<App initial={page('employees', {
      personnelAsOf: '2026-11-17',
      personnelBasics: { 'e-1': { onboardingId: 'planned-one', gradeName: '선임',
        positionName: '팀원', jobName: '개발', employmentStatus: 'ACTIVE' } },
    })} />);
    expect(view.getByRole('link', { name: '입사 예정 관리' }).getAttribute('href')).toBe('/admin/employees/onboarding');
    expect(await view.findByRole('link', { name: '최초 발령 보기' })).toHaveProperty('pathname', '/admin/employees/onboarding/planned-one');
    const joinDate = await view.findByLabelText('입사일 1');
    fireEvent.keyDown(joinDate, { key: 'F2' });
    expect(view.getByLabelText('입사일 1')).not.toBeInstanceOf(HTMLInputElement);
    fireEvent.click(view.getByRole('button', { name: '인사기본 Excel' }));
    const dialog = view.getByRole('dialog', { name: '인사기본 Excel' });
    expect(within(dialog).getByRole('button', { name: '선택한 사원 다운로드' })).toHaveProperty('disabled', true);
    expect(dialog.querySelector('form[data-native-submit]')).toHaveProperty('action', expect.stringContaining('/admin/employees/exports'));
  });

  it('편집 전에는 텍스트 셀을 표시하고 저장된 조직 코드는 클릭·F2로 편집하지 않는다', async () => {
    const view = await show();
    const region = view.getByRole('region', { name: '조직 편집 시트' });
    await view.findByLabelText('조직 이름 1');
    expect(region.querySelector('.ag-cell input:not([type="checkbox"]),.ag-cell select')).toBeNull();
    const code = view.getByLabelText('조직 코드 1');
    expect(code.textContent).toBe('DEV');
    fireEvent.click(code);
    fireEvent.keyDown(code.closest('.ag-cell')!, { key: 'F2' });
    expect(region.querySelector('[col-id="code"] input')).toBeNull();
  });

  it('클릭한 셀만 편집하고 편집 중 마지막 입력값을 즉시 저장한다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(() => new Promise(() => {}));
    vi.stubGlobal('fetch', fetcher);
    const view = await show();
    fireEvent.click(await view.findByLabelText('조직 이름 1'), { detail: 1 });
    const editor = await view.findByRole('textbox', { name: '조직 이름 1' });
    fireEvent.change(editor, { target: { value: '마지막 입력' } });
    await save(view, '이름 변경');
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    const payload = JSON.parse((fetcher.mock.calls[0][1]?.body as URLSearchParams).get('rows')!);
    expect(payload[0]).toMatchObject({ clientRowId: 'row-org', name: '마지막 입력' });
  });

  it.each(['organizations', 'employees'] as const)('%s 편집을 안정적인 행 ID의 공통 그리드로 표시한다', async (kind) => {
    const view = await show(page(kind));
    const sheet = view.getByRole('region', { name: kind === 'organizations' ? '조직 편집 시트' : '사원 편집 시트' });
    expect(sheet.querySelector('.ag-root-wrapper')).toBeTruthy();
    expect(sheet.querySelector(`[row-id="${kind === 'organizations' ? 'row-org' : 'row-employee'}"]`)).toBeTruthy();
  });

  it('도구 모음과 고정 코드·사번을 표시하고 미변경 행은 전송하지 않는다', async () => {
    const view = await show();
    for (const label of ['조회', '행 추가', '비활성화', '저장', '변경 취소']) expect(view.getByRole('button', { name: label })).toBeTruthy();
    expect(view.getByRole('button', { name: '비활성화' }).title).toBe('미저장 신규 행은 제거하고 저장된 행은 비활성 예정으로 표시합니다.');
    expect(view.getByLabelText('조직 코드 1').getAttribute('aria-readonly')).toBe('true');
    expect(postedRows(view)).toEqual([]);
    expect((view.getByRole('button', { name: '저장' }) as HTMLButtonElement).disabled).toBe(true);
    expect(new FormData(bulkForm(view)).get('_csrf')).toBe('csrf');
  });

  it('새 행은 저장 전 추가·제거하며 취소는 조회값으로 복원한다', async () => {
    const view = await show();
    fireEvent.click(view.getByRole('button', { name: '행 추가' }));
    const code = await changeCell(view, '조직 코드 2', 'HR');
    expect(code.hasAttribute('readonly')).toBe(false);
    await changeCell(view, '조직 이름 2', '인사팀');
    expect(postedRows(view)[0]).toMatchObject({ operation: 'CREATE', code: 'HR', name: '인사팀' });
    fireEvent.click(view.getByLabelText('2행 선택'));
    fireEvent.click(view.getByRole('button', { name: '비활성화' }));
    expect(view.queryByLabelText('조직 코드 2')).toBeNull();
    await changeCell(view, '조직 이름 1', '개발본부');
    fireEvent.click(view.getByRole('button', { name: '변경 취소' }));
    expect(cellValue(view, '조직 이름 1')).toBe('개발팀');
    expect(postedRows(view)).toEqual([]);
  });

  it('사원 등록은 입사 예정 흐름에서 시작하고 기존 사번은 읽기 전용이다', async () => {
    const view = await show(page('employees'));
    expect(view.getByLabelText('사번 1').getAttribute('aria-readonly')).toBe('true');
    expect(view.queryByRole('button', { name: '행 추가' })).toBeNull();
    expect(view.getByRole('link', { name: '입사 예정 관리' })).toHaveProperty('pathname', '/admin/employees/onboarding');
  });

  it('기존 행 삭제는 비활성 예정이고 취소하면 복구된다', async () => {
    const view = await show();
    fireEvent.click(view.getByLabelText('1행 선택'));
    fireEvent.click(view.getByRole('button', { name: '비활성화' }));
    expect(view.getByText('비활성 예정')).toBeTruthy();
    expect(postedRows(view)[0]).toMatchObject({ id: 'o-1', operation: 'DEACTIVATE', expectedVersion: 3 });
    expect(view.getByText(/이력을 보존하고 비활성화/)).toBeTruthy();
    fireEvent.click(view.getByRole('button', { name: '변경 취소' }));
    expect(view.queryByText('비활성 예정')).toBeNull();
  });

  it('변경 행만 보내고 수정 필드·변경 수를 표시한다', async () => {
    const view = await show(page('organizations', { organizationSheet: {
      commandId: 'batch-command', reason: '', rows: [orgRow, { ...orgRow, id: 'o-2', clientRowId: 'row-2', code: 'HR', name: '인사팀' }],
      latestRows: [organization, { ...organization, id: 'o-2', code: 'HR', name: '인사팀' }], errors: [],
    } }));
    await changeCell(view, '조직 이름 1', '개발본부');
    expect(postedRows(view)).toHaveLength(1);
    expect(postedRows(view)[0].name).toBe('개발본부');
    expect(view.getByText('저장 전 변경 1건')).toBeTruthy();
    expect(view.getByLabelText('조직 이름 1').getAttribute('data-changed')).toBe('true');
    expect(view.container.querySelector('[data-unsaved-changes="true"]')).toBeTruthy();
  });

  it('정렬·필터 후에도 숨은 선택 행과 편집값을 같은 행 ID로 저장·취소한다', async () => {
    const second = { ...organization, id: 'o-2', code: 'ADMIN', name: '관리팀' };
    const view = await show(page('organizations', { organizationSheet: {
      commandId: 'batch-command', reason: '', rows: [orgRow, { ...second, clientRowId: 'row-2', expectedVersion: 3, operation: 'UPDATE' }],
      latestRows: [organization, second], errors: [],
    } }));
    fireEvent.click(view.getByRole('columnheader', { name: /조직 코드/ }).querySelector('.ag-header-cell-label')!);
    await waitFor(() => expect(view.container.querySelector('[row-id="row-2"]')?.getAttribute('row-index')).toBe('0'));
    const input = await openCell(view, '조직 이름 1');
    fireEvent.change(input, { target: { value: '개발수정' } });
    expect(document.activeElement).toBe(input);
    expect(postedRows(view)[0]).toMatchObject({ id: 'o-1', clientRowId: 'row-org', name: '개발수정' });
    fireEvent.click(view.getByLabelText('1행 선택'));
    fireEvent.change(view.getByLabelText('조직 편집 시트 현재 페이지 필터'), { target: { value: '관리팀' } });
    await waitFor(() => expect(view.getByText('숨은 선택 1행 · 숨은 변경 1행')).toBeTruthy());
    fireEvent.click(view.getByRole('button', { name: '비활성화' }));
    expect(postedRows(view)[0]).toMatchObject({ id: 'o-1', operation: 'DEACTIVATE' });
    fireEvent.click(view.getByRole('button', { name: '변경 취소' }));
    expect(postedRows(view)).toEqual([]);
    fireEvent.change(view.getByLabelText('조직 편집 시트 현재 페이지 필터'), { target: { value: '' } });
    await waitFor(() => expect(cellValue(view, '조직 이름 1')).toBe('개발팀'));
  });

  it('50행을 넘는 입력을 막고 이유를 알려준다', async () => {
    const view = await show(page('organizations', { organizationSheet: {
      commandId: 'batch-command', reason: '', rows: Array.from({ length: 49 }, (_, i) => ({ clientRowId: `new-${i}`, operation: 'CREATE', code: `N${i}`, name: '새 조직', parentId: null, active: true })), latestRows: [], errors: [],
    } }));
    fireEvent.click(view.getByRole('button', { name: '행 추가' }));
    expect((view.getByRole('button', { name: '행 추가' }) as HTMLButtonElement).disabled).toBe(true);
    expect(view.getByText(/한 번에 최대 50행/)).toBeTruthy();
    expect(postedRows(view)).toHaveLength(50);
  });

  it('사원 시트는 재직·활성만 편집하고 배정·계정 화면으로 연결한다', async () => {
    const view = await show(page('employees'));
    expect(view.getByLabelText('사번 1').getAttribute('aria-readonly')).toBe('true');
    await changeCell(view, '이름 1', '김수정');
    expect(postedRows(view, 'employees')[0]).toMatchObject({ displayName: '김수정', expectedVersion: 7, joinedOn: '2026-10-01' });
    expect(view.getByRole('link', { name: '김직원 소속·상세' }).getAttribute('href')).toBe('/admin/employees/e-1');
    expect(view.getByRole('link', { name: '김직원 근무 배정' }).getAttribute('href')).toBe('/admin/employees/e-1/schedule');
    const data = new FormData(bulkForm(view, 'employees'));
    expect(data.get('organizationId')).toBe('o-1');
    expect(data.get('status')).toBe('active');
  });

  it('미저장 링크·조회·뒤로가기 이동을 거절하면 현재 편집을 보존한다', async () => {
    const fetcher = vi.fn<typeof fetch>(); vi.stubGlobal('fetch', fetcher);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const view = await show();
    await changeCell(view, '조직 이름 1', '편집 중');
    fireEvent.click(view.getByRole('link', { name: '내 정보' }));
    fireEvent.submit(view.container.querySelector('form[method="get"]')!);
    history.pushState(null, '', '/me/profile');
    fireEvent.popState(window);
    expect(confirm).toHaveBeenCalledTimes(3);
    expect(fetcher).not.toHaveBeenCalled();
    expect(location.pathname).toBe('/admin/organizations');
    expect(cellValue(view, '조직 이름 1')).toBe('편집 중');
  });

  it('불명확한503 재전송은 같은 명령을 유지하고 편집하면 새 명령을 발급한다', async () => {
    let finishSave!: (result: Response) => void;
    const failed = response({ page: 'org/problem', csrf, data: { message: '저장 결과 확인 필요' } }, 503);
    const fetcher = vi.fn<typeof fetch>()
      .mockImplementationOnce(() => new Promise(resolve => { finishSave = resolve; }))
      .mockResolvedValue(failed);
    vi.stubGlobal('fetch', fetcher);
    const view = await show();
    await changeCell(view, '조직 이름 1', '새 이름');
    await save(view, '업무 검토');
    const first = (fetcher.mock.calls[0][1]?.body as URLSearchParams).get('commandId');
    const reason = view.getByLabelText('일괄 변경 사유') as HTMLTextAreaElement;
    expect(reason.disabled).toBe(true);
    await act(async () => finishSave(failed.clone()));
    await waitFor(() => expect(view.getByText('저장 결과 확인 필요')).toBeTruthy());
    expect(reason.disabled).toBe(false);
    expect(reason.value).toBe('업무 검토');
    fireEvent.submit(bulkForm(view));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect((fetcher.mock.calls[1][1]?.body as URLSearchParams).get('commandId')).toBe(first);
    await act(async () => {});
    await changeCell(view, '조직 이름 1', '다시 수정');
    expect(new FormData(bulkForm(view)).get('commandId')).not.toBe(first);
  });

  it('422는 전체 입력·삭제예정·행 식별자를 복원하고 오류 필드로 초점을 보낸다', async () => {
    const initial = page();
    const failed = page('organizations', { organizationSheet: {
      commandId: 'posted-command', reason: '업무 검토', rows: [{ ...orgRow, name: '중복팀', operation: 'DEACTIVATE' }, { clientRowId: 'new-row', operation: 'CREATE', code: 'NEW', name: '신규팀', parentId: null, active: true }],
      latestRows: [organization], errors: [{ clientRowId: 'new-row', field: 'code', message: '조직 코드가 중복됩니다.' }],
    } });
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(response(failed, 422)));
    const view = await show(initial);
    await changeCell(view, '조직 이름 1', '중복팀');
    await save(view, '업무 검토');
    await waitFor(() => expect(view.getByRole('button', { name: /조직 코드가 중복됩니다/ })).toBeTruthy());
    expect(view.getByText('비활성 예정')).toBeTruthy();
    expect(cellValue(view, '조직 코드 2')).toBe('NEW');
    await waitFor(() => expect(document.activeElement).toBe(view.getByLabelText('조직 코드 2')));
    expect(new FormData(bulkForm(view)).get('commandId')).toBe('posted-command');
    expect(postedRows(view).map(row => row.clientRowId)).toEqual(['row-org', 'new-row']);
  });

  it('409는 최신값과 편집값을 함께 보여주고 재확인은 최신 버전·새 명령을 사용한다', async () => {
    const view = await show(page('organizations', { organizationSheet: {
      commandId: 'conflicted-command', reason: '업무 검토', rows: [{ ...orgRow, name: '내 편집값' }],
      latestRows: [{ ...organization, name: '다른 관리자 저장값', version: 4 }],
      errors: [{ clientRowId: 'row-org', field: 'expectedVersion', message: '최신 버전을 확인하세요.' }],
    } }));
    expect(view.getByText('다른 관리자 저장값')).toBeTruthy();
    expect(view.getAllByText('내 편집값').length).toBeGreaterThan(0);
    fireEvent.click(view.getByRole('button', { name: '최신값 확인 후 편집 유지' }));
    expect(postedRows(view)[0].expectedVersion).toBe(4);
    expect(new FormData(bulkForm(view)).get('commandId')).not.toBe('conflicted-command');
    expect(cellValue(view, '조직 이름 1')).toBe('내 편집값');
  });

  it('상위 조직·활성 필드 오류도 숨기지 않고 표시한다', async () => {
    const view = await show(page('organizations', { organizationSheet: {
      commandId: 'batch-command', reason: '검토', rows: [orgRow], latestRows: [organization],
      errors: [{ clientRowId: 'row-org', field: 'parentId', message: '상위 조직 순환입니다.' }, { clientRowId: 'row-org', field: 'active', message: '소속이 남아 있습니다.' }],
    } }));
    expect(view.getByText('상위 조직 순환입니다.', { selector: 'p' })).toBeTruthy();
    expect(view.getByText('소속이 남아 있습니다.')).toBeTruthy();
  });

  it('조직·사원 시트에 현재 조직장·근무를 읽기 전용으로 표시한다', async () => {
    const org = await show(page('organizations', { organizationHeadsById: { 'o-1': { employeeName: '박조직장', eligible: false, approverEligible: false } } }));
    expect(org.container.querySelector('main')?.textContent).toContain('박조직장 · 재지정 필요');
    cleanup();
    const view = await show(page('employees', { employeeContext: { 'e-1': { organizationName: '개발팀', organizationHeadName: '박조직장', organizationHeadEligible: true, organizationHeadApproverEligible: true, scheduleName: '고정 근무' } } }));
    const row = view.container.querySelector('[row-id="row-employee"] .ag-cell[col-id="assignments"]')!;
    expect(row.textContent).toContain('박조직장'); expect(row.textContent).toContain('고정 근무');
    expect(row.querySelector('input[name="organizationId"],input[name="scheduleName"]')).toBeNull();
    expect(postedRows(view, 'employees')).toEqual([]);
  });

  it('저장 중 이동은 손실 확인 없이 차단하고 진행 중 POST를 유지한다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(() => new Promise(() => {})); vi.stubGlobal('fetch', fetcher);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const view = await show();
    await changeCell(view, '조직 이름 1', '저장 중');
    await save(view, '저장 확인');
    fireEvent.click(view.getByRole('link', { name: '내 정보' }));
    expect(confirm).not.toHaveBeenCalled(); expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0][1]?.signal?.aborted).toBe(false);
  });

  it.each(['503', '네트워크 오류'] as const)('POST 대기 중 시트·조회·입력삭제취소를 잠그고 %s 후 상태·재시도 명령을 복구한다', async (failure) => {
    let complete!: (value: Response) => void;
    let reject!: (reason: Error) => void;
    const fetcher = vi.fn<typeof fetch>().mockReturnValue(new Promise<Response>((resolve, fail) => { complete = resolve; reject = fail; }));
    vi.stubGlobal('fetch', fetcher);
    const view = await show(page('organizations', { organizationSheet: {
      commandId: 'pending-command', reason: '검토', rows: [orgRow, { ...orgRow, clientRowId: 'pending-deactivate', id: 'o-2', code: 'OLD', name: '비활성 예정 조직', operation: 'DEACTIVATE' }],
      latestRows: [organization, { ...organization, id: 'o-2', code: 'OLD', name: '비활성 예정 조직' }], errors: [],
    } }));
    await changeCell(view, '조직 이름 1', '저장할 편집');
    fireEvent.click(view.getByLabelText('1행 선택'));
    const controls = Array.from(view.container.querySelectorAll<HTMLButtonElement | HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>('form button,form input,form select,form textarea'));
    const before = controls.map(control => control.disabled);
    const originalPayload = new FormData(bulkForm(view));
    await save(view);
    const locked = controls.every(control => control.disabled);
    fireEvent.click(view.getByLabelText('조직 이름 1'), { detail: 1 });
    expect(view.queryByRole('textbox', { name: '조직 이름 1' })).toBeNull();
    act(() => {
      (view.getByRole('button', { name: '행 추가' }) as HTMLButtonElement).click();
      (view.getByRole('button', { name: '비활성화' }) as HTMLButtonElement).click();
      (view.getByRole('button', { name: '변경 취소' }) as HTMLButtonElement).click();
    });
    await act(async () => failure === '503'
      ? complete(response({ page: 'org/problem', csrf, data: { message: '잠시 후 재시도하세요.' } }, 503))
      : reject(new TypeError('Failed to fetch')));
    expect(locked).toBe(true);
    expect(controls.map(control => control.disabled)).toEqual(before);
    expect(cellValue(view, '조직 이름 1')).toBe('저장할 편집');
    expect(view.getByLabelText('조직 이름 2').getAttribute('aria-disabled')).toBe('true');
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
    const restoredAt = (path: string) => new Promise<void>(resolve => {
      const listener = () => {
        if (location.pathname !== path) return;
        window.removeEventListener('popstate', listener);
        resolve();
      };
      window.addEventListener('popstate', listener);
    });
    history.replaceState(null, '', '/history-sentinel');
    history.pushState(null, '', '/admin/organizations?q=개발&page=1');
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(response(page('employees'), 200, '/admin/employees'))
      .mockResolvedValueOnce(response(page(), 200, '/admin/organizations?q=개발&page=1'))
      .mockResolvedValueOnce(response(page('employees'), 200, '/admin/employees'));
    vi.stubGlobal('fetch', fetcher);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const view = await show();
    fireEvent.click(view.getByRole('button', { name: '인사·조직' }));
    fireEvent.click(view.getByRole('button', { name: '조직·사원' }));
    fireEvent.click(view.getByRole('link', { name: '인사기본' }));
    await view.findByLabelText('이름 1', {}, { timeout: 5000 });
    await changeCell(view, '이름 1', '직원 편집');
    const backRestored = restoredAt('/admin/employees');
    act(() => history.back());
    await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1));
    await backRestored;
    await waitFor(() => expect(location.pathname).toBe('/admin/employees'));
    confirm.mockReturnValueOnce(true);
    act(() => history.back());
    await view.findByLabelText('조직 이름 1', {}, { timeout: 5000 });
    expect(location.pathname).toBe('/admin/organizations');
    expect(new URL(String(fetcher.mock.calls[1][0]), location.origin).pathname).toBe('/admin/organizations');
    expect(new URL(String(fetcher.mock.calls[1][0]), location.origin).searchParams.get('page')).toBe('1');
    await changeCell(view, '조직 이름 1', '조직 편집');
    const forwardRestored = restoredAt('/admin/organizations');
    act(() => history.forward());
    await waitFor(() => expect(confirm).toHaveBeenCalledTimes(3));
    await forwardRestored;
    await waitFor(() => expect(location.pathname).toBe('/admin/organizations'));
    confirm.mockReturnValueOnce(true);
    act(() => history.forward());
    await view.findByLabelText('이름 1', {}, { timeout: 5000 });
    expect(location.pathname).toBe('/admin/employees');
    expect(fetcher).toHaveBeenCalledTimes(3);
    expect(new URL(String(fetcher.mock.calls[2][0]), location.origin).pathname).toBe('/admin/employees');
  });

  it('저장은 확인 창에서 건수와 사유를 확인한 뒤 시트 폼으로 보내고 창을 닫으면 편집을 유지한다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(() => new Promise(() => {}));
    vi.stubGlobal('fetch', fetcher);
    const view = await show();
    expect(view.container.querySelector('[row-id="row-org"] [col-id="state"]')?.textContent).toBe('');
    await changeCell(view, '조직 이름 1', '개발본부');
    expect(view.container.querySelector('[row-id="row-org"] [col-id="state"]')?.textContent).toBe('수정');
    fireEvent.click(view.getByRole('button', { name: '저장' }));
    const dialog = await view.findByRole('dialog', { name: '변경 내용 저장' });
    expect(within(dialog).getByText('수정 1건')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }));
    await waitFor(() => expect(view.queryByRole('dialog')).toBeNull());
    expect(cellValue(view, '조직 이름 1')).toBe('개발본부');
    await save(view, '조직 개편');
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    const body = fetcher.mock.calls[0][1]?.body as URLSearchParams;
    expect(body.get('reason')).toBe('조직 개편');
    expect(JSON.parse(body.get('rows')!)[0]).toMatchObject({ clientRowId: 'row-org', name: '개발본부' });
    expect(body.get('_csrf')).toBe('csrf');
    expect(body.get('q')).toBe('개발');
  });

  it('확인 창을 거치지 않은 제출은 보내지 않고 확인 창을 연다', async () => {
    const fetcher = vi.fn<typeof fetch>(); vi.stubGlobal('fetch', fetcher);
    const view = await show();
    await changeCell(view, '조직 이름 1', '개발본부');
    fireEvent.submit(bulkForm(view));
    expect(await view.findByRole('dialog', { name: '변경 내용 저장' })).toBeTruthy();
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('사유 422는 확인 창을 다시 열고 사유 칸에 오류와 초점을 둔다', async () => {
    const failed = page('organizations', { error: '변경 사유를 입력하세요.', organizationSheet: {
      commandId: 'posted-command', reason: '', rows: [{ ...orgRow, name: '새 이름' }], latestRows: [organization],
      errors: [{ clientRowId: '', field: 'reason', message: '변경 사유를 입력하세요.' }],
    } });
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(response(failed, 422)));
    const view = await show();
    await changeCell(view, '조직 이름 1', '새 이름');
    await save(view, ' ');
    const dialog = await waitFor(() => {
      const element = view.getByRole('dialog', { name: '변경 내용 저장' });
      expect(within(element).getByText('변경 사유를 입력하세요.')).toBeTruthy();
      return element;
    });
    const reason = within(dialog).getByLabelText('일괄 변경 사유');
    await waitFor(() => expect(document.activeElement).toBe(reason));
    expect(reason.getAttribute('aria-invalid')).toBe('true');
    expect(view.container.querySelector('.t-org-sheet .notice.error')).toBeNull();
    expect(view.queryByRole('navigation', { name: '조직 편집 시트 오류 위치' })).toBeNull();
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
  it('조직장 이력과 선택 조직 직원을 공통 조회 그리드로 표시한다', async () => {
    const heads = await show(featurePage('org/organization-heads', { organizationHeads }));
    expect(heads.getByRole('region', { name: '조직장 배정 이력' }).querySelector('.ag-root-wrapper')).toBeTruthy();
    cleanup();
    const company = await show(featurePage('company/organizations', { companyOrganizations }));
    expect(company.getByRole('region', { name: '선택 조직 직원' }).querySelector('[row-id="e-1"]')).toBeTruthy();
    expect(company.getByRole('navigation', { name: '조직 계층' }).querySelector('details > summary')).toBeTruthy();
  });

  it('조직장 POST 대기 중 다른 배정·종료 폼 입력도 잠그고 실패하면 복구한다', async () => {
    let complete!: (value: Response) => void;
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockReturnValue(new Promise<Response>(resolve => { complete = resolve; })));
    const view = await show(featurePage('org/organization-heads', { organizationHeads }));
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
  it('관리자 조직장 배정과 종료는 별도 POST·버전·적용일·사유를 보낸다', async () => {
    const view = await show(featurePage('org/organization-heads', { organizationHeads }));
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
  it('조직장 이력·부재와 결재 적격성을 구분하고 오류 입력을 보존한다', async () => {
    const view = await show(featurePage('org/organization-heads', { error: '재직 기간을 확인하세요.', organizationHeads: { ...organizationHeads, form: { ...headForm, employeeId: 'missing', reason: '기존 입력' } } }));
    expect(view.getByText('이전조직장')).toBeTruthy();
    expect(view.getByText('재지정 필요')).toBeTruthy();
    expect(view.getByText('결재 자격 없음')).toBeTruthy();
    expect(view.getByText('재직 기간을 확인하세요.')).toBeTruthy();
    const assign = view.container.querySelector('form[action="/admin/organization-heads"][method="post"]') as HTMLFormElement;
    expect(new FormData(assign).get('employeeId')).toBe('missing');
    expect(new FormData(assign).get('reason')).toBe('기존 입력');
    expect(view.getByText('현재 선택값 (재지정 필요)')).toBeTruthy();
  });
  it('대표 조직도는 계층을 접고 펼치며 현재 조직장과 선택 조직 직원만 읽는다', async () => {
    const view = await show(featurePage('company/organizations', { companyOrganizations }, ['EMPLOYEE', 'COMPANY_REPRESENTATIVE']));
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
  it('조직도 검색·페이지 조건을 보존하고 조직 선택은 페이지를 초기화한다', async () => {
    const view = await show(featurePage('company/organizations', { companyOrganizations }));
    const next = new URL(view.getByRole('link', { name: '다음' }).getAttribute('href')!, location.origin);
    expect(next.searchParams.get('q')).toBe('김 & 개발'); expect(next.searchParams.get('organizationId')).toBe('o-1'); expect(next.searchParams.get('page')).toBe('2');
    const link = new URL(view.getByRole('link', { name: 'BACK · 백엔드팀' }).getAttribute('href')!, location.origin);
    expect(link.searchParams.get('organizationId')).toBe('o-2'); expect(link.searchParams.get('page')).toBe('0');
    expect((view.getByLabelText('선택 조직 사번·이름 검색') as HTMLInputElement).value).toBe('김 & 개발');
    expect((view.getByLabelText('선택 조직 사번·이름 검색') as HTMLInputElement).maxLength).toBe(100);
  });
  it('공석·부적격 조직장·직원 없음은 실제 상태를 표시한다', async () => {
    const view = await show(featurePage('company/organizations', { companyOrganizations: { ...companyOrganizations, selectedOrganization: { ...companyOrganizations.selectedOrganization, headEligible: false }, employees: [], hasNext: false } }));
    const main = view.container.querySelector('main')!;
    expect(main.textContent).toContain('조직장 미배정'); expect(main.textContent).toContain('재지정 필요'); expect(main.textContent).toContain('조회된 직원이 없습니다.');
  });
});

describe('조직장 칸 표시', () => {
  it('상태 문구와 배정 링크 사이를 띄운다', async () => {
    const view = await show();
    const cell = view.container.querySelector('.ag-row [col-id="organizationHead"]');
    expect(cell?.textContent).toBe('조직장 미배정 조직장 배정');
  });
});
