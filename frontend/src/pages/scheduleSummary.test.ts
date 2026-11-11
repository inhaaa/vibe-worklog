import { describe, expect, it } from 'vitest';
import { dayClass, formatMinutes, pendingRequests, plannedMinutes, tally, weekOf, type SummaryDay } from './scheduleSummary';

const work = { workday: true, startTime: '09:00', endTime: '18:00', breaks: [{ startTime: '12:00', endTime: '13:00' }] };
const rest = { workday: false, startTime: null, endTime: null, breaks: [] };
function day(date: string, changes: Partial<SummaryDay['snapshot']> = {}, requests: SummaryDay['requests'] = []): SummaryDay {
  const weekday = new Date(`${date}T00:00:00Z`).getUTCDay();
  const workday = weekday !== 0 && weekday !== 6;
  return { snapshot: { workDate: date, dayKind: workday ? 'WORKDAY' : 'NON_WORKDAY', schedule: workday ? work : rest, ...changes }, requests };
}
const request = (id: string, tone: string, effectiveLeave = false) => ({ request: { id }, tone, effectiveLeave });
const november = Array.from({ length: 30 }, (_, index) => day(`2026-11-${String(index + 1).padStart(2, '0')}`));

describe('내 근무 일정 요약 계산', () => {
  it('하루를 승인 연차·미배정/미확인·비근로일/휴일·예정 근무 순으로 하나만 분류하고 예정 휴게를 뺀다', () => {
    expect(dayClass(day('2026-11-10'))).toBe('work');
    expect(plannedMinutes(day('2026-11-10'))).toBe(480);
    expect(dayClass(day('2026-11-10', {}, [request('r-1', 'green', true)]))).toBe('leave');
    expect(dayClass(day('2026-11-10', { dayKind: 'HOLIDAY' }))).toBe('off');
    expect(dayClass(day('2026-11-14'))).toBe('off');
    expect(dayClass(day('2026-11-19', { schedule: null, dayKind: 'UNCONFIRMED' }))).toBe('unknown');
    expect(dayClass(day('2026-11-18', { dayKind: 'UNCONFIRMED' }))).toBe('unknown');
    expect(plannedMinutes(day('2026-11-19', { schedule: null }))).toBe(0);
    expect(plannedMinutes(day('2026-11-10', { schedule: { ...work, startTime: '09:00:00.000', endTime: '18:30:00' } }))).toBe(510);
  });

  it('미배정: 일정이 없거나 미확인인 날은 0시간 근무로 세지 않고 따로 센다', () => {
    const days = [day('2026-11-16'), day('2026-11-17', { schedule: null }), day('2026-11-18', { dayKind: 'UNCONFIRMED' })];
    expect(tally(days)).toEqual({ workDays: 1, workMinutes: 480, leaveDays: 0, offDays: 0, unknownDays: 2 });
  });

  it('주 경계: 선택한 날짜가 속한 월요일~일요일을 고른다', () => {
    const week = weekOf(november, '2026-11-12');
    expect([week.from, week.to, week.days.length, week.partial]).toEqual(['2026-11-09', '2026-11-15', 7, false]);
    expect(tally(week.days)).toEqual({ workDays: 5, workMinutes: 2400, leaveDays: 0, offDays: 2, unknownDays: 0 });
    expect(weekOf(november, '2026-11-15').from).toBe('2026-11-09');
    expect(weekOf(november, '2026-11-16').from).toBe('2026-11-16');
  });

  it('월 경계: 앞뒤 달에 걸친 주는 이번 달 날짜만 집계하고 걸쳤다고 알린다', () => {
    const first = weekOf(november, '2026-11-01');
    expect([first.from, first.to, first.days.map(entry => entry.snapshot.workDate), first.partial])
      .toEqual(['2026-10-26', '2026-11-01', ['2026-11-01'], true]);
    const last = weekOf(november, '2026-11-30');
    expect([last.from, last.to, last.days.map(entry => entry.snapshot.workDate), last.partial])
      .toEqual(['2026-11-30', '2026-12-06', ['2026-11-30'], true]);
    expect(tally(last.days).workDays).toBe(1);
  });

  it('검토 중 신청은 여러 날에 걸쳐도 한 번만 세고 처음 나온 날짜 순으로 둔다', () => {
    const days = [day('2026-11-17', {}, [request('r-2', 'amber')]), day('2026-11-18', {}, [request('r-2', 'amber')]),
      day('2026-11-05', {}, [request('r-1', 'amber'), request('r-3', 'green', true)])];
    expect(pendingRequests(days).map(entry => [entry.item.request.id, entry.date])).toEqual([['r-1', '2026-11-05'], ['r-2', '2026-11-17']]);
    expect(formatMinutes(9120)).toBe('152:00');
    expect(formatMinutes(510)).toBe('8:30');
  });
});
