// @vitest-environment jsdom
import React from 'react';
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { WorkflowPages } from './WorkflowPages';

afterEach(cleanup);
const csrf = { token: 'test', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const show = (page: string, data: Record<string, unknown>) =>
  render(<WorkflowPages page={page} data={data} csrf={csrf} reload={() => {}} />);

function pair(region: HTMLElement, label: string, value: string) {
  const term = within(region).getByText(label, { selector: 'dt' });
  expect(term.nextElementSibling?.tagName).toBe('DD');
  expect(term.nextElementSibling?.textContent).toBe(value);
}

describe('조회 상세의 이름 있는 정보 영역', () => {
  it('계산 근거의 항목·값 관계와 미확정 안내를 같은 상세 영역 안에 보존한다', () => {
    show('worktime/result', {
      month: '2026-10', admin: false, times: { '확인 실근로': '미확정' },
      detail: {
        day: { id: 'result-1', employeeId: 'employee-1', workDate: '2026-10-26', resultRevision: 1,
          ruleVersion: 'CALC-1', current: false, result: { status: 'REVIEW_REQUIRED', inputRevision: 2,
            reviewRevision: 3, leaveDays: 0, reasons: ['ACTUAL_WORK_UNCONFIRMED'] } },
        completeEvidence: false, latestVersion: false, latestResultId: 'result-2', clock: null,
        run: null, policy: null, assessment: null, employment: null, leaveEffects: [], pendingCorrections: [],
      },
    });
    const region = screen.getByRole('region', { name: '계산 근거 상세' });
    pair(region, '결과 ID', 'result-1');
    pair(region, '확인 실근로', '미확정');
    expect(within(region).getByRole('status').textContent).toContain('현재 원천으로 대신 채우지 않았습니다');
    expect(within(region).getByRole('link', { name: '최신 계산 결과' }).getAttribute('href')).toBe('/worktime/results/result-2');
    expect(region.querySelector('form')).toBeNull();
  });

  it('감사 상세의 추적 항목과 변경 전후 요약을 분리하고 자유 문자열은 텍스트로 유지한다', () => {
    show('worktime/audit-detail', { audit: { id: 'audit-1', action: 'APPROVE',
      targetKind: 'REQUEST', targetId: 'request-1', actorAccountId: 'actor-1', commandId: 'command-1',
      requestTraceId: 'trace-1', at: '2026-10-26T00:00:00.123Z', reason: '<script>unsafe</script>',
      beforeSummary: { status: 'PENDING' }, afterSummary: { status: 'APPROVED' } } });
    const region = screen.getByRole('region', { name: '감사 상세' });
    pair(region, '감사 ID', 'audit-1');
    pair(region, '처리 사유', '<script>unsafe</script>');
    const before = within(region).getByRole('heading', { name: '변경 전 업무 요약' }).closest('section')!;
    const after = within(region).getByRole('heading', { name: '변경 후 업무 요약' }).closest('section')!;
    pair(before, 'status', 'PENDING');
    pair(after, 'status', 'APPROVED');
    expect(region.querySelector('script, form')).toBeNull();
  });
});
