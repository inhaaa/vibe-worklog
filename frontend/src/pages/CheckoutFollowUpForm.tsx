import { useRef, useState, type FormEvent } from 'react';
import { flushSync } from 'react-dom';
import type { CsrfToken } from '../types';
import { ErrorNotice, Form, TextAreaField } from '../ui';

interface Props {
  employee: { employeeId: string; employeeNo: string; employeeName: string; workDate: string };
  csrf: CsrfToken;
  reload: () => void;
  close: () => void;
}

type Phase = 'editing' | 'sending' | 'uncertain' | 'refresh' | 'saved';

export function CheckoutFollowUpForm({ employee, csrf, reload, close }: Props) {
  const [reason, setReason] = useState('');
  const [phase, setPhase] = useState<Phase>('editing');
  const [error, setError] = useState('');
  const running = useRef(false);
  const command = useRef<{ reason: string; commandId: string } | null>(null);
  const text = reason.trim();
  const valid = text.length > 0 && text.length <= 500;

  async function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (running.current || !valid || (phase !== 'editing' && phase !== 'uncertain')) return;
    running.current = true;
    setPhase('sending');
    setError('');
    if (command.current?.reason !== text) command.current = { reason: text, commandId: crypto.randomUUID() };
    try {
      const response = await fetch('/admin/attendance/follow-ups', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({ employeeId: employee.employeeId, workDate: employee.workDate, ...command.current }),
      });
      if (response.redirected || response.status === 401 || response.status === 403) {
        setPhase('refresh');
        setError('로그인 상태와 보완 요청 권한을 확인해 주세요.');
        return;
      }
      if (response.status >= 500) throw new Error();
      const data = await response.json();
      if (!response.ok) {
        const stale = response.status === 404 || response.status === 409;
        setPhase(stale ? 'refresh' : 'editing');
        setError(typeof data?.message === 'string' ? data.message : stale
          ? '근무일 상태가 바뀌었습니다. 목록을 다시 확인해 주세요.' : '보완 사유를 확인해 주세요.');
        return;
      }
      if (typeof data?.id !== 'string' || !data.id) throw new Error();
      // Release the shell's pending-save guard before reloading the current filtered page.
      flushSync(() => setPhase('saved'));
      reload();
    } catch {
      setPhase('uncertain');
      setError('저장 결과를 확인할 수 없습니다. 같은 요청으로 다시 확인해 주세요.');
    } finally {
      running.current = false;
    }
  }

  return <Form action="/admin/attendance/follow-ups" aria-label="퇴근 누락 보완 요청" className="wl-form checkout-follow-up-form"
    data-unsaved-changes={phase === 'editing' && Boolean(text)} data-modal-busy={phase === 'sending'} onSubmit={send}>
    <strong>{employee.employeeNo} · {employee.employeeName} · {employee.workDate}</strong>
    <TextAreaField label="보완 사유" name="reason" required rows={2} maxLength={500}
      autoFocus readOnly={phase !== 'editing'} value={reason} onChange={event => setReason(event.currentTarget.value)} />
    <ErrorNotice message={error} />
    {phase === 'saved' && <p role="status">보완을 요청했습니다.</p>}
    <div className="actions">
      {(phase === 'refresh' || phase === 'saved') ? <button className="t-btn" type="button" onClick={reload}>목록 새로고침</button>
        : <button className="t-btn primary" type="submit" disabled={phase === 'sending' || !valid}>
          {phase === 'uncertain' ? '같은 요청 다시 확인' : phase === 'sending' ? '요청 중' : '요청 보내기'}
        </button>}
      {phase === 'editing' && <button className="t-btn" type="button" onClick={close}>취소</button>}
    </div>
  </Form>;
}
