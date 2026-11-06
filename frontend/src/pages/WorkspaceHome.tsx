import { useId, useRef, useState } from 'react';
import type { PageProps } from '../types';
import type { DaySnapshot } from './WorkflowTypes';
import './WorkspaceHome.css';

interface HomeRequest {
  request: { id: string; fromDate: string; toDate: string };
  kindLabel: string;
  statusLabel: string;
  tone: string;
}

interface HomeDay {
  snapshot: Pick<DaySnapshot, 'workDate'>;
  scheduleText: string;
  calendarText: string;
  requests: { effectiveLeave: boolean }[];
}

interface HomeToday extends HomeDay {
  confirmationText: string;
  inTime: string;
  outTime: string;
  timeline: { time: string; title: string; kind: string }[];
}

interface HomeView {
  employee: { employeeName: string };
  today: HomeToday;
  week: (HomeDay & { today: boolean })[];
  availableLeaveDays: number | null;
  pendingRequests: number;
  recentRequests: HomeRequest[];
}

const dateLabel = new Intl.DateTimeFormat('ko-KR', {
  month: 'long', day: 'numeric', weekday: 'long', timeZone: 'Asia/Seoul',
});
const weekdayLabel = new Intl.DateTimeFormat('ko-KR', { weekday: 'short', timeZone: 'Asia/Seoul' });
const seoulDay = (date: string) => new Date(`${date}T00:00:00+09:00`);

const iconPaths = {
  calendar: 'M4 6h16v14H4z M8 4v4m8-4v4M4 11h16',
  leave: 'M3 16h18M5 16a7 7 0 1 1 14 0M8 20h8',
  request: 'M6 3h9l3 3v15H6zM14 3v4h4M9 11h6m-6 4h6',
  arrow: 'M4 12h15m-6-6 6 6-6 6',
  plus: 'M12 5v14M5 12h14',
  edit: 'M4 6h6m4 0h6M4 12h10m4 0h2M4 18h2m4 0h10M10 4v4m4 2v4M6 16v4',
  clock: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 2',
};

function Icon({ name }: { name: keyof typeof iconPaths }) {
  return <svg aria-hidden="true" focusable="false" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d={iconPaths[name]} /></svg>;
}

const widgetIds = ['schedule', 'leave', 'pending', 'attendance', 'requests', 'week'] as const;
type WidgetId = typeof widgetIds[number];

const widgets: Record<WidgetId, { title: string; icon: keyof typeof iconPaths; detail: string }> = {
  schedule: { title: '오늘 예정 근무', icon: 'calendar', detail: '오늘 일정과 근무일 여부' },
  leave: { title: '사용 가능 연차', icon: 'leave', detail: '현재 원장의 연차 잔여일' },
  pending: { title: '검토 중인 내 신청', icon: 'request', detail: '처리를 기다리는 신청 건수' },
  attendance: { title: '오늘 근무', icon: 'clock', detail: '출퇴근 기록과 오늘의 타임라인' },
  requests: { title: '최근 내 신청', icon: 'request', detail: '최근 신청 내역과 결재 상태' },
  week: { title: '이번 주 일정', icon: 'calendar', detail: '요일별 근무와 휴가 일정' },
};

function readWidgets(storageKey: string | undefined): readonly WidgetId[] {
  if (!storageKey) return widgetIds;
  try {
    const saved = localStorage.getItem(storageKey);
    if (saved === null) return widgetIds;
    const ids: unknown = JSON.parse(saved);
    if (Array.isArray(ids)) {
      const known = widgetIds.filter(id => ids.includes(id));
      if (known.length || ids.length === 0) return known;
    }
  } catch {
    // 저장소 접근이 제한되거나 값이 깨진 경우에는 기본 구성을 보여 준다.
  }
  return widgetIds;
}

export function WorkspaceHome({ data }: Pick<PageProps, 'data'>) {
  const home = data.workspaceHome as HomeView | undefined;
  if (!home) return <section className="t-work-home"><h1>홈</h1><p>업무 현황을 확인하려면 화면을 새로 고쳐 주세요.</p></section>;
  const scope = typeof data.homeWidgetScope === 'string' ? data.homeWidgetScope : '';
  const storageKey = scope ? `worklog-home-widgets:${scope}` : undefined;
  return <HomeDashboard key={storageKey} home={home} storageKey={storageKey} />;
}

function HomeDashboard({ home, storageKey }: { home: HomeView; storageKey?: string }) {
  const [visible, setVisible] = useState(() => readWidgets(storageKey));
  const [draft, setDraft] = useState<readonly WidgetId[] | null>(null);
  const [localOnly, setLocalOnly] = useState(false);
  const editorId = useId();
  const editButton = useRef<HTMLButtonElement>(null);

  function closeEditor() {
    setDraft(null);
    editButton.current?.focus();
  }

  function saveWidgets() {
    if (!draft) return;
    let persisted = false;
    if (storageKey) {
      try {
        localStorage.setItem(storageKey, JSON.stringify(draft));
        persisted = true;
      } catch {
        // 저장할 수 없는 환경에서도 화면의 선택은 반영한다.
      }
    }
    setLocalOnly(!persisted);
    setVisible(draft);
    closeEditor();
  }

  const { today, week, availableLeaveDays, pendingRequests, recentRequests } = home;
  const date = today.snapshot.workDate;
  const todayOnLeave = today.requests.some(request => request.effectiveLeave);
  const metrics = [
    { id: 'schedule', value: today.scheduleText, note: todayOnLeave ? '승인된 연차가 있습니다' : today.calendarText },
    { id: 'leave', value: availableLeaveDays == null ? '미등록' : `${availableLeaveDays}일`, note: '현재 사용 기간의 원장 기준' },
    { id: 'pending', value: `${pendingRequests}건`, note: '본인이 대상인 신청 기준' },
  ] as const;
  const displayedMetrics = metrics.filter(({ id }) => visible.includes(id));
  const showAttendance = visible.includes('attendance');
  const showRequests = visible.includes('requests');

  return <div className="t-work-home">
    <div className="t-work-home-desk">
      <header className="t-work-home-welcome">
        <div className="t-work-home-intro">
          <p className="t-work-home-date"><Icon name="calendar" /><time dateTime={date}>{dateLabel.format(seoulDay(date))}</time></p>
          <h1>{home.employee.employeeName}님, 안녕하세요</h1>
          <p>오늘의 근무 일정과 내 신청을 한눈에 확인하세요.</p>
        </div>
        <div className="t-work-home-actions">
          <a href="/me/attendance"><Icon name="clock" />출퇴근 기록</a>
          <a href="/me/requests/new"><Icon name="plus" />근태 신청</a>
        </div>
      </header>
      <div className="t-work-home-toolbar">
        <div>
          <h2>내 위젯</h2>
          <p role="status">{visible.length}개 표시 중{localOnly && <> · <span>이 화면에만 구성이 적용됩니다.</span></>}</p>
        </div>
        <button type="button" className="t-btn t-work-home-button" ref={editButton} aria-expanded={draft !== null}
          aria-controls={draft ? editorId : undefined} onClick={() => draft ? closeEditor() : setDraft(visible)}>
          <Icon name="edit" />위젯 편집
        </button>
      </div>
      {draft && <form id={editorId} className="t-work-home-editor" onSubmit={event => { event.preventDefault(); saveWidgets(); }}>
        <fieldset>
          <legend>표시할 위젯</legend>
          <div className="t-work-home-widget-options">
            {widgetIds.map(id => <label key={id}>
              <input type="checkbox" checked={draft.includes(id)} onChange={event => {
                const checked = event.target.checked;
                setDraft(widgetIds.filter(item => item === id ? checked : draft.includes(item)));
              }} />
              <span className="t-work-home-widget-icon" data-widget={id}><Icon name={widgets[id].icon} /></span>
              <span><strong>{widgets[id].title}</strong><small>{widgets[id].detail}</small></span>
            </label>)}
          </div>
        </fieldset>
        <div className="t-work-home-editor-actions">
          <button type="button" className="t-btn t-work-home-button" onClick={closeEditor}>취소</button>
          <button type="submit" className="t-btn t-work-home-button primary">구성 저장</button>
        </div>
      </form>}
      {visible.length === 0 && <section className="t-work-home-panel t-work-home-no-widgets" aria-label="빈 위젯 영역">
        <Icon name="edit" />
        <p><strong>표시 중인 위젯이 없습니다.</strong></p>
        <p>위젯 편집에서 홈에 보고 싶은 항목을 골라보세요.</p>
      </section>}

      {displayedMetrics.length > 0 && <dl className="t-work-home-metrics" aria-label="내 근태 요약">
        {displayedMetrics.map(({ id, value, note }) => <div key={id} className="t-work-home-metric" data-widget={id}>
          <dt><span className="t-work-home-widget-icon"><Icon name={widgets[id].icon} /></span>{widgets[id].title}</dt>
          <dd><strong>{value}</strong><small>{note}</small></dd>
        </div>)}
      </dl>}

      {(showAttendance || showRequests) && <div className="t-work-home-columns">
        {showAttendance && <section className="t-work-home-panel" aria-labelledby="home-work-heading">
          <div className="t-work-home-panel-heading"><h2 id="home-work-heading"><span className="t-work-home-widget-icon"><Icon name="clock" /></span>오늘 근무</h2><a href="/me/attendance">기록 상세 <Icon name="arrow" /></a></div>
          <dl className="t-work-home-clock">
            <div><dt>현재 반영 출근</dt><dd>{today.inTime}</dd></div>
            <div><dt>현재 반영 퇴근</dt><dd>{today.outTime}</dd></div>
          </dl>
          <p className="t-work-home-confirmation">{today.confirmationText}</p>
          {today.timeline.length > 0 ? <ol className="t-work-home-timeline" role="list">
            {today.timeline.map((entry, index) => <li key={`${entry.time}-${entry.title}-${index}`}>
              <time dateTime={entry.time}>{entry.time.slice(0, 5)}</time>
              <span>{entry.title}</span>
              <span className="t-work-home-badge" data-tone={entry.kind === 'recorded' ? 'recorded' : undefined}>{entry.kind === 'recorded' ? '현재 반영' : '예정'}</span>
            </li>)}
          </ol> : <p className="t-work-home-empty">표시할 일정이나 출퇴근 기록이 없습니다.</p>}
        </section>}

        {showRequests && <section className="t-work-home-panel" aria-labelledby="home-requests-heading">
          <div className="t-work-home-panel-heading"><h2 id="home-requests-heading"><span className="t-work-home-widget-icon"><Icon name="request" /></span>최근 내 신청</h2><a href="/me/requests">전체 보기 <Icon name="arrow" /></a></div>
          {recentRequests.length > 0 ? <ul className="t-work-home-recent" role="list">
            {recentRequests.map(({ request, kindLabel, statusLabel, tone }) => <li key={request.id}>
              <a href={`/requests/${request.id}`}>
                <span className="t-work-home-request-icon"><Icon name="request" /></span>
                <span><strong>{kindLabel}</strong><small>{request.fromDate === request.toDate ? request.fromDate : `${request.fromDate} ~ ${request.toDate}`}</small></span>
                <span className="t-work-home-badge" data-tone={tone}>{statusLabel}</span>
              </a>
            </li>)}
          </ul> : <p className="t-work-home-empty">아직 본인 대상 신청이 없습니다.</p>}
        </section>}
      </div>}

      {visible.includes('week') && <section className="t-work-home-panel" aria-labelledby="home-week-heading">
        <div className="t-work-home-panel-heading"><h2 id="home-week-heading"><span className="t-work-home-widget-icon"><Icon name="calendar" /></span>이번 주 일정</h2><a href="/me/schedule">전체 일정 <Icon name="arrow" /></a></div>
        <div className="t-work-home-week">
          {week.map(day => {
            const workDate = day.snapshot.workDate;
            return <a key={workDate} href={`/me/schedule?month=${workDate.slice(0, 7)}&date=${workDate}`} aria-current={day.today ? 'date' : undefined}>
              <span className="t-work-home-week-date">{weekdayLabel.format(seoulDay(workDate))}<time dateTime={workDate}>{workDate.slice(5).replace('-', '.')}</time>{day.today && <span>오늘</span>}</span>
              <span className="t-work-home-week-shift"><strong>{day.scheduleText}</strong>
                <small>{day.requests.some(request => request.effectiveLeave) ? '승인된 연차' : day.calendarText}</small></span>
            </a>;
          })}
        </div>
      </section>}
    </div>
  </div>;
}
