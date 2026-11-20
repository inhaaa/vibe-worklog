export function SettlementBackLink({ href }: { href?: string }) {
  return href ? <a className="t-btn" href={href}>선택근무 정산으로</a> : null;
}
