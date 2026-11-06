import { useId, useState } from 'react';
import './EmployeeWorkspace.css';
export interface EmployeeHeaderData {
  employeeId: string; employeeNo: string; employeeName: string; companyName: string; organizationName: string | null;
  joinedOn: string; leftOn?: string | null; birthDate?: string | null; gradeName?: string | null;
  positionName?: string | null; jobName?: string | null; employmentStatus: string; photoUrl?: string | null; asOf: string;
}
type EmployeeSummary = Pick<EmployeeHeaderData, 'employeeName' | 'companyName'>;
export const employmentStatusName = (status: string) => ({ WORKING: '재직', ON_LEAVE: '휴직', SUSPENDED: '정직', PRE_JOINING: '입사 예정', LEFT: '퇴사', UNKNOWN: '상태 미등록' }[status] ?? '상태 미등록');

export function EmployeeHeader({ employee }: { employee: EmployeeHeaderData | EmployeeSummary }) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const summaryHeading = useId();
  const details = 'employeeId' in employee ? employee : null;
  const photoUrl = details?.photoUrl;
  const photo = photoUrl && failedUrl !== photoUrl ? photoUrl : null;
  const entries = details ? [
    ['생년월일', details.birthDate], ['직급', details.gradeName], ['직책', details.positionName],
    ['직무', details.jobName], ['입사일', details.joinedOn],
  ] : [];

  return <section className="t-employee-header" aria-label="사원 인사정보" data-compact={!details}>
    <div className="t-employee-identity-card">
      <div className="t-employee-portrait">
        {photo ? <img src={photo} alt={`${employee.employeeName} 사원사진`} onError={() => setFailedUrl(photo)} />
          : <span aria-hidden="true">{Array.from(employee.employeeName)[0] || 'W'}</span>}
      </div>
      <div className="t-employee-identity">
        <div className="t-employee-name-row">
          <h2>{employee.employeeName}</h2>
          {details && <span className={`t-employment-state t-state-${details.employmentStatus.toLowerCase()}`}>{employmentStatusName(details.employmentStatus)}</span>}
        </div>
        <p className="t-employee-company">{employee.companyName}</p>
        {details && <p className="t-employee-meta">
          <span className="t-employee-organization"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false"><path d="M4 21V5h10v16 M14 11h6v10 M8 9h2 M8 13h2 M8 17h2 M3 21h18" /></svg>{details.organizationName ?? '소속 미배정'}</span>
          <span className="t-employee-number">사번 {details.employeeNo}</span>
        </p>}
      </div>
    </div>
    {details && <section className="t-employee-summary" aria-labelledby={summaryHeading}>
      <h3 id={summaryHeading}>인사 요약</h3>
      <dl className="t-employee-facts">
        {entries.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value || '미등록'}</dd></div>)}
      </dl>
    </section>}
  </section>;
}
