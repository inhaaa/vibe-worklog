import type { PageProps } from '../types';
import type { ReactNode } from 'react';
import { dayClass, formatMinutes, pendingRequests, tally, weekOf } from './scheduleSummary';
import { useEffect, useId, useRef, useState } from 'react';
import { AttendanceRecord } from './WorkflowPages';
import type { DaySnapshot } from './WorkflowTypes';
import { Form, fmtDate, fmtTime, fmtDateTime } from '../ui';
import { WorklogSheet, sheetColumn } from '../components/WorklogSheet';
import { PageHeader } from '../components/PageHeader';
import './ScheduleCalendar.css';

interface RequestItem {
  request: { id: string; fromDate: string; toDate: string };
  kindLabel: string;
  statusLabel: string;
  tone: string;
  effectiveLeave: boolean;
}
interface WorkspaceDay {
  snapshot: DaySnapshot;
  requests: RequestItem[];
  today: boolean;
  selected: boolean;
  timeline: { time: string; title: string; detail: string; kind: string }[];
  scheduleText: string;
  calendarText: string;
  confirmationText: string;
  inTime: string;
  outTime: string;
}
interface Home {
  employee: { employeeName: string; companyName: string };
  today: WorkspaceDay;
  week: WorkspaceDay[];
  availableLeaveDays: number | null;
  pendingRequests: number;
  recentRequests: RequestItem[];
}
interface Calendar {
  month: string;
  selectedDate: string;
  days: WorkspaceDay[];
  leadingBlanks: number[];
  previousMonth: string | null;
  nextMonth: string | null;
  selectedDay: WorkspaceDay;
  assignment?: {
    group: boolean; code: string; name: string; workCode?: string; workName?: string;
    beforeStart?: string; beforeEnd?: string; afterStart?: string; afterEnd?: string;
    overtimeBreaks: {start: string; end: string}[]; overtimeMinutes: number;
  };
}
const weekdays = ['월', '화', '수', '목', '금', '토', '일'];
export function StatusPanel() {
  return (
    <section id="setup-status" className="panel" aria-live="polite">
      <h2>개발 환경 구축 상태</h2>
      <p className="badge">기본 인증·본인 조회 연결</p>
      <p>
        허용 IP 출퇴근·근로 확인, 연차·신청·독립 승인, 일·주·월 집계와 검토용 Excel을 연결했습니다.
      </p>
      <p>예정 휴게·체류시간은 실제 근로 확정과 구분합니다.</p>
    </section>
  );
}

function Today({ data, reload }: PageProps) {
  const home = data.workspaceHome as Home;
  const day = home.today.snapshot;
  const hasPair = ['IN', 'OUT'].every((kind) =>
    (day.effectiveEvents ?? []).some((event) => event.kind === kind),
  );
  return (
    <div className="t-home-flow">
      <header className="t-flow-header">
        <div className="t-flow-date">
          <strong>{day.workDate.slice(-2)}</strong>
          <div>
            <p className="t-context">오늘 · 서울</p>
            <time>{fmtDate(day.workDate)}</time>
          </div>
        </div>
        <div className="t-flow-greeting">

          <h1>{home.employee.employeeName}님의 하루</h1>
          <p className="t-muted">일정부터 기록까지, 오늘의 흐름을 한눈에 확인하세요.</p>
        </div>
        <a className="t-btn secondary" href="/me/schedule" aria-label="내 근무 일정 →">
          내 근무 일정
        </a>
      </header>
      <div className="t-flow-layout">
        <section className="t-card t-flow-agenda">
          <header className="t-cardhead">
            <div>

              <h2>하루의 흐름</h2>
            </div>
            <div className="t-flow-legend">
              <span>예정</span>
              <span className="recorded">현재 반영</span>
            </div>
          </header>
          <div className="t-flow-schedule">
            <div>
              <strong>{home.today.scheduleText}</strong>
              <p className="t-subtle">{home.today.calendarText}</p>
            </div>
          </div>
          {home.today.timeline.length ? (
            <ol className="t-flow-timeline">
              {home.today.timeline.map((entry, index) => (
                <li className={`t-flow-entry ${entry.kind}`} key={index}>
                  <time className="t-flow-time">{fmtTime(entry.time)}</time>
                  <span className="t-flow-node" aria-hidden="true" />
                  <div className="t-flow-entry-copy">
                    <strong>{entry.title}</strong>
                    <p>{entry.detail}</p>
                  </div>
                </li>
              ))}
            </ol>
          ) : (
            <div className="t-flow-empty">
              <h3>오늘의 기록을 기다리고 있어요</h3>
              <p>일정이 배정되거나 출퇴근이 기록되면 시간순으로 이곳에 모아 보여드려요.</p>
            </div>
          )}
          <footer className="t-flow-footnote">
            <p>
              예정 일정과 현재 반영 기록을 구분해 표시합니다. 실제 근로시간은 별도 확인과 계산이
              필요합니다.
            </p>
            <a href="#home-record-comparison">원천·반영 비교</a>
          </footer>
        </section>
        <div className="t-flow-side">
          <section className="t-card t-flow-clock">
            <h2>오늘 출퇴근</h2>
            <span className={`t-tag ${day.confirmation ? 'green' : 'amber'}`}>
              {home.today.confirmationText}
            </span>
            <dl className="t-flow-clock-times">
              <div>
                <dt>현재 반영 출근</dt>
                <dd>{home.today.inTime}</dd>
              </div>
              <div>
                <dt>현재 반영 퇴근</dt>
                <dd>{home.today.outTime}</dd>
              </div>
            </dl>
            <div className="actions">
              {(['IN', 'OUT'] as const).map((kind) => (
                <Form key={kind} action="/me/attendance/events">
                  <input type="hidden" name="eventKind" value={kind} />
                  <input
                    type="hidden"
                    name="commandId"
                    value={String(data[kind === 'IN' ? 'inCommand' : 'outCommand'])}
                  />
                  <button className={`t-btn ${kind === 'IN' ? 'primary' : 'secondary'}`}>
                    {kind === 'IN' ? '출근 기록' : '퇴근 기록'}
                  </button>
                </Form>
              ))}
            </div>
            <p className="t-subtle">허용 네트워크에서 서버 시각으로 원천을 저장합니다.</p>
            <a href="/me/attendance">날짜별 기록 보기</a>
          </section>
          <section className="t-card t-flow-tasks">
            <h2>확인할 일</h2>
            <ul className="t-flow-task-list">
              {!day.schedule && (
                <li>
                  <a href="/me/schedule">근무 일정 미배정 · 배정은 담당자에게 문의</a>
                </li>
              )}
              {home.availableLeaveDays == null && (
                <li>
                  <a href="/me/requests">기초 연차 미등록 · 등록은 담당자에게 문의</a>
                </li>
              )}
              {!day.confirmation && hasPair && (
                <li>
                  <a href="#home-work-confirmation">실제 근로·휴게 확인</a>
                </li>
              )}
              <li>
                <a href="/me/requests">내 신청 검토 중 {home.pendingRequests}건</a>
              </li>
            </ul>
            <div className="t-flow-leave">
              <span>사용 가능 연차</span>
              <strong>
                {home.availableLeaveDays == null ? '미등록' : `${home.availableLeaveDays}일`}
              </strong>
            </div>
            <p className="t-subtle">현재 사용 기간의 원장 기준 · 대기 신청은 차감 전</p>
          </section>
        </div>
      </div>
      <section className="t-card t-flow-week">
        <header className="t-cardhead">
          <h2>이번 주 일정</h2>
          <a href="/me/schedule">전체 일정</a>
        </header>
        <div className="t-flow-days">
          {home.week.map((entry, index) => (
            <a
              key={entry.snapshot.workDate}
              className={`t-flow-day ${entry.today ? 'today' : ''}`}
              aria-current={entry.today ? 'date' : undefined}
              href={`/me/schedule?month=${entry.snapshot.workDate.slice(0, 7)}&date=${entry.snapshot.workDate}`}
            >
              <span>{weekdays[index]}</span>
              <time>{entry.snapshot.workDate.slice(5).replace('-', '.')}</time>
              {entry.today && <span>오늘</span>}
              <strong>{entry.scheduleText}</strong>
              <small>{entry.calendarText}</small>
              {entry.requests.map((item) => (
                <small key={item.request.id}>
                  {item.kindLabel} · {item.statusLabel}
                </small>
              ))}
            </a>
          ))}
        </div>
      </section>
      <section className="t-card">
        <header className="t-cardhead">
          <h2>최근 내 신청</h2>
          <a href="/me/requests">신청 전체 보기</a>
        </header>
        {home.recentRequests.length ? (
          <>
            <p className="t-subtle">본인 대상 신청 중 최근 최대 3건</p>
            <WorklogSheet
              label="최근 내 신청"
              rows={home.recentRequests}
              getRowId={(item) => item.request.id}
              scope="all"
              columns={[
                sheetColumn<RequestItem>(
                  { colId: 'kind', headerName: '신청 종류', valueGetter: ({ data }) => data?.kindLabel, minWidth: 160, pinned: 'left' },
                  (item) => <a href={`/requests/${item.request.id}`}>{item.kindLabel}</a>,
                ),
                { colId: 'period', headerName: '대상 날짜', valueGetter: ({ data }) => data && `${fmtDate(data.request.fromDate)} ~ ${fmtDate(data.request.toDate)}`, minWidth: 260 },
                sheetColumn<RequestItem>(
                  { colId: 'status', headerName: '상태', valueGetter: ({ data }) => data?.statusLabel, minWidth: 130 },
                  (item) => <span className={`t-tag ${item.tone}`}>{item.statusLabel}</span>,
                ),
              ]}
            />
          </>
        ) : (
          <p>아직 본인 대상 신청이 없습니다.</p>
        )}
      </section>
      <details className="t-card t-flow-comparison" id="home-record-comparison">
        <summary>원천 기록과 현재 반영 기록 비교</summary>
        <p>원천 출퇴근은 보존하며 승인 정정의 현재 반영 기록과 구분합니다. 서울 시각 기준입니다.</p>
        <div className="t-flow-compare-grid">
          {[
            ['원천 출퇴근', day.events],
            ['현재 반영된 기록', day.effectiveEvents ?? []],
          ].map(([title, events]) => (
            <div key={String(title)}>
              <h3>{String(title)}</h3>
              {(events as DaySnapshot['events']).length ? (
                <ul>
                  {(events as DaySnapshot['events']).map((event) => (
                    <li key={event.id}>
                      {event.kind === 'IN' ? '출근' : '퇴근'}{' '}
                      <time>{fmtDateTime(event.occurredAt)}</time>
                    </li>
                  ))}
                </ul>
              ) : (
                <p>기록이 없습니다.</p>
              )}
            </div>
          ))}
        </div>
      </details>
      <details className="t-card" id="home-work-confirmation">
        <summary>원천 상세와 실제 근로·휴게 확인</summary>
        <AttendanceRecord data={data} />
      </details>
      <details className="t-card">
        <summary>환경 정보</summary>
        <StatusPanel />
        <button type="button" className="t-btn secondary" onClick={reload}>
          상태 새로 고침
        </button>
      </details>
    </div>
  );
}

function seoulCalendarToday() {
  const parts = new Intl.DateTimeFormat('en', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date());
  return ['year', 'month', 'day'].map(type => parts.find(part => part.type === type)?.value).join('-');
}

const calendarRequestKinds = [
  { kind: 'LEAVE_DAY', title: '종일 연차', icon: 'M4 12h16m-8-8v16' },
  { kind: 'ATTENDANCE_CORRECTION', title: '출퇴근·휴게 정정', icon: 'M12 6v6l4 2M20 12a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z' },
] as const;

function calendarRequestRestriction(kind: typeof calendarRequestKinds[number]['kind'], date: string, today: string) {
  const year = Number(date.slice(0, 4));
  if (year < 1900 || year > 9998) return '신청은 1900~9998년 날짜로 할 수 있습니다.';
  if (kind === 'LEAVE_DAY' && date < today) return '지난 날짜에는 연차를 신청할 수 없습니다.';
  if (kind === 'ATTENDANCE_CORRECTION' && date > today) return '미래 날짜는 정정할 수 없습니다.';
  return null;
}

const barIcons = {
  work: 'M12 7v5l3 2M20 12a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z',
  off: 'M5 21V4m0 0h11l-2 4 2 4H5',
  leave: 'm5 12 4 4 10-10',
  wait: 'M7 3h10M7 21h10M8 3c0 5 8 6 8 9s-8 4-8 9M16 3c0 5-8 6-8 9',
} as const;
type BarKind = keyof typeof barIcons | 'unknown' | 'other';
/** 캘린더 막대: 종류별 색·아이콘·문구(설계 7.11). 클래스와 문구는 기존 계약을 그대로 쓴다. */
function Bar({ kind, className = '', children }: { kind: BarKind; className?: string; children: ReactNode }) {
  const icon = kind in barIcons ? barIcons[kind as keyof typeof barIcons] : null;
  return <span className={`t-day-event ${className}`.trim()} data-kind={kind}>
    {icon && <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d={icon} /></svg>}
    {children}
  </span>;
}
const shortDate = (date: string) =>
  `${date.slice(5, 7)}.${date.slice(8, 10)} (${weekdays[(new Date(`${date}T00:00:00Z`).getUTCDay() + 6) % 7]})`;

function Schedule({ data }: PageProps) {
  const calendar = data.workspaceCalendar as Calendar;
  const selected = calendar.selectedDay;
  const [year, month] = calendar.month.split('-');
  const menuId = useId();
  const [request, setRequest] = useState<{ date: string; detail: boolean } | null>(null);
  const requestDate = request?.date;
  const menu = useRef<HTMLDivElement>(null);
  const opener = useRef<HTMLElement>(null);
  const today = calendar.days.find(entry => entry.today)?.snapshot.workDate ?? seoulCalendarToday();
  const sideId = useId();
  const summary = tally(calendar.days);
  const pending = pendingRequests(calendar.days);
  const week = weekOf(calendar.days, calendar.selectedDate);
  const weekTally = tally(week.days);
  const todo = [
    ...pending.map(({ item, date }) => ({ key: `request-${item.request.id}`, date, label: item.kindLabel, state: item.statusLabel, tone: 'amber', href: `/requests/${item.request.id}` })),
    ...calendar.days.filter(entry => dayClass(entry) === 'unknown').map(entry => ({
      key: `day-${entry.snapshot.workDate}`, date: entry.snapshot.workDate, label: entry.snapshot.schedule ? '캘린더 미확인' : '근무 미배정',
      state: '담당자 확인', tone: '', href: `/me/schedule?month=${calendar.month}&date=${entry.snapshot.workDate}`,
    })),
  ].sort((a, b) => a.date.localeCompare(b.date));
  const planKind = (entry: WorkspaceDay): BarKind => {
    const schedule = entry.snapshot.schedule;
    if (!schedule) return 'unknown';
    return !schedule.workday || ['HOLIDAY', 'NON_WORKDAY'].includes(entry.snapshot.dayKind) ? 'off' : 'work';
  };

  function openMenu(date: string, trigger: HTMLElement, detail = false) {
    opener.current = trigger;
    setRequest({ date, detail });
  }

  function closeMenu(restoreFocus: boolean) {
    if (restoreFocus) opener.current?.focus({ preventScroll: true });
    setRequest(null);
  }

  useEffect(() => {
    if (!request) return;
    (menu.current?.querySelector('a') ?? menu.current?.querySelector('button'))?.focus({ preventScroll: true });
    function outside(event: PointerEvent) {
      const target = event.target as Node;
      if (!menu.current?.contains(target) && !opener.current?.contains(target)) setRequest(null);
    }
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [request]);

  function requestMenu(date: string) {
    const year = date.slice(0, 4);
    return <div ref={menu} id={menuId} className="t-attendance-request-menu" role="group"
      aria-label={`${date} 신청 메뉴`} onKeyDown={event => {
        if (event.key === 'Escape') {
          event.preventDefault();
          closeMenu(true);
        }
      }} onBlur={event => {
        if (event.relatedTarget && event.relatedTarget !== opener.current && !event.currentTarget.contains(event.relatedTarget)) closeMenu(false);
      }}>
      <header>
        <div><span>날짜별 신청</span><time dateTime={date}>{fmtDate(date)}</time></div>
        <button type="button" className="t-icon-button" aria-label="신청 메뉴 닫기" onClick={() => closeMenu(true)}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg>
        </button>
      </header>
      {calendarRequestKinds.map(({ kind, title, icon }) => {
        const blocked = calendarRequestRestriction(kind, date, today);
        const label = <>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d={icon} /></svg>
          <span>{title}{blocked && <small>{blocked}</small>}</span>
        </>;
        return blocked
          ? <span key={kind} className="t-attendance-request-option" aria-disabled="true">{label}</span>
          : <a key={kind} className="t-attendance-request-option"
            href={`/me/requests/new?${new URLSearchParams({ year, kind, date })}`}
            onClick={event => {
              if (!event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) closeMenu(true);
            }}>{label}</a>;
      })}
    </div>;
  }

  return (
    <>
      <PageHeader title="근태 캘린더" help="배정된 근무와 신청 상태를 확인하고 날짜에서 바로 신청하세요." actions={<>
        <Form method="get" action="/me/schedule" className="wl-month-jump">
          <label className="t-sr-only" htmlFor="workspace-month">조회 월</label>
          <input className="t-input" id="workspace-month" type="month" name="month"
            defaultValue={calendar.month} min="0001-01" max="9999-12" required />
          <button className="t-btn" type="submit">월 조회</button>
        </Form>
        <a className="t-btn primary" href={`/me/requests/new?${new URLSearchParams({ year })}`}>근태 신청</a>
      </>} />
      <dl className="wl-summary" aria-label={`${year}년 ${Number(month)}월 요약`}>
        <div><dt>예정 근무</dt><dd>{summary.workDays}일 · {formatMinutes(summary.workMinutes)}</dd></div>
        <div><dt>승인 연차</dt><dd>{summary.leaveDays}일</dd></div>
        <div data-tone={pending.length ? 'warn' : undefined}><dt>검토 중</dt><dd>{pending.length}건</dd></div>
        <div data-tone={summary.unknownDays ? 'danger' : undefined}><dt>확인 필요</dt><dd>{summary.unknownDays}일</dd></div>
      </dl>
      <div className="t-attendance-calendar">
        <section className="t-card t-calendar-card t-attendance-board" aria-label="월별 근무 일정">
          <div className="t-calendar-toolbar">
            <h2 className="t-attendance-month"><span>{year}년</span>{' '}{Number(month)}월</h2>
            <div className="t-month-navigation" aria-label="표시 월 이동">
              {calendar.previousMonth && (
                <a className="t-icon-button" href={`/me/schedule?month=${calendar.previousMonth}`} aria-label="이전 달">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="m14 6-6 6 6 6" /></svg>
                </a>
              )}
              <a className="t-btn secondary" href="/me/schedule">오늘</a>
              {calendar.nextMonth && (
                <a className="t-icon-button" href={`/me/schedule?month=${calendar.nextMonth}`} aria-label="다음 달">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="m10 6 6 6-6 6" /></svg>
                </a>
              )}
            </div>
            <div className="t-calendar-legend">
              {([['work', '예정 근무'], ['off', '비근로일·휴일'], ['leave', '승인 연차'], ['wait', '검토 중'], ['unknown', '미배정·미확인']] as const)
                .map(([kind, label]) => <span key={kind}><i className="wl-legend-swatch" data-kind={kind} aria-hidden="true" />{label}</span>)}
              <span className="t-attendance-hint">날짜 우클릭 또는 ⋯로 신청</span>
            </div>
          </div>
          <div className="t-weekdays" aria-hidden="true">
            {weekdays.map((day) => (
              <span key={day}>{day}</span>
            ))}
          </div>
          <div className="t-month-grid">
            {calendar.leadingBlanks.map((blank, index) => (
              <span className="t-day outside" key={`blank-${index}`} aria-hidden="true" />
            ))}
            {calendar.days.map((entry) => {
              const date = entry.snapshot.workDate;
              const weekday = new Date(`${date}T00:00:00Z`).getUTCDay();
              const weekend = weekday === 0 || weekday === 6;
              return (
                <div className="t-attendance-slot" key={date} onContextMenu={event => {
                  if (menu.current?.contains(event.target as Node)) return;
                  event.preventDefault();
                  openMenu(date, event.currentTarget.querySelector<HTMLAnchorElement>('.t-day')!);
                }}>
                  <a
                    className={['t-day', entry.selected && 'selected', entry.today && 'today', weekend && 'weekend'].filter(Boolean).join(' ')}
                    data-weekend={weekday === 6 ? 'sat' : weekday === 0 ? 'sun' : undefined}
                    href={`/me/schedule?month=${calendar.month}&date=${date}`}
                    aria-current={entry.today ? 'date' : undefined}
                    aria-label={`${date} · ${entry.scheduleText}${entry.selected ? ' · 선택한 날짜' : ''}`}
                    onKeyDown={event => {
                      if (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) {
                        event.preventDefault();
                        openMenu(date, event.currentTarget);
                      }
                    }}
                  >
                    <time className="t-day-number" dateTime={date}>{Number(date.slice(-2))}</time>
                    <Bar kind={planKind(entry)} className="t-attendance-plan">
                      {entry.snapshot.schedule == null ? '미배정' : entry.scheduleText}
                    </Bar>
                    {entry.snapshot.dayKind === 'UNCONFIRMED' && <Bar kind="unknown" className="warning">미확인</Bar>}
                    {entry.snapshot.dayKind === 'HOLIDAY' && <Bar kind="off">확인된 휴일</Bar>}
                    {entry.requests.slice(0, 2).map(item => (
                      <Bar key={item.request.id} kind={item.effectiveLeave ? 'leave' : item.tone === 'amber' ? 'wait' : 'other'}
                        className={item.effectiveLeave ? 'leave' : item.tone === 'amber' ? 'warning' : ''}>
                        {item.kindLabel} · {item.statusLabel}
                      </Bar>
                    ))}
                    {entry.requests.length > 2 && <Bar kind="other">+{entry.requests.length - 2}건, 날짜 상세</Bar>}
                  </a>
                  <button type="button" className="t-icon-button t-attendance-more"
                    aria-label={`${date} 신청 메뉴`} aria-expanded={requestDate === date && !request?.detail}
                    aria-controls={requestDate === date && !request?.detail ? menuId : undefined}
                    onClick={event => {
                      if (requestDate === date && !request?.detail) closeMenu(true);
                      else openMenu(date, event.currentTarget);
                    }}>
                    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                      <circle cx="5" cy="12" r="1.7" /><circle cx="12" cy="12" r="1.7" /><circle cx="19" cy="12" r="1.7" />
                    </svg>
                  </button>
                  {requestDate === date && !request?.detail && requestMenu(date)}
                </div>
              );
            })}
          </div>
        </section>
        {(todo.length > 0 || weekTally.workDays + weekTally.leaveDays + weekTally.offDays > 0) && (
          <aside className="wl-side" aria-label="주간 근무와 처리할 일">
            {weekTally.workDays + weekTally.leaveDays + weekTally.offDays > 0 && (
              <section className="wl-side-block" aria-labelledby={`${sideId}-week`}>
                <h3 id={`${sideId}-week`}>주간 근무</h3>
                <p className="wl-side-period">{shortDate(week.from)} – {shortDate(week.to)}{week.partial && ' · 이번 달 날짜만'}</p>
                <p className="wl-side-big">{formatMinutes(weekTally.workMinutes)}<small>예정</small></p>
                <dl className="wl-side-facts">
                  <div data-kind="work"><dt>예정 근무</dt><dd>{weekTally.workDays}일</dd></div>
                  <div data-kind="leave"><dt>승인 연차</dt><dd>{weekTally.leaveDays}일</dd></div>
                  <div data-kind="off"><dt>비근로일·휴일</dt><dd>{weekTally.offDays}일</dd></div>
                  {weekTally.unknownDays > 0 && <div data-kind="unknown"><dt>확인 필요</dt><dd>{weekTally.unknownDays}일</dd></div>}
                </dl>
              </section>
            )}
            {todo.length > 0 && (
              <section className="wl-side-block" aria-labelledby={`${sideId}-todo`}>
                <h3 id={`${sideId}-todo`}>처리할 일</h3>
                <ul className="wl-side-list">
                  {todo.slice(0, 6).map(item => (
                    <li key={item.key}><a href={item.href}>{shortDate(item.date)} {item.label}</a><span className={`t-tag ${item.tone}`.trim()}>{item.state}</span></li>
                  ))}
                </ul>
                {todo.length > 6 && <p className="wl-side-more">외 {todo.length - 6}건</p>}
              </section>
            )}
          </aside>
        )}
        <section className="t-card t-day-detail t-attendance-detail" aria-label="선택한 날짜 상세">
          <header>
            <div>
              <p className="t-context">선택한 날짜</p>
              <h2><time dateTime={calendar.selectedDate}>{fmtDate(calendar.selectedDate)}</time></h2>
            </div>
            <button className="t-btn secondary" type="button" aria-expanded={Boolean(request?.detail)}
              aria-controls={request?.detail ? menuId : undefined}
              onClick={event => {
                if (request?.detail) closeMenu(true);
                else openMenu(calendar.selectedDate, event.currentTarget, true);
              }}>이 날짜로 신청</button>
            {request?.detail && requestMenu(request.date)}
          </header>
          <dl className="wl-info-grid">
            <div>
              <dt>근무 일정</dt>
              <dd>{selected.scheduleText}</dd>
            </div>
            {calendar.assignment && <div>
              <dt>{calendar.assignment.group ? '근무조' : '기존 고정배정'}</dt>
              <dd>{calendar.assignment.code} · {calendar.assignment.name}</dd>
            </div>}
            {calendar.assignment?.workCode && <div>
              <dt>근무시간코드</dt><dd>{calendar.assignment.workCode} · {calendar.assignment.workName}</dd>
            </div>}
            {calendar.assignment?.workCode && <div>
              <dt>요일별 연장 기준</dt>
              <dd>{calendar.assignment.beforeStart && <span>출근 전 {calendar.assignment.beforeStart.slice(0,5)}~{calendar.assignment.beforeEnd?.slice(0,5)} </span>}
                {calendar.assignment.afterStart && <span>퇴근 후 {calendar.assignment.afterStart.slice(0,5)}~{calendar.assignment.afterEnd?.slice(0,5)} </span>}
                {calendar.assignment.beforeStart || calendar.assignment.afterStart
                  ? <span>최대 {calendar.assignment.overtimeMinutes}분</span> : '신청 미허용'}
                {selected.snapshot.dayKind === 'HOLIDAY' && <div>휴일에는 이 연장 신청 기준을 적용하지 않습니다.</div>}
                {selected.snapshot.dayKind === 'NON_WORKDAY' && <div>비근로일에는 이 연장 신청 기준을 적용하지 않습니다.</div>}
                {!['WORKDAY', 'HOLIDAY', 'NON_WORKDAY'].includes(selected.snapshot.dayKind) && <div>해당일 적용 여부는 휴일 캘린더 확인이 필요합니다.</div>}
                {calendar.assignment.overtimeBreaks.map(b => <div key={b.start}>연장 중 예정 휴게 {b.start.slice(0,5)}~{b.end.slice(0,5)}</div>)}
              </dd>
            </div>}

            <div>
              <dt>휴일 확인</dt>
              <dd>{selected.calendarText}</dd>
            </div>
            <div>
              <dt>실제 근로</dt>
              <dd>{selected.confirmationText}</dd>
            </div>
          </dl>
          <div className="t-attendance-detail-body">
            {!!selected.snapshot.schedule?.breaks.length && (
              <div>
                <h3>예정 휴게</h3>
                {selected.snapshot.schedule.breaks.map((pause, index) => (
                  <p className="t-subtle" key={index}>{fmtTime(pause.startTime)} ~ {fmtTime(pause.endTime)}</p>
                ))}
              </div>
            )}
            <div>
              <h3>해당 날짜의 신청·효력</h3>
              {selected.requests.length ? selected.requests.map(item => (
                <div className="t-listrow" key={item.request.id}>
                  <a href={`/requests/${item.request.id}`}>{item.kindLabel}</a>
                  <span className={`t-tag ${item.tone}`} data-effective-leave={item.effectiveLeave || undefined}>{item.statusLabel}</span>
                </div>
              )) : <p className="t-muted">이 날짜에 조회된 본인 대상 신청이 없습니다.</p>}
            </div>
          </div>
          <div className="t-attendance-detail-actions">
            <a className="t-btn secondary" href={`/me/attendance?date=${calendar.selectedDate}`}>출퇴근 기록</a>
            <a className="t-btn secondary" href="/me/requests">휴가·정정 신청</a>
          </div>
        </section>
      </div>
    </>
  );
}
export function CorePages(props: PageProps) {
  switch (props.page) {
    case 'today':
      return <Today {...props} />;
    case 'workspace/schedule':
      return <Schedule {...props} />;
    case 'fragments/status':
      return <StatusPanel />;
    case 'org/problem':
    case 'error':
      return (
        <section className="panel">
          <h1>요청을 확인해 주세요</h1>
          <p role="alert">
            {String(props.data.message ?? props.data.error ?? '요청을 처리할 수 없습니다.')}
          </p>
          <a
            href={
              typeof location !== 'undefined' && location.pathname.startsWith('/operator/')
                ? '/operator/login'
                : '/login'
            }
          >
            로그인으로
          </a>
        </section>
      );
    default:
      return null;
  }
}
