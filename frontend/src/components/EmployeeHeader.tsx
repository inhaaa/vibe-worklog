import { useState } from 'react';
import './EmployeeWorkspace.css';
export interface EmployeeHeaderData {
  employeeId: string; employeeNo: string; employeeName: string; companyName: string; organizationName: string | null;
  joinedOn: string; leftOn?: string | null; birthDate?: string | null; gradeName?: string | null;
  positionName?: string | null; jobName?: string | null; employmentStatus: string; photoUrl?: string | null; asOf: string;
}
export const employmentStatusName = (status: string) => ({ WORKING: '재직', ON_LEAVE: '휴직', SUSPENDED: '정직', PRE_JOINING: '입사 예정', LEFT: '퇴사', UNKNOWN: '상태 미등록' }[status] ?? '상태 미등록');

export function EmployeeHeader({ employee: e }: { employee: EmployeeHeaderData }) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const photo = e.photoUrl && failedUrl !== e.photoUrl ? e.photoUrl : null;
  const entries = [
    ['생년월일', e.birthDate], ['직급', e.gradeName], ['직책', e.positionName], ['직무', e.jobName], ['입사일', e.joinedOn],
  ];
  return <section className="t-employee-header" aria-label="사원 인사정보">
    <div className="t-employee-header-body">
      <div className="t-employee-portrait">
        {photo ? <img src={photo} alt={`${e.employeeName} 사원사진`} onError={() => setFailedUrl(photo)} /> : <span aria-label="사원사진 미등록">{e.employeeName.slice(0, 1) || 'W'}</span>}
      </div>
      <div className="t-employee-identity">
        <p className="t-employee-company">{e.companyName}</p>
        <div className="t-employee-name-row"><h2>{e.employeeName}</h2><span className={`t-employment-state t-state-${e.employmentStatus.toLowerCase()}`}>{employmentStatusName(e.employmentStatus)}</span></div>
        <p className="t-employee-meta">{e.organizationName ?? '소속 미배정'}<span aria-hidden="true">·</span><span>사번 {e.employeeNo}</span></p>
      </div>
      <dl className="t-employee-facts">{entries.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value || '미등록'}</dd></div>)}</dl>
    </div>
  </section>;
}
