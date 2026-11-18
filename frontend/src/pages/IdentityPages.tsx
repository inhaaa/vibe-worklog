import type React from 'react';
import { useState } from 'react';
import type { PageProps } from '../types';
import { duration, Field, Form, fmtDate, fmtDateTime, SelectField } from '../ui';
import { WorklogSheet, sheetColumn } from '../components/WorklogSheet';
import { PageHeader } from '../components/PageHeader';
import { SectionHeader } from '../components/SectionHeader';
import { PersonalDetailsTabs, type PersonalDetailsModel } from './PersonalDetails';
import { RoleManagementPage, type RoleDirectory } from './RoleManagementPage';
import { RoleMembersSheet } from './RoleMembersSheet';
import { requestKindName, type LeavePart } from './leavePart';
import { CheckoutOperationsPage, CompanyAttendanceViews, type CheckoutOperations } from './CheckoutOperationsPage';

interface Profile {
  employeeName: string;
  joinedOn: string;
  approvalPreview?: { eligible:boolean; approverName:string|null; message:string|null };
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
  scope?: "LEAVE" | "CORRECTION" | null;
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
    leavePart?: LeavePart | null;
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
function ProfilePage({ details, identity, approval }: {
  details?: PersonalDetailsModel;
  identity: { employeeName: string; joinedOn: string; birthDate?: string | null };
  approval?: Profile['approvalPreview'];
}) {
  const [expanded,setExpanded]=useState(false);
  return (
    <>
      <PageHeader title="내 정보" />
      {details && <PersonalDetailsTabs model={details} identity={identity} />}
      {approval && <section className="wl-section">
        <button className="t-btn" aria-expanded={expanded} onClick={()=>setExpanded(!expanded)}>기존 배정 기준</button>
        {expanded && <div><p>{approval.eligible ? '기본 결재자 · '+approval.approverName : approval.message || '기본 결재자를 확인해 주세요.'}</p>
          <p>신청서를 작성할 때 해당 업무에 적용되는 결재 순서를 확인할 수 있습니다.</p></div>}
      </section>}
    </>
  );
}
function CompanyAttendancePage({ model: m, canViewCheckout }: { model: CompanyAttendance; canViewCheckout: boolean }) {
  const [employeeId, setEmployeeId] = useState(m.employeeId ?? '');
  const values = { month: m.month, organizationId: m.organizationId, employeeId: m.employeeId, q: m.q };
  return (
    <>
      <PageHeader title="회사 근무현황" help={<>
        <p>사원을 선택해 해당 월의 일별 근무 결과를 확인하세요.</p>
        <p>조직은 서울 기준 오늘의 소속으로 조회합니다.</p>
        <p>선택 사원의 검토용 결과입니다. 미확정·오래된 결과는 0시간으로 처리하지 않습니다.</p>
      </>} />
      {canViewCheckout && <CompanyAttendanceViews />}
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
                colId: 'scheduledMillis', headerName: '예정 근무',
                valueGetter: ({ data }) => data?.current ? data.scheduledMillis : null,
                valueFormatter: ({ value }) => duration(value),
                filterValueGetter: ({ data }) => duration(data?.current ? data.scheduledMillis : null),
                getQuickFilterText: ({ value }) => duration(value), minWidth: 230, cellClass: 'w-sheet-number',
              },
              {
                colId: 'actualWorkMillis', headerName: '실근로',
                valueGetter: ({ data }) => data?.current ? data.actualWorkMillis : null,
                valueFormatter: ({ value }) => duration(value),
                filterValueGetter: ({ data }) => duration(data?.current ? data.actualWorkMillis : null),
                getQuickFilterText: ({ value }) => duration(value), minWidth: 220, cellClass: 'w-sheet-number',
              },
              { colId: 'leaveDays', headerName: '휴가 (일)', valueGetter: ({ data }) => data?.current && data.leaveDays != null ? data.leaveDays : '미확정', minWidth: 130, cellClass: 'w-sheet-number' },
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
      <PageHeader title={m.scope === "LEAVE" ? "회사 휴가 신청 현황" : m.scope === "CORRECTION" ? "회사 정정 현황" : "회사 신청 현황"} help={<>
        <p>조회연도의 회사 신청 유형과 처리 상태를 확인하세요.</p>
        <p>조직은 서울 기준 오늘의 소속입니다. 신청일량은 조회연도에 포함된 일량이며 취소 신청은 원신청을 기준으로 합니다.</p>
      </>} />
      <Form action="/company/requests" method="get" className="wl-querybar">
        {m.scope && <input type="hidden" name="scope" value={m.scope} />}
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
            values={{ year: m.year, organizationId: m.organizationId, status: m.status, scope: m.scope ?? null }}
            page={m.page}
            hasNext={m.hasNext}
          />}
          columns={[
            { colId: 'employee', headerName: '사번·이름', valueGetter: ({ data }) => data && `${data.employeeNo} · ${data.employeeName}`, minWidth: 200, pinned: 'left' },
            { colId: 'organizationName', headerName: '현재 소속', valueGetter: ({ data }) => data?.organizationName ?? '미배정', minWidth: 150 },
            { colId: 'kind', headerName: '종류', valueGetter: ({ data }) => data && (data.kind==='ATTENDANCE_CORRECTION'?'기록 정정':requestKindName(data.kind,data.leavePart)), minWidth: 140 },
            { colId: 'period', headerName: '기간', valueGetter: ({ data }) => data && `${fmtDate(data.fromDate)} ~ ${fmtDate(data.toDate)}`, minWidth: 250 },
            { field: 'quantityDays', headerName: '조회연도 신청일량 (일)', minWidth: 220, cellClass: 'w-sheet-number' },
            { colId: 'status', headerName: '상태·효력', valueGetter: ({ data }) => data && `${statuses[data.status] ?? data.status} · ${effects[data.effectStatus] ?? data.effectStatus}`, minWidth: 190 },
            { colId: 'submittedAt', headerName: '제출 시각 (서울)', valueGetter: ({ data }) => data && fmtDateTime(data.submittedAt), minWidth: 240 },
          ]}
        />
      </section>
    </>
  );
}
export function IdentityPages({ page, data, csrf, reload }: PageProps): React.ReactNode | null {
  switch (page) {
    case 'workspace/profile': {
      const p = data.profile as Profile;
      return <ProfilePage details={data.personalDetails as PersonalDetailsModel | undefined} approval={p.approvalPreview} identity={{ employeeName: p.employeeName,
        joinedOn: p.joinedOn, birthDate: (data.employeeHeader as { birthDate?: string | null } | undefined)?.birthDate }} />;
    }
    case 'org/access-list': {
      const model = data.roleDirectory as RoleDirectory;
      return <RoleManagementPage model={model}>
        <RoleMembersSheet model={model} csrf={csrf} reload={reload} actorEmployeeId={model.actorEmployeeId} />
      </RoleManagementPage>;
    }
    case 'company/attendance': {
      const checkout = data.checkoutOperations as CheckoutOperations | undefined;
      return checkout
        ? <CheckoutOperationsPage key={JSON.stringify([checkout.date, checkout.organizationId, checkout.q, checkout.status, checkout.page, checkout.error])} model={checkout} />
        : <CompanyAttendancePage model={data.companyAttendance as CompanyAttendance} canViewCheckout={data.canViewCheckout === true} />;
    }
    case 'company/requests':
      return <CompanyRequestsPage model={data.companyRequests as CompanyRequests} />;
    default:
      return null;
  }
}
