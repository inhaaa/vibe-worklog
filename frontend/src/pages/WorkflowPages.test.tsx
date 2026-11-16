// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render as domRender, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { WorkflowPages } from './WorkflowPages';

const csrf = { token: 'csrf-value', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
afterEach(cleanup);
async function render(page: string, data: Record<string, unknown>) {
  const view = domRender(<WorkflowPages page={page} data={data} csrf={csrf} reload={() => {}} />);
  await Promise.all(Array.from(view.container.querySelectorAll('.w-sheet[role="region"]')).map(sheet =>
    within(view.container).findByRole('grid', { name: sheet.getAttribute('aria-label')! })));
  return view;
}
const body = (view: Awaited<ReturnType<typeof render>>, action: string) =>
  new FormData(view.container.querySelector<HTMLFormElement>(`form[action="${action}"]`)!);
const employee = { id: 'employee-1', employeeNo: 'E001', displayName: '홍길동' };
const bucket = {
  id: 'bucket-1',
  usableFrom: '2026-01-01',
  usableTo: null,
  balanceDays: 7,
  availableDays: 7,
  pendingDays: 2,
};
const request = {
  id: 'request-1',
  targetEmployeeName: '<script>alert(1)</script>',
  kind: 'LEAVE_DAY',
  fromDate: '2026-11-01',
  toDate: '2026-11-02',
  quantityDays: 2,
  status: 'PENDING',
  effectStatus: 'NONE',
  version: 4,
  reason: '<img src=x onerror=alert(1)>',
  approverName: '승인자',
  originalRequestId: null,
};
const day = {
  employeeId: 'employee-1',
  workDate: '2026-10-26',
  events: [{ id: 'in-1', kind: 'IN', occurredAt: '2026-10-26T00:00:00.123Z' }],
  effectiveEvents: [{ id: 'in-1', kind: 'IN', occurredAt: '2026-10-26T00:00:00.123Z' }],
  inputRevision: 2,
  reviewRevision: 3,
  dayKind: 'UNCONFIRMED',
  schedule: null,
  confirmation: null,
};
const confirmable = {
  ...day,
  dayKind: 'WORKDAY',
  calendarVersionId: 'calendar-1',
  schedule: { workday: true, startTime: '09:00:00.000', endTime: '18:00:00.000', breaks: [{ startTime: '12:00:00.000', endTime: '13:00:00.000' }] },
  events: [{ id: 'in-1', kind: 'IN', occurredAt: '2026-10-26T00:00:00.123Z' }, { id: 'out-1', kind: 'OUT', occurredAt: '2026-10-26T09:03:40.000Z' }],
  effectiveEvents: [{ id: 'in-1', kind: 'IN', occurredAt: '2026-10-26T00:00:00.123Z' }, { id: 'out-1', kind: 'OUT', occurredAt: '2026-10-26T09:03:40.000Z' }],
};
const confirmationForm = { workDate: day.workDate, baseInputRevision: 2, expectedReviewRevision: 3, sourceFingerprint: 'clock-fingerprint', commandId: 'confirm-command' };
const result = {
  status: 'REVIEW_REQUIRED',
  actualWorkMillis: null,
  confirmedBreakMillis: null,
  over8hMillis: null,
  leaveDays: 0,
  inputRevision: 2,
  reviewRevision: 3,
  reasons: ['ACTUAL_WORK_UNCONFIRMED'],
};

describe('WorkflowPages', () => {
  it('업무 조회 신청 목록을 공통 그리드와 안정적 행 ID로 표시한다', async () => {
    const view = domRender(<WorkflowPages page="request/list" data={{ title: '고객사 신청 현황', requests: [request] }} csrf={csrf} reload={() => {}} />);
    expect(await view.findByRole('grid', { name: '근태 신청 목록' })).toBeTruthy();
    expect(view.container.querySelector('[row-id="request-1"]')).toBeTruthy();
    expect(view.getByRole('link', { name: 'LEAVE_DAY' }).getAttribute('href')).toBe('/requests/request-1');
    expect(view.container.textContent).toContain(request.targetEmployeeName);
    expect(view.container.querySelector('script')).toBeNull();
  });
  it('알 수 없는 페이지는 다른 화면에 위임한다', async () => {
    expect((await render('unknown/page', {})).container.innerHTML).toBe('');
  });
  it('결재 정보가 없는 관리 목록과 fragment의 공통 그리드 계약은 유지한다', async () => {
    for (const page of ['request/list', 'request/rows']) {
      const view = await render(page, { title: '고객사 신청 현황', requests: [request] });
      expect(view.getByRole('grid', { name: '근태 신청 목록' })).toBeTruthy();
      expect(view.container.querySelector('[row-id="request-1"]')).toBeTruthy();
      expect(view.container.querySelector('.t-approval-workspace')).toBeNull();
      expect(view.queryByRole('navigation', { name: '결재 목록' })).toBeNull();
      view.unmount();
    }
  });
  it('결재 대기함은 조회한 페이지 건수와 신청자·기간·한글 상태를 카드로 표시한다', async () => {
    const view = await render('request/list', { title: '결재 대기함', requests: [request], completed: false, page: 0, hasNext: true });
    expect(view.getByRole('list', { name: '결재 신청 목록' })).toBeTruthy();
    const text = view.container.textContent;
    for (const value of ['이 페이지 1건', '종일 연차', '결재 대기', '효력 없음', '2일', '2026-11-01', '2026-11-02', request.targetEmployeeName, request.reason]) expect(text).toContain(value);
    expect(view.getByRole('link', { name: '신청 검토' }).getAttribute('href')).toBe('/requests/request-1');
    expect(view.getByRole('link', { name: '다음' }).getAttribute('href')).toBe('/approvals?page=1');
    expect(view.container.querySelector('script,img,form')).toBeNull();
    expect(text).not.toContain('PENDING');
    expect(text).not.toContain('전체 1건');
  });
  it('처리 완료함은 본인 결정 범위 안내와 실제 승인·반려 상태를 표시한다', async () => {
    const view = await render('request/list', { title: '내가 처리한 결재', completed: true, page: 1, hasNext: false, requests: [
      { ...request, status: 'APPROVED', effectStatus: 'EFFECTIVE' },
      { ...request, id: 'request-2', status: 'REJECTED', effectStatus: 'NONE' },
    ] });
    const text = view.container.textContent;
    for (const value of ['이 페이지 2건', '본인이 승인하거나 반려한 신청입니다.', '철회는 처리한 결재에 포함하지 않습니다.', '유효']) expect(text).toContain(value);
    expect(view.getByRole('link', { name: '내가 처리한 결재' }).getAttribute('aria-current')).toBe('page');
    expect(view.container.querySelector('[data-status="APPROVED"]')?.textContent).toBe('승인');
    expect(view.container.querySelector('[data-status="REJECTED"]')?.textContent).toBe('반려');
    expect(view.getAllByRole('link', { name: '상세 보기' })).toHaveLength(2);
    expect(view.getByRole('link', { name: '이전' }).getAttribute('href')).toBe('/approvals/completed?page=0');
    expect(view.queryByRole('link', { name: '다음' })).toBeNull();
  });
  it('결재 목록의 빈 상태는 대기와 처리 완료를 구분하고 가상 신청을 만들지 않는다', async () => {
    const waiting = await render('request/list', { title: '결재 대기함', requests: [], completed: false, page: 0, hasNext: false });
    expect(waiting.container.textContent).toContain('현재 결재할 신청이 없습니다.');
    expect(waiting.container.textContent).toContain('이 페이지 0건');
    expect(waiting.container.querySelector('a[href^="/requests/"]')).toBeNull();
    expect(waiting.queryByRole('link', { name: '이전' })).toBeNull();
    waiting.unmount();
    const completed = await render('request/list', { title: '내가 처리한 결재', requests: [], completed: true, page: 0, hasNext: false });
    expect(completed.container.textContent).toContain('아직 처리한 결재가 없습니다.');
    expect(completed.container.querySelector('a[href^="/requests/"]')).toBeNull();
  });
  it('결재 카드의 정정·취소와 알 수 없는 값은 가상 DAY나 상태로 바꾸지 않는다', async () => {
    const view = await render('request/list', { title: '결재 대기함', completed: false, requests: [
      { ...request, id: 'correction', kind: 'ATTENDANCE_CORRECTION', quantityDays: null },
      { ...request, id: 'cancel', kind: 'CANCEL_LEAVE', quantityDays: 2, effectStatus: 'CANCELED' },
      { ...request, id: 'unknown', kind: 'FUTURE_KIND', status: 'FUTURE_STATUS', effectStatus: 'FUTURE_EFFECT', quantityDays: 10 },
    ] });
    const text = view.container.textContent;
    for (const value of ['출퇴근·휴게 정정', '연차 취소', '효력 취소', 'FUTURE_KIND', 'FUTURE_STATUS', 'FUTURE_EFFECT']) expect(text).toContain(value);
    expect(text).not.toContain(' DAY');
    expect(text).not.toContain('null');
  });
  it('지정 결재자는 대기함 복귀와 구분된 승인·반려 검토 폼을 사용한다', async () => {
    const view = await render('request/detail', { request, detail: { days: [], history: [], correction: null }, canDecide: true, pageCommand: 'decision-command' });
    expect(view.getByRole('link', { name: '대기함으로' }).getAttribute('href')).toBe('/approvals');
    expect(view.getByRole('heading', { name: '결재 검토', level: 2 })).toBeTruthy();
    for (const name of ['승인', '반려']) expect(view.getByRole('heading', { name, level: 3 })).toBeTruthy();
    for (const name of ['승인 검토', '반려 검토']) {
      const form = view.getByRole('form', { name });
      const reason = form.querySelector<HTMLTextAreaElement>('textarea[name="reason"]')!;
      expect(reason.required).toBe(true);
      expect(reason.maxLength).toBe(500);
      const posted = new FormData(form as HTMLFormElement);
      expect(posted.get('expectedVersion')).toBe('4');
      expect(posted.get('commandId')).toBe('decision-command');
    }
    expect(view.getByRole('button', { name: '승인하기' })).toBeTruthy();
    expect(view.getByRole('button', { name: '반려하기' })).toBeTruthy();
    expect(view.container.textContent).toContain('결정 사유는 처리 이력에 남습니다.');
    view.unmount();
    const own = await render('request/detail', { request, detail: { days: [], history: [], correction: null }, canDecide: false });
    expect(own.queryByRole('link', { name: '대기함으로' })).toBeNull();
    expect(own.queryByRole('button', { name: '승인하기' })).toBeNull();
    expect(own.queryByRole('button', { name: '반려하기' })).toBeNull();
  });
  it('타인 신청의 처리 후 상세에서도 실제 승인자 역할은 본인 처리 완료함으로 돌아간다', async () => {
    for (const status of ['APPROVED', 'REJECTED']) {
      const view = await render('request/detail', {
        request: { ...request, status }, detail: { days: [], history: [], correction: null },
        canDecide: false, ownRequest: false, workspacePrincipal: { roles: ['EMPLOYEE', 'APPROVER'] },
      });
      expect(view.getByRole('link', { name: '내가 처리한 결재' }).getAttribute('href')).toBe('/approvals/completed');
      expect(view.queryByRole('link', { name: '대기함으로' })).toBeNull();
      expect(view.container.querySelector('form[action$="/approve"],form[action$="/reject"]')).toBeNull();
      view.unmount();
    }
  });
  it('본인 여부가 명시되지 않거나 자기 신청·관리자뿐인 경우 완료함 동선을 추정하지 않는다', async () => {
    for (const data of [
      { ownRequest: true, workspacePrincipal: { roles: ['EMPLOYEE', 'APPROVER'] } },
      { workspacePrincipal: { roles: ['EMPLOYEE', 'APPROVER'] } },
      { ownRequest: false, workspacePrincipal: { roles: ['EMPLOYEE', 'ATTENDANCE_ADMIN'] } },
      { ownRequest: false },
    ]) {
      const view = await render('request/detail', { request: { ...request, status: 'APPROVED' }, detail: { days: [], history: [], correction: null }, canDecide: false, ...data });
      expect(view.queryByRole('link', { name: '내가 처리한 결재' })).toBeNull();
      expect(view.queryByRole('link', { name: '대기함으로' })).toBeNull();
      view.unmount();
    }
    const pending = await render('request/detail', { request, detail: { days: [], history: [], correction: null }, canDecide: false, ownRequest: false, workspacePrincipal: { roles: ['EMPLOYEE', 'APPROVER'] } });
    expect(pending.queryByRole('link', { name: '내가 처리한 결재' })).toBeNull();
  });
  it('종일 연차의 묶음·기간·재시도 명령과 미예약 대기량을 보존한다', async () => {
    const view = await render('request/mine', {
      buckets: [bucket],
      requests: [],
      form: {
        bucketId: 'bucket-1',
        from: '2026-11-01',
        to: '2026-11-02',
        reason: '연차',
        commandId: 'leave-command',
      },
    });
    const posted = body(view, '/me/requests/leave');
    expect(posted.get('commandId')).toBe('leave-command');
    expect(posted.get('from')).toBe('2026-11-01');
    expect(posted.get('to')).toBe('2026-11-02');
    expect(view.container.textContent).toContain('대기는 예약·차감하지 않습니다');
    expect(view.container.textContent).toContain('대기 2일');
  });
  it('서버가 승인 권한을 주지 않으면 자기승인·담당자 승인을 표시하지 않는다', async () => {
    const view = await render('request/detail', {
      request,
      detail: { days: [], history: [], correction: null },
      admin: true,
      canDecide: false,
      candidates: [],
      pageCommand: 'decision-command',
    });
    expect(view.container.querySelector('form[action$="/approve"],form[action$="/reject"]')).toBeNull();
    expect(view.container.querySelector('form[action$="/reassign"]')).toBeTruthy();
    expect(view.container.textContent).toContain(request.targetEmployeeName);
    expect(view.container.querySelector('script,img')).toBeNull();
  });
  it('독립 승인·반려는 요청 버전과 동일 명령을 제출한다', async () => {
    const view = await render('request/detail', {
      request,
      detail: { days: [], history: [], correction: null },
      canDecide: true,
      canWithdraw: true,
      canCancel: true,
      canCancelException: true,
      pageCommand: 'decision-command',
    });
    for (const action of [
      'approve',
      'reject',
      'withdraw',
      'cancellations',
      'cancellation-exceptions',
    ])
      {
        const form = view.container.querySelector(`form[action$="/requests/request-1/${action}"]`) as HTMLFormElement;
        expect(form).toBeTruthy();
        expect(new FormData(form).get('expectedVersion')).toBe('4');
        expect(new FormData(form).get('commandId')).toBe('decision-command');
      }
    expect(view.getByRole('button', { name: '승인하기' })).toBeTruthy();
  });
  it('결재 진행은 대상 사원·현재 결재 담당과 현재 상태를 결재란으로 보여 준다', async () => {
    const view = await render('request/detail', { request, detail: { days: [], history: [], correction: null } });
    const approval = view.getByRole('region', { name: '결재 진행' });
    const steps = within(within(approval).getByRole('list', { name: '결재란' })).getAllByRole('listitem');
    expect(steps.map(step => step.querySelector('strong')?.textContent)).toEqual([request.targetEmployeeName, '승인자']);
    expect(within(steps[1]).getByText('결재 대기').className).toBe('t-tag amber');
  });
  it.each(['ATTENDANCE_CORRECTION', 'CANCEL_LEAVE'])('대리 작성한 %s 상세에서 대상 사원을 기안자로 표시하지 않는다', async kind => {
    const view = await render('request/detail', {
      request: { ...request, kind, exceptional: true, authorAccountId: 'admin-account', targetEmployeeId: 'employee-2', targetEmployeeName: '합성 대상 사원' },
      detail: { days: [], history: [], correction: null },
    });
    const approval = view.getByRole('region', { name: '결재 진행' });
    const target = within(within(approval).getByRole('list', { name: '결재란' })).getAllByRole('listitem')[0];
    expect(within(target).getByText('대상 사원')).toBeTruthy();
    expect(within(target).getByText('합성 대상 사원')).toBeTruthy();
    expect(within(approval).queryByText('기안', { exact: true })).toBeNull();
  });
  it('신청 상세는 내 신청으로 돌아가는 링크와 한글 상태·정보 카드를 표시한다', async () => {
    const view = await render('request/detail', {
      request,
      detail: { days: [{ leaveDate: '2026-11-01', quantityDays: 0, zeroReason: 'CONFIRMED_HOLIDAY' }], history: [], correction: null },
    });
    expect(view.container.querySelector('.t-request-detail-page')).toBeTruthy();
    expect(view.getByRole('heading', { name: '신청 상세', level: 1 })).toBeTruthy();
    expect(view.getByRole('link', { name: '내 신청' }).getAttribute('href')).toBe('/me/requests');
    expect(view.getByLabelText('현재 상태').textContent).toBe('결재 대기');
    const text = view.container.textContent;
    for (const label of ['종일 연차', '현재 효력', '효력 없음', '신청 기간', '신청 사유', '결재 담당', '2일', '확정 휴일']) expect(text).toContain(label);
    expect(text).toContain(request.reason);
    expect(text).toContain(request.targetEmployeeName);
    expect(view.container.querySelector('script,img')).toBeNull();
    expect(text).not.toContain('LEAVE_DAY');
    expect(text).not.toContain('CONFIRMED_HOLIDAY');
  });
  it('처리 이력은 저장된 시각·사유·상태 전이만 타임라인으로 표시한다', async () => {
    const view = await render('request/detail', {
      request: { ...request, status: 'APPROVED', effectStatus: 'EFFECTIVE' },
      detail: {
        days: [], correction: null,
        history: [
          { createdAt: '2026-11-01T00:00:00.123Z', eventKind: 'SUBMITTED', fromStatus: null, toStatus: 'PENDING', reason: '원래 사유' },
          { createdAt: '2026-11-01T01:00:00.456Z', eventKind: 'APPROVED', fromStatus: 'PENDING', toStatus: 'APPROVED', reason: '검토 완료' },
        ],
      },
    });
    const timeline = view.container.querySelector('.t-request-detail-timeline');
    expect(timeline).toBeTruthy();
    expect(timeline!.querySelectorAll('li')).toHaveLength(2);
    const times = view.container.querySelectorAll('time');
    expect(times).toHaveLength(2);
    expect(times[0].getAttribute('datetime')).toBe('2026-11-01T00:00:00.123Z');
    expect(times[0].textContent).toBe('2026-11-01 09:00:00');
    const text = view.container.textContent;
    for (const label of ['신청 제출', '결재 대기 → 승인', '검토 완료', '유효']) expect(text).toContain(label);
    for (const code of ['SUBMITTED', 'APPROVED', 'null']) expect(text).not.toContain(code);
  });
  it('저장 이력이 없으면 가상의 제출·완료 단계를 만들지 않는다', async () => {
    const view = await render('request/detail', { request, detail: { days: [], history: [], correction: null } });
    const history = within(view.getByRole('heading', { name: '처리 이력' }).closest('section')!);
    expect(history.getByText('저장된 처리 이력이 없습니다.')).toBeTruthy();
    expect(history.queryByRole('list')).toBeNull();
    expect(view.container.querySelector('time')).toBeNull();
    expect(view.queryByText('신청 제출', { exact: true })).toBeNull();
    expect(view.container.textContent).toContain('현재 가능한 결정·변경이 없습니다.');
  });
  it('정정은 DAY 수량 없이 유지·변경·제거를 표시한다', async () => {
    const view = await render('request/detail', {
      request: { ...request, kind: 'ATTENDANCE_CORRECTION', quantityDays: null, status: 'APPROVED', effectStatus: 'SUPERSEDED' },
      detail: {
        days: [], history: [],
        correction: { inAction: 'KEEP', outAction: 'SET', requestedOutAt: '2026-11-01T09:00:00.123Z', breaksConfirmed: false, actualBreaks: null },
      },
    });
    const text = view.container.textContent;
    for (const label of ['출퇴근·휴게 정정', '후속 정정으로 대체', '출근 유지', '퇴근 변경', '2026-11-01 18:00:00']) expect(text).toContain(label);
    for (const code of [' DAY', 'KEEP', 'SET']) expect(text).not.toContain(code);
  });
  it('제출 당시 결재선과 취소의 원신청 연결을 보이고 정책·버전은 숨긴다', async () => {
    const view = await render('request/detail', {
      request: { ...request, kind: 'CANCEL_LEAVE', originalRequestId: 'original-1', effectStatus: 'CANCELED' },
      detail: {
        days: [], history: [], correction: null,
        attendancePolicy: { code: 'ANNUAL', name: '연차 정책', revision: 2, effectiveFrom: '2026-01-01', unit: 'DAY', payKind: 'PAID', deductionRule: 'LEAVE_DAY', basis: '정책 근거' },
        routeSnapshot: { routeCode: 'TEAM', routeName: '조직장 결재', revision: 3, scopeKind: 'ORGANIZATION', approverKind: 'ORGANIZATION_HEAD', organizationName: '개발팀', resolvedApproverName: '최초 담당', resolvedAt: '2026-11-01T00:00:00Z' },
      },
    });
    expect(view.getByRole('link', { name: '원신청 보기' }).getAttribute('href')).toBe('/requests/original-1');
    expect(view.queryByRole('heading', { name: '제출 당시 근태정책' })).toBeNull();
    expect(view.getByRole('heading', { name: '제출 당시 결재선' })).toBeTruthy();
    const text = view.container.textContent;
    for (const label of ['연차 취소', '효력 취소', '취소 심사 중에는 원승인·차감이 유지됩니다.', '대상자의 현재 조직장', '최초 담당']) expect(text).toContain(label);
    for (const hidden of ['결재선 버전', '해석 시각', '제출 당시 근태정책', '신청 버전', '원천 기록은 보존하며', 'Asia/Seoul', ' DAY'])
      expect(text).not.toContain(hidden);
  });
  it('알 수 없는 상세 코드는 원래 값을 보존하고 알려진 정정·이력만 번역한다', async () => {
    const view = await render('request/detail', {
      request: { ...request, kind: 'FUTURE_KIND', status: 'FUTURE_STATUS', effectStatus: 'FUTURE_EFFECT' },
      detail: {
        days: [],
        history: [{ createdAt: '2026-11-01T00:00:00Z', eventKind: 'FUTURE_EVENT', fromStatus: 'FUTURE_STATUS', toStatus: 'PENDING', reason: '새 상태' }],
        correction: { inAction: 'CLEAR', outAction: 'FUTURE_ACTION', breaksConfirmed: false, actualBreaks: null },
      },
    });
    const text = view.container.textContent;
    for (const code of ['FUTURE_KIND', 'FUTURE_STATUS', 'FUTURE_EFFECT', 'FUTURE_EVENT', 'FUTURE_ACTION']) expect(text).toContain(code);
    expect(text).toContain('출근 제거');
    expect(text).toContain('FUTURE_STATUS → 결재 대기');
    expect(text).not.toContain(' DAY');
  });
  it('미지원 결재선 코드도 기존 의미로 추정하지 않고 원래 값을 표시한다', async () => {
    const view = await render('request/detail', {
      request: { ...request, kind: 'constructor', status: 'toString', effectStatus: 'valueOf' },
      detail: {
        days: [], history: [], correction: null,
        attendancePolicy: { code: 'CUSTOM', name: '새 정책', revision: 1, effectiveFrom: '2026-01-01', unit: 'FUTURE_UNIT', payKind: 'FUTURE_PAY', deductionRule: 'FUTURE_DEDUCTION', basis: '근거' },
        routeSnapshot: { routeCode: 'CUSTOM', routeName: '새 결재선', revision: 1, scopeKind: 'FUTURE_SCOPE', approverKind: 'FUTURE_APPROVER', resolvedApproverName: '담당', resolvedAt: '2026-11-01T00:00:00Z' },
      },
    });
    const text = view.container.textContent;
    for (const code of ['constructor', 'toString', 'valueOf', 'FUTURE_SCOPE', 'FUTURE_APPROVER']) expect(text).toContain(code);
  });
  it('정정은 원천 결속·KEEP/SET/CLEAR·체크 해제 마커를 보존한다', async () => {
    const view = await render('request/correction', {
      day,
      exceptional: true,
      actions: ['KEEP', 'SET', 'CLEAR'],
      eventTimes: { 'in-1': '2026-10-26 09:00:00' },
      form: {
        targetEmployeeId: 'employee-1',
        workDate: day.workDate,
        baseInputRevision: 2,
        sourceFingerprint: 'fingerprint',
        commandId: 'correction-command',
        inAction: 'SET',
        requestedInAt: '2026-10-26T09:00:00.123',
        outAction: 'KEEP',
        requestedOutAt: '',
        actualBreaks: '',
        reason: '정정',
      },
    });
    const posted = body(view, '/admin/requests/exceptions');
    expect(posted.get('sourceFingerprint')).toBe('fingerprint');
    expect(posted.get('baseInputRevision')).toBe('2');
    expect(posted.has('_presenceAsWork')).toBe(true);
    expect(posted.has('_breaksConfirmed')).toBe(true);
    expect(posted.has('presenceAsWork')).toBe(false);
    expect(posted.has('breaksConfirmed')).toBe(false);
    expect(posted.get('inAction')).toBe('SET');
    expect(posted.get('outAction')).toBe('KEEP');
    expect(posted.get('requestedInAt')).toBe('2026-10-26T09:00');
    expect(view.getByLabelText('출근 시각').getAttribute('step')).toBe('1');
    for (const label of ['출근 변경', '퇴근 변경']) {
      const options = (view.getByLabelText(label) as HTMLSelectElement).options;
      expect(Array.from(options, option => [option.value, option.text])).toEqual([
        ['KEEP', '유지'], ['SET', '시각 수정'], ['CLEAR', '누락으로 정정'],
      ]);
    }
  });
  it('저장된 실제 휴게는 검증된 서울 구간으로 표시하고 손상된 원문을 숨긴다', async () => {
    const data = {
      request,
      detail: {
        days: [],
        history: [],
        correction: {
          inAction: 'KEEP',
          outAction: 'KEEP',
          breaksConfirmed: true,
          actualBreaks:
            '[{"startAt":"2026-10-26T03:00:00.123Z","endAt":"2026-10-26T04:00:00.123Z"}]',
        },
      },
    };
    expect((await render('request/detail', data)).container.textContent).toContain('2026-10-26 12:00:00 ~ 2026-10-26 13:00:00');
    data.detail.correction.actualBreaks = 'private-invalid-json';
    const view = await render('request/detail', data);
    expect(view.container.textContent).toContain('확인 필요');
    expect(view.container.textContent).not.toContain('private-invalid-json');
  });
  it('기초·조정 원장은 정수 일수와 버전 제출을 유지한다', async () => {
    const base = await render('request/balances', {
      employee,
      employees: [employee],
      buckets: [bucket],
      form: { employeeId: employee.id, commandId: 'opening-command', quantityDays: 5 },
    });
    expect(base.container.querySelector('form[action="/admin/leave-balances/entries"]')).toBeTruthy();
    const quantity = base.container.querySelector('input[name="quantityDays"]')!;
    expect(quantity.getAttribute('step')).toBe('1');
    expect(quantity.getAttribute('min')).toBe('0');
    expect(base.getByRole('link', { name: '예외 정정 신청' }).getAttribute('href')).toBe('/admin/requests/exceptions/new?employeeId=employee-1');
    const ledger = await render('request/ledger', {
      bucketId: bucket.id,
      ledger: [
        {
          id: 'entry-1',
          entryKind: 'RESTORE',
          signedQuantityDays: 1,
          effectiveOn: '2026-10-26',
          sourceReference: '승인',
          reason: '취소',
        },
      ],
      adjustment: {
        expectedVersion: 6,
        commandId: 'adjust-command',
        effectiveOn: '2026-10-26',
        quantityDays: -1,
        sourceReference: '근거',
        reason: '조정',
      },
    });
    expect(ledger.container.textContent).toContain('RESTORE');
    expect(ledger.getByRole('grid', { name: '연차 원장' })).toBeTruthy();
    expect(ledger.container.querySelector('[row-id="entry-1"]')).toBeTruthy();
    const adjusted = body(ledger, '/admin/leave-balances/buckets/bucket-1/adjustments');
    expect(adjusted.get('expectedVersion')).toBe('6');
    expect(adjusted.get('quantityDays')).toBe('-1');
  });
  it('근로시간은 실근로·확인 전·휴가 지표와 다섯 열만 보이고 담당자 명령을 제한한다', async () => {
    const data = {
      admin: true, employeeId: employee.id, employees: [employee], from: '2026-10-01', to: '2026-10-31',
      calculateCommand: 'calc-command', exportCommand: 'export-command',
      overview: {
        month: '2026-10',
        days: [
          { id: 'result-1', workDate: '2026-10-26', current: true, pending: false, approvedLeaveDays: 0,
            result: { ...result, actualWorkMillis: 29_110_000, confirmedBreakMillis: 3_600_000, over8hMillis: 310_000 } },
          { id: null, workDate: '2026-10-27', current: false, pending: true, approvedLeaveDays: 0, result },
          { id: 'result-3', workDate: '2026-10-28', current: false, pending: true, approvedLeaveDays: 1,
            result: { ...result, actualWorkMillis: 28_800_000 } },
        ],
        weeks: [{ from: '2026-10-26', to: '2026-11-02', current: false, pendingDays: 2,
          result: { confirmedWorkMillis: 29_110_000, unconfirmedDays: 6, over40hMillis: null, reasons: [] } }],
        summary: { from: '2026-10-01', to: '2026-11-01', current: false, pendingDays: 2,
          result: { confirmedWorkMillis: 29_110_000, unconfirmedDays: 30, over40hMillis: null, reasons: ['PARTIAL_TOTAL'] } },
      },
    };
    const view = await render('worktime/month', data);
    const text = view.container.textContent ?? '';
    expect(view.getByRole('heading', { name: '근로시간' })).toBeTruthy();
    for (const hidden of ['일·주·월 근로시간 검토', '상태·최신성', '실근로 ms', '휴게 ms', 'PARTIAL_TOTAL', 'REVIEW_REQUIRED', 'CALC-1', '미확정', '(서울)', '결과 ID·입력/규칙 버전'])
      expect(text).not.toContain(hidden);
    const summary = view.getByLabelText('월 요약');
    expect(summary.textContent).toContain('실근로 합계8:05:10');
    expect(summary.textContent).toContain('확인 전2일');
    expect(summary.textContent).toContain('휴가1일');
    expect(Array.from(summary.querySelectorAll('div')).find(d => d.textContent?.startsWith('확인 전'))?.getAttribute('data-tone')).toBe('warn');
    const grid = view.getByRole('grid', { name: '일별 근로시간' });
    expect(Array.from(grid.querySelectorAll('.ag-header-cell[col-id]')).map(cell => cell.getAttribute('col-id')))
      .toEqual(['workDate', 'actualWorkMillis', 'confirmedBreakMillis', 'over8hMillis', 'approvedLeaveDays']);
    expect(within(grid).getByRole('columnheader', { name: /8시간 초과/ })).toBeTruthy();
    expect(grid.querySelector('[row-id="2026-10-26"] [col-id="actualWorkMillis"]')?.textContent).toBe('8:05:10');
    expect(grid.querySelector('[row-id="2026-10-28"] [col-id="actualWorkMillis"]')?.textContent).toBe('-');
    expect(grid.querySelector('[row-id="2026-10-28"] [col-id="approvedLeaveDays"]')?.textContent).toBe('1');
    expect(within(grid).getByRole('link', { name: '2026-10-26' }).getAttribute('href')).toBe('/worktime/results/result-1');
    expect(within(grid).queryByRole('link', { name: '2026-10-27' })).toBeNull();
    expect(grid.querySelector('[row-id="2026-10-27"] [col-id="workDate"]')?.textContent).toBe('2026-10-27');
    expect(grid.querySelector('[row-id="2026-10-26"] [col-id="approvedLeaveDays"]')?.textContent).toBe('-');
    const weeks = view.getByRole('grid', { name: '주간 근로시간' });
    expect(weeks.textContent).toContain('2026-10-26 ~ 2026-11-01');
    expect(weeks.querySelector('[col-id="pendingDays"]:not(.ag-header-cell)')?.textContent).toBe('2일');
    expect(weeks.querySelector('[col-id="confirmedWorkMillis"]:not(.ag-header-cell)')?.textContent).toBe('8:05:10');
    expect(weeks.querySelector('[col-id="over40hMillis"]:not(.ag-header-cell)')?.textContent).toBe('-');
    expect(view.container.querySelector('form[action="/admin/calculations"]')).toBeTruthy();
    expect(view.container.querySelector('form[action="/admin/reports/exports"]')).toBeTruthy();
    expect((await render('worktime/month', { ...data, admin: false })).container.querySelector('form[action="/admin/calculations"]')).toBeNull();
  });
  it('계산 상세는 저장 근거가 없으면 현재값으로 대체하지 않고 미확인으로 표시한다', async () => {
    const view = await render('worktime/result', {
      month: '2026-10',
      admin: false,
      times: { '확인 실근로': '-' },
      detail: {
        day: {
          ...day,
          id: 'result-1',
          result,
          resultRevision: 1,
          ruleVersion: 'CALC-1',
          current: false,
        },
        completeEvidence: false,
        latestVersion: false,
        latestResultId: 'result-2',
        clock: null,
        run: null,
        policy: null,
        assessment: null,
        employment: null,
        leaveEffects: [],
        pendingCorrections: [],
      },
    });
    expect(view.container.textContent).toContain('저장된 근거가 없거나 일부를 확인할 수 없습니다');
    expect(view.getByRole('link', { name: '최신 계산 결과' }).getAttribute('href')).toBe('/worktime/results/result-2');
    expect(view.container.textContent).toContain('저장 당시 주 기산점 검토 미확인');
    expect(view.container.textContent).toContain("'-'는 미확정이며 실제 0시간과 다릅니다");
  });
  it('저장 당시 원천·유효 사건을 서로 다른 그리드와 사건 ID로 표시한다', async () => {
    const effective = { id: 'effective-1', kind: 'IN', occurredAt: '2026-10-26T00:01:00.456Z' };
    const view = await render('worktime/result', { month: '2026-10', admin: false, times: {}, detail: {
      day: { ...day, id: 'result-1', result, resultRevision: 1, ruleVersion: 'CALC-1', current: true },
      completeEvidence: true, latestVersion: true, latestResultId: 'result-1',
      clock: { ...day, effectiveEvents: [effective] }, run: null, policy: null, assessment: null, employment: null,
      leaveEffects: [], pendingCorrections: [],
    } });
    const source = view.getByRole('region', { name: '당시 원천 출퇴근' });
    const changed = view.getByRole('region', { name: '당시 유효 출퇴근' });
    expect(within(source).getByRole('grid', { name: '당시 원천 출퇴근' })).toBeTruthy();
    expect(source.querySelector('[row-id="in-1"]')).toBeTruthy();
    expect(source.textContent).toContain('2026-10-26 09:00:00');
    expect(within(changed).getByRole('grid', { name: '당시 유효 출퇴근' })).toBeTruthy();
    expect(changed.querySelector('[row-id="effective-1"]')).toBeTruthy();
    expect(changed.textContent).toContain('2026-10-26 09:01:00');
  });

  it('재직 기간 밖 날짜는 값 칸을 비워 둔다', async () => {
    const days = [
      { id: null, workDate: '2026-10-19', current: false, pending: false, approvedLeaveDays: 0, employed: false, result },
      { id: 'result-2', workDate: '2026-10-20', current: true, pending: false, approvedLeaveDays: 0, employed: true,
        result: { ...result, actualWorkMillis: 28_800_000, confirmedBreakMillis: 3_600_000, over8hMillis: 0 } },
    ];
    const period = { current: true, pendingDays: 0, result: { confirmedWorkMillis: 12_000, unconfirmedDays: 1, over40hMillis: null, reasons: [] } };
    const view = await render('worktime/month', { admin: false, employeeId: employee.id, employees: [],
      overview: { month: '2026-10', days, weeks: [], summary: period } });
    const grid = view.getByRole('grid', { name: '일별 근로시간' });
    const cell = (row: string, col: string) => grid.querySelector(`[row-id="${row}"] [col-id="${col}"]`)?.textContent;
    expect(cell('2026-10-19', 'workDate')).toBe('2026-10-19');
    for (const col of ['actualWorkMillis', 'confirmedBreakMillis', 'over8hMillis', 'approvedLeaveDays']) expect(cell('2026-10-19', col)).toBe('');
    expect(cell('2026-10-20', 'actualWorkMillis')).toBe('8:00:00');
    expect(cell('2026-10-20', 'approvedLeaveDays')).toBe('-');
  });

  it('일별 실근로는 표시 문자열이 아닌 밀리초 값으로 정렬한다', async () => {
    const days = [null, 0, 36_000_000, 7_200_000].map((actualWorkMillis, index) => ({
      id: null, workDate: `2026-10-${20 + index}`, current: true, pending: false, approvedLeaveDays: 0,
      result: { ...result, actualWorkMillis },
    }));
    const period = { current: true, pendingDays: 0, result: { confirmedWorkMillis: 12_000, unconfirmedDays: 1, over40hMillis: null, reasons: [] } };
    const view = await render('worktime/month', { admin: false, employeeId: employee.id, employees: [],
      overview: { month: '2026-10', days, weeks: [], summary: period } });
    const grid = view.getByRole('grid', { name: '일별 근로시간' });
    expect(grid.querySelector('[row-id="2026-10-20"] [col-id="actualWorkMillis"]')?.textContent).toBe('-');
    expect(grid.querySelector('[row-id="2026-10-21"] [col-id="actualWorkMillis"]')?.textContent).toBe('0:00:00');
    expect(view.getByLabelText('월 요약').querySelector('[data-tone]')).toBeNull();
    fireEvent.click(within(grid).getByRole('columnheader', { name: /실근로/ }).querySelector('.ag-header-cell-label')!);
    await waitFor(() => expect(grid.querySelector('[row-id="2026-10-23"]')?.getAttribute('row-index')).toBe('2'));
    expect(grid.querySelector('[row-id="2026-10-22"]')?.getAttribute('row-index')).toBe('3');
  });
  it('정책 검토는 두 별도 계약과 unchecked 마커를 보존한다', async () => {
    const view = await render('worktime/policies', {
      employees: [employee],
      employeeId: employee.id,
      policies: [],
      assessments: [],
      policyForm: { commandId: 'policy-command', from: '2026-01-01', to: '2026-12-31' },
      assessmentForm: {
        employeeId: employee.id,
        commandId: 'assessment-command',
        from: '2026-01-01',
        to: '2026-12-31',
      },
    });
    expect(view.container.querySelector('form[action="/admin/work-assessments"]')).toBeTruthy();
    expect(view.container.querySelector('input[name="_mondayWeekReviewed"]')).toBeTruthy();
    expect(view.container.querySelector('input[name="_generalRuleReviewed"]')).toBeTruthy();
    expect(body(view, '/admin/work-assessments').get('commandId')).toBe('assessment-command');
  });
  it('감사 페이지의 이전은 after·다음은 before를 사용한다', async () => {
    const view = await render('worktime/audit', {
      audits: [],
      page: { previousCursor: 'newer-id', nextCursor: 'older-id' },
    });
    expect(view.getByRole('link', { name: '이전 페이지 (더 최근)' }).getAttribute('href')).toBe('/admin/audit?after=newer-id');
    expect(view.getByRole('link', { name: '다음 페이지 (더 과거)' }).getAttribute('href')).toBe('/admin/audit?before=older-id');
    expect(view.container.textContent).toContain('감사 기록이 없습니다');
    expect(view.getByRole('grid', { name: '고객사 감사 목록' })).toBeTruthy();
  });
  it('감사 상세는 서버의 안전한 업무 요약을 텍스트로 표시한다', async () => {
    const view = await render('worktime/audit-detail', {
      audit: {
        id: 'audit-1',
        action: 'APPROVE',
        beforeSummary: { status: 'PENDING' },
        afterSummary: { status: '<svg onload=alert(1)>' },
        reason: '<script>x</script>',
        at: '2026-10-26T00:00:00.123Z',
      },
    });
    expect(view.container.textContent).toContain('<svg onload=alert(1)>');
    expect(view.container.querySelector('svg,script')).toBeNull();
    expect(view.container.textContent).toContain('요약은 전체 저장 원문의 재현이 아닙니다');
  });
  it('감사 조회는 안정적인 행 ID·상세 링크·서울 시각을 보존한다', async () => {
    const view = await render('worktime/audit', { audits: [{ id: 'audit-1', at: '2026-10-26T00:00:00.123Z', action: 'APPROVE', targetKind: 'REQUEST', targetId: 'request-1', actorAccountId: 'account-1', reason: '<img src=x onerror=alert(1)>' }], page: { previousCursor: null, nextCursor: null } });
    expect(view.getByRole('grid', { name: '고객사 감사 목록' })).toBeTruthy();
    expect(view.container.querySelector('[row-id="audit-1"]')).toBeTruthy();
    expect(view.getByRole('link', { name: 'APPROVE' }).getAttribute('href')).toBe('/admin/audit/audit-1');
    expect(view.container.textContent).toContain('2026-10-26 09:00:00');
    expect(view.container.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(view.container.querySelector('img')).toBeNull();
  });
  it('출퇴근 기록은 구분·시각만 보이고 버전·캘린더 코드·안내문을 보이지 않는다', async () => {
    const view = await render('clock/history', { day: confirmable, eventTimes: { 'in-1': '2026-10-26 09:00:00' }, confirmationForm });
    const text = view.container.textContent ?? '';
    for (const hidden of ['출근 기록·실제 근로 확인', '사실 v', '검토 v', '캘린더:', 'Asia/Seoul', '체류시간을 실제 근로로', '원천'])
      expect(text).not.toContain(hidden);
    expect(text).toContain('예정 09:00~18:00 · 휴게 12:00~13:00');
    const grid = view.getByRole('grid', { name: '출퇴근 기록' });
    expect(Array.from(grid.querySelectorAll('.ag-header-cell[col-id]')).map(cell => cell.getAttribute('col-id'))).toEqual(['kind', 'occurredAt']);
    expect(within(grid).getByRole('columnheader', { name: /구분/ })).toBeTruthy();
    expect(within(grid).getByRole('columnheader', { name: /시각/ })).toBeTruthy();
    expect(grid.querySelector('[row-id="in-1"]')?.textContent).toContain('2026-10-26 09:00:00');
  });
  it('근무 확인은 출근·퇴근이 하나씩 있고 미확인일 때만 보이며 개정·지문·명령을 제출한다', async () => {
    const view = await render('clock/history', { day: confirmable, eventTimes: {}, confirmationForm });
    const posted = body(view, '/me/work-confirmations');
    expect(posted.get('expectedReviewRevision')).toBe('3');
    expect(posted.get('sourceFingerprint')).toBe('clock-fingerprint');
    expect(posted.get('commandId')).toBe('confirm-command');
    expect(view.getByRole('button', { name: '근무 확인' })).toBeTruthy();
    expect(view.container.textContent).not.toContain('원천 한 쌍');
    const IN = { id: 'in-1', kind: 'IN', occurredAt: '2026-10-26T00:00:00.000Z' }, OUT = { id: 'out-1', kind: 'OUT', occurredAt: '2026-10-26T09:00:00.000Z' };
    const withEffective = (effectiveEvents: unknown[]) => ({ ...confirmable, effectiveEvents });
    for (const blocked of [{ ...confirmable, schedule: null }, { ...day, schedule: confirmable.schedule, calendarVersionId: 'calendar-1' },
      { ...confirmable, confirmation: { id: 'c-1' } }, { ...confirmable, calendarVersionId: null },
      withEffective([IN, { ...IN, id: 'in-2' }, OUT]), withEffective([IN]),
      withEffective([{ ...IN, occurredAt: '2026-10-26T09:00:00.000Z' }, { ...OUT, occurredAt: '2026-10-26T00:00:00.000Z' }]),
      withEffective([IN, { ...OUT, occurredAt: '2026-10-26T15:30:00.000Z' }])])
      expect((await render('clock/history', { day: blocked, eventTimes: {}, confirmationForm })).container.querySelector('form[action="/me/work-confirmations"]')).toBeNull();
    const corrected = { ...confirmable, events: [IN, { ...IN, id: 'in-2' }, OUT], effectiveEvents: [IN, OUT] };
    const view2 = await render('clock/history', { day: corrected, eventTimes: {}, confirmationForm });
    expect(view2.container.querySelector('form[action="/me/work-confirmations"]')).toBeTruthy();
    expect(view2.container.textContent).toContain('정정 반영 기록');
    expect(view2.container.textContent).toMatch(/출근 · .*퇴근 · /);
  });
  it('캘린더·일정 상태를 한국어로 보이고 오늘 카드의 일정은 한 번만 보인다', async () => {
    const text = async (d: unknown) => (await render('clock/history', { day: d, eventTimes: {}, confirmationForm })).container.textContent ?? '';
    const holiday = await text({ ...confirmable, dayKind: 'HOLIDAY' });
    expect(holiday).toContain('휴일 · 휴게 12:00~13:00');
    expect(holiday).not.toContain('예정 09:00');
    expect(await text({ ...confirmable, dayKind: 'NON_WORKDAY' })).toContain('비근무일');
    expect(await text({ ...confirmable, calendarVersionId: null })).toContain('캘린더 미확인');
    expect(await text({ ...confirmable, schedule: null })).toContain('근무 일정 미배정');
    const today = await render('clock/history', { day: confirmable, today: day.workDate, isToday: true, inCommand: 'in-cmd', outCommand: 'out-cmd', eventTimes: {}, confirmationForm });
    expect((within(today.getByRole('region', { name: '오늘 출퇴근' })).getByText(/예정 09:00~18:00/)).textContent).toBe('예정 09:00~18:00 · 휴게 12:00~13:00');
    expect((today.container.textContent ?? '').split('예정 09:00~18:00').length - 1).toBe(1);
  });
  it('근무 확인 뒤에는 계산된 실근로를 초 단위로 보인다', async () => {
    const confirmed = { ...confirmable, confirmation: { id: 'c-1' } };
    expect((await render('clock/history', { day: confirmed, eventTimes: {}, confirmationForm, actualWorkMillis: 29_486_000 })).container.textContent)
      .toContain('근무 확인 완료 · 실근로 8:11:26');
    expect((await render('clock/history', { day: confirmed, eventTimes: {}, confirmationForm, actualWorkMillis: null })).container.textContent)
      .toContain('근무 확인 완료 · 실근로는 담당자 재집계 후 표시됩니다.');
  });
  it('서울 오늘 조회에서만 출퇴근 명령을 제공한다', async () => {
    const data = { day, today: day.workDate, isToday: true, inCommand: 'in-cmd', outCommand: 'out-cmd', eventTimes: {}, confirmationForm: { workDate: day.workDate, commandId: 'confirm' } };
    const view = await render('clock/history', data);
    const forms = Array.from(view.container.querySelectorAll<HTMLFormElement>('form[action="/me/attendance/events"]'));
    expect(forms.map(form => {
      const values = new FormData(form);
      return [values.get('eventKind'), values.get('commandId')];
    })).toEqual([['IN', 'in-cmd'], ['OUT', 'out-cmd']]);
    expect((await render('clock/history', { ...data, isToday: false })).container.innerHTML).not.toContain('action="/me/attendance/events"');
    expect((await render('clock/history', { ...data, today: '2026-11-02' })).container.innerHTML).not.toContain('action="/me/attendance/events"');
  });
});
