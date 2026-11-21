import type { AttendanceDecision, ExitReceipt } from './employmentLeaveTypes';

export interface AnnualLeaveRule {
  version: number;
  fiscalStartMonth: number;
  grantDays: number;
  carryLimitDays: number;
}

export interface AnnualCycleModel {
  policy: AnnualLeaveRule | null;
  year: number;
  yearSelected: boolean;
  today: string;
  publicDemo: boolean;
}

export interface AnnualCycleRow {
  employeeId: string;
  employeeNo: string;
  employeeName: string;
  previousDays: number;
  grantDays: number;
  carryDays: number;
  expireDays: number;
}

export interface AnnualCyclePreview {
  year: number;
  periodStart: string;
  periodEnd: string;
  rule: AnnualLeaveRule;
  rows: AnnualCycleRow[];
  postedCount: number;
  existingCount: number;
  laterCount: number;
  pendingCount: number;
  token: string;
  canPost: boolean;
}

export interface PolicyCommand {
  expectedVersion: number;
  fiscalStartMonth: number;
  grantDays: number;
  carryLimitDays: number;
  reason: string;
  commandId: string;
}

export interface AnnualCycleCommand {
  year: number;
  token: string;
  reason: string;
  commandId: string;
}

export interface PersonalLedger {
  exitReceipt?: ExitReceipt | null;
  year: number;
  today: string;
  availableDays: number;
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
