import type React from 'react';
import type { PageProps } from '../types';
import { WorklogSheet, sheetColumn } from '../components/WorklogSheet';
import { PageHeader } from '../components/PageHeader';
import {
  Check,
  ErrorNotice,
  Field,
  Form,
  FormRow,
  FormTable,
  SelectField,
  TextAreaField,
  fmtDate,
  fmtDateTime,
  fmtTime,
} from '../ui';
import type {
  AccessModel,
  CalendarModel,
  CommandForm,
  EmployeeForm,
  EmployeeModel,
  EmployeesModel,
  Employee,
  NetworksModel,
  OrganizationForm,
  OrganizationModel,
  OrganizationsModel,
  ScheduleAssignmentModel,
  ScheduleDetailModel,
  ScheduleListModel,
  Template,
  TemplateForm,
} from './AdminTypes';

function Hidden({ name, value }: { name: string; value?: string | number | null }) {
  return <input type="hidden" name={name} value={value ?? ''} />;
}

function CommandFields({ form }: { form: CommandForm }) {
  return (
    <>
      <Hidden name="commandId" value={form.commandId} />
      <Hidden name="expectedVersion" value={form.expectedVersion} />
    </>
  );
}

function Reason({
  form,
  label = '변경 사유',
  id,
}: {
  form: CommandForm;
  label?: string;
  id: string;
}) {
  return (
    <>
      <TextAreaField
        id={id}
        label={label}
        name="reason"
        value={form.reason}
        rows={3}
        maxLength={500}
        required
      />
      <p className="hint">500자 이내로 입력하세요. 불필요한 개인정보는 적지 마세요.</p>
    </>
  );
}

function ActiveStatus({ active }: { active: boolean }) {
  return <span className={`t-tag ${active ? 'green' : 'gray'}`}>{active ? '활성' : '비활성'}</span>;
}

function queryHref(path: string, values: Record<string, string | number | null | undefined>) {
  const query = new URLSearchParams();
  for (const [name, value] of Object.entries(values))
    if (value != null) query.set(name, String(value));
  return `${path}?${query}`;
}

function EmployeeFields({ form, editing = false }: { form: EmployeeForm; editing?: boolean }) {
  return (
    <>
      <CommandFields form={form} />
      <FormTable>
        <Field
          id="employee-number"
          label="사번"
          name="employeeNo"
          value={form.employeeNo}
          maxLength={40}
          readOnly={editing}
          required={!editing}
          aria-describedby="employee-number-help"
        />
        <p id="employee-number-help" className="hint">
          {editing
            ? '등록한 사번은 변경할 수 없습니다.'
            : '등록 후 변경하거나 다른 사원에게 재사용할 수 없습니다.'}
        </p>
        <Field
          id="employee-name"
          label="이름"
          name="displayName"
          value={form.displayName}
          maxLength={120}
          required
        />
        <FormRow label="재직 기간" required>
          <Field
            id="employee-joined"
            label="입사일"
            name="joinedOn"
            type="date"
            value={form.joinedOn ?? ''}
            required
          />
          <Field
            id="employee-left"
            label="마지막 재직일 (선택)"
            name="leftOn"
            type="date"
            value={form.leftOn ?? ''}
            aria-describedby="employee-left-help"
          />
        </FormRow>
        <p id="employee-left-help" className="hint">
          마지막 재직일 당일까지 재직으로 처리합니다. 미정이면 비워 두세요.
        </p>
        <FormRow label="상태"><Check name="active" label="활성 사원" checked={form.active} /></FormRow>
        {editing && (
          <p className="hint">
            재직일이나 활성 상태를 변경하면 연결된 계정의 기존 로그인이 종료됩니다.
          </p>
        )}
        <Reason id="employee-reason" form={form} label={editing ? '변경 사유' : '등록 사유'} />
      </FormTable>
    </>
  );
}

function EmployeesPage({ model: m }: { model: EmployeesModel }) {
  const listHref = (page: number, employeeId?: string) =>
    queryHref('/admin/employees', {
      q: m.q,
      page,
      organizationId: m.organizationId,
      status: m.status,
      employeeId,
    });
  const selected = m.selectedEmployee;
  return (
    <>
      <PageHeader title="직원 목록" help="조직과 재직 정보를 한곳에서." />
      <div className="t-grid-3" aria-label="현재 페이지 직원 현황">
        <section className="t-card">
          <p className="t-metric-label">현재 페이지 직원</p>
          <p className="t-metric-value">{m.pageEmployeeCount}</p>
        </section>
        <section className="t-card">
          <p className="t-metric-label">현재 페이지 활성</p>
          <p className="t-metric-value">{m.pageActiveCount}</p>
        </section>
        <section className="t-card">
          <p className="t-metric-label">현재 페이지 비활성</p>
          <p className="t-metric-value">{m.pageInactiveCount}</p>
        </section>
      </div>
      <p className="t-subtle">
        검색·필터를 적용한 현재 페이지의 집계입니다. 활성 상태는 재직 기간·퇴사 여부와 구분합니다.
      </p>
      <div className="t-employee-layout">
        <section className="t-card" aria-labelledby="employee-list-title">
          <header className="t-cardhead">
            <h2 id="employee-list-title">직원 명단</h2>
            <p className="t-muted" role="status">
              {m.page + 1}페이지 · {m.pageEmployeeCount}명 표시
            </p>
          </header>
          <form
            action="/admin/employees"
            method="get"
            className="t-toolbar"
            role="search"
            aria-label="직원 검색 및 필터"
          >
            <label className="t-sr-only" htmlFor="tna-employee-search">
              직원 이름 또는 사번 검색
            </label>
            <input
              className="t-input"
              id="tna-employee-search"
              name="q"
              type="search"
              defaultValue={m.q}
              placeholder="이름 또는 사번 검색"
              maxLength={120}
            />
            <label className="t-sr-only" htmlFor="tna-department">
              현재 소속 조직
            </label>
            <select
              className="t-select"
              id="tna-department"
              name="organizationId"
              defaultValue={m.organizationId ?? ''}
            >
              <option value="">전체 소속</option>
              {m.options.map((option) => (
                <option key={option.id} value={option.id}>
                  {option.name}
                </option>
              ))}
            </select>
            <label className="t-sr-only" htmlFor="tna-employment">
              활성 상태
            </label>
            <select className="t-select" id="tna-employment" name="status" defaultValue={m.status}>
              <option value="all">전체 상태</option>
              <option value="active">활성</option>
              <option value="inactive">비활성</option>
            </select>
            <button type="submit" className="t-btn primary">
              검색
            </button>
            <a href="/admin/employees" className="t-btn secondary">
              전체 보기
            </a>
          </form>
          {m.employees.length === 0 ? (
            <p className="t-muted">
              {!m.q && !m.organizationId && m.status === 'all'
                ? '등록된 사원이 없습니다. 아래에서 첫 사원을 등록해 주세요.'
                : '검색 결과가 없습니다. 검색어와 필터를 확인해 주세요.'}
            </p>
          ) : (
            <WorklogSheet
              label="직원 명단"
              rows={m.employees}
              getRowId={(employee) => employee.id}
              scope="page"
              columns={[
                sheetColumn<Employee>(
                  { colId: 'employee', headerName: '직원', valueGetter: ({ data }) => data?.displayName, minWidth: 180, pinned: 'left' },
                  (employee) => <a className="t-person-button" href={`${listHref(m.page, employee.id)}#employee-detail`} aria-current={selected?.id === employee.id ? 'true' : undefined}>
                    <span className="t-avatar" aria-hidden="true">직</span><strong>{employee.displayName}</strong>
                  </a>,
                ),
                { field: 'employeeNo', headerName: '사번', minWidth: 120 },
                { colId: 'organization', headerName: '현재 소속', valueGetter: ({ data }) => data && (m.employeeOrganizations[data.id] ?? '미배정'), minWidth: 160 },
                sheetColumn<Employee>(
                  { colId: 'active', headerName: '활성 상태', valueGetter: ({ data }) => data?.active ? '활성' : '비활성', minWidth: 130 },
                  (employee) => <ActiveStatus active={employee.active} />,
                ),
              ]}
            />
          )}
          <nav className="t-actions" aria-label="사원 목록 페이지">
            {m.page > 0 && (
              <a className="t-btn secondary" href={listHref(m.page - 1)}>
                이전 페이지
              </a>
            )}
            <span>{m.page + 1}페이지</span>
            {m.hasNext && (
              <a className="t-btn secondary" href={listHref(m.page + 1)}>
                다음 페이지
              </a>
            )}
          </nav>
        </section>
        <section
          className="t-card"
          id="employee-detail"
          tabIndex={-1}
          aria-labelledby="selected-employee-title"
        >
          <h2 id="selected-employee-title">직원 상세</h2>
          {selected ? (
            <>
              <div className="t-person-hero">
                <span className="t-avatar blue" aria-hidden="true">
                  직
                </span>
                <h3>{selected.displayName}</h3>
                <ActiveStatus active={selected.active} />
              </div>
              <dl className="t-person-info">
                <div>
                  <dt>사번</dt>
                  <dd>{selected.employeeNo}</dd>
                </div>
                <div>
                  <dt>현재 소속</dt>
                  <dd>{m.employeeOrganizations[selected.id] ?? '미배정'}</dd>
                </div>
                <div>
                  <dt>입사일</dt>
                  <dd>
                    <time dateTime={selected.joinedOn}>{fmtDate(selected.joinedOn)}</time>
                  </dd>
                </div>
                <div>
                  <dt>마지막 재직일</dt>
                  <dd>
                    {selected.leftOn ? (
                      <time dateTime={selected.leftOn}>{fmtDate(selected.leftOn)}</time>
                    ) : (
                      '미지정'
                    )}
                  </dd>
                </div>
              </dl>
              <p className="t-subtle">
                현재 소속은 서울 기준 오늘 유효한 배정입니다. 비활성은 퇴사·삭제와 구분합니다.
              </p>
              <p>
                <a className="t-btn secondary" href={`/admin/employees/${selected.id}`}>
                  사원 상세·수정
                </a>
              </p>
              <p>
                <a href={`/admin/employees/${selected.id}/access`}>계정 초대·역할·승인자 관리</a>
              </p>
            </>
          ) : (
            <p className="t-muted">
              표시할 직원이 없습니다. 검색 조건을 바꾸거나 직원을 등록해 주세요.
            </p>
          )}
        </section>
      </div>
      <details className="t-card" open={Boolean(m.error)}>
        <summary id="employee-create-title">직원 등록</summary>
        <p className="hint">사원을 등록해도 로그인 계정은 자동으로 생성되지 않습니다.</p>
        <ErrorNotice message={m.error} />
        <Form action="/admin/employees" method="post" className="admin-form">
          <EmployeeFields form={m.form} />
          <div className="actions">
            <button type="submit">사원 등록</button>
          </div>
        </Form>
      </details>
    </>
  );
}

function EmployeePage({ model: m }: { model: EmployeeModel }) {
  const employeePath = `/admin/employees/${m.employee.id}`;
  return (
    <div className="t-legacy-page admin-shell">
      <PageHeader title={m.employee.displayName} actions={<>
        <a className="t-btn" href="/admin/employees">사원 목록으로</a>
        <a className="t-btn" href={`${employeePath}/personnel`}>인사정보·사원사진 등록</a>
        <a className="t-btn" href={`${employeePath}/access`}>계정 초대·역할·승인자 관리</a>
        <a className="t-btn" href={`${employeePath}/schedule`}>고정 근무 템플릿 배정</a>
      </>} />
      <ErrorNotice message={m.error} />
      <section className="wl-section" aria-labelledby="employee-change-title">
        <h2 id="employee-change-title">기본정보·재직 변경</h2>
        <Form action={`${employeePath}/changes`} method="post" className="wl-form">
          <EmployeeFields form={m.form} editing />
          <div className="wl-form-actions">
            <a className="t-btn" href={employeePath}>입력 취소</a>
            <button type="submit" className="t-btn primary">사원 정보 저장</button>
          </div>
        </Form>
      </section>
      <section className="wl-section" aria-labelledby="assignment-history-title">
        <h2 id="assignment-history-title">소속 이력</h2>
        <p className="hint">
          시작일은 포함하고 종료일은 포함하지 않습니다. 종료일에 새 소속이 시작됩니다.
        </p>
        {m.assignments.length === 0 ? (
          <p className="notice">등록된 소속이 없습니다. 아래에서 첫 소속을 등록해 주세요.</p>
        ) : (
          <WorklogSheet
            label="소속 이력"
            rows={m.assignments}
            getRowId={(assignment) => assignment.id}
            scope="all"
            columns={[
              { field: 'organizationName', headerName: '조직', minWidth: 170, pinned: 'left' },
              sheetColumn<EmployeeModel['assignments'][number]>(
                { colId: 'validFrom', headerName: '시작일 (포함)', valueGetter: ({ data }) => data && fmtDate(data.validFrom), minWidth: 160 },
                (assignment) => <time dateTime={assignment.validFrom}>{fmtDate(assignment.validFrom)}</time>,
              ),
              { colId: 'validTo', headerName: '종료일 (제외)', valueGetter: ({ data }) => data?.validTo ? fmtDate(data.validTo) : '종료일 없음', minWidth: 160 },
              { field: 'reason', headerName: '사유', minWidth: 240 },
            ]}
          />
        )}
      </section>
      <section className="wl-section" aria-labelledby="assignment-create-title">
        <h2 id="assignment-create-title">소속 등록·이동</h2>
        <p className="hint" id="assignment-date-help">
          적용일은 오늘부터 선택할 수 있으며 입사일부터 마지막 재직일까지여야 합니다. 기존 이력이
          있으면 마지막 시작일보다 늦은 날만 선택할 수 있습니다. 과거 이력 수정·같은 날 재배정·예약
          취소는 제공하지 않습니다.
        </p>
        <Form action={`${employeePath}/assignments`} method="post" className="wl-form">
          <CommandFields form={m.assignmentForm} />
          <FormTable>
            <SelectField
              id="assignment-organization"
              label="새 소속 조직"
              name="organizationId"
              value={m.assignmentForm.organizationId ?? ''}
              required
              aria-describedby="assignment-organization-help"
            >
              <option value="">조직을 선택하세요</option>
              {m.options
                .filter((option) => option.active)
                .map((option) => (
                  <option key={option.id} value={option.id}>
                    {option.name}
                  </option>
                ))}
            </SelectField>
            <p className="hint" id="assignment-organization-help">
              활성 조직만 선택할 수 있습니다. 조직이 없으면{' '}
              <a href="/admin/organizations">조직 관리</a>에서 등록해 주세요.
            </p>
            <Field
              id="assignment-from"
              label="적용 시작일"
              name="validFrom"
              type="date"
              value={m.assignmentForm.validFrom ?? ''}
              required
              aria-describedby="assignment-date-help"
            />
            <Reason id="assignment-reason" form={m.assignmentForm} label="소속 등록·이동 사유" />
            <p className="hint">기존 소속 이력은 보존됩니다.</p>
          </FormTable>
          <div className="wl-form-actions">
            <button type="submit" className="t-btn primary">소속 등록·이동</button>
          </div>
        </Form>
      </section>
    </div>
  );
}

function OrganizationFields({
  form,
  options,
  organizationId,
}: {
  form: OrganizationForm;
  options: OrganizationModel['options'];
  organizationId?: string;
}) {
  return (
    <>
      <CommandFields form={form} />
      <FormTable>
      <Field
        id="organization-code"
        label="조직 코드"
        name="code"
        value={form.code}
        maxLength={40}
        readOnly={Boolean(organizationId)}
        required={!organizationId}
      />
      <p className="hint">
        {organizationId
          ? '등록한 코드는 변경할 수 없습니다.'
          : '등록 후 코드는 변경할 수 없습니다.'}
      </p>
      <Field
        id="organization-name"
        label="조직 이름"
        name="name"
        value={form.name}
        maxLength={120}
        required
      />
      <SelectField
        id="organization-parent"
        label="상위 조직"
        name="parentId"
        value={form.parentId ?? ''}
      >
        <option value="">없음 (최상위 조직)</option>
        {options
          .filter((option) =>
            organizationId
              ? (option.active && option.id !== organizationId) || option.id === form.parentId
              : option.active,
          )
          .map((option) => (
            <option key={option.id} value={option.id}>
              {option.name}
              {option.active ? '' : ' (비활성)'}
            </option>
          ))}
      </SelectField>
      {organizationId && (
        <p className="hint">
          새 상위 조직은 활성 조직을 선택하세요. 비활성 조직은 기존 부모를 유지해 이름을 수정할 수
          있지만 부모가 비활성이면 재활성화할 수 없습니다. 하위 조직을 상위 조직으로 지정할 수
          없습니다.
        </p>
      )}
      <FormRow label="상태"><Check label="활성 조직" name="active" checked={form.active} /></FormRow>
      {organizationId && (
        <p className="hint">
          활성 하위 조직이나 오늘·미래의 소속이 남아 있으면 비활성화할 수 없습니다.
        </p>
      )}
      <Reason
        id="organization-reason"
        form={form}
        label={organizationId ? '변경 사유' : '등록 사유'}
      />
      </FormTable>
    </>
  );
}

function OrganizationsPage({ model: m }: { model: OrganizationsModel }) {
  return (
    <div className="t-legacy-page admin-shell">

      <h1>조직 관리</h1>
      <section className="panel" aria-labelledby="organization-list-title">
        <h2 id="organization-list-title">조직 목록</h2>
        <form action="/admin/organizations" method="get" className="search-form" role="search">
          <div>
            <Field
              id="organization-query"
              label="조직 검색"
              name="q"
              type="search"
              value={m.q}
              placeholder="코드 또는 이름"
            />
          </div>
          <button type="submit">검색</button>
          <a href="/admin/organizations">전체 보기</a>
        </form>
        {m.organizations.length === 0 ? (
          <p className="notice">
            {m.q
              ? '검색 결과가 없습니다. 검색어를 확인해 주세요.'
              : '등록된 조직이 없습니다. 아래에서 첫 조직을 등록해 주세요.'}
          </p>
        ) : (
          <WorklogSheet
            label="조직 목록"
            rows={m.organizations}
            getRowId={(organization) => organization.id}
            scope="page"
            columns={[
              { field: 'code', headerName: '코드', minWidth: 140, pinned: 'left' },
              sheetColumn<OrganizationsModel['organizations'][number]>(
                { colId: 'name', headerName: '이름', valueGetter: ({ data }) => data?.name, minWidth: 170 },
                (organization) => <a href={`/admin/organizations/${organization.id}`}>{organization.name}</a>,
              ),
              { colId: 'parent', headerName: '상위 조직', valueGetter: ({ data }) => data?.parentId ? m.orgNames[data.parentId] : '없음', minWidth: 170 },
              { colId: 'active', headerName: '상태', valueGetter: ({ data }) => data?.active ? '활성' : '비활성', minWidth: 120 },
            ]}
          />
        )}
        <nav className="actions pagination" aria-label="조직 목록 페이지">
          {m.page > 0 && (
            <a href={queryHref('/admin/organizations', { q: m.q, page: m.page - 1 })}>
              이전 페이지
            </a>
          )}
          <span>{m.page + 1}페이지</span>
          {m.hasNext && (
            <a href={queryHref('/admin/organizations', { q: m.q, page: m.page + 1 })}>
              다음 페이지
            </a>
          )}
        </nav>
      </section>
      <section className="panel" aria-labelledby="organization-create-title">
        <h2 id="organization-create-title">조직 등록</h2>
        <ErrorNotice message={m.error} />
        <Form action="/admin/organizations" method="post" className="admin-form">
          <OrganizationFields form={m.form} options={m.options} />
          <div className="actions">
            <button type="submit">조직 등록</button>
          </div>
        </Form>
      </section>
    </div>
  );
}

function OrganizationPage({ model: m }: { model: OrganizationModel }) {
  const path = `/admin/organizations/${m.organization.id}`;
  return (
    <div className="t-legacy-page admin-shell">
      <PageHeader title={m.organization.name} actions={<a className="t-btn" href="/admin/organizations">조직 목록으로</a>} />
      <section className="wl-section" aria-labelledby="organization-change-title">
        <h2 id="organization-change-title">조직 정보 변경</h2>
        <ErrorNotice message={m.error} />
        <Form action={`${path}/changes`} method="post" className="wl-form">
          <OrganizationFields
            form={m.form}
            options={m.options}
            organizationId={m.organization.id}
          />
          <div className="wl-form-actions">
            <a className="t-btn" href={path}>입력 취소</a>
            <button type="submit" className="t-btn primary">조직 정보 저장</button>
          </div>
        </Form>
      </section>
    </div>
  );
}

function AccessPage({ model: m }: { model: AccessModel }) {
  const path = `/admin/employees/${m.employee.id}`;
  const account = m.account;
  const invited =
    typeof window !== 'undefined' && new URLSearchParams(window.location.search).has('invited');
  return (
    <div className="t-legacy-page admin-shell">
      <PageHeader title={m.employee.displayName} actions={<a className="t-btn" href={path}>사원 상세로</a>} />
      <ErrorNotice message={m.error} />
      {invited && (
        <p className="notice" role="status">
          로컬 모의 메일함에 초대를 전달했습니다. 실제 메일은 발송하지 않습니다.
        </p>
      )}
      {!m.eligibleEmployee && (
        <p className="notice">
          현재 재직 중인 활성 사원만 초대·역할 변경·승인자 배정을 할 수 있습니다.
        </p>
      )}
      <section className="wl-section" aria-labelledby="account-title">
        <h2 id="account-title">로그인 계정</h2>
        {account ? (
          <>
            <p>{account.email}</p>
            <p>
              {account.active
                ? '활성 계정'
                : account.credentialSet
                  ? '비활성 계정'
                  : '초대 대기 · 아직 로그인할 수 없음'}
            </p>
          </>
        ) : (
          <p>계정 없음 · 사원 등록과 별도로 초대해야 합니다.</p>
        )}
        <p className="hint">
          이메일 변경과 비밀번호 재설정은 후속입니다. 비밀번호·초대코드는 관리 화면에서 조회하지
          않습니다.
        </p>
        {m.eligibleEmployee && (!account || (!account.active && !account.credentialSet)) && (
          <>
            <h3>{account ? '초대 재발급' : '계정 초대'}</h3>
            <p className="hint">
              초대는24시간 유효합니다. 재발급하면 기존 초대를 사용할 수 없습니다.
            </p>
            {!m.localDelivery && (
              <p className="notice">
                초대 전달 채널이 준비되지 않았습니다. 로컬 모의 경로에서만 검증할 수 있습니다.
              </p>
            )}
            <Form action={`${path}/invitations`} method="post" className="wl-form">
              <CommandFields form={m.invitationForm} />
              <FormTable>
                <Field
                  id="invitation-email"
                  label="초대 이메일"
                  name="email"
                  type="email"
                  maxLength={254}
                  value={m.invitationForm.email}
                  readOnly={Boolean(account)}
                  required
                />
                <Reason id="invitation-reason" form={m.invitationForm} label="초대 사유" />
              </FormTable>
              <div className="wl-form-actions">
                <button type="submit" className="t-btn primary" disabled={!m.localDelivery}>
                  초대 발급
                </button>
              </div>
            </Form>
          </>
        )}
      </section>
      <section className="wl-section" aria-labelledby="roles-title">
        <h2 id="roles-title">역할</h2>
        <p className="hint">
          공통은 기본 역할입니다. 관리자·대표와 결재 담당 기능은 각각 부여합니다. 대표는 회사
          현황을 조회하며, 지정된 승인자만 배정된 신청을 처리합니다. 역할이 바뀌면 기존 로그인이 종료됩니다.
        </p>
        {(!account || !account.active || !account.credentialSet) && (
          <p>초대 수락 후 활성 계정의 역할을 변경할 수 있습니다.</p>
        )}
        {m.eligibleEmployee && account?.active && account.credentialSet && (
          <Form action={`${path}/roles`} method="post" className="wl-form">
            <CommandFields form={m.rolesForm} />
            <Hidden name="roles" value="EMPLOYEE" />
            <FormTable>
              <FormRow label="역할">
                <p>공통 (EMPLOYEE) · 필수</p>
                <Check
                  name="roles"
                  label="승인자 (APPROVER)"
                  value="APPROVER"
                  checked={m.rolesForm.roles?.includes('APPROVER') ?? false}
                />
                <Check
                  name="roles"
                  label="근태 담당자 (ATTENDANCE_ADMIN)"
                  value="ATTENDANCE_ADMIN"
                  checked={m.rolesForm.roles?.includes('ATTENDANCE_ADMIN') ?? false}
                />
                <Check
                  name="roles"
                  label="대표 (COMPANY_REPRESENTATIVE)"
                  value="COMPANY_REPRESENTATIVE"
                  checked={m.rolesForm.roles?.includes('COMPANY_REPRESENTATIVE') ?? false}
                />
              </FormRow>
              <p className="hint">
                마지막 유효 담당자는 다른 담당자를 연결하기 전 회수할 수 없습니다.
              </p>
              <Reason id="roles-reason" form={m.rolesForm} label="역할 변경 사유" />
            </FormTable>
            <div className="wl-form-actions">
              <button type="submit" className="t-btn primary">역할 저장</button>
            </div>
          </Form>
        )}
      </section>
      <section className="wl-section" aria-labelledby="approver-title">
        <h2 id="approver-title">지정 승인자</h2>
        <p className="notice">
          {m.currentAssignment
            ? `${m.currentAssignment.approverName} · ${m.currentAssignment.eligible ? '현재 유효' : '재지정 필요'}`
            : '지정 승인자가 없습니다.'}
        </p>
        <p className="hint">
          자기 지정은 허용하지 않습니다. 변경은 지금부터 새 신청에 적용되며 기존 대기 신청은 자동
          이동하지 않습니다. 미래 예약·신청 재할당은 후속입니다.
        </p>
        {m.eligibleEmployee && (
          <Form action="/admin/approver-assignments" method="post" className="wl-form">
            <Hidden name="employeeId" value={m.employee.id} />
            <CommandFields form={m.approverForm} />
            <FormTable>
              <SelectField
                id="approver-id"
                label="새 승인자"
                name="approverEmployeeId"
                value={m.approverForm.approverEmployeeId ?? ''}
              >
                <option value="">승인자 없음 (명시 해제)</option>
                {m.selectedApproverMissing && (
                  <option value={m.approverForm.approverEmployeeId ?? ''}>
                    현재 선택값 (재지정 필요)
                  </option>
                )}
                {m.candidates
                  .filter((candidate) => candidate.id !== m.employee.id)
                  .map((candidate) => (
                    <option key={candidate.id} value={candidate.id}>
                      {candidate.displayName}
                    </option>
                  ))}
              </SelectField>
              <Field
                id="approver-from"
                label="적용일"
                name="validFrom"
                type="date"
                readOnly
                required
                value={m.approverForm.validFrom}
              />
              <p className="hint">
                오늘 즉시 지정·교체·해제만 지원합니다. 당일 변경 전 관계도 이력으로 보존합니다.
              </p>
              <Reason id="approver-reason" form={m.approverForm} label="승인자 변경 사유" />
            </FormTable>
            <div className="wl-form-actions">
              <a className="t-btn" href={`${path}/access`}>최신 내용 다시 조회</a>
              <button type="submit" className="t-btn primary">승인자 저장</button>
            </div>
          </Form>
        )}
        {m.assignments.length > 0 && (
          <WorklogSheet
            title="승인자 배정 이력"
            label="승인자 배정 이력"
            rows={m.assignments}
            getRowId={(assignment) => assignment.id}
            scope="all"
            columns={[
              { field: 'approverName', headerName: '승인자', minWidth: 160, pinned: 'left' },
              { colId: 'validFrom', headerName: '시작일', valueGetter: ({ data }) => data && fmtDate(data.validFrom), minWidth: 150 },
              { colId: 'validTo', headerName: '종료일 (제외)', valueGetter: ({ data }) => data?.validTo ? fmtDate(data.validTo) : '미지정', minWidth: 160 },
              { colId: 'status', headerName: '상태', valueGetter: ({ data }) => data && (data.supersededAt ? '당일 교체·해제' : data.validTo ? '종료' : data.eligible ? '현재 유효' : '재지정 필요'), minWidth: 170 },
              { field: 'reason', headerName: '사유', minWidth: 240 },
            ]}
          />
        )}
      </section>
    </div>
  );
}

const weekdays = ['월', '화', '수', '목', '금', '토', '일'];

function ScheduleFields({
  form,
  today,
  template,
}: {
  form: TemplateForm;
  today: string;
  template?: Template;
}) {
  return (
    <>
      <CommandFields form={form} />
      <FormTable>
        <Field
          id="template-code"
          label="템플릿 코드"
          name="code"
          maxLength={40}
          value={form.code}
          readOnly={Boolean(template)}
          required
        />
        <Field
          id="template-name"
          label="이름"
          name="name"
          maxLength={120}
          value={form.name}
          readOnly={Boolean(template)}
          required
        />
        <Field
          id="valid-from"
          label="버전 사용 시작일"
          name="validFrom"
          type="date"
          value={form.validFrom}
          min={today}
          required
        />
        <p className="hint">
          당일 시작·종료하는 하루 한 구간입니다. 예정 휴게는 실제 휴게 증거가 아닙니다. 복수 휴게는
          12:00-13:00,15:00-15:10처럼 입력하세요.
        </p>
        <FormRow label="요일별 근무">
          <div className="table-scroll" role="region" aria-label="요일별 근무와 예정 휴게" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">요일</th>
                  <th scope="col">근로일</th>
                  <th scope="col">시작</th>
                  <th scope="col">종료</th>
                  <th scope="col">예정 휴게</th>
                </tr>
              </thead>
              <tbody>
                {form.days.map((day, index) => (
                  <tr key={day.weekday}>
                    <td>
                      {weekdays[day.weekday - 1]}
                      <Hidden name={`days[${index}].weekday`} value={day.weekday} />
                    </td>
                    <td>
                      <Hidden name={`_days[${index}].workday`} value="on" />
                      <input
                        type="checkbox"
                        name={`days[${index}].workday`}
                        value="true"
                        defaultChecked={day.workday}
                        aria-label={`${weekdays[day.weekday - 1]}요일 근로일`}
                      />
                    </td>
                    <td>
                      <input
                        type="text"
                        name={`days[${index}].startTime`}
                        defaultValue={day.startTime}
                        placeholder="09:00"
                        maxLength={12}
                        aria-label={`${weekdays[day.weekday - 1]}요일 시작`}
                      />
                    </td>
                    <td>
                      <input
                        type="text"
                        name={`days[${index}].endTime`}
                        defaultValue={day.endTime}
                        placeholder="18:00"
                        maxLength={12}
                        aria-label={`${weekdays[day.weekday - 1]}요일 종료`}
                      />
                    </td>
                    <td>
                      <input
                        type="text"
                        name={`days[${index}].breaks`}
                        defaultValue={day.breaks}
                        maxLength={1000}
                        aria-label={`${weekdays[day.weekday - 1]}요일 예정 휴게`}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </FormRow>
        <Reason id="schedule-reason" form={form} label="등록·변경 사유" />
        <p className="hint">
          비근로일은 시각·휴게를 모두 비우세요. 법정 휴게·근로시간 적합성을 자동 보증하지 않습니다.
        </p>
      </FormTable>
      <div className="wl-form-actions">
        <button type="submit" className="t-btn primary">불변 버전 저장</button>
      </div>
    </>
  );
}

function ScheduleListPage({ model: m }: { model: ScheduleListModel }) {
  return (
    <div className="t-legacy-page admin-shell">
      <PageHeader title="고정 근무 템플릿" help="여러 템플릿을 만들고 사원별 적용일로 배정합니다. 과거 배정은 당시 불변 버전을 사용합니다." />
      <ErrorNotice message={m.error} />
      <section className="wl-section">
        <h2>등록된 템플릿</h2>
        {m.templates.length === 0 && <p>등록된 템플릿이 없습니다.</p>}
        <ul className="wl-rows">
          {m.templates.map((item) => (
            <li key={item.id}>
              <a href={`/admin/schedules/${item.id}`}>
                {item.code} · {item.name}
              </a>
            </li>
          ))}
        </ul>
      </section>
      <section className="wl-section">
        <h2>새 템플릿</h2>
        <Form action="/admin/schedules" method="post" className="wl-form">
          <ScheduleFields form={m.form} today={m.today} />
        </Form>
      </section>
    </div>
  );
}

function ScheduleDetailPage({ model: m }: { model: ScheduleDetailModel }) {
  return (
    <div className="t-legacy-page admin-shell">
      <PageHeader title={m.template.name} help="근무 시간 기준: Asia/Seoul (서울)" actions={<a className="t-btn" href="/admin/schedules">템플릿 목록</a>} />
      <ErrorNotice message={m.error} />
      <section className="wl-section">
        <h2>불변 버전 이력</h2>
        {m.versions.map((version) => (
          <article key={version.id}>
            <h3>
              버전 {version.revision} · 사용 시작 {fmtDate(version.validFrom)}
            </h3>
            <p>{version.reason}</p>
            <WorklogSheet
              label={`버전 ${version.revision} 요일별 근무·휴게`}
              rows={m.versionDays[version.id] ?? []}
              getRowId={(day) => `${version.id}:${day.weekday}`}
              scope="all"
              columns={[
                { colId: 'weekday', headerName: '요일', valueGetter: ({ data }) => data && weekdays[data.weekday - 1], minWidth: 100, pinned: 'left', comparator: (_a, _b, a, b) => (a.data?.weekday ?? 0) - (b.data?.weekday ?? 0) },
                { colId: 'workday', headerName: '구분', valueGetter: ({ data }) => data?.workday ? '근로일' : '비근로일', minWidth: 130 },
                { colId: 'startTime', headerName: '시작', valueGetter: ({ data }) => data?.workday ? fmtTime(data.startTime) : '해당 없음', minWidth: 130 },
                { colId: 'endTime', headerName: '종료', valueGetter: ({ data }) => data?.workday ? fmtTime(data.endTime) : '해당 없음', minWidth: 130 },
                { colId: 'breaks', headerName: '예정 휴게', valueGetter: ({ data }) => data?.breaks.map((pause) => `${fmtTime(pause.startTime)}–${fmtTime(pause.endTime)}`).join(' '), minWidth: 220 },
              ]}
            />
          </article>
        ))}
      </section>
      <section className="wl-section">
        <h2>새 버전 추가</h2>
        <p className="hint">기존 버전과 배정은 유지합니다. 사원별 새 적용일 배정이 필요합니다.</p>
        <Form
          action={`/admin/schedules/${m.template.id}/versions`}
          method="post"
          className="wl-form"
        >
          <ScheduleFields form={m.form} today={m.today} template={m.template} />
        </Form>
      </section>
    </div>
  );
}

function ScheduleAssignmentPage({ model: m }: { model: ScheduleAssignmentModel }) {
  return (
    <div className="t-legacy-page admin-shell">
      <PageHeader title={`${m.employee.displayName} · 근무 배정`} actions={<a className="t-btn" href={`/admin/employees/${m.employee.id}`}>사원 상세</a>} />
      <ErrorNotice message={m.error} />
      <section className="wl-section">
        <h2>배정 이력</h2>
        {m.assignments.length === 0 ? (
          <p>배정이 없습니다. 일정 누락 날짜를 정상 근로일로 간주하지 않습니다.</p>
        ) : (
          <WorklogSheet
            label="근무 배정 이력"
            rows={m.assignments}
            getRowId={(assignment) => assignment.id}
            scope="all"
            columns={[
              { field: 'templateName', headerName: '템플릿', minWidth: 170, pinned: 'left' },
              { field: 'revision', headerName: '버전', minWidth: 100, cellClass: 'w-sheet-number' },
              { colId: 'validFrom', headerName: '시작 (포함)', valueGetter: ({ data }) => data && fmtDate(data.validFrom), minWidth: 150 },
              { colId: 'validTo', headerName: '종료 (제외)', valueGetter: ({ data }) => data?.validTo ? fmtDate(data.validTo) : '종료 없음', minWidth: 150 },
              { field: 'reason', headerName: '사유', minWidth: 240 },
            ]}
          />
        )}
      </section>
      <section className="wl-section">
        <h2>적용일 배정</h2>
        <p className="hint">
          오늘 이후의 재직 기간 안에서 마지막 시작일보다 늦은 날을 선택하세요. 과거 정정·같은 날
          교체·예약 취소는 이번 단위에서 제공하지 않습니다.
        </p>
        <Form
          action={`/admin/employees/${m.employee.id}/schedule`}
          method="post"
          className="wl-form"
        >
          <CommandFields form={m.form} />
          <FormTable>
            <SelectField
              id="schedule-version"
              label="템플릿 버전"
              name="scheduleVersionId"
              value={m.form.scheduleVersionId ?? ''}
              required
            >
              <option value="">선택하세요</option>
              {m.versions.map((version) => (
                <option key={version.id} value={version.id}>
                  {m.templateNames[version.templateId]} / v{version.revision} /{' '}
                  {fmtDate(version.validFrom)}
                </option>
              ))}
            </SelectField>
            <Field
              id="schedule-assignment-from"
              label="적용 시작일"
              name="validFrom"
              type="date"
              min={m.today}
              value={m.form.validFrom}
              required
            />
            <Reason id="schedule-assignment-reason" form={m.form} label="배정 사유" />
          </FormTable>
          <div className="wl-form-actions">
            <button type="submit" className="t-btn primary">근무 배정 저장</button>
          </div>
        </Form>
      </section>
    </div>
  );
}

function NetworksPage({ model: m }: { model: NetworksModel }) {
  return (
    <div className="t-legacy-page admin-shell">
      <PageHeader title="허용 IP 네트워크" help={<>
        <p>시각 기준: Asia/Seoul (서울)</p>
        <p>IP 일치는 현장 근로나 본인 존재를 보증하지 않습니다. 실제 접속 주소만 검증하며 전달 헤더로 우회할 수 없습니다.</p>
      </>} actions={<a className="t-btn" href="/admin/calendar">휴일 캘린더</a>} />
      <ErrorNotice message={m.error} />
      <section className="wl-section">
        <h2>허용망 이력</h2>
        {m.networks.length === 0 && <p>등록된 허용망이 없습니다.</p>}
        <ul className="wl-rows">
          {m.networks.map((network) => (
            <li key={network.id}>
              <div className="wl-rows-main">
                <h3>{network.label} · {network.network}</h3>
                <p>시작 {fmtDateTime(network.validFrom)} · {network.validTo ? `종료 ${fmtDateTime(network.validTo)}` : '현재 유효'}</p>
              </div>
              {!network.validTo && (
                <Form action={`/admin/networks/${network.id}/close`} method="post" className="wl-form wl-inline-form">
                  <Hidden name="commandId" value={m.pageCommand} />
                  <Hidden name="expectedVersion" value={network.version} />
                  <Field id={`close-${network.id}`} label="종료 사유" name="reason" maxLength={500} required />
                  <button type="submit" className="t-btn">허용 종료</button>
                </Form>
              )}
            </li>
          ))}
        </ul>
      </section>
      <section className="wl-section">
        <h2>허용망 추가</h2>
        <Form action="/admin/networks/changes" method="post" className="wl-form">
          <Hidden name="commandId" value={m.form.commandId} />
          <FormTable>
            <Field id="network" label="IPv4/IPv6 CIDR" name="network" value={m.form.network} placeholder="192.0.2.0/24" required maxLength={80} />
            <Field id="network-label" label="명칭" name="label" value={m.form.label} maxLength={120} required />
            <FormRow label="위험 검토">
              <Check name="wideConfirmed" label="광범위 허용 위험을 검토했습니다" checked={m.form.wideConfirmed} />
            </FormRow>
            <Reason id="network-reason" form={m.form} label="등록 사유" />
          </FormTable>
          <div className="wl-form-actions">
            <button type="submit" className="t-btn primary">허용망 저장</button>
          </div>
        </Form>
      </section>
    </div>
  );
}

function CalendarPage({ model: m }: { model: CalendarModel }) {
  return (
    <div className="t-legacy-page admin-shell">
      <PageHeader title="휴일 캘린더" help="담당자가 근거를 확인한 날짜만 명시합니다. 미등록 날짜는 미확인입니다. 요일만으로 법정 휴일을 추정하지 않습니다."
        actions={<a className="t-btn" href="/admin/networks">허용 네트워크</a>} />
      <ErrorNotice message={m.error} />
      <section className="wl-section">
        <h2>불변 버전</h2>
        {m.calendars.length === 0 && <p>등록된 캘린더 버전이 없습니다.</p>}
        <ul>
          {m.calendars.map((version) => (
            <li key={version.id}>
              v{version.revision} · {fmtDate(version.validFrom)} ~ {fmtDate(version.validTo)} (종료
              제외) · {version.reason}
            </li>
          ))}
        </ul>
      </section>
      <section className="wl-section">
        <h2>검토한 기간 등록</h2>
        <p className="hint">
          아래 기간 전체를 일반일로 등록하고 예외 날짜를 지정합니다. 기존 기간과 중첩할 수 없습니다.
        </p>
        <Form action="/admin/calendar/versions" method="post" className="wl-form">
          <Hidden name="commandId" value={m.form.commandId} />
          <FormTable>
            <Field id="calendar-from" label="시작일 (포함)" name="from" type="date" value={m.form.from} required />
            <Field id="calendar-to" label="종료일 (포함)" name="to" type="date" value={m.form.to} required />
            <TextAreaField id="calendar-basis" label="기간 전체의 검토 근거" name="basis" value={m.form.basis} maxLength={500} required />
            <FormRow label="검토 확인" required>
              <Check name="reviewed" label="기간의 일반일·비근로일·휴일 근거를 모두 검토했습니다" checked={m.form.reviewed} required />
            </FormRow>
            <TextAreaField id="calendar-exceptions" label="예외 날짜 (한 줄에 날짜|종류|이름|근거)" name="exceptions" rows={5}
              value={m.form.exceptions} placeholder="2026-12-25|HOLIDAY|공휴일|확인한 공식 근거" />
            <p className="hint">
              종류: WORKDAY(일반일), NON_WORKDAY(명시 비근로일), HOLIDAY(근거 있는 휴일). 주간
              템플릿의 비근로일은 별도로 유지됩니다.
            </p>
            <Reason id="calendar-reason" form={m.form} label="등록 사유" />
          </FormTable>
          <div className="wl-form-actions">
            <button type="submit" className="t-btn primary">캘린더 버전 저장</button>
          </div>
        </Form>
      </section>
    </div>
  );
}

export function AdminPages({ page, data }: PageProps): React.ReactNode | null {
  if ((page === 'org/employees' && data.employeeSheet) ||
      (page === 'org/organizations' && data.organizationSheet)) return null;
  switch (page) {
    case 'org/employees':
      return <EmployeesPage model={data as unknown as EmployeesModel} />;
    case 'org/employee':
      return <EmployeePage model={data as unknown as EmployeeModel} />;
    case 'org/organizations':
      return <OrganizationsPage model={data as unknown as OrganizationsModel} />;
    case 'org/organization':
      return <OrganizationPage model={data as unknown as OrganizationModel} />;
    case 'org/access':
      return <AccessPage model={data as unknown as AccessModel} />;
    case 'schedule/list':
      return <ScheduleListPage model={data as unknown as ScheduleListModel} />;
    case 'schedule/detail':
      return <ScheduleDetailPage model={data as unknown as ScheduleDetailModel} />;
    case 'schedule/assignment':
      return <ScheduleAssignmentPage model={data as unknown as ScheduleAssignmentModel} />;
    case 'schedule/form': {
      const m = data as unknown as {
        form: TemplateForm;
        today: string;
        template?: Template;
        error?: string | null;
      };
      return (
        <section className="panel">
          <h1>근무 템플릿</h1>
          <ErrorNotice message={m.error} />
          <Form
            action={m.template ? `/admin/schedules/${m.template.id}/versions` : '/admin/schedules'}
            method="post"
            className="admin-form"
          >
            <ScheduleFields form={m.form} today={m.today} template={m.template} />
          </Form>
        </section>
      );
    }
    case 'clock/networks':
      return <NetworksPage model={data as unknown as NetworksModel} />;
    case 'clock/calendar':
      return <CalendarPage model={data as unknown as CalendarModel} />;
    default:
      return null;
  }
}
