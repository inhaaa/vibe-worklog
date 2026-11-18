const path = '/company/attendance';
const checkoutStatuses = ['ALL', 'MISSING', 'PENDING', 'CORRECTED'];

export const monthEndReasons = {
  UNCALCULATED: '미산정',
  STALE: '재집계 필요',
  UNCONFIRMED: '근무 확인 필요',
  PENDING_CORRECTION: '정정 결재 대기',
} as const;

export function attendanceListReturn(target: string | null = new URLSearchParams(window.location.search).get('returnTo')) {
  if (!target?.startsWith(path + '?') || target.length > 2048 || target.includes('#')) return null;
  const query = new URLSearchParams(target.slice(path.length + 1));
  const view = query.get('view');
  const allowed = ['view', 'organizationId', 'q', 'page'];
  if (view === 'checkout') {
    allowed.push('date', 'status');
    if (!/^\d{4}-\d{2}-\d{2}$/.test(query.get('date') ?? '') || !checkoutStatuses.includes(query.get('status') ?? 'ALL')) return null;
  } else if (view === 'month-end') {
    allowed.push('month', 'reason');
    const reason = query.get('reason') ?? 'ALL';
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(query.get('month') ?? '') || (reason !== 'ALL' && !Object.hasOwn(monthEndReasons, reason))) return null;
  } else return null;
  const org = query.get('organizationId');
  const page = query.get('page') ?? '0';
  if ([...query.keys()].some(key => !allowed.includes(key) || query.getAll(key).length !== 1)
      || (query.get('q') ?? '').length > 100 || !/^\d{1,10}$/.test(page) || Number(page) > 100_000
      || (org && !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(org))) return null;
  return target;
}

export function AttendanceListBackLink({ href }: { href?: string | null }) {
  const target = attendanceListReturn(href);
  if (!target) return null;
  const monthEnd = new URLSearchParams(target.slice(path.length + 1)).get('view') === 'month-end';
  return <a className="t-btn" href={target}>{monthEnd ? '월말 확인 목록으로' : '퇴근 누락 목록으로'}</a>;
}

export function CompanyAttendanceViews({ active = 'month' }: { active?: 'month' | 'checkout' | 'month-end' }) {
  return <nav className="checkout-views" aria-label="회사 근무현황 조회 방식">
    <a href={path} aria-current={active === 'month' ? 'page' : undefined}>월별 근무현황</a>
    <a href={path + '?view=checkout'} aria-current={active === 'checkout' ? 'page' : undefined}>퇴근 누락</a>
    <a href={path + '?view=month-end'} aria-current={active === 'month-end' ? 'page' : undefined}>월말 확인 대상</a>
  </nav>;
}
