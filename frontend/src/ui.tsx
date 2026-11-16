import { createContext, useContext, useId } from 'react';
import type {
  FormHTMLAttributes,
  InputHTMLAttributes,
  ReactNode,
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

/** 문서형 표(설계 7.8). 안의 Field·SelectField·TextAreaField는 항목명·값 칸 한 행으로 그린다. */
const FormTableContext = createContext(false);
export function FormTable({ columns = 1, children }: { columns?: 1 | 2; children: ReactNode }) {
  return (
    <FormTableContext value={true}>
      <div className="wl-form-table" data-columns={columns === 2 ? 2 : undefined}>{children}</div>
    </FormTableContext>
  );
}

/** 문서형 표의 한 행. htmlFor가 없으면 값 칸을 항목명으로 이름 붙인 group으로 둔다(묶음·체크 항목). */
export function FormRow({
  label,
  htmlFor,
  required = false,
  children,
}: {
  label: string;
  htmlFor?: string;
  required?: boolean;
  children: ReactNode;
}) {
  const labelId = useId();
  const className = `wl-form-label${required ? ' wl-required' : ''}`;
  return (
    <div className="wl-form-row" role={htmlFor ? undefined : 'group'} aria-labelledby={htmlFor ? undefined : labelId}>
      {htmlFor ? <label className={className} htmlFor={htmlFor}>{label}</label> : <span id={labelId} className={className}>{label}</span>}
      <div className="wl-form-value"><FormTableContext value={false}>{children}</FormTableContext></div>
    </div>
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
  const inTable = useContext(FormTableContext);
  const fieldId = id ?? generatedId;
  const input = (
    <input
      {...props}
      id={fieldId}
      type={type}
      className={className}
      defaultValue={type === 'password' ? undefined : (value ?? undefined)}
    />
  );
  return inTable ? (
    <FormRow label={label} htmlFor={fieldId} required={props.required}>{input}</FormRow>
  ) : (
    <>
      <label htmlFor={fieldId}>{label}</label>
      {input}
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
  const inTable = useContext(FormTableContext);
  const fieldId = id ?? generatedId;
  const select = (
    <select {...props} id={fieldId} className={className} defaultValue={value ?? ''}>
      {children}
    </select>
  );
  return inTable ? (
    <FormRow label={label} htmlFor={fieldId} required={props.required}>{select}</FormRow>
  ) : (
    <>
      <label htmlFor={fieldId}>{label}</label>
      {select}
    </>
  );
}

interface TextAreaProps extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'value'> {
  label: string;
  value?: string | null;
}
export function TextAreaField({ label, value, id, ...props }: TextAreaProps) {
  const generatedId = useId();
  const inTable = useContext(FormTableContext);
  const fieldId = id ?? generatedId;
  const textarea = <textarea {...props} id={fieldId} defaultValue={value ?? ''} />;
  return inTable ? (
    <FormRow label={label} htmlFor={fieldId} required={props.required}>{textarea}</FormRow>
  ) : (
    <>
      <label htmlFor={fieldId}>{label}</label>
      {textarea}
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
  const [hour, minute, second = '00'] = clock.split(':');
  return second === '00' && !/[1-9]/.test(fraction) ? `${hour}:${minute}` : `${hour}:${minute}:${second}`;
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
    hour12: false,
  }).format(instant);
}
export function duration(milliseconds?: number | null) {
  if (milliseconds == null || !Number.isFinite(milliseconds)) return '-';
  const seconds = Math.floor(milliseconds / 1000);
  return `${Math.floor(seconds / 3600)}:${String(Math.floor(seconds / 60) % 60).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
}
/** datetime-local 입력값을 1초 단위 입력칸에 맞춘다. 서버 값의 밀리초만 지운다. */
export const secondsInput = (value?: string | null) => (value ?? '').replace(/(\d{2}:\d{2}:\d{2})\.\d+/g, '$1');
