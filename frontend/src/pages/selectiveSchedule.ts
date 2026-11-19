export type WorkMode = 'FIXED' | 'SELECTIVE';
export type SettlementPeriod = 'CALENDAR_MONTH' | 'ISO_WEEK';

export interface WorkPolicy {
  workMode?: WorkMode | null;
  coreStart?: string | null;
  coreEnd?: string | null;
  standardMinutes?: number | null;
  settlementPeriod?: SettlementPeriod | null;
}

export const periodNames: Record<SettlementPeriod, string> = {
  CALENDAR_MONTH: '1개월 (매월 1일~말일)',
  ISO_WEEK: '1주 (월~일)',
};

export const selectiveReasonLabels: Record<string, string> = {
  LEAVE_WORK_CONFLICT: '승인된 종일 휴가와 출퇴근 기록이 겹칩니다.',
  SELECTIVE_CORE_REVIEW: '코어시간의 근무 기록을 확인해 주세요.',
  SELECTIVE_WINDOW_REVIEW: '선택 가능 시간 밖의 근무 기록을 확인해 주세요.',
  SELECTIVE_SETTLEMENT_APPLIES: '선택근무 정산기간 적용',
};

export function hoursAndMinutes(minutes: number) {
  const h = Math.floor(minutes / 60), m = minutes % 60;
  return [h ? h + '시간' : '', m || !h ? m + '분' : ''].filter(Boolean).join(' ');
}

export function coreHours(policy: WorkPolicy) {
  return policy.coreStart && policy.coreEnd
    ? policy.coreStart.slice(0, 5) + ' ~ ' + policy.coreEnd.slice(0, 5) : '없음';
}

export function selectiveHours(policy: WorkPolicy & { startTime: string | null; endTime: string | null }) {
  const window = '선택 가능 ' + (policy.startTime?.slice(0, 5) ?? '미확인') + ' ~ ' + (policy.endTime?.slice(0, 5) ?? '미확인');
  const core = policy.coreStart ? '코어 ' + coreHours(policy) : '코어시간 없음';
  const standard = policy.standardMinutes != null ? hoursAndMinutes(policy.standardMinutes) : '미확인';
  return [window, core, '표준 1일 ' + standard, policy.settlementPeriod && periodNames[policy.settlementPeriod] + ' 정산']
    .filter(Boolean).join(' · ');
}
