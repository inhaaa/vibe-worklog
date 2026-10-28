import { createContext, useContext, useId, useLayoutEffect, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { flushSync } from 'react-dom';
import type { CustomCellEditorProps } from 'ag-grid-react';

export interface SheetEditorField {
  value: string;
  label: string;
  onChange: (value: string) => void;
  onCancel?: () => void;
  type?: 'text' | 'number' | 'date';
  options?: readonly (readonly [string, string])[];
  disabled?: boolean;
  readOnly?: boolean;
  required?: boolean;
  maxLength?: number;
  min?: string | number;
  step?: number;
  changed?: boolean;
  error?: string;
}

export const SheetEditorFields = createContext<ReadonlyMap<string, SheetEditorField> | null>(null);

export function validateSheetField(field: SheetEditorField): string | undefined {
  if (field.disabled || field.readOnly) return;
  if (field.required && !field.value.trim()) return '필수 입력입니다.';
  if (field.maxLength != null && field.value.length > field.maxLength) return `${field.maxLength}자 이내로 입력하세요.`;
  if (!field.value) return;
  if (field.type === 'number') {
    const number = Number(field.value);
    if (!Number.isFinite(number)) return '숫자를 입력하세요.';
    const min = field.min == null ? undefined : Number(field.min);
    if (min != null && number < min) return `${min} 이상으로 입력하세요.`;
    const step = field.step ?? 1;
    if (step > 0 && Math.abs((number - (min ?? 0)) / step - Math.round((number - (min ?? 0)) / step)) > 1e-8) return `${step} 단위로 입력하세요.`;
  }
  if (field.type === 'date') {
    const timestamp = Date.parse(`${field.value}T00:00:00Z`);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(field.value) || !Number.isFinite(timestamp) || new Date(timestamp).toISOString().slice(0, 10) !== field.value) return '날짜를 확인하세요.';
    if (field.min != null && field.value < String(field.min)) return `${field.min} 이후로 입력하세요.`;
  }
}

/** AG Grid owns editing/navigation; page state receives each keystroke before a save can run. */
export function SheetCellEditor<T>({ field: initialField, fieldKey, eventKey, onValueChange, onKeyDown: gridKeyDown }: CustomCellEditorProps<T, string> & { field: SheetEditorField; fieldKey: string }) {
  // AG Grid keeps editor params from edit start; React context supplies current errors and callbacks.
  const field = useContext(SheetEditorFields)?.get(fieldKey) ?? initialField;
  const initial = useRef(field.value);
  const cancel = useRef(field.onCancel);
  const errorId = useId();
  const startsWithKey = eventKey?.length === 1 && !field.options;
  const first = startsWithKey ? eventKey : field.value;
  const [value, setValue] = useState(first);
  const control = useRef<HTMLInputElement | HTMLSelectElement>(null);
  const currentField = useRef(field);
  currentField.current = field;
  useLayoutEffect(() => {
    control.current?.focus();
    if (control.current instanceof HTMLInputElement && (field.type ?? 'text') === 'text') {
      if (startsWithKey) control.current.setSelectionRange(first.length, first.length);
      else control.current.select();
    }
    if (first !== initial.current) {
      onValueChange(first);
      // Defer the page update until the initial grid editor mount has completed.
      queueMicrotask(() => { if (control.current) flushSync(() => currentField.current.onChange(first)); });
    }
  }, []);
  function change(next: string) {
    flushSync(() => {
      setValue(next);
      onValueChange(next);
      currentField.current.onChange(next);
    });
  }
  const shared = {
    className: 'w-sheet-editor', value, 'aria-label': field.label,
    'aria-invalid': Boolean(field.error), 'aria-describedby': field.error ? errorId : undefined,
    required: field.required,
    onKeyDownCapture: (event: KeyboardEvent) => {
      if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) { event.stopPropagation(); return; }
      if (event.key === 'Enter') {
        event.preventDefault(); event.stopPropagation();
        gridKeyDown(event.nativeEvent);
      }
    },
    onKeyDown: (event: KeyboardEvent) => {
      if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) { event.stopPropagation(); return; }
      if (event.key === 'Escape') {
        if (cancel.current) flushSync(() => { setValue(initial.current); onValueChange(initial.current); cancel.current!(); });
        else if (value !== initial.current) change(initial.current);
      }
    },
  };
  return <>
    {field.options ? <select {...shared} ref={node => { control.current = node; }} onChange={event => change(event.target.value)}>
      {!field.options.some(([key]) => key === value) && <option value={value}>{value || '선택하세요'}</option>}
      {field.options.map(([key, text]) => <option key={key} value={key}>{text}</option>)}
    </select> : <input {...shared} ref={node => { control.current = node; }} type={field.type ?? 'text'} maxLength={field.maxLength}
      min={field.min} step={field.step} onChange={event => change(event.target.value)} />}
    {field.error && <span className="t-sr-only" id={errorId}>{field.error}</span>}
  </>;
}
