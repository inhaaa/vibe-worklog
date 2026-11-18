import { useEffect, useRef, useState, type FormEvent } from 'react';
import { PAGE_ACCEPT, PageRequestError } from '../transport';
import type { CsrfToken } from '../types';
import { duration, ErrorNotice } from '../ui';
import { monthEndReasons } from './CompanyAttendanceNavigation';
import type { MonthEndReview } from './MonthEndReviewPage';

type Reason = keyof typeof monthEndReasons;
type Day = { employeeId: string; workDate: string };
type DisplayDay = Day & { employeeNo: string; employeeName: string; reasons: Reason[] };
type PreviewRow = DisplayDay & { weekStart: string; weekEnd: string };
type ResultRow = DisplayDay & { resultId: string; actualWorkMillis: number | null };
type Preview = { previewToken: string; weekCount: number; rows: PreviewRow[] };
type Receipt = { batchId: string; weekCount: number; rows: ResultRow[] };
export type BatchSelection = Pick<MonthEndReview, 'month' | 'organizationId' | 'q' | 'reason' | 'page'> & { rows: Day[] };
type Command = BatchSelection & { previewToken: string; calculationReason: string; commandId: string };

const path = '/admin/calculations/month-end';
export const dayKey = (row: Day) => row.employeeId + '/' + row.workDate;
const reasonNames = (reasons: Reason[]) => reasons.map(reason => monthEndReasons[reason]).join(' · ');
const denied = (error: unknown): error is PageRequestError => error instanceof PageRequestError &&
  (error.status === 401 || error.status === 403 || error.reauthenticate);
// Match String.strip() in OrgCommands.requiredText, including its non-breaking space exceptions.
const javaWhitespace = '[\\u0009-\\u000d\\u001c-\\u0020\\u1680\\u2000-\\u2006\\u2008-\\u200a\\u2028\\u2029\\u205f\\u3000]';
const reasonEdges = new RegExp('^' + javaWhitespace + '+|' + javaWhitespace + '+$', 'gu');

async function post<T>(url: string, body: BatchSelection | Command, csrf: CsrfToken, signal?: AbortSignal): Promise<T> {
  const response = await fetch(url, { method: 'POST', credentials: 'same-origin', signal,
    headers: { Accept: 'application/json, ' + PAGE_ACCEPT + ';q=0.9', 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify(body) });
  if (response.redirected) throw new PageRequestError(401, '다시 로그인해 주세요.');
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new PageRequestError(response.status, problem?.message || problem?.data?.message || '선택한 대상의 현재 상태를 다시 확인해 주세요.',
      problem?.data?.reauthenticate === true);
  }
  return response.json() as Promise<T>;
}

function matches<T extends DisplayDay>(result: { weekCount: number; rows: T[] }, target: BatchSelection, valid: (row: T) => boolean) {
  const keys = new Set(target.rows.map(dayKey));
  return Number.isInteger(result?.weekCount) && result.weekCount > 0 && result.weekCount <= keys.size
    && Array.isArray(result.rows) && result.rows.length === keys.size && result.rows.every(row => row
      && keys.delete(dayKey(row)) && typeof row.employeeNo === 'string' && typeof row.employeeName === 'string'
      && Array.isArray(row.reasons) && row.reasons.every(reason => Object.hasOwn(monthEndReasons, reason)) && valid(row));
}

interface Props {
  target: BatchSelection;
  csrf: CsrfToken;
  returnTo: string;
  close: (confirmed: boolean) => void;
  onCalculated: () => void;
  refresh: (signal: AbortSignal) => Promise<void>;
  reload: () => void;
  deny: (error: PageRequestError) => void;
}

type Step = 'previewing' | 'ready' | 'saving' | 'uncertain' | 'reviewAgain' | 'result';

export function MonthEndBatch({ target, csrf, returnTo, close, onCalculated, refresh, reload, deny }: Props) {
  const [step, setStep] = useState<Step>('previewing');
  const [preview, setPreview] = useState<Preview>();
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const [receipt, setReceipt] = useState<Receipt>();
  const [list, setList] = useState<'loading' | 'failed' | 'current'>('loading');
  const command = useRef<Command | null>(null);
  const running = useRef(false);
  const heading = useRef<HTMLHeadingElement>(null);
  const reasonInput = useRef<HTMLInputElement>(null);
  const trimmed = reason.replace(reasonEdges, '');
  const length = Array.from(trimmed).length;
  const forbiddenCharacters = /[\p{Cc}\p{Cs}]/u.test(trimmed);
  const validReason = length >= 1 && length <= 500 && !forbiddenCharacters;
  const locallyInvalid = Boolean(reason) && !validReason;

  useEffect(() => {
    if (step !== 'previewing') return;
    const controller = new AbortController();
    post<Preview>(path + '/preview', target, csrf, controller.signal).then(result => {
      if (controller.signal.aborted) return;
      if (!result?.previewToken || !matches(result, target, row => Boolean(row.weekStart && row.weekEnd))) throw new Error();
      setPreview(result); setStep('ready');
    }).catch(failure => {
      if (controller.signal.aborted) return;
      if (denied(failure)) { deny(failure); return; }
      setError(failure instanceof PageRequestError ? failure.message : '미리보기를 불러오지 못했습니다.');
      setStep('reviewAgain');
    });
    return () => controller.abort();
  }, [step, target, csrf, deny]);

  useEffect(() => {
    if (step !== 'result' || list !== 'loading') return;
    const controller = new AbortController();
    refresh(controller.signal).then(() => {
      if (!controller.signal.aborted) setList('current');
    }).catch(failure => {
      if (controller.signal.aborted) return;
      if (denied(failure)) { deny(failure); return; }
      setList('failed');
    });
    return () => controller.abort();
  }, [step, list, refresh, deny]);

  useEffect(() => {
    if (step === 'ready' || step === 'reviewAgain' || step === 'result') heading.current?.focus();
  }, [step]);

  async function execute(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (running.current || !preview || !validReason || (step !== 'ready' && step !== 'uncertain') || (step === 'ready' && error)) return;
    running.current = true;
    command.current ??= { ...target, previewToken: preview.previewToken, calculationReason: trimmed, commandId: crypto.randomUUID() };
    setStep('saving'); setError('');
    try {
      const result = await post<Receipt>(path, command.current, csrf);
      if (!result?.batchId || result.weekCount !== preview.weekCount || !matches(result, target, row =>
        typeof row.resultId === 'string' && row.resultId.length > 0 &&
        (row.actualWorkMillis === null || (Number.isSafeInteger(row.actualWorkMillis) && row.actualWorkMillis >= 0)))) throw new Error();
      onCalculated(); setReceipt(result); setStep('result');
    } catch (failure) {
      if (denied(failure)) { deny(failure); return; }
      if (failure instanceof PageRequestError && failure.status < 500) {
        command.current = null;
        setError(failure.message);
        if (failure.status === 422) {
          setStep('ready');
          requestAnimationFrame(() => reasonInput.current?.focus());
        } else {
          setPreview(undefined); setStep('reviewAgain');
        }
      } else {
        setError('재집계 결과를 확인하지 못했습니다. 같은 요청을 다시 보내 처리 결과를 확인해 주세요.');
        setStep('uncertain');
      }
    } finally { running.current = false; }
  }

  if (step === 'result' && receipt) {
    const back = new URLSearchParams({ returnTo });
    const resolved = receipt.rows.filter(row => !row.reasons.length).length;
    return <section className="month-review-batch" aria-label="재집계 결과">
      <h3 ref={heading} tabIndex={-1}>재집계 결과</h3>
      <p>사원별 주간 집계 {receipt.weekCount}건 · 해소 {resolved}건 · 확인 필요 {receipt.rows.length - resolved}건</p>
      <ul className="month-review-batch-rows" aria-label="근무일별 재집계 결과">{receipt.rows.map(row =>
        <li key={dayKey(row)}>
          <div><strong>{row.employeeNo} · {row.employeeName}</strong><time dateTime={row.workDate}>{row.workDate}</time></div>
          <div>실근로 <strong>{row.actualWorkMillis == null ? '미확정' : row.actualWorkMillis === 0 ? '0시간' : duration(row.actualWorkMillis)}</strong></div>
          <div>{row.reasons.length ? '남은 사유 · ' + reasonNames(row.reasons) : '해소'}</div>
          <a className="t-btn" href={'/worktime/results/' + encodeURIComponent(row.resultId) + '?' + back}>일 결과 상세</a>
        </li>)}</ul>
      {list === 'loading' && <p role="status">같은 조건으로 목록을 조회하고 있습니다.</p>}
      {list === 'failed' && <ErrorNotice message="재집계를 완료했습니다. 월말 확인 대상 목록을 다시 조회해 주세요." />}
      <div className="actions">
        {list === 'failed' && <button className="t-btn" type="button" onClick={() => setList('loading')}>목록 다시 조회</button>}
        <button className="t-btn primary" type="button" disabled={list !== 'current'} onClick={() => close(true)}>결과 확인</button>
      </div>
    </section>;
  }
  if (step === 'previewing') return <p role="status">재집계 미리보기를 불러오고 있습니다.</p>;
  if (step === 'reviewAgain') return <section className="month-review-batch" aria-label="미리보기 확인">
    <h3 ref={heading} tabIndex={-1}>미리보기 확인</h3>
    <ErrorNotice message={error} />
    <div className="actions">
      <button className="t-btn primary" type="button" onClick={() => { setError(''); setStep('previewing'); }}>다시 미리보기</button>
      <button className="t-btn" type="button" onClick={reload}>목록 다시 조회</button>
      <button className="t-btn" type="button" onClick={() => close(false)}>선택 변경</button>
    </div>
  </section>;
  if (!preview) return null;
  return <section className="month-review-batch" aria-label="재집계 미리보기" data-modal-busy={step === 'saving'}
    data-unsaved-changes={step === 'uncertain' || (step === 'ready' && Boolean(trimmed))}>
    <h3 ref={heading} tabIndex={-1}>재집계 미리보기</h3>
    <p>선택한 근무일 {preview.rows.length}건 · 사원별 주간 집계 {preview.weekCount}건</p>
    <p>선택한 날이 속한 월요일부터 일요일까지 재집계합니다.</p>
    <ul className="month-review-batch-rows" aria-label="선택한 근무일과 재집계 범위">{preview.rows.map(row =>
      <li key={dayKey(row)}>
        <div><strong>{row.employeeNo} · {row.employeeName}</strong><time dateTime={row.workDate}>{row.workDate}</time></div>
        <div>{reasonNames(row.reasons)}</div>
        <div>계산 범위 <span>{row.weekStart} ~ {row.weekEnd}</span></div>
      </li>)}</ul>
    <form action={path} method="post" className="month-review-batch-form" onSubmit={execute}>
      <label htmlFor="month-end-calculation-reason">재집계 사유</label>
      <input ref={reasonInput} type="text" className="t-input" id="month-end-calculation-reason" name="calculationReason" required
        value={reason} readOnly={step !== 'ready'} onChange={event => { setReason(event.currentTarget.value); setError(''); }}
        aria-invalid={locallyInvalid || (step === 'ready' && Boolean(error)) || undefined} aria-describedby="month-end-reason-help" />
      <small id="month-end-reason-help">한 줄 1~500자 · 앞뒤 공백 제외 · 현재 {length}자</small>
      <ErrorNotice message={error || (locallyInvalid && forbiddenCharacters ? '탭·제어 문자와 올바르지 않은 문자는 사용할 수 없습니다.' : '')} />
      <div className="actions">
        <button className="t-btn primary" type="submit" disabled={step === 'saving' || !validReason || (step === 'ready' && Boolean(error))}>
          {step === 'uncertain' ? '같은 요청 다시 보내기' : step === 'saving' ? '재집계 중' : '재집계 실행'}
        </button>
        {step === 'ready' && <button className="t-btn" type="button" onClick={() => close(false)}>선택 변경</button>}
      </div>
    </form>
  </section>;
}
