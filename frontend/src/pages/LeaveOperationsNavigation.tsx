const path = '/admin/requests?';
const allowed = new Set(['scope', 'status', 'leaveTypeCode', 'requestKind', 'from', 'to', 'organizationId', 'q', 'page']);

export function leaveOperationsReturn(target: string | null | undefined): string | null {
  if (!target?.startsWith(path) || target.includes('#')) return null;
  const query = new URLSearchParams(target.slice(path.length));
  if (query.get('scope') !== 'LEAVE') return null;
  const seen = new Set<string>();
  for (const key of query.keys()) {
    if (!allowed.has(key) || seen.has(key)) return null;
    seen.add(key);
  }
  return target;
}

export function LeaveOperationsBackLink({ href }: { href: string | null | undefined }) {
  const target = leaveOperationsReturn(href);
  return target ? <a className="t-btn t-request-detail-back" href={target}>휴가 신청 목록으로</a> : null;
}
