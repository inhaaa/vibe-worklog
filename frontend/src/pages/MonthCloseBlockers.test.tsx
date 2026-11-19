// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { AttendanceListBackLink, attendanceListReturn } from './CompanyAttendanceNavigation';
import { IdentityPages } from './IdentityPages';

const path = '/company/attendance';
const month = '2026-10';
const org = '2266031f-88f4-49b0-b426-69cd8cab1183';
const source = path + '?' + new URLSearchParams({ view: 'month-end', month, organizationId: org, q: '한 & 팀', reason: 'STALE', page: '2' });
const csrf = { token: 'test', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const row = { id: 'request-1', employeeId: 'employee-a', employeeNo: '101', employeeName: '한사원', kind: 'LEAVE_DAY',
  fromDate: '2026-09-30', toDate: '2026-10-02', outOfEmployment: false };
const list = { month, kind: 'PENDING_REQUEST', page: 1, total: 101, hasNext: true, rows: [row] };
const listHref = (kind: string, page: number, origin = source) => path + '?' + new URLSearchParams({
  view: 'close-blockers', month, kind, page: String(page), source: origin });
function show(model = list, roles = ['ATTENDANCE_ADMIN']) {
  window.history.replaceState(null, '', listHref(model.kind,model.page));
  return render(<IdentityPages page="company/attendance" data={{ monthCloseBlockers: model, canViewCheckout: true,
    workspacePrincipal: { roles } }} csrf={csrf} reload={vi.fn()} />);
}
function link(name: string) { return screen.getByRole('link', { name }).getAttribute('href')!; }
function query(url: string) { return new URL(url,'https://example.test').searchParams; }
afterEach(() => { cleanup(); window.history.replaceState(null, '', '/'); vi.unstubAllGlobals(); });

it('결재 대기 신청의 전체 기간·사원·종류를 보고 상세와 같은 페이지로 왕복한다', () => {
  show();
  expect(screen.getByRole('heading', { name: '결재 대기 신청' })).toBeTruthy();
  expect(screen.getByText(/101건/)).toBeTruthy();
  const item = within(screen.getByRole('listitem'));
  expect(item.getByText(/101.*한사원/)).toBeTruthy();
  expect(item.getByText(/2026-09-30/)).toBeTruthy();
  expect(item.getByText(/2026-10-02/)).toBeTruthy();
  expect(item.getByText('휴가 신청')).toBeTruthy();
  const detail = link('신청 상세');
  expect(new URL(detail,'https://example.test').pathname).toBe('/requests/request-1');
  const back = query(detail).get('returnTo')!;
  expect(Object.fromEntries(query(back))).toEqual(Object.fromEntries(query(listHref(list.kind,1))));
  render(<AttendanceListBackLink href={back} />);
  expect(link('월 마감 미처리 목록으로')).toBe(back);
  expect(link('월 마감 점검으로 돌아가기')).toBe(source + '#month-close-title');
  for (const [label,page] of [['이전',0],['다음',2]] as const) {
    expect(Object.fromEntries(query(link(label)))).toEqual(Object.fromEntries(query(listHref(list.kind,page))));
  }
});

it('진행 중 보완 요청에서 근무일 상세로 이동하며 재직기간 밖 요청은 정비가 필요함을 구별한다', () => {
  const followUp = { ...row, id: 'f-1', kind: 'CHECKOUT_FOLLOW_UP', fromDate: '2026-10-02', toDate: '2026-10-02' };
  show({ ...list, kind: 'OPEN_FOLLOW_UP', page: 0, total: 2, hasNext: false,
    rows: [followUp,{ ...followUp, id: 'f-2', employeeNo: '102', employeeName: '이사원', outOfEmployment: true }] });
  expect(screen.getByRole('heading', { name: '진행 중 보완 요청' })).toBeTruthy();
  const items = screen.getAllByRole('listitem').map(el => within(el));
  const detail = items[0].getByRole('link', { name: '근태 집계' }).getAttribute('href')!;
  expect(new URL(detail,'https://example.test').pathname).toBe('/admin/attendance');
  expect(Object.fromEntries(query(detail))).toMatchObject({ employeeId: row.employeeId, month, workDate: '2026-10-02' });
  expect(Object.fromEntries(query(query(detail).get('returnTo')!))).toEqual(Object.fromEntries(query(listHref('OPEN_FOLLOW_UP',0))));
  expect(items[1].getByText(/재직기간 밖/)).toBeTruthy();
  expect(items[1].queryByRole('link', { name: '근태 집계' })).toBeNull();
});

it('처리되어 빈 목록과 잘못된 복귀 주소에서 같은 달 점검으로 이동할 수 있다', () => {
  window.history.replaceState(null,'',listHref('PENDING_REQUEST',0,'https://example.test/outside'));
  render(<IdentityPages page="company/attendance" data={{ monthCloseBlockers: { ...list, page: 0, rows: [], total: 0, hasNext: false },
    canViewCheckout: true, workspacePrincipal: { roles: ['ATTENDANCE_ADMIN'] } }} csrf={csrf} reload={vi.fn()} />);
  expect(screen.getByText(/현재 결재 대기 신청이 없습니다/)).toBeTruthy();
  expect(query(link('월 마감 점검으로 돌아가기')).get('month')).toBe(month);
  expect(screen.queryByRole('link',{name:'다음'})).toBeNull();
});

it('차단 목록 복귀 주소는 동일 월 점검 출발지만 한 단계 허용한다', () => {
  expect(attendanceListReturn(listHref('OPEN_FOLLOW_UP',0))).toBeTruthy();
  for (const bad of [source.replace(month,'2026-09'), source + '&source=' + encodeURIComponent(source),
      'https://example.test/bad', source + '&organizationId=' + org]) {
    expect(attendanceListReturn(listHref('OPEN_FOLLOW_UP',0,bad))).toBeNull();
  }
  expect(attendanceListReturn(listHref('OTHER',0))).toBeNull();
  expect(attendanceListReturn(listHref('PENDING_REQUEST',0) + '&q=검색')).toBeNull();
});

it('담당자 권한을 확인하지 못한 화면에는 사원 행을 표시하지 않는다', () => {
  show(list,['EMPLOYEE', 'COMPANY_REPRESENTATIVE']);
  expect(screen.getByRole('alert').textContent).toContain('권한');
  expect(screen.queryByText(/한사원/)).toBeNull();
  expect(screen.queryByRole('link',{ name: '신청 상세' })).toBeNull();
});

it('근무일 지표의 페이지와 상세에서도 점검을 시작한 필터와 페이지로 돌아간다', () => {
  vi.stubGlobal('fetch',vi.fn(() => new Promise(() => {})));
  window.history.replaceState(null,'',path + '?' + new URLSearchParams({ view: 'month-end', month, reason: 'UNCALCULATED', page: '0', source }));
  const review = { month, latestMonth: '2026-11', organizationId: null, options: [], q: '', reason: 'UNCALCULATED',
    page: 0, hasNext: true, hasPrevious: false, error: null, rows: [{ employeeId: row.employeeId, employeeNo: row.employeeNo,
      employeeName: row.employeeName, workDate: '2026-10-02', organizationName: '개발팀', reasons: ['UNCALCULATED'], resultId: null, pendingRequestId: null }] };
  render(<IdentityPages page="company/attendance" data={{ monthEndReview: review, canViewCheckout: true, workspacePrincipal: { roles: ['ATTENDANCE_ADMIN'] } }} csrf={csrf} reload={vi.fn()} />);
  expect(link('월 마감 점검으로 돌아가기')).toBe(source + '#month-close-title');
  const back = query(link('근태 집계')).get('returnTo')!;
  expect(attendanceListReturn(back)).toBe(back);
  expect(query(back).get('source')).toBe(source);
  expect(query(link('다음')).get('source')).toBe(source);
});
