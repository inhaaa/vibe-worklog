import { useEffect, useId, useMemo, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import type { ColDef } from 'ag-grid-community';
import { Modal } from '../components/Modal';
import { sheetColumn, WorklogSheet } from '../components/WorklogSheet';
import type { SheetFieldError, WorklogSheetHandle } from '../components/WorklogSheet';
import type { CsrfToken } from '../types';
import './RoleMembersSheet.css';

type Row = {
  employeeId: string; employeeNo: string; employeeName: string; organizationName: string | null;
  available: boolean; editable: boolean; version: number; restriction: string | null;
};
type Model = { role: string; members: Row[]; q: string; page: number; hasNext: boolean; total: number };
type Operation = 'GRANT' | 'REVOKE';
type Change = { row: Row; operation: Operation };
type DisplayRow = Row & { operation?: Operation };
type Latest = {
  clientRowId: string; employeeId: string; version: number; granted: boolean; available: boolean;
  editable?: boolean; restriction?: string | null;
};
type Problem = { message?: string; errors?: { clientRowId?: string; message: string }[]; latest?: Latest[] };
type Receipt = { affectedSelf: boolean; changes: Change[] };
type Command = {
  commandId: string; reason: string; rows: { clientRowId: string; employeeId: string; expectedVersion: number; operation: Operation }[];
};
type Outcome = { affectedSelf: boolean; rows: { clientRowId: string; employeeId: string; version: number }[] };
type Props = { model: Model; csrf: CsrfToken; reload: () => void; actorEmployeeId?: string };
type Candidates = { rows: Row[]; page: number; hasNext: boolean };
const limit = 50;
const empty = { rows: [], page: 0, hasNext: false };
const actionText = (operation: Operation) => operation === 'GRANT' ? '부여' : '해제';
const rowKey = (row: Row) => row.employeeId;
const expired = (response: Response) => response.status === 401 || response.status === 403 || response.redirected;

function complete(result: Outcome, command: Command) {
  return typeof result.affectedSelf === 'boolean' && Array.isArray(result.rows)
    && result.rows.length === command.rows.length && command.rows.every(row => result.rows.some(saved =>
      saved?.employeeId === row.employeeId && saved.clientRowId === row.clientRowId && saved.version === row.expectedVersion + 1));
}

const columns: ColDef<DisplayRow>[] = [
  { field: 'employeeNo', headerName: '사번', pinned: 'left', minWidth: 100 },
  sheetColumn({ field: 'employeeName', headerName: '이름' }, row =>
    <a href={`/admin/employees/${encodeURIComponent(row.employeeId)}/access`} aria-label={`${row.employeeName} 권한 관리`}>{row.employeeName}</a>),
  { field: 'organizationName', headerName: '현재 조직', valueFormatter: ({ value }) => value ?? '미배정' },
  { colId: 'state', headerName: '사용 상태', minWidth: 200, valueGetter: ({ data }) =>
    data?.restriction || (data?.available ? '사용 가능' : '사용 제한') },
  sheetColumn({ colId: 'planned', headerName: '변경 예정', minWidth: 126,
    valueGetter: ({ data }) => data?.operation ? `${actionText(data.operation)} 예정` : '' }, row =>
    row.operation ? <span className={`t-tag ${row.operation === 'GRANT' ? 'green' : 'red'}`}>{actionText(row.operation)} 예정</span> : '—'),
];

export function RoleMembersSheet(props: Props) {
  const { model } = props;
  return <MembershipEditor key={JSON.stringify([model.role, model.q, model.page])} {...props} />;
}

function MembershipEditor({ model, csrf, reload, actorEmployeeId }: Props) {
  const [draft, setDraft] = useState(new Map<string, Change>());
  const [selected, setSelected] = useState(new Set<string>());
  const [reviewed, setReviewed] = useState(new Map<string, Latest>());
  const [pickerOpen, setPickerOpen] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [reason, setReason] = useState('');
  const [hiddenChanges, setHiddenChanges] = useState(0);
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState<Command>();
  const [problem, setProblem] = useState<Problem & { conflict?: boolean }>();
  const [refreshRequired, setRefreshRequired] = useState(false);
  const [receipt, setReceipt] = useState<Receipt>();
  const [accessLost, setAccessLost] = useState(false);
  const sheet = useRef<WorklogSheetHandle>(null);
  const errorSummary = useRef<HTMLDivElement>(null);
  const formId = useId();
  const editable = model.role !== 'EMPLOYEE';
  const path = `/admin/access/roles/${encodeURIComponent(model.role)}`;
  const changes = Array.from(draft.values());
  const grants = changes.filter(change => change.operation === 'GRANT').length;
  const selfChange = changes.some(change => change.row.employeeId === actorEmployeeId);

  const rows = useMemo(() => {
    const displayed = new Map<string, DisplayRow>();
    for (const original of model.members) {
      const current = reviewed.get(original.employeeId);
      if (current && !current.granted) continue;
      displayed.set(original.employeeId, {
        ...original,
        ...(current && { version: current.version, available: current.available,
          editable: current.editable ?? current.available, restriction: current.restriction ?? null }),
      });
    }
    for (const [id, change] of draft) displayed.set(id, { ...change.row, operation: change.operation });
    return Array.from(displayed.values());
  }, [model.members, reviewed, draft]);
  const errors = useMemo<SheetFieldError[]>(() => (problem?.errors ?? [])
    .filter(error => error.clientRowId && draft.has(error.clientRowId))
    .map(error => ({ clientRowId: error.clientRowId!, field: 'planned', message: error.message })), [draft, problem]);
  const revokable = rows.filter(row => selected.has(row.employeeId) && row.editable && !row.operation);

  useEffect(() => {
    if (problem && !confirming) errorSummary.current?.focus();
  }, [confirming, problem]);

  useEffect(() => {
    if (!draft.size && !busy) return;
    const beforeUnload = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [busy, draft.size]);

  function requestAccess() {
    setAccessLost(true);
    setPickerOpen(false);
    setConfirming(false);
    setDraft(new Map());
    setUncertain(undefined);
  }

  function stage(rowsToChange: Row[], operation: Operation) {
    if (uncertain || problem?.conflict) return;
    const next = new Map(draft);
    for (const row of rowsToChange) next.set(row.employeeId, { row, operation });
    if (next.size > limit) {
      setProblem({ message: '최대 50명까지 한 번에 저장할 수 있습니다.' });
      return;
    }
    setProblem(undefined);
    setRefreshRequired(false);
    setDraft(next);
    setSelected(new Set());
  }

  function cancelChanges() {
    const selectedDraft = Array.from(selected).filter(id => draft.has(id));
    const next = new Map(draft);
    if (selectedDraft.length) selectedDraft.forEach(id => next.delete(id));
    else { next.clear(); setReason(''); }
    setDraft(next);
    setSelected(new Set());
    if (!problem?.conflict || !next.size) setProblem(undefined);
  }

  function acceptLatest() {
    const next = new Map(draft), versions = new Map(reviewed);
    for (const state of problem?.latest ?? []) {
      const change = next.get(state.clientRowId);
      if (!change) continue;
      versions.set(state.employeeId, state);
      if (!state.available || state.editable === false || state.granted === (change.operation === 'GRANT')) {
        next.delete(state.clientRowId);
      } else {
        next.set(state.clientRowId, { ...change, row: { ...change.row, version: state.version, available: state.available,
          editable: state.editable ?? state.available, restriction: state.restriction ?? null } });
      }
    }
    setReviewed(versions);
    setDraft(next);
    setProblem(undefined);
    setRefreshRequired(next.size === 0);
  }

  function openConfirmation() {
    setHiddenChanges(sheet.current?.hiddenChangeCount() ?? 0);
    setConfirming(true);
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || problem?.conflict) return;
    const checkedReason = reason.trim();
    if (!uncertain && (!checkedReason || Array.from(checkedReason).length > 500)) {
      setProblem({ message: '변경 사유를 1~500자로 입력해 주세요.' });
      event.currentTarget.querySelector('textarea')?.focus();
      return;
    }
    const command = uncertain ?? {
      commandId: crypto.randomUUID(), reason: checkedReason,
      rows: changes.map(({ row, operation }) =>
        ({ clientRowId: row.employeeId, employeeId: row.employeeId, expectedVersion: row.version, operation })),
    };
    setBusy(true);
    setProblem(undefined);
    try {
      const response = await fetch(`${path}/members/bulk`, { method: 'POST',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify(command) });
      if (expired(response)) { requestAccess(); return; }
      if (response.status >= 500) throw new Error('unconfirmed');
      const result = await response.json();
      if (!response.ok) {
        setUncertain(undefined);
        setProblem({ ...result, message: result.message || '권한 변경 요청을 확인해 주세요.', conflict: response.status === 409 });
        setConfirming(false);
        return;
      }
      if (!complete(result, command)) throw new Error('unconfirmed');
      setUncertain(undefined);
      setDraft(new Map());
      setSelected(new Set());
      setReason('');
      setConfirming(false);
      setReceipt({ affectedSelf: result.affectedSelf, changes });
    } catch {
      setUncertain(command);
      setProblem({ message: '저장 결과를 확인할 수 없습니다. 같은 내용으로 다시 확인해 주세요.' });
      setConfirming(false);
    } finally {
      setBusy(false);
    }
  }

  if (accessLost) return <section className="t-card" aria-label="권한 변경">
    <p role="alert">권한을 다시 확인해야 합니다.</p>
    <a className="t-btn" href="/login">다시 로그인</a>
  </section>;

  const confirmTitle = '권한 변경 확인';
  const editBlocked = busy || Boolean(uncertain || problem?.conflict);
  const finish = () => { setReceipt(undefined); reload(); };
  return <section className="wl-role-members" aria-label="권한별 사원" data-modal-busy={busy ? 'true' : undefined}>
    {!receipt?.affectedSelf && <>
      {refreshRequired && <div className="wl-role-error">
        <p role="status">최신 상태에서 다시 저장할 변경이 없습니다.</p>
        <button className="t-btn" type="button" onClick={reload}>최신 목록 조회</button>
      </div>}
      {problem && !confirming && <div className="wl-role-error" ref={errorSummary} role="alert" tabIndex={-1}>
        <p>{problem.message}</p>
        {problem.conflict && (problem.latest?.length ? <>
          <ul>{problem.latest.flatMap(state => {
            const change = draft.get(state.clientRowId);
            return change ? [<li key={state.clientRowId}>
              {change.row.employeeName} · 현재 {state.granted ? '부여됨' : '미부여'}
              {!state.available && ' · 사용 제한'} · 예정 {actionText(change.operation)}
            </li>] : [];
          })}</ul>
          <button className="t-btn" type="button" onClick={acceptLatest}>최신 상태 반영</button>
        </> : <button className="t-btn" type="button" onClick={reload}>최신 목록 다시 조회</button>)}
        {uncertain && <button type="button" className="t-btn" onClick={openConfirmation}>같은 내용으로 다시 확인</button>}
        {problem.errors?.filter(error => !error.clientRowId || !draft.has(error.clientRowId)).map((error, index) =>
          <p key={index}>{error.message}</p>)}
      </div>}
      <div data-unsaved-changes={draft.size > 0 ? 'true' : undefined}>
        <WorklogSheet label="권한 사원 시트" count={model.total} rows={rows} columns={columns} getRowId={rowKey}
          emptyMessage="선택한 권한의 사원이 없습니다." ref={sheet}
          selectedIds={editable ? selected : undefined} onSelectionChange={editable ? setSelected : undefined}
          selectionLabel={row => `${row.employeeName} 권한 행 선택`} isRowSelectable={row => row.editable}
          changedIds={new Set(draft.keys())} errors={errors}
          actions={editable ? <>
            <button className="t-btn" type="button" disabled={editBlocked || draft.size >= limit || Boolean(receipt)} onClick={() => setPickerOpen(true)}>사원 추가</button>
            <button className="t-btn" type="button" disabled={editBlocked || !revokable.length} onClick={() => stage(revokable, 'REVOKE')}>권한 해제</button>
            <button className="t-btn" type="button" disabled={busy || !!uncertain || !draft.size} onClick={cancelChanges}>변경 취소</button>
            <button className="t-btn primary" type="button" disabled={editBlocked || !draft.size} onClick={openConfirmation}>저장</button>
          </> : undefined} />
      </div>
    </>}
    {pickerOpen && <CandidatePicker path={path} blocked={draft} onExpired={requestAccess} onClose={() => setPickerOpen(false)}
      onAdd={chosen => { stage(chosen, 'GRANT'); setPickerOpen(false); }} />}
    {confirming && <Modal title={confirmTitle} size="s" busy={busy} onClose={() => setConfirming(false)} footer={<>
      <button className="t-btn" type="button" disabled={busy} onClick={() => setConfirming(false)}>취소</button>
      <button className="t-btn primary" type="submit" form={formId} disabled={busy}>{uncertain ? '같은 내용 확인' : `${changes.length}건 저장`}</button>
    </>}>
      <form id={formId} onSubmit={save} className="wl-role-reason">
        <p>부여 {grants}명 · 해제 {changes.length - grants}명{hiddenChanges ? ` · 시트에 숨은 변경 ${hiddenChanges}명` : ''}</p>
        <ul className="wl-role-targets">{changes.map(({ row, operation }) =>
          <li key={row.employeeId}>{row.employeeName} ({row.employeeNo}) · {actionText(operation)}</li>)}</ul>
        {selfChange && <p>내 권한이 바뀌면 저장 뒤 다시 로그인해야 합니다.</p>}
        {model.role === 'APPROVER' && grants < changes.length &&
          <p>권한을 해제하면 해당 사원이 결재를 처리할 수 없으므로 지정 승인자와 대기 신청을 확인해 주세요.</p>}
        <label htmlFor={`${formId}-reason`}>변경 사유</label>
        <textarea id={`${formId}-reason`} className="t-input" rows={4} maxLength={500} required value={reason}
          disabled={busy || !!uncertain} onChange={event => setReason(event.target.value)} />
        {problem && !uncertain && <p role="alert">{problem.message}</p>}
      </form>
    </Modal>}
    {receipt && <Modal title="권한 변경 완료" size="s" onClose={receipt.affectedSelf ? () => window.location.assign('/login') : finish}
      footer={receipt.affectedSelf ? <a className="t-btn primary" href="/login">다시 로그인</a>
        : <button className="t-btn primary" type="button" onClick={finish}>목록 확인</button>}>
      <p role="status">{receipt.changes.length}명의 권한 변경을 저장했습니다.</p>
      <ul className="wl-role-targets">{receipt.changes.map(({ row, operation }) =>
        <li key={row.employeeId}>{row.employeeName} ({row.employeeNo}) · {actionText(operation)} 완료</li>)}</ul>
      {receipt.affectedSelf && <p>내 권한이 변경되었습니다. 다시 로그인해 주세요.</p>}
    </Modal>}
  </section>;
}

function CandidatePicker({ path, blocked, onAdd, onClose, onExpired }: {
  path: string; blocked: ReadonlyMap<string, Change>; onAdd: (rows: Row[]) => void;
  onClose: () => void; onExpired: () => void;
}) {
  const [selected, setSelected] = useState(new Map<string, Row>());
  const [query, setQuery] = useState('');
  const [criteria, setCriteria] = useState({ q: '', page: 0 });
  const [result, setResult] = useState<Candidates>(empty);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const id = useId();
  const expire = useRef(onExpired);
  expire.current = onExpired;
  const remaining = limit - blocked.size;

  useEffect(() => {
    const request = new AbortController();
    setStatus('loading');
    const params = new URLSearchParams({ q: criteria.q, page: String(criteria.page) });
    void (async () => {
      try {
        const response = await fetch(`${path}/candidates?${params}`, { headers: { Accept: 'application/json' }, signal: request.signal });
        if (request.signal.aborted) return;
        if (expired(response)) { expire.current(); return; }
        if (!response.ok) throw new Error('candidate query failed');
        const data: Candidates = await response.json();
        if (request.signal.aborted) return;
        setResult(data);
        setStatus('ready');
      } catch {
        if (!request.signal.aborted) setStatus('error');
      }
    })();
    return () => request.abort();
  }, [criteria, path]);

  function toggle(row: Row) {
    const next = new Map(selected);
    if (!next.delete(row.employeeId) && next.size < remaining) next.set(row.employeeId, row);
    setSelected(next);
  }

  return <Modal title="사원 추가" size="m" onClose={onClose} footer={<>
    <button type="button" className="t-btn" onClick={onClose}>취소</button>
    <button type="button" className="t-btn primary" disabled={!selected.size}
      onClick={() => onAdd(Array.from(selected.values()))}>선택한 사원 추가</button>
  </>}>
    <form className="wl-role-candidate-query" onSubmit={event => {
      event.preventDefault(); setCriteria({ q: query.trim(), page: 0 });
    }}>
      <label htmlFor={id}>후보 사원 검색</label>
      <input id={id} className="t-input" type="search" placeholder="사번 또는 이름" maxLength={120}
        value={query} onChange={event => setQuery(event.target.value)} />
      <button type="submit" className="t-btn">조회</button>
    </form>
    <p aria-live="polite">선택 {selected.size}명 · 추가 가능 {remaining}명</p>
    {status === 'loading' && <p role="status">후보를 불러오는 중입니다.</p>}
    {status === 'error' && <p role="alert">후보를 불러오지 못했습니다. 다시 조회해 주세요.</p>}
    {status === 'ready' && <>
      {result.rows.length ?
      <ul className="wl-role-candidates">{result.rows.map(row => <li key={row.employeeId}>
        <label><input type="checkbox" aria-label={`${row.employeeName} 후보 선택`} checked={selected.has(row.employeeId)}
          disabled={blocked.has(row.employeeId) || !row.editable || !row.available || !selected.has(row.employeeId) && selected.size >= remaining}
          onChange={() => toggle(row)} />
          <span><strong>{row.employeeName}</strong> <span>{row.employeeNo} · {row.organizationName ?? '미배정'}</span>
            {blocked.has(row.employeeId) && <span>변경 예정</span>}</span></label>
      </li>)}</ul> : <p>부여 가능한 사원이 없습니다. <a href="/admin/employees">사원 관리에서 계정과 초대 확인</a></p>}
      {(result.page > 0 || result.hasNext) && <nav className="wl-role-candidate-pages" aria-label="후보 페이지">
        <button className="t-btn" type="button" disabled={result.page === 0}
          onClick={() => setCriteria({ ...criteria, page: result.page - 1 })}>이전</button>
        <span>{result.page + 1} 페이지</span>
        <button className="t-btn" type="button" disabled={!result.hasNext}
          onClick={() => setCriteria({ ...criteria, page: result.page + 1 })}>다음</button>
      </nav>}
    </>}
  </Modal>;
}
