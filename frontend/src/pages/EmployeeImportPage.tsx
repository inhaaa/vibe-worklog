import { useMemo, useState } from 'react';
import type { ColDef } from 'ag-grid-community';
import { PageHeader } from '../components/PageHeader';
import { WorklogSheet, sheetEditorColumn } from '../components/WorklogSheet';
import type { SheetFieldError } from '../components/WorklogSheet';
import type { CsrfToken } from '../types';
import { IMPORT_FIELDS, parseClipboardTable, toImportDrafts } from './clipboardTable';
import type { ImportDraft } from './clipboardTable';

export interface EmployeeImportModel { organizations: { id: string; code: string; name: string }[]; maxRows: number }
interface FieldError { field: string; message: string }
interface Preview { valid: boolean; rows: { clientRowId: string; employeeNo: string | null; errors: FieldError[] }[] }
interface Result { batchId: string; employees: number; accounts: number; employeeNos: string[] }

const CAPTIONS: Record<(typeof IMPORT_FIELDS)[number], string> = {
  employeeNo: '사번', displayName: '이름', joinedOn: '입사일', birthDate: '생년월일',
  organizationCode: '조직코드', gradeName: '직급', positionName: '직책',
};

async function postJson<T>(url: string, csrf: CsrfToken, body: object): Promise<{ status: number; data: T }> {
  const response = await fetch(url, {
    method: 'POST', credentials: 'same-origin',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify(body),
  });
  return { status: response.status, data: await response.json() as T };
}

export function EmployeeImportPage({ model, csrf }: { model: EmployeeImportModel; csrf: CsrfToken }) {
  const [drafts, setDrafts] = useState<ImportDraft[]>([]);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [reviewed, setReviewed] = useState('');
  const [reason, setReason] = useState('');
  const [commandId, setCommandId] = useState(() => crypto.randomUUID());
  const [result, setResult] = useState<Result | null>(null);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const current = JSON.stringify(drafts);
  const fresh = preview !== null && reviewed === current;
  const errors: SheetFieldError[] = fresh
    ? preview.rows.flatMap(row => row.errors.map(error => ({ clientRowId: row.clientRowId, field: error.field, message: error.message })))
    : [];
  const invalidRows = fresh ? preview.rows.filter(row => row.errors.length > 0).length : 0;

  function load(text: string) {
    const next = toImportDrafts(parseClipboardTable(text));
    if (next.length > model.maxRows) { setMessage(`한 번에 ${model.maxRows}행까지 등록할 수 있습니다.`); return; }
    setDrafts(next); setPreview(null); setResult(null); setMessage('');
  }

  function update(rowId: string, field: (typeof IMPORT_FIELDS)[number], value: string) {
    setDrafts(rows => rows.map(row => row.clientRowId === rowId ? { ...row, [field]: value } : row));
  }

  const columns = useMemo<ColDef<ImportDraft>[]>(() => IMPORT_FIELDS.map(field => sheetEditorColumn<ImportDraft>(
    { field, headerName: CAPTIONS[field], minWidth: field === 'displayName' ? 140 : 110 },
    (row, index) => ({ label: `${CAPTIONS[field]} ${index + 1}`, value: row[field], onChange: value => update(row.clientRowId, field, value) }),
  )), []);

  async function validate() {
    setBusy(true); setMessage('');
    try {
      const { status, data } = await postJson<Preview & { message?: string }>('/admin/employees/import/preview', csrf, { rows: drafts });
      if (status !== 200) { setMessage(data.message ?? '검증하지 못했습니다.'); return; }
      setPreview(data); setReviewed(current);
    } catch { setMessage('검증하지 못했습니다. 잠시 후 다시 시도해 주세요.'); }
    finally { setBusy(false); }
  }

  async function save() {
    setBusy(true); setMessage('');
    try {
      const { status, data } = await postJson<Result & Preview & { message?: string }>('/admin/employees/import/save', csrf, { commandId, reason, rows: drafts });
      if (status === 200) { setResult(data); setDrafts([]); setPreview(null); setCommandId(crypto.randomUUID()); return; }
      if (status === 422 && Array.isArray(data.rows)) { setPreview(data); setReviewed(current); setCommandId(crypto.randomUUID()); return; }
      setMessage(data.message ?? '저장하지 못했습니다.');
    } catch {
      // 응답을 받지 못했으면 같은 명령 식별자로 다시 저장해 중복 등록을 막는다.
      setMessage('저장 결과를 확인하지 못했습니다. 같은 내용으로 다시 저장하면 한 번만 반영됩니다.');
    } finally { setBusy(false); }
  }

  return (
    <section className="w-page">
      <PageHeader title="재직자 일괄 등록" help="엑셀에서 사번·이름·입사일·생년월일·조직코드·직급·직책 7개 열을 머리글 없이 복사해 붙여넣습니다. 사번을 비우면 입사 연도로 자동 발급합니다."
        actions={<a className="t-btn" href="/admin/employees/import/template" download>양식 내려받기</a>} />
      <p className="t-muted">조직코드: {model.organizations.map(o => `${o.code} ${o.name}`).join(', ') || '등록된 조직 없음'}</p>
      <textarea aria-label="엑셀에서 복사한 행 붙여넣기" rows={3} placeholder="엑셀에서 복사한 행을 여기에 붙여넣으세요."
        onPaste={event => { event.preventDefault(); load(event.clipboardData.getData('text/plain')); }} />
      <WorklogSheet<ImportDraft> label="붙여넣은 재직자" count={drafts.length} rows={drafts} columns={columns}
        getRowId={row => row.clientRowId} errors={errors} scope="all" emptyMessage="붙여넣은 행이 없습니다." />
      {fresh && <p role="status">{preview.valid ? '모든 행 정상' : `오류 ${invalidRows}행`}</p>}
      <label>등록 사유<textarea value={reason} maxLength={500} onChange={event => setReason(event.target.value)} /></label>
      <div className="wl-form-actions" aria-busy={busy}>
        <button type="button" className="t-btn" onClick={validate} disabled={busy || drafts.length === 0}>검증</button>
        <button type="button" className="t-btn primary" onClick={save} disabled={busy || !fresh || !preview.valid || reason.trim() === ''}>저장</button>
      </div>
      {message && <p className="notice" role="alert">{message}</p>}
      {result && (
        <div role="status">
          <p>{`등록 ${result.employees}명 · 계정 ${result.accounts}개`}</p>
          <p>다음 단계: <a href="/admin/schedules">근무 설정</a> · <a href="/admin/leave-balances">연차 관리</a> · <a href="/admin/accounts">사용자 관리</a></p>
        </div>
      )}
    </section>
  );
}
