import { secondsInput } from '../ui';

export interface RequestBreakEditorProps {
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
}

function localDateTime(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,3})?)?$/.exec(value);
  if (!match) return false;
  const [, year, month, day, hour, minute, second = '0'] = match;
  const date = new Date(`${year}-${month}-${day}T00:00:00Z`);
  return date.getUTCFullYear() === Number(year) && date.getUTCMonth() + 1 === Number(month)
    && date.getUTCDate() === Number(day) && Number(hour) < 24 && Number(minute) < 60 && Number(second) < 60;
}

export function RequestBreakEditor({ value, onChange, disabled = false }: RequestBreakEditorProps) {
  const rows = value === '' ? [] : value.split(',');
  function update(index: number, row: string) {
    onChange(rows.map((current, at) => at === index ? row : current).join(','));
  }
  return <section className="t-break-editor" aria-label="실제 휴게 구간">
    <div className="t-break-editor-head"><div><h3>실제 휴게</h3><p>날짜와 시각은 서울 기준입니다. 실제 휴게가 없으면 구간 없이 확인하세요.</p></div><button type="button" className="t-btn" disabled={disabled} onClick={() => onChange(value ? `${value},/` : '/')}>휴게 추가</button></div>
    <input type="hidden" name="actualBreaks" value={value} />
    {!rows.length && <p className="t-break-empty">등록한 휴게 구간이 없습니다.</p>}
    {rows.map((raw, index) => {
      const pair = raw.split('/');
      const editable = pair.length === 2 && pair.every(part => part === '' || localDateTime(part));
      const [start = '', end = ''] = pair;
      const invalidOrder = start && end && Date.parse(`${start}+09:00`) >= Date.parse(`${end}+09:00`);
      return <div className="t-break-row" key={index}>
        <span className="t-break-number" aria-hidden="true">{index + 1}</span>
        {editable ? <>
          <label>시작<input aria-label={`휴게 ${index + 1} 시작`} className="t-input" type="datetime-local" step="1" value={secondsInput(start)} disabled={disabled} required onChange={event => update(index, `${event.target.value}/${end}`)} /></label>
          <label>종료<input aria-label={`휴게 ${index + 1} 종료`} className="t-input" type="datetime-local" step="1" value={secondsInput(end)} disabled={disabled} required ref={element => element?.setCustomValidity(invalidOrder ? '휴게 시작보다 늦은 종료를 입력해 주세요.' : '')} onChange={event => update(index, `${start}/${event.target.value}`)} /></label>
        </> : <label className="t-break-original">기존 입력 확인<input aria-label={`휴게 ${index + 1} 원본 입력`} className="t-input" type="text" value={raw} disabled={disabled} required ref={element => element?.setCustomValidity('보존된 휴게 입력을 시작/종료 형식으로 수정하거나 명시적으로 제거해 주세요.')} onChange={event => update(index, event.target.value)} /><small>기존 입력을 보존했습니다. 시작/종료 형식으로 수정하거나 이 구간을 제거하세요.</small></label>}
        <button type="button" className="t-btn t-break-remove" aria-label={`휴게 ${index + 1} 제거`} disabled={disabled} onClick={() => onChange(rows.filter((_, at) => at !== index).join(','))}>제거</button>
        {invalidOrder && editable && <p className="t-break-error">휴게 시작보다 늦은 종료를 입력해 주세요.</p>}
      </div>;
    })}
  </section>;
}
