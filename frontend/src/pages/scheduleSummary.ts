/** 내 근무 일정의 요약 줄·주간 근무·처리할 일 계산(설계 7.11). 새 API 없이 월 달력 자료만 쓴다. */
export interface SummaryRequest { request: { id: string }; tone: string; effectiveLeave: boolean }
export interface SummaryDay<R extends SummaryRequest = SummaryRequest> {
  snapshot: {
    workDate: string;
    dayKind: string;
    schedule: { workday: boolean; startTime: string | null; endTime: string | null; breaks: { startTime: string; endTime: string }[] } | null;
  };
  requests: R[];
}
export type DayClass = 'work' | 'off' | 'leave' | 'unknown';
export interface Tally { workDays: number; workMinutes: number; leaveDays: number; offDays: number; unknownDays: number }

const DAY = 86_400_000;
const clockMillis = (time: string | null) => {
  if (!time) return 0;
  const [hours, minutes, seconds = '0'] = time.split(':');
  const [wholeSeconds, fraction = ''] = seconds.split('.');
  return Number(hours) * 3_600_000 + Number(minutes) * 60_000
    + Number(wholeSeconds) * 1_000 + Number(fraction.padEnd(3, '0'));
};

/** 승인 연차 → 미배정·미확인 → 비근로일·휴일 → 예정 근무 순으로 하루를 하나로 분류한다. */
export function dayClass(day: SummaryDay): DayClass {
  const { schedule, dayKind } = day.snapshot;
  if (day.requests.some(item => item.effectiveLeave)) return 'leave';
  if (!schedule || dayKind === 'UNCONFIRMED') return 'unknown';
  if (!schedule.workday || dayKind === 'HOLIDAY' || dayKind === 'NON_WORKDAY') return 'off';
  return 'work';
}

/** 예정 근무와 휴게는 서버의 time(3) 정밀도로 차감·합산한다. */
function plannedMillis(day: SummaryDay) {
  const schedule = day.snapshot.schedule;
  if (dayClass(day) !== 'work' || !schedule) return 0;
  const breaks = schedule.breaks.reduce((sum, pause) => sum + Math.max(0, clockMillis(pause.endTime) - clockMillis(pause.startTime)), 0);
  return Math.max(0, clockMillis(schedule.endTime) - clockMillis(schedule.startTime) - breaks);
}

/** 분 단위 반환값에도 초·밀리초를 보존한다. 다른 분류는 0이다. */
export const plannedMinutes = (day: SummaryDay) => plannedMillis(day) / 60_000;

export function tally(days: SummaryDay[]): Tally {
  const result: Tally = { workDays: 0, workMinutes: 0, leaveDays: 0, offDays: 0, unknownDays: 0 };
  let workMillis = 0;
  for (const day of days) {
    const kind = dayClass(day);
    if (kind === 'work') { result.workDays += 1; workMillis += plannedMillis(day); }
    else if (kind === 'leave') result.leaveDays += 1;
    else if (kind === 'off') result.offDays += 1;
    else result.unknownDays += 1;
  }
  result.workMinutes = workMillis / 60_000;
  return result;
}

/** date가 속한 월~일 한 주 중 달력에 있는 날만 고른다. partial은 그 주가 앞뒤 달에 걸쳤다는 뜻이다. */
export function weekOf<D extends SummaryDay>(days: D[], date: string) {
  const time = Date.parse(`${date}T00:00:00Z`);
  const monday = time - ((new Date(time).getUTCDay() + 6) % 7) * DAY;
  const from = new Date(monday).toISOString().slice(0, 10);
  const to = new Date(monday + 6 * DAY).toISOString().slice(0, 10);
  return { from, to, partial: from.slice(0, 7) !== to.slice(0, 7), days: days.filter(day => day.snapshot.workDate >= from && day.snapshot.workDate <= to) };
}

/** 검토 중(tone amber) 신청. 여러 날에 걸친 신청은 처음 나온 날짜 하나로 센다. */
export function pendingRequests<R extends SummaryRequest>(days: SummaryDay<R>[]) {
  const seen = new Map<string, { item: R; date: string }>();
  for (const day of days)
    for (const item of day.requests)
      if (item.tone === 'amber' && !seen.has(item.request.id)) seen.set(item.request.id, { item, date: day.snapshot.workDate });
  return [...seen.values()].sort((a, b) => a.date.localeCompare(b.date));
}

/** 예정 요약의 시·분 표시에만 분 미만을 내린다. 표시값을 다시 합산하지 않는다. */
export const formatMinutes = (total: number) => {
  const minutes = Math.floor(total);
  return `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}`;
};
