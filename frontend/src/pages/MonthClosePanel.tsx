import { useEffect, useLayoutEffect, useRef, useState, type FormEvent } from 'react';
import { PageRequestError } from '../transport';
import type { CsrfToken } from '../types';
import { ErrorNotice, fmtDateTime } from '../ui';
import { monthEndAccessDenied, monthEndReason, monthEndRequest } from './monthEndApi';
import { monthCloseListHref } from './CompanyAttendanceNavigation';

const path = '/admin/attendance/month-close';
type Status = 'OPEN' | 'CLOSED';
type Action = 'CLOSE' | 'REOPEN';
interface MonthState { month: string; status: Status; version: number; pastMonth: boolean }
interface Transition { version: number; status: Status; changedAt: string; actorName: string; reason: string }
interface Snapshot extends MonthState { history: Transition[]; hasMoreHistory?: boolean }
const countLabels = { employeeCount: '점검 사원', reviewDays: '확인할 근무일', uncalculatedDays: '미산정',
  staleDays: '재집계 필요', unconfirmedDays: '근무 확인 필요', pendingCorrectionDays: '정정 결재 대기',
  pendingRequests: '결재 대기 신청', openFollowUps: '진행 중 보완 요청' } as const;
type Inspection = MonthState & Record<keyof typeof countLabels, number> & {
  canClose: boolean; outOfEmploymentFollowUp?: { employeeNo: string; workDate: string } | null;
};
const countCategories = { reviewDays: 'ALL', uncalculatedDays: 'UNCALCULATED', staleDays: 'STALE',
  unconfirmedDays: 'UNCONFIRMED', pendingCorrectionDays: 'PENDING_CORRECTION',
  pendingRequests: 'PENDING_REQUEST', openFollowUps: 'OPEN_FOLLOW_UP' } as const;
type Command = { month: string; action: Action; expectedVersion: number; reason: string; commandId: string };
type Phase = 'loading' | 'failed' | 'ready' | 'checking' | 'editing' | 'saving' | 'uncertain';
export type MonthCloseMode = 'unknown' | 'open' | 'closed' | 'working';

const count = (value: number) => Number.isSafeInteger(value) && value >= 0;
const validState = (value: MonthState, month: string) => value?.month === month && count(value.version)
  && (value.status === 'OPEN' || value.status === 'CLOSED') && typeof value.pastMonth === 'boolean';
const validTransition = (value: Transition) => value && count(value.version) && value.version > 0
  && (value.status === 'OPEN' || value.status === 'CLOSED') && typeof value.reason === 'string'
  && typeof value.actorName === 'string' && typeof value.changedAt === 'string' && Number.isFinite(Date.parse(value.changedAt));

function nextMonthStart(month: string) {
  const [year, number] = month.split('-').map(Number);
  return String(number === 12 ? year + 1 : year) + '-' + String(number % 12 + 1).padStart(2, '0') + '-01';
}

function CheckResults({ check, source }: { check: Inspection; source: string }) {
  const followUp = check.outOfEmploymentFollowUp;
  return <section className="month-close-check" aria-label="회사 전체 점검 결과">
    <h3>회사 전체 점검 결과</h3>
    <dl className="month-close-counts">{(Object.keys(countLabels) as (keyof typeof countLabels)[]).map(key => {
      const value = check[key] + (key === 'employeeCount' ? '명' : '건');
      const href = key !== 'employeeCount' && check[key] > 0
        ? monthCloseListHref(check.month, countCategories[key], 0, source) : null;
      return <div key={key}><dt>{countLabels[key]}</dt><dd>{href ? <a
        href={key === 'pendingRequests' || key === 'openFollowUps' ? href : href + '#month-end-results'}
        aria-label={countLabels[key] + ' ' + value + ' 목록 보기'}>{value}</a> : value}</dd></div>;
    })}</dl>
    {followUp && <p>{followUp.workDate} 보완 요청일이 재직기간 밖에 있습니다. 인사정보와 출퇴근 원천 기록을 확인해 주세요.{' '}
      <a href={'/admin/employees/bulk-edit?q=' + encodeURIComponent(followUp.employeeNo)}>사번 {followUp.employeeNo} 재직기간 확인</a></p>}
    {check.canClose ? <p>미해결 항목이 없어 이 달을 마감할 수 있습니다.</p> : <p>{!check.employeeCount ? '이 달에 재직한 사원이 없습니다.'
      : check.reviewDays || check.pendingRequests || check.openFollowUps ? '남은 근무일·신청·보완 요청을 처리한 뒤 다시 점검해 주세요.'
      : '이 달은 아직 마감할 수 없습니다.'}</p>}
  </section>;
}

interface Props {
  month: string; source: string; csrf: CsrfToken; otherWork: boolean;
  onMode: (mode: MonthCloseMode) => void; onStatusChanged: () => void; deny: (error: PageRequestError) => void;
}
export function MonthClosePanel({ month, source, csrf, otherWork, onMode, onStatusChanged, deny }: Props) {
  const [snapshot, setSnapshot] = useState<Snapshot>();
  const [check, setCheck] = useState<Inspection>();
  const [phase, setPhase] = useState<Phase>('loading');
  const [attempt, setAttempt] = useState(0);
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const [readError, setReadError] = useState('');
  const [notice, setNotice] = useState('');
  const scope = useRef<AbortController | null>(null);
  const minimumVersion = useRef(0);
  const running = useRef(false);
  const command = useRef<Command | null>(null);
  const reasonInput = useRef<HTMLInputElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const focusTrigger = useRef(false);
  const closed = snapshot?.status === 'CLOSED';
  const editing = phase === 'editing' || phase === 'saving' || phase === 'uncertain';
  const mode: MonthCloseMode = editing || phase === 'checking' ? 'working' : !snapshot ? 'unknown' : closed ? 'closed' : 'open';
  const { trimmed, length, forbiddenCharacters, validReason } = monthEndReason(reason);

  useLayoutEffect(() => { onMode(mode); }, [mode, onMode]);
  useEffect(() => {
    const controller = new AbortController();
    scope.current = controller;
    monthEndRequest<Snapshot>(path + '?month=' + encodeURIComponent(month), undefined, controller.signal).then(result => {
      if (controller.signal.aborted) return;
      if (!validState(result, month) || result.version < minimumVersion.current
        || !Array.isArray(result.history) || !result.history.every(validTransition)) throw new Error();
      minimumVersion.current = result.version;
      setSnapshot(result); setPhase('ready');
    }).catch(failure => {
      if (controller.signal.aborted) return;
      if (monthEndAccessDenied(failure)) { deny(failure); return; }
      setReadError('마감 상태를 조회하지 못했습니다. 목록과 계산 근거는 계속 조회할 수 있습니다.');
      setPhase('failed');
    });
    return () => controller.abort();
  }, [month, attempt, deny]);
  useEffect(() => {
    if (phase === 'editing') reasonInput.current?.focus();
    if (phase === 'ready' && !otherWork && focusTrigger.current) {
      trigger.current?.focus(); focusTrigger.current = false;
    }
  }, [phase, otherWork]);
  useEffect(() => { if (otherWork && phase === 'ready') setCheck(undefined); }, [otherWork, phase]);

  function readAgain() {
    setSnapshot(undefined); setCheck(undefined); setReadError(''); setReason('');
    command.current = null; setPhase('loading'); setAttempt(value => value + 1);
  }
  function refreshMonth() {
    focusTrigger.current = true; onStatusChanged(); readAgain();
  }
  function startEditing() {
    command.current = null;
    setReason(''); setError(''); setNotice(''); setPhase('editing');
  }
  async function inspect() {
    const signal = scope.current?.signal;
    if (!snapshot || otherWork || phase !== 'ready' || running.current || !signal || signal.aborted) return;
    running.current = true;
    setPhase('checking'); setCheck(undefined); setNotice(''); setError('');
    try {
      const result = await monthEndRequest<Inspection>(path + '/check', { csrf, body: { month } }, signal);
      if (signal.aborted) return;
      if (!validState(result, month) || typeof result.canClose !== 'boolean'
        || !(Object.keys(countLabels) as (keyof typeof countLabels)[]).every(key => count(result[key]))) throw new Error();
      if (result.status !== snapshot.status || result.version !== snapshot.version) {
        minimumVersion.current = Math.max(minimumVersion.current, result.version);
        setError('월 마감 상태가 변경되었습니다. 최신 상태를 확인해 주세요.'); refreshMonth(); return;
      }
      if (result.canClose && (!result.pastMonth || !result.employeeCount || result.reviewDays || result.pendingRequests || result.openFollowUps)) throw new Error();
      setCheck(result);
      if (result.canClose) startEditing();
      else { focusTrigger.current = true; setPhase('ready'); }
    } catch (failure) {
      if (signal.aborted) return;
      if (monthEndAccessDenied(failure)) { deny(failure); return; }
      setError(failure instanceof PageRequestError ? failure.message : '마감 점검 결과를 확인하지 못했습니다. 다시 점검해 주세요.');
      if (failure instanceof PageRequestError && failure.status === 409) refreshMonth();
      else { focusTrigger.current = true; setPhase('ready'); }
    } finally { running.current = false; }
  }
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const signal = scope.current?.signal;
    if (!snapshot || otherWork || running.current || !signal || signal.aborted || !validReason
      || (phase !== 'editing' && phase !== 'uncertain') || (phase === 'editing' && error) || (!closed && !check?.canClose)) return;
    running.current = true;
    command.current ??= { action: closed ? 'REOPEN' : 'CLOSE', month, expectedVersion: snapshot.version, reason: trimmed, commandId: crypto.randomUUID() };
    const current = command.current;
    setPhase('saving'); setError('');
    try {
      const result = await monthEndRequest<Transition & { month: string }>(path, { csrf, body: current }, signal);
      if (signal.aborted) return;
      if (!validTransition(result) || result.month !== month || result.version !== current.expectedVersion + 1
        || result.status !== (closed ? 'OPEN' : 'CLOSED') || result.reason !== current.reason) throw new Error();
      minimumVersion.current = result.version;
      setNotice(closed ? month + ' 근태를 재개했습니다. 소급 변경을 처리한 뒤 다시 점검하고 마감해 주세요.'
        : month + ' 근태 마감을 기록했습니다.');
      refreshMonth();
    } catch (failure) {
      if (signal.aborted) return;
      if (monthEndAccessDenied(failure)) { deny(failure); return; }
      if (failure instanceof PageRequestError && failure.status < 500) {
        setError(failure.message);
        if (failure.status === 422) { command.current = null; setPhase('editing'); }
        else if (failure.status === 409) refreshMonth();
        else { focusTrigger.current = true; readAgain(); }
      } else {
        setError('처리 결과를 확인하지 못했습니다. 같은 요청을 다시 보내 결과를 확인해 주세요.');
        setPhase('uncertain');
      }
    } finally { running.current = false; }
  }

  return <section className="wl-section month-close" aria-labelledby="month-close-title" data-modal-busy={phase === 'saving'}
    data-unsaved-changes={phase === 'uncertain' || (phase === 'editing' && Boolean(trimmed))}>
    <div className="wl-section-title month-close-header"><h2 id="month-close-title" tabIndex={-1}>월 마감 관리</h2>
      <span>{month} {snapshot && <strong className={closed ? 'month-close-status closed' : 'month-close-status'}>{closed ? '마감 완료' : '마감 전'}</strong>}</span></div>
    {notice && <p role="status">{notice}</p>}
    <ErrorNotice message={error} />
    <ErrorNotice message={readError} />
    {phase === 'loading' && <p role="status">마감 상태를 조회하고 있습니다.</p>}
    {phase === 'failed' && <button className="t-btn" type="button" onClick={readAgain}>마감 상태 다시 조회</button>}
    {snapshot && <>
      <p>{closed ? '마감한 달의 정정·재집계·보완 요청을 처리하려면 먼저 재개해 주세요.'
        : '목록의 조회 조건과 관계없이 회사 전체의 근태를 점검합니다.'}</p>
      {!snapshot.pastMonth && <p>{nextMonthStart(month)}부터 마감할 수 있습니다.</p>}
      {!editing && <button ref={trigger} className="t-btn" type="button" disabled={otherWork || phase !== 'ready'}
        onClick={closed ? startEditing : inspect}>{closed ? '월 마감 재개' : phase === 'checking' ? '회사 전체 점검 중' : '회사 전체 마감 점검'}</button>}
      {check && <CheckResults check={check} source={source} />}
      {editing && <form action={path} method="post" className="month-review-batch-form" onSubmit={save}>
        <label htmlFor="month-close-reason">{closed ? '월 재개 사유' : '월 마감 사유'}</label>
        <input id="month-close-reason" name="reason" type="text" className="t-input" required ref={reasonInput}
          value={reason} readOnly={phase !== 'editing'} aria-invalid={Boolean(reason) && !validReason || phase === 'editing' && Boolean(error) || undefined}
          aria-describedby="month-close-reason-help" onChange={event => { setReason(event.currentTarget.value); setError(''); }} />
        <small id="month-close-reason-help">한 줄 1~500자 · 앞뒤 공백 제외 · 현재 {length}자</small>
        {forbiddenCharacters && <ErrorNotice message="탭·제어 문자와 올바르지 않은 문자는 사용할 수 없습니다." />}
        <div className="actions"><button className="t-btn primary" type="submit"
          disabled={phase === 'saving' || !validReason || (phase === 'editing' && Boolean(error))}>
          {phase === 'uncertain' ? '같은 요청 다시 보내기' : phase === 'saving' ? '처리 중' : closed ? '재개 확정' : '월 마감 확정'}</button>
          {phase === 'editing' && <button className="t-btn" type="button" onClick={() => {
            setReason(''); setError(''); setCheck(undefined); focusTrigger.current = true; setPhase('ready');
          }}>취소</button>}</div>
      </form>}
      {snapshot.history.length > 0 && <div className="month-close-history"><h3>마감 처리 이력 <small>서울 시각</small></h3>
        <ol aria-label="마감 처리 이력">{snapshot.history.map(item => <li key={item.version}>
          <strong>{item.status === 'CLOSED' ? '마감' : '재개'}</strong><time dateTime={item.changedAt}>{fmtDateTime(item.changedAt)}</time>
          <span>{item.actorName}</span><span>{item.reason}</span></li>)}</ol>
        {snapshot.hasMoreHistory && <small>최근 {snapshot.history.length}건을 표시합니다.</small>}</div>}
    </>}
  </section>;
}
