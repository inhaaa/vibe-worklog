const path = '/company/attendance';
const checkoutStatuses = ['ALL', 'MISSING', 'PENDING', 'CORRECTED'];

export const monthEndReasons = {
  UNCALCULATED: '미산정',
  STALE: '재집계 필요',
  UNCONFIRMED: '근무 확인 필요',
  PENDING_CORRECTION: '정정 결재 대기',
} as const;

export const monthCloseKinds = { PENDING_REQUEST: '결재 대기 신청', OPEN_FOLLOW_UP: '진행 중 보완 요청' } as const;

export function monthCloseListHref(month: string, category: keyof typeof monthCloseKinds | keyof typeof monthEndReasons | 'ALL',
  page: number, source: string | null) {
  const blockers = Object.hasOwn(monthCloseKinds, category);
  const query = new URLSearchParams({ view: blockers ? 'close-blockers' : 'month-end', month,
    [blockers ? 'kind' : 'reason']: category, page: String(page) });
  if (source) query.set('source', source);
  return path + '?' + query;
}

export function monthCloseSource(month: string, target = new URLSearchParams(window.location.search).get('source')): string | null {
  if (!target?.startsWith(path + '?')) return null;
  const query = new URLSearchParams(target.slice(path.length + 1));
  if (query.get('view') !== 'month-end' || query.get('month') !== month || query.has('source')) return null;
  return attendanceListReturn(target);
}

export function attendanceListReturn(target: string | null = new URLSearchParams(window.location.search).get('returnTo')): string | null {
  if (!target?.startsWith(path + '?') || target.length > 2048 || target.includes('#')) return null;
  const query = new URLSearchParams(target.slice(path.length + 1));
  const view = query.get('view');
  const allowed = ['view', 'page'];
  if (view === 'checkout') {
    allowed.push('date', 'status', 'organizationId', 'q');
    if (!/^\d{4}-\d{2}-\d{2}$/.test(query.get('date') ?? '') || !checkoutStatuses.includes(query.get('status') ?? 'ALL')) return null;
  } else if (view === 'month-end' || view === 'close-blockers') {
    allowed.push('month', 'source');
    const month = query.get('month') ?? '';
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month) || (query.has('source') && !monthCloseSource(month, query.get('source')))) return null;
    if (view === 'close-blockers') {
      allowed.push('kind');
      if (!Object.hasOwn(monthCloseKinds, query.get('kind') ?? '')) return null;
    } else {
      allowed.push('reason', 'organizationId', 'q');
      const reason = query.get('reason') ?? 'ALL';
      if (reason !== 'ALL' && !Object.hasOwn(monthEndReasons, reason)) return null;
    }
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
  const view = new URLSearchParams(target.slice(path.length + 1)).get('view');
  if (view === 'close-blockers' && !href) return null;
  const label = view === 'close-blockers' ? '월 마감 미처리 목록으로' : view === 'month-end' ? '월말 확인 목록으로' : '퇴근 누락 목록으로';
  return <a className="t-btn" href={target}>{label}</a>;
}

export function CompanyAttendanceViews({ active = 'month' }: { active?: 'month' | 'checkout' | 'month-end' | 'exceptions' | 'weekly-hours' }) {
  return <nav className="checkout-views" aria-label="회사 근무현황 조회 방식">
    <a href={path} aria-current={active === 'month' ? 'page' : undefined}>월별 근무현황</a>
    <a href={path + '?view=checkout'} aria-current={active === 'checkout' ? 'page' : undefined}>퇴근 누락</a>
    <a href={path + '?view=month-end'} aria-current={active === 'month-end' ? 'page' : undefined}>월말 확인 대상</a>
    <a href={path + '?view=exceptions'} aria-current={active === 'exceptions' ? 'page' : undefined}>지각·조퇴·결근</a>
    <a href={path + '?view=weekly-hours'} aria-current={active === 'weekly-hours' ? 'page' : undefined}>주 52시간</a>
  </nav>;
}
