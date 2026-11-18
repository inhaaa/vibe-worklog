import { useEffect, useId, useMemo, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import type { ColDef } from 'ag-grid-community';
import type { PageProps } from '../types';
import { Form, fmtDate, fmtDateTime } from '../ui';
import { WorklogSheet, sheetColumn, sheetEditorColumn } from '../components/WorklogSheet';
import { PageHeader } from '../components/PageHeader';
import { SheetSaveDialog, sheetChangeCounts } from '../components/SheetSaveDialog';
import { sheetStateColumn, sheetStateOf } from '../components/SheetState';
import type { WorklogSheetHandle } from '../components/WorklogSheet';
import type { SheetEditorField } from '../components/SheetCellEditor';

type Value = string | number | boolean | null | undefined;
interface Entry {
  id: string;
  code: string;
  name: string;
  version: number;
  systemOwned?: boolean;
  systemKind?: string | null;
  supported?: boolean;
  capabilityMessage?: string;
  [field: string]: Value;
}
interface Row {
  clientRowId: string;
  id?: string | null;
  expectedVersion?: number | null;
  operation: 'CREATE' | 'UPDATE' | 'DEACTIVATE';
  code: string;
  name: string;
  [field: string]: Value;
}
interface FieldError { clientRowId: string; field: string; message: string }
interface Sheet { commandId: string; reason: string; rows: Row[]; latestRows: Entry[]; errors: FieldError[] }
interface CommonModel {
  q: string; page: number; hasNext: boolean; groups: Entry[]; groupId: string | null;
  selectedGroup?: Entry | null; codeQ: string; codePage: number; codeHasNext: boolean;
  codes: Entry[]; groupSheet: Sheet; codeSheet: Sheet;
}
interface TypeVersion {
  id: string; typeId: string; code: string; name: string; revision: number;
  effectiveFrom: string; enabled: boolean; unit: string; payKind: string;
  deductionRule: string; recognitionRule: string; basis: string; reason: string;
  createdAt: string; createdBy?: string; createdByName?: string | null;
}
interface TypeModel { today: string; q: string; page: number; hasNext: boolean; types: Entry[]; versions: TypeVersion[]; sheet: Sheet }
interface Field {
  key: string; label: string; kind?: 'check' | 'number' | 'date' | 'select';
  max?: number; required?: boolean; choices?: [string, string][];
}
const commonFields: Field[] = [
  { key: 'code', label: '코드', max: 40, required: true },
  { key: 'name', label: '이름', max: 120, required: true },
  { key: 'description', label: '설명', max: 500 },
  { key: 'sortOrder', label: '정렬', kind: 'number', required: true },
  { key: 'active', label: '사용', kind: 'check' },
];
const typeFields: Field[] = [
  { key: 'code', label: '코드', max: 8, required: true },
  { key: 'name', label: '이름', max: 120, required: true },
  { key: 'effectiveFrom', label: '적용일', kind: 'date', required: true },
  { key: 'unit', label: '단위', kind: 'select', choices: [['DAY', '일(DAY)'], ['HOUR', '시간(HOUR)'], ['NONE', '해당 없음']] },
  { key: 'payKind', label: '유급 구분', kind: 'select', choices: [['UNSPECIFIED', '미확인'], ['PAID', '유급'], ['UNPAID', '무급']] },
  { key: 'deductionRule', label: '차감 규칙', kind: 'select', choices: [['NONE', '차감 없음'], ['LEAVE_DAY', '승인 시 연차 일수 차감']] },
  { key: 'recognitionRule', label: '근무 인정', kind: 'select', choices: [['NOT_SUPPORTED', '산정 미지원']] },
  { key: 'enabled', label: '신규 신청 허용', kind: 'check' },
  { key: 'basis', label: '정책 근거', max: 500, required: true },
];
function href(values: Record<string, string | number | null>, path = '/admin/common-codes') {
  const query = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => { if (value != null) query.set(key, String(value)); });
  return `${path}?${query}`;
}
function systemText(kind: Value) {
  return kind === 'ANNUAL_LEAVE' ? '시스템 연차' : kind === 'ATTENDANCE_CORRECTION' ? '시스템 기록 정정' : '사용자 초안';
}
function useUnloadGuard(dirty: boolean) {
  useEffect(() => {
    const guard = (event: BeforeUnloadEvent) => { if (dirty) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', guard);
    return () => window.removeEventListener('beforeunload', guard);
  }, [dirty]);
}
function CodeSheet({ title, heading, action, model, fields, context, today, group, error, pagination, busy = false }: {
  title: string; heading?: ReactNode; action: string; model: Sheet; fields: Field[];
  context: Record<string, string | number | null>; today?: string; group?: Entry | null; error?: string | null;
  pagination?: ReactNode; busy?: boolean;
}) {
  const attendance = Boolean(today);
  const detail = action === '/admin/common-codes/bulk';
  const [rows, setRows] = useState(model.rows);
  const [reason, setReason] = useState(model.reason);
  const [commandId, setCommandId] = useState(model.commandId);
  const [selected, setSelected] = useState(new Set<string>());
  const [errors, setErrors] = useState(model.errors ?? []);
  const [confirming, setConfirming] = useState(() => (model.errors ?? []).some(item => item.field === 'reason'));
  const formId = useId();
  const sheet = useRef<WorklogSheetHandle>(null);
  const sheetElement = useRef<HTMLDivElement>(null);
  const latest = useMemo(() => new Map(model.latestRows.map(entry => [entry.id, entry])), [model.latestRows]);
  const changed = (row: Row) => {
    const original = row.id ? latest.get(row.id) : undefined;
    if (!original) return true;
    if (row.operation === 'DEACTIVATE') return attendance || original.active === true;
    return fields.some(field => row[field.key] !== original[field.key]);
  };
  const dirty = rows.filter(changed);
  const conflicts = dirty.filter(row => row.id && latest.has(row.id) && row.expectedVersion !== latest.get(row.id)!.version);
  const unsaved = dirty.length > 0 || reason.length > 0;
  const reasonError = errors.find(item => item.field === 'reason')?.message;
  const lockedGroup = detail && (!group || group.systemOwned === true);
  useUnloadGuard(unsaved);
  const renew = () => setCommandId(crypto.randomUUID());
  function update(rowId: string, key: string, value: Value) {
    setRows(current => current.map(row => row.clientRowId === rowId ? {
      ...row, [key]: value,
      ...(attendance && key !== 'effectiveFrom' && String(row.effectiveFrom ?? '') < today! ? { effectiveFrom: today } : {}),
    } : row));
    setErrors(current => current.filter(item => item.clientRowId !== rowId || item.field !== key));
    renew();
  }
  function add() {
    const common = { clientRowId: crypto.randomUUID(), operation: 'CREATE' as const, code: '', name: '' };
    const row: Row = attendance
      ? { ...common, systemKind: null, effectiveFrom: today, unit: 'DAY', payKind: 'UNSPECIFIED', deductionRule: 'NONE', recognitionRule: 'NOT_SUPPORTED', enabled: false, basis: '' }
      : { ...common, description: '', sortOrder: 0, active: true, ...(detail ? { groupId: context.groupId } : {}) };
    setRows(current => [...current, row]); renew();
    setTimeout(() => sheet.current?.focusCell(row.clientRowId, 'code'), 0);
  }
  function remove() {
    setRows(current => current.filter(row => row.id || !selected.has(row.clientRowId)).map(row => {
      const original = row.id ? latest.get(row.id) : undefined;
      if (!selected.has(row.clientRowId) || !row.id || original?.systemOwned) return row;
      return { ...row, operation: 'DEACTIVATE', ...(attendance ? { enabled: false, effectiveFrom: String(row.effectiveFrom ?? '') < today! ? today : row.effectiveFrom } : {}) };
    }));
    setSelected(new Set()); renew();
  }
  function cancel() {
    setRows(model.latestRows.map(entry => ({ ...entry, clientRowId: model.rows.find(row => row.id === entry.id)?.clientRowId ?? entry.id, expectedVersion: entry.version, operation: 'UPDATE' })));
    setReason(''); setErrors([]); setSelected(new Set()); renew();
  }
  function confirmLatest() {
    setRows(current => current.map(row => row.id && latest.has(row.id) ? { ...row, expectedVersion: latest.get(row.id)!.version } : row));
    setErrors([]); renew();
  }
  const payload = dirty.map(row => {
    const values: Record<string, Value> = { clientRowId: row.clientRowId, id: row.id || undefined, expectedVersion: row.id ? row.expectedVersion : undefined, operation: row.operation };
    fields.forEach(field => { values[field.key] = row[field.key]; });
    if (attendance) values.systemKind = row.systemKind ?? null;
    if (detail) values.groupId = row.groupId ?? context.groupId;
    return values;
  });
  function submit(event: FormEvent) {
    if (!dirty.length || dirty.length > 50 || conflicts.length || lockedGroup) {
      event.preventDefault();
      return;
    }
    if (sheet.current && !sheet.current.validate()) {
      event.preventDefault();
      return;
    }
    if (!confirming) {
      event.preventDefault();
      setConfirming(true);
      return;
    }
    const anotherDraft = Array.from(document.querySelectorAll('[data-unsaved-changes="true"]')).some(element => element !== sheetElement.current);
    if (anotherDraft && !window.confirm('다른 시트에도 저장하지 않은 변경이 있습니다. 다른 시트의 편집을 버리고 이 시트를 저장할까요?')) event.preventDefault();
  }
  function changeReason(value: string) {
    setReason(value);
    setErrors(current => current.filter(item => item.field !== 'reason'));
    renew();
  }
  function display(value: Value, field: Field) {
    if (field.kind === 'check') return value ? '사용' : '미사용';
    return field.choices?.find(([key]) => key === value)?.[1] ?? String(value ?? '');
  }
  function editorField(row: Row, index: number, field: Field): SheetEditorField {
    const original = row.id ? latest.get(row.id) : undefined;
    const rowError = errors.find(item => item.clientRowId === row.clientRowId && item.field === field.key);
    const label = !attendance || field.key === 'code' || field.key === 'name' ? `${title} ${field.label} ${index + 1}` : `${field.label} ${index + 1}`;
    const systemFixed = attendance && Boolean(row.systemKind) && ['unit', 'deductionRule', 'recognitionRule'].includes(field.key);
    const customFixed = attendance && !row.systemKind && ['enabled', 'deductionRule', 'recognitionRule'].includes(field.key);
    const disabled = lockedGroup || original?.systemOwned === true || systemFixed || customFixed || (row.operation === 'DEACTIVATE' && field.key !== 'effectiveFrom');
    return {
      label, value: String(row[field.key] ?? ''), disabled,
      readOnly: field.key === 'code' && Boolean(row.id),
      type: field.kind === 'number' ? 'number' : field.kind === 'date' ? 'date' : 'text',
      options: field.choices, maxLength: field.max, required: field.required,
      min: field.kind === 'date' ? today : field.kind === 'number' ? 0 : undefined,
      step: field.kind === 'number' ? 1 : undefined,
      changed: !original || row[field.key] !== original[field.key], error: rowError?.message,
      onChange: value => update(row.clientRowId, field.key, field.kind === 'number' ? (value === '' ? '' : Number(value)) : value),
      onCancel: () => {
        setRows(current => current.map(item => item.clientRowId === row.clientRowId ? row : item));
        renew();
      },
    };
  }
  function checkCell(row: Row, index: number, field: Field) {
    const state = editorField(row, index, field);
    return <input className="t-sheet-check" type="checkbox" aria-label={state.label}
      disabled={state.disabled} checked={Boolean(row[field.key])} data-changed={String(state.changed)}
      aria-invalid={Boolean(state.error)} onChange={event => update(row.clientRowId, field.key, event.target.checked)} />;
  }
  function protection(row: Row) {
    const original = row.id ? latest.get(row.id) : undefined;
    return attendance ? <><strong>{systemText(row.systemKind)}</strong><small className="t-code-help">{original?.supported && row.enabled ? '지원 정책 · 이 버전 신청 허용' : !row.systemKind ? '계산·차감 계약 미지원 · 신청 불가' : '이 버전 신규 신청 미사용'}</small><small className="t-code-help">{original?.capabilityMessage}</small><small className="t-code-help">{original ? `최신 등록 v${original.revision}` : '초안'}</small></>
      : <>{original?.systemOwned ? <span className="t-tag gray">시스템 보호</span> : '사용자 관리'}{!detail && row.id && <a href={href({ ...context, groupId: row.id, codePage: 0 })}>상세코드 보기</a>}</>;
  }
  const columns = [
    sheetStateColumn<Row>(row => sheetStateOf(row, changed(row))),
    ...fields.map(field => {
      const column: ColDef<Row> = {
      colId: field.key, headerName: field.label,
      pinned: field.key === 'code' ? 'left' : undefined,
      minWidth: field.kind === 'check' || field.kind === 'number' ? 110 : field.key === 'description' || field.key === 'basis' ? 240 : 160,
      flex: field.kind === 'check' || field.kind === 'number' ? 0 : 1,
      width: field.kind === 'check' || field.kind === 'number' ? 110 : undefined,
      filter: field.kind === 'number' ? 'agNumberColumnFilter' : 'agTextColumnFilter',
      cellClass: field.kind === 'number' ? 'w-sheet-number' : field.kind === 'check' ? 'w-sheet-center' : undefined,
      valueGetter: ({ data }) => data ? field.kind === 'number' ? data[field.key] : display(data[field.key], field) : '',
      };
      return field.kind === 'check'
        ? sheetColumn<Row>(column, (row, index) => checkCell(row, index, field))
        : sheetEditorColumn<Row>(column, (row, index) => editorField(row, index, field));
    }),
    sheetColumn<Row>({ colId: 'protection', headerName: attendance ? '유형·신청 가능' : '보호·상세', minWidth: attendance ? 280 : 180,
      valueGetter: ({ data }) => data ? attendance ? `${systemText(data.systemKind)} ${data.id ? latest.get(data.id)?.capabilityMessage ?? '' : ''}` : data.id && latest.get(data.id)?.systemOwned ? '시스템 보호' : '사용자 관리' : '',
    }, row => protection(row)),
  ];
  const fieldErrors = errors.some(item => fields.some(field => field.key === item.field));
  return <div className="t-org-sheet" ref={sheetElement} data-unsaved-changes={String(unsaved)}>
    <Form id={formId} action={action} onSubmit={submit} className="t-sheet-form">
      <input type="hidden" name="commandId" value={commandId} />
      <input type="hidden" name="rows" value={JSON.stringify(payload)} />
      {Object.entries(context).map(([name, value]) => value == null ? null : <input key={name} type="hidden" name={name} value={value} />)}
      {lockedGroup && <p className="notice">{group ? '시스템 소유 그룹의 상세코드는 편집할 수 없습니다.' : '코드그룹을 먼저 조회·선택하세요.'}</p>}
      {!reasonError && (error || errors.length > 0) && <div className="notice error" role="alert" tabIndex={-1} data-error-focus={fieldErrors ? undefined : 'true'}>
        <p>{error ?? '입력을 확인하세요. 편집 내용은 보존되어 있습니다.'}</p>
        {errors.filter(item => !fields.some(field => field.key === item.field)).map((item, index) => <p key={index}>{item.message}</p>)}
      </div>}
      <WorklogSheet ref={sheet} label={`${title} 편집 시트`} title={heading ?? title} rows={rows} columns={columns} getRowId={row => row.clientRowId}
        selectedIds={selected} onSelectionChange={setSelected}
        selectionLabel={(_row, index) => `${title} ${index + 1}행 선택`}
        isRowSelectable={row => !lockedGroup && !(row.id && latest.get(row.id)?.systemOwned)}
        changedIds={new Set(dirty.map(row => row.clientRowId))} errors={errors}
        emptyMessage={`등록된 ${title}가 없습니다. 행 추가 버튼으로 추가하세요.`}
        actions={<div className="w-sheet-tools" role="group" aria-label={`${title} 시트 편집`}>
          <button type="button" className="t-btn" onClick={add} disabled={dirty.length >= 50 || lockedGroup}>행 추가</button>
          <button type="button" className="t-btn" onClick={remove} disabled={!selected.size || lockedGroup} title="미저장 신규 행은 제거하고 저장된 행은 비활성 예정으로 표시합니다.">비활성화</button>
          <span className="w-sheet-divider" aria-hidden="true" />
          <button type="button" className="t-btn" onClick={cancel} disabled={!unsaved}>변경 취소</button>
          <button type="submit" className="t-btn primary" disabled={!dirty.length || dirty.length > 50 || Boolean(conflicts.length) || lockedGroup}>저장</button>
        </div>}
        pagination={pagination} />
      {conflicts.length > 0 && <section className="t-sheet-conflict" aria-label={`${title} 충돌 최신값 비교`}>
        <h3>최신값을 확인하세요</h3>
        {conflicts.map(row => {
          const current = latest.get(row.id!)!;
          return <div key={row.clientRowId}><p>최신값: <strong>{current.name}</strong> · v{current.version}</p><p>편집값: <strong>{row.name}</strong></p>
            <dl>{fields.map(field => <div key={field.key}><dt>{field.label}</dt><dd>{display(current[field.key], field)} → {display(row[field.key], field)}</dd></div>)}</dl>
          </div>;
        })}
        <button type="button" className="t-btn" onClick={confirmLatest}>최신값 확인 후 편집 유지</button>
      </section>}
    </Form>
    {confirming && <SheetSaveDialog formId={formId} label={`${title} 일괄 변경 사유`} reason={reason} error={reasonError}
      counts={sheetChangeCounts(dirty)} busy={busy} onReasonChange={changeReason} onClose={() => setConfirming(false)} />}
  </div>;
}
function Pagination({ title, page, hasNext, values, path }: { title: string; page: number; hasNext: boolean; values: Record<string, string | number | null>; path?: string }) {
  return <nav className="actions pagination" aria-label={`${title} 페이지`}><span>{page + 1}페이지</span>
    {page > 0 && <a href={href({ ...values, [title === '상세코드' ? 'codePage' : 'page']: page - 1 }, path)}>이전</a>}
    {hasNext && <a href={href({ ...values, [title === '상세코드' ? 'codePage' : 'page']: page + 1 }, path)}>다음</a>}
  </nav>;
}
function CommonCodes({ model: m, error, busy }: { model: CommonModel; error?: string | null; busy?: boolean }) {
  const context = { q: m.q, page: m.page, groupId: m.groupId, codeQ: m.codeQ, codePage: m.codePage };
  const selected = m.selectedGroup ?? m.groups.find(group => group.id === m.groupId) ?? null;
  return <section className="t-code-management">
    <PageHeader title="공통코드 관리" help={<>
      <p>회사별 표시·분류 코드를 관리하세요. 권한·신청 상태·계산 규칙은 변경되지 않습니다.</p>
      <p>한 번에 최대 50행을 저장합니다. 기존 행 삭제는 이력을 보존하는 비활성 처리입니다.</p>
    </>} actions={<a className="t-btn" href="/admin/attendance-types">근태코드 관리</a>} />
    <section className="wl-section" role="region" aria-label="코드그룹 관리">
      <form action="/admin/common-codes" method="get" className="wl-querybar" role="search" aria-label="코드그룹 검색"><label htmlFor="code-group-q">코드그룹 검색</label><input id="code-group-q" className="t-input" name="q" type="search" maxLength={120} defaultValue={m.q} placeholder="코드 또는 이름" /><div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div></form>
      <CodeSheet title="코드그룹" action="/admin/common-code-groups/bulk" model={m.groupSheet} fields={commonFields} context={context} error={m.groupSheet.errors.length ? error : undefined} busy={busy}
        pagination={<Pagination title="코드그룹" page={m.page} hasNext={m.hasNext} values={context} />} />
    </section>
    <section className="wl-section" role="region" aria-label="상세코드 관리">
      <form action="/admin/common-codes" method="get" className="wl-querybar" role="search" aria-label="상세코드 검색">
        <input type="hidden" name="q" value={m.q} /><input type="hidden" name="page" value={m.page} />
        <label htmlFor="code-group-choice">코드그룹 선택</label><select id="code-group-choice" className="t-select" name="groupId" defaultValue={m.groupId ?? ''}><option value="">그룹 선택</option>{selected && !m.groups.some(group => group.id === selected.id) && <option value={selected.id}>{selected.name}</option>}{m.groups.map(group => <option key={group.id} value={group.id}>{group.code} · {group.name}{!group.active && ' (그룹 비활성)'}</option>)}</select>
        <label htmlFor="common-code-q">상세코드 검색</label><input id="common-code-q" className="t-input" name="codeQ" type="search" maxLength={120} defaultValue={m.codeQ} placeholder="코드 또는 이름" /><div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div>
      </form>
      {selected?.active === false && <p className="notice">비활성 그룹입니다. 상세코드와 과거 참조는 보존됩니다.</p>}
      <CodeSheet title="상세코드" heading={selected ? `상세코드 · ${selected.name}` : '상세코드'} action="/admin/common-codes/bulk" model={m.codeSheet} fields={commonFields} context={context} group={selected} error={m.codeSheet.errors.length ? error : undefined} busy={busy}
        pagination={<Pagination title="상세코드" page={m.codePage} hasNext={m.codeHasNext} values={context} />} />
    </section>
    {error && !m.groupSheet.errors.length && !m.codeSheet.errors.length && <p className="notice error" role="alert" tabIndex={-1} data-error-focus="true">{error}</p>}
  </section>;
}
function AttendanceTypes({ model: m, error, busy }: { model: TypeModel; error?: string | null; busy?: boolean }) {
  function period(version: TypeVersion) {
    const sameType = m.versions.filter(item => item.typeId === version.typeId);
    const nextDate = sameType.map(item => item.effectiveFrom).filter(date => date > version.effectiveFrom).sort()[0];
    const latestRevision = Math.max(...sameType.filter(item => item.effectiveFrom === version.effectiveFrom).map(item => item.revision));
    return latestRevision > version.revision ? `동일 적용일의 v${latestRevision}로 대체` : nextDate ? `${fmtDate(nextDate)} 전까지` : '다음 적용일 전까지 · 현재 종료 미정';
  }
  const versions = m.versions.filter(version => m.sheet.latestRows.some(type => type.id === version.typeId));
  const versionColumns: ColDef<TypeVersion>[] = [
    sheetColumn<TypeVersion>({ colId: 'code', headerName: '코드·이름', minWidth: 180, pinned: 'left', valueGetter: ({ data }) => data ? `${data.code} ${data.name}` : '' },
      version => <>{version.code}<small className="t-code-help">{version.name}</small></>),
    sheetColumn<TypeVersion>({ field: 'revision', headerName: '버전', minWidth: 100, width: 100, flex: 0, cellClass: 'w-sheet-number', filter: 'agNumberColumnFilter' }, version => `v${version.revision}`),
    sheetColumn<TypeVersion>({ colId: 'effectiveFrom', headerName: '적용 기간', minWidth: 240, valueGetter: ({ data }) => data ? `${fmtDate(data.effectiveFrom)}부터 ${period(data)}` : '' },
      version => <>{fmtDate(version.effectiveFrom)}부터<small className="t-code-help">{period(version)}</small></>),
    { colId: 'enabled', headerName: '신청', minWidth: 100, valueGetter: ({ data }: { data: TypeVersion | undefined }) => data?.enabled ? '사용' : '미사용' },
    { colId: 'unit', headerName: '단위·유급', minWidth: 180, valueGetter: ({ data }: { data: TypeVersion | undefined }) => data ? `${typeFields[3].choices?.find(([value]) => value === data.unit)?.[1]} · ${typeFields[4].choices?.find(([value]) => value === data.payKind)?.[1]}` : '' },
    sheetColumn<TypeVersion>({ colId: 'deductionRule', headerName: '차감·인정', minWidth: 190, valueGetter: ({ data }) => data ? `${data.deductionRule === 'LEAVE_DAY' ? '승인 시 일수 차감' : '차감 없음'} 근무 인정 산정 미지원` : '' },
      version => <>{version.deductionRule === 'LEAVE_DAY' ? '승인 시 일수 차감' : '차감 없음'}<small className="t-code-help">근무 인정 산정 미지원</small></>),
    { field: 'basis', headerName: '정책 근거', minWidth: 220 },
    sheetColumn<TypeVersion>({ colId: 'createdAt', headerName: '변경 사유·등록자·시각 (서울)', minWidth: 250, valueGetter: ({ data }) => data ? `${data.reason} ${data.createdByName ?? '시스템 기초'} ${fmtDateTime(data.createdAt)}` : '' },
      version => <>{version.reason}<small className="t-code-help">{version.createdByName ?? '시스템 기초'}</small><small className="t-code-help">{fmtDateTime(version.createdAt)}</small></>),
  ];
  return <section className="t-code-management">
    <PageHeader title="근태코드 관리" help={<>
      <p>신청 정책을 적용일별 버전으로 관리하세요. 기존 대기·완료 신청은 제출 당시 정책을 유지합니다.</p>
      <p>한 번에 최대 50행을 저장합니다. 기존 행 삭제는 이력을 보존하는 비활성 처리입니다. 적용일 이후의 신규 신청에만 반영됩니다.</p>
      <p>유급 구분은 정책 메타데이터입니다. 급여·법정 계산이나 근무 인정시간을 산정하지 않습니다. 같은 적용일의 수정도 새 버전이며 미래 적용일을 예약할 수 있습니다.</p>
      <p>편집 시트는 최신 등록 정책(예약 포함)입니다. 대상일의 적용 정책은 이력의 적용일·버전으로 결정됩니다. 비활성 버전을 추가해도 이후 예약 정책은 보존되어 다시 사용될 수 있습니다.</p>
    </>} actions={<a className="t-btn" href="/admin/common-codes">공통코드 관리</a>} />
    <section className="wl-section">
      <form action="/admin/attendance-types" method="get" className="wl-querybar" role="search" aria-label="근태코드 검색"><label htmlFor="attendance-type-q">근태코드 검색</label><input id="attendance-type-q" className="t-input" type="search" name="q" maxLength={120} defaultValue={m.q} placeholder="코드 또는 이름" /><div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div></form>
      <p className="notice">신규 근태코드는 영문 대문자와 숫자 1~8자로 입력하며 초안으로 저장됩니다.</p>
      <CodeSheet title="근태코드" action="/admin/attendance-types/bulk" model={m.sheet} fields={typeFields} context={{ q: m.q, page: m.page }} today={m.today} error={error} busy={busy}
        pagination={<Pagination title="근태코드" page={m.page} hasNext={m.hasNext} values={{ q: m.q }} path="/admin/attendance-types" />} />
    </section>
    <section className="wl-section t-code-versions">
      <WorklogSheet label="근태코드 버전 이력" title="근태코드 버전 이력" rows={versions} columns={versionColumns} getRowId={version => version.id}
        emptyMessage="등록된 버전이 없습니다." />
      <p className="t-subtle">조회된 코드의 불변 정책입니다. 대상일 이하 가장 늦은 적용일에서 가장 높은 버전을 사용합니다.</p>
    </section>
  </section>;
}
export function CodePages({ page, data, busy }: PageProps) {
  if (page === 'org/common-codes') return <CommonCodes model={data.commonCodes as CommonModel} error={data.error as string | undefined} busy={busy} />;
  if (page === 'org/attendance-types') return <AttendanceTypes model={data.attendanceTypes as TypeModel} error={data.error as string | undefined} busy={busy} />;
  return null;
}
