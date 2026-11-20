export const leavePartNames = {
  FULL: '종일 연차',
  AM: '오전 반차',
  PM: '오후 반차',
} as const;

export type LeavePart = keyof typeof leavePartNames;

const otherRequests: Record<string, string> = {
  ATTENDANCE_CORRECTION: '출퇴근·휴게 정정',
  OVERTIME: '연장근무',
  CANCEL_OVERTIME: '연장근무 취소',
};

export function requestKindName(kind: string, part?: LeavePart | null, companyLeaveName?: string | null) {
  if (companyLeaveName && (kind === 'LEAVE_DAY' || kind === 'CANCEL_LEAVE')) {
    const unit = part === 'AM' || part === 'PM' ? ` · ${leavePartNames[part]}` : '';
    return `${companyLeaveName}${unit}${kind === 'CANCEL_LEAVE' ? ' 취소' : ''}`;
  }
  if (kind === 'LEAVE_DAY') return leavePartNames[part ?? 'FULL'];
  if (kind === 'CANCEL_LEAVE') return part === 'AM' || part === 'PM' ? `${leavePartNames[part]} 취소` : '연차 취소';
  return Object.hasOwn(otherRequests, kind) ? otherRequests[kind] : kind;
}

/** 같은 날 승인된 오전·오후가 모두 있으면 하루를 차지한다. 예전 신청에는 단위가 없다. */
export function combinedLeavePart(parts: (LeavePart | null | undefined)[]): LeavePart | null {
  if (parts.length === 0) return null;
  const first = parts[0] ?? 'FULL';
  return parts.every(part => (part ?? 'FULL') === first) ? first : 'FULL';
}
