import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import type { PageProps } from '../types';
import { Form, fmtDate, fmtDateTime } from '../ui';
import { encodeForm, fetchPage, PageRequestError } from '../transport';
import type { RequestView } from './WorkflowTypes';
import type { AnnualModel, AnnualView, ApprovalPreview, AttributionView, ComposeView } from './AnnualRequestTypes';
import { RequestBreakEditor } from '../components/RequestBreakEditor';
import './RequestWorkspace.css';

const kindName = (kind: string) => ({ LEAVE_DAY: '종일 연차', ATTENDANCE_CORRECTION: '출퇴근·휴게 정정', CANCEL_LEAVE: '연차 취소' }[kind] ?? kind);
const statusName = (status: string) => ({ PENDING: '결재 대기', APPROVED: '승인', REJECTED: '반려', WITHDRAWN: '철회' }[status] ?? status);
const actionName = (action: string) => ({ KEEP: '유지', SET: '시각 수정', CLEAR: '누락으로 정정' }[action] ?? '확인 필요');
const hidden = (name: string, value: string | number | boolean | null | undefined) => <input type="hidden" name={name} value={value == null ? '' : String(value)} />;
function RequestIcon({ kind }: { kind: 'CLOSE' | ComposeView['kind'] }) {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {kind === 'CLOSE' ? <path d="m6 6 12 12M18 6 6 18"/> : kind === 'LEAVE_DAY' ? <><rect x="4" y="5" width="16" height="16" rx="3"/><path d="M8 3v4m8-4v4M4 11h16m-9 4h2v3h-2z"/></> : <><circle cx="12" cy="12" r="8"/><path d="M12 7v5l3 2M18 3l3 3M3 18l3 3"/></>}
  </svg>;
}
function url(path: string, values: Record<string, string | number | null | undefined>) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) if (value != null && value !== '') query.set(key, String(value));
  return `${path}?${query}`;
}
function RequestTable({ rows, year, canResubmit=[] }: { rows: RequestView[]; year?: number; canResubmit?: string[] }) {
  return <div className="table-scroll t-request-board"><table className="t-annual-table"><thead><tr><th>신청 유형·대상</th><th>원신청 기간·일수</th><th>결재 진행</th><th>현재 상태</th><th>상세</th></tr></thead><tbody>
    {rows.map(row => <tr key={row.id}>
      <td data-label="신청 유형·대상"><a className="t-request-title" href={`/requests/${row.id}`}>{kindName(row.kind)}</a><small>{row.targetEmployeeName}</small>{row.reason&&<p className="t-request-reason">{row.reason}</p>}</td>
      <td data-label="기간·일수"><span>{fmtDate(row.fromDate)}</span><small>~ {fmtDate(row.toDate)}{row.kind === 'LEAVE_DAY' && ` · 원신청 ${row.quantityDays} DAY`}</small></td>
      <td data-label="결재 진행"><span>{row.approverName || '담당 미지정'}</span><small>{row.status==='PENDING'?'담당자 검토 대기':'처리된 신청'}</small></td>
      <td data-label="현재 상태"><span className={`t-request-status t-request-status-${row.status.toLowerCase()}`}>{statusName(row.status)}</span><small>{row.effectStatus === 'EFFECTIVE' ? '현재 효력 유효' : row.effectStatus === 'CANCELED' ? '취소 완료' : row.effectStatus === 'SUPERSEDED' ? '후속 정정으로 대체' : '효력 없음'}</small></td>
      <td data-label="상세"><a className="t-request-detail" href={`/requests/${row.id}`} aria-label={`${kindName(row.kind)} 상세`}>상세 보기 </a>{year&&canResubmit.includes(row.id)&&['REJECTED','WITHDRAWN'].includes(row.status)&&['LEAVE_DAY','ATTENDANCE_CORRECTION'].includes(row.kind)&&<a className="t-request-resubmit" href={url('/me/requests/new',{year,kind:row.kind,date:row.fromDate,predecessorRequestId:row.id})}>재신청</a>}</td>
    </tr>)}
  </tbody></table>{!rows.length && <div className="t-request-empty"><span aria-hidden="true">▤</span><p>조회 조건에 맞는 신청이 없습니다.</p><small>연도·유형·상태 또는 현재 페이지 검색을 확인해 주세요.</small></div>}</div>;
}
function Pages({ annual: a, authored = false }: { annual: AnnualView; authored?: boolean }) {
  const page = authored ? a.authoredPage : a.page, next = authored ? a.authoredHasNext : a.hasNext;
  const values = { year: a.year, typeId: a.typeId, status: a.status, page: a.page, authoredPage: a.authoredPage };
  return <nav className="actions pagination" aria-label={authored ? '대리 작성 페이지' : '내 신청 페이지'}><span>{page + 1}페이지 · 최대 50건</span>{page > 0 && <a href={url('/me/requests', { ...values, [authored ? 'authoredPage' : 'page']: page - 1 })}>이전</a>}{next && <a href={url('/me/requests', { ...values, [authored ? 'authoredPage' : 'page']: page + 1 })}>다음</a>}</nav>;
}
function useUnload(dirty: boolean) {
  useEffect(() => {
    const listener = (event: BeforeUnloadEvent) => { if (dirty) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', listener);
    return () => window.removeEventListener('beforeunload', listener);
  }, [dirty]);
}
function ApprovalLine({ approval, checked }: { approval: ApprovalPreview | undefined; checked: boolean }) {
  const status = approval?.eligible ? (checked ? '미리보기 확인' : '결재 가능') : '확인 필요';
  return <section className="t-request-routing" aria-labelledby="annual-approval-line">
    <div className="t-request-context-heading">
      <h3 id="annual-approval-line">결재선</h3>
      {approval && <span className="t-request-route-status" data-eligible={approval.eligible}>
        {status}
      </span>}
    </div>
    {approval ? <>
      <dl className="t-request-context-facts">
        <div><dt>결재 경로</dt><dd>{approval.routeName ?? (approval.source === 'LEGACY' ? '사원별 지정 승인자' : '미지정')}</dd></div>
        <div><dt>결재 담당</dt><dd>{approval.approverName ?? '미지정'}</dd></div>
      </dl>
      {approval.message && approval.message !== status && <p className="t-request-route-note">{approval.message}</p>}
    </> : <p className="t-request-route-note" role="status">유형·일자 조회 후 확인</p>}
  </section>;
}
function ApplicantFacts({ model }: { model: AnnualModel }) {
  const employee = model.employeeHeader;
  return <section className="t-request-applicant" aria-labelledby="annual-applicant">
    <div className="t-request-context-heading"><h3 id="annual-applicant">신청자</h3></div>
    <dl className="t-request-context-facts">
      <div><dt>이름</dt><dd>{employee?.employeeName ?? model.workspaceEmployee?.employeeName ?? '확인 필요'}</dd></div>
      <div><dt>소속</dt><dd>{employee?.organizationName ?? (employee ? '소속 미배정' : model.workspaceEmployee?.companyName ?? '확인 필요')}</dd></div>
      {employee?.employeeNo && <div><dt>사번</dt><dd>{employee.employeeNo}</dd></div>}
      <div><dt>작성 기준일</dt><dd>{fmtDate(model.annual.today)}</dd></div>
    </dl>
  </section>;
}
function RequestComposer({ initial, model, onClose, submitting, embedded = false }: { initial: ComposeView; model: AnnualModel; onClose: () => void; submitting: boolean; embedded?: boolean }) {
  type Step = 'TYPE' | 'CONTENT' | 'REVIEW';
  const modal = embedded || (initial.mode === 'MODAL' && typeof HTMLDialogElement !== 'undefined' && typeof HTMLDialogElement.prototype.showModal === 'function');
  const initialDate = (initial.kind === 'LEAVE_DAY' ? initial.leaveForm.from : initial.correctionForm.workDate) ?? model.annual.today;
  const opensApplication = embedded || location.pathname === '/me/requests/new';
  const preserveOpeningFocus = modal && opensApplication;
  const [step, setStep] = useState<Step>(initial.preview && !initial.conflict && !model.error ? 'REVIEW' : opensApplication || model.error || initial.conflict || initial.leaveForm.reason || initial.correctionForm.reason || !modal ? 'CONTENT' : 'TYPE');
  const previousStep = useRef(step);
  const [compose, setCompose] = useState(initial);
  const [leave, setLeave] = useState(initial.leaveForm), [correction, setCorrection] = useState(initial.correctionForm);
  const [kind, setKind] = useState(initial.kind), [date, setDate] = useState(initialDate);
  const correctionDate = initial.correctionForm.workDate ?? model.annual.today;
  const lookupDates = useRef({ LEAVE_DAY: initial.leaveForm.from ?? model.annual.today, ATTENDANCE_CORRECTION: initial.kind === 'LEAVE_DAY' && correctionDate > model.annual.today ? model.annual.today : correctionDate });
  const [ready, setReady] = useState(Boolean(initial.preview?.eligible) && !initial.conflict && !model.error);
  const [dirty, setDirty] = useState(Boolean(model.error || initial.preview || initial.leaveForm.reason || initial.correctionForm.reason));
  const [busy, setBusy] = useState(false), [error, setError] = useState(model.error ?? '');
  const dialog = useRef<HTMLDialogElement>(null), form = useRef<HTMLFormElement>(null), notice = useRef<HTMLParagraphElement>(null);
  const typePicker = useRef<HTMLFieldSetElement>(null);
  const activeRequest = useRef(false), previewHeading = useRef<HTMLHeadingElement>(null), contentHeading = useRef<HTMLHeadingElement>(null);
  const locked = busy || submitting;
  const isLeave = compose.kind === 'LEAVE_DAY', current = isLeave ? leave : correction;
  const loadedDate = (isLeave ? compose.leaveForm.from : compose.correctionForm.workDate) ?? model.annual.today;
  const needsContext = kind !== compose.kind || date !== loadedDate;
  const approval = needsContext ? undefined : step === 'REVIEW' ? compose.preview?.approval : compose.approval;
  useUnload(dirty);
  useEffect(() => {
    if (modal && dialog.current && !dialog.current.open) dialog.current.showModal();
    if (preserveOpeningFocus) {
      notice.current?.focus();
      return;
    }
    const target = dialog.current?.querySelector<HTMLElement>('[data-error-focus="true"]') ?? form.current?.querySelector<HTMLElement>('[data-error-focus="true"]');
    target?.focus();
  }, [modal,preserveOpeningFocus]);
  useLayoutEffect(() => { if (error) notice.current?.focus(); }, [error]);
  useEffect(() => { if (compose.preview && !error) previewHeading.current?.focus(); }, [compose.preview,error]);
  useEffect(() => {
    const stepChanged = previousStep.current !== step;
    previousStep.current = step;
    if (error) return;
    if (step === 'CONTENT' && (!preserveOpeningFocus || stepChanged)) contentHeading.current?.focus();
    if (step === 'TYPE') typePicker.current?.querySelector<HTMLInputElement>('input:checked')?.focus();
  }, [step,error,preserveOpeningFocus]);
  function change(field: string, value: string | boolean) {
    if (isLeave) setLeave(current => ({ ...current, [field]: value, commandId: crypto.randomUUID() }));
    else setCorrection(current => ({ ...current, [field]: value, commandId: crypto.randomUUID() }));
    setReady(false); setDirty(true); setError(''); setStep('CONTENT');
  }
  function close() {
    if (locked) return;
    if (embedded) { onClose(); return; }
    if (dirty && !window.confirm('입력한 신청 내용이 사라집니다. 닫을까요?')) return;
    dialog.current?.close(); setDirty(false); onClose();
  }
  async function preview() {
    if (activeRequest.current || submitting || !form.current?.reportValidity()) return;
    const body = encodeForm(new FormData(form.current));
    activeRequest.current = true; setBusy(true); setError('');
    try {
      const result = await fetchPage('/me/requests/preview', { method: 'POST', body });
      const next = result.envelope.data.compose as ComposeView | null;
      if (!next) throw new PageRequestError(503, '미리보기 결과를 확인하지 못했습니다. 같은 내용으로 다시 확인해 주세요.');
      setCompose(next); setLeave(next.leaveForm); setCorrection(next.correctionForm);
      const nextDate = (next.kind === 'LEAVE_DAY' ? next.leaveForm.from : next.correctionForm.workDate) ?? model.annual.today;
      setDate(nextDate);
      lookupDates.current[next.kind] = nextDate;
      setReady(result.status < 400 && Boolean(next.preview?.eligible) && !next.conflict);
      setError(String(result.envelope.data.error ?? '')); setDirty(true);
      setStep(result.status < 400 && next.preview && !next.conflict ? 'REVIEW' : 'CONTENT');
    } catch (failure) {
      setReady(false); setError(failure instanceof PageRequestError ? failure.message : '미리보기를 확인하지 못했습니다. 입력과 명령을 유지했습니다. 같은 내용으로 다시 확인해 주세요.');
    } finally { activeRequest.current = false; setBusy(false); }
  }
  function confirmLatest() {
    if (!isLeave && compose.day) setCorrection(current => ({ ...current, baseInputRevision: compose.day!.inputRevision, sourceFingerprint: compose.day!.sourceFingerprint, replacesRequestId: compose.currentCorrectionRequestId ?? null, commandId: crypto.randomUUID() }));
    else setLeave(current => ({ ...current, commandId: crypto.randomUUID() }));
    setCompose(current => ({ ...current, conflict: false })); setReady(false); setDirty(true); setError(''); setStep('CONTENT');
  }
  function submit(event: FormEvent<HTMLFormElement>) {
    if (!ready || locked || compose.conflict) { event.preventDefault(); event.stopPropagation(); if (!locked && !compose.conflict) void preview(); }
  }
  const content = <>
    {!embedded && <header className="t-modal-head"><div><h2 id="annual-compose-title">근태 신청</h2><p className="t-request-year">{model.annual.year}년 신청</p><p id="annual-compose-description">필요한 내용을 차례로 작성하고, 제출 전에 한 번 더 확인하세요.</p></div><button type="button" className="t-btn t-request-close" aria-label="신청 창 닫기" onClick={close} disabled={locked}><RequestIcon kind="CLOSE"/></button></header>}
    <div className="t-request-form-context"><ApprovalLine approval={approval} checked={step==='REVIEW'} /><ApplicantFacts model={model} /></div>
    <nav className="t-request-steps" aria-label="신청 작성 단계"><ol>{(['TYPE','CONTENT','REVIEW'] as const).map((value,index)=><li key={value} data-complete={String(index < ['TYPE','CONTENT','REVIEW'].indexOf(step))}><span className="t-request-step-number" aria-hidden="true">{index+1}</span><span aria-current={step===value?'step':undefined}>{['신청 유형','내용 작성','제출 전 확인'][index]}</span></li>)}</ol></nav>
    <div className="t-request-layout"><div className="t-request-main">
    {error && <p ref={notice} className="notice error" role="alert" tabIndex={-1} data-error-focus="true">{error}</p>}
    <Form action="/me/requests/new" method="get" className="t-annual-context" hidden={step==='REVIEW'}>
      {hidden('year', model.annual.year)}{hidden('mode', modal ? 'MODAL' : 'PAGE')}
      {kind === compose.kind && current.predecessorRequestId && hidden('predecessorRequestId', current.predecessorRequestId)}
      <fieldset ref={typePicker} className="t-request-type-picker" hidden={step!=='TYPE'} disabled={locked}><legend>어떤 신청을 하시겠어요?</legend><div className="t-request-type-cards">
        {(['LEAVE_DAY','ATTENDANCE_CORRECTION'] as const).map(value=><label className="t-request-type-card" key={value} data-selected={String(kind===value)}><input type="radio" name="kind" value={value} checked={kind===value} onChange={()=>{setKind(value);setDate(lookupDates.current[value]);}} data-error-focus={!preserveOpeningFocus&&step==='TYPE'&&kind===value&&!error?'true':undefined}/><span className="t-request-type-icon"><RequestIcon kind={value}/></span><strong>{kindName(value)}</strong><small>{value==='LEAVE_DAY'?'하루 단위로 연차를 사용해요.':'출퇴근 시각과 실제 휴게를 바로잡아요.'}</small></label>)}
      </div><p className="t-muted">병가·반차·시간차 등 미지원 초안은 신청할 수 없습니다.</p></fieldset>
      <div className="t-request-context-date" hidden={step==='CONTENT'&&kind==='LEAVE_DAY'}><label htmlFor="annual-date">{kind === 'LEAVE_DAY' ? '신청 날짜' : '정정할 날짜'}</label><input id="annual-date" className="t-input" type="date" disabled={locked} name="date" value={date} onChange={event => {setDate(event.target.value);lookupDates.current[kind]=event.target.value;}} max={kind === 'ATTENDANCE_CORRECTION' ? model.annual.today : undefined} required />
        <button type={step==='TYPE'&&!needsContext?'button':'submit'} className={`t-btn ${step==='TYPE'?'primary':''}`} disabled={locked} onClick={event=>{if(step==='TYPE'&&!needsContext){event.preventDefault();if(event.currentTarget.form?.reportValidity())setStep('CONTENT');}}}>{step==='TYPE'?'내용 작성으로':'유형·일자 조회'}</button>
      </div>
      {step==='CONTENT'&&<p className="t-request-kind-summary"><span>{kindName(compose.kind)}</span><button type="button" className="t-btn t-request-text-button" disabled={locked} onClick={()=>setStep('TYPE')}>유형 변경</button></p>}
    </Form>
    {kind === compose.kind ? <Form action={isLeave ? '/me/requests/leave' : '/me/requests/correction'} className="t-annual-compose-form" onSubmit={submit} hidden={step==='TYPE'} aria-labelledby={step==='CONTENT'?'annual-application-title':undefined}>
      {hidden('year', model.annual.year)}{hidden('mode', modal ? 'MODAL' : 'PAGE')}{hidden('kind', compose.kind)}{hidden('commandId', current.commandId)}
      {current.predecessorRequestId && hidden('predecessorRequestId', current.predecessorRequestId)}
      <fieldset ref={element => { form.current = element?.form ?? null; }} disabled={locked}>
        <div className="t-request-content-fields" hidden={step!=='CONTENT'}>
        <h3 id="annual-application-title" ref={contentHeading} tabIndex={-1} data-error-focus={!preserveOpeningFocus&&step==='CONTENT'&&!error?'true':undefined}>신청서</h3>
        {isLeave ? <>
          <div className="t-request-field"><label htmlFor="annual-bucket">사용할 연차</label><select id="annual-bucket" name="bucketId" className="t-select" value={leave.bucketId ?? ''} onChange={event => change('bucketId', event.target.value)} required><option value="">선택하세요</option>{model.buckets.map(bucket => <option key={bucket.id} value={bucket.id}>사용 가능 {bucket.availableDays} DAY · {fmtDate(bucket.usableFrom)} ~ {bucket.usableTo ? fmtDate(bucket.usableTo) : '종료 없음'}</option>)}</select></div>
          <div className="t-request-date-range"><div className="t-request-field"><label htmlFor="annual-from">시작일</label><input id="annual-from" className="t-input" type="date" name="from" value={leave.from ?? ''} onChange={event => change('from', event.target.value)} min={model.annual.today} required /></div><span aria-hidden="true">→</span><div className="t-request-field"><label htmlFor="annual-to">종료일 (포함)</label><input id="annual-to" className="t-input" type="date" name="to" value={leave.to ?? ''} onChange={event => change('to', event.target.value)} min={leave.from ?? undefined} required /></div></div>
        </> : <>
          {hidden('workDate', correction.workDate)}{hidden('baseInputRevision', correction.baseInputRevision)}{hidden('sourceFingerprint', correction.sourceFingerprint)}{hidden('replacesRequestId', correction.replacesRequestId)}
          <p>대상 근무일 {fmtDate(correction.workDate)}</p>
          <section className="notice" role="region" aria-label="현재 정정 근거"><strong>현재 유효한 출퇴근 근거</strong>
            {(compose.day?.effectiveEvents ?? compose.day?.events ?? []).length ? <ul>{(compose.day?.effectiveEvents ?? compose.day?.events ?? []).map(event=><li key={event.id}>{event.kind==='IN'?'출근':'퇴근'} · {fmtDateTime(event.occurredAt)} (서울)</li>)}</ul> : <p>조회된 유효 출퇴근 기록이 없습니다. 확인된 실제 근거로만 정정하세요.</p>}
            {compose.day?.confirmation ? <p>실제 근로·휴게 확인 {fmtDateTime(compose.day.confirmation.confirmedAt)}</p> : <p>실제 근로·휴게 확인이 아직 없습니다.</p>}
          </section>
          <p className="t-muted">원천 기록을 보존하고 확인된 근거로 정정 심사를 요청합니다.</p>
          {(['in', 'out'] as const).map(direction => <div className="t-annual-correction" key={direction}><label htmlFor={`annual-${direction}-action`}>{direction === 'in' ? '출근 변경' : '퇴근 변경'}</label><select id={`annual-${direction}-action`} className="t-select" name={`${direction}Action`} value={correction[`${direction}Action`]} onChange={event => { change(`${direction}Action`, event.target.value); if (event.target.value !== 'SET') change(direction === 'in' ? 'requestedInAt' : 'requestedOutAt', ''); }}><option value="KEEP">유지</option><option value="SET">시각 수정</option><option value="CLEAR">누락으로 정정</option></select><label htmlFor={`annual-${direction}-time`}>제안 {direction === 'in' ? '출근' : '퇴근'} 시각 (서울)</label><input id={`annual-${direction}-time`} className="t-input" type="datetime-local" step="0.001" name={direction === 'in' ? 'requestedInAt' : 'requestedOutAt'} value={(direction === 'in' ? correction.requestedInAt : correction.requestedOutAt) ?? ''} onChange={event => change(direction === 'in' ? 'requestedInAt' : 'requestedOutAt', event.target.value)} readOnly={correction[`${direction}Action`] !== 'SET'} /></div>)}
          <label className="checkbox-label"><input type="checkbox" name="presenceAsWork" value="true" checked={correction.presenceAsWork} onChange={event => change('presenceAsWork', event.target.checked)} />제안 구간의 실제 근로를 확인했습니다</label>
          <label className="checkbox-label"><input type="checkbox" name="breaksConfirmed" value="true" checked={correction.breaksConfirmed} onChange={event => change('breaksConfirmed', event.target.checked)} />실제 휴게를 확인했습니다</label>
          <RequestBreakEditor value={correction.actualBreaks ?? ''} onChange={value=>change('actualBreaks',value)} disabled={locked}/>
        </>}
        <div className="t-request-field"><label htmlFor="annual-reason">신청 사유 <span aria-hidden="true">{(current.reason??'').length}/500</span></label><textarea id="annual-reason" aria-label="신청 사유" name="reason" maxLength={500} rows={3} value={current.reason ?? ''} onChange={event => change('reason', event.target.value)} required /></div>
        <p className="t-muted">민감정보를 적지 마세요. 대기 신청은 잔여를 예약하거나 차감하지 않습니다.</p>
        {compose.conflict && <div className="notice"><p>다른 변경이 반영됐습니다. 제안값은 보존했습니다. 최신 근거를 확인한 뒤 다시 미리보기 하세요.</p><button type="button" className="t-btn" onClick={confirmLatest}>최신 근거 확인 후 편집 유지</button></div>}
        </div>
        {compose.preview && <section className="t-request-preview" aria-label="신청 미리보기" hidden={step!=='REVIEW'}>
          <h3 ref={previewHeading} tabIndex={-1} data-error-focus={!preserveOpeningFocus&&step==='REVIEW'&&!error?'true':undefined}>신청 미리보기</h3>
          <p className="t-request-review-kind">{kindName(compose.kind)}</p>
          <dl className="t-request-review-details">
            <div><dt>대상 기간</dt><dd>{isLeave?`${fmtDate(leave.from)} ~ ${fmtDate(leave.to)}`:fmtDate(correction.workDate)}</dd></div>
            {!isLeave&&<>
              {(['in','out'] as const).map(direction=><div key={direction}><dt>{direction==='in'?'제안 출근':'제안 퇴근'}</dt><dd>{actionName(correction[`${direction}Action`])}{correction[`${direction}Action`]==='SET'&&` · ${(direction==='in'?correction.requestedInAt:correction.requestedOutAt)?.replace('T',' ')}`}</dd></div>)}
              <div><dt>실제 확인</dt><dd>{correction.presenceAsWork?'실제 근로 확인':'실제 근로 미확인'} · {correction.breaksConfirmed?'실제 휴게 확인':'실제 휴게 미확인'}</dd></div>
              <div><dt>휴게 구간</dt><dd>{correction.actualBreaks?correction.actualBreaks.split(',').map((part,index)=><div key={index}>{part.replaceAll('T',' ').replace('/',' → ')}</div>):'휴게 구간 없음'}</dd></div>
            </>}
            <div><dt>신청 사유</dt><dd>{current.reason}</dd></div>
          </dl>
          <div className="t-request-review-totals">{compose.preview.quantityDays != null && <p className="t-request-review-quantity">예상 차감 {compose.preview.quantityDays} DAY <small>선택 묶음 사용 가능 {compose.preview.selectedBucketAvailableDays} DAY</small></p>}
          <p>조회 시점 오늘 사용 가능 {compose.preview.availableDays} DAY</p></div>
          {compose.preview.policy && <p>{compose.preview.policy.name} · 정책 v{compose.preview.policy.revision}</p>}
          <p>{compose.preview.message}</p><p className="t-muted">{ready ? '제출·승인 시 다시 검증합니다.' : '입력 또는 근거가 바뀌었습니다. 미리보기를 다시 확인하세요.'}</p>
        </section>}
        <div className="t-request-footer">{step==='CONTENT'?<><button type="button" className="t-btn" disabled={locked} onClick={()=>setStep('TYPE')}>이전</button><button type="button" className="t-btn primary" onClick={() => void preview()} disabled={locked || compose.conflict}>{busy?'확인 중…':'미리보기'}</button></>:<><button type="button" className="t-btn" disabled={locked} onClick={()=>{setReady(false);setStep('CONTENT');}}>내용 수정</button><button className="t-btn primary" disabled={!ready || locked || compose.conflict}>{submitting?'제출 중…':'신청 제출'}</button></>}</div>
      </fieldset>
    </Form> : <p className="notice">유형·일자 조회를 눌러 현재 근거와 새 작성 폼을 확인하세요.</p>}
    {!embedded&&<a className="t-request-page-link" href={url('/me/requests/new', { year: model.annual.year, kind: compose.kind, date: isLeave ? leave.from : correction.workDate, mode: 'PAGE', predecessorRequestId: current.predecessorRequestId })}>일반 작성 페이지</a>}
    </div><aside className="t-request-summary" aria-label="신청 요약">
      <div className="t-request-summary-head"><span className="t-request-summary-icon"><RequestIcon kind={kind}/></span><span className="t-request-summary-stage">{step==='REVIEW'?'제출 전 확인':'작성 중'}</span></div>
      <h3>신청 요약</h3><p className="t-request-summary-intro">입력한 내용을 한눈에 확인하세요.</p>
      <dl className="t-request-summary-facts">
        <div><dt>신청 유형</dt><dd>{kindName(kind)}</dd></div>
        <div><dt>{step==='TYPE'||needsContext?'기준 날짜':'대상 기간'}</dt><dd>{step==='TYPE'||needsContext?fmtDate(date):isLeave?`${fmtDate(leave.from)} ~ ${fmtDate(leave.to)}`:fmtDate(correction.workDate)}</dd></div>
        <div><dt>오늘 사용 가능</dt><dd>{step==='REVIEW'&&compose.preview?compose.preview.availableDays:model.annual.currentAvailableDays} DAY</dd></div>
        {!embedded&&<div><dt>예정 결재 담당</dt><dd>{needsContext?'유형·일자 조회 후 확인':approval?.approverName??'미지정'}</dd></div>}
      </dl>
      <p className="t-request-summary-note">현재 잔여는 조회 시점 기준입니다. 신청 대기 중에는 예약·차감하지 않으며 제출·승인 시 다시 확인합니다.</p>
    </aside></div>
    {!embedded&&<footer className="t-request-dismiss"><span>제출 전까지 신청 내용은 저장되지 않습니다.</span><button type="button" className="t-btn" onClick={close} disabled={locked}>닫기</button></footer>}
  </>;
  return modal&&!embedded ? <dialog ref={dialog} className="t-request-dialog" aria-modal="true" aria-labelledby="annual-compose-title" aria-describedby="annual-compose-description" onCancel={event => { event.preventDefault(); close(); }} data-unsaved-changes={String(dirty)}>{content}</dialog> : <section className={`t-request-compose-page${embedded?' t-request-embedded':' t-card'}`} aria-labelledby={embedded?undefined:'annual-compose-title'} data-unsaved-changes={String(dirty)} data-modal-busy={String(locked)}>{content}</section>;
}
function Annual({ model: m, submitting }: { model: AnnualModel; submitting: boolean }) {
  const a=m.annual, [open,setOpen]=useState(Boolean(m.compose)), trigger=useRef<HTMLAnchorElement>(null);
  const [search,setSearch]=useState('');
  const query=search.trim().toLocaleLowerCase('ko-KR');
  const requests=query?a.requests.filter(row=>[kindName(row.kind),row.targetEmployeeName,row.reason,row.fromDate,row.toDate,statusName(row.status),row.approverName].some(value=>value?.toLocaleLowerCase('ko-KR').includes(query))):a.requests;
  const missing=a.coverage==='NO_REGISTERED_DATA';
  useEffect(()=>{if(!open&&m.compose)trigger.current?.focus();},[open,m.compose]);
  const metrics: [string, ReactNode, string][] = [
    ['사용',missing?'등록 자료 없음':`${a.usedDays} DAY`,'선택 연도 · 오늘까지 유효한 승인'],
    ['사용 예정',missing?'등록 자료 없음':`${a.plannedDays} DAY`,'오늘 이후 · 승인 시 이미 차감'],
    ['결재 대기',missing?'등록 자료 없음':`${a.pendingDays} DAY`,'대기는 예약·차감하지 않음'],
    ['현재 사용 가능',`${a.currentAvailableDays} DAY`,'조회 시점 오늘 · 선택 연도와 무관'],
    ['확인된 등록 기초·조정',a.registeredDays==null?(a.unknownBucketCount?'귀속 미확인':'등록 자료 없음'):`${a.registeredDays} DAY`,a.attributionPartial?'귀속 확인 자료의 부분합':'자동 발생량·연초 잔여가 아님'],
    ['귀속연도 원장 잔액',a.attributedBalanceDays==null?(a.unknownBucketCount?'귀속 미확인':'등록 자료 없음'):`${a.attributedBalanceDays} DAY`,'확인 묶음의 현재 잔액 · 연말 잔여 아님'],
  ];
  return <section className="t-annual-page"><header className="t-pagehead"><div><h1>내 근태현황·신청</h1><p className="t-muted">대상 휴가일·근무일 기준 {a.year}년 현황 · 모든 상태는 조회 시점 현재입니다.</p></div><a ref={trigger} role="button" className="t-btn primary t-request-start" href={url('/me/requests/new',{year:a.year})} onKeyDown={event=>{if(event.key===' '){event.preventDefault();event.currentTarget.click();}}}><span aria-hidden="true">＋</span> 신청</a></header>
    <p className="t-muted">기준 시각 {fmtDateTime(a.asOf)} (서울) · 목록 필터와 무관한 연도 전체 등록 자료 기준입니다.</p>
    {missing&&<p className="notice">선택 연도의 등록 자료가 없습니다. 연간 전체 사용량이나 자동 발생량이 0이라고 확정할 수 없습니다.</p>}
    {a.createdId&&<section className="t-request-success" role="status" aria-label="신청 접수 결과"><span className="t-request-success-icon" aria-hidden="true">✓</span><div><h2>신청을 접수했습니다.</h2><p>승인 결과는 신청 상세에서 확인할 수 있어요.</p>{a.outsideYear&&<p>신청 기간이 조회 연도 밖입니다. <a href={url('/me/requests',{year:a.outsideYear})}>{a.outsideYear}년 현황 보기</a></p>}</div><a className="t-btn" href={`/requests/${a.createdId}`}>생성한 신청 상세 </a></section>}
    <div className="t-annual-metrics">{[metrics[3],...metrics.slice(0,3)].map(([label,value,hint])=><section className={`t-card${label==='현재 사용 가능'?' t-request-available':''}`} key={label}><h2>{label}</h2><strong>{value}</strong><p>{hint}</p></section>)}</div>
    <dl className="t-request-balance-basis">{metrics.slice(4).map(([label,value,hint])=><div key={label}><dt>{label}</dt><dd>{value}<small>{hint}</small></dd></div>)}</dl>
    {a.unknownBucketCount>0&&<p className="notice">귀속 미확인 묶음 {a.unknownBucketCount}개. 기준일의 연도를 귀속연도로 추정하지 않습니다. 담당자의 근거 확인이 필요합니다.</p>}
    <Form action="/me/requests" method="get" className="t-card t-annual-filters" role="search" aria-label="연도별 근태 조회"><div className="t-request-filter-field"><label htmlFor="annual-year">조회 연도</label><input id="annual-year" className="t-input" name="year" type="number" min="1900" max="9998" defaultValue={a.year} required /></div><div className="t-request-filter-field"><label htmlFor="annual-type-filter">근태유형</label><select id="annual-type-filter" name="typeId" className="t-select" defaultValue={a.typeId??''}><option value="">전체 유형</option>{a.types.map(type=><option key={type.id} value={type.id}>{type.name}{!type.supported?' (신청 미지원)':''}</option>)}</select></div><div className="t-request-filter-field"><label htmlFor="annual-status">상태</label><select id="annual-status" name="status" className="t-select" defaultValue={a.status}><option value="">전체 상태</option>{['PENDING','APPROVED','REJECTED','WITHDRAWN'].map(status=><option key={status} value={status}>{statusName(status)}</option>)}</select></div><div className="t-request-filter-actions"><button className="t-btn">조회</button></div></Form>
    <section className="t-card" aria-labelledby="annual-request-history"><header className="t-request-list-head"><div><h2 id="annual-request-history">내 대상 신청 이력</h2><p className="t-muted">본인 대상의 원신청 기간·일수입니다. 연간 수치는 해당 연도 날짜만 집계합니다.</p></div><label className="t-request-list-search">현재 페이지 신청 검색<input aria-label="현재 페이지 신청 검색" className="t-input" type="search" value={search} placeholder="유형, 사유, 날짜 검색" onChange={event=>setSearch(event.target.value)}/><span>불러온 최대 50건에서 검색</span></label></header><RequestTable rows={requests} year={a.year} canResubmit={a.canResubmitRequestIds ?? []}/><Pages annual={a}/></section>
    <section className="t-card"><h2>내가 대리 작성한 이력</h2><p className="t-muted">다른 사원 대상의 작성 이력이며 본인 수치에 포함하지 않습니다. 위 목록의 유형·상태 필터와 독립입니다.</p><RequestTable rows={a.authoredRequests}/><Pages annual={a} authored/></section>
    <section className="t-card"><h2 id="annual-bucket-summary">기초 묶음·귀속 정보</h2><p className="t-muted">귀속연도 잔액은 현재 원장 잔액입니다. 표시된 연간 사용을 다시 빼지 않습니다.</p>
      <div className="table-scroll t-annual-summary-scroll"><table className="t-annual-summary-table" role="table" aria-labelledby="annual-bucket-summary">
        <thead role="rowgroup"><tr role="row"><th scope="col" role="columnheader">귀속</th><th scope="col" role="columnheader">사용 가능 기간</th><th scope="col" role="columnheader">현재 잔액</th><th scope="col" role="columnheader">오늘 사용 가능</th><th scope="col" role="columnheader">확인 상태·근거</th></tr></thead>
        <tbody role="rowgroup">{a.attributionBuckets.map(bucket=><tr key={bucket.bucketId} role="row">
          <td role="cell" data-label="귀속"><div className="t-annual-summary-value">{bucket.classificationStatus==='CONFIRMED'?`${bucket.entitlementYear}년`:'귀속 미확인'}</div></td>
          <td role="cell" data-label="사용 가능 기간"><div className="t-annual-summary-value">{fmtDate(bucket.usableFrom)} ~ {bucket.usableTo?fmtDate(bucket.usableTo):'종료 없음'}</div></td>
          <td role="cell" data-label="현재 잔액"><div className="t-annual-summary-value">{bucket.balanceDays} DAY</div></td>
          <td role="cell" data-label="오늘 사용 가능"><div className="t-annual-summary-value">{bucket.availableDays} DAY</div></td>
          <td role="cell" data-label="확인 상태·근거"><div className="t-annual-summary-value">{bucket.availability==='CURRENT'?'현재 사용기간':bucket.availability==='EXPIRED'?'사용기간 만료':'사용기간 미개시'}<small>{bucket.basis??'분류 근거 미등록'}</small></div></td>
        </tr>)}</tbody>
      </table></div>
    </section>
    <section className="t-card"><h2 id="annual-type-summary">유형별 연간 신청</h2><p className="t-muted">신청 건수와 휴가 DAY는 서로 다른 단위입니다. 반려·철회 건수도 현재 상태 기준입니다.</p>
      <div className="table-scroll t-annual-summary-scroll"><table className="t-annual-summary-table" role="table" aria-labelledby="annual-type-summary">
        <thead role="rowgroup"><tr role="row"><th scope="col" role="columnheader">근태유형</th><th scope="col" role="columnheader">전체</th><th scope="col" role="columnheader">대기</th><th scope="col" role="columnheader">승인</th><th scope="col" role="columnheader">반려</th><th scope="col" role="columnheader">철회</th><th scope="col" role="columnheader">휴가 DAY</th></tr></thead>
        <tbody role="rowgroup">{a.summaries.map(summary=><tr key={`${summary.typeId}-${summary.kind}`} role="row">
          <td role="cell" data-label="근태유형"><div className="t-annual-summary-value">{summary.name}<small>{kindName(summary.kind)}</small></div></td>
          <td role="cell" data-label="전체"><div className="t-annual-summary-value">{summary.requestCount}건</div></td>
          <td role="cell" data-label="대기"><div className="t-annual-summary-value">{summary.pendingCount}건</div></td>
          <td role="cell" data-label="승인"><div className="t-annual-summary-value">{summary.approvedCount}건</div></td>
          <td role="cell" data-label="반려"><div className="t-annual-summary-value">{summary.rejectedCount}건</div></td>
          <td role="cell" data-label="철회"><div className="t-annual-summary-value">{summary.withdrawnCount}건</div></td>
          <td role="cell" data-label="휴가 DAY"><div className="t-annual-summary-value">{summary.kind==='LEAVE_DAY'?`사용 ${summary.usedDays} / 예정 ${summary.plannedDays} / 대기 ${summary.pendingDays}`:'해당 없음'}</div></td>
        </tr>)}</tbody>
      </table></div>
    </section>
    {open&&m.compose&&<RequestComposer initial={m.compose} model={m} submitting={submitting} onClose={()=>setOpen(false)}/>}
  </section>;
}
export function AnnualRequestPage({ page,data,busy,onClose }:PageProps) {
  if(page==='request/mine'&&data.annual&&data.compose&&onClose) return <RequestComposer initial={data.compose as ComposeView} model={data as unknown as AnnualModel} submitting={Boolean(busy)} onClose={onClose} embedded/>;
  return page==='request/mine'&&data.annual?<Annual model={data as unknown as AnnualModel} submitting={Boolean(busy)}/>:null;
}

export function LeaveAttributionPage({page,data}:PageProps){
  if(page!=='request/attributions')return null;
  return <AttributionManagement model={data.leaveAttributions as AttributionView} error={data.error as string|undefined}/>;
}
function AttributionManagement({model:m,error}:{model:AttributionView;error?:string}) {
  const [status,setStatus]=useState(m.form.classificationStatus), [dirty,setDirty]=useState(Boolean(error));
  const [command,setCommand]=useState(m.form.commandId), [expected,setExpected]=useState(m.form.expectedRevision);
  const latest=m.history[0]?.revision ?? 0, conflict=latest!==expected;
  useUnload(dirty);
  const change=()=>{setDirty(true);setCommand(crypto.randomUUID());};
  return <section className="t-annual-page">
    <header className="t-pagehead"><div><h1>기초 묶음 귀속연도</h1><p>{m.bucket.employeeName} · {m.bucket.balanceDays} DAY 현재 잔액</p></div><a className="t-btn" href={`/admin/leave-balances/buckets/${m.bucket.id}`}>원장 보기</a></header>
    <p className="notice">묶음 전체가 단일 귀속연도라는 근거가 있을 때만 확정하세요. 혼합된 묶음은 미분류로 유지합니다. 분류는 기초·사용·복원 원장을 바꾸지 않습니다.</p>
    <section className="t-card"><h2>귀속 분류 등록</h2>
      {error&&<p className="notice error" role="alert" tabIndex={-1} data-error-focus="true">{error}</p>}
      {conflict&&<section className="notice"><p>최신 분류는 v{latest}입니다. 이력의 근거와 현재 편집값을 비교한 뒤 확인하세요.</p><button type="button" className="t-btn" onClick={()=>{setExpected(latest);change();}}>최신 이력 확인 후 편집 유지</button></section>}
      <Form action={`/admin/leave-balances/buckets/${m.bucket.id}/attributions`} className="admin-form" onChange={change} onSubmit={event=>{if(conflict)event.preventDefault();}} data-unsaved-changes={String(dirty)}>
        {hidden('expectedRevision',expected)}{hidden('commandId',command)}
        <label htmlFor="attribution-status">분류 상태</label><select id="attribution-status" name="classificationStatus" className="t-select" value={status} onChange={event=>setStatus(event.target.value)}><option value="UNCLASSIFIED">미분류 (확정 해제)</option><option value="CONFIRMED">귀속연도 확정</option></select>
        {status==='CONFIRMED'?<>
          <label htmlFor="entitlement-year">귀속연도</label><input id="entitlement-year" className="t-input" name="entitlementYear" type="number" min="1900" max="9998" defaultValue={m.form.entitlementYear??''} required/>
          <label className="checkbox-label"><input type="checkbox" name="singleYearConfirmed" value="true" defaultChecked={m.form.singleYearConfirmed} required/>묶음 전체가 단일 귀속연도라는 근거를 확인했습니다</label>
        </>:<p>귀속연도를 지정하지 않고 새 미분류 이력을 추가합니다.</p>}
        <label htmlFor="attribution-basis">분류 근거</label><textarea id="attribution-basis" name="basis" rows={3} maxLength={500} defaultValue={m.form.basis} required/>
        <label htmlFor="attribution-reason">변경 사유</label><textarea id="attribution-reason" name="reason" rows={2} maxLength={500} defaultValue={m.form.reason} required/>
        <button className="t-btn primary" disabled={conflict}>귀속 이력 저장</button>
      </Form>
    </section>
    <section className="t-card"><h2>귀속 분류 이력</h2><p className="t-muted">최신 revision을 사용하며 이전 근거는 덮어쓰지 않습니다.</p>
      <div className="table-scroll"><table><thead><tr><th>개정</th><th>분류·연도</th><th>근거·사유</th><th>등록자·시각 (서울)</th></tr></thead><tbody>
        {m.history.map(item=><tr key={item.id}><td>v{item.revision}</td><td>{item.classificationStatus==='CONFIRMED'?`${item.entitlementYear}년 확정`:'미분류'}</td><td>{item.basis}<small>{item.reason}</small></td><td>{item.createdByName??'등록 담당자'}<small>{fmtDateTime(item.createdAt)}</small></td></tr>)}
      </tbody></table></div>
      {!m.history.length&&<p>귀속 미확인입니다. 기준일로 자동 분류하지 않습니다.</p>}
    </section>
  </section>;
}
