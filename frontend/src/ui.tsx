import { createContext, useContext, useId } from 'react';
import type {
  FormHTMLAttributes,
  InputHTMLAttributes,
  SelectHTMLAttributes,
  TextareaHTMLAttributes,
} from 'react';
import type { CsrfToken } from './types';
export const CsrfContext = createContext<CsrfToken>({
  token: '',
  parameterName: '_csrf',
  headerName: 'X-CSRF-TOKEN',
});
export function Form({ method = 'post', children, ...props }: FormHTMLAttributes<HTMLFormElement>) {
  const csrf = useContext(CsrfContext);
  return (
    <form method={method} {...props}>
      {method.toLowerCase() !== 'get' && csrf.token && (
        <input type="hidden" name={csrf.parameterName} value={csrf.token} />
      )}
      {children}
    </form>
  );
}

interface FieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'value'> {
  label: string;
  value?: string | number | null;
}
export function Field({
  label,
  value,
  id,
  type = 'text',
  className = 't-input',
  ...props
}: FieldProps) {
  const generatedId = useId();
  const fieldId = id ?? generatedId;
  return (
    <>
      <label htmlFor={fieldId}>{label}</label>
      <input
        {...props}
        id={fieldId}
        type={type}
        className={className}
        defaultValue={type === 'password' ? undefined : (value ?? undefined)}
      />
    </>
  );
}

interface CheckProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'checked'> {
  label: string;
  name: string;
  checked?: boolean;
}
export function Check({ label, name, checked, value = 'true', id, ...props }: CheckProps) {
  const generatedId = useId();
  const fieldId = id ?? generatedId;
  return (
    <>
      <input type="hidden" name={`_${name}`} value="on" />
      <label htmlFor={fieldId} className="checkbox-label">
        <input
          {...props}
          id={fieldId}
          name={name}
          type="checkbox"
          value={value}
          defaultChecked={checked}
        />
        {label}
      </label>
    </>
  );
}

interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'value'> {
  label: string;
  value?: string | number | readonly string[] | null;
}
export function SelectField({
  label,
  value,
  children,
  id,
  className = 't-select',
  ...props
}: SelectProps) {
  const generatedId = useId();
  const fieldId = id ?? generatedId;
  return (
    <>
      <label htmlFor={fieldId}>{label}</label>
      <select {...props} id={fieldId} className={className} defaultValue={value ?? ''}>
        {children}
      </select>
    </>
  );
}

interface TextAreaProps extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'value'> {
  label: string;
  value?: string | null;
}
export function TextAreaField({ label, value, id, ...props }: TextAreaProps) {
  const generatedId = useId();
  const fieldId = id ?? generatedId;
  return (
    <>
      <label htmlFor={fieldId}>{label}</label>
      <textarea {...props} id={fieldId} defaultValue={value ?? ''} />
    </>
  );
}

export function ErrorNotice({ message }: { message?: string | null }) {
  return message ? (
    <p className="notice error" role="alert">
      {message}
    </p>
  ) : null;
}
export const fmtDate = (value?: string | null) => (value ? value.slice(0, 10) : '미지정');
export function fmtTime(value?: string | null) {
  if (!value) return '미확인';
  const [clock, fraction = ''] = value.split('.');
  return `${clock.length === 5 ? `${clock}:00` : clock}.${fraction.padEnd(3, '0').slice(0, 3)}`;
}
export function fmtDateTime(value?: string | null) {
  if (!value) return '미지정';
  const instant = new Date(value);
  if (!Number.isFinite(instant.getTime())) return '시각 미확인';
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    fractionalSecondDigits: 3,
    hour12: false,
  })
    .format(instant)
    .replace(',', '.');
}
export function duration(milliseconds?: number | null) {
  if (milliseconds == null || !Number.isFinite(milliseconds)) return '미확정';
  const seconds = Math.floor(milliseconds / 1000);
  return `${Math.floor(seconds / 3600)}:${String(Math.floor(seconds / 60) % 60).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}.${String(Math.floor(milliseconds % 1000)).padStart(3, '0')}`;
}
