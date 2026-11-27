export type AttendanceDecision = 'FULL' | 'NOT_FULL' | 'INCLUDED';
export const decisionNames: Record<AttendanceDecision, string> = { FULL: '개근', NOT_FULL: '미개근', INCLUDED: '개근 · 기존 원장에 포함' };

export interface OpeningSource {
  bucketId: string;
  sourceReference: string;
  creditedDays: number;
  linkedDays: number;
  accrualDates: string[];
}

export interface EmploymentEmployee {
  id: string;
  employeeNo: string;
  employeeName: string;
  joinedOn: string;
  leftOn: string | null;
}

export interface MonthlyPeriod {
  monthNumber: number;
  periodStart: string;
  periodEnd: string;
  accrualOn: string;
  scheduledDays: number;
  attendedDays: number;
  leaveDays: number;
  unresolvedDays: number;
  decision: AttendanceDecision | null;
  reason: string | null;
  grantedDays: number | null;
  token: string | null;
  includedBucketId: string | null;
  absenceDays?: number | null;
}

export interface EmploymentMonths extends Omit<EmploymentEmployee, 'id'> {
  employeeId: string;
  today: string;
  usableTo: string;
  settled: boolean;
  periods: MonthlyPeriod[];
  openingSources: OpeningSource[];
}

export interface ExitBucket {
  bucketId: string;
  sourceReference: string;
  usableFrom: string;
  usableTo: string | null;
  entitlementYear: number | null;
  openingDays: number;
  grantDays: number;
  carryDays: number;
  expireDays: number;
  usedDays: number;
  restoredDays: number;
  adjustmentDays: number;
  balanceDays: number;
  settlementDays: number;
  status: 'READY' | 'EMPTY' | 'UNKNOWN' | 'EXPIRED' | 'FUTURE' | 'NEGATIVE' | 'AFTER_EXIT';
}

export interface ExitReceipt {
  leftOn: string;
  settlementDays: number;
  reason: string;
}

export interface EmploymentExit extends Omit<EmploymentEmployee, 'id'> {
  employeeId: string;
  rows: ExitBucket[];
  balanceDays: number;
  settlementDays: number;
  missingMonths: string[];
  priorHistoryReviewRequired: boolean;
  blockers: string[];
  token: string | null;
  canPost: boolean;
  settlementId: string | null;
  reason: string | null;
}

export interface EmploymentLeaveModel {
  q: string;
  page: number;
  hasNext: boolean;
  employees: EmploymentEmployee[];
  today: string;
  publicDemo: boolean;
  monthly: EmploymentMonths | null;
  exit: EmploymentExit | null;
}

export const employmentPath = '/admin/leave-balances/employment';

export function employmentLink(q: string, page: number, employeeId?: string) {
  const query = new URLSearchParams();
  if (q) query.set('q', q);
  query.set('page', String(page));
  if (employeeId) query.set('employeeId', employeeId);
  return employmentPath + '?' + query.toString();
}
