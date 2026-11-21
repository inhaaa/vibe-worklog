// @vitest-environment jsdom
import React from 'react';
import { cleanup, render, within } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { AnnualRequestPage } from './AnnualRequestPage';

afterEach(cleanup);

it('연간 요약 표의 각 값은 모바일에서도 열 이름과 표 의미를 유지한다', () => {
  const view = render(<AnnualRequestPage page="request/mine" reload={() => {}} csrf={{ token: 'synthetic', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' }} data={{
    buckets: [], compose: null,
    annual: {
      year: 2026, today: '2026-11-05', asOf: '2026-11-05T00:00:00Z',
      typeId: null, status: '', page: 0, authoredPage: 0, hasNext: false, authoredHasNext: false,
      coverage: 'REGISTERED_DATA_ONLY', usedDays: 3, plannedDays: 2, pendingDays: 1,
      currentAvailableDays: 9, registeredDays: 15, attributedBalanceDays: 9,
      unknownBucketCount: 0, attributionPartial: false, types: [], requests: [], authoredRequests: [],
      attributionBuckets: [{ bucketId: 'bucket-1', classificationStatus: 'CONFIRMED', entitlementYear: 2026,
        usableFrom: '2026-01-01', usableTo: '2026-12-31', balanceDays: 9, availableDays: 9,
        availability: 'CURRENT', basis: '합성 등록 근거' }],
      summaries: [{ typeId: 'type-1', kind: 'LEAVE_DAY', name: '연차', requestCount: 6,
        pendingCount: 1, approvedCount: 3, rejectedCount: 1, withdrawnCount: 1,
        usedDays: 3, plannedDays: 2, pendingDays: 1 }],
    },
  }} />);

  for (const name of ['연차 묶음·귀속 정보', '유형별 연간 신청']) {
    const table = view.getByRole('table', { name });
    const headers = within(table).getAllByRole('columnheader');
    const cells = within(table).getAllByRole('cell');
    expect(cells).toHaveLength(headers.length);
    cells.forEach((cell, index) => expect(cell.getAttribute('data-label')).toBe(headers[index].textContent));
    expect(headers.every(header => header.getAttribute('scope') === 'col')).toBe(true);
  }
  expect(within(view.getByRole('table', { name: '연차 묶음·귀속 정보' })).getByText('2026년')).toBeTruthy();
  expect(view.getByText('합성 등록 근거')).toBeTruthy();
  expect(within(view.getByRole('table', { name: '연차 묶음·귀속 정보' })).getAllByText('9일')).toHaveLength(2);
  expect([...view.container.querySelectorAll('.t-request-balance-basis dd')].map(d => d.textContent)).toEqual(['15일', '9일']);
  expect(within(view.getByRole('table', { name: '유형별 연간 신청' })).getByText('사용 3 / 예정 2 / 대기 1')).toBeTruthy();
});
