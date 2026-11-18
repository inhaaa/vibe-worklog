import { useEffect, useMemo, useRef, useState } from 'react';
import type { ColDef } from 'ag-grid-community';
import { PageHeader } from '../components/PageHeader';
import { WorklogSheet, sheetEditorColumn } from '../components/WorklogSheet';
import type { SheetEditorField, WorklogSheetHandle } from '../components/WorklogSheet';
import type { CsrfToken } from '../types';
import type { BalancesModel } from './WorkflowTypes';
import './LeaveBalanceBatch.css';

type Operation = 'OPENING' | 'ADJUSTMENT';
type Draft = {
  clientRowId: string; operation: Operation; employeeNo: string; effectiveOn: string; quantityDays: string;
  usableFrom: string; usableTo: string; bucketId: string; sourceReference: string; reason: string;
};
type RowField = Exclude<keyof Draft, 'clientRowId'>;
type CommandRow = Omit<Draft, 'usableFrom' | 'usableTo' | 'bucketId'> & {
  usableFrom: string | null; usableTo: string | null; bucketId: string | null; expectedVersion: number | null;
};
type Command = { commandId: string; rows: CommandRow[] };
type ReviewRow = {
  clientRowId: string; employeeName: string | null; beforeDays: number | null; afterDays: number | null;
  expectedVersion: number | null; errors: { field: string; message: string }[];
};
type Review = { valid: boolean; rows: ReviewRow[] };
type Receipt = { batchId: string; rows: { clientRowId: string; id: string; version: number }[] };
type BucketOption = { id: string; baseDate: string; usableFrom: string; usableTo: string | null; sourceReference: string; balanceDays: number };
type Lookup = { buckets: BucketOption[]; loading?: boolean; error?: string };
const base = '/admin/leave-balances/bulk';
const limit = 50;
const rowKey = (row: Draft) => row.clientRowId;
const operationNames: Record<Operation, string> = { OPENING: '기초 등록', ADJUSTMENT: '증감 조정' };

function newRow(model: BalancesModel): Draft {
  return { clientRowId: crypto.randomUUID(), operation: 'OPENING', employeeNo: '', effectiveOn: model.form.baseDate,
    quantityDays: '', usableFrom: model.form.usableFrom, usableTo: model.form.usableTo ?? '', bucketId: '', sourceReference: '', reason: '' };
}

function reviewMatches(result: Review, rows: CommandRow[]) {
  return typeof result?.valid === 'boolean' && Array.isArray(result.rows) && result.rows.length === rows.length
    && rows.every(row => {
      const match = result.rows.find(item => item?.clientRowId === row.clientRowId);
      return match && Array.isArray(match.errors) && (!result.valid || (!match.errors.length
        && Number.isFinite(match.beforeDays) && Number.isFinite(match.afterDays)
        && (row.operation === 'OPENING' || Number.isSafeInteger(match.expectedVersion))));
    });
}

function receiptMatches(result: Receipt, rows: CommandRow[]) {
  return Boolean(result?.batchId) && Array.isArray(result.rows) && result.rows.length === rows.length
    && rows.every(row => result.rows.some(item => item?.clientRowId === row.clientRowId && typeof item.id === 'string'
      && item.id.length > 0 && Number.isSafeInteger(item.version) && (!row.bucketId || row.bucketId === item.id)));
}

export function LeaveBalanceBatch({ model, csrf }: { model: BalancesModel; csrf: CsrfToken }) {
  const [rows, setRows] = useState<Draft[]>(() => [newRow(model)]);
  const [selected, setSelected] = useState(new Set<string>());
  const [options, setOptions] = useState(new Map<string, Lookup>());
  const [review, setReview] = useState<Review>();
  const [confirmed, setConfirmed] = useState<Command>();
  const [receipt, setReceipt] = useState<Receipt>();
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [accessLost, setAccessLost] = useState(false);
  const [message, setMessage] = useState('');
  const inflight = useRef(false);
  const sheet = useRef<WorklogSheetHandle>(null);
  const feedback = useRef<HTMLElement>(null);
  const employees = useMemo(() => [['', '사번 선택'] as const, ...model.employees.map(employee =>
    [employee.employeeNo, employee.employeeNo + ' · ' + employee.displayName] as const)], [model.employees]);
  const locked = busy || uncertain;
  const individual = '/admin/leave-balances?employeeId=' + encodeURIComponent(model.employee.id);

  useEffect(() => {
    if (!dirty || receipt || accessLost) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty, receipt, accessLost]);
  useEffect(() => { if (review || receipt || message) feedback.current?.focus(); }, [review, receipt, message]);

  async function loadBuckets(employeeNo: string) {
    const put = (value: Lookup) => setOptions(current => new Map(current).set(employeeNo, value));
    put({ buckets: [], loading: true });
    try {
      const response = await fetch(base + '/options?employeeNo=' + encodeURIComponent(employeeNo), {
        headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store',
      });
      if (response.status === 403) { put({ buckets: [], error: '이 사원의 연차 묶음을 조회할 권한이 없습니다.' }); return; }
      if (!response.ok || response.redirected) throw new Error();
      const data: { buckets: BucketOption[] } = await response.json();
      if (!Array.isArray(data.buckets)) throw new Error();
      put({ buckets: data.buckets });
    } catch {
      put({ buckets: [], error: '연차 묶음을 불러오지 못했습니다.' });
    }
  }

  function amend(next: Draft[]) {
    setRows(next); setDirty(next.length > 0);
    setReview(undefined); setConfirmed(undefined); setMessage('');
  }
  function replace(row: Draft) {
    if (inflight.current || uncertain) return;
    amend(rows.map(item => item.clientRowId === row.clientRowId ? row : item));
    if (row.operation === 'ADJUSTMENT' && row.employeeNo && !options.has(row.employeeNo)) void loadBuckets(row.employeeNo);
  }
  function column(field: RowField, caption: string, settings: (row: Draft) => Partial<SheetEditorField> = () => ({}),
    definition: Partial<ColDef<Draft>> = {}) {
    return sheetEditorColumn<Draft>({ field, headerName: caption, ...definition }, (row, index) => {
      const extra = settings(row);
      return { label: caption + ' ' + (index + 1), value: row[field], required: true, ...extra, disabled: locked || extra.disabled,
        onChange: value => {
          const next = { ...row, [field]: value };
          if (field === 'operation' || field === 'employeeNo') next.bucketId = '';
          replace(next);
        },
        onCancel: () => replace(row),
      };
    });
  }
  function openingDate(row: Draft): Partial<SheetEditorField> {
    return row.operation === 'OPENING' ? { type: 'date' } : { value: '', disabled: true };
  }
  function bucketField(row: Draft): Partial<SheetEditorField> {
    if (row.operation !== 'ADJUSTMENT') return { value: '', disabled: true };
    const lookup = options.get(row.employeeNo);
    const hint = !row.employeeNo ? '사번을 선택하세요' : lookup?.loading ? '조회 중…'
      : lookup?.error ? '조회 실패' : !lookup?.buckets.length ? '등록된 묶음 없음' : '묶음 선택';
    return { disabled: !lookup?.buckets.length, options: [['', hint], ...(lookup?.buckets ?? []).map(bucket =>
      [bucket.id, bucket.usableFrom + ' ~ ' + (bucket.usableTo ?? '종료 없음') + ' · 잔여 ' + bucket.balanceDays
        + '일 · ' + bucket.sourceReference + ' (' + bucket.baseDate + ' 기준)'] as const)] };
  }
  const columns = [
    column('operation', '처리 구분', () => ({ options: [['OPENING', '기초 등록'], ['ADJUSTMENT', '증감 조정']] })),
    column('employeeNo', '사번', () => ({ options: employees }), { pinned: 'left', minWidth: 170 }),
    column('effectiveOn', '기준일', () => ({ type: 'date' })),
    column('quantityDays', '일수', row => ({ type: 'number', min: row.operation === 'OPENING' ? 0 : -100000, step: 0.5 })),
    column('usableFrom', '사용 가능 시작일', openingDate),
    column('usableTo', '사용 가능 종료일', row => ({ ...openingDate(row), required: false })),
    column('bucketId', '조정할 묶음', bucketField, { minWidth: 230 }),
    column('sourceReference', '근거 참조', () => ({ maxLength: 500 }), { minWidth: 180 }),
    column('reason', '사유', () => ({ maxLength: 500 }), { minWidth: 180 }),
  ];
  const errors = review?.rows.flatMap(row => row.errors.map(error =>
    ({ ...error, clientRowId: row.clientRowId, field: error.field === 'expectedVersion' ? 'bucketId' : error.field })));

  async function submit(save: boolean) {
    if (inflight.current || receipt || !rows.length || (save ? !confirmed : uncertain)) return;
    const command = save ? confirmed! : { commandId: crypto.randomUUID(), rows: rows.map(row => ({ ...row,
      usableFrom: row.operation === 'OPENING' ? row.usableFrom || null : null,
      usableTo: row.operation === 'OPENING' ? row.usableTo || null : null,
      bucketId: row.operation === 'ADJUSTMENT' ? row.bucketId || null : null, expectedVersion: null,
    })) };
    inflight.current = true; setBusy(true); setMessage('');
    if (!save) { setReview(undefined); setConfirmed(undefined); }
    try {
      const response = await fetch(base + (save ? '/save' : '/preview'), { method: 'POST', credentials: 'same-origin',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify(command) });
      if (response.redirected || response.status === 401 || response.status === 403) { setAccessLost(true); return; }
      if (response.status >= 500) throw new Error();
      const result = await response.json();
      if (save && response.ok) {
        if (!receiptMatches(result, command.rows)) throw new Error();
        setReceipt(result); setUncertain(false); setConfirmed(undefined);
      } else if ((response.ok || response.status === 409 || response.status === 422) && reviewMatches(result, command.rows)) {
        const checked: Review = result;
        setReview(checked); setUncertain(false);
        if (response.ok && checked.valid) {
          setConfirmed({ ...command, rows: command.rows.map(row => ({ ...row, expectedVersion:
            checked.rows.find(item => item.clientRowId === row.clientRowId)!.expectedVersion })) });
        } else {
          setConfirmed(undefined);
          if (save) setMessage('저장하지 못했습니다. 행별 결과를 확인하고 다시 미리보기를 실행하세요.');
          if (response.status === 409) {
            const changed = new Set(checked.rows.filter(row => row.errors.some(error => error.field === 'expectedVersion')).map(row => row.clientRowId));
            const employeeNos = new Set(command.rows.filter(row => changed.has(row.clientRowId)).map(row => row.employeeNo));
            employeeNos.forEach(employeeNo => void loadBuckets(employeeNo));
          }
        }
      } else if (response.ok) {
        throw new Error();
      } else {
        setConfirmed(undefined); setReview(undefined); setUncertain(false);
        setMessage(typeof result?.message === 'string' ? result.message : '입력 내용을 확인하고 다시 미리보기를 실행하세요.');
      }
    } catch {
      setUncertain(save);
      setMessage(save ? '저장 결과를 확인할 수 없습니다. 같은 내용으로 다시 확인하세요.' : '미리보기를 불러오지 못했습니다. 다시 시도하세요.');
    } finally {
      inflight.current = false; setBusy(false);
    }
  }

  if (accessLost) return <section className="wl-section"><p role="alert">로그인 상태와 연차 관리 권한을 다시 확인하세요.</p>
    <a href="/login" className="t-btn">다시 로그인</a></section>;
  return <div className="wl-balance-batch" data-unsaved-changes={dirty && !receipt ? 'true' : undefined}
    data-modal-busy={busy ? 'true' : undefined}>
    <PageHeader title="연차 잔여 일괄 입력" actions={<a className="t-btn" href={individual}>개인별 관리</a>} />
    {receipt ? <section className="wl-section" ref={feedback} tabIndex={-1} aria-label="일괄 저장 결과">
      <h2>{receipt.rows.length}행을 저장했습니다</h2>
      <ul className="wl-rows">{rows.map((row, i) => {
        const saved = receipt.rows.find(item => item.clientRowId === row.clientRowId)!;
        const name = review?.rows.find(item => item.clientRowId === row.clientRowId)?.employeeName ?? '';
        return <li key={row.clientRowId}>{i + 1}행 · <a href={'/admin/leave-balances/buckets/' + encodeURIComponent(saved.id)}>
          {row.employeeNo} · {name} 원장 보기</a> · {operationNames[row.operation]} {row.quantityDays}일</li>;
      })}</ul>
    </section> : <>
      <p className="t-muted">기초는 사용 가능 기간을, 조정은 기존 묶음을 지정하세요. 일수는 0.5일 단위로 입력합니다.</p>
      <WorklogSheet label="연차 일괄 입력 시트" title="입력 행" rows={rows} columns={columns} getRowId={rowKey} scope="all"
        ref={sheet} selectedIds={selected} onSelectionChange={setSelected} selectionLabel={(_, i) => (i + 1) + '행 선택'}
        errors={errors} emptyMessage="행을 추가해 사원과 일수를 입력하세요." actions={<>
          <button type="button" className="t-btn" disabled={locked || rows.length >= limit} onClick={event => {
            const trigger = event.currentTarget;
            const row = newRow(model); amend([...rows, row]);
            setTimeout(() => {
              if (document.activeElement === trigger) sheet.current?.focusCell(row.clientRowId, '__select');
            }, 0);
          }}>행 추가</button>
          <button type="button" className="t-btn" disabled={locked || !selected.size} onClick={() => {
            amend(rows.filter(row => !selected.has(row.clientRowId))); setSelected(new Set());
          }}>선택 행 제거</button>
        </>} />
      {Array.from(options).filter(([employeeNo, lookup]) => lookup.error && rows.some(row => row.operation === 'ADJUSTMENT' && row.employeeNo === employeeNo))
        .map(([employeeNo, lookup]) => <p key={employeeNo} role="alert">{employeeNo} · {lookup.error}{' '}
          <button type="button" className="t-btn" disabled={locked} onClick={() => void loadBuckets(employeeNo)}>묶음 다시 조회</button></p>)}
      {review && <section className="wl-section wl-balance-review" aria-label="일괄 입력 미리보기" tabIndex={-1} ref={feedback}>
        <h2>미리보기</h2>
        <ol>{rows.map((row, i) => {
          const checked = review.rows.find(item => item.clientRowId === row.clientRowId)!;
          return <li key={row.clientRowId}><div><strong>{i + 1}행 · {row.employeeNo} {checked.employeeName}</strong>
            <span>{operationNames[row.operation]}</span></div>
            {checked.beforeDays != null && <span>{checked.afterDays == null
              ? '현재 잔여 ' + checked.beforeDays + '일'
              : checked.beforeDays + '일 → ' + checked.afterDays + '일'}</span>}
            {checked.errors.map((error, index) => <p key={index} className="wl-balance-error">{error.message}</p>)}</li>;
        })}</ol>
      </section>}
      {message && <p className="notice" role="alert">{message}</p>}
      <div className="wl-form-actions" aria-busy={busy}>
        <button type="button" className="t-btn" disabled={locked || !rows.length} onClick={() => void submit(false)}>미리보기</button>
        <button type="button" className="t-btn primary" disabled={busy || !confirmed} onClick={() => void submit(true)}>
          {uncertain ? '같은 내용으로 저장 결과 확인' : '일괄 저장'}</button>
      </div>
    </>}
  </div>;
}
