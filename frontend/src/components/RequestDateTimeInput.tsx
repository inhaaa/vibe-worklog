import { secondsInput } from '../ui';

interface RequestDateTimeInputProps {
  id: string;
  name: string;
  value: string | null | undefined;
  onChange: (value: string) => void;
  readOnly?: boolean;
  required?: boolean;
}

export function RequestDateTimeInput({ id, name, value, onChange, readOnly, required }: RequestDateTimeInputProps) {
  return <>
    <input id={id} className="t-input" type="datetime-local" step="1" value={secondsInput(value)}
      onChange={event => onChange(event.target.value)} readOnly={readOnly} required={required} />
    <input type="hidden" name={name} value={value ?? ''} />
  </>;
}
