// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from '../App';
import { WorkflowPages } from './WorkflowPages';

const base = '/admin/leave-balances/employment';
const csrf = { parameterName: '_csrf', headerName: 'X-CSRF-TOKEN', token: 'synthetic' };
const employee = { id: 'employee-1', employeeNo: 'A101', employeeName: '가상 사원', joinedOn: '2026-03-10', leftOn: '2026-05-12' };
const period = { monthNumber: 1, periodStart: '2026-03-10', periodEnd: '2026-04-09', accrualOn: '2026-04-10',
  scheduledDays: 20, attendedDays: 17, leaveDays: 2, unresolvedDays: 1, decision: null, reason: null, grantedDays: null, token: 'monthly-snapshot' };
const reviewed = { ...period, monthNumber: 2, periodStart: '2026-04-10', periodEnd: '2026-05-09', accrualOn: '2026-05-10',
  decision: 'NOT_FULL', reason: '결근 근거 확인', grantedDays: 0, token: null };
const imported = { bucketId: 'original-credit', sourceReference: '4월 확정 이관표', creditedDays: 1, linkedDays: 0, accrualDates: [period.accrualOn] };
const allocated = { bucketId: 'used-credit', sourceReference: '다른 달 매칭 원장', creditedDays: 1, linkedDays: 1,
  accrualDates: [period.accrualOn, reviewed.accrualOn] };
const monthly = { employeeId: employee.id, employeeNo: employee.employeeNo, employeeName: employee.employeeName,
  joinedOn: employee.joinedOn, leftOn: employee.leftOn, today: '2026-05-12', usableTo: '2027-03-09', settled: false, periods: [period, reviewed] };
const bucket = { bucketId: 'bucket-1', usableFrom: '2026-04-10', usableTo: '2027-03-09', entitlementYear: 2026, sourceReference: '입사자 최초 연도 월별 연차',
  openingDays: 1, grantDays: 1, carryDays: 0, expireDays: 0, usedDays: 0.5, restoredDays: 0, adjustmentDays: 0, balanceDays: 1.5, settlementDays: 1.5, status: 'READY' };
const exit = { ...employee, employeeId: employee.id, rows: [bucket], balanceDays: 1.5, settlementDays: 1.5, missingMonths: [],
  priorHistoryReviewRequired: false, blockers: [], token: 'exit-snapshot', canPost: true, settlementId: null, reason: null };
const model = { q: '가상', page: 0, hasNext: true, employees: [employee], today: '2026-05-12', publicDemo: false, monthly, exit };
const show = (changes: Record<string, unknown> = {}, reload = vi.fn()) =>
  render(<WorkflowPages page="request/employment-leave" data={{ ...model, ...changes }} csrf={csrf} reload={reload} />);
const b = (name: string) => screen.getByRole('button', { name }) as HTMLButtonElement;
const field = (name: string, value: string) => fireEvent.change(screen.getByLabelText(name), { target: { value } });
const sendRequest = () => { const send = vi.fn<typeof fetch>(); vi.stubGlobal('fetch', send); return send; };
const response = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });
const click = (name: string) => fireEvent.click(b(name));
const chooseMonth = (decision = 'FULL', reason = '개근 확인서 대조') => {
  click('1개월차 검토'); field('개근 판단', decision); field('월별 판단 사유', reason); click('월별 발생 미리보기');
};
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it('사원을 검색하고 현재 검색어와 페이지를 보존한 주소로 각 사원을 선택한다', () => {
  show({ monthly: null, exit: null, q: '개발 &팀', page: 2 });
  const area = screen.getByRole('search', { name: '정산 사원 검색' }) as HTMLFormElement;
  expect(area.getAttribute('action')).toBe(base);
  expect(area.method).toBe('get');
  expect(new FormData(area).get('q')).toBe('개발 &팀');
  expect(new FormData(area).has('employeeId')).toBe(false);
  const target = new URL(screen.getByRole('link', { name: 'A101 가상 사원 선택' }).getAttribute('href')!, location.origin);
  expect(target.pathname).toBe(base);
  expect(Object.fromEntries(target.searchParams)).toEqual({ q: '개발 &팀', page: '2', employeeId: employee.id });
  const next = new URL(screen.getByRole('link', { name: '다음' }).getAttribute('href')!, location.origin);
  expect(next.searchParams.get('q')).toBe('개발 &팀');
  expect(next.searchParams.get('page')).toBe('3');
  expect(screen.queryByRole('table', { name: '월별 발생 근거' })).toBeNull();
});

it('일정과 출근·휴가·미확정 자료, 실제 산정 기간, 의제출근 판단을 함께 읽고 미개근 0일을 구분한다', () => {
  show();
  const table = screen.getByRole('table', { name: '월별 발생 근거' });
  const first = within(table).getAllByRole('row')[1];
  for (const value of ['2026-03-10', '2026-04-09', '2026-04-10', '소정근로 20일', '확인 출근 17일', '승인 휴가 2일', '미확정 1일'])
    expect(first.textContent).toContain(value);
  expect(within(table).getByText('미개근')).toBeTruthy();
  expect(within(table).getByText('결근 근거 확인')).toBeTruthy();
  expect(within(table).getByText('0일')).toBeTruthy();
  expect(screen.queryByRole('button', { name: '2개월차 검토' })).toBeNull();
  expect(screen.getByText(/의제출근/)).toBeTruthy();
  expect(screen.getByText(/1월 시작/)).toBeTruthy();
  expect(screen.getByRole('link', { name: '회사 연차 기준 설정' }).getAttribute('href')).toBe('/admin/leave-balances/annual-cycle');
  expect(within(first).getAllByRole('cell').every(cell => Boolean(cell.getAttribute('data-label')))).toBe(true);
});

it.each([['FULL', '1일'], ['NOT_FULL', '0일']])('직접 판단한 %s 일수와 사유를 먼저 미리 보고 한 번만 반영한다', async (decision, days) => {
  const send = sendRequest().mockResolvedValue(response({ id: 'receipt' })), reload = vi.fn();
  show({}, reload); chooseMonth(decision, '  출근 기록 대조  ');
  const preview = screen.getByRole('region', { name: '월별 판단 미리보기' });
  expect(preview.textContent).toContain(days);
  expect(preview.textContent).toContain('출근 기록 대조');
  expect(send).not.toHaveBeenCalled();
  click('월별 판단 확정'); await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
  const [url, init] = send.mock.calls[0];
  expect(url).toBe(base + '/monthly');
  expect(new Headers(init?.headers).get(csrf.headerName)).toBe(csrf.token);
  expect(JSON.parse(String(init?.body))).toEqual({ employeeId: employee.id, accrualOn: period.accrualOn, decision,
    reason: '출근 기록 대조', token: period.token, commandId: expect.any(String) });
  expect(b('월별 판단 확정').disabled).toBe(true);
});

it('출근 판단과 사유를 바꾸면 미리보기를 다시 확인해야 하며 사유의 공백과 길이를 검사한다', () => {
  show(); click('1개월차 검토'); expect(b('월별 발생 미리보기').disabled).toBe(true);
  field('개근 판단', 'FULL'); field('월별 판단 사유', '   '); expect(b('월별 발생 미리보기').disabled).toBe(true);
  field('월별 판단 사유', '가'.repeat(501)); expect(b('월별 발생 미리보기').disabled).toBe(true);
  field('월별 판단 사유', '대조 완료'); click('월별 발생 미리보기'); expect(b('월별 판단 확정').disabled).toBe(false);
  field('개근 판단', 'NOT_FULL'); expect(screen.queryByRole('button', { name: '월별 판단 확정' })).toBeNull();
});

it('이미 부여한 개근은 기초의 잔여가 0이어도 원천을 선택해 최초 1일과 이번 추가 0일을 대조한다', async () => {
  const send = sendRequest().mockResolvedValue(response({ id: 'already-included' }));
  show({ monthly: { ...monthly, openingSources: [{ ...imported, balanceDays: 0 }, allocated] } });
  click('1개월차 검토'); field('개근 판단', 'INCLUDED'); field('월별 판단 사유', '4월 이관 명세 대조');
  const picker = screen.getByLabelText('이미 부여한 원천') as HTMLSelectElement;
  expect(picker.value).toBe('');
  for (const info of [imported.sourceReference, '연결 기준 1일', '이미 연결 0일']) expect(picker.textContent).toContain(info);
  expect(screen.getByText(/연결 기준 일수에는 순감액 조정을 반영/)).toBeTruthy();
  expect(picker.querySelector<HTMLOptionElement>('option[value="used-credit"]')?.disabled).toBe(true);
  expect(b('월별 발생 미리보기').disabled).toBe(true);
  field('이미 부여한 원천', imported.bucketId); click('월별 발생 미리보기');
  const preview = screen.getByRole('region', { name: '월별 판단 미리보기' });
  for (const value of ['개근 1일', '이번 추가 반영 0일', imported.sourceReference, '4월 이관 명세 대조'])
    expect(preview.textContent).toContain(value);
  click('월별 판단 확정'); await waitFor(() => expect(send).toHaveBeenCalledTimes(1));
  expect(JSON.parse(String(send.mock.calls[0][1]?.body))).toMatchObject({ decision: 'INCLUDED', includedBucketId: imported.bucketId });
});

it('이관 포함을 선택했다가 일반 개근으로 바꾸면 원천을 보내지 않고 1일 부여를 다시 확인한다', async () => {
  const send = sendRequest().mockResolvedValue(response({ id: 'grant-once' }));
  show({ monthly: { ...monthly, openingSources: [imported] } });
  click('1개월차 검토'); field('개근 판단', 'INCLUDED'); field('이미 부여한 원천', imported.bucketId);
  field('월별 판단 사유', '신규 발생 근거'); click('월별 발생 미리보기');
  field('개근 판단', 'FULL'); expect(screen.queryByLabelText('이미 부여한 원천')).toBeNull();
  expect(screen.queryByRole('region', { name: '월별 판단 미리보기' })).toBeNull();
  click('월별 발생 미리보기'); click('월별 판단 확정'); await waitFor(() => expect(send).toHaveBeenCalledTimes(1));
  const body = JSON.parse(String(send.mock.calls[0][1]?.body));
  expect(body.decision).toBe('FULL'); expect(body.includedBucketId).toBeUndefined();
});

it('해당 발생일과 사용기간·귀속이 일치하는 원천만 해당 월에 연결할 수 있다', () => {
  const another = { ...imported, bucketId: 'other-period', sourceReference: '전년도 기준 기초', accrualDates: [reviewed.accrualOn] };
  show({ monthly: { ...monthly, openingSources: [imported, another] } });
  click('1개월차 검토'); field('개근 판단', 'INCLUDED');
  const options = screen.getByLabelText('이미 부여한 원천');
  expect(within(options).queryByRole('option', { name: /전년도 기준 기초/ })).toBeNull();
  expect(within(options).getByRole('option', { name: /4월 확정 이관표/ })).toBeTruthy();
});

it('전부 연결된 원천만 있으면 새 매칭을 막고 기확정 월의 개근·원천·실제 추가 0일은 표시한다', () => {
  show({ monthly: { ...monthly, openingSources: [allocated], periods: [period,
    { ...reviewed, decision: 'INCLUDED', includedBucketId: allocated.bucketId, reason: '회사 이관표에 포함', grantedDays: 0 }] } });
  click('1개월차 검토');
  expect((screen.getByRole('option', { name: '개근 · 기존 원장에 포함' }) as HTMLOptionElement).disabled).toBe(true);
  const row = within(screen.getByRole('table', { name: '월별 발생 근거' })).getAllByRole('row')[2];
  for (const s of ['개근', '추가 0일', allocated.sourceReference, '회사 이관표에 포함']) expect(row.textContent).toContain(s);
  expect(within(row).getByRole('link', { name: allocated.sourceReference }).getAttribute('href')).toBe('/admin/leave-balances/buckets/used-credit');
});

it('퇴사일에 원장 잔여·정산 대상과 기초·발생·사용·복원 등 묶음별 산정 근거를 대조하고 사유와 함께 확정한다', async () => {
  const send = sendRequest().mockResolvedValue(response({ id: 'exit-receipt' })), reload = vi.fn();
  show({}, reload); click('퇴사 정산 미리보기');
  const summary = screen.getByRole('region', { name: '퇴사 정산 미리보기' });
  expect(summary.textContent).toContain('1.5일');
  const row = within(summary).getByRole('table', { name: '묶음별 정산 근거' });
  expect(row.textContent).toContain(bucket.sourceReference);
  expect(row.textContent).toContain('사용 0.5일');
  expect(b('퇴사 정산 확정').disabled).toBe(true);
  field('퇴사 정산 사유', '퇴사일까지 대조'); click('퇴사 정산 확정');
  await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
  const [url, init] = send.mock.calls[0];
  expect(url).toBe(base + '/settle');
  expect(JSON.parse(String(init?.body))).toEqual({ employeeId: employee.id, token: exit.token, commandId: expect.any(String),
    priorHistoryConfirmed: false, reason: '퇴사일까지 대조' });
});

it('미검토 발생일과 서버가 돌려준 차단 사유를 알려 주고 해당 사원만 정산을 보류한다', () => {
  show({ exit: { ...exit, missingMonths: ['2026-04-10'], blockers: ['결재 중인 휴가가 있습니다.'], canPost: false, token: null } });
  click('퇴사 정산 미리보기');
  const area = screen.getByRole('region', { name: '퇴사 정산 미리보기' });
  expect(area.textContent).toContain('2026-04-10');
  expect(area.textContent).toContain('결재 중인 휴가가 있습니다.');
  expect(screen.queryByRole('button', { name: '퇴사 정산 확정' })).toBeNull();
});

it.each([{ dates: [] }, { dates: ['2025-05-10'] }])('근속 1년 이상이면 과거 누락 여부와 무관하게 입사일 기준 연간분 대조를 서버에 전달한다 ($dates)', async ({ dates }) => {
  const send = sendRequest().mockResolvedValue(response({ id: 'verified' }));
  show({ exit: { ...exit, priorHistoryReviewRequired: true, missingMonths: dates } });
  click('퇴사 정산 미리보기'); field('퇴사 정산 사유', '이관 자료 대조');
  expect(b('퇴사 정산 확정').disabled).toBe(true);
  fireEvent.click(screen.getByRole('checkbox', { name: '입사일 기준 발생·사용과 이관 원장을 대조했습니다' }));
  click('퇴사 정산 확정'); await waitFor(() => expect(send).toHaveBeenCalledTimes(1));
  expect(JSON.parse(String(send.mock.calls[0][1]?.body)).priorHistoryConfirmed).toBe(true);
});

it('응답 유실 후 같은 명령만 다시 보내며 연속 클릭은 단 한 번만 확정한다', async () => {
  const send = sendRequest().mockRejectedValueOnce(new TypeError()).mockResolvedValueOnce(response({ id: 'original' }));
  const reload = vi.fn(); show({}, reload); chooseMonth(); click('월별 판단 확정'); click('월별 판단 확정');
  const retry = await screen.findByRole('button', { name: '같은 명령으로 결과 확인' });
  expect(send).toHaveBeenCalledTimes(1);
  expect((screen.getByLabelText('개근 판단') as HTMLSelectElement).disabled).toBe(true);
  fireEvent.click(retry); await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
  expect(send.mock.calls[0][1]?.body).toEqual(send.mock.calls[1][1]?.body);
});

it('오래된 확인 자료는 확정할 수 없고 해당 사원을 같은 조건으로 다시 조회한다', async () => {
  sendRequest().mockResolvedValue(response({ message: '원장 잔여가 변경되었습니다.' }, 409));
  show(); chooseMonth(); click('월별 판단 확정');
  expect((await screen.findByRole('alert')).textContent).toContain('원장 잔여가 변경되었습니다.');
  expect(b('월별 판단 확정').disabled).toBe(true);
  const href = new URL(screen.getByRole('link', { name: '최신 근거 다시 조회' }).getAttribute('href')!, location.origin);
  expect(href.searchParams.get('q')).toBe(model.q);
  expect(href.searchParams.get('employeeId')).toBe(employee.id);
});

it('권한을 잃으면 사원 정보와 미리본 일수를 화면에서 지운다', async () => {
  sendRequest().mockResolvedValue(response({ message: '권한 없음' }, 403));
  show(); chooseMonth(); click('월별 판단 확정');
  expect(await screen.findByRole('link', { name: '다시 로그인' })).toBeTruthy();
  expect(screen.queryByRole('table', { name: '월별 발생 근거' })).toBeNull();
  expect(screen.queryByText('가상 사원')).toBeNull();
});

it('실제 화면 전환에서 월별 반영 직후 같은 사원·검색조건을 한 번만 재조회해 확정 행을 보여준다', async () => {
  const selected = base + '?q=A101&page=1&employeeId=' + employee.id;
  history.replaceState(null, '', selected);
  const initial = { page: 'request/employment-leave', csrf, data: { ...model, q: 'A101', page: 1,
    workspacePrincipal: { roles: ['TENANT_ADMIN'] }, workspaceEmployee: { employeeName: '가상 담당자', companyName: '가상 회사' } } };
  const done = { ...period, grantedDays: 1, reason: '개근 확정 원장', decision: 'FULL', token: null };
  const send = sendRequest().mockResolvedValueOnce(response({ id: 'original-receipt' }))
    .mockResolvedValueOnce(response({ ...initial, data: { ...initial.data, monthly: { ...monthly, periods: [done, reviewed] } } }));
  render(<App initial={initial} />);
  chooseMonth(); click('월별 판단 확정');
  expect(await screen.findByText('개근 확정 원장')).toBeTruthy();
  expect(send).toHaveBeenCalledTimes(2);
  expect(String(send.mock.calls[1][0])).toBe(location.origin + selected);
  expect(screen.queryByRole('button', { name: '1개월차 검토' })).toBeNull();
});

it('공개 모드에서 사원별 월별·퇴사 근거를 읽고 쓰기 제어를 제공하지 않는다', () => {
  show({ publicDemo: true });
  expect(screen.getByRole('table', { name: '월별 발생 근거' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: '1개월차 검토' })).toBeNull();
  click('퇴사 정산 미리보기');
  expect(screen.queryByRole('button', { name: '퇴사 정산 확정' })).toBeNull();
});

it('인사기간과 기존 확정 행이 어긋난 미결 월은 다시 기록할 수 없고 확인할 항목을 알려준다', () => {
  show({ monthly: { ...monthly, periods: [reviewed, { ...period, monthNumber: reviewed.monthNumber, token: null }] } });
  expect(screen.getByText('입사일·마지막 재직일과 기존 월별 판정을 확인해 주세요.')).toBeTruthy();
  expect(screen.queryByRole('button', { name: '2개월차 검토' })).toBeNull();
  expect(within(screen.getByRole('table', { name: '월별 발생 근거' })).getAllByRole('row')).toHaveLength(3);
});

it('기확정 0일 정산도 담당자와 직원에게 같은 마지막 재직일·일수·사유로 조회된다', () => {
  const receipt = { leftOn: employee.leftOn, settlementDays: 0, reason: '미사용분 없음' };
  const manager = show({ monthly: { ...monthly, settled: true }, exit: { ...exit, ...receipt, balanceDays: 0, rows: [], canPost: false, settlementId: 'done' } });
  const totals = screen.getByRole('region', { name: '퇴사 정산 결과' });
  expect(totals.textContent).toContain('0일'); expect(totals.textContent).toContain(receipt.reason);
  expect(screen.queryByRole('button', { name: '퇴사 정산 확정' })).toBeNull();
  expect(screen.queryByRole('button', { name: '1개월차 검토' })).toBeNull();
  manager.unmount();
  const movements = [['MONTHLY_ACCRUAL', 0, '미개근 확인'], ['EXIT_SETTLEMENT', -0.5, '퇴사일 잔여 대조']] as const;
  const ledger = { year: 2026, today: '2026-05-12', availableDays: 0, basis: 'FISCAL', periodStart: '2026-01-01', periodEnd: '2026-12-31', entries: movements.map(([entryKind, signedQuantityDays, reason], index) => ({
    id: 'row-' + index, bucketId: 'b1', employeeNo: employee.employeeNo, entitlementYear: 2026,
    entryKind, signedQuantityDays, effectiveOn: '2026-05-12', leaveDate: null, sourceReference: '사원별 산정 근거', reason,
  })) };
  render(<WorkflowPages page="request/personal-ledger" data={{ ledger: { ...ledger, exitReceipt: receipt } }} csrf={csrf} reload={vi.fn()} />);
  const result = screen.getByRole('region', { name: '퇴사 정산 결과' });
  for (const value of [employee.leftOn, '0일', receipt.reason]) expect(result.textContent).toContain(value);
  const entries = screen.getByRole('table', { name: '연차 변동 이력' });
  for (const value of ['월별 연차 발생', '미개근 확인', '0일', '퇴사 잔여 정산', '-0.5일']) expect(entries.textContent).toContain(value);
});

it('직원 원장에서 같은 0일도 판정 필드를 사용해 미개근과 기존 부여에 포함된 개근을 구분한다', () => {
  const ledger = { year: 2026, today: '2026-07-15', availableDays: 0, basis: 'FISCAL', periodStart: '2026-01-01', periodEnd: '2026-12-31', entries: ['INCLUDED', 'NOT_FULL'].map((monthlyDecision, i) => ({
    id: 'row-' + i, bucketId: 'monthly', employeeNo: 'A101', entitlementYear: 2026, entryKind: 'MONTHLY_ACCRUAL',
    signedQuantityDays: 0, effectiveOn: '2026-06-10', leaveDate: null, sourceReference: '개근 여부 증빙 확인',
    reason: '인사 담당자 확인', monthlyDecision,
  })) };
  render(<WorkflowPages page="request/personal-ledger" data={{ ledger }} csrf={csrf} reload={vi.fn()} />);
  const entries = within(screen.getByRole('table', { name: '연차 변동 이력' })).getAllByRole('row').slice(1);
  expect(within(entries[0]).getByText('개근 · 기존 원장에 포함')).toBeTruthy();
  expect(within(entries[1]).getByText('미개근')).toBeTruthy();
  expect(entries.every(row => within(row).getByText('0일'))).toBe(true);
});
