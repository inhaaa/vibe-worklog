import { useState } from 'react';
import { PageHeader } from '../components/PageHeader';
import { Form, duration, fmtDateTime, fmtTime } from '../ui';
import type { WorkMode } from './selectiveSchedule';
import './AttendancePeriodPage.css';

const attendance = '/me/attendance';
const scheduleLabels = {
  WORKDAY: '근무일', HOLIDAY: '휴일', NON_WORKDAY: '비근무일', NO_SCHEDULE: '근무 일정 미배정',
  CALENDAR_UNKNOWN: '캘린더 미확인', APPROVED_LEAVE: '승인 휴가', OUTSIDE_EMPLOYMENT: '재직 기간 외',
};
const recordLabels = {
  NONE: '기록 없음', PRESENT: '출퇴근 기록 있음', IN_PROGRESS: '근무 중', MISSING: '출퇴근 누락', INVALID: '확인 필요',
};

interface Row {
  workDate: string;
  scheduleKind: keyof typeof scheduleLabels;
  workMode?: WorkMode | null;
  startTime: string | null;
  endTime: string | null;
  inAt: string | null;
  outAt: string | null;
  recordStatus: keyof typeof recordLabels;
  corrected: boolean;
  pendingCorrection: boolean;
  workStatus: 'AVAILABLE' | 'PENDING' | 'STALE' | 'ABSENT';
  actualWorkMillis: number | null;
  correctionSuggested: boolean;
}

export interface AttendancePeriod {
  today: string;
  from: string;
  to: string;
  missingOnly: boolean;
  rows: Row[];
}

function periodLink(from: string, to: string, missingOnly: boolean) {
  const params = new URLSearchParams({ view: 'period', from, to });
  if (missingOnly) params.set('missingOnly', 'true');
  return `${attendance}?${params}`;
}

function previousMonth(currentStart: string) {
  const end = new Date(`${currentStart}T00:00:00Z`);
  end.setUTCDate(0);
  const lastDay = end.toISOString().slice(0, 10);
  return { from: `${lastDay.slice(0, 7)}-01`, to: lastDay };
}

const clock = (value: string | null) => value ? fmtDateTime(value).slice(11) : '-';

function AttendancePeriodFilter({ model, error }: { model: AttendancePeriod; error?: string }) {
  const { today, from, to } = model;
  const [missingOnly, setMissingOnly] = useState(model.missingOnly);
  const thisMonthStart = `${today.slice(0, 7)}-01`;
  const lastMonth = previousMonth(thisMonthStart);

  return <Form action={attendance} method="get" className="wl-querybar attendance-period-filter">
      <input type="hidden" name="view" value="period" />
      <label>시작일<input className="t-input" name="from" type="date" defaultValue={from} max={today} required
        data-error-focus={error ? 'true' : undefined} aria-invalid={Boolean(error)} aria-describedby={error ? 'attendance-period-error' : undefined} /></label>
      <label>종료일<input className="t-input" name="to" type="date" defaultValue={to} max={today} required
        aria-invalid={Boolean(error)} aria-describedby={error ? 'attendance-period-error' : undefined} /></label>
      <label className="attendance-period-toggle"><input type="checkbox" name="missingOnly" value="true" checked={missingOnly}
        onChange={event => setMissingOnly(event.currentTarget.checked)} />출퇴근 누락만</label>
      <button className="t-btn wl-query-submit">조회</button>
      <div className="attendance-period-shortcuts">
        <a className="t-btn" href={periodLink(thisMonthStart, today, missingOnly)}>이번 달</a>
        <a className="t-btn" href={periodLink(lastMonth.from, lastMonth.to, missingOnly)}>지난달</a>
      </div>
    </Form>;
}

export function AttendancePeriodPage({ model, error }: { model: AttendancePeriod; error?: string }) {
  const { from, to, missingOnly, rows } = model;
  return <section className="attendance-period">
    <PageHeader title="출퇴근 기간 조회" actions={<a className="t-btn" href={attendance}>오늘 기록</a>} />
    <AttendancePeriodFilter key={`${from}/${to}/${missingOnly}`} model={model} error={error} />
    {error ? <p id="attendance-period-error" className="notice" role="alert">{error}</p>
      : rows.length === 0 ? <p role="status">{missingOnly ? '선택한 기간에 출퇴근 누락이 없습니다.' : '선택한 기간에 출퇴근 기록이 없습니다.'}</p>
        : <table className="attendance-period-results" role="table" aria-label="기간별 출퇴근 기록">
          <thead role="rowgroup"><tr role="row"><th scope="col" role="columnheader">날짜</th><th scope="col" role="columnheader">예정</th>
            <th scope="col" role="columnheader">출근</th><th scope="col" role="columnheader">퇴근</th><th scope="col" role="columnheader">상태</th>
            <th scope="col" role="columnheader">실근로</th></tr></thead>
          <tbody role="rowgroup">{rows.map(row => <tr key={row.workDate} role="row">
            <th scope="row" role="rowheader"><a href={`${attendance}?date=${row.workDate}`}>{row.workDate}</a></th>
            <td role="cell" data-label="예정">{row.scheduleKind === 'WORKDAY' && row.startTime && row.endTime
              ? (row.workMode === 'SELECTIVE' ? '선택 가능 ' : '') + fmtTime(row.startTime) + ' ~ ' + fmtTime(row.endTime) : scheduleLabels[row.scheduleKind]}</td>
            <td role="cell" data-label="출근">{clock(row.inAt)}</td>
            <td role="cell" data-label="퇴근">{clock(row.outAt)}</td>
            <td role="cell" data-label="상태" className="attendance-period-status"><span>{recordLabels[row.recordStatus]}</span>
              {row.corrected && <span>정정 반영</span>}{row.pendingCorrection && <span>정정 대기</span>}
              {row.correctionSuggested && <a href={`/me/requests/correction?date=${row.workDate}`} aria-label={`${row.workDate} 정정 신청`}>정정 신청</a>}</td>
            <td role="cell" data-label="실근로">{row.workStatus === 'PENDING' ? '확인 전'
              : row.workStatus === 'STALE' ? '- (재집계 필요)' : duration(row.actualWorkMillis)}</td>
          </tr>)}</tbody>
        </table>}
  </section>;
}
