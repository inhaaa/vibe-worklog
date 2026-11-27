import type { AttendanceDecision, ExitReceipt, MonthlyPeriod } from './employmentLeaveTypes';

export type AnnualLeaveBasis = 'FISCAL' | 'HIRE_DATE';
export const basisNames: Record<AnnualLeaveBasis, string> = { FISCAL: '회계연도 기준', HIRE_DATE: '입사일 기준' };

export interface AnnualLeaveRule {
  version: number;
  basis: AnnualLeaveBasis;
  fiscalStartMonth: number;
  grantDays: number;
  carryLimitDays: number;
}

export interface AnnualCycleModel {
  policy: AnnualLeaveRule | null;
  year: number;
  yearSelected: boolean;
  month: string;
  today: string;
  publicDemo: boolean;
}

export interface AnnualCycleRow {
  employeeId: string;
  employeeNo: string;
  employeeName: string;
  joinedOn: string;
  grantOn: string;
  serviceYear: number;
  periodStart: string;
  periodEnd: string;
  scheduledDays: number;
  absenceDays: number;
  unresolvedDays: number;
  attendanceRate: number | null;
  decision: 'GRANT' | 'NEEDS_REVIEW';
  reason: string | null;
  previousDays: number;
  grantDays: number;
  carryDays: number;
  expireDays: number;
}

export interface AnnualCyclePreview {
  year: number;
  month: string | null;
  periodStart: string;
  periodEnd: string;
  rule: AnnualLeaveRule;
  rows: AnnualCycleRow[];
  postedCount: number;
  existingCount: number;
  laterCount: number;
  pendingCount: number;
  reviewCount: number;
  token: string;
  canPost: boolean;
}

export interface PolicyCommand {
  expectedVersion: number;
  basis: AnnualLeaveBasis;
  fiscalStartMonth: number | null;
  carryLimitDays: number;
  reason: string;
  commandId: string;
}

export interface AnnualCycleCommand {
  year?: number;
  month?: string;
  token: string;
  reason: string;
  reviewedEmployeeIds: string[];
  commandId: string;
}

export type MonthlyBulkDecision = 'FULL' | 'NOT_FULL' | 'NEEDS_REVIEW';
export interface MonthlyBulkRow {
  employeeId: string;
  employeeNo: string;
  employeeName: string;
  joinedOn: string;
  period: MonthlyPeriod;
  decision: MonthlyBulkDecision;
  finding: string;
}
export interface MonthlyBulkPreview {
  today: string;
  rows: MonthlyBulkRow[];
  fullCount: number;
  notFullCount: number;
  reviewCount: number;
  token: string;
  canPost: boolean;
}

export interface PersonalLedger {
  exitReceipt?: ExitReceipt | null;
  year: number;
  today: string;
  availableDays: number;
  basis: AnnualLeaveBasis;
  periodStart: string;
  periodEnd: string;
  entries: {
    id: string;
    bucketId: string;
    employeeNo: string;
    entitlementYear: number | null;
    entryKind: string;
    monthlyDecision?: AttendanceDecision | null;
    signedQuantityDays: number;
    effectiveOn: string;
    leaveDate: string | null;
    sourceReference: string | null;
    reason: string;
  }[];
}
