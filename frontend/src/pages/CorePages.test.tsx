// @vitest-environment jsdom
import type { ReactElement } from 'react';
import { cleanup, fireEvent, render, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
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
const renderMarkup = async (element: ReactElement) => {
  const view = render(element);
  await waitFor(() => {
    for (const sheet of view.container.querySelectorAll('.w-sheet'))
      expect(sheet.querySelector('[role="grid"][aria-label]')).not.toBeNull();
  });
  return view.container.innerHTML;
};
afterEach(cleanup);
describe('개인 홈과 일정', () => {
  it('미배정·미등록·미확인을 0시간이나 0일로 바꾸지 않는다', async () => {
    const html = await renderMarkup(
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
    const doc = new DOMParser().parseFromString(html, 'text/html');
    expect(doc.querySelector('input[name="eventKind"][value="IN"]')).not.toBeNull();
    expect(doc.querySelector('input[name="commandId"][value="out"]')).not.toBeNull();
    expect(html).toContain('&lt;사용자&gt;');
    expect(html).not.toContain('<사용자>');
  });
  it('선택한 월과 날짜를 그대로 보존하고 취소/대기 신청을 승인으로 바꾸지 않는다', async () => {
    const html = await renderMarkup(
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
  it('요약 줄·막대·오른쪽 패널은 이번 달 자료로 예정 근무·연차·검토 중·확인 필요와 선택한 주를 계산한다', () => {
    const work = { workday: true, startTime: '09:00', endTime: '18:00', breaks: [{ startTime: '12:00', endTime: '13:00' }] };
    const entry = (date: string, changes: Record<string, unknown> = {}, requests: unknown[] = []) => ({
      ...day, today: false, selected: date === '2026-11-10', requests, scheduleText: '09:00–18:00',
      snapshot: { ...snapshot, workDate: date, dayKind: 'WORKDAY', schedule: work, ...changes },
    });
    const days = [
      entry('2026-11-09'), entry('2026-11-10'),
      entry('2026-11-11', {}, [{ request: { id: 'leave', fromDate: '2026-11-11', toDate: '2026-11-11' }, kindLabel: '종일 연차', statusLabel: '승인 유효', tone: 'green', effectiveLeave: true }]),
      entry('2026-11-12', {}, [{ request: { id: 'wait', fromDate: '2026-11-12', toDate: '2026-11-12' }, kindLabel: '종일 연차', statusLabel: '검토 중', tone: 'amber', effectiveLeave: false }]),
      entry('2026-11-13', { schedule: null, dayKind: 'UNCONFIRMED' }),
      entry('2026-11-14', { dayKind: 'NON_WORKDAY', schedule: { ...work, workday: false } }),
      entry('2026-11-16'),
    ];
    const view = render(<CorePages {...props('workspace/schedule', { workspaceCalendar: {
      month: '2026-11', selectedDate: '2026-11-10', days, leadingBlanks: [], previousMonth: null, nextMonth: null, selectedDay: days[1] } })} />);
    const pairs = (root: Element) => [...root.querySelectorAll('dt')].map(term => [term.textContent, term.nextElementSibling?.textContent]);
    expect(pairs(view.container.querySelector('.wl-summary')!)).toEqual([['예정 근무', '4일 · 32:00'], ['승인 연차', '1일'], ['검토 중', '1건'], ['확인 필요', '1일']]);
    const panel = view.getByRole('complementary', { name: '주간 근무와 처리할 일' });
    expect(within(panel).getByText('11.09 (월) – 11.15 (일)')).toBeTruthy();
    expect(panel.querySelector('.wl-side-big')?.firstChild?.textContent).toBe('24:00');
    expect(pairs(panel.querySelector('.wl-side-facts')!)).toEqual([['예정 근무', '3일'], ['승인 연차', '1일'], ['비근로일·휴일', '1일'], ['확인 필요', '1일']]);
    expect(within(panel).getAllByRole('link').map(link => [link.textContent, link.getAttribute('href')])).toEqual([
      ['11.12 (목) 종일 연차', '/requests/wait'], ['11.13 (금) 근무 미배정', '/me/schedule?month=2026-11&date=2026-11-13'],
    ]);
    const bar = (date: string, selector: string) => view.container.querySelector(`a[href$="date=${date}"] ${selector}`);
    expect(bar('2026-11-11', '.t-day-event.leave')?.getAttribute('data-kind')).toBe('leave');
    expect(bar('2026-11-11', '.t-day-event.leave svg')).not.toBeNull();
    expect(bar('2026-11-12', '.t-day-event.warning')?.getAttribute('data-kind')).toBe('wait');
    expect(bar('2026-11-13', '.t-attendance-plan')?.getAttribute('data-kind')).toBe('unknown');
    expect(bar('2026-11-14', '.t-attendance-plan')?.getAttribute('data-kind')).toBe('off');
    expect(bar('2026-11-09', '.t-attendance-plan')?.getAttribute('data-kind')).toBe('work');
    expect(view.getByRole('link', { name: '근태 신청' }).getAttribute('href')).toBe('/me/requests/new?year=2026');
  });
  it('달력은 기존 7열·빈칸·주말·오늘·선택·미확인 표시와 날짜 접근성 계약을 유지한다', async () => {
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
    const markup = await renderMarkup(
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

  it('선택하지 않은 날짜의 우클릭과 더보기에서 같은 날짜와 유형의 신청 링크를 연다', () => {
    const previous = {
      ...day, today: false, selected: true,
      snapshot: { ...snapshot, workDate: '2026-10-25' },
    };
    const target = { ...day, selected: false };
    const view = render(<CorePages {...props('workspace/schedule', {
      workspaceCalendar: {
        month: '2026-10', selectedDate: '2026-10-25', selectedDay: previous,
        days: [previous, target], leadingBlanks: [], previousMonth: null, nextMonth: null,
      },
    })} />);
    const dateLink = view.getByRole('link', { name: /^2026-10-26 ·/ });
    const trigger = view.getByRole('button', { name: '2026-10-26 신청 메뉴' });
    const checkMenu = () => {
      const menu = within(view.getByRole('group', { name: '2026-10-26 신청 메뉴' }));
      for (const [name, kind] of [['종일 연차', 'LEAVE_DAY'], ['출퇴근·휴게 정정', 'ATTENDANCE_CORRECTION']]) {
        const url = new URL((menu.getByRole('link', { name }).getAttribute('href'))!, 'https://worklog.test');
        expect(url.pathname).toBe('/me/requests/new');
        expect(Object.fromEntries(url.searchParams)).toEqual({ year: '2026', kind, date: '2026-10-26' });
      }
      expect(view.getByRole('region', { name: '선택한 날짜 상세' }).querySelector('time')?.dateTime).toBe('2026-10-25');
      return menu;
    };

    fireEvent.contextMenu(dateLink);
    const first = checkMenu().getByRole('link', { name: '종일 연차' });
    expect(document.activeElement).toBe(first);
    fireEvent.keyDown(first, { key: 'Escape' });
    expect(view.queryByRole('group', { name: '2026-10-26 신청 메뉴' })).toBeNull();
    expect(document.activeElement).toBe(dateLink);
    fireEvent.click(trigger);
    checkMenu();
    fireEvent.pointerDown(document.body);
    expect(view.queryByRole('group', { name: '2026-10-26 신청 메뉴' })).toBeNull();
    fireEvent.keyDown(dateLink, { key: 'F10', shiftKey: true });
    checkMenu();
  });

  it('최근 신청을 안정된 신청 ID의 조회 시트에 표시하고 상세 링크와 상태를 보존한다', async () => {
    const view = render(<CorePages {...props('today', {
      day: snapshot,
      workspaceHome: {
        employee: { employeeName: '직원', companyName: '회사' }, today: day, week: [],
        availableLeaveDays: null, pendingRequests: 1,
        recentRequests: [{ request: { id: 'request-1', fromDate: '2026-10-26', toDate: '2026-10-27' }, kindLabel: '종일 연차', statusLabel: '대기', tone: 'amber', effectiveLeave: false }],
      }, inCommand: 'in', outCommand: 'out', confirmationForm: {},
    })} />);
    expect(await view.findByRole('grid', { name: '최근 내 신청' })).toBeTruthy();
    expect((await view.findByRole('link', { name: '종일 연차' })).getAttribute('href')).toBe('/requests/request-1');
    const row = view.container.querySelector('[row-id="request-1"]')!;
    expect(row.textContent).toContain('2026-10-26 ~ 2026-10-27');
    expect(row.textContent).toContain('대기');
  });
});
