import { useEffect, useMemo, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import type { PageProps } from '../types';
import { ErrorNotice, Form, fmtDate, fmtDateTime } from '../ui';
import type { Employee, Organization } from './AdminTypes';
import { WorklogSheet, sheetColumn, sheetEditorColumn } from '../components/WorklogSheet';
import type { SheetEditorField, WorklogSheetHandle } from '../components/WorklogSheet';
type Kind = 'DIRECT_EMPLOYEE' | 'ORGANIZATION_HEAD';
interface Route {
  id: string;
  code: string;
  name: string;
  active: boolean;
  version: number;
  routeVersionId: string;
  revision: number;
  approverKind: Kind;
  employeeId: string | null;
  employeeName: string | null;
}
interface Row {
  clientRowId: string;
  id?: string | null;
  expectedVersion?: number | null;
  operation: 'CREATE' | 'UPDATE' | 'DEACTIVATE';
  code: string;
  name: string;
  active: boolean;
  approverKind: Kind;
  employeeId: string | null;
}
interface RowError {
  clientRowId: string;
  field: string;
  message: string;
}
interface Version {
  id: string;
  routeId: string;
  code: string;
  name: string;
  active: boolean;
  revision: number;
  approverKind: Kind;
  employeeId: string | null;
  employeeName: string | null;
  reason: string;
  createdAt: string;
}
interface Routes {
  q: string;
  page: number;
  hasNext: boolean;
  routes: Route[];
  versions: Version[];
  candidates: Employee[];
  sheet: {
    commandId: string;
    reason: string;
    rows: Row[];
    latestRows: Route[];
    errors: RowError[];
  };
}
interface AssignmentForm {
  scopeKind: 'EMPLOYEE' | 'ORGANIZATION';
  employeeId: string | null;
  organizationId: string | null;
  routeVersionId: string | null;
  validFrom: string;
  expectedVersion: number | null;
  reason: string;
  commandId: string;
}
interface Assignment {
  id: string;
  routeVersionId: string;
  routeCode: string;
  routeName: string;
  revision: number;
  approverKind: Kind;
  approverEmployeeName: string | null;
  scopeKind: string;
  employeeId: string | null;
  organizationId: string | null;
  validFrom: string;
  validTo: string | null;
  supersededAt: string | null;
  reason: string;
  version: number;
  routeActive: boolean;
}
interface Assignments {
  today: string;
  scopeKind: AssignmentForm['scopeKind'];
  employeeId: string | null;
  organizationId: string | null;
  employees: Employee[];
  organizations: Organization[];
  versions: Version[];
  history: Assignment[];
  form: AssignmentForm;
}
const routeFields = ['code', 'name', 'active', 'approverKind', 'employeeId'] as const;
const kindText = (kind: string) => kind === 'ORGANIZATION_HEAD' ? '대상자의 현재 조직장' : '지정 사원';
function queryHref(path: string, values: Record<string, string | number | null>) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(values))
    if (value != null)
      query.set(key, String(value));
  return `${path}?${query}`;
}
function useUnloadGuard(dirty: boolean) {
  useEffect(() => {
    const guard = (event: BeforeUnloadEvent) => {
      if (dirty) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', guard);
    return () => window.removeEventListener('beforeunload', guard);
  }, [dirty]);
}
function routeChanged(row: Row, previous: Route | undefined) {
  if (!row.id || !previous)
    return true;
  if (row.operation === 'DEACTIVATE')
    return previous.active;
  return routeFields.some(field => row[field] !== previous[field]);
}
function RouteSheet({ model: m, error }: {
  model: Routes;
  error?: string | null;
}) {
  const [rows, setRows] = useState(m.sheet.rows);
  const [reason, setReason] = useState(m.sheet.reason);
  const [commandId, setCommandId] = useState(m.sheet.commandId);
  const [selected, setSelected] = useState(new Set<string>());
  const [errors, setErrors] = useState(m.sheet.errors ?? []);
  const sheet = useRef<WorklogSheetHandle>(null);
  const latest = useMemo(() => new Map(m.sheet.latestRows.map(route => [route.id, route])), [m.sheet.latestRows]);
  const dirty = rows.filter(row => routeChanged(row, row.id ? latest.get(row.id) : undefined));
  const conflicts = dirty.filter(row => row.id && latest.has(row.id) && row.expectedVersion !== latest.get(row.id)!.version);
  const unsaved = dirty.length > 0 || reason.length > 0;
  const renew = () => setCommandId(crypto.randomUUID());
  useUnloadGuard(unsaved);
  function update(id: string, field: string, value: string | boolean | null) {
    setRows(current => current.map(row => row.clientRowId === id ? { ...row, [field]: value, ...(field === 'approverKind' && value === 'ORGANIZATION_HEAD' ? { employeeId: null } : {}) } : row));
    setErrors(current => current.filter(item => item.clientRowId !== id || item.field !== field));
    renew();
  }
  function add() {
    const clientRowId = crypto.randomUUID();
    setRows(current => [...current, { clientRowId, operation: 'CREATE', code: '', name: '', active: true, approverKind: 'ORGANIZATION_HEAD', employeeId: null }]);
    renew();
    setTimeout(() => sheet.current?.focusCell(clientRowId, 'code'), 0);
  }
  function remove() {
    setRows(current => current.filter(row => row.id || !selected.has(row.clientRowId)).map(row => selected.has(row.clientRowId) && row.id && latest.get(row.id)?.active ? { ...row, operation: 'DEACTIVATE' } : row));
    setSelected(new Set());
    renew();
  }
  function cancel() {
    setRows(m.sheet.latestRows.map(route => ({ ...route, operation: 'UPDATE', expectedVersion: route.version, clientRowId: m.sheet.rows.find(row => row.id === route.id)?.clientRowId ?? route.id })));
    setReason('');
    setErrors([]);
    setSelected(new Set());
    renew();
  }
  function confirmLatest() {
    setRows(current => current.map(row => row.id && latest.has(row.id) ? { ...row, expectedVersion: latest.get(row.id)!.version } : row));
    setErrors([]);
    renew();
  }
  function displayValue(route: Row | Route, field: typeof routeFields[number]) {
    if (field === 'active') return route.active ? '활성' : '비활성';
    if (field === 'approverKind') return kindText(route.approverKind);
    if (field === 'employeeId') return m.candidates.find(employee => employee.id === route.employeeId)?.displayName ?? ('employeeName' in route ? route.employeeName : null) ?? (route.employeeId ? '현재 선택값 · 재확인 필요' : '없음');
    return route[field];
  }
  function cell(row: Row, index: number, field: Exclude<typeof routeFields[number], 'active'>, label: string): SheetEditorField {
    const fieldError = errors.find(item => item.clientRowId === row.clientRowId && item.field === field);
    const previous = row.id ? latest.get(row.id) : undefined;
    const shared: SheetEditorField = {
      label: `${label} ${index + 1}`, value: String(row[field] ?? ''),
      disabled: row.operation === 'DEACTIVATE', changed: !previous || row[field] !== previous[field], error: fieldError?.message,
      onChange: value => update(row.clientRowId, field, field === 'employeeId' ? value || null : value),
      onCancel: () => { setRows(current => current.map(item => item.clientRowId === row.clientRowId ? row : item)); renew(); },
    };
    if (field === 'approverKind') return { ...shared, options: [['ORGANIZATION_HEAD', '대상자의 현재 조직장'], ['DIRECT_EMPLOYEE', '지정 사원']] };
    if (field === 'employeeId') {
      const missing = row.employeeId && !m.candidates.some(employee => employee.id === row.employeeId);
      return { ...shared, disabled: shared.disabled || row.approverKind === 'ORGANIZATION_HEAD', required: row.approverKind === 'DIRECT_EMPLOYEE',
        options: [['', row.approverKind === 'ORGANIZATION_HEAD' ? '제출 시 조직장 해석' : '결재 사원 선택'],
          ...(missing ? [[row.employeeId!, `${previous?.employeeName ?? '현재 선택값'} (재지정 필요)`] as const] : []),
          ...m.candidates.map(employee => [employee.id, `${employee.employeeNo} · ${employee.displayName}`] as const)],
      };
    }
    return { ...shared, readOnly: field === 'code' && Boolean(row.id), maxLength: field === 'code' ? 40 : 120, required: true };
  }
  function submit(event: FormEvent) {
    if (!sheet.current?.validate()) { event.preventDefault(); return; }
    if (dirty.length < 1 || dirty.length > 50 || conflicts.length)
      event.preventDefault();
  }
  const payload = dirty.map(row => ({ clientRowId: row.clientRowId, id: row.id || undefined, expectedVersion: row.id ? row.expectedVersion : undefined, operation: row.operation, code: row.code, name: row.name, active: row.active, approverKind: row.approverKind, employeeId: row.employeeId }));
  const columns = [
    sheetEditorColumn<Row>({ field: 'code', headerName: '코드', minWidth: 150, pinned: 'left' }, (row, index) => cell(row, index, 'code', '결재선 코드')),
    sheetEditorColumn<Row>({ field: 'name', headerName: '이름', minWidth: 180, flex: 1 }, (row, index) => cell(row, index, 'name', '결재선 이름')),
    sheetEditorColumn<Row>({ field: 'approverKind', headerName: '결재 방식', minWidth: 210 }, (row, index) => cell(row, index, 'approverKind', '결재 방식')),
    sheetEditorColumn<Row>({ field: 'employeeId', headerName: '지정 사원', minWidth: 210 }, (row, index) => cell(row, index, 'employeeId', '지정 결재 사원')),
    sheetColumn<Row>({ colId: 'revision', headerName: '현재 버전', minWidth: 130, valueGetter: ({ data }) => data?.id ? latest.get(data.id)?.revision ?? '' : '' }, row => {
      const previous = row.id ? latest.get(row.id) : undefined;
      return previous ? `v${previous.revision}` : '저장 후 생성';
    }),
    sheetColumn<Row>({ field: 'active', headerName: '활성', minWidth: 90, maxWidth: 110, cellClass: 'w-sheet-center' }, (row, index) => <input className="t-sheet-check" type="checkbox" aria-label={`활성 ${index + 1}`} checked={row.active}
      disabled={row.operation === 'DEACTIVATE'} aria-invalid={errors.some(error => error.clientRowId === row.clientRowId && error.field === 'active')}
      data-changed={String(!row.id || row.active !== latest.get(row.id)?.active)} onChange={event => update(row.clientRowId, 'active', event.target.checked)} />),
    sheetColumn<Row>({ colId: 'state', headerName: '변경 상태', minWidth: 120, valueGetter: ({ data }) => data ? data.operation === 'DEACTIVATE' ? '비활성 예정' : !data.id ? '신규' : routeChanged(data, latest.get(data.id)) ? '수정' : '저장됨' : '' }, row => {
      const modified = routeChanged(row, row.id ? latest.get(row.id) : undefined);
      return <span className={`t-tag ${row.operation === 'DEACTIVATE' ? 'amber' : modified ? 'blue' : 'gray'}`}>{row.operation === 'DEACTIVATE' ? '비활성 예정' : !row.id ? '신규' : modified ? '수정' : '저장됨'}</span>;
    }),
  ];
  return <section className="t-org-sheet t-approval-routes" data-unsaved-changes={String(unsaved)}>
    <header className="t-pagehead">
      <div>
        <p className="t-eyebrow">권한·결재 설정</p>
        <h1>결재선 관리</h1>
        <p className="t-muted">한 명의 결재자 또는 대상자의 현재 조직장을 지정하세요.</p>
      </div>
      <a className="t-btn" href="/admin/approval-route-assignments">사원·조직별 배정</a>
    </header>
    <section className="t-card">
      <form action="/admin/approval-routes" method="get" className="t-toolbar" role="search" aria-label="결재선 검색">
        <label htmlFor="route-q">결재선 검색</label>
        <input
          id="route-q"
          className="t-input"
          type="search"
          name="q"
          defaultValue={m.q}
          maxLength={120}
          placeholder="코드 또는 이름" />
        <button className="t-btn primary">조회</button>
        <a className="t-btn" href="/admin/approval-routes">전체 보기</a>
      </form>
      <p className="t-subtle">내용 변경은 새 버전으로 저장됩니다. 기존 배정은 유지되며 새 버전을 사용하려면 다시 배정하세요.</p>
      <Form action="/admin/approval-routes/bulk" onSubmit={submit} className="t-sheet-form">
        <input type="hidden" name="commandId" value={commandId} />
        <input type="hidden" name="rows" value={JSON.stringify(payload)} />
        <input type="hidden" name="q" value={m.q} />
        <input type="hidden" name="page" value={m.page} />
        <div className="t-sheet-toolbar" role="group" aria-label="결재선 시트 편집">
          <button type="button" className="t-btn" onClick={add} disabled={dirty.length >= 50}>입력</button>
          <button type="button" className="t-btn" onClick={remove} disabled={!selected.size} title="미저장 신규 행은 제거하고 저장된 행은 비활성 예정으로 표시합니다.">비활성화</button>
          <button
            type="submit"
            className="t-btn primary"
            disabled={!dirty.length || dirty.length > 50 || Boolean(conflicts.length)}>저장</button>
          <button type="button" className="t-btn" onClick={cancel} disabled={!unsaved}>취소</button>
          <span className="t-tag blue" role="status">변경 {dirty.length}행</span>
        </div>
        <p className="t-subtle">한 번에 최대 50행을 저장합니다. 기존 행 삭제는 이력을 보존하고 비활성화합니다. 비활성 결재선은 신규 신청을 막지만 기존 신청의 결재자를 바꾸지 않습니다.</p>
        {(error || errors.length > 0) && <div
          className="notice error"
          role="alert"
          tabIndex={-1}
          data-error-focus={errors.some(item => routeFields.includes(item.field as typeof routeFields[number])) ? undefined : 'true'}>
          <p>
            {error ?? '입력을 확인하세요. 편집 내용은 보존되어 있습니다.'}
          </p>
          {errors.filter(item => !routeFields.includes(item.field as typeof routeFields[number])).map((item, index) => <p key={index}>
            {item.message}
          </p>)}
        </div>}
        <WorklogSheet ref={sheet} label="결재선 편집 시트" rows={rows} columns={columns} getRowId={row => row.clientRowId}
          selectedIds={selected} onSelectionChange={setSelected} selectionLabel={(_row, index) => `${index + 1}행 선택`}
          changedIds={new Set(dirty.map(row => row.clientRowId))} errors={errors} rowHeight={errors.length ? 64 : 40} />
        {!rows.length && <p className="notice">등록된 결재선이 없습니다. 입력 버튼으로 추가하세요.</p>}
        {conflicts.length > 0 && <section className="t-sheet-conflict" aria-label="충돌 최신값 비교">
          <h2>최신값을 확인하세요</h2>
          {conflicts.map(row => {
            const current = latest.get(row.id!)!; return <div key={row.clientRowId}>
              <p>최신값: <strong>
                {current.name}
              </strong> · v{current.version}</p>
              <p>편집값: <strong>
                {row.name}
              </strong></p>
              <dl>
                {routeFields.map(field => <div key={field}>
                  <dt>
                    {{ code: '코드', name: '이름', active: '활성', approverKind: '결재 방식', employeeId: '지정 사원 ID' }[field]}
                  </dt>
                  <dd>{displayValue(current, field)} → {displayValue(row, field)}</dd>
                </div>)}
              </dl>
            </div>;
          })}
          <button type="button" className="t-btn" onClick={confirmLatest}>최신값 확인 후 편집 유지</button>
        </section>}
        <div className="t-sheet-reason">
          <label htmlFor="route-reason">일괄 변경 사유</label>
          <textarea
            id="route-reason"
            name="reason"
            value={reason}
            rows={2}
            maxLength={500}
            required
            onChange={event => { setReason(event.target.value); renew(); }} />
        </div>
      </Form>
      <nav className="actions pagination" aria-label="결재선 페이지">
        <span>{m.page + 1}페이지</span>
        {m.page > 0 && <a href={queryHref('/admin/approval-routes', { q: m.q, page: m.page - 1 })}>이전</a>}
        {m.hasNext && <a href={queryHref('/admin/approval-routes', { q: m.q, page: m.page + 1 })}>다음</a>}
      </nav>
    </section>
    <section className="t-card t-route-versions">
      <h2>결재선 버전 이력</h2>
      <p className="t-subtle">저장된 내용은 덮어쓰지 않습니다. 조회된 결재선의 버전입니다.</p>
      <VersionTable versions={m.versions.filter(version => m.sheet.latestRows.some(route => route.id === version.routeId))} />
    </section>
  </section>;
}
function VersionTable({ versions }: {
  versions: Version[];
}) {
  const columns = [
    sheetColumn<Version>({ colId: 'name', headerName: '코드·이름', minWidth: 200, pinned: 'left', valueGetter: ({ data }) => data ? `${data.code} · ${data.name}` : '' }, version => <>{version.code} · {version.name}{!version.active && <span className="t-tag gray">정의 비활성</span>}</>),
    sheetColumn<Version>({ field: 'revision', headerName: '버전', minWidth: 90 }, version => `v${version.revision}`),
    sheetColumn<Version>({ colId: 'approver', headerName: '결재자', minWidth: 230, valueGetter: ({ data }) => data ? `${kindText(data.approverKind)}${data.employeeName ? ` · ${data.employeeName}` : ''}` : '' }, version => <>{kindText(version.approverKind)}{version.employeeName ? ` · ${version.employeeName}` : ''}</>),
    sheetColumn<Version>({ field: 'createdAt', headerName: '등록 시각 (서울)', minWidth: 185 }, version => fmtDateTime(version.createdAt)),
    sheetColumn<Version>({ field: 'reason', headerName: '변경 사유', minWidth: 220, flex: 1 }, version => version.reason),
  ];
  return versions.length ? <WorklogSheet label="결재선 버전 이력" rows={versions} columns={columns} getRowId={version => version.id} rowHeight={64} /> : <p className="notice">등록된 버전이 없습니다.</p>;
}
function RouteAssignments({ model: m, error }: {
  model: Assignments;
  error?: string | null;
}) {
  const [scopeKind, setScopeKind] = useState(m.scopeKind);
  const [form, setForm] = useState(m.form);
  const [commandId, setCommandId] = useState(m.form.commandId);
  const [expectedVersion, setExpectedVersion] = useState(m.form.expectedVersion);
  const currentAssignment = m.history.find(assignment => !assignment.supersededAt && assignment.validFrom <= m.today && (!assignment.validTo || assignment.validTo > m.today));
  const originalVersionId = currentAssignment?.routeVersionId ?? null;
  const dirty = Boolean(form.reason) || (form.routeVersionId || null) !== originalVersionId || form.validFrom !== m.today;
  const selected = m.scopeKind === 'EMPLOYEE' ? m.employees.find(employee => employee.id === m.employeeId) : m.organizations.find(organization => organization.id === m.organizationId);
  const conflict = selected != null && expectedVersion !== selected.version;
  useUnloadGuard(dirty);
  function update(field: 'routeVersionId' | 'validFrom' | 'reason', value: string) { setForm(current => ({ ...current, [field]: value })); setCommandId(crypto.randomUUID()); }
  function cancel() {
    setForm({ ...m.form, routeVersionId: originalVersionId, validFrom: m.today, reason: '' });
    setExpectedVersion(selected?.version ?? m.form.expectedVersion);
    setCommandId(crypto.randomUUID());
  }
  function start() { document.getElementById('route-version')?.focus(); }
  const selectedVersion = m.versions.find(version => version.id === form.routeVersionId);
  const missingVersion = form.routeVersionId && !selectedVersion;
  const columns = [
    sheetColumn<Assignment>({ colId: 'routeName', headerName: '결재선·버전', minWidth: 230, pinned: 'left', valueGetter: ({ data }) => data ? `${data.routeCode} · ${data.routeName} · v${data.revision}` : '' }, assignment => `${assignment.routeCode} · ${assignment.routeName} · v${assignment.revision}`),
    sheetColumn<Assignment>({ colId: 'approver', headerName: '결재 방식', minWidth: 230, valueGetter: ({ data }) => data ? `${kindText(data.approverKind)}${data.approverEmployeeName ? ` · ${data.approverEmployeeName}` : ''}` : '' }, assignment => <>{kindText(assignment.approverKind)}{assignment.approverEmployeeName ? ` · ${assignment.approverEmployeeName}` : ''}</>),
    sheetColumn<Assignment>({ field: 'validFrom', headerName: '적용 기간 (종료 제외)', minWidth: 230 }, assignment => `${fmtDate(assignment.validFrom)} ~ ${assignment.validTo ? fmtDate(assignment.validTo) : '종료 미정'}`),
    sheetColumn<Assignment>({ colId: 'state', headerName: '상태', minWidth: 230, valueGetter: ({ data }) => data ? data.supersededAt ? '대체됨' : data.validTo && data.validTo <= m.today ? '종료' : data.validFrom > m.today ? '예정' : '현재 배정' : '' }, assignment => <><span className="t-tag gray">{assignment.supersededAt ? '대체됨' : assignment.validTo && assignment.validTo <= m.today ? '종료' : assignment.validFrom > m.today ? '예정' : '현재 배정'}</span>{!assignment.routeActive && <span className="t-tag amber">결재선 비활성 · 신청 차단</span>}</>),
    sheetColumn<Assignment>({ field: 'reason', headerName: '사유', minWidth: 220, flex: 1 }, assignment => assignment.reason),
  ];
  return <section className="t-approval-assignments" data-unsaved-changes={String(dirty)}>
    <header className="t-pagehead">
      <div>
        <p className="t-eyebrow">권한·결재 설정</p>
        <h1>결재선 배정</h1>
        <p className="t-muted">사원 또는 조직에 적용할 1단계 결재선 버전을 지정하세요.</p>
      </div>
      <a className="t-btn" href="/admin/approval-routes">결재선 관리</a>
    </header>
    <ErrorNotice message={error} />
    <section className="t-card">
      <form
        action="/admin/approval-route-assignments"
        method="get"
        className="t-toolbar"
        role="search"
        aria-label="결재선 배정 대상 조회">
        <label htmlFor="route-scope">배정 범위</label>
        <select
          id="route-scope"
          className="t-select"
          name="scopeKind"
          value={scopeKind}
          onChange={event => setScopeKind(event.target.value as AssignmentForm['scopeKind'])}>
          <option value="EMPLOYEE">사원별</option>
          <option value="ORGANIZATION">조직별</option>
        </select>
        {scopeKind === 'EMPLOYEE' ? <>
          <label htmlFor="route-employee">대상 사원</label>
          <select className="t-select" id="route-employee" name="employeeId" defaultValue={m.employeeId ?? ''} required>
            <option value="">사원 선택</option>
            {m.employees.map(employee => <option key={employee.id} value={employee.id}>{employee.employeeNo} · {employee.displayName}</option>)}
          </select>
        </> : <>
          <label htmlFor="route-organization">대상 조직</label>
          <select
            className="t-select"
            id="route-organization"
            name="organizationId"
            defaultValue={m.organizationId ?? ''}
            required>
            <option value="">조직 선택</option>
            {m.organizations.map(organization => <option key={organization.id} value={organization.id}>{organization.code} · {organization.name}</option>)}
          </select>
        </>}
        <button className="t-btn primary">조회</button>
      </form>
      <p className="t-subtle">선택 우선순위: 사원별 결재선 → 기존 지정 승인자 → 현재 소속 조직의 결재선. 선택된 경로가 공석·부적격이면 신청을 차단합니다.</p>
    </section>
    {selected ? <>
      <section className="t-card">
        <h2>{'displayName' in selected ? selected.displayName : selected.name} · 결재선 지정·해제</h2>
        {conflict && <div className="t-sheet-conflict">
          <p>대상 정보가 변경되었습니다. 최신 대상과 배정 이력을 확인한 후 입력을 유지할 수 있습니다.</p>
          <p>조회 대상 버전 {selected.version} · 입력 기준 {expectedVersion}</p>
          <button
            type="button"
            className="t-btn"
            onClick={() => { setExpectedVersion(selected.version); setCommandId(crypto.randomUUID()); }}>최신 대상 확인 후 입력 유지</button>
        </div>}
        <Form
          action="/admin/approval-route-assignments"
          className="admin-form t-route-assignment-form"
          onSubmit={event => {
            if (conflict)
              event.preventDefault();
          }}>
          <input type="hidden" name="scopeKind" value={m.form.scopeKind} />
          <input type="hidden" name="employeeId" value={m.form.employeeId ?? ''} />
          <input type="hidden" name="organizationId" value={m.form.organizationId ?? ''} />
          <input type="hidden" name="expectedVersion" value={expectedVersion ?? ''} />
          <input type="hidden" name="commandId" value={commandId} />
          <label htmlFor="route-version">적용할 결재선 버전</label>
          <select
            className="t-select"
            id="route-version"
            name="routeVersionId"
            value={form.routeVersionId ?? ''}
            onChange={event => update('routeVersionId', event.target.value)}>
            <option value="">저장할 버전을 선택하세요</option>
            {missingVersion && <option value={form.routeVersionId!}>현재 선택값 (재확인 필요)</option>}
            {m.versions.map(version => <option key={version.id} value={version.id} disabled={!version.active}>{version.code} · {version.name} · v{version.revision} · {kindText(version.approverKind)}{version.employeeName ? ` (${version.employeeName})` : ''}{!version.active ? ' (정의 비활성)' : ''}</option>)}
          </select>
          <label htmlFor="route-valid-from">적용일 (시작 포함·종료 제외)</label>
          <input
            className="t-input"
            id="route-valid-from"
            type="date"
            name="validFrom"
            min={m.today}
            value={form.validFrom}
            required
            onChange={event => update('validFrom', event.target.value)} />
          <label htmlFor="route-assignment-reason">배정·해제 사유</label>
          <textarea
            id="route-assignment-reason"
            name="reason"
            rows={3}
            maxLength={500}
            value={form.reason}
            required
            onChange={event => update('reason', event.target.value)} />
          <div className="t-sheet-toolbar" role="group" aria-label="결재선 배정 편집">
            <button className="t-btn" type="button" onClick={start}>입력</button>
            <button
              className="t-btn primary"
              type="submit"
              disabled={conflict || !selected.active || !selectedVersion?.active}
              title={!selected.active ? '비활성 대상에는 새 배정을 할 수 없습니다.' : undefined}>저장</button>
            <button
              className="t-btn"
              type="submit"
              formAction="/admin/approval-route-assignments/end"
              disabled={conflict || m.history.every(assignment => assignment.supersededAt || assignment.validTo && assignment.validTo <= m.today)}>배정 종료</button>
            <button className="t-btn" type="button" disabled={!dirty} onClick={cancel}>취소</button>
          </div>
          <p className="t-subtle">오늘 이후에 적용하며 기존 구간은 이력으로 보존합니다. 종료하면 해당일부터 이 배정을 해제합니다. 기존 대기 신청은 자동 이동하지 않고 관리자가 신청 상세에서 명시 재할당합니다.</p>
        </Form>
      </section>
      <section className="t-card">
        <h2>적용 배정 이력</h2>
        {m.history.length ? <WorklogSheet label="결재선 배정 이력" rows={m.history} columns={columns} getRowId={assignment => assignment.id} rowHeight={64} scope="all" /> : <p className="notice">등록된 배정이 없습니다.</p>}
      </section>
    </> : <section className="t-card">
      <p className="notice">대상 사원 또는 조직을 선택해 조회하세요.</p>
    </section>}
  </section>;
}
export function ApprovalRoutePages({ page, data }: PageProps): ReactNode {
  if (page === 'org/approval-routes')
    return <RouteSheet model={data.approvalRoutes as Routes} error={data.error as string | null | undefined} />;
  if (page === 'org/approval-route-assignments')
    return <RouteAssignments
      model={data.approvalRouteAssignments as Assignments}
      error={data.error as string | null | undefined} />;
  return null;
}
