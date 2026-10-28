import { useEffect, useMemo, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import type { PageProps } from '../types';
import { ErrorNotice, Field, Form, SelectField, TextAreaField, fmtDate, fmtDateTime } from '../ui';
import type { Employee, Organization } from './AdminTypes';
import { WorklogSheet, sheetColumn, sheetEditorColumn } from '../components/WorklogSheet';
import type { SheetEditorField, WorklogSheetHandle } from '../components/WorklogSheet';

type Operation = 'CREATE' | 'UPDATE' | 'DEACTIVATE';
interface RowBase { clientRowId: string; id?: string | null; expectedVersion?: number | null; operation: Operation; active: boolean }
interface OrganizationRow extends RowBase { code: string; name: string; parentId: string | null }
interface EmployeeRow extends RowBase { employeeNo: string; displayName: string; joinedOn: string; leftOn: string | null }
type Row = OrganizationRow | EmployeeRow;
interface RowError { clientRowId: string; field: string; message: string }
interface SheetModel { commandId: string; reason: string; rows: Row[]; latestRows: (Employee | Organization)[]; errors: RowError[] }
interface SheetPageModel {
  organizationSheet?: SheetModel; employeeSheet?: SheetModel;
  options: Organization[]; q: string; page: number; hasNext: boolean;
  organizationId?: string | null; status?: string;
  employeeOrganizations?: Record<string, string>; error?: string | null;
  organizationHeadsById?: Record<string, { employeeName: string; eligible: boolean; approverEligible: boolean }>;
  employeeContext?: Record<string, { organizationName: string | null; organizationHeadName: string | null; organizationHeadEligible: boolean; organizationHeadApproverEligible: boolean; scheduleName: string | null }>;
}
const fields = (organization: boolean) => organization
  ? ['code', 'name', 'parentId', 'active'] as const
  : ['employeeNo', 'displayName', 'joinedOn', 'leftOn', 'active'] as const;
function businessValue(row: Row | Employee | Organization, field: string) {
  return (row as unknown as Record<string, unknown>)[field] ?? '';
}
function changed(row: Row, latest: Employee | Organization | undefined, organization: boolean) {
  if (!row.id || row.operation === 'CREATE') return true;
  if (!latest) return true;
  if (row.operation === 'DEACTIVATE') return latest.active;
  return fields(organization).some(field => businessValue(row, field) !== businessValue(latest, field));
}
function serialize(row: Row, organization: boolean) {
  const result: Record<string, unknown> = {
    clientRowId: row.clientRowId, id: row.id || undefined,
    expectedVersion: row.id ? row.expectedVersion : undefined, operation: row.operation,
  };
  for (const field of fields(organization)) result[field] = (row as unknown as Record<string, unknown>)[field];
  return result;
}
function queryHref(path: string, values: Record<string, string | number | null | undefined>) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) if (value != null) params.set(key, String(value));
  return `${path}?${params}`;
}
function SheetPage({ model: m, organization }: { model: SheetPageModel; organization: boolean }) {
  const model = (organization ? m.organizationSheet : m.employeeSheet)!;
  const [rows, setRows] = useState(model.rows);
  const [reason, setReason] = useState(model.reason);
  const [commandId, setCommandId] = useState(model.commandId);
  const [selected, setSelected] = useState(new Set<string>());
  const [errors, setErrors] = useState(model.errors ?? []);
  const sheet = useRef<WorklogSheetHandle>(null);
  const latest = useMemo(() => new Map(model.latestRows.map(row => [row.id, row])), [model.latestRows]);
  const dirty = rows.filter(row => changed(row, row.id ? latest.get(row.id) : undefined, organization));
  const conflicts = dirty.filter(row => row.id && latest.has(row.id) && row.expectedVersion !== latest.get(row.id)!.version);
  const unsaved = dirty.length > 0 || reason.length > 0;
  const path = organization ? '/admin/organizations' : '/admin/employees';
  const title = organization ? '조직 관리' : '사원 관리';
  const label = organization ? '조직' : '사원';
  const renew = () => setCommandId(crypto.randomUUID());
  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (unsaved) { event.preventDefault(); event.returnValue = ''; }
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [unsaved]);
  function update(clientRowId: string, field: string, value: string | boolean | null) {
    setRows(current => current.map(row => row.clientRowId === clientRowId ? { ...row, [field]: value } : row));
    setErrors(current => current.filter(error => error.clientRowId !== clientRowId || error.field !== field));
    renew();
  }
  function add() {
    const base = { clientRowId: crypto.randomUUID(), operation: 'CREATE' as const, active: true };
    setRows(current => [...current, organization
      ? { ...base, code: '', name: '', parentId: null }
      : { ...base, employeeNo: '', displayName: '', joinedOn: '', leftOn: null }]);
    renew();
    setTimeout(() => sheet.current?.focusCell(base.clientRowId, organization ? 'code' : 'employeeNo'), 0);
  }
  function remove() {
    setRows(current => current.filter(row => row.id || !selected.has(row.clientRowId)).map(row =>
      selected.has(row.clientRowId) && row.id && latest.get(row.id)?.active ? { ...row, operation: 'DEACTIVATE' } : row));
    setSelected(new Set()); renew();
  }
  function cancel() {
    setRows(model.latestRows.map((row) => ({ ...row,
      clientRowId: model.rows.find(item => item.id === row.id)?.clientRowId ?? row.id,
      expectedVersion: row.version, operation: 'UPDATE',
    })));
    setReason(''); setSelected(new Set()); setErrors([]); renew();
  }
  function confirmLatest() {
    setRows(current => current.map(row => row.id && latest.has(row.id) ? { ...row, expectedVersion: latest.get(row.id)!.version } : row));
    setErrors([]); renew();
  }
  function input(row: Row, index: number, field: string, caption: string, options: { type?: 'text' | 'date'; fixed?: boolean; maxLength?: number; required?: boolean } = {}): SheetEditorField {
    const previous = row.id ? latest.get(row.id) : undefined;
    const error = errors.find(item => item.clientRowId === row.clientRowId && item.field === field);
    return {
      label: `${caption} ${index + 1}`, value: String(businessValue(row, field)), type: options.type,
      readOnly: Boolean(options.fixed && row.id), disabled: row.operation === 'DEACTIVATE',
      maxLength: options.maxLength, required: options.required, error: error?.message,
      changed: !previous || businessValue(row, field) !== businessValue(previous, field),
      onChange: value => update(row.clientRowId, field, value || (field === 'leftOn' ? null : '')),
      onCancel: () => { setRows(current => current.map(item => item.clientRowId === row.clientRowId ? row : item)); renew(); },
    };
  }
  function submit(event: FormEvent) {
    if (!sheet.current?.validate()) { event.preventDefault(); return; }
    if (dirty.length < 1 || dirty.length > 50 || conflicts.length) event.preventDefault();
  }
  function headCell(row: Row) {
    if (!row.id) return '저장 후 배정';
    const head = m.organizationHeadsById?.[row.id];
    return <><HeadStatus name={head?.employeeName ?? null} eligible={head?.eligible ?? false} approverEligible={head?.approverEligible ?? false} /><a href={queryHref('/admin/organization-heads', { organizationId: row.id })}>조직장 배정</a></>;
  }
  function employeeCell(row: Row, previous: Employee | Organization | undefined) {
    if (!row.id) return '저장 후 배정';
    const context = m.employeeContext?.[row.id];
    const name = businessValue(previous ?? row, 'displayName');
    return <div className="t-sheet-links">
      <span>{context?.organizationName ?? m.employeeOrganizations?.[row.id] ?? '소속 미배정'}</span>
      <span>조직장: {context?.organizationHeadName ? `${context.organizationHeadName}${context.organizationHeadEligible ? '' : ' · 재지정 필요'}` : '미배정'}</span>
      <span>근무: {context?.scheduleName ?? '미배정'}</span>
      <a href={`/admin/employees/${row.id}`} aria-label={`${name} 소속·상세`}>소속·상세</a>
      <a href={`/admin/employees/${row.id}/schedule`} aria-label={`${name} 근무 배정`}>근무 배정</a>
      <a href={`/admin/employees/${row.id}/access`}>계정·권한</a>
    </div>;
  }
  const values = { q: m.q, organizationId: organization ? null : m.organizationId, status: organization ? null : m.status };
  const columns = [
    sheetEditorColumn<Row>({ colId: organization ? 'code' : 'employeeNo', headerName: organization ? '조직 코드' : '사번', minWidth: 150, pinned: 'left' },
      (row, index) => input(row, index, organization ? 'code' : 'employeeNo', organization ? '조직 코드' : '사번', { fixed: true, maxLength: 40, required: true })),
    sheetEditorColumn<Row>({ colId: organization ? 'name' : 'displayName', headerName: organization ? '조직 이름' : '이름', minWidth: 180 },
      (row, index) => input(row, index, organization ? 'name' : 'displayName', organization ? '조직 이름' : '이름', { maxLength: 120, required: true })),
    ...(organization ? [
      sheetEditorColumn<Row>({ colId: 'parentId', headerName: '상위 조직', minWidth: 180 }, (row, index) => ({
        ...input(row, index, 'parentId', '상위 조직'),
        options: [['', '없음'], ...m.options.filter(option => option.id !== row.id && (option.active || option.id === businessValue(row, 'parentId'))).map(option => [option.id, `${option.name}${option.active ? '' : ' (비활성)'}`] as const)],
        onChange: value => update(row.clientRowId, 'parentId', value || null),
      })),
      sheetColumn<Row>({ colId: 'organizationHead', headerName: '조직장', minWidth: 190, valueGetter: ({ data }) => data?.id ? m.organizationHeadsById?.[data.id]?.employeeName ?? '' : '' }, row => headCell(row)),
    ] : [
      sheetEditorColumn<Row>({ colId: 'joinedOn', headerName: '입사일', minWidth: 160 }, (row, index) => input(row, index, 'joinedOn', '입사일', { type: 'date', required: true })),
      sheetEditorColumn<Row>({ colId: 'leftOn', headerName: '마지막 재직일', minWidth: 160 }, (row, index) => input(row, index, 'leftOn', '마지막 재직일', { type: 'date' })),
      sheetColumn<Row>({ colId: 'assignments', headerName: '현재 소속·배정', minWidth: 270, flex: 1, valueGetter: ({ data }) => data?.id ? m.employeeContext?.[data.id]?.organizationName ?? m.employeeOrganizations?.[data.id] ?? '' : '' }, row => employeeCell(row, row.id ? latest.get(row.id) : undefined)),
    ]),
    sheetColumn<Row>({ colId: 'active', field: 'active', headerName: '활성', minWidth: 90, maxWidth: 110, cellClass: 'w-sheet-center' }, (row, index) => <input className="t-sheet-check" type="checkbox" aria-label={`활성 ${index + 1}`} checked={row.active} disabled={row.operation === 'DEACTIVATE'} data-changed={String(!row.id || row.active !== latest.get(row.id)?.active)} onChange={event => update(row.clientRowId, 'active', event.target.checked)} />),
    sheetColumn<Row>({ colId: 'state', headerName: '변경 상태', minWidth: 120, valueGetter: ({ data }) => data ? data.operation === 'DEACTIVATE' ? '비활성 예정' : data.operation === 'CREATE' ? '신규' : changed(data, data.id ? latest.get(data.id) : undefined, organization) ? '수정' : '저장됨' : '' }, row => {
      const modified = changed(row, row.id ? latest.get(row.id) : undefined, organization);
      return <span className={`t-tag ${row.operation === 'DEACTIVATE' ? 'amber' : modified ? 'blue' : 'gray'}`}>{row.operation === 'DEACTIVATE' ? '비활성 예정' : row.operation === 'CREATE' ? '신규' : modified ? '수정' : '저장됨'}</span>;
    }),
  ];
  return <section className="t-org-sheet" data-unsaved-changes={String(unsaved)}>
    <header className="t-pagehead"><div><p className="t-eyebrow">인사 관리</p><h1>{title}</h1><p className="t-muted">여러 행을 편집하고 한 번에 저장하세요.</p></div><a className="t-btn" href="/company/organizations">조직도</a></header>
    <section className="t-card">
      <form action={path} method="get" className="t-toolbar" role="search" aria-label={`${label} 검색`}>
        <label htmlFor="sheet-q">{label} 검색</label><input id="sheet-q" className="t-input" name="q" type="search" defaultValue={m.q} maxLength={120} placeholder={organization ? '코드 또는 이름' : '사번 또는 이름'} />
        {!organization && <><label className="t-sr-only" htmlFor="sheet-org">현재 소속</label><select id="sheet-org" className="t-select" name="organizationId" defaultValue={m.organizationId ?? ''}><option value="">전체 소속</option>{m.options.map(option => <option key={option.id} value={option.id}>{option.name}</option>)}</select><label className="t-sr-only" htmlFor="sheet-status">활성 상태</label><select id="sheet-status" className="t-select" name="status" defaultValue={m.status ?? 'all'}><option value="all">전체 상태</option><option value="active">활성</option><option value="inactive">비활성</option></select></>}
        <button className="t-btn primary" type="submit">조회</button><a className="t-btn" href={path}>전체 보기</a>
      </form>
      <Form action={`${path}/bulk`} onSubmit={submit} className="t-sheet-form">
        <input type="hidden" name="commandId" value={commandId} /><input type="hidden" name="rows" value={JSON.stringify(dirty.map(row => serialize(row, organization)))} />
        <input type="hidden" name="q" value={m.q} /><input type="hidden" name="page" value={m.page} />
        {!organization && <><input type="hidden" name="organizationId" value={m.organizationId ?? ''} /><input type="hidden" name="status" value={m.status ?? 'all'} /></>}
        <div className="t-sheet-toolbar" role="group" aria-label={`${label} 시트 편집`}>
          <button className="t-btn" type="button" onClick={add} disabled={dirty.length >= 50} aria-describedby="sheet-limit">입력</button>
          <button className="t-btn" type="button" onClick={remove} disabled={selected.size === 0} title="미저장 신규 행은 제거하고 저장된 행은 비활성 예정으로 표시합니다.">비활성화</button>
          <button className="t-btn primary" type="submit" disabled={dirty.length < 1 || dirty.length > 50 || conflicts.length > 0} title={conflicts.length ? '최신값을 비교하고 확인하세요.' : dirty.length ? '변경 행을 함께 저장합니다.' : '변경한 행이 없습니다.'}>저장</button>
          <button className="t-btn" type="button" onClick={cancel} disabled={!unsaved}>취소</button>
          <span className="t-tag blue" role="status">변경 {dirty.length}행</span>
        </div>
        <p className="t-subtle" id="sheet-limit">한 번에 최대 50행을 저장합니다. 기존 행 삭제는 이력을 보존하고 비활성화합니다. 저장 전까지 서버 자료는 바뀌지 않습니다.</p>
        {(m.error || errors.length > 0) && <div className="notice error" role="alert" tabIndex={-1} data-error-focus={errors.some(error => ['code','name','employeeNo','displayName','joinedOn','leftOn'].includes(error.field)) ? undefined : 'true'}><p>{m.error ?? '입력을 확인해 주세요. 편집 내용은 보존되어 있습니다.'}</p>{errors.filter(error => !['code','name','employeeNo','displayName','joinedOn','leftOn'].includes(error.field)).map((error, index) => <p key={index}>{error.message}</p>)}</div>}
        <WorklogSheet ref={sheet} label={`${label} 편집 시트`} rows={rows} columns={columns} getRowId={row => row.clientRowId}
          rowHeight={organization ? 64 : 112} selectedIds={selected} onSelectionChange={setSelected}
          selectionLabel={(_row, index) => `${index + 1}행 선택`} changedIds={new Set(dirty.map(row => row.clientRowId))} errors={errors} />
        {rows.length === 0 && <p className="t-muted">조회된 {label}이 없습니다. 입력 버튼으로 새 행을 추가하세요.</p>}
        {conflicts.length > 0 && <section className="t-sheet-conflict" aria-label="충돌 최신값 비교"><h2>다른 변경이 저장되어 있습니다</h2><p className="t-muted">최신값과 현재 편집값을 비교하세요. 확인 전에는 덮어쓰지 않습니다.</p>{conflicts.map(row => <div key={row.clientRowId}><p>최신값: <strong>{String(businessValue(latest.get(row.id!)!, organization ? 'name' : 'displayName'))}</strong> · 버전 {latest.get(row.id!)!.version}</p><p>편집값: <strong>{String(businessValue(row, organization ? 'name' : 'displayName'))}</strong></p><dl>{fields(organization).map(field => <div key={field}><dt>{({ code: '코드', name: '이름', parentId: '상위 조직 ID', employeeNo: '사번', displayName: '이름', joinedOn: '입사일', leftOn: '마지막 재직일', active: '활성' } as Record<string,string>)[field]}</dt><dd>{String(businessValue(latest.get(row.id!)!, field)) || '없음'} → {String(businessValue(row, field)) || '없음'}</dd></div>)}</dl></div>)}<button type="button" className="t-btn" onClick={confirmLatest}>최신값 확인 후 편집 유지</button></section>}
        <div className="t-sheet-reason"><label htmlFor="sheet-reason">일괄 변경 사유</label><textarea id="sheet-reason" name="reason" value={reason} maxLength={500} rows={2} required onChange={event => { setReason(event.target.value); renew(); }} /><p className="t-subtle">변경한 모든 행에 적용됩니다. 500자 이내로 입력하고 불필요한 개인정보는 적지 마세요.</p></div>
      </Form>
      <nav className="actions pagination" aria-label={`${label} 페이지`}><span>{m.page + 1}페이지 · 조회 {model.latestRows.length}행</span>{m.page > 0 && <a href={queryHref(path, { ...values, page: m.page - 1 })}>이전</a>}{m.hasNext && <a href={queryHref(path, { ...values, page: m.page + 1 })}>다음</a>}</nav>
    </section>
  </section>;
}

interface HeadForm { organizationId: string | null; employeeId: string | null; validFrom: string; expectedVersion: number | null; reason: string; commandId: string }
interface OrganizationHeads {
  asOf: string; today: string; organizationId: string | null; organizations: Organization[];
  candidates: { id: string; employeeNo: string; employeeName: string; joinedOn: string; leftOn: string | null }[];
  history: { id: string; organizationId: string; employeeId: string; employeeNo: string; employeeName: string; validFrom: string; validTo: string | null; supersededAt: string | null; reason: string; version: number; eligible: boolean; approverEligible: boolean }[];
  form: HeadForm;
}
interface OrganizationNode extends Organization {
  headEmployeeNo: string | null; headEmployeeName: string | null; headEligible: boolean; headApproverEligible: boolean;
}
interface CompanyOrganizations {
  asOf: string; today: string; organizationId: string | null; q: string; page: number; hasNext: boolean;
  organizations: OrganizationNode[]; selectedOrganization: OrganizationNode | null;
  employees: { id: string; employeeNo: string; employeeName: string; organizationName: string | null }[];
}
function HeadStatus({ name, eligible, approverEligible }: { name: string | null; eligible: boolean; approverEligible: boolean }) {
  if (!name) return <span className="t-subtle">조직장 미배정</span>;
  return <span className="t-head-status"><span>{name}{eligible ? '' : ' · 재지정 필요'}</span>{!approverEligible && <small className="t-subtle">결재 자격 없음</small>}</span>;
}
function HeadCommandFields({ form }: { form: HeadForm }) {
  return <><input type="hidden" name="commandId" value={form.commandId} /><input type="hidden" name="organizationId" value={form.organizationId ?? ''} /><input type="hidden" name="expectedVersion" value={form.expectedVersion ?? ''} /></>;
}
function OrganizationHeadsPage({ model: m, error }: { model: OrganizationHeads; error?: string | null }) {
  const selected = m.organizations.find(organization => organization.id === m.organizationId);
  const missing = m.form.employeeId && !m.candidates.some(employee => employee.id === m.form.employeeId);
  type Head = OrganizationHeads['history'][number];
  const columns = [
    sheetColumn<Head>({ colId: 'employeeName', field: 'employeeName', headerName: '조직장', minWidth: 170, pinned: 'left' }, head => <><strong>{head.employeeName}</strong><small className="t-subtle">{head.employeeNo}</small></>),
    sheetColumn<Head>({ field: 'validFrom', headerName: '시작일 (포함)', minWidth: 145 }, head => fmtDate(head.validFrom)),
    sheetColumn<Head>({ field: 'validTo', headerName: '종료일 (제외)', minWidth: 145 }, head => head.validTo ? fmtDate(head.validTo) : '종료일 없음'),
    sheetColumn<Head>({ colId: 'state', headerName: '상태', minWidth: 190, valueGetter: ({ data: head }) => head ? head.supersededAt ? '교체 이력' : head.validTo && head.validTo <= m.today ? '종료' : head.validFrom > m.today ? '예정' : '현재 배정' : '' }, head => <><span className="t-tag gray">{head.supersededAt ? '교체 이력' : head.validTo && head.validTo <= m.today ? '종료' : head.validFrom > m.today ? '예정' : '현재 배정'}</span>{!head.eligible && <span className="t-tag amber">재지정 필요</span>}{!head.approverEligible && <span className="t-subtle">결재 자격 없음</span>}</>),
    sheetColumn<Head>({ field: 'reason', headerName: '사유', minWidth: 220, flex: 1 }, head => head.reason),
  ];
  return <div className="t-organization-heads">
    <header className="t-pagehead"><div><p className="t-eyebrow">인사 관리</p><h1>조직장 배정</h1><p className="t-muted">조직별 한 명을 지정하고 적용일별 이력을 보존합니다.</p></div><a className="t-btn" href="/company/organizations">조직도</a></header>
    <section className="t-card"><form action="/admin/organization-heads" method="get" className="t-toolbar"><div><SelectField label="배정할 조직" name="organizationId" value={m.organizationId}><option value="">조직을 선택하세요</option>{m.organizations.map(organization => <option key={organization.id} value={organization.id}>{organization.code} · {organization.name}{organization.active ? '' : ' (비활성)'}</option>)}</SelectField></div><button className="t-btn primary" type="submit">조회</button></form><p className="t-subtle">조회 기준 (서울): {fmtDateTime(m.asOf)}</p></section>
    <ErrorNotice message={error} />
    {selected ? <>
      <section className="t-card t-head-history"><h2>{selected.name} 조직장 이력</h2><p className="t-subtle">시작일은 포함하고 종료일은 제외합니다. 당일 교체와 종료 이력도 보존합니다.</p>
        {m.history.length === 0 ? <p className="notice">등록된 조직장이 없습니다.</p> : <WorklogSheet label="조직장 배정 이력" rows={m.history} columns={columns} getRowId={head => head.id} rowHeight={64} scope="all" />}
      </section>
      <div className="t-head-forms"><section className="t-card"><h2>조직장 지정·교체</h2><p className="t-muted">조직장 배정으로 관리·결재 권한이 자동 부여되지 않습니다. 소속과 다른 조직의 사원도 겸직할 수 있습니다.</p><Form action="/admin/organization-heads" className="admin-form"><HeadCommandFields form={m.form} /><SelectField label="새 조직장" name="employeeId" value={m.form.employeeId} required><option value="">사원을 선택하세요</option>{missing && <option value={m.form.employeeId!}>현재 선택값 (재지정 필요)</option>}{m.candidates.map(employee => <option key={employee.id} value={employee.id}>{employee.employeeNo} · {employee.employeeName}</option>)}</SelectField><Field label="적용 시작일 (포함)" name="validFrom" type="date" value={m.form.validFrom} min={m.today} required /><TextAreaField label="지정·교체 사유" name="reason" value={m.form.reason} rows={3} maxLength={500} required /><p className="t-subtle">오늘 이후 날짜만 선택하세요. 기존 대기 신청의 결재자는 자동 변경되지 않습니다.</p><button className="t-btn primary" disabled={!selected.active || m.candidates.length === 0} title={!selected.active ? '비활성 조직에는 배정할 수 없습니다.' : m.candidates.length === 0 ? '배정 가능한 재직 사원이 없습니다.' : undefined}>조직장 저장</button></Form></section>
      <section className="t-card"><h2>조직장 배정 종료</h2><p className="t-muted">해당 적용일부터 조직장을 비워 둡니다. 이전 배정과 신청 이력은 보존합니다.</p><Form action="/admin/organization-heads/end" className="admin-form"><HeadCommandFields form={m.form} /><Field label="종료 적용일 (제외)" name="validFrom" type="date" value={m.form.validFrom} min={m.today} required /><TextAreaField label="종료 사유" name="reason" value={m.form.reason} rows={3} maxLength={500} required /><button className="t-btn" disabled={m.history.every(head => head.supersededAt || (head.validTo && head.validTo <= m.today))} title="열려 있는 배정이 있을 때 종료할 수 있습니다.">배정 종료</button></Form></section></div>
    </> : <section className="t-card"><p className="notice">조직을 선택해 조직장 배정과 이력을 확인하세요.</p></section>}
  </div>;
}
function CompanyOrganizationsPage({ model: m }: { model: CompanyOrganizations }) {
  const ids = new Set(m.organizations.map(organization => organization.id));
  type CompanyEmployee = CompanyOrganizations['employees'][number];
  const columns = [
    sheetColumn<CompanyEmployee>({ field: 'employeeNo', headerName: '사번', minWidth: 140, pinned: 'left' }, employee => employee.employeeNo),
    sheetColumn<CompanyEmployee>({ field: 'employeeName', headerName: '이름', minWidth: 150 }, employee => employee.employeeName),
    sheetColumn<CompanyEmployee>({ field: 'organizationName', headerName: '현재 소속', minWidth: 180, flex: 1 }, employee => employee.organizationName ?? '미배정'),
  ];
  const children = (parentId: string | null) => m.organizations.filter(organization => parentId === null ? !organization.parentId || !ids.has(organization.parentId) : organization.parentId === parentId);
  function node(organization: OrganizationNode): ReactNode {
    const nested = children(organization.id);
    const summary = <><a href={queryHref('/company/organizations', { organizationId: organization.id, q: m.q, page: 0 })} aria-current={organization.id === m.organizationId ? 'true' : undefined}>{organization.code} · {organization.name}</a>{!organization.active && <span className="t-tag gray">비활성</span>}<HeadStatus name={organization.headEmployeeName} eligible={organization.headEligible} approverEligible={organization.headApproverEligible} /></>;
    return <li key={organization.id}>{nested.length ? <details open><summary>{summary}</summary><ul>{nested.map(node)}</ul></details> : <div className="t-org-leaf">{summary}</div>}</li>;
  }
  return <div className="t-company-organizations"><header className="t-pagehead"><div><p className="t-eyebrow">회사 현황</p><h1>조직도</h1><p className="t-muted">현재 조직과 조직장을 확인하고 조직별 직원을 조회하세요.</p></div></header><p className="t-subtle">조회 기준 (서울): {fmtDateTime(m.asOf)} · 유효 소속일 {fmtDate(m.today)}</p><div className="t-org-layout"><section className="t-card"><h2>조직 계층</h2><p className="t-subtle">전체 조직 구조입니다. 화살표로 하위 조직을 접고 펼칠 수 있습니다.</p><nav aria-label="조직 계층" className="t-org-tree">{m.organizations.length ? <ul>{children(null).map(node)}</ul> : <p className="notice">등록된 조직이 없습니다.</p>}</nav></section><section className="t-card"><h2>{m.selectedOrganization ? `${m.selectedOrganization.name} 직원` : '조직별 직원'}</h2>{m.selectedOrganization ? <><div className="t-org-selected-head"><span className="t-subtle">현재 조직장</span><HeadStatus name={m.selectedOrganization.headEmployeeName} eligible={m.selectedOrganization.headEligible} approverEligible={m.selectedOrganization.headApproverEligible} /></div><p className="t-subtle">선택 조직의 오늘 유효 소속 · 검색 결과의 현재 페이지 최대 50명입니다. 하위 조직은 포함하지 않습니다.</p><form action="/company/organizations" method="get" className="t-toolbar"><input type="hidden" name="organizationId" value={m.organizationId ?? ''} /><div><Field label="선택 조직 사번·이름 검색" name="q" type="search" value={m.q} maxLength={100} /></div><button className="t-btn primary">조회</button></form>{m.employees.length ? <WorklogSheet label="선택 조직 직원" rows={m.employees} columns={columns} getRowId={employee => employee.id} /> : <p className="notice">조회된 직원이 없습니다.</p>}<nav className="actions pagination" aria-label="조직 직원 페이지"><span>{m.page + 1}페이지 · {m.employees.length}명</span>{m.page > 0 && <a href={queryHref('/company/organizations', { organizationId: m.organizationId, q: m.q, page: m.page - 1 })}>이전</a>}{m.hasNext && <a href={queryHref('/company/organizations', { organizationId: m.organizationId, q: m.q, page: m.page + 1 })}>다음</a>}</nav></> : <p className="notice">조직을 선택하면 현재 조직장과 직원 목록을 볼 수 있습니다.</p>}</section></div></div>;
}

export function OrgSheetPages({ page, data }: PageProps): ReactNode {
  const m = data as unknown as SheetPageModel;
  if (page === 'org/organizations' && m.organizationSheet) return <SheetPage model={m} organization />;
  if (page === 'org/employees' && m.employeeSheet) return <SheetPage model={m} organization={false} />;
  if (page === 'org/organization-heads') return <OrganizationHeadsPage model={data.organizationHeads as OrganizationHeads} error={data.error as string | null | undefined} />;
  if (page === 'company/organizations') return <CompanyOrganizationsPage model={data.companyOrganizations as CompanyOrganizations} />;
  return null;
}
