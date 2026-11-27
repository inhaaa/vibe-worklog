// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { AnnualMonthlyBulk } from './AnnualMonthlyBulk';
import type { MonthlyBulkPreview } from './annualLeaveTypes';

const csrf = { headerName: 'X-TEST-CSRF', parameterName: '_csrf', token: 'synthetic-token' };
const base = '/admin/leave-balances/annual-cycle';
const period = (monthNumber: number, unresolvedDays = 0, absenceDays = 0) => ({ monthNumber, periodStart: '2026-08-01', periodEnd: '2026-08-31',
  accrualOn: '2026-09-01', scheduledDays: 21, attendedDays: 21, leaveDays: 0, unresolvedDays, absenceDays, decision: null, reason: null,
  grantedDays: 0, token: 't' + monthNumber, includedBucketId: null });
const preview: MonthlyBulkPreview = { today: '2026-11-22', token: 'bulk-token', canPost: true, fullCount: 1, notFullCount: 1, reviewCount: 1, rows: [
  { employeeId: 'a', employeeNo: 'W01', employeeName: '김가람', joinedOn: '2026-08-01', period: period(1), decision: 'FULL', finding: '자동 판정: 소정 21일, 결근 0일' },
  { employeeId: 'b', employeeNo: 'W02', employeeName: '이소라', joinedOn: '2026-08-01', period: period(1, 0, 1), decision: 'NOT_FULL', finding: '자동 판정: 소정 21일, 결근 1일' },
  { employeeId: 'c', employeeNo: 'W03', employeeName: '박하늘', joinedOn: '2026-08-01', period: period(1, 3), decision: 'NEEDS_REVIEW', finding: '판정 불가 3일' },
] };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it('월 연차 판정을 미리 보고 개근·미개근 행만 사유와 함께 반영한 뒤 다시 조회한다', async () => {
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(json(preview)).mockResolvedValueOnce(json({ id: 'r' }))
    .mockResolvedValueOnce(json({ ...preview, rows: [preview.rows[2]], fullCount: 0, notFullCount: 0, canPost: false }));
  vi.stubGlobal('fetch', send);
  render(<AnnualMonthlyBulk csrf={csrf} publicDemo={false} />);
  fireEvent.click(screen.getByRole('button', { name: '월 연차 미리보기' }));
  const table = await screen.findByRole('table', { name: '월 연차 자동 판정' });
  expect(within(table).getAllByRole('row')).toHaveLength(4);
  expect(within(table).getByText('판정 불가 3일')).toBeTruthy();
  const apply = screen.getByRole('button', { name: '개근·미개근 2건 반영' }) as HTMLButtonElement;
  expect(apply.disabled).toBe(true);
  fireEvent.change(screen.getByLabelText('월 연차 반영 사유'), { target: { value: '출근부 자동 판정 확인' } });
  fireEvent.click(apply);
  await screen.findByText('월 연차 2건을 원장에 반영했습니다.');
  expect(send.mock.calls.map(([url]) => url)).toEqual([base + '/monthly-preview', base + '/monthly-apply', base + '/monthly-preview']);
  expect(JSON.parse(String(send.mock.calls[1][1]?.body))).toEqual({ token: 'bulk-token', reason: '출근부 자동 판정 확인', commandId: expect.any(String) });
  expect(screen.queryByRole('button', { name: /건 반영/ })).toBeNull();
});

it('공개 데모는 미리보기만 한다', async () => {
  vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValueOnce(json(preview)));
  render(<AnnualMonthlyBulk csrf={csrf} publicDemo />);
  fireEvent.click(screen.getByRole('button', { name: '월 연차 미리보기' }));
  await screen.findByRole('table', { name: '월 연차 자동 판정' });
  expect(screen.queryByRole('button', { name: /건 반영/ })).toBeNull();
});

it('미리본 뒤 근태가 바뀌어 거절되면 서버 문구를 보여준다', async () => {
  vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValueOnce(json(preview))
    .mockResolvedValueOnce(json({ message: '근태 결과나 사원 정보가 바뀌었습니다. 월 연차 미리보기를 다시 확인해 주세요.' }, 409)));
  render(<AnnualMonthlyBulk csrf={csrf} publicDemo={false} />);
  fireEvent.click(screen.getByRole('button', { name: '월 연차 미리보기' }));
  await screen.findByRole('table', { name: '월 연차 자동 판정' });
  fireEvent.change(screen.getByLabelText('월 연차 반영 사유'), { target: { value: '확인' } });
  fireEvent.click(screen.getByRole('button', { name: '개근·미개근 2건 반영' }));
  expect((await screen.findByRole('alert')).textContent).toContain('미리보기를 다시 확인해 주세요');
});
