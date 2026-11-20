import type { ListModel } from './WorkflowTypes';
import { requestKindName } from './leavePart';
import { fmtDate } from '../ui';
import './ApprovalWorkspace.css';

const label = (value: string, names: Record<string, string>) =>
  Object.hasOwn(names, value) ? names[value] : value;
const statusName = (value: string) => label(value, {
  PENDING: '결재 대기', APPROVED: '승인', REJECTED: '반려', WITHDRAWN: '철회',
});
const effectName = (value: string) => label(value, {
  NONE: '효력 없음', EFFECTIVE: '유효', CANCELED: '효력 취소', SUPERSEDED: '후속 정정으로 대체',
});

function ApprovalIcon() {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true"><path d="M8 5H6a2 2 0 0 0-2 2v13h16V7a2 2 0 0 0-2-2h-2M8 3h8v4H8zM8 13l3 3 5-5" strokeLinecap="round" strokeLinejoin="round" /></svg>;
}

export function ApprovalWorkspace({ model: m }: { model: ListModel }) {
  const base = m.completed ? '/approvals/completed' : '/approvals';
  return (
    <div className="t-approval-workspace">
      <header className="t-approval-pagehead">
        <div className="t-approval-heading">
          <span className="t-approval-icon"><ApprovalIcon /></span>
          <div><h1>{m.title}</h1></div>
        </div>
        <a className="t-approval-btn" href="/me/requests">내 신청 보기</a>
      </header>
      <nav className="t-approval-nav" aria-label="결재 목록">
        <a href="/approvals" aria-current={!m.completed ? 'page' : undefined}>결재 대기함</a>
        <a href="/approvals/completed" aria-current={m.completed ? 'page' : undefined}>내가 처리한 결재</a>
      </nav>
      <section className="t-approval-intro" aria-label="조회 범위">
        <div><h2>{m.completed ? '내가 남긴 결정' : '검토할 신청'}</h2><p>{m.completed
          ? '본인이 승인하거나 반려한 신청입니다. 철회는 처리한 결재에 포함하지 않습니다.'
          : '현재 본인에게 지정된 대기 신청입니다. 내용을 확인한 뒤 승인 또는 반려해 주세요.'}</p></div>
        <span className="t-approval-count">이 페이지 {m.requests.length}건</span>
      </section>
      {m.requests.length === 0 ? <section className="t-approval-empty" aria-label="빈 결재 목록">
        <span className="t-approval-icon"><ApprovalIcon /></span>
        <h2>{m.completed ? '아직 처리한 결재가 없습니다.' : '현재 결재할 신청이 없습니다.'}</h2>
        <p>{m.completed ? '승인·반려한 신청은 이곳에서 다시 확인할 수 있어요.' : '새로 지정된 신청이 들어오면 이곳에서 확인할 수 있어요.'}</p>
      </section> : <ul className="t-approval-list" aria-label="결재 신청 목록">
        {m.requests.map((r) => <li key={r.id} className="t-approval-card">
          <div className="t-approval-card-top"><span className="t-approval-kind">{requestKindName(r.kind,r.leavePart,r.companyLeaveName)}</span><span className="t-approval-status" data-status={r.status}>{statusName(r.status)}</span></div>
          <div className="t-approval-person"><span className="t-approval-avatar" aria-hidden="true">{r.targetEmployeeName.slice(0,1)}</span><div><h2>{r.targetEmployeeName}</h2><p>신청 대상</p></div></div>
          <dl className="t-approval-facts">
            {r.formCode && <div><dt>신청서</dt><dd>{r.formName} ({r.formCode})</dd></div>}
            {r.totalSteps && r.status === 'PENDING' && <div><dt>결재 진행</dt><dd>{r.currentStep}/{r.totalSteps}단계 결재 중{m.completed && ' · 내 단계 승인 완료'}</dd></div>}
            <div><dt>대상 기간</dt><dd>{fmtDate(r.fromDate)} <span aria-hidden="true">~</span> {fmtDate(r.toDate)}</dd></div>
            {(r.kind === 'LEAVE_DAY' || r.kind === 'CANCEL_LEAVE') && r.quantityDays != null && <div><dt>신청 일수</dt><dd className="t-approval-quantity">{r.quantityDays}일</dd></div>}
          </dl>
          <p className="t-approval-reason">{r.reason}</p>
          <div className="t-approval-meta"><span>현재 결재 담당 <strong>{r.approverName || '미지정'}</strong></span><span>현재 효력 <strong>{effectName(r.effectStatus)}</strong></span></div>
          <a className={`t-approval-btn${m.completed ? '' : ' primary'}`} href={`/requests/${r.id}`}>{m.completed ? '상세 보기' : '신청 검토'}</a>
        </li>)}
      </ul>}
      {m.page !== undefined && <nav className="t-approval-pagination" aria-label="결재 페이지">
        <span>{m.page + 1}페이지</span><div>{m.page > 0 && <a className="t-approval-btn" href={`${base}?page=${m.page - 1}`}>이전</a>}{m.hasNext && <a className="t-approval-btn" href={`${base}?page=${m.page + 1}`}>다음</a>}</div>
      </nav>}
    </div>
  );
}
