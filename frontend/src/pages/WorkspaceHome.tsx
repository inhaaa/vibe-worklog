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
  clock: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 2',
};

function Icon({ name }: { name: keyof typeof iconPaths }) {
  return <svg aria-hidden="true" focusable="false" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d={iconPaths[name]} /></svg>;
}

export function WorkspaceHome({ data }: Pick<PageProps, 'data'>) {
  const home = data.workspaceHome as HomeView | undefined;
  if (!home) return <section className="t-work-home"><h1>홈</h1><p>업무 현황을 확인하려면 화면을 새로 고쳐 주세요.</p></section>;

  const { today, week, availableLeaveDays, pendingRequests, recentRequests } = home;
  const date = today.snapshot.workDate;
  const todayOnLeave = today.requests.some(request => request.effectiveLeave);

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
      <dl className="t-work-home-metrics" aria-label="내 근태 요약">
        <div><dt><Icon name="calendar" />오늘 예정 근무</dt><dd><strong>{today.scheduleText}</strong><small>{todayOnLeave ? '승인된 연차가 있습니다' : today.calendarText}</small></dd></div>
        <div><dt><Icon name="leave" />사용 가능 연차</dt><dd><strong>{availableLeaveDays == null ? '미등록' : `${availableLeaveDays}일`}</strong><small>현재 사용 기간의 원장 기준</small></dd></div>
        <div><dt><Icon name="request" />검토 중인 내 신청</dt><dd><strong>{pendingRequests}건</strong><small>본인이 대상인 신청 기준</small></dd></div>
      </dl>

      <div className="t-work-home-columns">
        <section className="t-work-home-panel" aria-labelledby="home-work-heading">
          <div className="t-work-home-panel-heading"><h2 id="home-work-heading">오늘 근무</h2><a href="/me/attendance">기록 상세 <Icon name="arrow" /></a></div>
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
        </section>

        <section className="t-work-home-panel" aria-labelledby="home-requests-heading">
          <div className="t-work-home-panel-heading"><h2 id="home-requests-heading">최근 내 신청</h2><a href="/me/requests">전체 보기 <Icon name="arrow" /></a></div>
          {recentRequests.length > 0 ? <ul className="t-work-home-recent" role="list">
            {recentRequests.map(({ request, kindLabel, statusLabel, tone }) => <li key={request.id}>
              <a href={`/requests/${request.id}`}>
                <span className="t-work-home-request-icon"><Icon name="request" /></span>
                <span><strong>{kindLabel}</strong><small>{request.fromDate === request.toDate ? request.fromDate : `${request.fromDate} ~ ${request.toDate}`}</small></span>
                <span className="t-work-home-badge" data-tone={tone}>{statusLabel}</span>
              </a>
            </li>)}
          </ul> : <p className="t-work-home-empty">아직 본인 대상 신청이 없습니다.</p>}
        </section>
      </div>

      <section className="t-work-home-panel t-work-home-week-panel" aria-labelledby="home-week-heading">
        <div className="t-work-home-panel-heading"><h2 id="home-week-heading">이번 주 일정</h2><a href="/me/schedule">전체 일정 <Icon name="arrow" /></a></div>
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
      </section>
    </div>
  </div>;
}
