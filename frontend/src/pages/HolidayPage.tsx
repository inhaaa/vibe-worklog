import { useMemo, useState } from 'react';
import type { ColDef } from 'ag-grid-community';
import { PageHeader } from '../components/PageHeader';
import { WorklogSheet, sheetColumn, sheetEditorColumn } from '../components/WorklogSheet';
import type { SheetFieldError } from '../components/WorklogSheet';
import type { CsrfToken } from '../types';
import { postJson } from './EmployeeImportPage';

type Category = 'PUBLIC' | 'COMPANY' | 'NON_WORKDAY';
export interface HolidayRow { id: string; date: string; name: string; category: Category; basis: string | null }
export interface HolidayModel {
  year: number; version: number; applied: { at: string; by: string | null } | null;
  rows: HolidayRow[]; closedMonths: string[]; builtIn: boolean;
}
interface Draft { clientRowId: string; date: string; name: string; category: string; basis: string; saved?: string }
interface RowError { clientRowId: string | null; field: string; message: string }
interface Result { year: number; version: number; added: number; updated: number; deleted: number; changed: string[]; applied: string[] }
interface Preview { year: number; holidays: { date: string; name: string; basis: string }[]; notice: string }
type Field = 'date' | 'name' | 'category' | 'basis';

const CATEGORIES = [['PUBLIC', '공휴일'], ['COMPANY', '회사 휴일'], ['NON_WORKDAY', '비근로일']] as const;
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];
const content = (row: Draft) => JSON.stringify([row.date, row.name, row.category, row.basis]);
const snapshot = (rows: Draft[]) => JSON.stringify(rows.map(content).sort());
const fromServer = (row: HolidayRow): Draft => {
  const draft = { clientRowId: row.id, date: row.date, name: row.name, category: row.category, basis: row.basis ?? '' };
  return { ...draft, saved: content(draft) };
};
const rowId = (row: Draft) => row.clientRowId;

function weekday(date: string) {
  const time = Date.parse(`${date}T00:00:00Z`);
  return /^\d{4}-\d{2}-\d{2}$/.test(date) && Number.isFinite(time) ? WEEKDAYS[new Date(time).getUTCDay()] : '';
}

function seoulTime(at: string) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date(at)).map(part => [part.type, part.value]));
  return `${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`;
}

export function HolidayPage({ model, csrf }: { model: HolidayModel; csrf: CsrfToken }) {
  const year = model.year;
  const lockedIds = useMemo(() => new Set(model.rows.filter(row => model.closedMonths.includes(row.date.slice(0, 7))).map(row => row.id)), [model]);
  const [drafts, setDrafts] = useState<Draft[]>(() => model.rows.map(fromServer));
  const [baseline, setBaseline] = useState(() => snapshot(model.rows.map(fromServer)));
  const [version, setVersion] = useState(model.version);
  const [applied, setApplied] = useState(model.applied);
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const [reason, setReason] = useState('');
  const [commandId, setCommandId] = useState(() => crypto.randomUUID());
  const [errors, setErrors] = useState<RowError[]>([]);
  const [notice, setNotice] = useState('');
  const [message, setMessage] = useState('');
  const [result, setResult] = useState<Result | null>(null);
  const [busy, setBusy] = useState(false);
  const [accessLost, setAccessLost] = useState(false);
  const dirty = snapshot(drafts) !== baseline;

  // 내용이 바뀌면 새 명령으로 저장한다(서버 입력 해시가 행 내용을 포함).
  function edit(next: (rows: Draft[]) => Draft[]) {
    setDrafts(next); setCommandId(crypto.randomUUID()); setResult(null);
  }
  const update = (id: string, field: Field, value: string) => edit(rows => rows.map(row => row.clientRowId === id ? { ...row, [field]: value } : row));

  const columns = useMemo<ColDef<Draft>[]>(() => {
    const editor = (field: Field, headerName: string, minWidth: number, extra: { type?: 'date'; required?: boolean; maxLength?: number; options?: typeof CATEGORIES }) =>
      sheetEditorColumn<Draft>({ field, headerName, minWidth }, (row, index) => ({
        label: `${headerName} ${index + 1}`, value: row[field], readOnly: lockedIds.has(row.clientRowId),
        onChange: value => update(row.clientRowId, field, value), ...extra,
      }));
    return [
      editor('date', '날짜', 130, { type: 'date', required: true }),
      sheetColumn<Draft>({ colId: 'weekday', headerName: '요일', minWidth: 60, maxWidth: 70 }, row => weekday(row.date)),
      editor('name', '휴일명', 160, { required: true, maxLength: 120 }),
      editor('category', '구분', 110, { options: CATEGORIES }),
      editor('basis', '근거', 220, { maxLength: 500 }),
      sheetColumn<Draft>({ colId: 'status', headerName: '상태', minWidth: 130 }, row =>
        lockedIds.has(row.clientRowId) ? '마감 월(변경 불가)' : row.saved === content(row) ? '반영됨' : '저장 전 변경'),
    ];
  }, [lockedIds]);

  const sheetErrors = useMemo<SheetFieldError[]>(() => errors.flatMap(error => error.clientRowId
    ? [{ clientRowId: error.clientRowId, field: error.field, message: error.message }] : []), [errors]);
  const errorLabel = (error: RowError) => {
    if (!error.clientRowId) return error.message;
    const date = drafts.find(row => row.clientRowId === error.clientRowId)?.date;
    return `${date || '날짜 없음'}: ${error.message}`;
  };

  async function importPublic() {
    setBusy(true); setMessage(''); setNotice('');
    try {
      const { status, data, lost } = await postJson<Preview & { message?: string }>('/admin/holidays/public-preview', csrf, { year });
      if (lost) { setAccessLost(true); return; }
      if (status !== 200) { setMessage(data.message ?? '공휴일을 불러오지 못했습니다.'); return; }
      if (data.holidays.length === 0) { setMessage(`${year}년 공휴일은 아직 내장되지 않았습니다. 날짜를 직접 추가하세요.`); return; }
      const have = new Set(drafts.map(row => row.date));
      const added = data.holidays.filter(holiday => !have.has(holiday.date)).map(holiday => ({
        clientRowId: crypto.randomUUID(), date: holiday.date, name: holiday.name, category: 'PUBLIC', basis: holiday.basis,
      }));
      edit(rows => [...rows, ...added].sort((a, b) => a.date.localeCompare(b.date)));
      setNotice(`${added.length}일을 불러왔습니다. 저장해야 근태에 반영됩니다. ${data.notice}`);
    } catch { setMessage('공휴일을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'); }
    finally { setBusy(false); }
  }

  async function save() {
    setBusy(true); setMessage(''); setErrors([]);
    try {
      const rows = drafts.map(({ clientRowId, date, name, category, basis }) => ({ clientRowId, date, name, category, basis }));
      const { status, data, lost } = await postJson<Result & { message?: string; errors?: RowError[] }>('/admin/holidays/save', csrf,
        { commandId, reason, year, expectedVersion: version, rows });
      if (lost) { setAccessLost(true); return; }
      if (status === 200) {
        const saved = drafts.map(row => ({ ...row, saved: content(row) }));
        setDrafts(saved); setBaseline(snapshot(saved)); setVersion(data.version); setResult(data);
        setApplied({ at: new Date().toISOString(), by: null }); setNotice(''); setCommandId(crypto.randomUUID());
        return;
      }
      setCommandId(crypto.randomUUID());
      if (status === 422 && Array.isArray(data.errors)) { setErrors(data.errors); return; }
      setMessage(data.message ?? '저장하지 못했습니다.');
    } catch {
      // 응답을 받지 못했으면 같은 명령 식별자로 다시 저장해 중복 반영을 막는다.
      setMessage('저장 결과를 확인하지 못했습니다. 같은 내용으로 다시 저장하면 한 번만 반영됩니다.');
    } finally { setBusy(false); }
  }

  if (accessLost) return <section className="w-page"><p role="alert">로그인 상태와 권한을 다시 확인하세요.</p>
    <a href="/login" className="t-btn">다시 로그인</a></section>;
  return (
    <section className="w-page" data-unsaved-changes={String(dirty)} data-modal-busy={String(busy)}>
      <PageHeader title="휴일 관리" help="연도별 휴일을 관리합니다. 저장하면 바뀐 날짜만 근태에 반영되고, 그 날짜의 근무 확인과 일 결과는 다시 확인·재계산이 필요해집니다. 마감 월은 바꿀 수 없습니다."
        actions={<>
          <a className="t-btn" href={`/admin/holidays?year=${year - 1}`}>이전 연도</a>
          <strong>{`${year}년`}</strong>
          <a className="t-btn" href={`/admin/holidays?year=${year + 1}`}>다음 연도</a>
        </>} />
      <p role="status">{applied
        ? `근태 반영됨 · 마지막 반영 ${seoulTime(applied.at)}${applied.by ? ` · ${applied.by}` : ''}`
        : '아직 근태 미반영'}</p>
      {model.closedMonths.length > 0 && <p className="t-muted">{`마감 월: ${model.closedMonths.join(', ')}`}</p>}
      <WorklogSheet<Draft> label={`${year}년 휴일`} count={drafts.length} rows={drafts} columns={columns} getRowId={rowId}
        errors={sheetErrors} scope="all" emptyMessage="등록한 휴일이 없습니다."
        selectedIds={selected} onSelectionChange={setSelected} isRowSelectable={row => !lockedIds.has(row.clientRowId)}
        actions={<>
          <button type="button" className="t-btn" disabled={busy}
            onClick={() => edit(rows => [...rows, { clientRowId: crypto.randomUUID(), date: '', name: '', category: 'COMPANY', basis: '' }])}>행 추가</button>
          <button type="button" className="t-btn" disabled={busy || selected.size === 0}
            onClick={() => { edit(rows => rows.filter(row => !selected.has(row.clientRowId))); setSelected(new Set()); }}>선택 삭제</button>
          <button type="button" className="t-btn" disabled={busy} onClick={importPublic}>{`${year}년 공휴일 불러오기`}</button>
        </>} />
      {notice && <p role="status">{notice}</p>}
      {errors.length > 0 && (
        <div className="notice" role="alert">
          <p>{`저장하지 못했습니다. 오류 ${errors.length}건`}</p>
          <ul>{errors.map((error, index) => <li key={index}>{errorLabel(error)}</li>)}</ul>
        </div>
      )}
      <div className="wl-save-reason">
        <label htmlFor="holiday-reason" className="wl-required">저장 사유</label>
        <textarea id="holiday-reason" value={reason} maxLength={500} onChange={event => setReason(event.target.value)} />
      </div>
      <div className="wl-form-actions" aria-busy={busy}>
        <button type="button" className="t-btn primary" onClick={save}
          disabled={busy || reason.trim() === '' || (!dirty && version > 0)}>저장</button>
      </div>
      {message && <p className="notice" role="alert">{message}</p>}
      {result && <p role="status">{`저장했습니다. 추가 ${result.added} · 수정 ${result.updated} · 삭제 ${result.deleted} · 근태 반영 ${result.applied.length}일`}</p>}
    </section>
  );
}
