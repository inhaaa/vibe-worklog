import React, { useId, useState } from 'react';
import './RequestDetail.css';
import './RequestWorkspace.css';
import './RecordDetails.css';
import { ApprovalWorkspace } from './ApprovalWorkspace';
import { requestKindName, leavePartNames, type LeavePart } from './leavePart';
import { LeaveOperationsPage, type LeaveOperations } from './LeaveOperationsPage';
import { LeaveBalanceBatch } from './LeaveBalanceBatch';
import { SelectiveSettlements } from './SelectiveSettlements';
import { selectiveHours, selectiveReasonLabels } from './selectiveSchedule';
import { RequestBreakEditor } from '../components/RequestBreakEditor';
import { RequestDateTimeInput } from '../components/RequestDateTimeInput';
import type { CsrfToken, PageProps } from '../types';
import { WorklogSheet, sheetColumn } from '../components/WorklogSheet';
import { PageHeader } from '../components/PageHeader';
import { AttendancePeriodPage, type AttendancePeriod } from './AttendancePeriodPage';
import { AttendanceListBackLink, attendanceListReturn } from './CompanyAttendanceNavigation';
import { LeaveOperationsBackLink, leaveOperationsReturn } from './LeaveOperationsNavigation';
import { ApprovalStamps } from '../components/ApprovalStamps';
import { InlineRequestApproval } from './RequestApprovalPreview';
import { Notice } from '../components/Notice';
import {
  Form,
  FormRow,
  FormTable,
  Field,
  Check,
  SelectField,
  TextAreaField,
  ErrorNotice,
  fmtDate,
  fmtTime,
  fmtDateTime,
  duration,
} from '../ui';
import type {
  EmployeeChoice,
  Bucket,
  RequestView,
  RequestDetailModel,
  MineModel,
  ListModel,
  ClockModel,
  ClockEvent,
  CorrectionModel,
  BalancesModel,
  LedgerModel,
  LedgerEntry,
  MonthModel,
  DayView,
  PeriodView,
  PoliciesModel,
  ResultModel,
  AuditModel,
  AuditRow,
  AuditDetail,
  DaySnapshot,
} from './WorkflowTypes';

const hidden = (name: string, value: string | number | boolean | null | undefined) => (
  <input type="hidden" name={name} value={value == null ? '' : String(value)} />
);
const bucketPeriod = (b: Bucket) =>
  `${fmtDate(b.usableFrom)} ~ ${b.usableTo == null ? '종료 없음' : fmtDate(b.usableTo)}`;
const EmployeeOptions = ({ employees }: { employees: EmployeeChoice[] }) => (
  <>
    {employees.map((e) => (
      <option key={e.id} value={e.id}>
        {e.employeeNo} · {e.displayName}
      </option>
    ))}
  </>
);
const Reason = ({ label = '사유', value }: { label?: string; value?: string }) => (
  <TextAreaField label={label} name="reason" value={value} maxLength={500} required />
);
const Summary = ({ entries }: { entries: Record<string, string> }) => (
  <dl className="wl-form-table">
    {Object.entries(entries ?? {}).map(([key, value]) => (
      <React.Fragment key={key}>
        <dt>{key}</dt>
        <dd>{value}</dd>
      </React.Fragment>
    ))}
  </dl>
);
function previousDay(iso: string): string {
  const value = new Date(`${iso}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() - 1);
  return value.toISOString().slice(0, 10);
}
function storedIntervals(value: string | null): string {
  if (value == null) return '미확인';
  try {
    const parsed: unknown = JSON.parse(value);
    if (!Array.isArray(parsed)) return '확인 필요';
    const intervals: string[] = [];
    for (const interval of parsed as unknown[]) {
      if (
        typeof interval !== 'object' ||
        interval == null ||
        !('startAt' in interval) ||
        !('endAt' in interval)
      )
        return '확인 필요';
      const { startAt, endAt } = interval;
      if (
        typeof startAt !== 'string' ||
        typeof endAt !== 'string' ||
        !/T.*(?:Z|[+-]\d\d:\d\d)$/.test(startAt) ||
        !/T.*(?:Z|[+-]\d\d:\d\d)$/.test(endAt)
      )
        return '확인 필요';
      const start = Date.parse(startAt),
        end = Date.parse(endAt);
      if (!Number.isFinite(start) || !Number.isFinite(end) || start >= end) return '확인 필요';
      intervals.push(`${fmtDateTime(startAt)} ~ ${fmtDateTime(endAt)}`);
    }
    return intervals.length ? intervals.join('; ') : '입력된 휴게 없음';
  } catch {
    return '확인 필요';
  }
}

const requestName = (value: string, names: Record<string, string>) => names[value] ?? value;
const requestStateName = (status: string) => requestName(status, { PENDING: '결재 대기', APPROVED: '승인', REJECTED: '반려', WITHDRAWN: '철회' });
const requestEffectName = (effect: string) => requestName(effect, { EFFECTIVE: '반영', CANCELED: '취소', SUPERSEDED: '대체', NONE: '미반영' });

function RequestRows({ requests, pagination }: { requests: RequestView[]; pagination?: React.ReactNode }) {
  const columns = [
    sheetColumn<RequestView>({ field: 'targetEmployeeName', headerName: '대상', minWidth: 170, pinned: 'left' }, request => request.targetEmployeeName),
    sheetColumn<RequestView>({ field: 'kind', headerName: '종류', minWidth: 170, valueGetter: ({ data }) => data && requestKindName(data.kind,data.leavePart) }, request => <a href={`/requests/${request.id}`}>{requestKindName(request.kind,request.leavePart)}</a>),
    sheetColumn<RequestView>({ field: 'fromDate', headerName: '기간', minWidth: 230, valueGetter: ({ data }) => data ? `${fmtDate(data.fromDate)} ~ ${fmtDate(data.toDate)}` : '' }, request => `${fmtDate(request.fromDate)} ~ ${fmtDate(request.toDate)}`),
    sheetColumn<RequestView>({ colId: 'state', headerName: '상태·효력', minWidth: 190, valueGetter: ({ data }) => data ? `${requestStateName(data.status)} / ${requestEffectName(data.effectStatus)}` : '' }, request => `${requestStateName(request.status)} / ${requestEffectName(request.effectStatus)}`),
    sheetColumn<RequestView>({ field: 'approverName', headerName: '승인자', minWidth: 150, flex: 1 }, request => request.status === 'PENDING' && request.totalSteps ? `${request.approverName} · ${request.currentStep}/${request.totalSteps}단계` : request.approverName),
  ];
  return <WorklogSheet label="근태 신청 목록" rows={requests} columns={columns} getRowId={request => request.id} emptyMessage="신청이 없습니다." pagination={pagination} />;
}
function Mine({ model: m }: { model: MineModel }) {
  const f = m.form;
  const [part, setPart] = useState<LeavePart>(f.leavePart ?? 'FULL');
  const [from, setFrom] = useState(f.from);
  return (
    <>
      <h1>내 근태 신청</h1>
      <p>
        <a href="/me/requests/correction">출퇴근·실제 휴게 정정 신청</a>
      </p>
      <section className="panel">
        <h2>연차 잔여와 대기량</h2>
        <p>담당자가 등록한 기초 잔여와 조정은 0.5일 단위입니다. 대기는 예약·차감하지 않습니다.</p>
        <ul>
          {m.buckets.map((b) => (
            <li key={b.id}>
              {bucketPeriod(b)} · 현재 잔여 {b.balanceDays} / 사용 가능 {b.availableDays} / 대기{' '}
              {b.pendingDays}일
            </li>
          ))}
        </ul>
      </section>
      <section className="panel">
        <h2>연차 신청</h2>
        <Form action="/me/requests/leave" method="post" className="admin-form">
          {hidden('commandId', f.commandId)}
          {f.predecessorRequestId && hidden('predecessorRequestId', f.predecessorRequestId)}
          <SelectField label="기초 묶음" name="bucketId" value={f.bucketId ?? ''} required>
            <option value="">선택하세요</option>
            {m.buckets.map((b) => (
              <option key={b.id} value={b.id}>
                {bucketPeriod(b)} / 사용 가능 {b.availableDays}일
              </option>
            ))}
          </SelectField>
          <SelectField label="휴가 단위" name="leavePart" value={part} onChange={event => setPart(event.target.value as LeavePart)}>
            {Object.entries(leavePartNames).map(([value,name]) => <option key={value} value={value}>{name}</option>)}
          </SelectField>
          <Field label={part === 'FULL' ? '시작일' : '반차 사용일'} name="from" type="date" value={from} onChange={event => setFrom(event.target.value)} required />
          {part === 'FULL' ? <Field label="종료일 (포함)" name="to" type="date" value={f.to} required /> : hidden('to',from)}
          <Reason value={f.reason} />
          <p className="hint">
            민감정보를 적지 마세요. 일정·휴일·유효기간은 승인 때 다시 검증합니다.
          </p>
          <button>연차 신청 제출</button>
        </Form>
      </section>
      <section className="panel">
        <h2>신청 이력</h2>
        <RequestRows requests={m.requests} />
      </section>
    </>
  );
}
function RequestDetailPage({ model: m }: { model: RequestDetailModel }) {
  const r = m.request,
    d = m.detail;
  const overtime = r.kind === 'OVERTIME' || r.kind === 'CANCEL_OVERTIME';
  const isLeave = r.kind === 'LEAVE_DAY' || r.kind === 'CANCEL_LEAVE';
  const staffLeave = m.admin && isLeave;
  const leaveReturn = staffLeave ? leaveOperationsReturn(m.leaveReturnTo) : null;
  const leaveTypeName = isLeave ? d.attendancePolicy?.name : null;
  const hasLeaveDecision = staffLeave && (r.status === 'APPROVED' || r.status === 'REJECTED');
  const label = (value: string, names: Record<string, string>) => Object.hasOwn(names, value) ? names[value] : value;
  const kindName = requestKindName(r.kind,r.leavePart);
  const statusName = (value: string) => label(value, { PENDING: '결재 대기', APPROVED: '승인', REJECTED: '반려', WITHDRAWN: '철회' });
  const effectName = label(r.effectStatus, { NONE: '효력 없음', EFFECTIVE: '유효', CANCELED: overtime ? '취소됨' : '효력 취소', SUPERSEDED: '후속 정정으로 대체' });
  const eventName = (value: string) => label(value, { SUBMITTED: '신청 제출', STEP_APPROVED: '중간 승인', APPROVED: '승인', REJECTED: '반려', WITHDRAWN: '신청 철회', REASSIGNED: '결재 담당 재할당' });
  const steps = d.steps ?? [];
  const activeStep = steps.find(step => step.status === 'ACTIVE');
  const intermediate = r.status === 'PENDING' && (r.currentStep ?? 1) < (r.totalSteps ?? 1);
  const stepState = (s: string) => label(s, { ACTIVE:'결재 중', WAITING:'예정', APPROVED:'승인', REJECTED:'반려', CLOSED:'진행 종료' });
  const correctionAction = (value: string) => label(value, { KEEP: '유지', SET: '변경', CLEAR: '제거' });
  const comparison = d.correctionComparison;
  const correctionTimes = (times: string[] | null) =>
    times == null ? '확인 필요' : times.length ? times.map(fmtDateTime).join(', ') : '미기록';
  const zeroReason = (value: string) => label(value, { CONFIRMED_HOLIDAY: '확정 휴일', CONFIRMED_NON_WORKDAY: '확정 비근로일' });
  const hasActions = m.canDecide || m.canWithdraw || m.canCancel || m.canCancelException || (m.admin && r.status === 'PENDING');
  const completedApproval = m.ownRequest === false && m.workspacePrincipal?.roles.includes('APPROVER')
    && (r.status === 'APPROVED' || r.status === 'REJECTED' || (steps.length > 1 && !m.canDecide && !m.admin));
  const versionFields = (
    <>
      {hidden('expectedVersion', r.version)}
      {r.currentStep != null && hidden('expectedStep', r.currentStep)}
      {hidden('commandId', m.pageCommand)}
    </>
  );
  const approvalTone: 'ok' | 'danger' | 'warn' | undefined = r.status === 'APPROVED' ? 'ok' : r.status === 'REJECTED' ? 'danger' : r.status === 'PENDING' ? 'warn' : undefined;
  return (
    <div className="t-request-detail-page">
      <PageHeader title="신청 상세" help="신청 내용과 처리 이력을 확인하세요."
        actions={<div className="t-request-detail-backlinks">
          {m.admin && <AttendanceListBackLink href={m.monthCloseReturnTo ?? undefined} />}
          <LeaveOperationsBackLink href={leaveReturn} />
          {m.canDecide ? <a className="t-btn t-request-detail-back" href="/approvals">대기함으로</a>
            : completedApproval && <a className="t-btn t-request-detail-back" href="/approvals/completed">내가 처리한 결재</a>}
          {overtime ? <>
            {m.admin && <a className="t-btn t-request-detail-back" href="/admin/overtime">연장근무 신청현황</a>}
            {m.ownRequest && <a className="t-btn t-request-detail-back" href="/me/overtime">내 연장근무 신청</a>}
          </> : <a className="t-btn t-request-detail-back" href="/me/requests">내 신청 </a>}
        </div>} />
      <div className="t-request-detail-document">
        <section className="t-card wl-detail-approval" aria-labelledby="request-record-approval-title">
          <h2 id="request-record-approval-title">결재 진행</h2>
          {r.formCode && <p>{r.formName} ({r.formCode}){r.legacyForm && ' · 기존 신청'}</p>}
          {activeStep && <p>{activeStep.stepNumber}/{steps.length} {activeStep.role} 결재 중{d.reassignmentRequired && ' · 재할당 필요'}</p>}
          <ApprovalStamps steps={[
            { role: '대상 사원', name: r.targetEmployeeName, detail: d.routeSnapshot?.organizationName, state: '제출' },
            ...(steps.length ? steps.map(step => ({
              role: step.role, name: step.decidedByName || step.approverName,
              detail: step.decidedAt ? fmtDateTime(step.decidedAt) : undefined, state: stepState(step.status),
            })) : [{ role: '결재', name: r.approverName || '미지정', detail: d.routeSnapshot?.routeName, state: statusName(r.status), tone: approvalTone }]),
          ]} />
        </section>
        {d.routeSnapshot ? <section className="t-card t-request-route t-request-record-routing" aria-labelledby="request-record-route-title">
          <h2 id="request-record-route-title">제출 당시 결재선</h2>
          <dl className="wl-form-table">
            <dt>적용 범위</dt><dd>{label(d.routeSnapshot.scopeKind, { EMPLOYEE: '사원별', ORGANIZATION: '조직별', FORM: '신청서별' })}</dd>
            <dt>결재 방식</dt><dd>{label(d.routeSnapshot.approverKind, { ORGANIZATION_HEAD: '대상자의 현재 조직장', DIRECT_EMPLOYEE: '지정 사원', ORGANIZATION_CHAIN: '조직장 순차결재' })}</dd>
            {d.routeSnapshot.organizationName && <><dt>제출 당시 소속</dt><dd>{d.routeSnapshot.organizationName}</dd></>}
            <dt>최초 결재 담당</dt><dd>{steps.length > 1 ? steps.map(step => step.role+' '+step.originalApproverName).join(' → ') : d.routeSnapshot.resolvedApproverName}</dd>
          </dl>
        </section> : <section className="t-card t-request-route t-request-record-routing" aria-labelledby="request-record-route-title"><h2 id="request-record-route-title">제출 당시 결재선</h2>{steps.length ? <ol>{steps.map(step=><li key={step.stepNumber}>{step.role} · {step.originalApproverName}</li>)}</ol> : <p className="hint">제출 당시 기존 사원별 지정 승인자를 사용한 신청입니다.</p>}</section>}
        <section className="t-card t-request-detail-summary" aria-labelledby="request-record-application-title">
          <div className="t-request-detail-summary-head">
            <div><h2 id="request-record-application-title">신청서</h2><p className="t-request-detail-person">{r.targetEmployeeName} · {kindName}</p></div>
            <span className="t-request-detail-status" data-status={r.status} aria-label="현재 상태">{statusName(r.status)}</span>
          </div>
          <p className="t-request-detail-effect">현재 효력 <strong>{effectName}</strong></p>
          <div className="t-request-detail-overview">
            <section className="t-request-detail-fact">
              <h3>신청 기간</h3>
              <p className="t-request-detail-dates"><span>{fmtDate(r.fromDate)}</span><span aria-hidden="true">~</span><span>{fmtDate(r.toDate)}</span></p>
              {isLeave && r.quantityDays != null && <p className="t-request-detail-quantity">{r.quantityDays}일</p>}
              {d.overtime && <p className="t-request-detail-quantity">{d.overtime.startTime.slice(0,5)}~{d.overtime.endTime.slice(0,5)} · {d.overtime.requestedMinutes}분</p>}
              {leaveTypeName && <p><span className="t-muted">휴가 종류 </span><strong>{leaveTypeName}</strong></p>}
              {staffLeave && r.submittedAt && <p><span className="t-muted">접수 시각 </span><time dateTime={r.submittedAt}>{fmtDateTime(r.submittedAt)}</time></p>}
            </section>
            <section className="t-request-detail-fact"><h3>신청 사유</h3><p className="t-request-detail-reason">{r.reason}</p></section>
            <section className="t-request-detail-fact">
              <h3>결재 담당</h3>
              <p className="t-request-detail-person">현재 결재 담당 {r.approverName}</p>
              {hasLeaveDecision && <p><span className="t-muted">실제 처리자 </span><strong>{m.leaveDecidedByName?.trim() || '미확인'}</strong></p>}
              <p className="t-muted">최초 담당은 제출 당시 결재선에서 확인하세요.</p>
            </section>
          </div>
          {r.kind === 'CANCEL_OVERTIME' && r.status === 'PENDING' && <Notice>취소 결재 중에는 원래 승인한 연장근무가 유효합니다.</Notice>}
          {r.kind === 'CANCEL_LEAVE' && r.status === 'PENDING' && <Notice>취소 심사 중에는 원승인·차감이 유지됩니다.</Notice>}
          {r.originalRequestId && <a className="t-btn t-request-detail-original" href={`/requests/${r.originalRequestId}${leaveReturn
            ? '?' + new URLSearchParams({ returnTo: leaveReturn }) : ''}`}>원신청 보기 </a>}
        </section>
      </div>
      <div className="t-request-detail-columns">
        {(d.days.length > 0 || d.correction) && <div className="t-request-detail-main">
          {d.days.length > 0 && <section className="t-card">
            <h2>날짜별 산정</h2>
            <ul className="t-request-detail-days">
              {d.days.map((day, i) => <li key={`${day.leaveDate}-${i}`}><span>{fmtDate(day.leaveDate)}</span>{r.kind === 'LEAVE_DAY' && <strong>{day.quantityDays}일</strong>}{day.zeroReason && <span className="t-muted">{zeroReason(day.zeroReason)}</span>}</li>)}
            </ul>
          </section>}
          {!!d.scheduleChanges?.length && <section className="t-card">
            <h2>승인 후 근무 일정 변경</h2>
            <p>원신청의 차감 내역과 변경된 근무일을 확인하세요.</p>
            {d.scheduleChanges.map(change => <div key={change.leaveDate}>
              <h3>{fmtDate(change.leaveDate)}</h3>
              <dl><dt>승인 당시 일정</dt><dd>{change.original}</dd>
                <dt>현재 일정</dt><dd>{change.current}</dd></dl>
            </div>)}
          </section>}
          {d.correction && <section className="t-card">
            <h2>요청한 정정</h2>
            {comparison && <table className="t-correction-comparison" aria-label="출퇴근 시각 비교">
              <thead><tr><th scope="col">구분</th><th scope="col">출근 시각</th><th scope="col">퇴근 시각</th></tr></thead>
              <tbody>
                {([['원천 기록', comparison.source], ['현재 유효 기록', comparison.current], ['제안 기록', comparison.proposed]] as const)
                  .map(([heading, times]) => <tr key={heading}>
                    <th scope="row">{heading}</th><td>{correctionTimes(times.in)}</td><td>{correctionTimes(times.out)}</td>
                  </tr>)}
              </tbody>
            </table>}
            <dl className="wl-form-table">
              <dt>출근</dt><dd>출근 {correctionAction(d.correction.inAction)}{d.correction.inAction === 'SET' && ` ${fmtDateTime(d.correction.requestedInAt)}`}</dd>
              <dt>퇴근</dt><dd>퇴근 {correctionAction(d.correction.outAction)}{d.correction.outAction === 'SET' && ` ${fmtDateTime(d.correction.requestedOutAt)}`}</dd>
              <dt>실제 근로 확인</dt><dd>{d.correction.presenceAsWork ? '확인' : '미확인'}</dd>
              <dt>실제 휴게</dt><dd>{d.correction.breaksConfirmed ? storedIntervals(d.correction.actualBreaks) : '미확인'}</dd>
            </dl>
          </section>}
        </div>}
        <div className="t-request-detail-side">
          <section className="t-card">
            <div className="t-request-detail-section-head"><h2>처리 이력</h2><span className="t-muted">{d.history.length}건</span></div>
            {d.history.length === 0 ? <p className="t-request-detail-empty">저장된 처리 이력이 없습니다.</p> : <ol className="t-request-detail-timeline">
              {d.history.map((h, i) => <li key={i}>
                <div className="t-request-detail-event"><strong>{eventName(h.eventKind)}</strong><time dateTime={h.createdAt}>{fmtDateTime(h.createdAt)}</time></div>
                <p className="t-request-detail-transition">{h.fromStatus ? `${statusName(h.fromStatus)} → ` : ''}{statusName(h.toStatus)}</p>
                <p className="t-request-detail-reason">{h.reason}</p>
              </li>)}
            </ol>}
          </section>
          <section className={`t-card t-request-detail-actions${m.canDecide ? ' t-request-decision-panel' : ''}`}>
            <h2>{m.canDecide ? '결재 검토' : '결정·변경'}</h2>
            {m.canDecide && <p className="t-request-decision-guide">신청 내용과 산정 근거를 확인해 주세요. 현재 권한·신청 상태는 처리할 때 다시 검증하며, 결정 사유는 처리 이력에 남습니다.</p>}
            {!hasActions && <p className="t-request-detail-empty">현재 가능한 결정·변경이 없습니다.</p>}
            {m.canDecide && (
              <>
                <Form action={`/requests/${r.id}/approve`} method="post" className="wl-form wl-decision-form" aria-label="승인 검토">
                  <h3>승인</h3>
                  <p>{intermediate ? '승인하면 다음 결재자에게 전달됩니다. 신청은 최종 승인까지 대기로 남습니다.' : r.kind === 'LEAVE_DAY' ? '승인하면 신청 상태와 실제 연차 차감이 함께 반영됩니다.' : '승인하면 신청한 내용의 효력이 반영됩니다.'}</p>
                  {versionFields}
                  <FormTable><Reason label="승인 검토 사유" /></FormTable>
                  <div className="wl-form-actions"><button className="t-btn primary">승인하기</button></div>
                </Form>
                <Form action={`/requests/${r.id}/reject`} method="post" className="wl-form wl-decision-form" aria-label="반려 검토">
                  <h3>반려</h3>
                  <p>보완이 필요한 내용을 사유로 남겨 주세요.</p>
                  {versionFields}
                  <FormTable><Reason label="반려 사유" /></FormTable>
                  <div className="wl-form-actions"><button className="t-btn secondary">반려하기</button></div>
                </Form>
              </>
            )}
            {m.canWithdraw && (
              <Form action={`/requests/${r.id}/withdraw`} method="post" className="wl-form wl-decision-form">
                {versionFields}
                <FormTable><Reason label="철회 사유" /></FormTable>
                <div className="wl-form-actions"><button className="t-btn secondary">대기 신청 철회</button></div>
              </Form>
            )}
            {m.canCancel && (
              <Form action={`/requests/${r.id}/cancellations`} method="post" className="wl-form wl-decision-form">
                {versionFields}
                <FormTable>
                  <Reason label="취소 요청 사유" value={m.cancelReason}/>
                  <p>
                    {overtime ? '예정 시작 전까지 승인된 구간 전체의 취소를 요청할 수 있습니다.' : '예정 시작 전 본인 요청만 일반 취소를 지원합니다. 지난 날짜는 담당자 예외 경로가 필요합니다.'}
                  </p>
                </FormTable>
                <InlineRequestApproval approval={m.cancelApproval}/>
                <div className="wl-form-actions"><button className="t-btn secondary" disabled={m.cancelApproval?.eligible === false}>취소 심사 요청</button></div>
              </Form>
            )}
            {m.admin && r.status === 'PENDING' && (
              <Form action={`/admin/requests/${r.id}/reassign`} method="post" className="wl-form wl-decision-form">
                <h3>대기 건 명시 재할당</h3>
                {versionFields}
                <FormTable>
                  <SelectField label="새 승인자" name="approverEmployeeId" required>
                    {(m.candidates ?? []).map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.displayName}
                      </option>
                    ))}
                  </SelectField>
                  <Reason label="재할당 사유" />
                </FormTable>
                <div className="wl-form-actions"><button className="t-btn">재할당 저장</button></div>
              </Form>
            )}
            {m.canCancelException && (
              <Form
                action={`/admin/requests/${r.id}/cancellation-exceptions`}
                method="post"
                className="wl-form wl-decision-form"
              >
                <h3>과거·퇴사자 예외 취소</h3>
                {versionFields}
                <FormTable>
                  <Reason label="검토 근거와 예외 취소 사유" value={m.exceptionCancelReason}/>
                  <p>다른 적격 승인자의 독립 승인이 필요합니다.</p>
                </FormTable>
                <InlineRequestApproval approval={m.cancelApproval}/>
                <div className="wl-form-actions"><button className="t-btn" disabled={m.cancelApproval?.eligible === false}>예외 취소 심사 요청</button></div>
              </Form>
            )}
          </section>
        </div>
      </div>
    </div>
  );
}
function Correction({ model: m }: { model: CorrectionModel }) {
  const f = m.form;
  const timeFieldId = useId();
  const [requestedInAt, setRequestedInAt] = useState(f.requestedInAt);
  const [requestedOutAt, setRequestedOutAt] = useState(f.requestedOutAt);
  const [breaks, setBreaks] = useState(f.actualBreaks ?? '');
  const [dirty, setDirty] = useState(Boolean(f.reason));
  const checkout = m.checkoutOnly === true;
  const events = checkout ? m.day.effectiveEvents ?? m.day.events : m.day.events;
  const actionName = (action: string) => ({ KEEP: '유지', SET: '시각 수정', CLEAR: '누락으로 정정' }[action] ?? action);
  return (
    <div className="t-correction-workspace">
      <PageHeader title={checkout ? '퇴근 누락 정정 신청' : '출퇴근·실제 휴게 정정 신청'}
        help="확인한 기록과 실제 근로 내용을 입력해 주세요."
        actions={checkout && !m.pendingCorrectionId && <a className="t-btn" href={`/me/requests/correction?date=${f.workDate}&mode=full`}>일반 정정 양식</a>} />
      {m.exceptional && (
        <p className="notice">
          담당자 예외 신청입니다. 작성자·대상자와 다른 적격 승인자의 독립 승인이 필요합니다.
        </p>
      )}
      <section className="wl-section t-correction-source" aria-label="현재 출퇴근 기록">
        <h2>현재 출퇴근 기록</h2>
        {checkout && <p>대상 근무일 <strong>{fmtDate(f.workDate)}</strong></p>}
        <ul className="t-correction-events">
          {events.map((e) => (
            <li key={e.id}>
              <span>{e.kind === 'IN' ? '출근' : e.kind === 'OUT' ? '퇴근' : e.kind}</span><strong>{m.eventTimes?.[e.id] ?? fmtDateTime(e.occurredAt)}</strong>
            </li>
          ))}
          {!events.length && <li>출퇴근 기록이 없습니다.</li>}
          {checkout && <li><span>퇴근</span><strong>미기록</strong></li>}
        </ul>
      </section>
      {m.pendingCorrectionId ? <section className="wl-section">
        <h2>결재 대기</h2>
        <a className="t-btn" href={`/requests/${m.pendingCorrectionId}`}>대기 중인 정정 보기</a>
      </section> : <Form
        action={m.exceptional ? '/admin/requests/exceptions' : checkout ? '/me/requests/correction?mode=checkout' : '/me/requests/correction'}
        method="post"
        className="wl-form t-correction-form"
        onChange={() => setDirty(true)}
        data-unsaved-changes={String(dirty)}
      >
        {hidden('targetEmployeeId', f.targetEmployeeId)}
        {hidden('workDate', f.workDate)}
        {hidden('baseInputRevision', f.baseInputRevision)}
        {hidden('sourceFingerprint', f.sourceFingerprint)}
        {hidden('commandId', f.commandId)}
        {hidden('exceptional', m.exceptional)}
        {f.predecessorRequestId && hidden('predecessorRequestId', f.predecessorRequestId)}
        {hidden('replacesRequestId', f.replacesRequestId)}
        <section className="wl-section">
          {checkout ? <>
            <h2>퇴근 시각 입력</h2>
            <p className="hint">기존 출근 시각을 유지합니다.</p>
            {hidden('inAction', 'KEEP')}
            {hidden('requestedInAt', '')}
            {hidden('outAction', 'SET')}
            <FormTable>
              <FormRow label="실제 퇴근 시각" htmlFor={`${timeFieldId}-out`} required>
                <RequestDateTimeInput id={`${timeFieldId}-out`} name="requestedOutAt" value={requestedOutAt} onChange={setRequestedOutAt} required />
              </FormRow>
              <Reason label="정정 사유와 사실 근거" value={f.reason} />
            </FormTable>
          </> : <>
            <h2>출퇴근 변경</h2>
            <FormTable columns={2}>
              <SelectField label="출근 변경" name="inAction" value={f.inAction}>
                {m.actions.map((a) => <option key={a} value={a}>{actionName(a)}</option>)}
              </SelectField>
              <FormRow label="출근 시각" htmlFor={`${timeFieldId}-in`}>
                <RequestDateTimeInput id={`${timeFieldId}-in`} name="requestedInAt" value={requestedInAt} onChange={setRequestedInAt} />
              </FormRow>
              <SelectField label="퇴근 변경" name="outAction" value={f.outAction}>
                {m.actions.map((a) => <option key={a} value={a}>{actionName(a)}</option>)}
              </SelectField>
              <FormRow label="퇴근 시각" htmlFor={`${timeFieldId}-out`}>
                <RequestDateTimeInput id={`${timeFieldId}-out`} name="requestedOutAt" value={requestedOutAt} onChange={setRequestedOutAt} />
              </FormRow>
            </FormTable>
          </>}
        </section>
        <section className="wl-section">
          <h2>{checkout ? '실제 근로·휴게' : '실제 근로·휴게와 사유'}</h2>
          <FormTable>
            <FormRow label="실제 확인">
              <Check name="presenceAsWork" label="제안 구간의 실제 근로를 확인했습니다" checked={f.presenceAsWork} />
              <Check name="breaksConfirmed" label="실제 휴게를 확인했습니다 (없으면 아래 비움)" checked={f.breaksConfirmed} />
            </FormRow>
            <FormRow label="휴게 구간">
              <RequestBreakEditor value={breaks} onChange={value => { setBreaks(value); setDirty(true); }} />
            </FormRow>
          </FormTable>
          {!checkout && <FormTable>
            <Reason label="정정 사유와 사실 근거" value={f.reason} />
          </FormTable>}
        </section>
        <InlineRequestApproval approval={m.approval}/>
        <div className="wl-form-actions"><button className="t-btn primary" disabled={m.approval?.eligible === false}>정정 심사 요청</button></div>
      </Form>}
    </div>
  );
}
function Balances({ model: m, csrf }: { model: BalancesModel; csrf: CsrfToken }) {
  const f = m.form;
  const [batch, setBatch] = useState(false);
  if (batch) return <LeaveBalanceBatch model={m} csrf={csrf} />;
  return (
    <>
      <PageHeader title="기초 잔여 관리" actions={<>
        <button className="t-btn primary" type="button" onClick={() => setBatch(true)}>사원별 일괄 입력</button>
        <a className="t-btn" href="/admin/requests?scope=LEAVE">휴가 신청 현황</a>
        <a className="t-btn" href={`/admin/requests/exceptions/new?employeeId=${m.employee.id}`}>예외 정정 신청</a>
      </>} />
      <Form action="/admin/leave-balances" method="get" className="wl-querybar">
        <div>
          <SelectField label="사원" name="employeeId" value={m.employee.id}>
            <EmployeeOptions employees={m.employees} />
          </SelectField>
        </div>
        <div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div>
      </Form>
      <section className="wl-section">
        <h2>{m.employee.displayName} · 기초 묶음</h2>
        <ul className="wl-rows">
          {m.buckets.map((b) => (
            <li key={b.id}>
              <a href={`/admin/leave-balances/buckets/${b.id}`}>
                {bucketPeriod(b)} / 잔여 {b.balanceDays}일 / 대기 {b.pendingDays}일
              </a>
            </li>
          ))}
        </ul>
      </section>
      <section className="wl-section">
        <h2>근거 있는 기초 등록</h2>
        <p className="hint">
          이전 원장에서 확인한 잔여를 0.5일 단위로 등록하세요. 본인의 기초 등록·조정은 다른 담당자가 처리합니다.
        </p>
        <Form action="/admin/leave-balances/entries" method="post" className="wl-form">
          {hidden('employeeId', f.employeeId)}
          {hidden('commandId', f.commandId)}
          <FormTable>
            <Field label="기초 기준일" name="baseDate" type="date" value={f.baseDate} required />
            <Field label="사용 가능 시작일" name="usableFrom" type="date" value={f.usableFrom} required />
            <Field label="사용 가능 종료일 (포함)" name="usableTo" type="date" value={f.usableTo ?? ''} />
            <Field label="기초 일수 (0.5일 단위)" name="quantityDays" type="number" min="0" max="100000" step="0.5" value={f.quantityDays ?? ''} required />
            <Field label="발생 근거 참조" name="sourceReference" maxLength={500} value={f.sourceReference} required />
            <Reason label="등록 사유" value={f.reason} />
          </FormTable>
          <div className="wl-form-actions"><button className="t-btn primary">기초 원장 등록</button></div>
        </Form>
      </section>
    </>
  );
}
function Ledger({ model: m }: { model: LedgerModel }) {
  const f = m.adjustment;
  const columns = [
    sheetColumn<LedgerEntry>({ field: 'entryKind', headerName: '종류', minWidth: 160, pinned: 'left' }, entry => entry.entryKind),
    sheetColumn<LedgerEntry>({ field: 'signedQuantityDays', headerName: '일수 증감', minWidth: 135, cellClass: 'w-sheet-number', filter: 'agNumberColumnFilter' }, entry => entry.signedQuantityDays),
    sheetColumn<LedgerEntry>({ field: 'effectiveOn', headerName: '기준일', minWidth: 150 }, entry => fmtDate(entry.effectiveOn)),
    sheetColumn<LedgerEntry>({ colId: 'basis', headerName: '근거·사유', minWidth: 280, flex: 1, valueGetter: ({ data }) => data ? `${data.sourceReference} · ${data.reason}` : '' }, entry => `${entry.sourceReference} · ${entry.reason}`),
  ];
  return (
    <>
      <PageHeader title="연차 원장" help={<>
        <p>분류는 원장 금액을 변경하지 않습니다.</p>
        <p>추가한 이력은 덮어쓰지 않습니다. 사용·복원은 원효력을 참조합니다.</p>
      </>} actions={<a className="t-btn" href={`/admin/leave-balances/buckets/${m.bucketId}/attributions`}>귀속연도 관리</a>} />
      <section className="wl-section">
        <WorklogSheet label="연차 원장" title="원장 이력" rows={m.ledger} columns={columns} getRowId={entry => entry.id} scope="all" />
      </section>
      <section className="wl-section">
        <h2>증감 조정</h2>
        <Form
          action={`/admin/leave-balances/buckets/${m.bucketId}/adjustments`}
          method="post"
          className="wl-form"
        >
          {hidden('expectedVersion', f.expectedVersion)}
          {hidden('commandId', f.commandId)}
          <FormTable>
            <Field label="조정 일수 (0.5일 단위, 감액은 음수)" name="quantityDays" type="number" min="-100000" max="100000" step="0.5" value={f.quantityDays ?? ''} required />
            <Field label="효력 기준일" name="effectiveOn" type="date" value={f.effectiveOn} required />
            <Field label="근거 참조" name="sourceReference" value={f.sourceReference} maxLength={500} required />
            <Reason label="조정 사유" value={f.reason} />
          </FormTable>
          <div className="wl-form-actions"><button className="t-btn primary">조정 원장 추가</button></div>
        </Form>
      </section>
    </>
  );
}

function Month({ model: m }: { model: MonthModel }) {
  const view = m.overview,
    summary = view.summary;
  const returnTo = m.admin ? attendanceListReturn(m.checkoutReturnTo ?? null) : null;
  const reviewDay = returnTo ? view.days.find(day => day.workDate === m.checkoutWorkDate) : undefined;
  const shown = (day: DayView, value: number | null) => (day.current ? value : null);
  const away = (day: DayView) => day.employed === false;
  const timeColumn = (colId: string, headerName: string, pick: (day: DayView) => number | null, flagPending = false) =>
    sheetColumn<DayView>({ colId, headerName, minWidth: 120, cellClass: 'w-sheet-number', filter: 'agNumberColumnFilter',
      valueGetter: ({ data }) => (data ? shown(data, pick(data)) : null),
      getQuickFilterText: ({ data }) => (!data || away(data) ? '' : flagPending && data.pending && shown(data, pick(data)) == null ? '확인 전' : duration(shown(data, pick(data)))) },
      day => {
        if (away(day)) return '';
        const value = shown(day, pick(day));
        return flagPending && day.pending && value == null ? <span className="w-sheet-pending">확인 전</span> : duration(value);
      });
  const columns = [
    sheetColumn<DayView>({ field: 'workDate', headerName: '근무일', minWidth: 130, pinned: 'left' },
      day => (day.id ? <a href={`/worktime/results/${day.id}`}>{fmtDate(day.workDate)}</a> : fmtDate(day.workDate))),
    timeColumn('actualWorkMillis', '실근로', day => day.result.actualWorkMillis, true),
    timeColumn('confirmedBreakMillis', '휴게', day => day.result.confirmedBreakMillis),
    timeColumn('over8hMillis', '8시간 초과', day => day.result.over8hMillis),
    sheetColumn<DayView>({ field: 'approvedLeaveDays', headerName: '휴가', minWidth: 90, cellClass: 'w-sheet-number', filter: 'agNumberColumnFilter',
      getQuickFilterText: ({ data }) => (data && !away(data) ? String(data.approvedLeaveDays) : '') },
      day => (away(day) ? '' : day.approvedLeaveDays ? day.approvedLeaveDays : '-')),
  ];
  const weekColumns = [
    sheetColumn<PeriodView>({ colId: 'week', headerName: '주간', minWidth: 210, pinned: 'left',
      valueGetter: ({ data }) => (data ? `${data.from} ~ ${previousDay(data.to)}` : '') }, week => `${week.from} ~ ${previousDay(week.to)}`),
    sheetColumn<PeriodView>({ colId: 'confirmedWorkMillis', field: 'result.confirmedWorkMillis', headerName: '실근로', minWidth: 120,
      cellClass: 'w-sheet-number', filter: 'agNumberColumnFilter',
      getQuickFilterText: ({ data }) => (data ? duration(data.result.confirmedWorkMillis) : '') }, week => duration(week.result.confirmedWorkMillis)),
    sheetColumn<PeriodView>({ field: 'pendingDays', headerName: '확인 전', minWidth: 100, cellClass: 'w-sheet-number',
      filter: 'agNumberColumnFilter', getQuickFilterText: ({ data }) => (data ? `${data.pendingDays}일` : '') }, week => `${week.pendingDays}일`),
    sheetColumn<PeriodView>({ colId: 'over40hMillis', field: 'result.over40hMillis', headerName: '40시간 초과', minWidth: 130,
      cellClass: 'w-sheet-number', filter: 'agNumberColumnFilter',
      getQuickFilterText: ({ data }) => (data ? duration(data.result.over40hMillis) : '') }, week => duration(week.result.over40hMillis)),
  ];
  const leaveDays = view.days.reduce((sum, day) => sum + day.approvedLeaveDays, 0);
  const periodFields = (
    <>
      {hidden('employeeId', m.employeeId)}
      {hidden('from', m.from)}
      {hidden('to', m.to)}
    </>
  );
  return (
    <>
      <PageHeader title="근로시간" actions={reviewDay && <AttendanceListBackLink href={returnTo} />} />
      <Form action={m.admin ? '/admin/attendance' : '/me/worktime'} method="get" className="wl-querybar">
        <Field label="기준 월" name="month" type="month" value={view.month} required />
        {m.admin && (
          <SelectField label="대상 사원" name="employeeId" value={m.employeeId}>
            <EmployeeOptions employees={m.employees} />
          </SelectField>
        )}
        <div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div>
      </Form>
      {reviewDay && <section className="wl-section">
        <h2>{reviewDay.workDate} 근무일</h2>
        <p>실근로시간 {reviewDay.current && reviewDay.result.actualWorkMillis != null ? duration(reviewDay.result.actualWorkMillis) : '미확정'}</p>
        {reviewDay.current && reviewDay.id && <a href={`/worktime/results/${reviewDay.id}?${new URLSearchParams({ returnTo: returnTo! })}`}>해당 일자 집계 근거</a>}
      </section>}
      <dl className="wl-summary" aria-label="월 요약">
        <div><dt>실근로 합계</dt><dd>{duration(summary.result.confirmedWorkMillis)}</dd></div>
        <div data-tone={summary.pendingDays ? 'warn' : undefined}><dt>확인 전</dt><dd>{summary.pendingDays}일</dd></div>
        <div><dt>휴가</dt><dd>{leaveDays}일</dd></div>
      </dl>
      <SelectiveSettlements key={[m.admin, m.employeeId, view.month].join('/')} admin={m.admin} employeeId={m.employeeId} month={view.month} />
      <section className="wl-section">
        <WorklogSheet label="일별 근로시간" title="일별" rows={view.days} columns={columns} getRowId={day => day.workDate} scope="all" />
      </section>
      <section className="wl-section">
        <WorklogSheet label="주간 근로시간" title="주간" rows={view.weeks} columns={weekColumns} getRowId={week => week.from} scope="all" />
      </section>
      {m.admin && (
        <section className="wl-section">
          <h2>수동 재집계·검토용 Excel</h2>
          <p>
            <a href={`/admin/work-policies?employeeId=${m.employeeId}`}>주 기산점·적용 대상 검토</a>{' '}
            · <a href="/admin/audit">감사 이력</a>
          </p>
          <Form action="/admin/calculations" method="post" className="wl-form">
            {reviewDay ? <>
              {hidden('employeeId', m.employeeId)}
              {hidden('from', reviewDay.workDate)}
              {hidden('to', reviewDay.workDate)}
              {hidden('returnTo', returnTo)}
            </> : periodFields}
            {hidden('commandId', m.calculateCommand)}
            <FormTable><Field label="재집계 사유" name="reason" maxLength={500} required /></FormTable>
            <div className="wl-form-actions"><button className="t-btn">{reviewDay ? '대상일과 해당 주 재집계' : '월과 경계 주 재집계'}</button></div>
          </Form>
          <Form action="/admin/reports/exports" method="post" className="wl-form">
            {periodFields}
            {hidden('commandId', m.exportCommand)}
            <FormTable><Field label="검토용 출력 사유" name="reason" maxLength={500} required /></FormTable>
            <div className="wl-form-actions"><button className="t-btn">검토용 XLSX 다운로드</button></div>
          </Form>
        </section>
      )}
    </>
  );
}
function Policies({ model: m }: { model: PoliciesModel }) {
  const p = m.policyForm,
    a = m.assessmentForm;
  return (
    <>
      <PageHeader title="적용 대상·주 기산점 검토" help="법률 적합성을 자동 인증하지 않습니다. 근거를 확인한 적용 대상과 기간만 명시하며 민감한 보호 사유는 수집하지 않습니다. 변경은 새 불변 버전입니다." />
      <Form action="/admin/work-policies" method="get" className="wl-querybar">
        <div>
          <SelectField label="대상 사원" name="employeeId" value={m.employeeId}>
            <EmployeeOptions employees={m.employees} />
          </SelectField>
        </div>
        <div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div>
      </Form>
      <section className="wl-section">
        <h2>사업장 주 기산점</h2>
        <ul className="wl-rows">
          {m.policies.map((r) => (
            <li key={r.id}>
              <div className="wl-rows-main">
                <h3>v{r.revision} · {fmtDate(r.validFrom)} ~ {r.validTo == null ? '종료 없음' : fmtDate(r.validTo)}</h3>
                <p>월요일 일치검토 {String(r.mondayWeekReviewed)} · {r.basis}</p>
              </div>
            </li>
          ))}
        </ul>
        <Form action="/admin/work-policies" method="post" className="wl-form">
          {hidden('commandId', p.commandId)}
          <FormTable>
            <Field label="적용 시작일" name="from" type="date" value={p.from} required />
            <Field label="종료일 (포함)" name="to" type="date" value={p.to} required />
            <FormRow label="월요일 확인">
              <Check label="실제 회사의 주 기산점이 월요일인 것을 확인했습니다" name="mondayWeekReviewed" checked={p.mondayWeekReviewed} />
            </FormRow>
            <TextAreaField label="검토 근거" name="basis" value={p.basis} maxLength={500} required />
            <Field label="등록 사유" name="reason" value={p.reason} maxLength={500} required />
          </FormTable>
          <div className="wl-form-actions"><button className="t-btn primary">기산점 검토 버전 저장</button></div>
        </Form>
      </section>
      <section className="wl-section">
        <h2>사원별 일반 규정 적용 검토</h2>
        <ul className="wl-rows">
          {m.assessments.map((r) => (
            <li key={r.id}>
              <div className="wl-rows-main">
                <h3>v{r.revision} · {fmtDate(r.validFrom)} ~ {r.validTo == null ? '종료 없음' : fmtDate(r.validTo)}</h3>
                <p>일반규정 검토 {String(r.generalRuleReviewed)} · {r.basis}</p>
              </div>
            </li>
          ))}
        </ul>
        <Form action="/admin/work-assessments" method="post" className="wl-form">
          {hidden('employeeId', a.employeeId)}
          {hidden('commandId', a.commandId)}
          <FormTable>
            <Field label="적용 시작일" name="from" type="date" value={a.from} required />
            <Field label="종료일 (포함)" name="to" type="date" value={a.to} required />
            <FormRow label="일반 규정 검토">
              <Check label="성인 통상 고정 근무의 일반 규정 적용을 검토했습니다" name="generalRuleReviewed" checked={a.generalRuleReviewed} />
            </FormRow>
            <p>
              단시간·보호 대상·특례·적용 제외 등 별도 검토가 필요하거나 미확인이라면 체크하지 마세요.
              구체적인 민감정보를 적지 않습니다.
            </p>
            <TextAreaField label="최소 검토 근거 참조" name="basis" value={a.basis} maxLength={500} required />
            <Field label="등록 사유" name="reason" value={a.reason} maxLength={500} required />
          </FormTable>
          <div className="wl-form-actions"><button className="t-btn primary">적용 검토 버전 저장</button></div>
        </Form>
      </section>
    </>
  );
}
function EventTable({ events, label }: { events: ClockEvent[] | null; label: string }) {
  const columns = [
    sheetColumn<ClockEvent>({ field: 'id', headerName: '사건 ID', minWidth: 200, pinned: 'left' }, event => event.id),
    sheetColumn<ClockEvent>({ field: 'kind', headerName: '종류', minWidth: 100 }, event => event.kind),
    sheetColumn<ClockEvent>({ field: 'occurredAt', headerName: '발생 시각 (서울)', minWidth: 240, flex: 1, valueGetter: ({ data }) => data ? fmtDateTime(data.occurredAt) : '' }, event => fmtDateTime(event.occurredAt)),
  ];
  return <WorklogSheet label={label} rows={events ?? []} columns={columns} getRowId={event => event.id} scope="all" />;
}
function FrozenClock({ clock: c }: { clock: DaySnapshot }) {
  const confirmation = c.confirmation;
  return (
    <>
      <section className="wl-section">
        <h2>당시 일정·휴일</h2>
        <dl className="wl-form-table">
          <dt>일정 버전 ID</dt>
          <dd>{c.scheduleVersionId ?? '일정 미확인'}</dd>
          <dt>휴일 캘린더 버전 ID</dt>
          <dd>{c.calendarVersionId ?? '캘린더 미확인'}</dd>
          <dt>일자 종류</dt>
          <dd>{c.dayKind}</dd>
        </dl>
        {c.schedule && (
          <>
            <p>{c.schedule.workday ? '소정 근무일' : '소정 비근무일'}</p>
            {c.schedule.workMode === 'SELECTIVE' ? <p>{selectiveHours(c.schedule)}</p>
              : <p>예정 시작·종료 {fmtTime(c.schedule.startTime)} / {fmtTime(c.schedule.endTime)}</p>}
            <h3>예정 휴게</h3>
            <ul>
              {c.schedule.breaks.map((b, i) => (
                <li key={i}>
                  {fmtTime(b.startTime)} ~ {fmtTime(b.endTime)}
                </li>
              ))}
            </ul>
            <p>예정 휴게를 실제 확인 휴게로 자동 간주하지 않습니다.</p>
          </>
        )}
      </section>
      <section className="wl-section">
        <h2>당시 원천과 유효 출퇴근</h2>
        <p>
          원천은 보존하며 승인된 정정의 유효 사건을 별도로 보여 줍니다. IP·인증 원문은 표시하지
          않습니다.
        </p>
        <h3>원천 사건</h3>
        {c.events.length === 0 && <p>저장 당시 원천 사건 없음</p>}
        <EventTable events={c.events} label="당시 원천 출퇴근" />
        <h3>유효 사건</h3>
        {!c.effectiveEvents?.length && <p>저장 당시 유효 사건 없음 또는 근거 확인 필요</p>}
        <EventTable events={c.effectiveEvents} label="당시 유효 출퇴근" />
        <h3>실제 근로·휴게 확인</h3>
        {confirmation ? (
          <>
            <dl className="wl-form-table">
              <dt>확인 ID</dt>
              <dd>{confirmation.id}</dd>
              <dt>확인 대상 사실·검토 개정</dt>
              <dd>
                {confirmation.confirmedInputRevision} / {confirmation.reviewRevision}
              </dd>
              <dt>실제 근로·휴게 일치 판단</dt>
              <dd>
                {String(confirmation.presenceAsWork)} / {String(confirmation.breaksMatch)}
              </dd>
              <dt>확인 시각</dt>
              <dd>{fmtDateTime(confirmation.confirmedAt)}</dd>
            </dl>
            {confirmation.breaks.length === 0 && <p>확인된 휴게 구간 없음</p>}
            <ul>
              {confirmation.breaks.map((b, i) => (
                <li key={i}>
                  {fmtDateTime(b.startAt)} ~ {fmtDateTime(b.endAt)}
                </li>
              ))}
            </ul>
          </>
        ) : (
          <p>저장된 실제 근로·휴게 확인 근거 없음</p>
        )}
      </section>
    </>
  );
}
function Result({ model: m }: { model: ResultModel }) {
  const d = m.detail,
    day = d.day,
    run = d.run;
  const times = m.times ?? {
    소정근로: '-',
    체류: '-',
    '확인 실근로': '-',
    '확인 휴게': '-',
    '일정 밖': '-',
    '일8시간 초과 참고': '-',
    야간: '-',
    휴일: '-',
    '법정 최종 연장': '-',
  };
  return (
    <section className="t-record-detail" aria-label="계산 근거 상세">
      <PageHeader title="계산 근거 상세" help={<>
        <p>이 결과 ID에 저장된 계산 당시의 근거입니다. 현재 입력으로 바꾸거나 재집계하지 않습니다. 법률·급여 확정 자료가 아닌 검토용 결과입니다.</p>
        <p>시각 기준: Asia/Seoul (UTC+09:00) · 시간량: 시:분:초. 날짜와 실제 발생 시각은 구분합니다.</p>
      </>} actions={<>
        {m.admin && <AttendanceListBackLink />}
        {m.workspacePrincipal?.roles.includes('EMPLOYEE') && <a className="t-btn" href={`/me/worktime?month=${m.month}`}>내 근로시간</a>}
        {m.admin && <a className="t-btn" href={`/admin/attendance?employeeId=${day.employeeId}&month=${m.month}`}>사원 월 현황</a>}
      </>} />
      <section className="wl-section">
        <h2>결과와 최신성</h2>
        <p>
          {day.employeeNo} · {day.employeeName} / {fmtDate(day.workDate)}
        </p>
        <p>사번·이름은 현재 사원 정보이며 아래 업무 근거는 계산 당시 값입니다.</p>
        <dl className="wl-form-table">
          <dt>결과 ID</dt>
          <dd>{day.id}</dd>
          <dt>결과 개정·규칙</dt>
          <dd>
            v{day.resultRevision} · {day.ruleVersion}
          </dd>
          <dt>상태</dt>
          <dd>{day.result.status}</dd>
          <dt>사실·검토 개정</dt>
          <dd>
            {day.result.inputRevision} / {day.result.reviewRevision}
          </dd>
          <dt>계산 시각</dt>
          <dd>{fmtDateTime(day.calculatedAt)}</dd>
        </dl>
        <p>{day.current ? '유효한 계산 결과' : '현재 입력 근거가 변경됨 · 재집계 필요'}</p>
        <p>
          {d.latestVersion ? '이 날짜의 최신 계산 버전' : '과거 계산 버전 · 이후 계산 결과 있음'}
        </p>
        {!d.latestVersion && d.latestResultId && (
          <p>
            <a href={`/worktime/results/${d.latestResultId}`}>최신 계산 결과</a>
          </p>
        )}
        {!d.completeEvidence && (
          <p className="notice error" role="status">
            저장된 근거가 없거나 일부를 확인할 수 없습니다. 현재 원천으로 대신 채우지 않았습니다.
            담당자 확인이 필요합니다.
          </p>
        )}
        <ul>
          {day.result.reasons.map((reason) => (
            <li key={reason}>{reason === 'LEAVE_WORK_CONFLICT' && d.clock?.schedule?.workMode !== 'SELECTIVE'
              ? reason : selectiveReasonLabels[reason] ?? reason}</li>
          ))}
        </ul>
      </section>
      <section className="wl-section">
        <h2>저장된 산정 결과</h2>
        <Summary entries={times} />
        <dl className="wl-form-table">
          <dt>승인 연차</dt>
          <dd>{day.result.leaveDays} DAY</dd>
        </dl>
        <p>
          '-'는 미확정이며 실제 0시간과 다릅니다. 야간·휴일·초과 참고량을 더해 총 실근로로 만들지 않습니다.
        </p>
      </section>
      {run && (
        <section className="wl-section">
          <h2>계산 실행</h2>
          <dl className="wl-form-table">
            <dt>실행 ID</dt>
            <dd>{run.id}</dd>
            <dt>실행 상태·엔진</dt>
            <dd>
              {run.status} · {run.engineVersion}
            </dd>
            <dt>요청한 기간 (양끝 포함)</dt>
            <dd>
              {fmtDate(run.fromDate)} ~ {fmtDate(run.toDate)}
            </dd>
            <dt>경계 주 포함 기간 (양끝 포함)</dt>
            <dd>
              {fmtDate(run.expandedFrom)} ~ {fmtDate(run.expandedTo)}
            </dd>
            <dt>요청 계정 ID</dt>
            <dd>{run.requestedBy}</dd>
            <dt>실행 시작·종료 시각</dt>
            <dd>
              {fmtDateTime(run.startedAt)} / {fmtDateTime(run.finishedAt)}
            </dd>
          </dl>
        </section>
      )}
      {d.clock ? (
        <FrozenClock clock={d.clock} />
      ) : (
        <section className="wl-section">
          <h2>당시 일정·휴일</h2>
          <p>저장 당시 일정·휴일 근거 확인 필요</p>
        </section>
      )}
      <section className="wl-section">
        <h2>당시 정책·적용 검토·재직 근거</h2>
        <p>원문 자유 근거·민감한 보호 사유는 표시하지 않습니다.</p>
        {d.policy ? (
          <>
            <h3>주 기산점 검토</h3>
            <p>
              {d.policy.id} · v{d.policy.revision}
            </p>
            <p>
              {fmtDate(d.policy.validFrom)} ~{' '}
              {d.policy.validTo == null ? '종료일 없음' : fmtDate(d.policy.validTo)} (종료일이
              있으면 미포함)
            </p>
            <p>
              {d.policy.mondayWeekReviewed ? '월요일 주 기산점 일치 검토됨' : '주 기산점 미확인'}
            </p>
          </>
        ) : (
          <p>저장 당시 주 기산점 검토 미확인</p>
        )}
        {d.assessment ? (
          <>
            <h3>일반 규정 적용 검토</h3>
            <p>
              {d.assessment.id} · v{d.assessment.revision}
            </p>
            <p>
              {fmtDate(d.assessment.validFrom)} ~{' '}
              {d.assessment.validTo == null ? '종료일 없음' : fmtDate(d.assessment.validTo)}{' '}
              (종료일이 있으면 미포함)
            </p>
            <p>
              {d.assessment.generalRuleReviewed ? '일반 규정 적용 검토됨' : '일반 규정 적용 미확인'}
            </p>
          </>
        ) : (
          <p>저장 당시 일반 규정 적용 미확인</p>
        )}
        {d.employment ? (
          <>
            <h3>재직기간</h3>
            <p>
              {fmtDate(d.employment.joinedOn)} ~{' '}
              {d.employment.leftOn == null
                ? '당시 재직 종료일 미등록'
                : fmtDate(d.employment.leftOn)}{' '}
              (마지막 재직일이 있으면 포함)
            </p>
          </>
        ) : (
          <p>저장 당시 재직기간 근거 확인 필요</p>
        )}
      </section>
      <section className="wl-section">
        <h2>당시 신청·효력 참조</h2>
        <h3>승인 연차 효력 ID</h3>
        {d.leaveEffects.length === 0 && <p>저장된 승인 연차 참조 없음 또는 근거 확인 필요</p>}
        <ul>
          {d.leaveEffects.map((id) => (
            <li key={id}>{id}</li>
          ))}
        </ul>
        <h3>대기 정정 신청 ID</h3>
        {d.pendingCorrections.length === 0 && <p>저장된 대기 정정 참조 없음 또는 근거 확인 필요</p>}
        <ul>
          {d.pendingCorrections.map((id) => (
            <li key={id}>{id}</li>
          ))}
        </ul>
      </section>
    </section>
  );
}

function Audit({ model: m }: { model: AuditModel }) {
  const columns = [
    sheetColumn<AuditRow>({ field: 'at', headerName: '서울 시각', minWidth: 230, pinned: 'left', valueGetter: ({ data }) => data ? fmtDateTime(data.at) : '' }, row => fmtDateTime(row.at)),
    sheetColumn<AuditRow>({ field: 'action', headerName: '행위·상세', minWidth: 180 }, row => <a href={`/admin/audit/${row.id}`}>{row.action}</a>),
    sheetColumn<AuditRow>({ colId: 'target', headerName: '대상', minWidth: 260, valueGetter: ({ data }) => data ? `${data.targetKind} · ${data.targetId}` : '' }, row => `${row.targetKind} · ${row.targetId}`),
    sheetColumn<AuditRow>({ field: 'actorAccountId', headerName: '처리 계정', minWidth: 220 }, row => row.actorAccountId),
    sheetColumn<AuditRow>({ field: 'reason', headerName: '사유', minWidth: 240, flex: 1 }, row => row.reason),
  ];
  return (
    <>
      <PageHeader title="고객사 감사 이력" help={<>
        <p>열람 자체를 감사합니다. 현재 담당자 권한으로 소속 고객사의 감사만 확인합니다. 시각 기준은 Asia/Seoul (UTC+09:00)입니다.</p>
        <p>이전·다음은 조회 시점 원장 기준이며 전체 원장의 고정 스냅샷을 보장하지 않습니다. 동일 시각에는 감사 ID로 순서를 구분합니다.</p>
        <p>한 번에 최대 50건을 표시합니다.</p>
        <p>비밀번호·토큰·이메일·IP와 저장 JSON 원문은 제공하지 않습니다. 상세에서는 제한된 전후 업무 요약을 확인할 수 있습니다.</p>
      </>} />
      <section className="wl-section">
        <WorklogSheet label="고객사 감사 목록" title="조회된 기록" rows={m.audits} columns={columns} getRowId={row => row.id}
          emptyMessage="이 범위에 감사 기록이 없습니다. 최신 목록에서 다시 확인할 수 있습니다."
          pagination={<nav aria-label="감사 페이지">
            {m.page.previousCursor && (
              <a href={`/admin/audit?after=${m.page.previousCursor}`}>이전 페이지 (더 최근)</a>
            )}{' '}
            <a href="/admin/audit">최신 목록</a>{' '}
            {m.page.nextCursor && (
              <a href={`/admin/audit?before=${m.page.nextCursor}`}>다음 페이지 (더 과거)</a>
            )}
          </nav>} />
      </section>
    </>
  );
}
function AuditDetailPage({ audit: a }: { audit: AuditDetail }) {
  return (
    <section className="t-record-detail" aria-label="감사 상세">
      <PageHeader title="감사 상세" />
      <p className="hint">같은 고객사의 기록이며 열람 자체도 감사에 남깁니다. 시각 기준: Asia/Seoul (UTC+09:00).</p>
      <section className="wl-section">
        <h2>행위와 추적 정보</h2>
        <dl className="wl-form-table">
          <dt>감사 ID</dt>
          <dd>{a.id}</dd>
          <dt>행위</dt>
          <dd>{a.action}</dd>
          <dt>대상</dt>
          <dd>
            {a.targetKind} · {a.targetId}
          </dd>
          <dt>처리 계정 ID</dt>
          <dd>{a.actorAccountId}</dd>
          <dt>서울 시각</dt>
          <dd>{fmtDateTime(a.at)}</dd>
          <dt>명령 ID</dt>
          <dd>{a.commandId}</dd>
          <dt>요청 추적 ID</dt>
          <dd>{a.requestTraceId}</dd>
          <dt>처리 사유</dt>
          <dd className="reason-cell">{a.reason}</dd>
        </dl>
      </section>
      <section className="wl-section">
        <h2>변경 전 업무 요약</h2>
        {Object.keys(a.beforeSummary ?? {}).length === 0 && (
          <p>표시 가능한 업무 요약이 없습니다.</p>
        )}
        <Summary entries={a.beforeSummary} />
      </section>
      <section className="wl-section">
        <h2>변경 후 업무 요약</h2>
        {Object.keys(a.afterSummary ?? {}).length === 0 && <p>표시 가능한 업무 요약이 없습니다.</p>}
        <Summary entries={a.afterSummary} />
      </section>
      <p>
        버전·상태·역할·기간·수량·참조 ID 등 알려진 업무 필드만 타입을 검증해 표시합니다. 자유 형식
        이름·코드는 값 생략으로 표시하고, 비밀번호·토큰·이메일·IP·자유 근거·미등록 필드와 JSON
        원문은 보여 주지 않습니다. 요약은 전체 저장 원문의 재현이 아닙니다.
      </p>
      <p>
        <a href="/admin/audit">최신 감사 목록으로</a>
      </p>
    </section>
  );
}

const eventName = (kind: string) => (kind === 'IN' ? '출근' : kind === 'OUT' ? '퇴근' : kind);
function confirmationTime(value: string) {
  const seoul = fmtDateTime(value).slice(11);
  const millis = new Date(value).getUTCMilliseconds();
  return millis ? seoul + '.' + String(millis).padStart(3, '0') : fmtTime(seoul);
}
function scheduleLine(day: DaySnapshot) {
  if (day.schedule == null) return null;
  const head = day.dayKind === 'HOLIDAY' ? '휴일' : day.dayKind === 'NON_WORKDAY' || !day.schedule.workday ? '비근무일'
    : day.schedule.workMode === 'SELECTIVE' ? selectiveHours(day.schedule)
    : `예정 ${fmtTime(day.schedule.startTime)}~${fmtTime(day.schedule.endTime)}`;
  const pauseLabel = day.schedule.workMode === 'SELECTIVE' ? '예정 휴게' : '휴게';
  const line = [head, ...day.schedule.breaks.map(b => `${pauseLabel} ${fmtTime(b.startTime)}~${fmtTime(b.endTime)}`)].join(' · ');
  return day.calendarVersionId == null ? `${line} · 캘린더 미확인` : line;
}
export function AttendanceRecord({ data, showSchedule = true }: { data: Record<string, unknown>; showSchedule?: boolean }): React.ReactNode {
  const m = data as unknown as ClockModel,
    d = m.day,
    f = m.confirmationForm;
  const selective = d.schedule?.workMode === 'SELECTIVE';
  const breaksId = useId();
  const correctionHref = `/me/requests/new?year=${d.workDate.slice(0, 4)}&kind=ATTENDANCE_CORRECTION&date=${d.workDate}`;
  const time = (event: ClockEvent) => m.eventTimes?.[event.id] ?? fmtDateTime(event.occurredAt);
  const columns = [
    sheetColumn<ClockEvent>({ field: 'kind', headerName: '구분', minWidth: 120, pinned: 'left', valueGetter: ({ data }) => data ? eventName(data.kind) : '' }, event => eventName(event.kind)),
    sheetColumn<ClockEvent>({ field: 'occurredAt', headerName: '시각', minWidth: 200, flex: 1, valueGetter: ({ data }) => data ? time(data) : '' }, time),
  ];
  const effective = d.effectiveEvents ?? d.events;
  const changedEvents =
    d.effectiveEvents != null &&
    (d.events.length !== d.effectiveEvents.length ||
      d.events.some((e, i) => {
        const after = d.effectiveEvents?.[i];
        return e.id !== after?.id || e.kind !== after?.kind || e.occurredAt !== after?.occurredAt;
      }));
  const ins = effective.filter(e => e.kind === 'IN'), outs = effective.filter(e => e.kind === 'OUT');
  const canConfirm = d.schedule != null && d.calendarVersionId != null && d.confirmation == null
    && (m.canConfirm ?? (ins.length === 1 && outs.length === 1
      && Date.parse(ins[0].occurredAt) < Date.parse(outs[0].occurredAt)
      && [ins[0], outs[0]].every(e => fmtDateTime(e.occurredAt).startsWith(d.workDate))));
  const needsCorrection = m.correctionSuggested === true && !canConfirm && d.confirmation == null && d.schedule != null && d.calendarVersionId != null;
  return (
    <section className="wl-section">
      {d.schedule == null ? <p className="notice">근무 일정 미배정</p> : showSchedule && <p className="t-muted">{scheduleLine(d)}</p>}
      <WorklogSheet title="출퇴근 기록" label="출퇴근 기록" rows={d.events} columns={columns} getRowId={event => event.id} scope="all" />
      {changedEvents && (
        <div className="notice">
          <h3>정정 반영 기록</h3>
          <ul>
            {d.effectiveEvents?.map(e => <li key={e.id}>{eventName(e.kind)} · {time(e)}</li>)}
          </ul>
        </div>
      )}
      {d.confirmation && (
        <p className="notice">
          {m.actualWorkMillis != null ? `근무 확인 완료 · 실근로 ${duration(m.actualWorkMillis)}` : '근무 확인 완료 · 실근로는 담당자 재집계 후 표시됩니다.'}
        </p>
      )}
      {needsCorrection && (
        <p className="notice">출퇴근 기록이 맞지 않아 근무 확인을 할 수 없습니다. <a href={correctionHref}>정정 신청</a></p>
      )}
      {canConfirm && (
        <Form action="/me/work-confirmations" method="post" className="wl-form">
          {hidden('workDate', f.workDate)}
          {hidden('baseInputRevision', f.baseInputRevision)}
          {hidden('expectedReviewRevision', f.expectedReviewRevision)}
          {hidden('sourceFingerprint', f.sourceFingerprint)}
          {hidden('commandId', f.commandId)}
          <h3>근무 확인</h3>
          <FormTable>
            <FormRow label="근무 확인" required>
              <label className="checkbox-label">
                <input type="checkbox" name="presenceAsWork" value="true" defaultChecked={f.presenceAsWork} required />
                휴게를 뺀 실제 근로가 맞습니다
              </label>
              <label className="checkbox-label">
                <input type="checkbox" name="breaksMatch" value="true" defaultChecked={f.breaksMatch} required
                  aria-describedby={selective ? breaksId : undefined} />
                {selective ? '표시된 휴게가 실제와 같습니다' : '휴게가 예정과 같습니다'}
              </label>
              {selective && <div className="t-subtle" id={breaksId}>
                <p>확인할 휴게 {m.confirmationBreaks == null ? '미확인' : m.confirmationBreaks.length
                  ? m.confirmationBreaks.map(b => confirmationTime(b.startAt) + ' ~ ' + confirmationTime(b.endAt)).join(', ') : '없음'}</p>
                <p>실제 휴게가 다르면 <a href={correctionHref}>정정 신청</a></p>
              </div>}
            </FormRow>
          </FormTable>
          <div className="wl-form-actions">
            <button type="submit" className="t-btn primary" disabled={selective && m.confirmationBreaks == null}>근무 확인</button>
          </div>
        </Form>
      )}
    </section>
  );
}

export function WorkflowPages(props: PageProps): React.ReactNode | null {
  const { page, data } = props;
  let content: React.ReactNode;
  switch (page) {
    case 'request/mine':
      if (data.annual) return null;
      content = <Mine model={data as unknown as MineModel} />;
      break;
    case 'request/list': {
      const m = data as unknown as ListModel;
      content = data.leaveOperations ? <LeaveOperationsPage model={data.leaveOperations as LeaveOperations} />
        : m.completed !== undefined ? <ApprovalWorkspace model={m} /> : (
        <>
          <PageHeader title={m.title} />
          <section className="wl-section">
            <RequestRows requests={m.requests} pagination={m.page !== undefined && <nav className="actions pagination" aria-label="결재 페이지"><span>{m.page + 1}페이지</span>{m.page > 0 && <a href={`${m.completed ? '/approvals/completed' : '/approvals'}?page=${m.page - 1}`}>이전</a>}{m.hasNext && <a href={`${m.completed ? '/approvals/completed' : '/approvals'}?page=${m.page + 1}`}>다음</a>}</nav>} />
          </section>
        </>
      );
      break;
    }
    case 'request/rows':
      content = <RequestRows requests={(data as unknown as ListModel).requests} />;
      break;
    case 'request/detail':
      content = <RequestDetailPage model={data as unknown as RequestDetailModel} />;
      break;
    case 'request/correction':
      content = <Correction model={data as unknown as CorrectionModel} />;
      break;
    case 'request/balances':
      content = <Balances model={data as unknown as BalancesModel} csrf={props.csrf} />;
      break;
    case 'request/ledger':
      content = <Ledger model={data as unknown as LedgerModel} />;
      break;
    case 'worktime/month':
      content = <Month model={data as unknown as MonthModel} />;
      break;
    case 'worktime/policies':
      content = <Policies model={data as unknown as PoliciesModel} />;
      break;
    case 'worktime/result':
      content = <Result model={data as unknown as ResultModel} />;
      break;
    case 'worktime/audit':
      content = <Audit model={data as unknown as AuditModel} />;
      break;
    case 'worktime/audit-detail':
      content = <AuditDetailPage audit={data.audit as AuditDetail} />;
      break;
    case 'clock/day':
      content = <AttendanceRecord data={data} />;
      break;
    case 'clock/period':
      return <div className="t-legacy-page content admin-shell"><AttendancePeriodPage model={data.attendancePeriod as AttendancePeriod}
        error={typeof data.error === 'string' ? data.error : undefined} /></div>;
    case 'clock/history': {
      const clock = data as unknown as ClockModel;
      const todayCard = clock.isToday && clock.day.workDate === data.today;
      content = (
        <>
          <PageHeader title="출퇴근 기록" help="출퇴근 시각을 기록하고 근무를 확인하세요."
            actions={<a className="t-btn" href="/me/attendance?view=period">기간 조회</a>} />
          {todayCard && <section className="t-clock-actions t-card" aria-label="오늘 출퇴근">
            <div><span className="t-clock-indicator" aria-hidden="true" /><strong>오늘의 근무</strong>{scheduleLine(clock.day) && <p className="t-muted">{scheduleLine(clock.day)}</p>}</div>
            <div className="t-clock-buttons">{(['IN', 'OUT'] as const).map(kind => {
              const name = kind === 'IN' ? '출근' : '퇴근', recorded = clock.day.events.find(e => e.kind === kind);
              return <Form key={kind} action="/me/attendance/events">
                {hidden('eventKind', kind)}{hidden('commandId', kind === 'IN' ? data.inCommand as string : data.outCommand as string)}
                <button className={`t-btn${kind === 'IN' ? ' primary' : ''}`} disabled={!!recorded || !(kind === 'IN' ? data.inCommand : data.outCommand)}>
                  {recorded ? `${name} ${fmtDateTime(recorded.occurredAt).slice(11)}` : `${name} 기록`}</button>
              </Form>;
            })}</div>
          </section>}
          <Form action="/me/attendance" method="get" className="wl-querybar">
            <Field label="기준일" name="date" type="date" value={clock.day.workDate} required />
            <div className="wl-querybar-actions">
              <a className="t-btn wl-query-reset" href="/me/attendance">오늘</a>
              <button className="t-btn wl-query-submit" type="submit">조회</button>
            </div>
          </Form>
          <AttendanceRecord data={data} showSchedule={!todayCard} />
        </>
      );
      break;
    }
    default:
      return null;
  }
  return (
    <div className="t-legacy-page content admin-shell">
      <ErrorNotice message={typeof data.error === 'string' ? data.error : undefined} />
      {content}
    </div>
  );
}
