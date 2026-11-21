import { useEffect, useRef, useState } from 'react';
import type { CsrfToken } from '../types';
import { PageRequestError, requestJson } from '../transport';
import { employmentPath, type AttendanceDecision } from './employmentLeaveTypes';

type Command = { employeeId: string; reason: string; token: string }
  & ({ accrualOn: string; decision: AttendanceDecision; includedBucketId?: string } | { priorHistoryConfirmed: boolean });
type Pending = { action: 'monthly' | 'settle'; body: Command & { commandId: string } };
type State =
  | { mode: 'idle' }
  | { mode: 'sending' | 'retry' | 'saved'; request: Pending }
  | { mode: 'invalid' | 'stale'; action: Pending['action']; message: string }
  | { mode: 'denied' };

export function useEmploymentPosting(csrf: CsrfToken, readOnly: boolean, reload: () => void) {
  const [state, setState] = useState<State>({ mode: 'idle' });
  const running = useRef(false);
  const mounted = useRef(false);
  const refreshed = useRef(false);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    if (state.mode === 'saved' && !refreshed.current) {
      // The shell permits a GET once the completed render clears the in-flight marker.
      refreshed.current = true;
      reload();
    }
  }, [state.mode, reload]);

  const locked = state.mode !== 'idle' && state.mode !== 'invalid';

  async function send(request: Pending) {
    if (running.current || readOnly) return;
    running.current = true;
    setState({ mode: 'sending', request });
    try {
      const data = await requestJson<{ id: string }>(employmentPath + '/' + request.action, { csrf, body: request.body });
      if (typeof data?.id !== 'string' || !data.id) throw new Error('처리 결과 확인 필요');
    } catch (error) {
      if (!mounted.current) return;
      if (error instanceof PageRequestError && (error.reauthenticate || error.status === 401 || error.status === 403)) {
        setState({ mode: 'denied' });
      } else if (error instanceof PageRequestError && error.status >= 400 && error.status < 500) {
        setState({ mode: error.status === 409 ? 'stale' : 'invalid', action: request.action, message: error.message });
      } else {
        setState({ mode: 'retry', request });
      }
      return;
    } finally {
      running.current = false;
    }
    if (mounted.current) setState({ mode: 'saved', request });
  }

  return {
    state, locked,
    clearError: () => { if (state.mode === 'invalid') setState({ mode: 'idle' }); },
    post: (body: Command) => {
      if (!locked) void send({ action: 'accrualOn' in body ? 'monthly' : 'settle', body: { ...body, commandId: crypto.randomUUID() } });
    },
    retry: () => { if (state.mode === 'retry') void send(state.request); },
  };
}

export type EmploymentPosting = ReturnType<typeof useEmploymentPosting>;

export function PostingFeedback({ posting, action, refresh }: {
  posting: EmploymentPosting; action: Pending['action']; refresh: string;
}) {
  const { state } = posting;
  const notice = useRef<HTMLDivElement>(null);
  useEffect(() => { notice.current?.focus(); }, [state]);
  if ('action' in state && state.action === action)
    return <div className="notice error" role="alert" ref={notice} tabIndex={-1}>{state.message}{' '}
      {state.mode === 'stale' && <a href={refresh}>최신 근거 다시 조회</a>}</div>;
  if (!('request' in state) || state.request.action !== action) return null;
  if (state.mode === 'retry')
    return <div className="notice" role="alert" ref={notice} tabIndex={-1}>서버의 반영 결과를 확인하지 못했습니다.{' '}
      <button className="t-btn" type="button" onClick={posting.retry}>같은 명령으로 결과 확인</button></div>;
  if (state.mode === 'saved')
    return <div className="notice" role="status" ref={notice} tabIndex={-1}>원장에 반영했습니다.{' '}
      <a href={refresh}>최신 근거 다시 조회</a></div>;
  return <p role="status">원장에 반영하고 있습니다.</p>;
}
