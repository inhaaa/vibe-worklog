// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { WorkflowPages } from './WorkflowPages';

const first = { id: 'employee-a', employeeNo: 'E001', displayName: '김가람' };
const second = { id: 'employee-b', employeeNo: 'E002', displayName: '서유진' };
const day = '2026-11-18';
const csrf = { token: 'csrf-test', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const base = '/admin/leave-balances/bulk';
const bucket = { id: 'bucket-b', baseDate: '2026-01-01', usableFrom: '2026-01-01', usableTo: null,
  sourceReference: '이전 원장', version: 4, balanceDays: 4 };
const model = { employee: first, employees: [first, second], buckets: [],
  form: { employeeId: first.id, baseDate: day, usableFrom: day, usableTo: '2027-11-17', commandId: 'single' } };
type TestRow = { clientRowId: string; operation: string; employeeNo: string; expectedVersion: number | null };
type Command = { commandId: string; rows: TestRow[] };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status,
  headers: { 'Content-Type': 'application/json' } });
const review = (command: Command) => ({ valid: true, rows: command.rows.map(row => ({
  clientRowId: row.clientRowId, employeeName: row.employeeNo === second.employeeNo ? second.displayName : first.displayName,
  beforeDays: row.operation === 'OPENING' ? 0 : 4, afterDays: row.operation === 'OPENING' ? 7.5 : 3.5,
  expectedVersion: row.operation === 'OPENING' ? null : 4, errors: [],
})) });
const receipt = (command: Command) => ({ batchId: command.commandId, rows: command.rows.map((row, i) =>
  ({ clientRowId: row.clientRowId, id: i ? 'bucket-b' : 'bucket-a', version: i ? 5 : 1 })) });
type View = ReturnType<typeof render>;

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
async function show() {
  const view = render(<WorkflowPages page="request/balances" data={model} csrf={csrf} reload={vi.fn()} />);
  fireEvent.click(view.getByRole('button', { name: '사원별 일괄 입력' }));
  await view.findByRole('grid', { name: '연차 일괄 입력 시트' });
  return view;
}
async function edit(view: View, label: string, value: string) {
  const cell = await view.findByLabelText(label);
  if (!cell.matches('input,select')) fireEvent.click(cell, { detail: 1 });
  const input = await waitFor(() => {
    const control = view.getByLabelText(label);
    expect(control.matches('input,select')).toBe(true);
    return control;
  });
  fireEvent.change(input, { target: { value } });
  fireEvent.keyDown(input, { key: 'Enter' });
  await waitFor(() => expect(input.isConnected).toBe(false));
}
const button = (view: View, name: string) => view.getByRole('button', { name }) as HTMLButtonElement;
async function opening(view: View) {
  await edit(view, '사번 1', 'E001');
  await edit(view, '일수 1', '7.5');
  await edit(view, '근거 참조 1', '이관대장 A');
  await edit(view, '사유 1', '연차 이관');
}

it('두 사원의 기초·조정을 미리 보고 확인한 버전으로 한 번 저장한 뒤 각각의 원장을 연다', async () => {
  const saves: Command[] = [], previews: Command[] = [];
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === base + '/options?employeeNo=E002') return json({ buckets: [bucket] });
    expect(init?.headers).toMatchObject({ [csrf.headerName]: csrf.token, 'Content-Type': 'application/json' });
    const command: Command = JSON.parse(String(init?.body));
    if (url === base + '/preview') { previews.push(command); return json(review(command)); }
    if (url === base + '/save') { saves.push(command); return json(receipt(command)); }
    throw Error('예상하지 않은 URL ' + url);
  });
  vi.stubGlobal('fetch', fetcher);
  const view = await show();
  expect(button(view, '일괄 저장').disabled).toBe(true);
  await opening(view);
  fireEvent.click(button(view, '행 추가'));
  await edit(view, '처리 구분 2', 'ADJUSTMENT');
  await edit(view, '사번 2', 'E002');
  await edit(view, '조정할 묶음 2', 'bucket-b');
  await edit(view, '일수 2', '-0.5');
  await edit(view, '근거 참조 2', '반일 대조');
  await edit(view, '사유 2', '입력 정정');
  fireEvent.click(button(view, '미리보기'));
  const preview = await view.findByRole('region', { name: '일괄 입력 미리보기' });
  expect(preview.textContent).toContain('0일 → 7.5일');
  expect(preview.textContent).toContain('4일 → 3.5일');
  expect(previews[0].rows).toMatchObject([
    { operation: 'OPENING', employeeNo: 'E001', quantityDays: '7.5', effectiveOn: day, usableFrom: day,
      usableTo: '2027-11-17', bucketId: null, expectedVersion: null, sourceReference: '이관대장 A', reason: '연차 이관' },
    { operation: 'ADJUSTMENT', employeeNo: 'E002', quantityDays: '-0.5', effectiveOn: day, usableFrom: null,
      usableTo: null, bucketId: 'bucket-b', expectedVersion: null, sourceReference: '반일 대조', reason: '입력 정정' },
  ]);
  expect(saves).toHaveLength(0);
  await edit(view, '사유 1', '인수 원장 대조');
  expect(view.queryByRole('region', { name: '일괄 입력 미리보기' })).toBeNull();
  expect(button(view, '일괄 저장').disabled).toBe(true);
  fireEvent.click(button(view, '미리보기'));
  await waitFor(() => expect(button(view, '일괄 저장').disabled).toBe(false));
  const save = button(view, '일괄 저장');
  fireEvent.click(save);
  fireEvent.click(save);
  const a = await view.findByRole('link', { name: 'E001 · 김가람 원장 보기' });
  expect(a.getAttribute('href')).toBe('/admin/leave-balances/buckets/bucket-a');
  expect(view.getByRole('link', { name: 'E002 · 서유진 원장 보기' }).getAttribute('href')).toBe('/admin/leave-balances/buckets/bucket-b');
  expect(saves).toHaveLength(1);
  expect(saves[0].rows.map(row => row.expectedVersion)).toEqual([null, 4]);
  expect(saves[0].rows[0]).toMatchObject({ reason: '인수 원장 대조' });
  expect(view.queryByRole('button', { name: '일괄 저장' })).toBeNull();
});

it('행별 오류와 저장 충돌을 표시하고 행 제거·수정 뒤 다시 확인하기 전에는 저장을 막는다', async () => {
  let call = 0, lookups = 0;
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
    if (url.endsWith('/options?employeeNo=E002')) return json({ buckets: [{ ...bucket, balanceDays: ++lookups === 1 ? 4 : 5 }] });
    const command: Command = JSON.parse(String(init.body));
    const result = review(command);
    if (url.endsWith('/preview') && ++call !== 1) return json(result);
    const conflict = url.endsWith('/save');
    return json({ valid: false, rows: result.rows.map((row, i) => (i === result.rows.length - 1
      ? { ...row, beforeDays: conflict ? 5 : null, afterDays: null, errors: [{ field: conflict ? 'expectedVersion' : 'quantityDays',
        message: conflict ? '묶음이 변경되었습니다.' : '0.5일 단위로 입력해 주세요.' }] } : row)) }, conflict ? 409 : 200);
  });
  const view = await show();
  await edit(view, '처리 구분 1', 'ADJUSTMENT');
  await edit(view, '사번 1', 'E002');
  await edit(view, '조정할 묶음 1', 'bucket-b');
  await edit(view, '일수 1', '-0.5');
  await edit(view, '근거 참조 1', '반일 대조');
  await edit(view, '사유 1', '원장 조정');
  fireEvent.click(button(view, '행 추가'));
  await edit(view, '일수 2', '0.25');
  fireEvent.click(button(view, '미리보기'));
  const preview = await view.findByRole('region', { name: '일괄 입력 미리보기' });
  expect(preview.textContent).toContain('0.5일 단위로 입력해 주세요.');
  expect(preview.textContent).toContain('4일 → 3.5일');
  expect(button(view, '일괄 저장').disabled).toBe(true);
  fireEvent.click(view.getByRole('checkbox', { name: '2행 선택' }));
  fireEvent.click(button(view, '선택 행 제거'));
  expect(view.queryByRole('region', { name: '일괄 입력 미리보기' })).toBeNull();
  fireEvent.click(button(view, '미리보기'));
  await waitFor(() => expect(button(view, '일괄 저장').disabled).toBe(false));
  fireEvent.click(button(view, '일괄 저장'));
  await waitFor(() => expect(view.getByRole('region', { name: '일괄 입력 미리보기' }).textContent).toContain('묶음이 변경되었습니다.'));
  expect(view.getByText('현재 잔여 5일')).toBeTruthy();
  await waitFor(() => expect(view.getByLabelText('조정할 묶음 1').textContent).toContain('잔여 5일'));
  expect(button(view, '일괄 저장').disabled).toBe(true);
});

it('저장 응답이 끊기면 입력을 잠그고 같은 명령을 재전송한다', async () => {
  const attempts: string[] = [];
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
    const command: Command = JSON.parse(String(init.body));
    if (url.endsWith('/preview')) return json(review(command));
    attempts.push(String(init.body));
    if (attempts.length === 1) throw new TypeError('network');
    return json(receipt(command));
  });
  const view = await show();
  await opening(view);
  fireEvent.click(button(view, '미리보기'));
  await waitFor(() => expect(button(view, '일괄 저장').disabled).toBe(false));
  fireEvent.click(button(view, '일괄 저장'));
  const retry = await view.findByRole('button', { name: '같은 내용으로 저장 결과 확인' });
  expect(button(view, '행 추가').disabled).toBe(true);
  fireEvent.click(retry);
  await view.findByRole('link', { name: 'E001 · 김가람 원장 보기' });
  expect(attempts).toHaveLength(2);
  expect(attempts[1]).toBe(attempts[0]);
});

it('조정할 사원을 바꾸면 이전 사원의 늦은 조회 결과로 묶음을 고를 수 없다', async () => {
  let finishFirst!: (r: Response) => void;
  vi.stubGlobal('fetch', async (url: string) => url.endsWith('E001')
    ? new Promise<Response>(resolve => { finishFirst = resolve; })
    : json({ buckets: [bucket] }));
  const view = await show();
  await edit(view, '처리 구분 1', 'ADJUSTMENT');
  await edit(view, '사번 1', 'E001');
  await edit(view, '사번 1', 'E002');
  await act(async () => finishFirst(json({ buckets: [{ ...bucket, id: 'bucket-a', sourceReference: '다른 사원 원장' }] })));
  fireEvent.click(await view.findByLabelText('조정할 묶음 1'), { detail: 1 });
  await waitFor(() => expect(view.getByLabelText('조정할 묶음 1').tagName).toBe('SELECT'));
  const options = (view.getByLabelText('조정할 묶음 1') as HTMLSelectElement).options;
  expect(Array.from(options, item => item.value)).toContain('bucket-b');
  expect(Array.from(options, item => item.value)).not.toContain('bucket-a');
});
