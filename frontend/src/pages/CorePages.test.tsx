// @vitest-environment jsdom
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { CorePages } from './CorePages';
import type { PageProps } from '../types';

const snapshot = {
  employeeId: 'e',
  workDate: '2026-10-26',
  events: [],
  effectiveEvents: [],
  schedule: null,
  inputRevision: 0,
  reviewRevision: 0,
  confirmation: null,
  dayKind: 'UNKNOWN',
};
const day = {
  snapshot,
  requests: [],
  today: true,
  selected: true,
  timeline: [],
  scheduleText: '근무 일정 미배정',
  calendarText: '캘린더 미확인',
  confirmationText: '실제 근로 미확인',
  inTime: '미기록',
  outTime: '미기록',
};
const props = (page: string, data: Record<string, unknown>): PageProps => ({
  page,
  data,
  csrf: { token: 't', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' },
  reload: () => {},
});
describe('개인 홈과 일정', () => {
  it('미배정·미등록·미확인을 0시간이나 0일로 바꾸지 않는다', () => {
    const html = renderToStaticMarkup(
      <CorePages
        {...props('today', {
          day: snapshot,
          workspaceHome: {
            employee: { employeeName: '<사용자>', companyName: '회사' },
            today: day,
            week: [],
            availableLeaveDays: null,
            pendingRequests: 0,
            recentRequests: [],
          },
          inCommand: 'in',
          outCommand: 'out',
          confirmationForm: {},
        })}
      />,
    );
    expect(html).toContain('근무 일정 미배정');
    expect(html).toContain('미등록');
    expect(html).toContain('실제 근로 미확인');
    expect(html).toContain('name="eventKind" value="IN"');
    expect(html).toContain('name="commandId" value="out"');
    expect(html).toContain('&lt;사용자&gt;');
    expect(html).not.toContain('<사용자>');
  });
  it('선택한 월과 날짜를 그대로 보존하고 취소/대기 신청을 승인으로 바꾸지 않는다', () => {
    const html = renderToStaticMarkup(
      <CorePages
        {...props('workspace/schedule', {
          workspaceCalendar: {
            month: '2026-10',
            selectedDate: '2026-10-26',
            days: [
              {
                ...day,
                requests: [
                  {
                    request: { id: 'r' },
                    kindLabel: '연차',
                    statusLabel: '취소 검토 중',
                    tone: 'amber',
                    effectiveLeave: true,
                  },
                ],
              },
            ],
            leadingBlanks: [],
            previousMonth: '2026-09',
            nextMonth: '2026-11',
            selectedDay: day,
          },
          selectedDay: snapshot,
        })}
      />,
    );
    expect(html).toContain('2026-10-26');
    expect(html).toContain('취소 검토 중');
    expect(html).toContain('month=2026-11');
    expect(html).toContain('/me/attendance?date=2026-10-26');
  });
  it('달력은 기존 7열·빈칸·주말·오늘·선택·미확인 표시와 날짜 접근성 계약을 유지한다', () => {
    const pending = {
      request: { id: 'pending' },
      kindLabel: '정정',
      statusLabel: '검토 중',
      tone: 'amber',
      effectiveLeave: false,
    };
    const saturday = {
      ...day,
      today: true,
      selected: false,
      snapshot: { ...snapshot, workDate: '2026-10-24', dayKind: 'UNCONFIRMED' },
      requests: [pending],
    };
    const sunday = {
      ...day,
      today: false,
      selected: true,
      snapshot: { ...snapshot, workDate: '2026-10-25', dayKind: 'HOLIDAY' },
      requests: [
        {
          ...pending,
          request: { id: 'leave' },
          kindLabel: '연차',
          statusLabel: '승인',
          tone: 'green',
          effectiveLeave: true,
        },
      ],
    };
    const markup = renderToStaticMarkup(
      <CorePages
        {...props('workspace/schedule', {
          workspaceCalendar: {
            month: '2026-10',
            selectedDate: '2026-10-25',
            days: [saturday, sunday],
            leadingBlanks: [0, 1, 2],
            previousMonth: '2026-09',
            nextMonth: '2026-11',
            selectedDay: sunday,
          },
          selectedDay: sunday.snapshot,
        })}
      />,
    );
    const doc = new DOMParser().parseFromString(markup, 'text/html');
    const grid = doc.querySelector('.t-month-grid');
    expect(grid).not.toBeNull();
    expect(grid!.querySelectorAll('span.t-day.outside[aria-hidden="true"]')).toHaveLength(3);
    const today = grid!.querySelector<HTMLAnchorElement>('a[href$="date=2026-10-24"]')!;
    const selected = grid!.querySelector<HTMLAnchorElement>('a[href$="date=2026-10-25"]')!;
    expect([...today.classList]).toEqual(expect.arrayContaining(['t-day', 'today', 'weekend']));
    expect([...selected.classList]).toEqual(
      expect.arrayContaining(['t-day', 'selected', 'weekend']),
    );
    expect(today.getAttribute('aria-current')).toBe('date');
    expect(selected.hasAttribute('aria-current')).toBe(false);
    expect(today.getAttribute('aria-label')).toBe('2026-10-24 · 근무 일정 미배정');
    expect(selected.getAttribute('aria-label')).toBe('2026-10-25 · 근무 일정 미배정 · 선택한 날짜');
    expect(today.querySelector('time.t-day-number')?.getAttribute('datetime')).toBe('2026-10-24');
    expect(today.querySelector('time.t-day-number')?.textContent).toBe('24');
    expect(
      [...today.querySelectorAll('.t-day-event.warning')].map((item) => item.textContent),
    ).toEqual(['미확인', '정정 · 검토 중']);
    expect(selected.querySelector('.t-day-event.leave')?.textContent).toBe('연차 · 승인');
    expect(
      [...selected.querySelectorAll('.t-day-event')].map((item) => item.textContent),
    ).toContain('확인된 휴일');
    expect(doc.querySelector('.t-day-detail h2 time')?.getAttribute('datetime')).toBe('2026-10-25');
  });
});
