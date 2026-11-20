// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import type { PageEnvelope } from './types';

const csrf = { token: 'csrf', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const scope = {
  workspacePrincipal: { roles: ['EMPLOYEE', 'ATTENDANCE_ADMIN'] },
  workspaceEmployee: { employeeName: '합성 담당자', companyName: '합성 회사' },
};
const filters = new URLSearchParams({
  scope: 'LEAVE', status: 'APPROVED', leaveTypeCode: 'LEAVE_DAY', requestKind: 'CANCEL_LEAVE',
  from: '2026-11-01', to: '2026-11-30', organizationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', q: '김 직원', page: '2',
});
const listPath = '/admin/requests?' + filters;
const detailPath = '/requests/cancel-1?' + new URLSearchParams({ returnTo: listPath });
const originalPath = '/requests/original-1?' + new URLSearchParams({ returnTo: listPath });
const cancellation = {
  id: 'cancel-1', originalRequestId: 'original-1', targetEmployeeName: '기존 대상 사원',
  version: 0, kind: 'CANCEL_LEAVE', status: 'APPROVED', effectStatus: 'NONE',
  fromDate: '2026-11-04', toDate: '2026-11-04', approverName: '결재자', reason: '합성 취소 사유',
  submittedAt: '2026-11-01T23:30:00Z',
};

const listing = (fresh = false): PageEnvelope => ({
  page: 'request/list', csrf, data: { ...scope, title: '휴가 신청 현황', requestScope: 'LEAVE',
    requests: [{ ...cancellation, targetEmployeeName: fresh ? '최신 처리 사원' : cancellation.targetEmployeeName }],
  },
});

function detail(returnTo: string | null = listPath, original = false): PageEnvelope {
  return { page: 'request/detail', csrf, data: { ...scope, admin: true, leaveReturnTo: returnTo,
    pageCommand: 'command-1', candidates: [],
    request: original ? { ...cancellation, id: 'original-1', originalRequestId: null, kind: 'LEAVE_DAY', effectStatus: 'CANCELED' } : cancellation,
    detail: { days: [], history: [], correction: null, attendancePolicy: { name: '합성 이전 연차 명칭' } },
  } };
}

function response(model: PageEnvelope, href: string) {
  const result = new Response(JSON.stringify(model), { status: 200, headers: { 'Content-Type': 'application/vnd.worklog.page+json' } });
  Object.defineProperty(result, 'url', { value: new URL(href, window.location.origin).href });
  return result;
}

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.open = false; } });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); history.replaceState(null, '', '/'); });

describe('담당자 휴가 목록 복귀', () => {
  it('취소 상세와 원신청에서 조회 조건 전체를 담은 명시 링크로 최신 휴가 목록을 연다', async () => {
    history.replaceState(null, '', listPath);
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(response(detail(), detailPath))
      .mockResolvedValueOnce(response(detail(listPath, true), originalPath))
      .mockResolvedValueOnce(response(listing(true), listPath));
    vi.stubGlobal('fetch', fetcher);
    render(<App initial={listing()} />);
    fireEvent.click(await screen.findByRole('link', { name: '연차 취소' }));

    const dialog = within(await screen.findByRole('dialog'));
    expect(dialog.getByRole('link', { name: '휴가 신청 목록으로' }).getAttribute('href')).toBe(listPath);
    const original = dialog.getByRole('link', { name: '원신청 보기' });
    expect(original.getAttribute('href')).toBe(originalPath);
    fireEvent.click(original);
    await waitFor(() => expect(location.pathname).toBe('/requests/original-1'));

    fireEvent.click(within(screen.getByRole('dialog')).getByRole('link', { name: '휴가 신청 목록으로' }));
    await screen.findByText('최신 처리 사원');
    expect(fetcher.mock.calls.at(-1)?.[0]).toBe(new URL(listPath, location.origin).href);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it.each(['브라우저 뒤로', '모달 닫기'])('%s도 기존 목록을 같은 조건으로 재조회한다', async action => {
    history.replaceState(null, '', listPath);
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(response(detail(), detailPath))
      .mockResolvedValueOnce(response(listing(true), listPath));
    vi.stubGlobal('fetch', fetcher);
    render(<App initial={listing()} />);
    fireEvent.click(await screen.findByRole('link', { name: '연차 취소' }));
    await screen.findByRole('dialog');

    if (action === '브라우저 뒤로') act(() => history.back());
    else fireEvent.click(within(screen.getByRole('dialog')).getAllByRole('button', { name: '닫기' })[0]);
    await screen.findByText('최신 처리 사원');
    expect(fetcher.mock.calls.at(-1)?.[0]).toBe(new URL(listPath, location.origin).href);
    expect(location.pathname + location.search).toBe(listPath);
  });

  it.each([
    '//external.test/admin/requests?scope=LEAVE',
    '/admin/requests?scope=CORRECTION',
    '/admin/requests?scope=LEAVE&scope=CORRECTION',
    '/admin/requests?scope=LEAVE&foreign=1',
  ])('허용되지 않은 복귀 주소를 링크에 넣지 않는다 (%s)', target => {
    history.replaceState(null, '', '/requests/cancel-1');
    render(<App initial={detail(target)} />);
    expect(screen.queryByRole('link', { name: '휴가 신청 목록으로' })).toBeNull();
    expect(screen.getByRole('link', { name: '원신청 보기' }).getAttribute('href')).toBe('/requests/original-1');
  });

  it('취소 상세와 원신청 상세는 당시 휴가 종류를 표시하고 취소 신청을 구분한다', () => {
    history.replaceState(null, '', '/requests/cancel-1');
    const view = render(<App initial={detail()} />);
    expect(screen.getByText('합성 이전 연차 명칭')).toBeTruthy();
    expect(screen.getByText('기존 대상 사원 · 연차 취소')).toBeTruthy();
    view.unmount();
    history.replaceState(null, '', '/requests/original-1');
    render(<App initial={detail(listPath, true)} />);
    expect(screen.getByText('합성 이전 연차 명칭')).toBeTruthy();
  });

  it('휴가 접수 시각과 승인·반려의 실제 처리자를 결재 담당과 구분한다', () => {
    history.replaceState(null, '', '/requests/cancel-1');
    const approved = detail();
    const view = render(<App initial={{ ...approved, data: { ...approved.data, leaveDecidedByName: '실제 합성 처리자' } }} />);
    expect(screen.getByText('현재 결재 담당 결재자')).toBeTruthy();
    expect(screen.getByText('실제 합성 처리자')).toBeTruthy();
    view.unmount();
    const rejected = render(<App initial={{ ...approved, data: { ...approved.data, request: { ...cancellation, status: 'REJECTED' } } }} />);
    expect(screen.getByText('실제 처리자')).toBeTruthy();
    expect(screen.getByText('미확인')).toBeTruthy();
    rejected.unmount();
    render(<App initial={{ ...approved, data: { ...approved.data, request: { ...cancellation, status: 'PENDING' } } }} />);
    expect(screen.getByText('접수 시각')).toBeTruthy();
    expect(screen.getByText('2026-11-02 08:30:00').getAttribute('datetime')).toBe(cancellation.submittedAt);
    expect(screen.queryByText('실제 처리자')).toBeNull();
  });
});
