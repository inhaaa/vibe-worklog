import { useEffect, useRef, useState } from 'react';
import { commandReason } from '../commandReason';
import { FormTable, fmtDate } from '../ui';
import { AnnualLeaveTable } from './AnnualLeaveTable';
import { EmploymentReason } from './EmploymentMonths';
import { PostingFeedback, type EmploymentPosting } from './EmploymentPosting';
import type { EmploymentExit, ExitBucket, ExitReceipt } from './employmentLeaveTypes';

const statusNames: Record<ExitBucket['status'], string> = {
  READY: '정산 대상', EMPTY: '잔여 없음', UNKNOWN: '귀속 확인 필요', EXPIRED: '사용기간 만료',
  FUTURE: '사용기간 시작 전', NEGATIVE: '음수 잔여 확인 필요', AFTER_EXIT: '퇴사 후 변동 확인 필요',
};

export function ExitReceiptSummary({ receipt }: { receipt: ExitReceipt }) {
  return <section className="wl-section employment-receipt" aria-labelledby="employment-exit-receipt">
    <h2 id="employment-exit-receipt">퇴사 정산 결과</h2>
    <dl className="employment-facts">
      <div><dt>마지막 재직일</dt><dd>{fmtDate(receipt.leftOn)}</dd></div>
      <div><dt>확정한 정산 일수</dt><dd><strong>{receipt.settlementDays}일</strong></dd></div>
      <div><dt>정산 사유</dt><dd>{receipt.reason}</dd></div>
    </dl>
  </section>;
}

function ExitBreakdown({ exit }: { exit: EmploymentExit }) {
  return <>
    <dl className="employment-facts">
      <div><dt>정산 전 원장 잔여</dt><dd>{exit.balanceDays}일</dd></div>
      <div><dt>정산 대상 일수</dt><dd><strong>{exit.settlementDays}일</strong></dd></div>
    </dl>
    {exit.rows.length ? <AnnualLeaveTable label="묶음별 정산 근거"
      columns={['묶음 근거·사용기간', '부여·증감', '사용·복원', '정산 전 잔여', '정산 대상', '상태']}
      rows={exit.rows.map(row => ({ id: row.bucketId, cells: [
        <><strong>{row.sourceReference}</strong><small>{row.entitlementYear == null ? '귀속 미확인' : row.entitlementYear + '년'}</small>
          <small>{fmtDate(row.usableFrom)} ~ {row.usableTo ? fmtDate(row.usableTo) : '종료일 미지정'}</small></>,
        <div className="employment-counts"><span>기초 {row.openingDays}일</span><span>발생·부여 {row.grantDays}일</span>
          <span>순이월 {row.carryDays}일</span><span>조정 {row.adjustmentDays}일</span><span>소멸 {row.expireDays}일</span></div>,
        <div className="employment-counts"><span>사용 {row.usedDays}일</span><span>복원 {row.restoredDays}일</span></div>,
        row.balanceDays + '일', <strong>{row.settlementDays}일</strong>, statusNames[row.status],
      ] }))} /> : <p>정산 기준일에 조회할 연차 묶음이 없습니다.</p>}
    {exit.missingMonths.length > 0 && <p>월별 판단 기록이 없는 발생일 · {exit.missingMonths.map(fmtDate).join(', ')}</p>}
    {exit.blockers.length > 0 && <div className="notice"><strong>정산 전 확인할 사항</strong><ul>{exit.blockers.map(message => <li key={message}>{message}</li>)}</ul></div>}
  </>;
}

export function ExitSettlement({ exit, readOnly, posting, refresh }: {
  exit: EmploymentExit; readOnly: boolean; posting: EmploymentPosting; refresh: string;
}) {
  const [expanded, setExpanded] = useState(false);
  const [reason, setReason] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const heading = useRef<HTMLHeadingElement>(null);
  const checked = commandReason(reason);
  const editable = !readOnly && !exit.settlementId && exit.canPost && Boolean(exit.token) && exit.blockers.length === 0;
  const canPost = editable && checked.validReason && (!exit.priorHistoryReviewRequired || confirmed) && !posting.locked;
  useEffect(() => { if (expanded) heading.current?.focus(); }, [expanded]);
  if (exit.settlementId && exit.leftOn) return <>
    <ExitReceiptSummary receipt={{ leftOn: exit.leftOn, settlementDays: exit.settlementDays, reason: exit.reason ?? '' }} />
    <section className="wl-section" aria-label="확정 당시 정산 근거"><h3>확정 당시 정산 근거</h3><ExitBreakdown exit={exit} /></section>
  </>;
  return <section className="wl-section" aria-labelledby="employment-exit-title">
    <h2 id="employment-exit-title">퇴사 잔여 정산</h2>
    {exit.leftOn ? <>
      <p>마지막 재직일 <time dateTime={exit.leftOn}>{fmtDate(exit.leftOn)}</time> 기준으로 원장의 잔여 일수를 정산합니다.</p>
      <button type="button" className="t-btn" aria-expanded={expanded} disabled={posting.locked}
        onClick={() => setExpanded(true)}>퇴사 정산 미리보기</button>
    </> : <p>인사기본에서 마지막 재직일을 등록하면 정산 대상 일수를 확인할 수 있습니다.</p>}
    {expanded && exit.leftOn && <section className="employment-editor" aria-labelledby="employment-exit-preview">
      <h3 ref={heading} tabIndex={-1} id="employment-exit-preview">퇴사 정산 미리보기</h3>
      <ExitBreakdown exit={exit} />
      {editable && <form className="wl-form" aria-label="퇴사 정산 확인" onSubmit={event => {
        event.preventDefault();
        if (canPost && exit.token) posting.post({ employeeId: exit.employeeId, token: exit.token,
          reason: checked.trimmed, priorHistoryConfirmed: exit.priorHistoryReviewRequired && confirmed });
      }}>
        <FormTable><EmploymentReason id="employment-exit-reason" label="퇴사 정산 사유" value={reason}
          disabled={posting.locked} update={value => { setReason(value); posting.clearError(); }} /></FormTable>
        {exit.priorHistoryReviewRequired && <>
          <p className="hint">최초 1년의 월별 발생과 이후 근속기간의 연간 부여·사용 일수를 입사일 기준으로 확인해 주세요.</p>
          <label className="employment-check"><input type="checkbox" checked={confirmed} disabled={posting.locked}
            onChange={event => { setConfirmed(event.target.checked); posting.clearError(); }} />입사일 기준 발생·사용과 이관 원장을 대조했습니다</label>
        </>}
        <p className="hint">확정하면 {exit.settlementDays}일이 퇴사 정산으로 원장에 기록됩니다. 급여 지급액은 별도로 산정합니다.</p>
        <div className="wl-form-actions"><button className="t-btn primary" type="submit" disabled={!canPost}>퇴사 정산 확정</button></div>
      </form>}
      <PostingFeedback action="settle" posting={posting} refresh={refresh} />
    </section>}
  </section>;
}
