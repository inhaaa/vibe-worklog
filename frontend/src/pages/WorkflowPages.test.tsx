import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { WorkflowPages } from './WorkflowPages';

const csrf = { token: 'csrf-value', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const render = (page: string, data: Record<string, unknown>) =>
  renderToStaticMarkup(<WorkflowPages page={page} data={data} csrf={csrf} reload={() => {}} />);
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
  it('알 수 없는 페이지는 다른 화면에 위임한다', () => {
    expect(render('unknown/page', {})).toBe('');
  });
  it('종일 연차의 묶음·기간·재시도 명령과 미예약 대기량을 보존한다', () => {
    const html = render('request/mine', {
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
    expect(html).toContain('action="/me/requests/leave"');
    expect(html).toContain('name="commandId" value="leave-command"');
    expect(html).toContain('name="from"');
    expect(html).toContain('name="to"');
    expect(html).toContain('대기는 예약·차감하지 않습니다');
    expect(html).toContain('대기 2 DAY');
  });
  it('서버가 승인 권한을 주지 않으면 자기승인·담당자 승인을 표시하지 않는다', () => {
    const html = render('request/detail', {
      request,
      detail: { days: [], history: [], correction: null },
      admin: true,
      canDecide: false,
      candidates: [],
      pageCommand: 'decision-command',
    });
    expect(html).not.toContain('/approve');
    expect(html).not.toContain('/reject');
    expect(html).toContain('/reassign');
    expect(html).toContain('&lt;script&gt;');
    expect(html).not.toContain('<script>');
  });
  it('독립 승인·반려는 요청 버전과 동일 명령을 제출한다', () => {
    const html = render('request/detail', {
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
      expect(html).toContain(`/requests/request-1/${action}`);
    expect(html).toContain('name="expectedVersion" value="4"');
    expect(html).toContain('name="commandId" value="decision-command"');
    expect(html).toContain('독립 승인');
  });
  it('정정은 원천 결속·KEEP/SET/CLEAR·체크 해제 마커를 보존한다', () => {
    const html = render('request/correction', {
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
    expect(html).toContain('action="/admin/requests/exceptions"');
    expect(html).toContain('name="sourceFingerprint" value="fingerprint"');
    expect(html).toContain('name="baseInputRevision" value="2"');
    expect(html).toContain('name="_presenceAsWork"');
    expect(html).toContain('name="_breaksConfirmed"');
    expect(html).toContain('step="0.001"');
    expect(html).toContain('CLEAR');
  });
  it('저장된 실제 휴게는 검증된 서울 구간으로 표시하고 손상된 원문을 숨긴다', () => {
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
    expect(render('request/detail', data)).toContain('12:00:00.123');
    data.detail.correction.actualBreaks = 'private-invalid-json';
    const html = render('request/detail', data);
    expect(html).toContain('확인 필요');
    expect(html).not.toContain('private-invalid-json');
  });
  it('기초·조정 원장은 정수 DAY와 버전 제출을 유지한다', () => {
    const base = render('request/balances', {
      employee,
      employees: [employee],
      buckets: [bucket],
      form: { employeeId: employee.id, commandId: 'opening-command', quantityDays: 5 },
    });
    expect(base).toContain('action="/admin/leave-balances/entries"');
    expect(base).toContain('step="1"');
    expect(base).toContain('min="0"');
    expect(base).toContain('/admin/requests/exceptions/new?employeeId=employee-1');
    const ledger = render('request/ledger', {
      bucketId: bucket.id,
      ledger: [
        {
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
    expect(ledger).toContain('RESTORE');
    expect(ledger).toContain('name="expectedVersion" value="6"');
    expect(ledger).toContain('value="-1"');
  });
  it('월 결과는 미확정과 부분합·경계 주를 구분하고 관리자 명령을 제한한다', () => {
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
    const html = render('worktime/month', data);
    expect(html).toContain('미확정');
    expect(html).toContain('8:00:00.000');
    expect(html).toContain('2026-10-04');
    expect(html).toContain('/worktime/results/result-1');
    expect(html).toContain('action="/admin/calculations"');
    expect(html).toContain('action="/admin/reports/exports"');
    expect(render('worktime/month', { ...data, admin: false })).not.toContain(
      '/admin/calculations',
    );
  });
  it('계산 상세는 저장 근거가 없으면 현재값으로 대체하지 않고 미확인으로 표시한다', () => {
    const html = render('worktime/result', {
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
    expect(html).toContain('저장된 근거가 없거나 일부를 확인할 수 없습니다');
    expect(html).toContain('/worktime/results/result-2');
    expect(html).toContain('저장 당시 주 기산점 검토 미확인');
    expect(html).toContain('미확정은 실제0시간과 다릅니다');
  });
  it('정책 검토는 두 별도 계약과 unchecked 마커를 보존한다', () => {
    const html = render('worktime/policies', {
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
    expect(html).toContain('action="/admin/work-assessments"');
    expect(html).toContain('name="_mondayWeekReviewed"');
    expect(html).toContain('name="_generalRuleReviewed"');
    expect(html).toContain('name="commandId" value="assessment-command"');
  });
  it('감사 페이지의 이전은 after·다음은 before를 사용한다', () => {
    const html = render('worktime/audit', {
      audits: [],
      page: { previousCursor: 'newer-id', nextCursor: 'older-id' },
    });
    expect(html).toContain('/admin/audit?after=newer-id');
    expect(html).toContain('/admin/audit?before=older-id');
    expect(html).toContain('감사 기록이 없습니다');
  });
  it('감사 상세는 서버의 안전한 업무 요약을 텍스트로 표시한다', () => {
    const html = render('worktime/audit-detail', {
      audit: {
        id: 'audit-1',
        action: 'APPROVE',
        beforeSummary: { status: 'PENDING' },
        afterSummary: { status: '<svg onload=alert(1)>' },
        reason: '<script>x</script>',
        at: '2026-10-26T00:00:00.123Z',
      },
    });
    expect(html).toContain('&lt;svg onload=alert(1)&gt;');
    expect(html).not.toContain('<svg');
    expect(html).toContain('요약은 전체 저장 원문의 재현이 아닙니다');
  });
  it('출퇴근 확인은 사실/검토 개정과 원천 지문·명령을 제출한다', () => {
    const html = render('clock/history', {
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
    expect(html).toContain('근무 일정 미배정');
    expect(html).toContain('action="/me/work-confirmations"');
    expect(html).toContain('name="expectedReviewRevision" value="3"');
    expect(html).toContain('name="sourceFingerprint" value="clock-fingerprint"');
    expect(html).toContain('09:00:00.123');
  });
});
