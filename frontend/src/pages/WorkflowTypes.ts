import type { LeavePart } from './leavePart';
import type { WorkPolicy } from './selectiveSchedule';

export interface EmployeeChoice {
  id: string;
  employeeNo: string;
  displayName: string;
}
export interface Bucket {
  id: string;
  usableFrom: string;
  usableTo: string | null;
  balanceDays: number;
  availableDays: number;
  pendingDays: number;
}
export interface LeaveForm {
  leavePart?: LeavePart | null;
  bucketId: string | null;
  from: string;
  to: string;
  reason: string;
  commandId: string;
  predecessorRequestId?: string | null;
}
export interface RequestView {
  leavePart?: LeavePart | null;
  formCode?: string | null;
  formName?: string | null;
  currentStep?: number;
  totalSteps?: number;
  legacyForm?: boolean;
  id: string;
  targetEmployeeName: string;
  kind: string;
  fromDate: string;
  toDate: string;
  quantityDays: number;
  status: string;
  effectStatus: string;
  version: number;
  reason: string;
  submittedAt?: string | null;
  approverName: string;
  originalRequestId: string | null;
}
export interface RequestDay {
  leaveDate: string;
  quantityDays: number;
  zeroReason: string | null;
}
export interface CorrectionDetail {
  inAction: string;
  requestedInAt: string | null;
  outAction: string;
  requestedOutAt: string | null;
  presenceAsWork?: boolean;
  breaksConfirmed: boolean;
  actualBreaks: string | null;
}
interface CorrectionTimes {
  in: string[] | null;
  out: string[] | null;
}
export interface ApprovalHistory {
  createdAt: string;
  eventKind: string;
  fromStatus: string | null;
  toStatus: string;
  reason: string;
}
export interface RequestDetail {
  overtime?: { workDate: string; startTime: string; endTime: string; requestedMinutes: number } | null;
  steps?: { stepNumber: number; role: string; originalApproverName: string; approverName: string;
    status: string; decidedByName: string | null; decidedAt: string | null; reason: string | null }[];
  reassignmentRequired?: boolean;
  scheduleChanges?: {leaveDate: string; original: string; current: string}[];
  days: RequestDay[];
  history: ApprovalHistory[];
  correction: CorrectionDetail | null;
  attendancePolicy?: { name: string } | null;
  correctionComparison?: {
    source: CorrectionTimes;
    current: CorrectionTimes;
    proposed: CorrectionTimes;
  } | null;
  routeSnapshot?: {
    routeName: string;
    scopeKind: string;
    approverKind: string;
    organizationName: string | null;
    resolvedApproverName: string;
  } | null;
}
export interface RequestDetailModel {
  cancelApproval?: import('./AnnualRequestTypes').ApprovalPreview;
  cancelReason?: string;
  exceptionCancelReason?: string;
  request: RequestView;
  detail: RequestDetail;
  leaveReturnTo?: string | null;
  monthCloseReturnTo?: string | null;
  leaveDecidedByName?: string | null;
  ownRequest?: boolean;
  workspacePrincipal?: { roles: string[] };
  admin: boolean;
  canDecide: boolean;
  canWithdraw: boolean;
  canCancel: boolean;
  canCancelException: boolean;
  pageCommand: string;
  candidates: EmployeeChoice[];
}
export interface MineModel {
  buckets: Bucket[];
  requests: RequestView[];
  form: LeaveForm;
}
export interface ListModel {
  title: string;
  requests: RequestView[];
  completed?: boolean;
  page?: number;
  hasNext?: boolean;
}
export interface ClockEvent {
  id: string;
  kind: string;
  occurredAt: string;
}
export interface ClockInterval {
  startAt: string;
  endAt: string;
}
export interface Schedule extends WorkPolicy {
  workday: boolean;
  startTime: string | null;
  endTime: string | null;
  breaks: { startTime: string; endTime: string }[];
}
export interface Confirmation {
  id: string;
  confirmedInputRevision: number;
  reviewRevision: number;
  presenceAsWork: boolean;
  breaksMatch: boolean;
  confirmedAt: string;
  breaks: ClockInterval[];
}
export interface DaySnapshot {
  employeeId: string;
  workDate: string;
  inputRevision: number;
  reviewRevision: number;
  schedule: Schedule | null;
  scheduleVersionId: string | null;
  calendarVersionId: string | null;
  dayKind: string;
  events: ClockEvent[];
  effectiveEvents: ClockEvent[] | null;
  confirmation: Confirmation | null;
}
export interface ConfirmationForm {
  workDate: string;
  baseInputRevision: number;
  expectedReviewRevision: number;
  sourceFingerprint: string;
  commandId: string;
  presenceAsWork: boolean;
  breaksMatch: boolean;
}
export interface ClockModel {
  today?: string;
  isToday?: boolean;
  canConfirm?: boolean;
  confirmationBreaks?: ClockInterval[];
  correctionSuggested?: boolean;
  inCommand?: string;
  outCommand?: string;
  day: DaySnapshot;
  eventTimes: Record<string, string>;
  confirmationForm: ConfirmationForm;
  actualWorkMillis?: number | null;
}
export interface CorrectionForm {
  targetEmployeeId: string | null;
  workDate: string;
  baseInputRevision: number;
  sourceFingerprint: string;
  commandId: string;
  inAction: string;
  requestedInAt: string;
  outAction: string;
  requestedOutAt: string;
  presenceAsWork: boolean;
  breaksConfirmed: boolean;
  actualBreaks: string;
  replacesRequestId: string | null;
  predecessorRequestId?: string | null;
  reason: string;
}
export interface CorrectionModel {
  approval?: import('./AnnualRequestTypes').ApprovalPreview;
  day: DaySnapshot;
  exceptional: boolean;
  actions: string[];
  eventTimes: Record<string, string>;
  checkoutOnly?: boolean;
  pendingCorrectionId?: string | null;
  form: CorrectionForm;
}
export interface BucketForm {
  employeeId: string;
  commandId: string;
  baseDate: string;
  usableFrom: string;
  usableTo: string | null;
  quantityDays: number | null;
  sourceReference: string;
  reason: string;
}
export interface BalancesModel {
  employee: EmployeeChoice;
  employees: EmployeeChoice[];
  buckets: Bucket[];
  form: BucketForm;
}
export interface AdjustmentForm {
  expectedVersion: number;
  commandId: string;
  effectiveOn: string;
  quantityDays: number | null;
  sourceReference: string;
  reason: string;
}
export interface LedgerEntry {
  id: string;
  entryKind: string;
  signedQuantityDays: number;
  effectiveOn: string;
  sourceReference: string;
  reason: string;
}
export interface LedgerModel {
  bucketId: string;
  ledger: LedgerEntry[];
  adjustment: AdjustmentForm;
}
export interface DayResult {
  status: string;
  actualWorkMillis: number | null;
  confirmedBreakMillis: number | null;
  over8hMillis: number | null;
  leaveDays: number;
  inputRevision: number;
  reviewRevision: number;
  reasons: string[];
}
export interface DayView {
  id: string | null;
  employeeId: string;
  employeeNo: string;
  employeeName: string;
  workDate: string;
  resultRevision: number;
  ruleVersion: string;
  calculatedAt: string;
  current: boolean;
  pending: boolean;
  approvedLeaveDays: number;
  employed: boolean;
  result: DayResult;
}
export interface PeriodResult {
  confirmedWorkMillis: number;
  unconfirmedDays: number;
  over40hMillis: number | null;
  reasons: string[];
}
export interface PeriodView {
  from: string;
  to: string;
  current: boolean;
  pendingDays: number;
  result: PeriodResult;
}
export interface MonthModel {
  overview: { month: string; days: DayView[]; weeks: PeriodView[]; summary: PeriodView };
  employeeId: string;
  employees: EmployeeChoice[];
  admin: boolean;
  from: string;
  to: string;
  calculateCommand: string;
  exportCommand: string;
  checkoutWorkDate?: string;
  checkoutReturnTo?: string;
}
export interface PolicyForm {
  from: string;
  to: string;
  mondayWeekReviewed: boolean;
  basis: string;
  reason: string;
  commandId: string;
}
export interface AssessmentForm {
  employeeId: string;
  from: string;
  to: string;
  generalRuleReviewed: boolean;
  basis: string;
  reason: string;
  commandId: string;
}
export interface PolicyEvidence {
  id: string;
  revision: number;
  validFrom: string;
  validTo: string | null;
  mondayWeekReviewed: boolean;
}
export interface AssessmentEvidence {
  id: string;
  revision: number;
  validFrom: string;
  validTo: string | null;
  generalRuleReviewed: boolean;
}
export interface PoliciesModel {
  employees: EmployeeChoice[];
  employeeId: string;
  policies: (PolicyEvidence & { basis: string })[];
  assessments: (AssessmentEvidence & { basis: string })[];
  policyForm: PolicyForm;
  assessmentForm: AssessmentForm;
}
export interface RunEvidence {
  id: string;
  requestedBy: string;
  fromDate: string;
  toDate: string;
  expandedFrom: string;
  expandedTo: string;
  engineVersion: string;
  status: string;
  startedAt: string;
  finishedAt: string | null;
}
export interface ResultDetail {
  day: DayView;
  latestVersion: boolean;
  latestResultId: string | null;
  completeEvidence: boolean;
  run: RunEvidence | null;
  clock: DaySnapshot | null;
  policy: PolicyEvidence | null;
  assessment: AssessmentEvidence | null;
  employment: { joinedOn: string; leftOn: string | null } | null;
  leaveEffects: string[];
  pendingCorrections: string[];
}
export interface ResultModel {
  detail: ResultDetail;
  times: Record<string, string>;
  month: string;
  admin: boolean;
  workspacePrincipal?: { roles: string[] };
}
export interface AuditRow {
  id: string;
  at: string;
  action: string;
  targetKind: string;
  targetId: string;
  actorAccountId: string;
  reason: string;
}
export interface AuditModel {
  audits: AuditRow[];
  page: { previousCursor: string | null; nextCursor: string | null };
}
export interface AuditDetail extends AuditRow {
  commandId: string;
  requestTraceId: string;
  beforeSummary: Record<string, string>;
  afterSummary: Record<string, string>;
}
