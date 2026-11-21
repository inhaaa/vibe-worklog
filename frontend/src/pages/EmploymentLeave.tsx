import { PageHeader } from '../components/PageHeader';
import { Field, Form, fmtDate } from '../ui';
import type { CsrfToken } from '../types';
import { ExitSettlement } from './EmploymentExit';
import { MonthlyReviews } from './EmploymentMonths';
import { useEmploymentPosting } from './EmploymentPosting';
import { employmentLink, employmentPath, type EmploymentLeaveModel } from './employmentLeaveTypes';
import './AnnualLeave.css';

export function EmploymentLeave({ model, csrf, reload }: { model: EmploymentLeaveModel; csrf: CsrfToken; reload: () => void }) {
  const { q, page, hasNext, employees, monthly, exit, publicDemo } = model;
  const posting = useEmploymentPosting(csrf, publicDemo, reload);
  const selected = monthly ?? exit;
  const refresh = employmentLink(q, page, selected?.employeeId);
  if (posting.state.mode === 'denied') return <section className="wl-section">
    <h2>연차 관리 권한 확인</h2><p role="alert">로그인 상태와 연차 관리 권한을 다시 확인해 주세요.</p>
    <a className="t-btn" href="/login">다시 로그인</a>
  </section>;
  return <div className="t-annual-page annual-leave-page employment-leave" data-modal-busy={posting.state.mode === 'sending' ? 'true' : undefined}>
    <PageHeader title="입사·퇴사 연차 정산" actions={<a className="t-btn" href="/admin/leave-balances">연차 관리</a>} />
    {publicDemo && <p className="notice">공개 데모에서는 사원별 발생 근거와 퇴사 정산 결과를 조회할 수 있습니다.</p>}
    <Form method="get" action={employmentPath} className="wl-querybar" role="search" aria-label="정산 사원 검색">
      <div><Field label="사번 또는 이름" name="q" value={q} type="search" /></div>
      <div className="wl-querybar-actions"><button type="submit" className="t-btn wl-query-submit" disabled={posting.locked}>사원 검색</button></div>
    </Form>
    <section className="wl-section" aria-labelledby="employment-results-title"><h2 id="employment-results-title">사원 검색 결과</h2>
      {employees.length ? <ul className="wl-rows employment-results">{employees.map(employee =>
        <li key={employee.id}><a href={employmentLink(q, page, employee.id)} aria-current={selected?.employeeId === employee.id ? 'true' : undefined}
          aria-label={employee.employeeNo + ' ' + employee.employeeName + ' 선택'}>{employee.employeeNo} · {employee.employeeName}</a>
          <span>{employee.leftOn ? '마지막 재직일 ' + fmtDate(employee.leftOn) : '입사일 ' + fmtDate(employee.joinedOn)}</span></li>)}</ul>
        : <p>검색 조건에 맞는 사원이 없습니다.</p>}
      {(page > 0 || hasNext) && <nav className="actions pagination" aria-label="사원 검색 결과 페이지">
        <span>{page + 1}페이지</span>{page > 0 && <a href={employmentLink(q, page - 1)}>이전</a>}
        {hasNext && <a href={employmentLink(q, page + 1)}>다음</a>}</nav>}
    </section>
    {selected ? <>
      <header className="employment-selection"><h2>{selected.employeeName} <small>{selected.employeeNo}</small></h2>
        <p>입사일 {fmtDate(selected.joinedOn)} · 마지막 재직일 {selected.leftOn ? fmtDate(selected.leftOn) : '미등록'} · 조회 기준일 {fmtDate(model.today)}</p></header>
      {monthly && <MonthlyReviews monthly={monthly} readOnly={publicDemo} posting={posting} refresh={refresh} />}
      {exit && <ExitSettlement exit={exit} readOnly={publicDemo} posting={posting} refresh={refresh} />}
    </> : employees.length > 0 && <p className="hint">사원을 선택하면 월별 발생 근거와 퇴사 정산 일수를 조회합니다.</p>}
  </div>;
}
