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
  NOT_CALCULATED: '아직 계산하지 않은 근무일이 있습니다.',
  STALE_RESULT: '근무 자료가 변경되어 재집계가 필요합니다.',
  PENDING_CORRECTION: '결재 대기 중인 정정 신청이 있습니다.',
  CALENDAR_UNCONFIRMED: '회사 달력에서 근로일 여부를 확인해 주세요.',
  BREAK_UNCONFIRMED: '실제 휴게시간을 확인해 주세요.',
  WORK_UNCONFIRMED: '출퇴근 구간의 실제 근로시간을 확인해 주세요.',
  CONFIRMATION_STALE: '기록이 변경되어 근로·휴게시간을 다시 확인해야 합니다.',
  INVALID_BREAK_INTERVAL: '실제 휴게 구간을 확인해 주세요.',
  MISSING_CLOCK_IN: '출근 기록이 없는 날이 있습니다.',
  MISSING_CLOCK_OUT: '퇴근 기록이 없는 날이 있습니다.',
  INVALID_CLOCK_ORDER: '출근과 퇴근의 시각 순서를 확인해 주세요.',
  DUPLICATE_CLOCK_EVENT: '중복된 출퇴근 기록을 확인해 주세요.',
  CROSS_MIDNIGHT_UNSUPPORTED: '자정을 넘긴 근무 기록을 확인해 주세요.',
  INVALID_SCHEDULE: '근무일의 시간 설정을 확인해 주세요.',
  LEAVE_WORK_CONFLICT: '승인된 종일 휴가와 출퇴근 기록이 겹칩니다.',
  SELECTIVE_CORE_REVIEW: '코어시간의 근무 기록을 확인해 주세요.',
  SELECTIVE_WINDOW_REVIEW: '선택 가능 시간 밖의 근무 기록을 확인해 주세요.',
  SELECTIVE_SETTLEMENT_APPLIES: '선택근무 정산기간 적용',
  DAY_IN_PROGRESS: '오늘 근무가 아직 끝나지 않았습니다.',
  MONTH_CLOSED: '마감 당시의 근무 기록입니다.',
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
