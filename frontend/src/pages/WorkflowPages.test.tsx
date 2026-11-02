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
    const view = domRender(<WorkflowPages page="request/list" data={{ title: '결재 대기', requests: [request], page: 0, hasNext: false, completed: false }} csrf={csrf} reload={() => {}} />);
    expect(await view.findByRole('grid', { name: '근태 신청 목록' })).toBeTruthy();
    expect(view.container.querySelector('[row-id="request-1"]')).toBeTruthy();
    expect(view.getByRole('link', { name: 'LEAVE_DAY' }).getAttribute('href')).toBe('/requests/request-1');
    expect(view.container.textContent).toContain(request.targetEmployeeName);
    expect(view.container.querySelector('script')).toBeNull();
  });
  it('알 수 없는 페이지는 다른 화면에 위임한다', async () => {
    expect((await render('unknown/page', {})).container.innerHTML).toBe('');
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
    expect(view.container.textContent).toContain('대기 2 DAY');
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
    expect(view.getByRole('button', { name: '독립 승인' })).toBeTruthy();
  });
  it('정정은 원천 결속·KEEP/SET/CLEAR·체크 해제 마커를 보존한다', async () => {
    const view = await render('request/correction', {
      day,
      exceptional: true,
      actions: ['KEEP', 'SET', 'CLEAR'],
      eventTimes: { 'in-1': '2026-10-26 09:00:00.123' },
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
    expect(view.getByLabelText('SET 출근 시각 (서울)').getAttribute('step')).toBe('0.001');
    expect(view.getAllByRole('option', { name: 'CLEAR' })).toHaveLength(2);
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
    expect((await render('request/detail', data)).container.textContent).toContain('12:00:00.123');
    data.detail.correction.actualBreaks = 'private-invalid-json';
    const view = await render('request/detail', data);
    expect(view.container.textContent).toContain('확인 필요');
    expect(view.container.textContent).not.toContain('private-invalid-json');
  });
  it('기초·조정 원장은 정수 DAY와 버전 제출을 유지한다', async () => {
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
  it('월 결과는 미확정과 부분합·경계 주를 구분하고 관리자 명령을 제한한다', async () => {
    const data = {
      admin: true,
      employeeId: employee.id,
      employees: [employee],
      from: '2026-10-01',
      to: '2026-10-31',
      calculateCommand: 'calc-command',
      exportCommand: 'export-command',
      totalTime: '8:00:00.000',
      actualTimes: {},
      overview: {
        month: '2026-10',
        days: [
          {
            id: 'result-1',
            workDate: day.workDate,
            current: false,
            result,
            ruleVersion: 'CALC-1',
            resultRevision: 2,
          },
        ],
        weeks: [
          {
            from: '2026-09-28',
            to: '2026-10-05',
            current: false,
            result: { confirmedWorkMillis: 0, unconfirmedDays: 7, over40hMillis: null },
          },
        ],
        summary: {
          current: false,
          result: { confirmedWorkMillis: 28800000, unconfirmedDays: 1, reasons: ['UNCONFIRMED'] },
        },
      },
    };
    const view = await render('worktime/month', data);
    expect(view.container.textContent).toContain('미확정');
    expect(view.container.textContent).toContain('8:00:00.000');
    expect(view.container.textContent).toContain('2026-10-04');
    expect(view.getByRole('link', { name: '계산 근거 상세' }).getAttribute('href')).toBe('/worktime/results/result-1');
    expect(view.getByRole('grid', { name: '일별 계산 결과' })).toBeTruthy();
    expect(view.container.querySelector(`[row-id="${day.workDate}"]`)).toBeTruthy();
    expect(view.container.querySelector('form[action="/admin/calculations"]')).toBeTruthy();
    expect(view.container.querySelector('form[action="/admin/reports/exports"]')).toBeTruthy();
    expect((await render('worktime/month', { ...data, admin: false })).container.querySelector('form[action="/admin/calculations"]')).toBeNull();
  });
  it('계산 상세는 저장 근거가 없으면 현재값으로 대체하지 않고 미확인으로 표시한다', async () => {
    const view = await render('worktime/result', {
      month: '2026-10',
      admin: false,
      times: { '확인 실근로': '미확정' },
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
    expect(view.container.textContent).toContain('미확정은 실제0시간과 다릅니다');
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
    expect(source.textContent).toContain('2026-10-26 09:00:00.123');
    expect(within(changed).getByRole('grid', { name: '당시 유효 출퇴근' })).toBeTruthy();
    expect(changed.querySelector('[row-id="effective-1"]')).toBeTruthy();
    expect(changed.textContent).toContain('2026-10-26 09:01:00.456');
  });

  it('일 결과는 미확정·실제0시간을 구분하고 ms를 숫자로 정렬한다', async () => {
    const days = [null, 0, 10, 2].map((actualWorkMillis, index) => ({
      id: null, workDate: `2026-10-${20 + index}`, current: true,
      result: { ...result, actualWorkMillis }, ruleVersion: 'CALC-1', resultRevision: 1,
    }));
    const period = { current: true, result: { confirmedWorkMillis: 12, unconfirmedDays: 1, reasons: [] } };
    const view = await render('worktime/month', { admin: false, employeeId: employee.id, employees: [],
      actualTimes: { '2026-10-21': '0:00:00.000' }, totalTime: '0:00:00.012',
      overview: { month: '2026-10', days, weeks: [], summary: period },
    });
    const grid = view.getByRole('grid', { name: '일별 계산 결과' });
    expect(grid.querySelector('[row-id="2026-10-20"] [col-id="actualWorkMillis"]')?.textContent).toBe('미확정');
    expect(grid.querySelector('[row-id="2026-10-21"] [col-id="actualWorkMillis"]')?.textContent).toBe('0');
    expect(grid.querySelector('[row-id="2026-10-21"] [col-id="actualTime"]')?.textContent).toBe('0:00:00.000');
    fireEvent.click(within(grid).getByRole('columnheader', { name: /실근로 ms/ }).querySelector('.ag-header-cell-label')!);
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
    expect(view.container.textContent).toContain('2026-10-26 09:00:00.123');
    expect(view.container.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(view.container.querySelector('img')).toBeNull();
  });
  it('출퇴근 확인은 사실/검토 개정과 원천 지문·명령을 제출한다', async () => {
    const view = await render('clock/history', {
      day,
      eventTimes: { 'in-1': '2026-10-26 09:00:00.123' },
      confirmationForm: {
        workDate: day.workDate,
        baseInputRevision: 2,
        expectedReviewRevision: 3,
        sourceFingerprint: 'clock-fingerprint',
        commandId: 'confirm-command',
      },
    });
    expect(view.container.textContent).toContain('근무 일정 미배정');
    const posted = body(view, '/me/work-confirmations');
    expect(posted.get('expectedReviewRevision')).toBe('3');
    expect(posted.get('sourceFingerprint')).toBe('clock-fingerprint');
    expect(view.container.textContent).toContain('09:00:00.123');
    expect(view.getByRole('grid', { name: '원천 출퇴근 기록' })).toBeTruthy();
    expect(view.container.querySelector('[row-id="in-1"]')).toBeTruthy();
  });
  it('서울 오늘 조회에서만 출퇴근 명령을 제공한다', () => {
    const data = { day, today: day.workDate, isToday: true, inCommand: 'in-cmd', outCommand: 'out-cmd', eventTimes: {}, confirmationForm: { workDate: day.workDate, commandId: 'confirm' } };
    const html = render('clock/history', data);
    expect(html).toContain('action="/me/attendance/events"');
    expect(html).toContain('name="commandId" value="in-cmd"');
    expect(html).toContain('name="commandId" value="out-cmd"');
    expect(render('clock/history', { ...data, isToday: false })).not.toContain('action="/me/attendance/events"');
    expect(render('clock/history', { ...data, today: '2026-11-02' })).not.toContain('action="/me/attendance/events"');
  });
});
