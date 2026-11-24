import { useState } from 'react';

export function CompanySetupGuide({ adminSelected, selectAdmin }: { adminSelected: boolean; selectAdmin: () => void }) {
  const [open, setOpen] = useState(false);
  const expanded = open && adminSelected;

  return <div className="company-setup-guide">
    <button type="button" className="t-btn" aria-expanded={expanded} aria-controls="company-setup-steps"
      onClick={() => {
        if (!expanded) selectAdmin();
        setOpen(!expanded);
      }}>
      회사 초기 설정 <span aria-hidden="true">{expanded ? '▴' : '▾'}</span>
    </button>
    <nav id="company-setup-steps" aria-label="회사 초기 설정 순서" hidden={!expanded}>
      <ol>
        <li><a href="/admin/organizations">조직 관리</a>에서 사원의 소속 부서를 등록합니다.</li>
        <li><a href="/admin/schedules?tab=codes">근무시간코드</a>에 출퇴근·휴게시간을 입력한 뒤 <a href="/admin/schedules?tab=groups">근무조</a>를 요일별로 편성합니다.
          {' '}<a href="/admin/calendar">근무일·휴일</a>에서 첫 근무일이 포함된 기간을 등록합니다.</li>
        <li><a href="/admin/networks">허용 네트워크</a>에 출퇴근을 기록할 사업장의 IP 대역을 등록합니다.</li>
        <li><a href="/admin/employees/onboarding">입사 예정 관리</a>에서 직원과 결재자의 입사일·소속·근무를 확정합니다.
          확정하면 계정이 자동으로 만들어지고, 직원은 입사일부터 사번으로 첫 로그인해 <strong>생년월일 확인</strong> 후 비밀번호를 정합니다.</li>
        <li><a href="/admin/access?role=APPROVER">승인자 권한 관리</a>에서 첫 로그인을 마친 결재자에게 권한을 부여합니다.
          {' '}<a href="/admin/approval-routes">결재선 관리</a>에서 결재선을 만들고 <a href="/admin/request-forms">신청서코드 관리</a>에서 신청서에 연결한 뒤 사원별로 미리 봅니다.</li>
      </ol>
    </nav>
  </div>;
}
