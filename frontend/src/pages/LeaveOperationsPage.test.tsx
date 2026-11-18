// @vitest-environment jsdom
import { cleanup, fireEvent, render, within } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { WorkflowPages } from './WorkflowPages';

afterEach(cleanup);

const row = {
  id: 'original', employeeNo: 'E001', employeeName: '합성 직원', organizationName: '운영팀',
  leaveTypeName: '기존 종일 연차', requestKind: 'LEAVE_DAY', from: '2026-11-18', to: '2026-11-19',
  quantityDays: 2, status: 'APPROVED', effectStatus: 'CANCELED', approverName: '현재 결재자', decidedByName: '실제 처리자',
  submittedAt: '2026-11-16T01:00:00Z', decidedAt: '2026-11-17T02:00:00Z', originalRequestId: null,
};
const model = {
  status: 'APPROVED', leaveTypeCode: 'ANNUAL_LEAVE', requestKind: '', from: '2026-11-17', to: '2026-11-18',
  organizationId: '11111111-1111-4111-8111-111111111111', q: 'E001 & 운영', page: 1, hasNext: true, asOf: '2026-11-18',
  leaveTypes: [{ code: 'ANNUAL_LEAVE', name: '종일 연차' }],
  organizations: [{ id: '11111111-1111-4111-8111-111111111111', code: 'OP', name: '운영팀' }],
  counts: { PENDING: 3, APPROVED: 61, REJECTED: 2, ALL: 67 },
  rows: [row, { ...row, id: 'cancel', requestKind: 'CANCEL_LEAVE', effectStatus: 'EFFECTIVE', originalRequestId: 'original' }],
  error: null,
};
const csrf = { token: 'csrf-value', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const list = (payload: Record<string, unknown>) =>
  render(<WorkflowPages page="request/list" data={payload} csrf={csrf} reload={() => {}} />);
const url = (element: HTMLElement) => new URL(element.getAttribute('href')!, window.location.origin);

it('휴가 목록의 상태와 페이지 이동에서 적용한 검색 조건을 유지한다', () => {
  const view = list({ leaveOperations: model });
  expect(view.getByRole('heading', { name: '휴가 신청 현황' })).toBeTruthy();
  expect((view.getByLabelText('휴가 종류') as HTMLSelectElement).value).toBe('ANNUAL_LEAVE');
  expect((view.getByLabelText('시작일') as HTMLInputElement).value).toBe('2026-11-17');
  expect((view.getByLabelText('종료일') as HTMLInputElement).value).toBe('2026-11-18');
  expect(view.getByRole('link', { name: '승인 61' }).getAttribute('aria-current')).toBe('page');
  const rejected = url(view.getByRole('link', { name: '반려 2' }));
  expect(Object.fromEntries(rejected.searchParams)).toMatchObject({
    scope: 'LEAVE', status: 'REJECTED', leaveTypeCode: 'ANNUAL_LEAVE', from: '2026-11-17', to: '2026-11-18',
    organizationId: model.organizationId, q: model.q, page: '0',
  });
  const next = url(view.getByRole('link', { name: '다음' }));
  expect(next.searchParams.get('page')).toBe('2');
  expect(next.searchParams.get('status')).toBe('APPROVED');
  const form = view.container.querySelector('form')!;
  expect(new FormData(form).get('scope')).toBe('LEAVE');
  expect(new FormData(form).get('page')).toBeNull();
});

it('원신청의 승인 이력과 취소 효력을 별도로 표시하고 상세에 복귀 조건을 전달한다', () => {
  const view = list({ leaveOperations: model });
  const rows = view.container.querySelectorAll<HTMLDetailsElement>('ul.leave-operations-rows details');
  expect(rows).toHaveLength(2);
  const original = within(rows[0]);
  expect(original.getByText('승인')).toBeTruthy();
  expect(original.getByText('취소됨')).toBeTruthy();
  expect(original.getByText('기존 종일 연차')).toBeTruthy();
  fireEvent.click(rows[0].querySelector('summary')!);
  expect(original.getByText('실제 처리자')).toBeTruthy();
  expect(original.getByText('2026-11-17 11:00:00')).toBeTruthy();
  const detail = url(original.getByText('신청 상세'));
  expect(detail.pathname).toBe('/requests/original');
  const back = new URL(detail.searchParams.get('returnTo')!, window.location.origin);
  expect(back.pathname).toBe('/admin/requests');
  expect(Object.fromEntries(back.searchParams)).toMatchObject({
    scope: 'LEAVE', status: 'APPROVED', leaveTypeCode: 'ANNUAL_LEAVE',
    from: '2026-11-17', to: '2026-11-18', organizationId: model.organizationId, q: model.q, page: '1',
  });
  const cancellation = within(rows[1]);
  expect(cancellation.getByText('승인')).toBeTruthy();
  expect(cancellation.queryByText('유효')).toBeNull();
  expect(url(cancellation.getByText('원래 휴가 신청')).searchParams.get('returnTo')).toBe(detail.searchParams.get('returnTo'));
});

it('대기 조회가 비어 있으면 검색 안내를 표시하고 정정은 기존 목록에서 표시한다', () => {
  const view = list({ leaveOperations: { ...model, status: 'PENDING', rows: [], page: 0, hasNext: false } });
  expect(view.getByRole('link', { name: '대기 3' }).getAttribute('aria-current')).toBe('page');
  expect(view.getByText('선택한 조건에 맞는 대기 휴가 신청이 없습니다.')).toBeTruthy();
  view.unmount();
  const correction = list({ requestScope: 'CORRECTION', title: '출퇴근 정정 현황', requests: [] });
  expect(correction.getByRole('heading', { name: '출퇴근 정정 현황' })).toBeTruthy();
  expect(correction.queryByLabelText('휴가 종류')).toBeNull();
});
