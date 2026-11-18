import { ApprovalStamps, type ApprovalStep } from '../components/ApprovalStamps';
import type { ApprovalPreview } from './AnnualRequestTypes';

export function RequestApprovalPreview({ approval, applicant, checked }: {
  approval: ApprovalPreview;
  applicant?: {name: string; organization: string | null};
  checked?: boolean;
}) {
  const state = approval.eligible ? (checked ? '미리보기 확인' : '결재 가능') : '확인 필요';
  const tone = approval.eligible ? 'ok' : 'warn';
  const stages: ApprovalStep[] = approval.steps?.length ? approval.steps.map(step => ({
    role: step.role, name: step.approverName, detail: step.organizationName, state, tone,
  })) : [{role: '결재', name: approval.approverName ?? '미지정',
    detail: approval.routeName ?? (approval.source === 'LEGACY' ? '사원별 지정 승인자' : '미지정'), state, tone}];
  return <div className="wl-approval">
    {approval.formCode && <p>{approval.formName} ({approval.formCode}) · 결재 기준일 {approval.basisDate}</p>}
    <ApprovalStamps steps={applicant ? [{role:'기안', name:applicant.name, detail:applicant.organization, state:'작성 중'}, ...stages] : stages}/>
    {approval.message && approval.message !== state && <p className="wl-approval-note">{approval.message}</p>}
  </div>;
}

export function InlineRequestApproval({approval}: {approval?: ApprovalPreview}) {
  return approval && <section aria-label="신청서 결재선">
    <h3>결재선</h3>
    <RequestApprovalPreview approval={approval}/>
    {approval.routeToken && <input type="hidden" name="routeToken" value={approval.routeToken}/>}
  </section>;
}
