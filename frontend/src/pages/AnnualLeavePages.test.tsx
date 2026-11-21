// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { WorkflowPages } from './WorkflowPages';
import type { AnnualCycleModel, AnnualCyclePreview, PersonalLedger } from './annualLeaveTypes';

const csrf = { headerName: 'X-TEST-CSRF', parameterName: '_csrf', token: 'synthetic-token' };
const base = '/admin/leave-balances/annual-cycle';
const policy = { version: 1, fiscalStartMonth: 1, grantDays: 12, carryLimitDays: 3 };
const model: AnnualCycleModel = { policy, year: 2026, yearSelected: false, today: '2026-01-05', publicDemo: false };
const preview: AnnualCyclePreview = { year: 2026, periodStart: '2026-01-01', periodEnd: '2026-12-31',
  rule: policy, postedCount: 1, existingCount: 2, laterCount: 1, pendingCount: 1, token: 'checked-state', canPost: true, rows: [
    { employeeId: 'employee-a', employeeNo: 'E101', employeeName: '김가람', previousDays: 4.5, grantDays: 12, carryDays: 3, expireDays: 1.5 },
    { employeeId: 'employee-b', employeeNo: 'E102', employeeName: '이소라', previousDays: 0, grantDays: 12, carryDays: 0, expireDays: 0 },
  ] };
const emptyPreview = { ...preview, rows: [], postedCount: 3, canPost: false };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
  status, headers: { 'Content-Type': 'application/json' },
});
const fetches = () => {
  const send = vi.fn<typeof fetch>();
  vi.stubGlobal('fetch', send);
  return send;
};
const button = (name: string) => screen.getByRole('button', { name }) as HTMLButtonElement;
const field = (name: string) => screen.getByLabelText(name) as HTMLInputElement;
const edit = (name: string, value: string) => fireEvent.change(field(name), { target: { value } });
const show = (values: Partial<AnnualCycleModel> = {}) =>
  render(<WorkflowPages page="request/annual-cycle" data={{ ...model, ...values }} csrf={csrf} reload={vi.fn()} />);
const approveExpiry = () => fireEvent.click(screen.getByRole('checkbox', { name: '소멸 대상과 일수를 확인했습니다.' }));
async function review() {
  fireEvent.click(button('미리보기'));
  return screen.findByRole('table', { name: '사원별 반영 예정' });
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it('미설정 기준에 일수를 채워 넣지 않고 입력한 회사 기준과 근거를 CSRF·기대 버전으로 저장한다', async () => {
  const send = fetches().mockResolvedValueOnce(json(policy));
  show({ policy: null });
  expect(screen.getByRole('link', { name: '입사·퇴사 연차 정산' }).getAttribute('href')).toBe('/admin/leave-balances/employment');
  expect(field('연간 부여 일수').value).toBe('');
  expect(field('이월 한도 일수').value).toBe('');
  expect(button('미리보기').disabled).toBe(true);
  edit('회계연도 시작 월', '1');
  edit('연간 부여 일수', '12.25');
  expect(field('연간 부여 일수').validity.stepMismatch).toBe(true);
  edit('연간 부여 일수', '12');
  edit('이월 한도 일수', '3');
  edit('기준 설정 사유', '사내 휴가 규정 개정');
  fireEvent.click(button('기준 저장'));
  await waitFor(() => expect(button('미리보기').disabled).toBe(false));
  expect(send).toHaveBeenCalledTimes(1);
  const [path, options] = send.mock.calls[0];
  expect(path).toBe(base + '/policy');
  expect(options).toMatchObject({ method: 'POST', credentials: 'same-origin' });
  expect(new Headers(options?.headers).get(csrf.headerName)).toBe(csrf.token);
  expect(JSON.parse(String(options?.body))).toEqual({ fiscalStartMonth: 1, grantDays: 12, carryLimitDays: 3,
    expectedVersion: 0, reason: '사내 휴가 규정 개정', commandId: expect.any(String) });
});

it.each([
  { oldMonth: 12, oldYear: 2025, nextMonth: 1, expected: 2026 },
  { oldMonth: 1, oldYear: 2026, nextMonth: 12, expected: 2025 },
  { oldMonth: null, oldYear: 2026, nextMonth: 12, expected: 2025 },
])('기본 조회 연도는 회사 시작월 $oldMonth → $nextMonth 저장 뒤 목표 연도 $expected 로 맞춘다', async ({ oldMonth, oldYear, nextMonth, expected }) => {
  fetches().mockResolvedValueOnce(json({ ...policy, version: oldMonth ? 2 : 1, fiscalStartMonth: nextMonth }));
  show({ policy: oldMonth ? { ...policy, fiscalStartMonth: oldMonth } : null, year: oldYear, yearSelected: false, today: '2026-11-21' });
  edit('회계연도 시작 월', String(nextMonth));
  if (!oldMonth) { edit('연간 부여 일수', '12'); edit('이월 한도 일수', '3'); }
  edit('기준 설정 사유', '회계 시작월 확정');
  fireEvent.click(button('기준 저장'));
  await screen.findByText('회사 연차 기준을 저장했습니다.');
  expect(field('대상 회계연도').value).toBe(String(expected));
});

it.each(['query', 'input'])('명시적으로 선택한 연도(%s)는 시작월을 저장해도 유지한다', async source => {
  fetches().mockResolvedValueOnce(json({ ...policy, version: 2, fiscalStartMonth: 1 }));
  show({ policy: { ...policy, fiscalStartMonth: 12 }, year: 2025, yearSelected: source === 'query', today: '2026-11-21' });
  if (source === 'input') {
    edit('대상 회계연도', '2024');
    edit('대상 회계연도', '2025');
  }
  edit('회계연도 시작 월', '1');
  edit('기준 설정 사유', '회계 시작월 변경');
  fireEvent.click(button('기준 저장'));
  await screen.findByText('회사 연차 기준을 저장했습니다.');
  expect(field('대상 회계연도').value).toBe('2025');
});

it('전기 잔여·이월·소멸·부여와 새 묶음 합계를 대조한 뒤 확인한 사원만 한 번 반영하고 최신 인원을 조회한다', async () => {
  const send = fetches().mockResolvedValueOnce(json(preview)).mockResolvedValueOnce(json({ id: 'result-1' }))
    .mockResolvedValueOnce(json(emptyPreview));
  show();
  edit('처리 사유', '연도별 연차 부여');
  const table = await review();
  const headers = within(table).getAllByRole('columnheader').map(header => header.textContent);
  const cells = within(within(table).getAllByRole('row')[1]).getAllByRole('cell');
  expect(cells.map(cell => cell.getAttribute('data-label'))).toEqual(headers);
  expect(cells.map(cell => cell.textContent)).toEqual([expect.stringContaining('김가람'), '4.5일', '3일', '1.5일', '12일', '15일']);
  expect(screen.getByText('이미 반영 1명')).toBeTruthy();
  expect(screen.getByText('기초 등록으로 제외 2명')).toBeTruthy();
  expect(screen.getByText('후속 연도 부여로 제외 1명')).toBeTruthy();
  expect(screen.getByText('전기 휴가 결재 대기 1명')).toBeTruthy();
  expect(button('원장에 반영').disabled).toBe(true);
  approveExpiry();
  const apply = button('원장에 반영');
  fireEvent.click(apply);
  fireEvent.click(apply);
  await screen.findByText('이미 반영 3명');
  expect(screen.getByRole('status').textContent).toContain('2명의 연차를 원장에 반영했습니다.');
  expect(screen.queryByRole('button', { name: '원장에 반영' })).toBeNull();
  expect(send.mock.calls.map(([url]) => url)).toEqual([base + '/preview', base + '/apply', base + '/preview']);
  expect(send.mock.calls.every(([, options]) => new Headers(options?.headers).get(csrf.headerName) === csrf.token)).toBe(true);
  expect(JSON.parse(String(send.mock.calls[0][1]?.body))).toEqual({ year: 2026 });
  expect(JSON.parse(String(send.mock.calls[1][1]?.body))).toEqual({
    year: 2026, token: preview.token, reason: '연도별 연차 부여', commandId: expect.any(String),
  });
});

it('조회 연도·기준 편집으로 미리보기를 폐기하고 충돌 뒤에는 같은 토큰을 다시 보낼 수 없다', async () => {
  const send = fetches().mockResolvedValueOnce(json(preview)).mockResolvedValueOnce(json(preview))
    .mockResolvedValueOnce(json(preview)).mockResolvedValueOnce(json({ message: '사원의 잔여가 변경되었습니다.' }, 409));
  show();
  edit('처리 사유', '승인 내역 확인');
  await review();
  edit('대상 회계연도', '2027');
  expect(screen.queryByRole('table')).toBeNull();
  edit('대상 회계연도', '2026');
  await review();
  edit('이월 한도 일수', '4');
  expect(screen.queryByRole('table')).toBeNull();
  expect(button('미리보기').disabled).toBe(true);
  edit('이월 한도 일수', '3');
  await review();
  approveExpiry();
  fireEvent.click(button('원장에 반영'));
  expect((await screen.findByRole('alert')).textContent).toContain('사원의 잔여가 변경되었습니다.');
  expect(screen.queryByRole('button', { name: '원장에 반영' })).toBeNull();
  expect(field('처리 사유').value).toBe('승인 내역 확인');
  expect(button('미리보기').disabled).toBe(false);
  expect(send.mock.calls.filter(([url]) => url === base + '/apply')).toHaveLength(1);
});

it('미리본 일수를 보면서 처리 사유를 입력하고 소멸 확인을 갱신할 수 있다', async () => {
  const send = fetches().mockResolvedValueOnce(json(preview));
  show();
  const table = await review();
  approveExpiry();
  expect(button('원장에 반영').disabled).toBe(true);
  edit('처리 사유', '미리본 소멸 수량을 확인함');
  expect(table.isConnected).toBe(true);
  expect((screen.getByRole('checkbox') as HTMLInputElement).checked).toBe(false);
  approveExpiry();
  expect(button('원장에 반영').disabled).toBe(false);
  expect(send).toHaveBeenCalledTimes(1);
});

it('부여·기준 사유는 서버와 같은 유니코드 문자 단위로 500자를 허용하고 초과 입력을 안내한다', async () => {
  const send = fetches().mockResolvedValueOnce(json({ ...preview, rows: [preview.rows[1]] }));
  show();
  await review();
  const longReason = '봄' + '🌱'.repeat(499);
  for (const label of ['기준 설정 사유', '처리 사유']) expect(field(label).maxLength).toBe(-1);
  edit('처리 사유', '  ' + longReason + '  ');
  expect(button('원장에 반영').disabled).toBe(false);
  edit('처리 사유', longReason + '달');
  expect(button('원장에 반영').disabled).toBe(true);
  expect(field('처리 사유').getAttribute('aria-invalid')).toBe('true');
  edit('연간 부여 일수', '12.5');
  edit('기준 설정 사유', longReason);
  expect(button('기준 저장').disabled).toBe(false);
  edit('기준 설정 사유', longReason + '달');
  expect(button('기준 저장').disabled).toBe(true);
  expect(field('기준 설정 사유').getAttribute('aria-invalid')).toBe('true');
  expect(send).toHaveBeenCalledTimes(1);
});

it('반영 응답이 유실되면 입력을 잠그고 동일한 명령으로 결과를 확인한다', async () => {
  const send = fetches().mockResolvedValueOnce(json(preview)).mockRejectedValueOnce(Error('연결 끊김'))
    .mockResolvedValueOnce(json({ id: 'original-receipt' })).mockResolvedValueOnce(json(emptyPreview));
  show();
  edit('처리 사유', '원장 이월');
  await review();
  approveExpiry();
  fireEvent.click(button('원장에 반영'));
  const retry = await screen.findByRole('button', { name: '같은 명령으로 반영 결과 확인' });
  expect(field('처리 사유').matches(':disabled')).toBe(true);
  expect(button('미리보기').disabled).toBe(true);
  fireEvent.click(retry);
  await screen.findByText('이미 반영 3명');
  expect(send.mock.calls[1][1]?.body).toBe(send.mock.calls[2][1]?.body);
});

it('미래 시작분은 기간과 수량만 보여주고 공개 데모에서는 현재 대상도 조회만 허용한다', async () => {
  const send = fetches().mockResolvedValueOnce(json({ ...preview, canPost: false }))
    .mockResolvedValueOnce(json(preview));
  show({ today: '2025-12-31' });
  await review();
  expect(screen.getByText(/2026-01-01부터 원장에 반영/)).toBeTruthy();
  expect(screen.queryByRole('button', { name: '원장에 반영' })).toBeNull();
  cleanup();
  show({ publicDemo: true });
  expect(button('기준 저장').disabled).toBe(true);
  await review();
  expect(screen.getByText(/공개 데모/)).toBeTruthy();
  expect(screen.queryByRole('button', { name: '원장에 반영' })).toBeNull();
  expect(send.mock.calls.every(([url]) => url === base + '/preview')).toBe(true);
});

it('처리한 연도를 다시 조회하면 현재 회사 기준과 별도로 그 연도에 고정한 기준을 보여준다', async () => {
  fetches().mockResolvedValueOnce(json(emptyPreview));
  show({ policy: { ...policy, version: 2, grantDays: 14 } });
  fireEvent.click(button('미리보기'));
  await screen.findByText('이미 반영 3명');
  expect(field('연간 부여 일수').value).toBe('14');
  const result = screen.getByRole('region', { name: '미리보기 결과' });
  expect(result.textContent).toContain('부여 12일');
  expect(result.textContent).toContain('이월 한도 3일');
  expect(within(result).getByText('전기 휴가 결재 대기 1명')).toBeTruthy();
  expect(within(result).getByRole('link', { name: '휴가 신청 현황' }).getAttribute('href')).toBe('/admin/requests?scope=LEAVE');
  expect(screen.queryByRole('button', { name: '원장에 반영' })).toBeNull();
});

it.each([false, true])('개정 충돌은 입력을 보존하고 명시 연도 선택(%s)에 맞는 최신 기준 조회를 안내한다', async yearSelected => {
  const send = fetches().mockResolvedValueOnce(json({ message: '처리 이력이 있어 시작 월을 바꿀 수 없습니다.' }, 409));
  show({ yearSelected });
  edit('회계연도 시작 월', '4');
  edit('기준 설정 사유', '기간 변경 검토');
  fireEvent.click(button('기준 저장'));
  expect((await screen.findByRole('alert')).textContent).toContain('시작 월을 바꿀 수 없습니다.');
  expect(field('회계연도 시작 월').value).toBe('4');
  expect(JSON.parse(String(send.mock.calls[0][1]?.body)).expectedVersion).toBe(1);
  expect(screen.getByRole('link', { name: '최신 기준 조회' }).getAttribute('href')).toBe(yearSelected ? base + '?year=2026' : base);
});

it('반영은 성공했지만 최신 미리보기를 불러오지 못한 경우 반영 요청 재시도를 제시하지 않는다', async () => {
  const send = fetches().mockResolvedValueOnce(json({ ...preview, rows: [preview.rows[1]] }))
    .mockResolvedValueOnce(json({ id: 'receipt' })).mockRejectedValueOnce(Error('새 조회 실패'));
  show();
  edit('처리 사유', '연차 부여');
  await review();
  expect(screen.queryByRole('checkbox')).toBeNull();
  fireEvent.click(button('원장에 반영'));
  expect((await screen.findByRole('alert')).textContent).toContain('미리보기');
  expect(screen.getByRole('status').textContent).toContain('1명의 연차를 원장에 반영했습니다.');
  expect(screen.queryByRole('button', { name: /반영 결과 확인|원장에 반영/ })).toBeNull();
  expect(button('미리보기').disabled).toBe(false);
  expect(send.mock.calls.filter(([url]) => url === base + '/apply')).toHaveLength(1);
});

it('다시 미리보는 중 권한이 회수되면 이전 사원 정보와 변경 명령을 화면에서 지운다', async () => {
  fetches().mockResolvedValueOnce(json(preview)).mockResolvedValueOnce(json({}, 403));
  show();
  await review();
  fireEvent.click(button('미리보기'));
  expect((await screen.findByRole('alert')).textContent).toContain('권한');
  expect(screen.queryByText('김가람')).toBeNull();
  expect(screen.queryByRole('form', { name: '회사 연차 기준' })).toBeNull();
  expect(screen.getByRole('link', { name: '다시 로그인' }).getAttribute('href')).toBe('/login');
});

it('직원의 오늘 사용 가능 잔여와 연도별 원장에 종류, 부호, 귀속연도, 효력 기준일, 휴가 사용일, 사유를 표시한다', () => {
  const movements = [
    ['OPENING', '기초 등록', 4.5], ['ADJUSTMENT', '잔여 조정', -0.5], ['CARRY_OUT', '다음 연도로 이월', -3],
    ['EXPIRE', '연차 소멸', -1.5], ['GRANT', '연차 부여', 12], ['CARRY_IN', '전년도에서 이월', 3],
    ['USE', '휴가 사용', -0.5], ['RESTORE', '취소 복원', 0.5],
  ] as const;
  const ledger: PersonalLedger = { year: 2026, today: '2026-01-05', availableDays: 15,
    entries: movements.map(([entryKind,, signedQuantityDays], i) => ({ id: 'row-' + i, bucketId: 'balance-' + i,
      employeeNo: 'E101', entitlementYear: i === 0 ? null : i < 4 ? 2025 : 2026, entryKind, signedQuantityDays,
      effectiveOn: '2026-01-05', leaveDate: i >= 6 ? '2026-02-02' : null, sourceReference: null, reason: '합성 사유 ' + i })) };
  render(<WorkflowPages page="request/personal-ledger" data={{ ledger }} csrf={csrf} reload={vi.fn()} />);
  expect(screen.getByRole('heading', { name: '내 연차 원장' })).toBeTruthy();
  expect(screen.getByRole('region', { name: '오늘 사용 가능 연차' }).textContent).toContain('15일');
  expect(field('조회 연도').value).toBe('2026');
  const form = field('조회 연도').closest('form')!;
  expect(form.getAttribute('action')).toBe('/me/leave-ledger');
  expect(form.method).toBe('get');
  expect(new FormData(form).has('employeeId')).toBe(false);
  const table = screen.getByRole('table', { name: '연차 변동 이력' });
  const headers = within(table).getAllByRole('columnheader').map(header => header.textContent);
  const rows = within(table).getAllByRole('row').slice(1);
  expect(rows).toHaveLength(movements.length);
  rows.forEach((row, i) => {
    const [,name, amount] = movements[i];
    expect(within(row).getByText(name)).toBeTruthy();
    expect(within(row).getByText((amount > 0 ? '+' : '') + amount + '일')).toBeTruthy();
    expect(within(row).getAllByRole('cell').map(cell => cell.getAttribute('data-label'))).toEqual(headers);
  });
  expect(within(rows[0]).getByText('귀속 미확인')).toBeTruthy();
  expect(within(rows[3]).getByText('2025년')).toBeTruthy();
  expect(within(rows[7]).getByText('2026-02-02')).toBeTruthy();
  expect(within(rows[7]).getByText('합성 사유 7')).toBeTruthy();
});
