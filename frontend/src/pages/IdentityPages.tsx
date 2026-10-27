import type React from 'react';
import { useState } from 'react';
import type { PageProps } from '../types';
import { duration, Field, Form, fmtDate, fmtDateTime, SelectField } from '../ui';

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
function ProfilePage({ profile: p }: { profile: Profile }) {
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
    ['현재 역할·기능', formatRoles(p.roles)],
  ];
  return (
    <>
      <header className="t-pagehead">
        <div>
          <p className="t-eyebrow">내 업무</p>
          <h1>내 정보</h1>
          <p className="t-muted">현재 계정과 업무 배정 정보를 확인하세요.</p>
        </div>
      </header>
      <section className="panel" aria-label="본인 정보">
        <p className="hint">조회 기준 (서울): {fmtDateTime(p.asOf)}</p>
        <dl className="t-person-info">
          {entries.map(([label, value]) => (
            <div key={label}><dt>{label}</dt><dd>{value}</dd></div>
          ))}
        </dl>
        <p className="hint">
          공통 업무와 명시적으로 부여된 역할을 함께 사용합니다. 결재 담당 기능은 실제 배정된 신청에 적용됩니다.
        </p>
      </section>
    </>
  );
}
function AccessListPage({ model: m }: { model: AccessList }) {
  return (
    <>
      <header className="t-pagehead">
        <div>
          <p className="t-eyebrow">관리 업무</p>
          <h1>권한 관리</h1>
          <p className="t-muted">계정별 역할을 확인하고 기존 관리 화면에서 변경하세요.</p>
        </div>
      </header>
      <section className="panel">
        <Form action="/admin/access" method="get" className="search-form">
          <div><Field label="사번·이름 검색" name="q" value={m.q} maxLength={120} /></div>
          <button type="submit">조회</button>
        </Form>
        <p className="hint">
          공통은 필수 역할입니다. 관리자·대표와 결재 담당 기능은 각각 부여하며 함께 보유할 수 있습니다.
        </p>
        {m.accessRows.length === 0 ? <p>조회된 계정이 없습니다.</p> : (
          <div className="table-scroll" role="region" aria-label="계정별 권한" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">사번</th><th scope="col">이름</th>
                  <th scope="col">역할·기능</th><th scope="col">계정 상태</th><th scope="col">관리</th>
                </tr>
              </thead>
              <tbody>
                {m.accessRows.map((row) => (
                  <tr key={row.employeeId}>
                    <td>{row.employeeNo}</td><td>{row.employeeName}</td>
                    <td>{formatRoles(row.roles)}</td><td>{row.active ? '활성' : '비활성'}</td>
                    <td>
                      <a href={`/admin/employees/${row.employeeId}/access`} aria-label={`${row.employeeName} 권한 관리`}>
                        권한 관리
                      </a>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Pagination path="/admin/access" values={{ q: m.q }} page={m.page} hasNext={m.hasNext} />
      </section>
    </>
  );
}
function CompanyAttendancePage({ model: m }: { model: CompanyAttendance }) {
  const [employeeId, setEmployeeId] = useState(m.employeeId ?? '');
  const values = { month: m.month, organizationId: m.organizationId, employeeId: m.employeeId, q: m.q };
  return (
    <>
      <header className="t-pagehead">
        <div>
          <p className="t-eyebrow">회사 현황</p><h1>회사 근무현황</h1>
          <p className="t-muted">사원을 선택해 해당 월의 일별 근무 결과를 확인하세요.</p>
        </div>
      </header>
      <section className="panel">
        <Form
          action="/company/attendance"
          method="get"
          className="admin-form"
          onChange={(event) => {
            const name = event.target.getAttribute('name');
            if (name === 'organizationId' || name === 'q') setEmployeeId('');
          }}
        >
          <input type="hidden" name="employeeId" value={employeeId} />
          <Field label="기준 월 (서울)" name="month" type="month" value={m.month} required />
          <OrganizationFilter organizations={m.organizations} organizationId={m.organizationId} />
          <Field label="사번·이름 검색" name="q" value={m.q} maxLength={100} />
          <div className="actions"><button type="submit">조회</button></div>
        </Form>
        <p className="hint">
          조직은 서울 기준 오늘의 소속으로 조회합니다. 조회 기준 (서울): {fmtDateTime(m.asOf)}
        </p>
        <h2>대상 사원</h2>
        {m.employees.length === 0 ? <p>조회할 사원이 없습니다.</p> : (
          <div className="table-scroll" role="region" aria-label="회사 현황 대상 사원" tabIndex={0}>
            <table>
              <thead><tr><th scope="col">사번·이름</th><th scope="col">현재 소속</th></tr></thead>
              <tbody>
                {m.employees.map((employee) => (
                  <tr key={employee.id}>
                    <td>
                      <a
                        href={queryHref('/company/attendance', { ...values, employeeId: employee.id, page: m.page })}
                        aria-current={m.selectedEmployee?.id === employee.id ? 'true' : undefined}
                      >
                        {employee.employeeNo} · {employee.employeeName}
                      </a>
                    </td>
                    <td>{employee.organizationName ?? '미배정'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Pagination path="/company/attendance" values={values} page={m.page} hasNext={m.hasNext} />
      </section>
      <section className="panel" aria-labelledby="company-days-title">
        <h2 id="company-days-title">
          {m.selectedEmployee ? `${m.selectedEmployee.employeeName} · ${m.month} 일별 현황` : '일별 현황'}
        </h2>
        <p className="hint">
          선택 사원의 검토용 결과입니다. 미확정·오래된 결과는 0시간으로 처리하지 않습니다.
        </p>
        {!m.selectedEmployee ? (
          <p>사원을 선택하면 해당 월의 일별 현황을 볼 수 있습니다.</p>
        ) : m.days.length === 0 ? <p>조회된 일별 현황이 없습니다.</p> : (
          <div className="table-scroll" role="region" aria-label="사원 일별 근무현황" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">근무일</th><th scope="col">상태·최신성</th>
                  <th scope="col">예정 근무 (시:분:초.밀리초)</th>
                  <th scope="col">실근로 (시:분:초.밀리초)</th><th scope="col">휴가 (DAY)</th>
                </tr>
              </thead>
              <tbody>
                {m.days.map((day) => (
                  <tr key={day.workDate} data-work-date={day.workDate}>
                    <td>{fmtDate(day.workDate)}</td>
                    <td>{statuses[day.status] ?? day.status} · {day.current ? '최신' : '미산정·재집계 필요'}</td>
                    <td>{duration(day.current ? day.scheduledMillis : null)}</td>
                    <td>{duration(day.current ? day.actualWorkMillis : null)}</td>
                    <td>{day.current && day.leaveDays != null ? day.leaveDays : '미확정'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
function CompanyRequestsPage({ model: m }: { model: CompanyRequests }) {
  return (
    <>
      <header className="t-pagehead">
        <div>
          <p className="t-eyebrow">회사 현황</p><h1>회사 신청 현황</h1>
          <p className="t-muted">조회연도의 회사 신청 유형과 처리 상태를 확인하세요.</p>
        </div>
      </header>
      <section className="panel">
        <Form action="/company/requests" method="get" className="admin-form">
          <Field label="조회연도 (서울)" name="year" type="number" value={m.year} min={1} max={9999} required />
          <OrganizationFilter organizations={m.organizations} organizationId={m.organizationId} />
          <SelectField label="신청 상태" name="status" value={m.status}>
            <option value="">전체 상태</option>
            {['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'].map((status) => (
              <option key={status} value={status}>{statuses[status]}</option>
            ))}
          </SelectField>
          <div className="actions"><button type="submit">조회</button></div>
        </Form>
        <p className="hint">
          조직은 서울 기준 오늘의 소속입니다. 신청일량은 조회연도에 포함된 일량이며 취소 신청은
          원신청을 기준으로 합니다. 조회 기준 (서울): {fmtDateTime(m.asOf)}
        </p>
        {m.requests.length === 0 ? <p>조회된 신청이 없습니다.</p> : (
          <div className="table-scroll" role="region" aria-label="회사 신청 목록" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">사번·이름</th><th scope="col">현재 소속</th><th scope="col">종류</th>
                  <th scope="col">기간</th><th scope="col">조회연도 신청일량 (DAY)</th>
                  <th scope="col">상태·효력</th><th scope="col">제출 시각 (서울)</th>
                </tr>
              </thead>
              <tbody>
                {m.requests.map((request) => (
                  <tr key={request.id}>
                    <td>{request.employeeNo} · {request.employeeName}</td>
                    <td>{request.organizationName ?? '미배정'}</td>
                    <td>{kinds[request.kind] ?? request.kind}</td>
                    <td>{fmtDate(request.fromDate)} ~ {fmtDate(request.toDate)}</td>
                    <td>{request.quantityDays}</td>
                    <td>{statuses[request.status] ?? request.status} · {effects[request.effectStatus] ?? request.effectStatus}</td>
                    <td>{fmtDateTime(request.submittedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Pagination
          path="/company/requests"
          values={{ year: m.year, organizationId: m.organizationId, status: m.status }}
          page={m.page}
          hasNext={m.hasNext}
        />
      </section>
    </>
  );
}
export function IdentityPages({ page, data }: PageProps): React.ReactNode | null {
  switch (page) {
    case 'workspace/profile':
      return <ProfilePage profile={data.profile as Profile} />;
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
