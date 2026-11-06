// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { WorkspaceHome } from './WorkspaceHome';

const day = {
  snapshot: { workDate: '2026-11-06' },
  today: true,
  scheduleText: '09:00–18:00',
  calendarText: '확인된 근로일',
  confirmationText: '실제 근로 미확인',
  inTime: '08:56',
  outTime: '미기록',
  timeline: [
    { time: '08:56:00', title: '현재 반영 출근', kind: 'recorded' },
    { time: '09:00:00', title: '예정 근무 시작', kind: 'planned' },
  ],
  requests: [],
};
const home = {
  employee: { employeeName: '김하늘', companyName: '가상 회사' },
  today: day,
  week: [day],
  availableLeaveDays: 0,
  pendingRequests: 1,
  recentRequests: [{
    request: { id: 'request-1', fromDate: '2026-11-09', toDate: '2026-11-09' },
    kindLabel: '종일 연차', statusLabel: '검토 중', tone: 'amber', effectiveLeave: false,
  }],
};

afterEach(cleanup);

describe('업무 홈', () => {
  it('서버가 제공한 오늘 일정, 출퇴근 및 내 신청 상태를 연결한다', () => {
    render(<WorkspaceHome data={{ workspaceHome: home }} />);

    expect(screen.getByRole('heading', { level: 1, name: '김하늘님, 안녕하세요' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '근태 신청' }).getAttribute('href')).toBe('/me/requests/new');
    expect(screen.getByRole('link', { name: '출퇴근 기록' }).getAttribute('href')).toBe('/me/attendance');
    expect(screen.getByText('0일')).toBeTruthy();
    expect(screen.getByText('1건')).toBeTruthy();
    expect(screen.getByText('실제 근로 미확인')).toBeTruthy();
    const today = screen.getByRole('region', { name: '오늘 근무' });
    const timeline = within(today).getByRole('list');
    expect(within(timeline).getByText('현재 반영 출근')).toBeTruthy();
    expect(within(timeline).getByText('예정 근무 시작')).toBeTruthy();
    expect(screen.getByRole('link', { name: /종일 연차.*검토 중/ }).getAttribute('href')).toBe('/requests/request-1');
    const week = screen.getByRole('region', { name: '이번 주 일정' });
    expect(within(week).getByRole('link', { current: 'date' }).getAttribute('href'))
      .toBe('/me/schedule?month=2026-11&date=2026-11-06');
  });

  it('미등록 연차와 미기록 상태를 실제 값 0과 구분한다', () => {
    render(<WorkspaceHome data={{ workspaceHome: {
      ...home, today: { ...day, timeline: [], inTime: '미기록' }, availableLeaveDays: null, recentRequests: [],
    } }} />);

    expect(screen.getByText('미등록')).toBeTruthy();
    expect(screen.queryByText('0일')).toBeNull();
    expect(screen.getByText('표시할 일정이나 출퇴근 기록이 없습니다.')).toBeTruthy();
    expect(screen.getByText('아직 본인 대상 신청이 없습니다.')).toBeTruthy();
  });
});
