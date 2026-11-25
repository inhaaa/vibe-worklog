import type { SettlementPeriod } from './selectiveSchedule';

export interface SettlementView {
  admin: boolean;
  employeeId: string;
  month: string;
}

type DayAction = 'CONFIRM' | 'CORRECT' | 'REQUEST' | 'CALENDAR' | 'RECALCULATE' | 'REVIEW' | 'NONE';
export interface UnconfirmedDate {
  workDate: string;
  reasons: string[];
  employeeAction: DayAction;
  adminAction: DayAction;
  requestId: string | null;
}

export function settlementHref(view: SettlementView, period?: { settlementPeriod: SettlementPeriod; fromDate: string }) {
  const query = new URLSearchParams();
  if (view.admin) query.set('employeeId', view.employeeId);
  query.set('month', view.month);
  if (period) {
    query.set('settlementPeriod', period.settlementPeriod);
    query.set('settlementFrom', period.fromDate);
  }
  return (view.admin ? '/admin/attendance?' : '/me/worktime?') + query;
}

export function matchesSettlementUrl(page: URL | null, source: string) {
  if (!page) return false;
  const expected = new URL(source, page.origin);
  return page.pathname === expected.pathname && page.searchParams.size === expected.searchParams.size
    && [...expected.searchParams].every(([key, value]) => page.searchParams.get(key) === value);
}

export function settlementDayAction(view: SettlementView, day: UnconfirmedDate, settlementReturn: string): { label: string; href?: string } {
  const action = view.admin ? day.adminAction : day.employeeAction;
  const date = day.workDate;
  const link = (label: string, path: string, query: Record<string, string>, hash = '') =>
    ({ label, href: path + '?' + new URLSearchParams({ ...query, settlementReturn }) + hash });

  if (action === 'REQUEST' && day.requestId) {
    return link('신청 진행 확인', '/requests/' + encodeURIComponent(day.requestId), {});
  }
  if (view.admin) {
    if (action === 'CALENDAR') return link('휴일 관리 확인', '/admin/holidays', { year: date.slice(0, 4) });
    if (action === 'RECALCULATE' || action === 'REVIEW') {
      const recalculate = action === 'RECALCULATE';
      return link(recalculate ? '대상일 재집계' : '근무일 검토', '/admin/attendance', {
        employeeId: view.employeeId, month: date.slice(0, 7), workDate: date,
      }, recalculate ? '#selective-day-calculation' : '#selective-day-review');
    }
  } else {
    // 직원 확인 버튼은 없다. 조건을 갖춘 날은 새벽 일괄 작업이 자동 확인한다.
    if (action === 'CONFIRM') return { label: '자동 확인 대기(새벽 집계)' };
    if (action === 'REVIEW') return link('근무 기록 보기', '/me/attendance', { date });
    if (action === 'CORRECT') return link('정정 신청', '/me/requests/correction', { date, mode: 'full' });
  }
  if (day.reasons.includes('MONTH_CLOSED')) return { label: '조회 전용' };
  if (day.reasons.includes('DAY_IN_PROGRESS')) return { label: '퇴근 후 확인' };
  return { label: '담당자 확인 대기' };
}
