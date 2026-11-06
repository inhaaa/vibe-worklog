import { useId, useLayoutEffect, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';

type Field = 'from' | 'to';
type Period = { from: string | null; to: string | null; today: string };

const fields = ['from', 'to'] as const;
const labels = { from: '시작일', to: '종료일 (포함)' };
const weekdays = ['일', '월', '화', '수', '목', '금', '토'];
const dayOffsets: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 };
const lastDate = '9999-12-31';
const asDate = (value: string) => new Date(`${value}T00:00:00Z`);
const isoDate = (date: Date) => date.toISOString().split('T')[0];

export function requestPeriodIssue({ from, to, today }: Period): { field: Field; message: string } | null {
  if (!from) return { field: 'from', message: '시작일을 선택해 주세요.' };
  if (from < today) return { field: 'from', message: '시작일은 오늘부터 선택할 수 있습니다.' };
  if (!to) return { field: 'to', message: '종료일을 선택해 주세요.' };
  if (to < from) return { field: 'to', message: '종료일은 시작일과 같거나 이후여야 합니다.' };
  return null;
}

function daysInMonth(date: Date) {
  const end = new Date(date);
  end.setUTCMonth(end.getUTCMonth() + 1, 0);
  return end.getUTCDate();
}

function shiftMonth(date: Date, offset: number) {
  const next = new Date(date);
  next.setUTCMonth(next.getUTCMonth() + offset, 1);
  next.setUTCDate(Math.min(date.getUTCDate(), daysInMonth(next)));
  return next;
}

export function RequestPeriodSheet({ from, to, today, onSelect }: Period & { onSelect: (field: Field, value: string) => void }) {
  const id = useId();
  const [active, setActive] = useState<Field | null>(null);
  const [focusedDate, setFocusedDate] = useState(today);
  const triggers = useRef<Partial<Record<Field, HTMLButtonElement>>>({});
  const focusedDay = useRef<HTMLButtonElement>(null);
  const shouldFocusDay = useRef(false);
  const values = { from, to };
  const issue = requestPeriodIssue({ from, to, today });
  const endMinimum = from && from > today ? from : today;
  const minimum = active === 'to' ? endMinimum : today;
  const current = asDate(focusedDate);
  const month = focusedDate.slice(0, 7);
  const firstWeekday = asDate(`${month}-01`).getUTCDay();
  const canGoBack = month > minimum.slice(0, 7);
  const canGoForward = month < lastDate.slice(0, 7);

  useLayoutEffect(() => {
    if (active && shouldFocusDay.current) {
      shouldFocusDay.current = false;
      focusedDay.current?.focus();
    }
  }, [active, focusedDate]);

  function close() {
    if (active) triggers.current[active]?.focus();
    setActive(null);
  }

  function toggle(field: Field) {
    if (active === field) { close(); return; }
    const min = field === 'from' ? today : endMinimum;
    const value = values[field];
    shouldFocusDay.current = true;
    setFocusedDate(value && value >= min ? value : min);
    setActive(field);
  }

  function move(next: Date, focus: boolean) {
    const clamped = new Date(Math.min(asDate(lastDate).getTime(), Math.max(next.getTime(), asDate(minimum).getTime())));
    const value = isoDate(clamped);
    if (value === focusedDate) return;
    shouldFocusDay.current = focus;
    setFocusedDate(value);
  }

  function onDayKey(event: KeyboardEvent<HTMLButtonElement>) {
    let next = new Date(current);
    const offset = dayOffsets[event.key];
    if (offset !== undefined) next.setUTCDate(next.getUTCDate() + offset);
    else if (event.key === 'Home' || event.key === 'End') {
      next.setUTCDate(next.getUTCDate() - next.getUTCDay() + (event.key === 'End' ? 6 : 0));
    } else if (event.key === 'PageUp' || event.key === 'PageDown') {
      next = shiftMonth(next, (event.key === 'PageUp' ? -1 : 1) * (event.shiftKey ? 12 : 1));
    } else return;
    event.preventDefault();
    move(next, true);
  }

  return <fieldset className="t-request-period-picker" onKeyDown={event => {
    if (event.key === 'Escape' && active) {
      event.preventDefault();
      event.stopPropagation();
      close();
    }
  }}>
    <legend>신청 기간</legend>
    <div className="t-request-period-sheet">
      {fields.map(field => <button type="button" className="t-btn t-request-period-trigger" key={field}
        ref={button => { if (button) triggers.current[field] = button; }}
        aria-expanded={active === field} aria-controls={active === field ? `${id}-calendar` : undefined}
        aria-invalid={issue?.field === field || undefined} aria-describedby={issue?.field === field ? `${id}-error` : undefined}
        data-period-invalid={issue?.field === field || undefined} onClick={() => toggle(field)}>
        <span>{labels[field]}</span><strong>{values[field] || '날짜 선택'}</strong>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <rect x="4" y="5" width="16" height="16" rx="3"/><path d="M8 3v4m8-4v4M4 11h16"/>
        </svg>
      </button>)}
      {active && <section className="t-request-calendar" id={`${id}-calendar`} aria-label={active === 'from' ? '시작일 선택' : '종료일 선택'}>
        <div className="t-request-calendar-head">
          <button type="button" className="t-btn" aria-label="이전 달" aria-disabled={!canGoBack} onClick={canGoBack ? () => move(shiftMonth(current, -1), false) : undefined}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m15 6-6 6 6 6"/></svg>
          </button>
          <h4 aria-live="polite">{current.getUTCFullYear()}년 {current.getUTCMonth() + 1}월</h4>
          <button type="button" className="t-btn" aria-label="다음 달" aria-disabled={!canGoForward} onClick={canGoForward ? () => move(shiftMonth(current, 1), false) : undefined}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m9 6 6 6-6 6"/></svg>
          </button>
        </div>
        <p id={`${id}-keys`} className="sr-only">방향키로 날짜를 이동하고 Enter로 선택합니다.</p>
        <div className="t-request-calendar-days" role="group" aria-label="날짜" aria-describedby={`${id}-keys`}>
          {weekdays.map(day => <span key={day} aria-hidden="true">{day}</span>)}
          {Array.from({ length: daysInMonth(current) }, (_, index) => {
            const day = index + 1;
            const value = `${month}-${String(day).padStart(2, '0')}`;
            const focused = value === focusedDate;
            return <button type="button" className="t-btn" key={value} aria-label={value} aria-pressed={value === values[active]}
              aria-current={value === today ? 'date' : undefined} disabled={value < minimum} tabIndex={focused ? 0 : -1}
              ref={focused ? focusedDay : undefined} style={day === 1 ? { gridColumnStart: firstWeekday + 1 } : undefined}
              onKeyDown={onDayKey} onClick={() => { if (value !== values[active]) onSelect(active, value); close(); }}>{day}</button>;
          })}
        </div>
      </section>}
    </div>
    {issue && <p className="t-request-period-error" id={`${id}-error`} aria-live="polite">{issue.message}</p>}
  </fieldset>;
}
