import { useEffect, useRef, useState } from 'react';
import { PageRequestError } from '../transport';
import { duration } from '../ui';
import { monthEndAccessDenied, monthEndRequest } from './monthEndApi';
import { matchesSettlementUrl, settlementDayAction, settlementHref, type SettlementView, type UnconfirmedDate } from './selectiveNavigation';
import { periodNames, selectiveReasonLabels, type SettlementPeriod } from './selectiveSchedule';
import './SelectiveSettlements.css';

interface Period {
  settlementPeriod: SettlementPeriod;
  fromDate: string;
  toDate: string;
  appliedDays: number;
  targetMinutes: number | null;
  confirmedWorkMillis: number;
  paidLeaveMillis: number | null;
  remainingMillis: number | null;
  differenceMillis: number | null;
  unconfirmedDays: number;
  unconfirmedDates: UnconfirmedDate[];
  upcomingDays: number;
  warnings: string[];
}
interface Report {
  month: string;
  employeeId: string;
  employeeNo: string;
  employeeName: string;
  periods: Period[];
}
function elapsed(millis: number | null) {
  if (millis == null) return '미확정';
  const fraction = millis % 1000;
  return duration(millis) + (fraction ? '.' + String(fraction).padStart(3, '0') : '');
}
function difference(period: Period) {
  const value = period.differenceMillis;
  if (value == null) return period.unconfirmedDays ? '미확정' : '정산 대기';
  if (value === 0) return '일치';
  return (value > 0 ? '초과 ' : '부족 ') + elapsed(Math.abs(value));
}

function UnconfirmedDates({ view, dates, source, refresh }: {
  view: SettlementView; dates: UnconfirmedDate[]; source: string; refresh: () => void;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    if (matchesSettlementUrl(new URL(location.href), source)
      && (document.activeElement === document.body || document.activeElement?.matches('main h1, #selective-settlements'))) {
      heading.current?.focus();
    }
  }, [source]);
  return <section className="selective-days" aria-labelledby="selective-days-heading">
    <header>
      <h4 ref={heading} id="selective-days-heading" tabIndex={-1}>미확정 날짜</h4>
      <button className="t-btn" type="button" onClick={refresh}>정산 다시 조회</button>
    </header>
    {dates.length === 0 ? <p role="status">처리할 미확정 날짜가 없습니다.</p>
      : <ul className="selective-days-list" role="list">{dates.map(day => {
        const action = settlementDayAction(view, day, source);
        const reasonsId = 'selective-reasons-' + day.workDate;
        const contents = <>
          <time dateTime={day.workDate}>{day.workDate}</time>
          <span className="selective-day-reasons" id={reasonsId}>{day.reasons.map(reason =>
            <span key={reason}>{selectiveReasonLabels[reason] ?? '근무 기준을 확인해 주세요.'}</span>)}</span>
          <span className={action.href ? 'selective-action-label' : 't-subtle'}>{action.label}</span>
        </>;
        return <li key={day.workDate}>{action.href
          ? <a className="selective-day-row" href={action.href} aria-label={day.workDate + ' ' + action.label}
              aria-describedby={reasonsId}>{contents}</a>
          : <div className="selective-day-row">{contents}</div>}</li>;
      })}</ul>}
  </section>;
}

function SettlementCard({ period, view, pageUrl, refresh }: {
  period: Period; view: SettlementView; pageUrl: URL | null; refresh: () => void;
}) {
  const source = settlementHref(view, period);
  const opened = matchesSettlementUrl(pageUrl, source);
  const detailId = 'selective-days-' + period.settlementPeriod + '-' + period.fromDate;
  const count = period.unconfirmedDates.length;
  return <article>
    <h3>{period.fromDate} ~ {period.toDate}</h3>
    <p className="t-subtle">{periodNames[period.settlementPeriod]} · 적용 {period.appliedDays}일</p>
    <dl className="wl-info-grid">
      <div><dt>기간 목표</dt><dd>{period.targetMinutes == null ? '미확정' : elapsed(period.targetMinutes * 60_000)}</dd></div>
      <div><dt>확인 실근로</dt><dd>{elapsed(period.confirmedWorkMillis)}</dd></div>
      <div><dt>승인 유급휴가</dt><dd>{elapsed(period.paidLeaveMillis)}</dd></div>
      <div><dt>현재 확인분 기준 남은 시간</dt><dd>{elapsed(period.remainingMillis)}</dd></div>
      <div><dt>정산 차이</dt><dd>{difference(period)}</dd></div>
      <div><dt>미확정 일수</dt><dd><a className="selective-day-count" aria-label={'미확정 ' + count + '일'}
        aria-controls={detailId} aria-expanded={opened} href={opened ? settlementHref(view) : source}>{count}일</a></dd></div>
      {period.upcomingDays > 0 && <div><dt>예정 근무일</dt><dd>{period.upcomingDays}일</dd></div>}
    </dl>
    <div id={detailId} hidden={!opened}>{opened &&
      <UnconfirmedDates view={view} dates={period.unconfirmedDates} source={source} refresh={refresh} />}</div>
    {period.differenceMillis == null && <p className="t-subtle">확인 실근로는 현재 확인된 날의 부분합입니다.</p>}
    {period.warnings.length > 0 && <ul>{period.warnings.map(warning =>
      <li key={warning}>{selectiveReasonLabels[warning] ?? '근무 기준을 확인해 주세요.'}</li>)}</ul>}
  </article>;
}

export function SelectiveSettlements(view: SettlementView) {
  const { month, employeeId, admin } = view;
  const [pageUrl] = useState(() => typeof window === 'undefined' ? null : new URL(location.href));
  const [report, setReport] = useState<Report>();
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const cancel = new AbortController();
    const endpoint = admin ? '/admin/attendance/month-close/selective-settlements' : '/worktime/selective-settlements';
    const query = new URLSearchParams({ month });
    if (admin) query.set('employeeId', employeeId);
    monthEndRequest<Report>(endpoint + '?' + query, undefined, cancel.signal).then(result => {
      if (cancel.signal.aborted) return;
      if (result?.month !== month || result.employeeId !== employeeId || !Array.isArray(result.periods)
        || result.periods.some(period => !Array.isArray(period.unconfirmedDates) || !Number.isInteger(period.upcomingDays))) {
        throw new Error('정산 조회 조건이 일치하지 않습니다.');
      }
      setReport(result);
    }).catch(cause => {
      if (cancel.signal.aborted) return;
      if (monthEndAccessDenied(cause)) setError('로그인 상태와 정산 조회 권한을 확인해 주세요.');
      else if (cause instanceof PageRequestError && cause.status === 503) setError(cause.message);
      else setError('선택근무 정산을 불러오지 못했습니다.');
    });
    return () => cancel.abort();
  }, [admin, employeeId, month, attempt]);
  function refresh() {
    setReport(undefined);
    setError('');
    setAttempt(n => n + 1);
  }

  if (!admin && report?.periods.length === 0) return null;
  return <section className="wl-section selective-settlements" aria-labelledby="selective-settlements">
    <h2 id="selective-settlements" tabIndex={-1}>선택근무 정산</h2>
    {error ? <div role="alert">{error} <button type="button" className="t-btn" onClick={refresh}>정산 다시 조회</button></div>
      : !report ? <p role="status">정산 기간을 조회하고 있습니다.</p>
      : report.periods.length === 0 ? <p>{report.employeeNo} · {report.employeeName} 사원에게 이 달에 적용된 선택근무 일정이 없습니다.</p>
      : <>
        {admin && <p>{report.employeeNo} · {report.employeeName}</p>}
        <p className="t-subtle">확인 실근로와 승인 유급휴가를 합쳐 기간 목표와 비교합니다. 기간이 끝나고 모든 적용일이 확인되면 정산 차이가 표시됩니다.</p>
        {report.periods.map(period => <SettlementCard key={period.settlementPeriod + period.fromDate}
          period={period} view={view} pageUrl={pageUrl} refresh={refresh} />)}
      </>}
  </section>;
}
