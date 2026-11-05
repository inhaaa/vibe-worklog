import type { PageProps } from '../types';
import { AttendanceRecord } from './WorkflowPages';
import type { DaySnapshot } from './WorkflowTypes';
import { Form, fmtDate, fmtTime, fmtDateTime } from '../ui';
import { WorklogSheet, sheetColumn } from '../components/WorklogSheet';

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

function Schedule({ data }: PageProps) {
  const calendar = data.workspaceCalendar as Calendar;
  const selected = calendar.selectedDay;
  const [year, month] = calendar.month.split('-');
  return (
    <>
      <header className="t-pagehead">
        <div>

          <h1>내 근무 일정</h1>
          <p className="t-muted">배정 일정과 신청 효력을 날짜별로 확인하세요.</p>
        </div>
      </header>
      <div className="t-calendar-layout">
        <section className="t-card t-calendar-card" aria-label="월별 근무 일정">
          <div className="t-calendar-toolbar">
            <div className="t-month-navigation">
              {calendar.previousMonth && (
                <a
                  className="t-icon-button"
                  href={`/me/schedule?month=${calendar.previousMonth}`}
                  aria-label="이전 달"
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="m14 6-6 6 6 6" /></svg>
                </a>
              )}
              <h2>
                {year}년 {Number(month)}월
              </h2>
              {calendar.nextMonth && (
                <a
                  className="t-icon-button"
                  href={`/me/schedule?month=${calendar.nextMonth}`}
                  aria-label="다음 달"
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="m10 6 6 6-6 6" /></svg>
                </a>
              )}
            </div>
            <a className="t-btn secondary" href="/me/schedule">
              오늘
            </a>
          </div>
          <Form method="get" action="/me/schedule" className="t-toolbar">
            <label className="t-sr-only" htmlFor="workspace-month">
              조회 월
            </label>
            <input
              className="t-input"
              id="workspace-month"
              type="month"
              name="month"
              defaultValue={calendar.month}
              min="0001-01"
              max="9999-12"
              required
            />
            <button className="t-btn secondary" type="submit">
              월 조회
            </button>
          </Form>
          <div className="t-calendar-legend">
            <span>
              <i className="blue" aria-hidden="true" />
              예정 근무
            </span>
            <span>
              <i className="amber" aria-hidden="true" />
              휴일 정보 미확인
            </span>
            <span>
              <i className="green" aria-hidden="true" />
              승인 연차
            </span>
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
              const weekend = [0, 6].includes(new Date(`${date}T00:00:00Z`).getUTCDay());
              return (
                <a
                  key={date}
                  className={[
                    't-day',
                    entry.selected ? 'selected' : '',
                    entry.today ? 'today' : '',
                    weekend ? 'weekend' : '',
                  ]
                    .filter(Boolean)
                    .join(' ')}
                  href={`/me/schedule?month=${calendar.month}&date=${date}`}
                  aria-current={entry.today ? 'date' : undefined}
                  aria-label={`${date} · ${entry.scheduleText}${entry.selected ? ' · 선택한 날짜' : ''}`}
                >
                  <time className="t-day-number" dateTime={date}>
                    {Number(date.slice(-2))}
                  </time>
                  <span className="t-day-event">
                    {entry.snapshot.schedule == null ? '미배정' : entry.scheduleText}
                  </span>
                  {entry.snapshot.dayKind === 'UNCONFIRMED' && (
                    <span className="t-day-event warning">미확인</span>
                  )}
                  {entry.snapshot.dayKind === 'HOLIDAY' && (
                    <span className="t-day-event">확인된 휴일</span>
                  )}
                  {entry.requests.slice(0, 2).map((item) => (
                    <span
                      className={[
                        't-day-event',
                        item.effectiveLeave ? 'leave' : item.tone === 'amber' ? 'warning' : '',
                      ]
                        .filter(Boolean)
                        .join(' ')}
                      key={item.request.id}
                    >
                      {item.kindLabel}{' · '}{item.statusLabel}
                    </span>
                  ))}
                  {entry.requests.length > 2 && (
                    <span className="t-day-event">+{entry.requests.length - 2}건, 날짜 상세</span>
                  )}
                </a>
              );
            })}
          </div>
          <p className="t-calendar-note">
            일정은 예정 정보입니다. 연차의 대기·승인·취소와 실제 근로 확인은 구분합니다. 각 날짜의
            신청 전체는 날짜 상세에서 확인하세요.
          </p>
        </section>
        <section className="t-card t-day-detail" aria-label="선택한 날짜 상세">
          <p className="t-context">선택한 날짜</p>
          <h2>
            <time dateTime={calendar.selectedDate}>{fmtDate(calendar.selectedDate)}</time>
          </h2>
          <div className="t-list">
            <div className="t-listrow">
              <span className="t-muted">근무 일정</span>
              <strong>{selected.scheduleText}</strong>
            </div>
            <div className="t-listrow">
              <span className="t-muted">휴일 확인</span>
              <strong>{selected.calendarText}</strong>
            </div>
            <div className="t-listrow">
              <span className="t-muted">실제 근로</span>
              <span>{selected.confirmationText}</span>
            </div>
          </div>
          {!!selected.snapshot.schedule?.breaks.length && (
            <div>
              <h3>예정 휴게</h3>
              {selected.snapshot.schedule.breaks.map((pause, index) => (
                <p className="t-subtle" key={index}>
                  {fmtTime(pause.startTime)} ~ {fmtTime(pause.endTime)}
                </p>
              ))}
            </div>
          )}
          <div>
            <h3>해당 날짜의 신청·효력</h3>
            {selected.requests.length ? (
              selected.requests.map((item) => (
                <div className="t-listrow" key={item.request.id}>
                  <a href={`/requests/${item.request.id}`}>{item.kindLabel}</a>
                  <span className={`t-tag ${item.tone}`}>{item.statusLabel}</span>
                </div>
              ))
            ) : (
              <p className="t-muted">이 날짜에 조회된 본인 대상 신청이 없습니다.</p>
            )}
          </div>
          <p className="t-subtle">
            예정 근무와 휴게를 실제 시간으로 확정하지 않습니다. 대기 정정은 승인된 사실과
            구분합니다.
          </p>
          <a className="t-btn secondary" href={`/me/attendance?date=${calendar.selectedDate}`}>
            원천·정정 반영 내역
          </a>
          <a className="t-btn secondary" href="/me/requests">
            휴가·정정 신청
          </a>
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
