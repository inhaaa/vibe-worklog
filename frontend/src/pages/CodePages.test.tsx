// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { CodePages } from './CodePages';
import type { PageEnvelope } from '../types';

const csrf = { token: 'csrf', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const principal = { workspacePrincipal: { roles: ['EMPLOYEE', 'ATTENDANCE_ADMIN'] }, workspaceEmployee: { employeeName: '관리자', companyName: '합성회사' } };
const group = { id: 'g-1', code: 'LOCATION', name: '근무 구분', description: '분류 전용', sortOrder: 0, active: true, systemOwned: false, version: 2 };
const code = { ...group, id: 'c-1', groupId: 'g-1', code: 'OFFICE', name: '사무실', version: 3 };
const type = { id: 't-1', code: 'ANNUAL', name: '종일 연차', active: true, version: 1, typeVersionId: 'v-1', revision: 1, effectiveFrom: '0001-01-01', enabled: true, systemKind: 'ANNUAL_LEAVE', unit: 'DAY', payKind: 'UNSPECIFIED', deductionRule: 'LEAVE_DAY', recognitionRule: 'NOT_SUPPORTED', basis: '기존 종일 연차', supported: true, capabilityMessage: '종일 연차만 지원' };
const row = (original: typeof group | typeof code | typeof type) => ({ ...original, clientRowId: `row-${original.id}`, expectedVersion: original.version, operation: 'UPDATE' });
const sheet = (original: typeof group | typeof code | typeof type) => ({ commandId: `command-${original.id}`, reason: '', rows: [row(original)], latestRows: [original], errors: [] });
function common(overrides: Record<string, unknown> = {}): PageEnvelope {
  return { page: 'org/common-codes', csrf, data: { ...principal, commonCodes: { q: '', page: 0, hasNext: false, groups: [group], groupId: group.id, codeQ: '', codePage: 0, codeHasNext: false, codes: [code], groupSheet: sheet(group), codeSheet: sheet(code), ...overrides } } };
}
function attendance(overrides: Record<string, unknown> = {}): PageEnvelope {
  return { page: 'org/attendance-types', csrf, data: { ...principal, attendanceTypes: { today: '2026-10-28', q: '', page: 0, hasNext: false, types: [type], versions: [{ ...type, id: 'v-1', typeId: 't-1', reason: '기초 정책', createdAt: '2026-10-28T01:00:00Z' }], sheet: sheet(type), ...overrides } } };
}
async function show(initial: PageEnvelope) {
  const view = render(<App initial={initial} />);
  await Promise.all(Array.from(view.container.querySelectorAll('.w-sheet[role="region"]')).map(sheet =>
    view.findByRole('grid', { name: sheet.getAttribute('aria-label')! }, { timeout: 5000 })));
  return view;
}
async function editCell(view: ReturnType<typeof render>, label: string, value: string, finish = true) {
  const display = await view.findByLabelText(label);
  if (!display.matches('input,select')) fireEvent.click(display.closest('.ag-cell')!, { detail: 1 });
  const input = await waitFor(() => {
    const editor = view.getByLabelText(label);
    expect(editor.matches('input,select')).toBe(true);
    return editor;
  });
  fireEvent.change(input, { target: { value } });
  if (finish) {
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });
    await waitFor(() => expect(input.isConnected).toBe(false));
  }
  return input;
}
function form(view: ReturnType<typeof render>, action = '/admin/attendance-types/bulk') { return view.container.querySelector(`form[action="${action}"]`) as HTMLFormElement; }
function payload(view: ReturnType<typeof render>, action?: string) { return JSON.parse(String(new FormData(form(view, action)).get('rows'))) as Record<string, unknown>[]; }
async function save(view: ReturnType<typeof render>, action: string, title: string, reason?: string) {
  fireEvent.click(within(form(view, action)).getByRole('button', { name: '저장' }));
  const dialog = await view.findByRole('dialog', { name: '변경 내용 저장' });
  if (reason !== undefined) fireEvent.change(within(dialog).getByLabelText(`${title} 일괄 변경 사유`), { target: { value: reason } });
  fireEvent.click(within(dialog).getByRole('button', { name: /건 저장$/ }));
  return dialog;
}
beforeEach(() => {
  history.replaceState(null, '', '/admin/attendance-types');
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value(this: HTMLDialogElement) { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value(this: HTMLDialogElement) { this.open = false; } });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe('공통코드·근태코드 React 관리', () => {
  it('회사 휴가 정책을 선택해 유급 여부·차감·사용 단위와 신청 허용을 저장한다', async () => {
    const view = await show(attendance());
    fireEvent.click(view.getByRole('button', { name: '행 추가' }));
    await editCell(view, '처리 방식 2', 'COMPANY_LEAVE');
    await editCell(view, '단위 2', 'DAY_HALF');
    await editCell(view, '유급 구분 2', 'PAID');
    await editCell(view, '차감 규칙 2', 'LEAVE_DAY');
    fireEvent.click(view.getByLabelText('신규 신청 허용 2'));
    expect(payload(view)[0]).toMatchObject({systemKind:null,recognitionRule:'COMPANY_LEAVE',unit:'DAY_HALF',payKind:'PAID',deductionRule:'LEAVE_DAY',enabled:true});
  });
  it('평소 입력 박스 없이 값을 표시하고 클릭한 셀만 편집한다', async () => {
    const view = await show(common());
    const grid = view.getByRole('grid', { name: '코드그룹 편집 시트' });
    expect(grid.querySelector('input:not([type=checkbox]),select')).toBeNull();
    const name = view.getByLabelText('코드그룹 이름 1');
    expect(name.textContent).toBe(group.name);
    fireEvent.click(name.closest('.ag-cell')!, { detail: 1 });
    const editor = await view.findByRole('textbox', { name: '코드그룹 이름 1' });
    fireEvent.change(editor, { target: { value: '클릭 편집' } });
    expect(payload(view, '/admin/common-code-groups/bulk')[0].name).toBe('클릭 편집');
    fireEvent.keyDown(editor, { key: 'Escape', code: 'Escape' });
    await waitFor(() => expect(payload(view, '/admin/common-code-groups/bulk')).toEqual([]));
  });

  it('편집 중 저장해도 마지막 값과 새 명령을 함께 보낸다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(() => new Promise(() => {}));
    vi.stubGlobal('fetch', fetcher);
    const view = await show(common());
    fireEvent.click(view.getByLabelText('코드그룹 이름 1').closest('.ag-cell')!, { detail: 1 });
    const editor = await view.findByRole('textbox', { name: '코드그룹 이름 1' });
    fireEvent.change(editor, { target: { value: '저장 직전 값' } });
    await save(view, '/admin/common-code-groups/bulk', '코드그룹', '표시명 정리');
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    const body = fetcher.mock.calls[0][1]?.body as URLSearchParams;
    expect(JSON.parse(body.get('rows')!)[0].name).toBe('저장 직전 값');
    expect(body.get('commandId')).not.toBe('command-g-1');
  });

  it('선택 셀은 한글 표시명을 보여주고 원시 코드로 저장한다', async () => {
    const view = await show(attendance());
    expect(view.getByLabelText('유급 구분 1').textContent).toBe('미확인');
    await editCell(view, '유급 구분 1', 'PAID', false);
    expect(payload(view)[0]).toMatchObject({ payKind: 'PAID', effectiveFrom: '2026-10-28' });
    fireEvent.keyDown(view.getByLabelText('유급 구분 1'), { key: 'Tab', code: 'Tab' });
    await waitFor(() => expect(view.getByLabelText('유급 구분 1').textContent).toBe('유급'));
  });

  it('비편집 상태의 필수값 누락도 저장 전에 검사한다', async () => {
    const fetcher = vi.fn();
    vi.stubGlobal('fetch', fetcher);
    const view = await show(common());
    await editCell(view, '코드그룹 이름 1', '');
    fireEvent.submit(form(view, '/admin/common-code-groups/bulk'));
    expect(fetcher).not.toHaveBeenCalled();
    await waitFor(() => expect(document.activeElement).toBe(view.getByRole('textbox', { name: '코드그룹 이름 1' })));
    expect(view.getByRole('navigation', { name: '코드그룹 편집 시트 오류 위치' })).toBeTruthy();
  });

  it('근태코드 셀 편집을 Esc로 취소하면 자동 보정한 적용일도 시작값으로 복원한다', async () => {
    const view = await show(attendance());
    const editor = await editCell(view, '근태코드 이름 1', '임시 이름', false);
    expect(payload(view)[0]).toMatchObject({ name: '임시 이름', effectiveFrom: '2026-10-28' });
    fireEvent.keyDown(editor, { key: 'Escape', code: 'Escape' });
    await waitFor(() => expect(payload(view)).toEqual([]));
    expect(view.getByLabelText('적용일 1').textContent).toBe('0001-01-01');
  });

  it('그룹·상세 편집을 안정적인 행 ID의 공통 그리드로 표시한다', async () => {
    const view = await show(common());
    const groups = await view.findByRole('grid', { name: '코드그룹 편집 시트' });
    const codes = await view.findByRole('grid', { name: '상세코드 편집 시트' });
    expect(groups.querySelector('[row-id="row-g-1"]')).toBeTruthy();
    expect(codes.querySelector('[row-id="row-c-1"]')).toBeTruthy();
    expect(view.container.querySelector('table')).toBeNull();
  });

  it('현재 페이지에서 선택한 근태코드의 이력만 표시하고 일괄 편집과 행 선택을 유지한다', async () => {
    const annual = { ...type, code: 'A01' };
    const custom = { ...type, id: 't-2', code: 'A010', name: '연차 초안' };
    const hidden = { ...type, id: 't-3', code: 'A011', name: '다른 페이지' };
    const entries = [custom, annual];
    const version = (entry: typeof type, revision = 1) => ({
      ...entry, id: `v-${entry.id}-${revision}`, typeId: entry.id, revision,
      reason: `${entry.code} 개정 ${revision}`, createdAt: '2026-10-28T01:00:00Z',
    });
    const view = await show(attendance({ q: 'a01', types: entries,
      sheet: { ...sheet(custom), rows: entries.map(row), latestRows: [...entries, hidden] },
      versions: [version(custom), version(annual, 2), version(annual), version(hidden)] }));
    const pick = view.getByRole('combobox', { name: '이력 조회 근태코드' }) as HTMLSelectElement;
    expect(pick.value).toBe(annual.id);
    expect(Array.from(pick.options, option => option.text)).toEqual(['A010 · 연차 초안', 'A01 · 종일 연차']);
    const annualHistory = within(await view.findByRole('grid', { name: '근태코드 버전 이력' }));
    expect(await annualHistory.findByText('A01 개정 2')).toBeTruthy();
    expect(annualHistory.queryByText('A010 개정 1')).toBeNull();
    fireEvent.click(view.getByLabelText('근태코드 1행 선택'));
    await editCell(view, '근태코드 이름 2', '편집 중인 연차');
    fireEvent.change(pick, { target: { value: custom.id } });
    const customHistory = within(await view.findByRole('grid', { name: '근태코드 버전 이력' }));
    expect(await customHistory.findByText('A010 개정 1')).toBeTruthy();
    expect(customHistory.queryByText('A01 개정 2')).toBeNull();
    expect((view.getByLabelText('근태코드 1행 선택') as HTMLInputElement).checked).toBe(true);
    expect(payload(view)).toEqual([expect.objectContaining({ id: annual.id, name: '편집 중인 연차' })]);
  });

  it('조회 조건이나 페이지가 바뀌면 보이는 근태코드에서 이력 선택을 새로 정한다', () => {
    const annual = { ...type, code: 'A01' };
    const correction = { ...type, id: 't-2', code: 'A02', name: '출퇴근 정정', systemKind: 'ATTENDANCE_CORRECTION' };
    const page = (q: string, pageNumber: number, types: typeof type[]) =>
      <CodePages {...attendance({ q, page: pageNumber, types })} reload={() => {}} />;
    const view = render(page('', 0, [annual, correction]));
    const pick = () => view.getByLabelText('이력 조회 근태코드') as HTMLSelectElement;
    fireEvent.change(pick(), { target: { value: correction.id } });
    view.rerender(page('a01', 0, [annual, correction]));
    expect(pick().value).toBe(annual.id);
    fireEvent.change(pick(), { target: { value: correction.id } });
    view.rerender(page('a01', 1, [annual, correction]));
    expect(pick().value).toBe(annual.id);
    view.rerender(page('attendance_correction', 0, [annual, correction]));
    expect(pick().value).toBe(correction.id);
    view.rerender(page('없는 코드', 0, []));
    expect(pick().disabled).toBe(true);
    expect(pick().value).toBe('');
  });

  it('근태코드 편집·불변 버전 이력을 공통 그리드로 표시한다', async () => {
    const view = await show(attendance());
    expect(await view.findByRole('grid', { name: '근태코드 편집 시트' })).toBeTruthy();
    const history = await view.findByRole('grid', { name: '근태코드 버전 이력' });
    expect(history.querySelector('[row-id="v-1"]')).toBeTruthy();
    expect(view.container.querySelector('table')).toBeNull();
  });

  it('숫자 정렬·필터 뒤에도 숨은 선택과 변경을 행 ID로 처리하고 취소한다', async () => {
    const first = { ...group, sortOrder: 10 };
    const second = { ...group, id: 'g-2', code: 'SECOND', name: '두번째 그룹', sortOrder: 2 };
    const view = await show(common({ groups: [first, second], groupSheet: { ...sheet(first), rows: [row(first), row(second)], latestRows: [first, second] } }));
    const grid = await view.findByRole('grid', { name: '코드그룹 편집 시트' });
    fireEvent.click(within(within(grid).getByRole('columnheader', { name: /^정렬/ })).getByText('정렬'));
    await waitFor(() => expect(grid.querySelector('[row-id="row-g-2"]')?.getAttribute('row-index')).toBe('0'));
    await editCell(view, '코드그룹 이름 1', '수정 근무 구분');
    fireEvent.click(view.getByLabelText('코드그룹 1행 선택'));
    fireEvent.change(view.getByLabelText('코드그룹 편집 시트 현재 페이지 필터'), { target: { value: '두번째' } });
    await waitFor(() => expect(view.getByText('숨은 선택 1행 · 숨은 변경 1행')).toBeTruthy());
    expect(payload(view, '/admin/common-code-groups/bulk')[0]).toMatchObject({ clientRowId: 'row-g-1', id: 'g-1', name: '수정 근무 구분' });
    const groups = within(view.getByRole('region', { name: '코드그룹 관리' }));
    fireEvent.click(groups.getByRole('button', { name: '비활성화' }));
    expect(payload(view, '/admin/common-code-groups/bulk')[0]).toMatchObject({ clientRowId: 'row-g-1', operation: 'DEACTIVATE' });
    fireEvent.click(groups.getByRole('button', { name: '변경 취소' }));
    expect(payload(view, '/admin/common-code-groups/bulk')).toEqual([]);
    fireEvent.change(view.getByLabelText('코드그룹 편집 시트 현재 페이지 필터'), { target: { value: '' } });
    expect((await view.findByLabelText('코드그룹 이름 1')).textContent).toBe(group.name);
    expect((view.getByLabelText('코드그룹 1행 선택') as HTMLInputElement).checked).toBe(false);
  });

  it('필터로 숨은 편집 행도 기존 저장 명령의 payload에 포함한다', async () => {
    const second = { ...group, id: 'g-2', code: 'SECOND', name: '두번째 그룹' };
    const fetcher = vi.fn<typeof fetch>().mockImplementation(() => new Promise(() => {}));
    vi.stubGlobal('fetch', fetcher);
    const view = await show(common({ groups: [group, second], groupSheet: { ...sheet(group), rows: [row(group), row(second)], latestRows: [group, second] } }));
    await view.findByRole('grid', { name: '코드그룹 편집 시트' });
    await editCell(view, '코드그룹 이름 1', '숨은 변경');
    fireEvent.change(view.getByLabelText('코드그룹 편집 시트 현재 페이지 필터'), { target: { value: '두번째' } });
    await waitFor(() => expect(view.getByText('숨은 선택 0행 · 숨은 변경 1행')).toBeTruthy());
    await save(view, '/admin/common-code-groups/bulk', '코드그룹', '표시명 정리');
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    expect(JSON.parse((fetcher.mock.calls[0][1]?.body as URLSearchParams).get('rows')!)).toEqual([
      expect.objectContaining({ clientRowId: 'row-g-1', id: 'g-1', name: '숨은 변경' }),
    ]);
  });

  it('공통코드의 모든 셀에 시트별 고유한 레이블을 붙인다', async () => {
    const view = await show(common());
    expect(view.getByLabelText('코드그룹 설명 1')).toBeTruthy();
    expect(view.getByLabelText('상세코드 설명 1')).toBeTruthy();
    expect(view.getByLabelText('코드그룹 정렬 1')).toBeTruthy();
    expect(view.getByLabelText('상세코드 사용 1')).toBeTruthy();
  });

  it('한 시트 저장이 다른 시트의 미저장 입력을 잃기 전에 확인한다', async () => {
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const view = await show(common());
    await editCell(view, '코드그룹 이름 1', '그룹 편집');
    await editCell(view, '상세코드 이름 1', '상세 편집');
    await save(view, '/admin/common-code-groups/bulk', '코드그룹', '그룹 정리');
    expect(confirm).toHaveBeenCalledWith('다른 시트에도 저장하지 않은 변경이 있습니다. 다른 시트의 편집을 버리고 이 시트를 저장할까요?');
    expect(fetcher).not.toHaveBeenCalled();
    expect(payload(view, '/admin/common-codes/bulk')[0].name).toBe('상세 편집');
  });

  it('신청 상세에 제출 당시 근태정책을 표시하지 않는다', async () => {
    const request = { id: 'r-1', targetEmployeeName: '김직원', kind: 'LEAVE_DAY', fromDate: '2026-11-01', toDate: '2026-11-01', quantityDays: 1, status: 'PENDING', effectStatus: 'NONE', version: 2, reason: '연차', approverName: '승인자', originalRequestId: null };
    const view = await show({ page: 'request/detail', csrf, data: { ...principal, request, detail: { days: [], correction: null, history: [], attendancePolicy: { ...type, id: 'v-1', typeId: 't-1' } }, admin: false, canDecide: false, pageCommand: 'decision-command' } });
    expect(view.queryByRole('heading', { name: '제출 당시 근태정책' })).toBeNull();
  });

  it('정책 이력에 등록자를 표시하고 시스템 기초 등록은 구분한다', async () => {
    const view = await show(attendance({ versions: [{ ...type, id: 'v-1', typeId: 't-1', reason: '관리자 변경', createdByName: '합성담당', createdAt: '2026-10-28T01:00:00Z' }, { ...type, id: 'v-0', typeId: 't-1', reason: '기초 정책', createdByName: null, createdAt: '2026-10-27T01:00:00Z' }] }));
    expect(view.getByText('합성담당')).toBeTruthy();
    expect(view.getByText('시스템 기초')).toBeTruthy();
  });

  it('최신 등록과 대상일 적용을 구분하고 다음 적용일·동일일 대체 버전을 보여준다', async () => {
    const versions = [
      { ...type, id: 'v-1', typeId: 't-1', reason: '첫 정책', createdAt: '2026-10-28T01:00:00Z' },
      { ...type, id: 'v-2', typeId: 't-1', revision: 2, effectiveFrom: '2026-11-01', enabled: false, reason: '예약 비활성', createdAt: '2026-10-28T01:00:00Z' },
      { ...type, id: 'v-3', typeId: 't-1', revision: 3, effectiveFrom: '2026-11-01', reason: '같은 날 정정', createdAt: '2026-10-28T01:00:00Z' },
    ];
    const view = await show(attendance({ versions }));
    expect(view.getByText(/최신 등록 정책\(예약 포함\)/)).toBeTruthy();
    expect(view.getByText(/이후 예약 정책은 보존/)).toBeTruthy();
    expect(view.getByText('2026-11-01 전까지')).toBeTruthy();
    expect(view.getByText('동일 적용일의 v3로 대체')).toBeTruthy();
  });

  it('관리자에게 별도 메뉴를 제공하고 대표에게 설정 메뉴를 숨긴다', async () => {
    const initial = common();
    const view = await show(initial);
    expect(view.getByRole('heading', { name: '공통코드 관리' })).toBeTruthy();
    expect(view.getByLabelText('주 메뉴').querySelector('a[href="/admin/common-codes"]')).toBeTruthy();
    expect(view.getByLabelText('주 메뉴').querySelector('a[href="/admin/attendance-types"]')).toBeTruthy();
    cleanup();
    initial.data.workspacePrincipal = { roles: ['EMPLOYEE', 'COMPANY_REPRESENTATIVE'] };
    const representative = await show(initial);
    expect(representative.getByLabelText('주 메뉴').querySelector('a[href="/admin/common-codes"]')).toBeNull();
  });

  it('그룹과 상세를 다른 명령으로 저장하며 변경 없는 행은 전송하지 않는다', async () => {
    const view = await show(common());
    const groups = within(view.getByRole('region', { name: '코드그룹 관리' }));
    const codes = within(view.getByRole('region', { name: '상세코드 관리' }));
    for (const name of ['조회', '행 추가', '비활성화', '저장', '변경 취소']) {
      expect(groups.getByRole('button', { name })).toBeTruthy();
      expect(codes.getByRole('button', { name })).toBeTruthy();
    }
    for (const scope of [groups, codes]) expect(scope.getByRole('button', { name: '비활성화' }).title).toBe('미저장 신규 행은 제거하고 저장된 행은 비활성 예정으로 표시합니다.');
    expect(payload(view, '/admin/common-code-groups/bulk')).toEqual([]);
    expect(payload(view, '/admin/common-codes/bulk')).toEqual([]);
    await editCell(view, '코드그룹 이름 1', '수정 그룹');
    expect(payload(view, '/admin/common-code-groups/bulk')[0]).toMatchObject({ id: 'g-1', expectedVersion: 2, name: '수정 그룹' });
    expect(payload(view, '/admin/common-codes/bulk')).toEqual([]);
    expect(new FormData(form(view, '/admin/common-code-groups/bulk')).get('_csrf')).toBe('csrf');
  });

  it('저장된 코드를 고정하고 새 행 제거·저장행 비활성 예정·취소를 지원한다', async () => {
    const view = await show(common());
    const codes = within(view.getByRole('region', { name: '상세코드 관리' }));
    expect(view.getByLabelText('상세코드 코드 1').getAttribute('aria-readonly')).toBe('true');
    fireEvent.click(codes.getByRole('button', { name: '행 추가' }));
    await editCell(view, '상세코드 코드 2', 'REMOTE');
    await editCell(view, '상세코드 이름 2', '재택');
    expect(payload(view, '/admin/common-codes/bulk')[0]).toMatchObject({ operation: 'CREATE', code: 'REMOTE' });
    fireEvent.click(view.getByLabelText('상세코드 2행 선택'));
    fireEvent.click(codes.getByRole('button', { name: '비활성화' }));
    await waitFor(() => expect(view.queryByLabelText('상세코드 코드 2')).toBeNull());
    fireEvent.click(view.getByLabelText('상세코드 1행 선택'));
    fireEvent.click(codes.getByRole('button', { name: '비활성화' }));
    expect(payload(view, '/admin/common-codes/bulk')[0].operation).toBe('DEACTIVATE');
    expect(codes.getByText('비활성 예정')).toBeTruthy();
    fireEvent.click(codes.getByRole('button', { name: '변경 취소' }));
    expect(payload(view, '/admin/common-codes/bulk')).toEqual([]);
  });

  it('시스템 소유 그룹·상세를 보호하고 미선택 그룹의 상세 입력을 막는다', async () => {
    const view = await show(common({ groups: [{ ...group, systemOwned: true }], groupSheet: sheet({ ...group, systemOwned: true }), codes: [{ ...code, systemOwned: true }], codeSheet: sheet({ ...code, systemOwned: true }) }));
    expect(view.getByLabelText('코드그룹 이름 1').getAttribute('aria-disabled')).toBe('true');
    expect(view.getByLabelText('상세코드 이름 1').getAttribute('aria-disabled')).toBe('true');
    expect((view.getByLabelText('상세코드 1행 선택') as HTMLInputElement).disabled).toBe(true);
    cleanup();
    const empty = await show(common({ groupId: null, codes: [], codeSheet: { ...sheet(code), rows: [], latestRows: [] } }));
    expect((within(empty.getByRole('region', { name: '상세코드 관리' })).getByRole('button', { name: '행 추가' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('근태코드 신규 행은 미지원 초안이며 시스템 유형을 선택하지 못한다', async () => {
    const view = await show(attendance());
    expect(view.getByRole('heading', { name: '근태코드 관리' })).toBeTruthy();
    expect(payload(view)).toEqual([]);
    fireEvent.click(view.getByRole('button', { name: '행 추가' }));
    await editCell(view, '근태코드 코드 2', 'SICK');
    await editCell(view, '근태코드 이름 2', '병가');
    expect(payload(view)[0]).toMatchObject({ operation: 'CREATE', systemKind: null, enabled: false, effectiveFrom: '2026-10-28' });
    expect((view.getByLabelText('신규 신청 허용 2') as HTMLInputElement).disabled).toBe(true);
    expect(view.getAllByText(/계산·차감 계약 미지원/).length).toBeGreaterThan(0);
  });

  it('기존 근태코드 수정은 새 정책 내용만 보내고 버전 이력을 표시한다', async () => {
    const view = await show(attendance());
    expect(view.getByLabelText('근태코드 코드 1').getAttribute('aria-readonly')).toBe('true');
    await editCell(view, '근태코드 이름 1', '연차 표시 변경');
    await editCell(view, '적용일 1', '2026-11-01');
    expect(payload(view)[0]).toMatchObject({ expectedVersion: 1, name: '연차 표시 변경', effectiveFrom: '2026-11-01' });
    expect(view.getByRole('heading', { name: '근태코드 버전 이력' })).toBeTruthy();
    expect(view.getByText('기초 정책')).toBeTruthy();
    expect(view.getByText(/기존 대기·완료 신청은 제출 당시 정책/)).toBeTruthy();
  });

  it('근태코드 삭제는 적용일의 비활성 후속 버전이며 취소로 복구한다', async () => {
    const view = await show(attendance());
    fireEvent.click(view.getByLabelText('근태코드 1행 선택'));
    fireEvent.click(view.getByRole('button', { name: '비활성화' }));
    expect(payload(view)[0]).toMatchObject({ operation: 'DEACTIVATE', enabled: false, effectiveFrom: '2026-10-28' });
    expect(view.getByLabelText('적용일 1').getAttribute('aria-disabled')).toBe('false');
    await editCell(view, '적용일 1', '2026-11-10');
    expect(payload(view)[0].effectiveFrom).toBe('2026-11-10');
    fireEvent.click(view.getByRole('button', { name: '변경 취소' }));
    expect(payload(view)).toEqual([]);
  });

  it('422 편집·행식별자·명령을 유지하고 필드 오류에 초점을 둔다', async () => {
    const draft = { ...row(type), name: '', effectiveFrom: '2026-10-28' };
    const view = await show(attendance({ sheet: { commandId: 'failed-command', reason: '입력 보존', rows: [draft], latestRows: [type], errors: [{ clientRowId: draft.clientRowId, field: 'name', message: '이름을 입력하세요' }] } }));
    expect(payload(view)[0].clientRowId).toBe(draft.clientRowId);
    expect(new FormData(form(view)).get('commandId')).toBe('failed-command');
    await waitFor(() => expect(document.activeElement).toBe(view.getByLabelText('근태코드 이름 1')));
    expect(view.getByRole('button', { name: /이름을 입력하세요/ })).toBeTruthy();
    expect(view.container.querySelector('[data-unsaved-changes="true"]')).toBeTruthy();
  });

  it('충돌은 최신 정책과 편집을 비교하고 명시 확인 뒤 새 명령으로 저장한다', async () => {
    const draft = { ...row(type), name: '편집 중', effectiveFrom: '2026-10-28' };
    const latest = { ...type, version: 2, name: '최신 정책' };
    const view = await show(attendance({ sheet: { commandId: 'old-command', reason: '충돌 검토', rows: [draft], latestRows: [latest], errors: [] } }));
    expect((view.getByRole('button', { name: '저장' }) as HTMLButtonElement).disabled).toBe(true);
    expect(view.getByText('최신 정책')).toBeTruthy();
    fireEvent.click(view.getByRole('button', { name: '최신값 확인 후 편집 유지' }));
    expect(payload(view)[0]).toMatchObject({ expectedVersion: 2, name: '편집 중' });
    expect(new FormData(form(view)).get('commandId')).not.toBe('old-command');
  });

  it('50행을 넘는 편집은 저장을 막고 검색·페이지 이동 전에 손실을 확인한다', async () => {
    const rows = Array.from({ length: 51 }, (_, i) => ({ ...row(type), clientRowId: `new-${i}`, id: null, expectedVersion: null, operation: 'CREATE', code: `C${i}` }));
    const view = render(<App initial={attendance({ sheet: { commandId: 'many', reason: '', rows, latestRows: [], errors: [] } })} />);
    expect((view.getByText('저장', { selector: 'button' }) as HTMLButtonElement).disabled).toBe(true);
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    fireEvent.submit(view.getByRole('search', { name: '근태코드 검색' }));
    expect(confirm).toHaveBeenCalled(); expect(fetcher).not.toHaveBeenCalled();
  });

  it('503 결과 미확정 재시도는 같은 명령을 쓰며 다시 편집하면 갱신한다', async () => {
    const response = new Response(JSON.stringify({ page: 'org/problem', csrf, data: { message: '결과 확인 필요' } }), { status: 503, headers: { 'Content-Type': 'application/vnd.worklog.page+json' } });
    Object.defineProperty(response, 'url', { value: new URL('/admin/attendance-types/bulk', location.href).href });
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => response.clone()); vi.stubGlobal('fetch', fetcher);
    const view = await show(attendance());
    await editCell(view, '근태코드 이름 1', '변경명');
    await save(view, '/admin/attendance-types/bulk', '근태코드', '검토');
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    const command = (fetcher.mock.calls[0][1]?.body as URLSearchParams).get('commandId');
    await waitFor(() => expect(view.getByText('결과 확인 필요')).toBeTruthy());
    await waitFor(() => expect(view.getByLabelText('근태코드 이름 1').hasAttribute('disabled')).toBe(false));
    fireEvent.click(view.getByLabelText('근태코드 이름 1').closest('.ag-cell')!, { detail: 1 });
    const unchangedEditor = await view.findByRole('textbox', { name: '근태코드 이름 1' });
    fireEvent.keyDown(unchangedEditor, { key: 'Escape', code: 'Escape' });
    expect(new FormData(form(view)).get('commandId')).toBe(command);
    fireEvent.submit(form(view));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect((fetcher.mock.calls[1][1]?.body as URLSearchParams).get('commandId')).toBe(command);
    await waitFor(() => expect(view.getByLabelText('근태코드 이름 1').hasAttribute('disabled')).toBe(false));
    await editCell(view, '근태코드 이름 1', '다시 수정');
    expect(new FormData(form(view)).get('commandId')).not.toBe(command);
  });
});
