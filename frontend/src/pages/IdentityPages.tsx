import type React from 'react';
import { useState } from 'react';
import type { PageProps } from '../types';
import { duration, Field, Form, fmtDate, fmtDateTime, SelectField } from '../ui';
import { WorklogSheet, sheetColumn } from '../components/WorklogSheet';
import { PageHeader } from '../components/PageHeader';
import { SectionHeader } from '../components/SectionHeader';
import { PersonalDetailsTabs, type PersonalDetailsModel } from './PersonalDetails';

interface Profile {
  employeeNo: string;
  employeeName: string;
  companyName: string;
  email: string;
  joinedOn: string;
  leftOn: string | null;
  organizationName: string | null;
  organizationHeadName?: string | null;
  organizationHeadEligible?: boolean;
  organizationHeadApproverEligible?: boolean;
  scheduleName: string | null;
  approverName: string | null;
  approverEligible: boolean;
  approvalPreview?: {
    source: string;
    routeName: string | null;
    approverName: string | null;
    eligible: boolean;
    message: string | null;
  };
  roles: string[];
  asOf: string;
}
interface AccessList {
  accessRows: {
    employeeId: string;
    employeeNo: string;
    employeeName: string;
    roles: string[];
    active: boolean;
  }[];
  q: string;
  page: number;
  hasNext: boolean;
}
interface Organization {
  id: string;
  code: string;
  name: string;
}
interface Employee {
  id: string;
  employeeNo: string;
  employeeName: string;
  organizationName: string | null;
}
interface CompanyAttendance {
  month: string;
  asOf: string;
  organizationId: string | null;
  employeeId: string | null;
  q: string;
  page: number;
  hasNext: boolean;
  organizations: Organization[];
  employees: Employee[];
  selectedEmployee: Employee | null;
  days: {
    workDate: string;
    scheduledMillis: number | null;
    actualWorkMillis: number | null;
    leaveDays: number | null;
    status: string;
    current: boolean;
  }[];
}
interface CompanyRequests {
  year: number;
  asOf: string;
  organizationId: string | null;
  status: string;
  page: number;
  hasNext: boolean;
  organizations: Organization[];
  requests: {
    id: string;
    employeeNo: string;
    employeeName: string;
    organizationName: string | null;
    kind: string;
    status: string;
    fromDate: string;
    toDate: string;
    quantityDays: number;
    effectStatus: string;
    submittedAt: string;
  }[];
}

const roleNames: Record<string, string> = {
  EMPLOYEE: '공통',
  ATTENDANCE_ADMIN: '관리자',
  COMPANY_REPRESENTATIVE: '대표',
  APPROVER: '결재 담당 가능',
};
export const formatRoles = (roles: string[]) =>
  Object.entries(roleNames)
    .filter(([role]) => roles.includes(role))
    .map(([, label]) => label)
    .join(' · ') || '역할 없음';
const statuses: Record<string, string> = {
  PENDING: '대기',
  APPROVED: '승인',
  REJECTED: '반려',
  WITHDRAWN: '철회',
  CALCULATED: '산정됨',
  REVIEW_REQUIRED: '검토 필요',
  NO_SCHEDULED_WORK: '예정 근무 없음',
};
const kinds: Record<string, string> = {
  LEAVE_DAY: '종일 연차',
  CANCEL_LEAVE: '연차 취소',
  ATTENDANCE_CORRECTION: '기록 정정',
};
const effects: Record<string, string> = {
  EFFECTIVE: '유효',
  NONE: '효력 없음',
  CANCELED: '취소됨',
  SUPERSEDED: '대체됨',
};
type Query = Record<string, string | number | null>;
function queryHref(path: string, values: Query) {
  const query = new URLSearchParams();
  for (const [name, value] of Object.entries(values))
    if (value != null) query.set(name, String(value));
  return `${path}?${query}`;
}
function Pagination({ path, values, page, hasNext }: {
  path: string;
  values: Query;
  page: number;
  hasNext: boolean;
}) {
  return (
    <nav className="actions pagination" aria-label="목록 페이지">
      {page > 0 && <a href={queryHref(path, { ...values, page: page - 1 })}>이전</a>}
      <span>{page + 1}페이지</span>
      {hasNext && <a href={queryHref(path, { ...values, page: page + 1 })}>다음</a>}
    </nav>
  );
}
function OrganizationFilter({ organizations, organizationId }: {
  organizations: Organization[];
  organizationId: string | null;
}) {
  return (
    <SelectField label="현재 조직 (서울 오늘)" name="organizationId" value={organizationId}>
      <option value="">전체 조직</option>
      {organizations.map((organization) => (
        <option key={organization.id} value={organization.id}>
          {organization.code} · {organization.name}
        </option>
      ))}
    </SelectField>
  );
}
function ProfilePage({ profile: p, hasHeader, details, birthDate }: { profile: Profile; hasHeader: boolean; details?: PersonalDetailsModel; birthDate?: string | null }) {
  const entries = [
    ['이름', p.employeeName],
    ['사번', p.employeeNo],
    ['회사', p.companyName],
    ['본인 계정 이메일', p.email],
    ['입사일', fmtDate(p.joinedOn)],
    ['마지막 재직일', p.leftOn ? fmtDate(p.leftOn) : '미정'],
    ['현재 소속', p.organizationName ?? '미배정'],
    ...(p.organizationHeadName !== undefined ? [
      ['현재 조직장', p.organizationHeadName ? `${p.organizationHeadName}${p.organizationHeadEligible ? '' : ' · 재지정 필요'}` : '조직장 미배정'],
      ['조직장 결재 자격', p.organizationHeadName && p.organizationHeadApproverEligible ? '현재 유효' : '조직장 결재 자격 없음'],
    ] : []),
    ['적용 근무', p.scheduleName ?? '미배정'],
    ['현재 결재 담당', p.approverName
      ? `${p.approverName} · ${p.approverEligible ? '현재 유효' : '재지정 필요'}`
      : '미배정'],
    ...(p.approvalPreview ? [
      ['적용 결재 경로', `${({ EMPLOYEE_ROUTE: '사원별 결재선', LEGACY: '기존 지정 승인자', ORGANIZATION_ROUTE: '조직별 결재선', NONE: '미배정' } as Record<string, string>)[p.approvalPreview.source] ?? '확인 필요'}${p.approvalPreview.routeName ? ` · ${p.approvalPreview.routeName}` : ''}`],
      ...(!p.approvalPreview.eligible && p.approvalPreview.message ? [['신청 전 확인', p.approvalPreview.message]] : []),
    ] : []),
    ['현재 역할·기능', formatRoles(p.roles)],
  ];
  return (
    <>
      <PageHeader title="내 정보" help="현재 계정과 업무 배정 정보를 확인하세요." />
      {details && <PersonalDetailsTabs model={details} identity={{ employeeName: p.employeeName, joinedOn: p.joinedOn, birthDate }} />}
      <section className="wl-section t-profile-details" aria-label="본인 정보">
        <div className="t-personal-panel-heading"><div><h2>계정과 업무 배정</h2><p>현재 적용 중인 근무와 결재 정보를 확인하세요.</p></div></div>
        <p className="t-profile-asof">조회 기준 (서울): {fmtDateTime(p.asOf)}</p>
        <dl className="wl-info-grid">
          {entries.filter(([label]) => !hasHeader || !['이름', '사번', '회사', '입사일', '현재 소속'].includes(label)).map(([label, value]) => (
            <div key={label}><dt>{label}</dt><dd>{value}</dd></div>
          ))}
        </dl>
        <p className="t-profile-footnote">
          공통 업무와 명시적으로 부여된 역할을 함께 사용합니다. 결재 담당 기능은 실제 배정된 신청에 적용됩니다.
        </p>
      </section>
    </>
  );
}
function AccessListPage({ model: m }: { model: AccessList }) {
  return (
    <>
      <PageHeader title="권한 관리" help={<>
        <p>계정별 역할을 확인하고 기존 관리 화면에서 변경하세요.</p>
        <p>공통은 필수 역할입니다. 관리자·대표와 결재 담당 기능은 각각 부여하며 함께 보유할 수 있습니다.</p>
      </>} />
      <Form action="/admin/access" method="get" className="wl-querybar">
        <Field label="사번·이름 검색" name="q" value={m.q} maxLength={120} />
        <div className="wl-querybar-actions"><button type="submit" className="t-btn wl-query-submit">조회</button></div>
      </Form>
      <section className="wl-section">
        <WorklogSheet
          label="계정별 권한"
          title="계정 목록"
          rows={m.accessRows}
          getRowId={(row) => row.employeeId}
          scope="page"
          emptyMessage="조회된 계정이 없습니다."
          pagination={<Pagination path="/admin/access" values={{ q: m.q }} page={m.page} hasNext={m.hasNext} />}
          columns={[
            { field: 'employeeNo', headerName: '사번', pinned: 'left', minWidth: 120 },
            { field: 'employeeName', headerName: '이름', minWidth: 130 },
            { colId: 'roles', headerName: '역할·기능', valueGetter: ({ data }) => data && formatRoles(data.roles), minWidth: 250 },
            { colId: 'active', headerName: '계정 상태', valueGetter: ({ data }) => data?.active ? '활성' : '비활성', minWidth: 120 },
            sheetColumn<AccessList['accessRows'][number]>(
              { colId: 'manage', headerName: '관리', sortable: false, filter: false, minWidth: 120 },
              (row) => <a href={`/admin/employees/${row.employeeId}/access`} aria-label={`${row.employeeName} 권한 관리`}>권한 관리</a>,
            ),
          ]}
        />
      </section>
    </>
  );
}
function CompanyAttendancePage({ model: m }: { model: CompanyAttendance }) {
  const [employeeId, setEmployeeId] = useState(m.employeeId ?? '');
  const values = { month: m.month, organizationId: m.organizationId, employeeId: m.employeeId, q: m.q };
  return (
    <>
      <PageHeader title="회사 근무현황" help={<>
        <p>사원을 선택해 해당 월의 일별 근무 결과를 확인하세요.</p>
        <p>조직은 서울 기준 오늘의 소속으로 조회합니다.</p>
        <p>선택 사원의 검토용 결과입니다. 미확정·오래된 결과는 0시간으로 처리하지 않습니다.</p>
      </>} />
      <Form
        action="/company/attendance"
        method="get"
        className="wl-querybar"
        onChange={(event) => {
          const name = event.target.getAttribute('name');
          if (name === 'organizationId' || name === 'q') setEmployeeId('');
        }}
      >
        <input type="hidden" name="employeeId" value={employeeId} />
        <Field label="기준 월 (서울)" name="month" type="month" value={m.month} required />
        <OrganizationFilter organizations={m.organizations} organizationId={m.organizationId} />
        <Field label="사번·이름 검색" name="q" value={m.q} maxLength={100} />
        <div className="wl-querybar-actions"><button type="submit" className="t-btn wl-query-submit">조회</button></div>
      </Form>
      <p className="t-subtle">조회 기준 (서울): {fmtDateTime(m.asOf)}</p>
      <section className="wl-section">
        <WorklogSheet
          label="회사 현황 대상 사원"
          title="대상 사원"
          rows={m.employees}
          getRowId={(employee) => employee.id}
          scope="page"
          emptyMessage="조회할 사원이 없습니다."
          pagination={<Pagination path="/company/attendance" values={values} page={m.page} hasNext={m.hasNext} />}
          columns={[
            sheetColumn<Employee>(
              { colId: 'employee', headerName: '사번·이름', valueGetter: ({ data }) => data && `${data.employeeNo} · ${data.employeeName}`, minWidth: 200, pinned: 'left' },
              (employee) => <a
                href={queryHref('/company/attendance', { ...values, employeeId: employee.id, page: m.page })}
                aria-current={m.selectedEmployee?.id === employee.id ? 'true' : undefined}
              >{employee.employeeNo} · {employee.employeeName}</a>,
            ),
            { colId: 'organizationName', headerName: '현재 소속', valueGetter: ({ data }) => data?.organizationName ?? '미배정', minWidth: 160 },
          ]}
        />
      </section>
      <section className="wl-section">
        {!m.selectedEmployee ? <>
          <SectionHeader title="일별 현황" />
          <p>사원을 선택하면 해당 월의 일별 현황을 볼 수 있습니다.</p>
        </> : (
          <WorklogSheet
            label="사원 일별 근무현황"
            title={`${m.selectedEmployee.employeeName} · ${m.month} 일별 현황`}
            rows={m.days}
            getRowId={(day) => day.workDate}
            scope="all"
            emptyMessage="조회된 일별 현황이 없습니다."
            columns={[
              { colId: 'workDate', headerName: '근무일', valueGetter: ({ data }) => data && fmtDate(data.workDate), minWidth: 140, pinned: 'left' },
              { colId: 'status', headerName: '상태·최신성', valueGetter: ({ data }) => data && `${statuses[data.status] ?? data.status} · ${data.current ? '최신' : '미산정·재집계 필요'}`, minWidth: 250 },
              {
                colId: 'scheduledMillis', headerName: '예정 근무 (시:분:초.밀리초)',
                valueGetter: ({ data }) => data?.current ? data.scheduledMillis : null,
                valueFormatter: ({ value }) => duration(value),
                filterValueGetter: ({ data }) => duration(data?.current ? data.scheduledMillis : null),
                getQuickFilterText: ({ value }) => duration(value), minWidth: 230, cellClass: 'w-sheet-number',
              },
              {
                colId: 'actualWorkMillis', headerName: '실근로 (시:분:초.밀리초)',
                valueGetter: ({ data }) => data?.current ? data.actualWorkMillis : null,
                valueFormatter: ({ value }) => duration(value),
                filterValueGetter: ({ data }) => duration(data?.current ? data.actualWorkMillis : null),
                getQuickFilterText: ({ value }) => duration(value), minWidth: 220, cellClass: 'w-sheet-number',
              },
              { colId: 'leaveDays', headerName: '휴가 (DAY)', valueGetter: ({ data }) => data?.current && data.leaveDays != null ? data.leaveDays : '미확정', minWidth: 130, cellClass: 'w-sheet-number' },
            ]}
          />
        )}
      </section>
    </>
  );
}
function CompanyRequestsPage({ model: m }: { model: CompanyRequests }) {
  return (
    <>
      <PageHeader title="회사 신청 현황" help={<>
        <p>조회연도의 회사 신청 유형과 처리 상태를 확인하세요.</p>
        <p>조직은 서울 기준 오늘의 소속입니다. 신청일량은 조회연도에 포함된 일량이며 취소 신청은 원신청을 기준으로 합니다.</p>
      </>} />
      <Form action="/company/requests" method="get" className="wl-querybar">
        <Field label="조회연도 (서울)" name="year" type="number" value={m.year} min={1} max={9999} required />
        <OrganizationFilter organizations={m.organizations} organizationId={m.organizationId} />
        <SelectField label="신청 상태" name="status" value={m.status}>
          <option value="">전체 상태</option>
          {['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'].map((status) => (
            <option key={status} value={status}>{statuses[status]}</option>
          ))}
        </SelectField>
        <div className="wl-querybar-actions"><button type="submit" className="t-btn wl-query-submit">조회</button></div>
      </Form>
      <p className="t-subtle">조회 기준 (서울): {fmtDateTime(m.asOf)}</p>
      <section className="wl-section">
        <WorklogSheet
          label="회사 신청 목록"
          title="신청 목록"
          rows={m.requests}
          getRowId={(request) => request.id}
          scope="page"
          emptyMessage="조회된 신청이 없습니다."
          pagination={<Pagination
            path="/company/requests"
            values={{ year: m.year, organizationId: m.organizationId, status: m.status }}
            page={m.page}
            hasNext={m.hasNext}
          />}
          columns={[
            { colId: 'employee', headerName: '사번·이름', valueGetter: ({ data }) => data && `${data.employeeNo} · ${data.employeeName}`, minWidth: 200, pinned: 'left' },
            { colId: 'organizationName', headerName: '현재 소속', valueGetter: ({ data }) => data?.organizationName ?? '미배정', minWidth: 150 },
            { colId: 'kind', headerName: '종류', valueGetter: ({ data }) => data && (kinds[data.kind] ?? data.kind), minWidth: 140 },
            { colId: 'period', headerName: '기간', valueGetter: ({ data }) => data && `${fmtDate(data.fromDate)} ~ ${fmtDate(data.toDate)}`, minWidth: 250 },
            { field: 'quantityDays', headerName: '조회연도 신청일량 (DAY)', minWidth: 220, cellClass: 'w-sheet-number' },
            { colId: 'status', headerName: '상태·효력', valueGetter: ({ data }) => data && `${statuses[data.status] ?? data.status} · ${effects[data.effectStatus] ?? data.effectStatus}`, minWidth: 190 },
            { colId: 'submittedAt', headerName: '제출 시각 (서울)', valueGetter: ({ data }) => data && fmtDateTime(data.submittedAt), minWidth: 240 },
          ]}
        />
      </section>
    </>
  );
}
export function IdentityPages({ page, data }: PageProps): React.ReactNode | null {
  switch (page) {
    case 'workspace/profile':
      return <ProfilePage profile={data.profile as Profile} hasHeader={Boolean(data.employeeHeader)}
        details={data.personalDetails as PersonalDetailsModel | undefined} birthDate={(data.employeeHeader as { birthDate?: string | null } | undefined)?.birthDate} />;
    case 'org/access-list':
      return <AccessListPage model={data as unknown as AccessList} />;
    case 'company/attendance':
      return <CompanyAttendancePage model={data.companyAttendance as CompanyAttendance} />;
    case 'company/requests':
      return <CompanyRequestsPage model={data.companyRequests as CompanyRequests} />;
    default:
      return null;
  }
}
