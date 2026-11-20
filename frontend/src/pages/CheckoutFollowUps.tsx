import { PageHeader } from '../components/PageHeader';
import { ErrorNotice } from '../ui';
import './CheckoutFollowUps.css';

export const followUpStatusNames = {
  REQUESTED: '직원 확인 대기',
  PENDING_APPROVAL: '정정 결재 대기',
  NEEDS_CONFIRMATION: '근무 확인 필요',
  AWAITING_CALCULATION: '재집계 대기',
  RESOLVED: '해소',
} as const;

export interface CheckoutFollowUp {
  id: string;
  workDate: string;
  reason: string;
  status: keyof typeof followUpStatusNames;
  approvalRequestId: string | null;
  resultId: string | null;
}

export interface CheckoutFollowUpList {
  rows: CheckoutFollowUp[];
  page: number;
  hasNext: boolean;
}

const path = '/me/attendance/follow-ups';

function FollowUpRows({ rows }: { rows: CheckoutFollowUp[] }) {
  return <ul className="checkout-follow-up-list" aria-label="근무일별 보완 요청">
    {rows.map(row => <li key={row.id}>
      <div><time dateTime={row.workDate}>{row.workDate}</time><span className="checkout-follow-up-state">{followUpStatusNames[row.status]}</span></div>
      <p className="checkout-follow-up-note">{row.reason}</p>
      <div className="actions">
        {row.status === 'NEEDS_CONFIRMATION' && <a className="t-btn" href={'/me/attendance?date=' + row.workDate}>근무 확인</a>}
        {(row.status === 'REQUESTED' || row.status === 'NEEDS_CONFIRMATION') &&
          <a className="t-btn" href={'/me/requests/correction?date=' + row.workDate}>정정 신청</a>}
        {row.approvalRequestId && <a className="t-btn" href={'/requests/' + encodeURIComponent(row.approvalRequestId)}>
          {row.status === 'REQUESTED' ? '이전 정정 상세' : '정정 상세'}</a>}
      </div>
    </li>)}
  </ul>;
}

export function HomeCheckoutFollowUps({ model }: { model?: CheckoutFollowUpList }) {
  if (!model?.rows.length) return null;
  return <section className="t-work-home-panel" aria-label="내 근태 보완 요청">
    <div className="t-work-home-panel-heading"><h2>내 근태 보완 요청</h2><a href={path}>전체 보기</a></div>
    <FollowUpRows rows={model.rows} />
  </section>;
}

export function MyCheckoutFollowUps({ model }: { model?: CheckoutFollowUpList }) {
  return <>
    <PageHeader title="내 근태 보완 요청" actions={<a className="t-btn" href="/me/home">홈으로</a>} />
    {!model ? <ErrorNotice message="보완 요청 목록을 불러오지 못했습니다. 다시 조회해 주세요." /> : <>
      {model.rows.length ? <FollowUpRows rows={model.rows} /> : <p>처리할 근태 보완 요청이 없습니다.</p>}
      {(model.page > 0 || model.hasNext) && <nav className="actions pagination" aria-label="보완 요청 페이지">
        {model.page > 0 && <a href={path + '?page=' + (model.page - 1)}>이전</a>}
        <span>{model.page + 1}페이지</span>
        {model.hasNext && <a href={path + '?page=' + (model.page + 1)}>다음</a>}
      </nav>}
    </>}
  </>;
}
