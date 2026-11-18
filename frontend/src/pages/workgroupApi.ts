import { useEffect, useRef, useState } from 'react';
import type { CsrfToken } from '../types';

export const base = '/admin/schedules/workgroups';
export type Employee = {
  id: string; number: string; name: string; organization: string | null; currentGroup: string | null; version: number;
};
export type Weekday = {
  weekday: number; codeVersionId: string | null; code: string | null; startTime: string | null; endTime: string | null;
  breaks: { startTime: string; endTime: string }[]; minutes: number;
};
export type Group = {
  id: string; code: string; name: string; version: number; validFrom: string | null; revision: number | null;
  revisionId: string | null; days: Weekday[]; revisions: { id: string; revision: number; validFrom: string; reason: string }[];
};
export type Review = { token: string; conflicts: string[] };
export type GroupReview = Review & { beforeDays: Weekday[]; days: Weekday[]; weeklyMinutes: number;
  affected: Employee[]; departing: Employee[]; incoming: Employee[] };
export type AssignmentReview = Review & { rows: {
  employeeId: string; number: string; name: string; currentGroup: string | null; newGroup: string;
  lastDay: string | null; firstDay: string; nextStart: string | null; assignmentId: string | null; conflict: string | null;
}[] };

class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}
async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(base + path, { ...options,
    headers: { Accept: 'application/json', ...options?.headers } });
  if (response.redirected || response.status === 401 || response.status === 403) {
    throw new ApiError('로그인 상태와 근무 설정 권한을 확인해 주세요.', 403);
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new ApiError(problem.message || '요청을 처리할 수 없습니다.', response.status);
  }
  return response.json() as Promise<T>;
}
export function useScheduleQuery<T>(path: string) {
  const [attempt, setAttempt] = useState(0);
  const key = path + '#' + attempt;
  const [result, setResult] = useState<{ key: string; data?: T; error?: string }>();
  useEffect(() => {
    const cancel = new AbortController();
    request<T>(path, { signal: cancel.signal }).then(data => {
      if (!cancel.signal.aborted) setResult({ key, data });
    }).catch((error: Error) => {
      if (!cancel.signal.aborted) setResult({ key, error: error instanceof ApiError ? error.message : '목록을 조회할 수 없습니다.' });
    });
    return () => cancel.abort();
  }, [key, path]);
  return { data: result?.key === key ? result.data : undefined,
    error: result?.key === key ? result.error : undefined, refresh: () => setAttempt(value => value + 1) };
}

// 저장 응답 유실 시 미리보기에서 확정한 본문·명령 ID를 그대로 재사용한다.
export function useReviewedCommand<T extends Review>(path: string, csrf: CsrfToken, onSaved: (id: string) => void, onBusy: (busy: boolean) => void) {
  const [review, setReview] = useState<{ data: T; command: Record<string, unknown> }>();
  const [error, setError] = useState('');
  const [uncertain, setUncertain] = useState(false);
  const [busy, setBusy] = useState(false);
  const running = useRef(false);
  const send = <R,>(action: string, command: Record<string, unknown>) => request<R>(path + '/' + action, {
    method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(command),
  });
  function clear() { setReview(undefined); setError(''); setUncertain(false); }
  function loading(value: boolean) { running.current = value; setBusy(value); onBusy(value); }
  async function preview(draft: Record<string, unknown>) {
    if (running.current || uncertain) return;
    clear(); loading(true);
    try {
      const command = { ...draft, commandId: crypto.randomUUID() };
      const data = await send<T>('preview', command);
      setReview({ data, command: { ...command, token: data.token } });
    } catch (err) { setError(err instanceof ApiError ? err.message : '미리보기를 불러올 수 없습니다. 다시 조회해 주세요.'); }
    finally { loading(false); }
  }
  async function save() {
    if (running.current || !review?.data.token || review.data.conflicts.length) return;
    loading(true); setError('');
    try {
      const result = await send<{ id: string }>('save', review.command);
      if (!result.id) throw new Error('unknown receipt');
      clear(); onSaved(result.id);
    } catch (err) {
      if (err instanceof ApiError && err.status < 500) {
        clear(); setError(err.message);
      } else {
        setUncertain(true); setError('저장 결과를 확인할 수 없습니다. 같은 명령으로 다시 확인해 주세요.');
      }
    } finally { loading(false); }
  }
  return { preview, save, clear, busy, uncertain, review: review?.data, error };
}
