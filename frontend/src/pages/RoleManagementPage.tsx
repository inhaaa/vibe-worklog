import { useId } from 'react';
import type { ReactNode } from 'react';
import { PageHeader } from '../components/PageHeader';
import { Form } from '../ui';
import './RoleManagementPage.css';

export type RoleMemberRow = {
  employeeId: string;
  employeeNo: string;
  employeeName: string;
  organizationName: string | null;
  version: number;
  available: boolean;
  editable: boolean;
  restriction: string | null;
};

export type RoleDirectory = {
  roles: { code: string; label: string; editable: boolean; memberCount: number }[];
  role: string;
  actorEmployeeId: string;
  members: RoleMemberRow[];
  q: string;
  page: number;
  hasNext: boolean;
  total: number;
};

function directoryUrl(role: string, q: string, page?: number) {
  const query = new URLSearchParams({ role });
  if (q) query.set('q', q);
  if (page !== undefined) query.set('page', String(page));
  return `/admin/access?${query}`;
}

export function RoleManagementPage({ model, children }: { model: RoleDirectory; children: ReactNode }) {
  const searchId = useId();
  return (
    <>
      <PageHeader title="권한 관리" />
      <div className="role-management">
        <nav className="role-management-roles" aria-label="권한 목록">
          <ul>
            {model.roles.map(({ code, label, memberCount }) => (
              <li key={code}>
                <a href={directoryUrl(code, model.q)} aria-current={code === model.role ? 'page' : undefined}>
                  <span>{label}</span><span>{memberCount.toLocaleString('ko-KR')}명</span>
                </a>
              </li>
            ))}
          </ul>
        </nav>
        <div className="role-management-sheet">
          {model.role === 'EMPLOYEE' && <p className="role-management-default">공통은 직원의 기본 권한입니다.</p>}
          <Form key={`${model.role}:${model.q}`} action="/admin/access" method="get" className="wl-querybar"
            role="search" aria-label="권한 사원 검색">
            <input type="hidden" name="role" value={model.role} />
            <label htmlFor={searchId}>사번·이름 검색</label>
            <input id={searchId} type="search" className="t-input" name="q" maxLength={120} defaultValue={model.q} />
            <div className="wl-querybar-actions"><button type="submit" className="t-btn wl-query-submit">조회</button></div>
          </Form>
          <p className="role-management-count">검색 결과 {model.total.toLocaleString('ko-KR')}명</p>
          {children}
          {(model.page > 0 || model.hasNext) && (
            <nav className="actions pagination" aria-label="권한 사원 페이지">
              {model.page > 0 && <a href={directoryUrl(model.role, model.q, model.page - 1)}>이전</a>}
              <span>{model.page + 1}페이지</span>
              {model.hasNext && <a href={directoryUrl(model.role, model.q, model.page + 1)}>다음</a>}
            </nav>
          )}
        </div>
      </div>
    </>
  );
}
