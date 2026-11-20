// @vitest-environment jsdom
import { cleanup, fireEvent, render, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { RoleMembersSheet } from './RoleMembersSheet';

const current = { employeeId: 'existing', employeeNo: 'E01', employeeName: '기존 사원', organizationName: '제품팀',
  available: true, editable: true, version: 2, restriction: '' };
const candidate = { ...current, employeeId: 'new', employeeNo: 'E02', employeeName: '새 사원', version: 0 };
const model = { role: 'APPROVER', members: [current], q: '사원', page: 0, hasNext: false, total: 1 };
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'example-token' };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
  status, headers: { 'Content-Type': 'application/json' },
});
const success = (options?: RequestInit, affectedSelf = false) => {
  const { rows } = JSON.parse(String(options?.body));
  return json({ affectedSelf, rows: rows.map((row: { clientRowId: string; employeeId: string; expectedVersion: number }) =>
    ({ clientRowId: row.clientRowId, employeeId: row.employeeId, version: row.expectedVersion + 1 })) });
};

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal',
    { configurable: true, value(this: HTMLDialogElement) { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close',
    { configurable: true, value(this: HTMLDialogElement) { this.open = false; } });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it('stages a grant and revocation and saves only selected role rows after confirmation', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(json({ rows: [candidate], page: 0, hasNext: false }))
    .mockImplementationOnce(async (_url, options) => success(options));
  vi.stubGlobal('fetch', fetcher);
  const reload = vi.fn();
  const view = render(<RoleMembersSheet model={model} csrf={csrf} reload={reload} />);
  fireEvent.click(view.getByRole('button', { name: '사원 추가' }));
  const picker = within(view.getByRole('dialog', { name: '사원 추가' }));
  fireEvent.click(await picker.findByRole('checkbox', { name: '새 사원 후보 선택' }));
  fireEvent.click(picker.getByRole('button', { name: '선택한 사원 추가' }));
  expect(await view.findByText('부여 예정')).toBeTruthy();
  fireEvent.click(await view.findByLabelText('기존 사원 권한 행 선택'));
  fireEvent.click(view.getByRole('button', { name: '권한 해제' }));
  expect(await view.findByText('해제 예정')).toBeTruthy();
  expect(view.container.querySelector('[data-unsaved-changes="true"]')).toBeTruthy();
  expect(fetcher).toHaveBeenCalledTimes(1);
  fireEvent.click(view.getByRole('button', { name: '저장' }));
  const confirm = within(view.getByRole('dialog', { name: '권한 변경 확인' }));
  fireEvent.change(confirm.getByLabelText('변경 사유'), { target: { value: '결재 업무 조정' } });
  fireEvent.click(confirm.getByRole('button', { name: '2건 저장' }));
  fireEvent.click(await view.findByRole('button', { name: '목록 확인' }));
  await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
  const [path, options] = fetcher.mock.calls[1]!;
  expect(path).toBe('/admin/access/roles/APPROVER/members/bulk');
  expect(new Headers(options?.headers).get('X-CSRF-TOKEN')).toBe(csrf.token);
  expect(JSON.parse(String(options?.body))).toMatchObject({ reason: '결재 업무 조정', rows: [
    { employeeId: 'new', expectedVersion: 0, operation: 'GRANT' },
    { employeeId: 'existing', expectedVersion: 2, operation: 'REVOKE' },
  ] });
});

it('keeps a conflicting draft and requires accepting the latest row before retry', async () => {
  const fetcher = vi.fn<typeof fetch>().mockImplementationOnce(async (_url, options) => {
    const { rows } = JSON.parse(String(options?.body));
    return json({ message: '최신 상태를 확인해 주세요.', errors: [{ clientRowId: rows[0].clientRowId, message: '버전 충돌' }],
      latest: [{ clientRowId: rows[0].clientRowId, employeeId: 'existing', version: 3, granted: true, available: true }] }, 409);
  }).mockImplementationOnce(async (_url, options) => success(options));
  vi.stubGlobal('fetch', fetcher);
  const view = render(<RoleMembersSheet model={model} csrf={csrf} reload={vi.fn()} />);
  fireEvent.click(await view.findByLabelText('기존 사원 권한 행 선택'));
  fireEvent.click(view.getByRole('button', { name: '권한 해제' }));
  fireEvent.click(view.getByRole('button', { name: '저장' }));
  let confirm = within(view.getByRole('dialog', { name: '권한 변경 확인' }));
  fireEvent.change(confirm.getByLabelText('변경 사유'), { target: { value: '업무 변경' } });
  fireEvent.click(confirm.getByRole('button', { name: '1건 저장' }));
  expect(await view.findByText('최신 상태를 확인해 주세요.')).toBeTruthy();
  expect(view.getByRole('button', { name: '저장' })).toHaveProperty('disabled', true);
  fireEvent.click(view.getByRole('button', { name: '최신 상태 반영' }));
  fireEvent.click(view.getByRole('button', { name: '저장' }));
  confirm = within(view.getByRole('dialog', { name: '권한 변경 확인' }));
  expect(confirm.getByLabelText('변경 사유')).toHaveProperty('value', '업무 변경');
  fireEvent.click(confirm.getByRole('button', { name: '1건 저장' }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
  const first = JSON.parse(String(fetcher.mock.calls[0]![1]?.body));
  const second = JSON.parse(String(fetcher.mock.calls[1]![1]?.body));
  expect(second.commandId).not.toBe(first.commandId);
  expect(second.rows[0].expectedVersion).toBe(3);
});

it('offers a roster refresh when another administrator already granted the whole draft', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(json({ rows: [candidate], page: 0, hasNext: false }))
    .mockImplementationOnce(async (_url, options) => {
      const { rows } = JSON.parse(String(options?.body));
      return json({ message: '최신 상태를 확인해 주세요.',
        latest: [{ clientRowId: rows[0].clientRowId, employeeId: 'new', version: 1, granted: true, available: true }] }, 409);
    });
  const reload = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const view = render(<RoleMembersSheet model={model} csrf={csrf} reload={reload} />);
  fireEvent.click(view.getByRole('button', { name: '사원 추가' }));
  const picker = within(view.getByRole('dialog', { name: '사원 추가' }));
  fireEvent.click(await picker.findByRole('checkbox', { name: '새 사원 후보 선택' }));
  fireEvent.click(picker.getByRole('button', { name: '선택한 사원 추가' }));
  fireEvent.click(view.getByRole('button', { name: '저장' }));
  const confirm = within(view.getByRole('dialog', { name: '권한 변경 확인' }));
  fireEvent.change(confirm.getByLabelText('변경 사유'), { target: { value: '새 부여 확인' } });
  fireEvent.click(confirm.getByRole('button', { name: '1건 저장' }));
  fireEvent.click(await view.findByRole('button', { name: '최신 상태 반영' }));
  expect(view.queryByText('부여 예정')).toBeNull();
  fireEvent.click(view.getByRole('button', { name: '최신 목록 조회' }));
  expect(reload).toHaveBeenCalledTimes(1);
  expect(fetcher).toHaveBeenCalledTimes(2);
});

it('keeps the exact retry request and shows a receipt before a self change requires login', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(json({ message: 'temporary' }, 503))
    .mockImplementationOnce(async (_url, options) => success(options, true));
  const reload = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const view = render(<RoleMembersSheet model={model} csrf={csrf} reload={reload} actorEmployeeId="existing" />);
  fireEvent.click(await view.findByLabelText('기존 사원 권한 행 선택'));
  fireEvent.click(view.getByRole('button', { name: '권한 해제' }));
  fireEvent.click(view.getByRole('button', { name: '저장' }));
  let confirm = within(view.getByRole('dialog', { name: '권한 변경 확인' }));
  expect(confirm.getByText('내 권한이 바뀌면 저장 뒤 다시 로그인해야 합니다.')).toBeTruthy();
  fireEvent.change(confirm.getByLabelText('변경 사유'), { target: { value: '담당 변경' } });
  fireEvent.click(confirm.getByRole('button', { name: '1건 저장' }));
  fireEvent.click(await view.findByRole('button', { name: '같은 내용으로 다시 확인' }));
  confirm = within(view.getByRole('dialog', { name: '권한 변경 확인' }));
  expect(confirm.getByLabelText('변경 사유')).toHaveProperty('disabled', true);
  fireEvent.click(confirm.getByRole('button', { name: '같은 내용 확인' }));
  const receipt = within(await view.findByRole('dialog', { name: '권한 변경 완료' }));
  expect(receipt.getByText('내 권한이 변경되었습니다. 다시 로그인해 주세요.')).toBeTruthy();
  expect(receipt.getByRole('link', { name: '다시 로그인' }).getAttribute('href')).toBe('/login');
  expect(view.queryByLabelText('기존 사원 권한 행 선택')).toBeNull();
  expect(reload).not.toHaveBeenCalled();
  expect(fetcher.mock.calls[0]![1]?.body).toBe(fetcher.mock.calls[1]![1]?.body);
});

it('keeps previous navigation after an empty candidate page and retains selections across pages', async () => {
  const first = { rows: [candidate], page: 0, hasNext: true };
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(json(first))
    .mockResolvedValueOnce(json({ rows: [], page: 1, hasNext: false }))
    .mockResolvedValueOnce(json(first));
  vi.stubGlobal('fetch', fetcher);
  const view = render(<RoleMembersSheet model={model} csrf={csrf} reload={vi.fn()} />);
  fireEvent.click(view.getByRole('button', { name: '사원 추가' }));
  const picker = within(view.getByRole('dialog', { name: '사원 추가' }));
  fireEvent.click(await picker.findByRole('checkbox', { name: '새 사원 후보 선택' }));
  fireEvent.click(picker.getByRole('button', { name: '다음' }));
  expect(await picker.findByText('부여 가능한 사원이 없습니다.', { exact: false })).toBeTruthy();
  fireEvent.click(picker.getByRole('button', { name: '이전' }));
  expect(await picker.findByRole('checkbox', { name: '새 사원 후보 선택' })).toHaveProperty('checked', true);
});
