import { useEffect, useState } from 'react';
import type { LeaveTypeOption } from './AnnualRequestTypes';

export function useLeaveTypes(seed: LeaveTypeOption[] | null | undefined, seedDate: string, date: string) {
  const [lookup, setLookup] = useState<{ date: string; options?: LeaveTypeOption[]; error?: string }>();
  const [attempt, setAttempt] = useState(0);
  const needsLookup = Array.isArray(seed) && Boolean(date) && seedDate !== date;

  useEffect(() => {
    if (!needsLookup) return;
    const controller = new AbortController();
    async function load() {
      try {
        const query = new URLSearchParams({ date });
        const response = await fetch('/me/requests/leave-types?' + query, {
          headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
        });
        if (!response.ok) throw new Error();
        const result = await response.json();
        if (!Array.isArray(result?.types)) throw new Error();
        if (!controller.signal.aborted) setLookup({ date, options: result.types });
      } catch {
        if (!controller.signal.aborted) setLookup({ date, error: '선택한 날짜의 휴가 종류를 불러오지 못했습니다.' });
      }
    }
    setLookup({ date });
    void load();
    return () => controller.abort();
  }, [date, needsLookup, attempt]);

  const current = needsLookup && lookup?.date === date ? lookup : undefined;
  return {
    options: needsLookup ? current?.options : seed,
    error: current?.error,
    loading: needsLookup && !current?.options && !current?.error,
    retry: () => { setLookup({ date }); setAttempt(value => value + 1); },
  };
}
