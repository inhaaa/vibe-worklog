// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import type { PageEnvelope } from '../types';

const csrf = { token: 'csrf', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const route = { id: 'route-1', code: 'TEAM', name: '팀 결재', active: true, version: 3, revision: 2, approverKind: 'ORGANIZATION_HEAD', employeeId: null };
const row = { ...route, clientRowId: 'row-1', expectedVersion: 3, operation: 'UPDATE' };
const principal = { workspacePrincipal: { roles: ['EMPLOYEE', 'ATTENDANCE_ADMIN', 'APPROVER'] }, workspaceEmployee: { employeeName: '관리자', companyName: '합성회사' } };
function page(overrides: Record<string, unknown> = {}): PageEnvelope {
  return { page: 'org/approval-routes', csrf, data: { ...principal, approvalRoutes: { q: '', page: 0, hasNext: false, candidates: [{ id: 'e-2', employeeNo: 'E002', displayName: '박승인' }], sheet: { commandId: 'batch-command', reason: '', rows: [row], latestRows: [route], errors: [] }, routes: [route], versions: [], ...overrides } } };
}
function form(view: ReturnType<typeof render>) { return view.container.querySelector('form[action="/admin/approval-routes/bulk"]') as HTMLFormElement; }
function posted(view: ReturnType<typeof render>) { return JSON.parse(String(new FormData(form(view)).get('rows'))) as Record<string, unknown>[]; }
function response(body: PageEnvelope, status: number) {
  const result = new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/vnd.worklog.page+json' } });
  Object.defineProperty(result, 'url', { value: new URL('/admin/approval-routes/bulk', location.href).href });
  return result;
}
const employee = { id: 'e-1', employeeNo: 'E001', displayName: '김직원', active: true, version: 5 };
const organization = { id: 'o-1', code: 'DEV', name: '개발팀', active: true, version: 8 };
const version = { id: 'v-2', routeId: 'route-1', code: 'TEAM', name: '팀 결재', active: true, revision: 2, approverKind: 'ORGANIZATION_HEAD', employeeId: null, employeeName: null };
function assignment(overrides: Record<string, unknown> = {}): PageEnvelope {
  return { page: 'org/approval-route-assignments', csrf, data: { ...principal, approvalRouteAssignments: { today: '2026-10-28', scopeKind: 'EMPLOYEE', employeeId: 'e-1', organizationId: null, employees: [employee], organizations: [organization], versions: [version], history: [], form: { scopeKind: 'EMPLOYEE', employeeId: 'e-1', organizationId: null, routeVersionId: null, validFrom: '2026-10-28', expectedVersion: 5, reason: '', commandId: 'assign-command' }, ...overrides } } };
}
async function show(element: React.ReactElement) {
  const view = render(element);
  await Promise.all(Array.from(view.container.querySelectorAll('.w-sheet[role="region"]')).map(sheet =>
    view.findByRole('grid', { name: sheet.getAttribute('aria-label')! })));
  return view;
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
beforeEach(() => { history.replaceState(null, '', '/admin/approval-routes'); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe('결재선 React 관리', () => {
  it('결재선은 텍스트로 조회하며 고정 코드는 Enter로 편집하지 않는다', async () => {
    const view = await show(<App initial={page()} />);
    const region = view.getByRole('region', { name: '결재선 편집 시트' });
    const code = await view.findByLabelText('결재선 코드 1');
    expect(region.querySelector('.ag-cell input:not([type="checkbox"]),.ag-cell select')).toBeNull();
    expect(code.textContent).toBe('TEAM');
    fireEvent.keyDown(code.closest('.ag-cell')!, { key: 'Enter' });
    expect(region.querySelector('[col-id="code"] input')).toBeNull();
  });

  it('이름 셀을 클릭해 편집한 마지막 값을 저장 직전까지 보존한다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(() => new Promise(() => {}));
    vi.stubGlobal('fetch', fetcher);
    const view = await show(<App initial={page()} />);
    fireEvent.click(await view.findByLabelText('결재선 이름 1'), { detail: 1 });
    const editor = await view.findByRole('textbox', { name: '결재선 이름 1' });
    fireEvent.change(editor, { target: { value: '최종 결재 이름' } });
    fireEvent.change(view.getByLabelText('일괄 변경 사유'), { target: { value: '이름 변경' } });
    fireEvent.submit(form(view));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    const payload = JSON.parse((fetcher.mock.calls[0][1]?.body as URLSearchParams).get('rows')!);
    expect(payload[0]).toMatchObject({ clientRowId: 'row-1', name: '최종 결재 이름' });
  });

  it('결재 방식 편집의 Esc는 지정 사원과 편집 시작 전의 다른 셀 수정을 함께 보존한다', async () => {
    const direct = { ...route, approverKind: 'DIRECT_EMPLOYEE', employeeId: 'e-2' };
    const view = await show(<App initial={page({ sheet: { commandId: 'direct-command', reason: '', rows: [{ ...row, ...direct }], latestRows: [direct], errors: [] } })} />);
    await changeCell(view, '결재선 이름 1', '앞선 이름 수정');
    const editor = await openCell(view, '결재 방식 1');
    fireEvent.change(editor, { target: { value: 'ORGANIZATION_HEAD' } });
    expect(posted(view)[0]).toMatchObject({ approverKind: 'ORGANIZATION_HEAD', employeeId: null });
    fireEvent.keyDown(editor, { key: 'Escape' });
    await waitFor(() => expect(editor.isConnected).toBe(false));
    expect(posted(view)[0]).toMatchObject({ name: '앞선 이름 수정', approverKind: 'DIRECT_EMPLOYEE', employeeId: 'e-2' });
  });

  it('결재선 편집과 버전 이력에 안정적인 행 ID의 공통 그리드를 사용한다', async () => {
    const view = await show(<App initial={page({ versions: [{ ...version, reason: '최초 등록', createdAt: '2026-10-28T00:00:00Z' }] })} />);
    expect(view.getByRole('region', { name: '결재선 편집 시트' }).querySelector('[row-id="row-1"]')).toBeTruthy();
    expect(view.getByRole('region', { name: '결재선 버전 이력' }).querySelector('[row-id="v-2"]')).toBeTruthy();
  });

  it('적용 배정 이력은 공통 조회 그리드로 표시한다', async () => {
    const view = await show(<App initial={assignment({ history: [{ id: 'a-1', routeVersionId: 'v-2', routeCode: 'TEAM', routeName: '팀 결재', revision: 2, approverKind: 'ORGANIZATION_HEAD', scopeKind: 'EMPLOYEE', employeeId: 'e-1', organizationId: null, validFrom: '2026-10-01', validTo: null, supersededAt: null, reason: '기존 배정', version: 0, routeActive: true }] })} />);
    expect(view.getByRole('region', { name: '결재선 배정 이력' }).querySelector('[row-id="a-1"]')).toBeTruthy();
  });

  it('관리자에게 결재선 메뉴와 담당자에게 완료 결재 메뉴를 제공한다', async () => {
    const view = await show(<App initial={page()} />);
    const menu = view.getByLabelText('주 메뉴');
    expect(menu.querySelector('a[href="/admin/approval-routes"]')).toBeTruthy();
    expect(menu.querySelector('a[href="/admin/approval-route-assignments"]')).toBeTruthy();
    expect(menu.querySelector('a[href="/approvals/completed"]')).toBeTruthy();
  });

  it('시트 도구·고정 코드·미변경 제외와 버전 재배정 안내를 제공한다', async () => {
    const view = await show(<App initial={page()} />);
    for (const name of ['조회', '입력', '비활성화', '저장', '취소']) expect(view.getByRole('button', { name })).toBeTruthy();
    expect(view.getByRole('button', { name: '비활성화' }).title).toBe('미저장 신규 행은 제거하고 저장된 행은 비활성 예정으로 표시합니다.');
    expect(view.getByLabelText('결재선 코드 1').getAttribute('aria-readonly')).toBe('true');
    expect(posted(view)).toEqual([]);
    expect(view.getByText(/새 버전을 사용하려면 다시 배정/)).toBeTruthy();
    expect(new FormData(form(view)).get('_csrf')).toBe('csrf');
  });

  it('지정 사원과 조직장을 선택하고 새 행·삭제 예정·취소를 처리한다', async () => {
    const view = await show(<App initial={page()} />);
    fireEvent.click(view.getByRole('button', { name: '입력' }));
    await changeCell(view, '결재선 코드 2', 'DIRECT');
    await changeCell(view, '결재선 이름 2', '사원 지정');
    await changeCell(view, '결재 방식 2', 'DIRECT_EMPLOYEE');
    await changeCell(view, '지정 결재 사원 2', 'e-2');
    expect(posted(view)[0]).toMatchObject({ operation: 'CREATE', code: 'DIRECT', approverKind: 'DIRECT_EMPLOYEE', employeeId: 'e-2' });
    fireEvent.click(view.getByLabelText('2행 선택'));
    fireEvent.click(view.getByRole('button', { name: '비활성화' }));
    expect(view.queryByLabelText('결재선 코드 2')).toBeNull();
    fireEvent.click(view.getByLabelText('1행 선택'));
    fireEvent.click(view.getByRole('button', { name: '비활성화' }));
    expect(posted(view)[0]).toMatchObject({ operation: 'DEACTIVATE', expectedVersion: 3 });
    expect(view.getByText('비활성 예정')).toBeTruthy();
    fireEvent.click(view.getByRole('button', { name: '취소' }));
    expect(posted(view)).toEqual([]);
  });

  it('50행 제한과 미저장 이동 확인을 적용한다', async () => {
    const rows = Array.from({ length: 50 }, (_, i) => ({ ...row, id: null, clientRowId: `new-${i}`, operation: 'CREATE', code: `R${i}` }));
    const view = await show(<App initial={page({ sheet: { commandId: 'batch-command', reason: '', rows, latestRows: [], errors: [] } })} />);
    expect((view.getByRole('button', { name: '입력' }) as HTMLButtonElement).disabled).toBe(true);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const fetcher = vi.fn<typeof fetch>(); vi.stubGlobal('fetch', fetcher);
    fireEvent.click(view.getByRole('link', { name: '내 정보' }));
    expect(confirm).toHaveBeenCalled();
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('422 응답에 편집값·명령·행 오류를 보존한다', async () => {
    const failed = page({ sheet: { commandId: 'posted-command', reason: '검토', rows: [{ ...row, name: '중복 이름' }], latestRows: [route], errors: [{ clientRowId: 'row-1', field: 'name', message: '이름을 확인하세요.' }] } });
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(response(failed, 422)));
    const view = await show(<App initial={page()} />);
    await changeCell(view, '결재선 이름 1', '중복 이름');
    fireEvent.submit(form(view));
    await waitFor(() => expect(view.getByRole('button', { name: /이름을 확인하세요/ })).toBeTruthy());
    expect(cellValue(view, '결재선 이름 1')).toBe('중복 이름');
    expect(new FormData(form(view)).get('commandId')).toBe('posted-command');
    await waitFor(() => expect(document.activeElement).toBe(view.getByLabelText('결재선 이름 1')));
  });

  it('409 최신 버전을 확인할 때만 새 명령과 예상 버전을 사용한다', async () => {
    const view = await show(<App initial={page({ sheet: { commandId: 'old-command', reason: '검토', rows: [{ ...row, name: '내 편집' }], latestRows: [{ ...route, name: '최신 이름', version: 4 }], errors: [] } })} />);
    expect(view.getByText('최신 이름')).toBeTruthy();
    expect((view.getByRole('button', { name: '저장' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(view.getByRole('button', { name: '최신값 확인 후 편집 유지' }));
    expect(posted(view)[0].expectedVersion).toBe(4);
    expect(new FormData(form(view)).get('commandId')).not.toBe('old-command');
  });

  it('저장 결과가 불명확하면 같은 명령을 재전송하고 재편집 때 갱신한다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response({ page: 'org/problem', csrf, data: { message: '결과 확인 필요' } }, 503));
    vi.stubGlobal('fetch', fetcher);
    const view = await show(<App initial={page()} />);
    await changeCell(view, '결재선 이름 1', '새 이름');
    fireEvent.change(view.getByLabelText('일괄 변경 사유'), { target: { value: '변경 검토' } });
    const id = new FormData(form(view)).get('commandId');
    fireEvent.submit(form(view));
    await waitFor(() => expect(view.getByText('결과 확인 필요')).toBeTruthy());
    expect(view.getByLabelText('결재선 이름 1').getAttribute('aria-disabled')).toBe('false');
    fireEvent.submit(form(view));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect((fetcher.mock.calls[1][1]?.body as URLSearchParams).get('commandId')).toBe(id);
    await waitFor(() => expect(view.getByLabelText('결재선 이름 1').getAttribute('aria-disabled')).toBe('false'));
    await changeCell(view, '결재선 이름 1', '다시 수정');
    expect(new FormData(form(view)).get('commandId')).not.toBe(id);
  });

  it('비활성 버전은 신규 선택·저장을 막고 취소하면 미저장 상태를 해제한다', async () => {
    const view = await show(<App initial={assignment({ versions: [{ ...version, active: false }] })} />);
    expect((view.getByRole('option', { name: /정의 비활성/ }) as HTMLOptionElement).disabled).toBe(true);
    fireEvent.change(view.getByLabelText('적용할 결재선 버전'), { target: { value: 'v-2' } });
    expect((view.getByRole('button', { name: '저장' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(view.getByLabelText('배정·해제 사유'), { target: { value: '임시 작성' } });
    fireEvent.click(view.getByRole('button', { name: '취소' }));
    expect(view.container.querySelector('[data-unsaved-changes="true"]')).toBeNull();
    expect((view.getByLabelText('배정·해제 사유') as HTMLTextAreaElement).value).toBe('');
  });

  it('배정 입력 중 새 입력을 취소하면 작성 내용을 보존한다', async () => {
    const view = await show(<App initial={assignment()} />);
    fireEvent.change(view.getByLabelText('적용할 결재선 버전'), { target: { value: 'v-2' } });
    fireEvent.change(view.getByLabelText('배정·해제 사유'), { target: { value: '계속 작성 중' } });
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    const command = view.container.querySelector('form.t-route-assignment-form') as HTMLFormElement;
    const id = new FormData(command).get('commandId');
    fireEvent.click(view.getByRole('button', { name: '입력' }));
    expect((view.getByLabelText('배정·해제 사유') as HTMLTextAreaElement).value).toBe('계속 작성 중');
    expect((view.getByLabelText('적용할 결재선 버전') as HTMLSelectElement).value).toBe('v-2');
    expect(new FormData(command).get('commandId')).toBe(id);
  });

  it('현재 배정 버전 기본값은 미저장으로 보지 않으며 취소는 저장된 배정으로 복원한다', async () => {
    const initial = assignment({ history: [{ id: 'a-1', routeVersionId: 'v-2', routeCode: 'TEAM', routeName: '팀 결재', revision: 2, approverKind: 'ORGANIZATION_HEAD', scopeKind: 'EMPLOYEE', employeeId: 'e-1', organizationId: null, validFrom: '2026-10-01', validTo: null, supersededAt: null, reason: '기존 배정', version: 0, routeActive: true }], form: { scopeKind: 'EMPLOYEE', employeeId: 'e-1', organizationId: null, routeVersionId: 'v-2', validFrom: '2026-10-28', expectedVersion: 5, reason: '', commandId: 'get-command' } });
    const view = await show(<App initial={initial} />);
    expect(view.container.querySelector('[data-unsaved-changes="true"]')).toBeNull();
    expect((view.getByRole('button', { name: '취소' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(view.getByLabelText('배정·해제 사유'), { target: { value: '작성 중' } });
    fireEvent.click(view.getByRole('button', { name: '취소' }));
    expect((view.getByLabelText('적용할 결재선 버전') as HTMLSelectElement).value).toBe('v-2');
    expect(view.container.querySelector('[data-unsaved-changes="true"]')).toBeNull();
  });

  it('대표에게 설정 메뉴를 보이지 않고 결재 기능이 없으면 완료함도 숨긴다', async () => {
    const initial = page(); initial.data.workspacePrincipal = { roles: ['EMPLOYEE', 'COMPANY_REPRESENTATIVE'] };
    const view = await show(<App initial={initial} />);
    const menu = view.getByLabelText('주 메뉴');
    expect(menu.querySelector('a[href="/admin/approval-routes"]')).toBeNull();
    expect(menu.querySelector('a[href="/approvals/completed"]')).toBeNull();
  });

  it('배정 범위에 맞는 조회 대상을 보내고 실제 편집 대상은 조회 결과로 고정한다', async () => {
    const view = await show(<App initial={assignment()} />);
    expect(view.getByRole('heading', { name: '결재선 배정' })).toBeTruthy();
    fireEvent.change(view.getByLabelText('배정 범위'), { target: { value: 'ORGANIZATION' } });
    const search = view.getByRole('search', { name: '결재선 배정 대상 조회' }) as HTMLFormElement;
    fireEvent.change(view.getByLabelText('대상 조직'), { target: { value: 'o-1' } });
    expect(new FormData(search).get('organizationId')).toBe('o-1');
    expect(new FormData(search).has('employeeId')).toBe(false);
    const command = view.container.querySelector('form[action="/admin/approval-route-assignments"][method="post"]') as HTMLFormElement;
    expect(new FormData(command).get('employeeId')).toBe('e-1');
  });

  it('실패 후 입력도 이동 확인으로 보존하고 버전 충돌은 명시 확인으로 회복한다', async () => {
    const view = await show(<App initial={assignment({ employees: [{ ...employee, version: 6 }], form: { scopeKind: 'EMPLOYEE', employeeId: 'e-1', organizationId: null, routeVersionId: 'v-2', validFrom: '2026-10-29', expectedVersion: 5, reason: '입력 보존', commandId: 'failed-command' } })} />);
    expect(view.container.querySelector('[data-unsaved-changes="true"]')).toBeTruthy();
    expect((view.getByRole('button', { name: '저장' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(view.getByRole('button', { name: '최신 대상 확인 후 입력 유지' }));
    const command = view.container.querySelector('form.t-route-assignment-form') as HTMLFormElement;
    expect(new FormData(command).get('expectedVersion')).toBe('6');
    expect(new FormData(command).get('commandId')).not.toBe('failed-command');
    expect((view.getByLabelText('배정·해제 사유') as HTMLTextAreaElement).value).toBe('입력 보존');
  });

  it('내 정보에 실제 선택 경로와 신청 차단 사유를 표시한다', async () => {
    const view = await show(<App initial={{ page: 'workspace/profile', csrf, data: { ...principal, profile: { employeeNo: 'E001', employeeName: '김직원', companyName: '합성회사', email: 'e@example.test', joinedOn: '2026-10-01', leftOn: null, organizationName: '개발팀', scheduleName: null, approverName: null, approverEligible: false, roles: ['EMPLOYEE'], asOf: '2026-10-28T00:00:00Z', approvalPreview: { source: 'ORGANIZATION_ROUTE', routeName: '팀 결재', approverName: null, eligible: false, message: '조직장 공석으로 재지정이 필요합니다.' } } } }} />);
    expect(view.getByText('조직별 결재선 · 팀 결재')).toBeTruthy();
    expect(view.getByText('조직장 공석으로 재지정이 필요합니다.')).toBeTruthy();
  });

  it('신청 상세의 최초 해석과 현재 담당을 구분한다', async () => {
    const request = { id: 'r-1', targetEmployeeName: '김직원', kind: 'LEAVE_DAY', fromDate: '2026-11-01', toDate: '2026-11-01', quantityDays: 1, status: 'PENDING', effectStatus: 'NONE', version: 2, reason: '연차', approverName: '현재담당', originalRequestId: null };
    const view = await show(<App initial={{ page: 'request/detail', csrf, data: { ...principal, request, detail: { days: [], correction: null, history: [], routeSnapshot: { routeCode: 'TEAM', routeName: '팀 결재', revision: 2, scopeKind: 'ORGANIZATION', approverKind: 'ORGANIZATION_HEAD', organizationName: '개발팀', resolvedApproverName: '최초조직장', resolvedAt: '2026-10-28T00:00:00Z' } }, admin: false, canDecide: false, pageCommand: 'decision-command' } }} />);
    expect(view.getByRole('heading', { name: '제출 당시 결재선' })).toBeTruthy();
    expect(view.getByText('최초조직장')).toBeTruthy();
    expect(view.getByText('현재 결재 담당 현재담당')).toBeTruthy();
  });

  it('완료 결재는 설명과 페이지를 제공한다', async () => {
    const view = await show(<App initial={{ page: 'request/list', csrf, data: { ...principal, title: '내가 처리한 결재', requests: [], completed: true, page: 1, hasNext: true } }} />);
    expect(view.getByText(/본인이 승인하거나 반려한 신청/)).toBeTruthy();
    expect(view.getByRole('link', { name: '다음' }).getAttribute('href')).toBe('/approvals/completed?page=2');
  });
});
