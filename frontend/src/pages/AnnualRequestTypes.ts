import type { Bucket, CorrectionForm, DaySnapshot, LeaveForm, RequestView } from './WorkflowTypes';
import type { EmployeeHeaderData } from '../components/EmployeeHeader';

export interface ApprovalPreview { source: string; routeName: string | null; approverName: string | null; eligible: boolean; message: string | null }
export interface TypeChoice { id: string; code: string; name: string; systemKind: string | null; supported: boolean }
export interface TypeSummary { typeId: string; code: string; name: string; kind: string; requestCount: number; pendingCount: number; approvedCount: number; rejectedCount: number; withdrawnCount: number; usedDays: number; plannedDays: number; pendingDays: number }
export interface BucketYear { bucketId: string; entitlementYear: number | null; classificationStatus: string; basis: string | null; usableFrom: string; usableTo: string | null; balanceDays: number; availableDays: number; openingAdjustmentDays: number; availability: string }
export interface AnnualView {
  year: number; today: string; asOf: string; typeId: string | null; status: string; page: number; hasNext: boolean;
  authoredPage: number; authoredHasNext: boolean; coverage: string; usedDays: number; plannedDays: number; pendingDays: number;
  currentAvailableDays: number; registeredDays: number | null; attributedBalanceDays: number | null; unknownBucketCount: number;
  attributionPartial: boolean; types: TypeChoice[]; summaries: TypeSummary[]; requests: RequestView[]; authoredRequests: RequestView[];
  attributionBuckets: BucketYear[]; canResubmitRequestIds: string[]; createdId: string | null; outsideYear: number | null;
}
export interface RequestPreview { quantityDays: number | null; availableDays: number; selectedBucketAvailableDays: number | null; policy: { code: string; name: string; revision: number } | null; approval: ApprovalPreview; eligible: boolean; message: string }
export interface ComposeView {
  mode: 'MODAL' | 'PAGE'; kind: 'LEAVE_DAY' | 'ATTENDANCE_CORRECTION'; leaveForm: LeaveForm; correctionForm: CorrectionForm;
  day: (DaySnapshot & { sourceFingerprint: string }) | null; preview: RequestPreview | null; approval: ApprovalPreview;
  currentCorrectionRequestId?: string | null; conflict: boolean;
}
export interface AnnualModel {
  annual: AnnualView; compose: ComposeView | null; buckets: Bucket[]; error?: string | null;
  employeeHeader?: Pick<EmployeeHeaderData, 'employeeName' | 'employeeNo' | 'organizationName'>;
  workspaceEmployee?: Pick<EmployeeHeaderData, 'employeeName' | 'companyName'>;
}
export interface Attribution { id: string; bucketId: string; revision: number; attendanceTypeId: string; classificationStatus: string; entitlementYear: number | null; basis: string; reason: string; createdBy: string; createdByName: string | null; createdAt: string }
export interface AttributionForm { expectedRevision: number; classificationStatus: string; entitlementYear: number | null; singleYearConfirmed: boolean; basis: string; reason: string; commandId: string }
export interface AttributionView { bucket: Bucket & { employeeName: string; baseDate: string; sourceReference: string }; history: Attribution[]; form: AttributionForm }
