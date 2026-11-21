import { useEffect, useRef, useState } from 'react';
import { commandReason } from '../commandReason';
import { FormRow, FormTable, fmtDate } from '../ui';
import { AnnualLeaveTable } from './AnnualLeaveTable';
import { PostingFeedback, type EmploymentPosting } from './EmploymentPosting';
import { decisionNames, type AttendanceDecision, type EmploymentMonths, type MonthlyPeriod, type OpeningSource } from './employmentLeaveTypes';

const canLink = (source: OpeningSource) => source.creditedDays - source.linkedDays >= 1;

export function EmploymentReason({ id, label, value, update, disabled }: {
  id: string; label: string; value: string; update: (value: string) => void; disabled: boolean;
}) {
  const reason = commandReason(value);
  const error = reason.length > 500 ? '확인 사유는 500자 이내로 입력해 주세요.'
    : reason.forbiddenCharacters ? '탭과 제어 문자를 제외하고 한 줄로 입력해 주세요.' : '';
  return <FormRow label={label} htmlFor={id} required>
    <input type="text" className="t-input" id={id} value={value} required disabled={disabled}
      aria-invalid={error ? true : undefined} aria-describedby={error ? id + '-error' : undefined}
      onChange={event => update(event.target.value)} />
    {error && <small role="alert" id={id + '-error'}>{error}</small>}
  </FormRow>;
}

function MonthEditor({ period, employeeId, sources, posting, refresh }: {
  period: MonthlyPeriod; employeeId: string; sources: OpeningSource[]; posting: EmploymentPosting; refresh: string;
}) {
  const [decision, setDecision] = useState<AttendanceDecision | ''>('');
  const [sourceId, setSourceId] = useState('');
  const [reason, setReason] = useState('');
  const [review, setReview] = useState(false);
  const select = useRef<HTMLSelectElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const checked = commandReason(reason);
  const source = sources.find(item => item.bucketId === sourceId && canLink(item));
  const canReview = Boolean(decision && (decision !== 'INCLUDED' || source) && checked.validReason && period.token && !posting.locked);
  useEffect(() => { select.current?.focus(); }, []);
  useEffect(() => { if (review) heading.current?.focus(); }, [review]);
  return <div className="employment-editor">
    <h3>{period.monthNumber}개월차 판단</h3>
    <p>{fmtDate(period.periodStart)} ~ {fmtDate(period.periodEnd)} · 발생 기준일 {fmtDate(period.accrualOn)}</p>
    <form className="wl-form" aria-label="월별 개근 판단" onSubmit={event => { event.preventDefault(); if (canReview) setReview(true); }}>
      <FormTable columns={2}>
        <FormRow label="개근 판단" htmlFor="employment-month-decision" required>
          <select required ref={select} className="t-select" id="employment-month-decision" value={decision} disabled={posting.locked}
            onChange={event => { setDecision(event.target.value as AttendanceDecision | ''); setSourceId(''); setReview(false); posting.clearError(); }}>
            <option value="">판단 선택</option><option value="FULL">개근</option><option value="NOT_FULL">미개근</option>
            <option value="INCLUDED" disabled={!sources.some(canLink)}>{decisionNames.INCLUDED}</option>
          </select>
        </FormRow>
        {decision === 'INCLUDED' && <FormRow label="이미 부여한 원천" htmlFor="employment-included-source" required>
          <select required className="t-select" id="employment-included-source" value={sourceId} disabled={posting.locked}
            onChange={event => { setSourceId(event.target.value); setReview(false); posting.clearError(); }}>
            <option value="">기초·선부여 원천 선택</option>
            {sources.map(item => <option key={item.bucketId} value={item.bucketId} disabled={!canLink(item)}>
              {item.sourceReference} · 연결 기준 {item.creditedDays}일 · 이미 연결 {item.linkedDays}일</option>)}
          </select>
        </FormRow>}
        <EmploymentReason id="employment-month-reason" label="월별 판단 사유" value={reason} disabled={posting.locked}
          update={value => { setReason(value); setReview(false); posting.clearError(); }} />
      </FormTable>
      <div className="wl-form-actions"><button className="t-btn" disabled={!canReview} type="submit">월별 발생 미리보기</button></div>
    </form>
    {review && decision && <section className="employment-confirm" aria-labelledby="employment-month-preview">
      <h4 id="employment-month-preview" ref={heading} tabIndex={-1}>월별 판단 미리보기</h4>
      {decision === 'INCLUDED' && source ? <>
        <p>{fmtDate(period.accrualOn)} · 개근 1일 · <strong>이번 추가 반영 0일</strong></p>
        <p>이미 부여한 원천 · {source.sourceReference} (연결 기준 {source.creditedDays}일, 이미 연결 {source.linkedDays}일)</p>
      </> : <p>{fmtDate(period.accrualOn)} · {decisionNames[decision]} · 발생 <strong>{decision === 'FULL' ? 1 : 0}일</strong></p>}
      <p>판단 사유 · {checked.trimmed}</p>
      <button type="button" className="t-btn primary" disabled={!canReview} onClick={() => {
        if (canReview && period.token) posting.post({ employeeId, token: period.token, accrualOn: period.accrualOn, decision,
          reason: checked.trimmed, ...(decision === 'INCLUDED' && source ? { includedBucketId: source.bucketId } : {}) });
      }}>월별 판단 확정</button>
    </section>}
    <PostingFeedback action="monthly" posting={posting} refresh={refresh} />
  </div>;
}

export function MonthlyReviews({ monthly, readOnly, posting, refresh }: {
  monthly: EmploymentMonths; readOnly: boolean; posting: EmploymentPosting; refresh: string;
}) {
  const [selected, setSelected] = useState<number>();
  const reviewing = monthly.periods.find(period => period.monthNumber === selected && !period.decision && period.token);
  const canEdit = !readOnly && !monthly.settled;
  const sources = monthly.openingSources ?? [];
  return <section className="wl-section" aria-labelledby="employment-month-title">
    <h2 id="employment-month-title">월별 연차 발생</h2>
    <p className="hint">회사 기준이 없으면 1월 시작 회계연도로 귀속됩니다. 다른 시작월은 첫 월별 확정 전에 설정해 주세요.{' '}
      <a href="/admin/leave-balances/annual-cycle">회사 연차 기준 설정</a></p>
    {monthly.periods.length ? <>
      <p className="hint">최초 1년 중 발생일이 도래한 기간입니다. 출근과 휴가는 같은 날에 함께 집계될 수 있습니다.
        미확정일과 의제출근 여부는 회사 자료를 대조해 판단해 주세요.</p>
      <p>사용 기한 <time dateTime={monthly.usableTo}>{fmtDate(monthly.usableTo)}</time></p>
      {canEdit && monthly.periods.some(period => !period.decision && !period.token)
        && <p className="notice">입사일·마지막 재직일과 기존 월별 판정을 확인해 주세요.</p>}
      {sources.length > 0 && <p className="hint">기초·선부여에 포함한 개근 월은 ‘개근 · 기존 원장에 포함’을 선택하세요.
        연결 기준 일수에는 순감액 조정을 반영하며, 이미 사용한 휴가도 연결할 수 있습니다.</p>}
      <AnnualLeaveTable label="월별 발생 근거" columns={['개월차', '산정 기간', '발생 기준일', '근태 근거', '판단·근거', '원장 반영', '검토']}
        rows={monthly.periods.map(period => ({ id: period.accrualOn, cells: [
          <strong>{period.monthNumber}개월차</strong>,
          <>{fmtDate(period.periodStart)} ~ {fmtDate(period.periodEnd)}</>, fmtDate(period.accrualOn),
          <div className="employment-counts"><span>소정근로 {period.scheduledDays}일</span><span>확인 출근 {period.attendedDays}일</span>
            <span>승인 휴가 {period.leaveDays}일</span><span>미확정 {period.unresolvedDays}일</span></div>,
          <>{period.decision ? decisionNames[period.decision] : '판단 대기'}{period.reason && <small>{period.reason}</small>}
            {period.decision === 'INCLUDED' && period.includedBucketId && <small><a href={'/admin/leave-balances/buckets/' + encodeURIComponent(period.includedBucketId)}>
              {sources.find(item => item.bucketId === period.includedBucketId)?.sourceReference ?? '기존 부여 원장'}</a></small>}</>,
          period.decision === 'INCLUDED' ? '개근 1일 · 추가 0일' : period.decision && period.grantedDays != null ? period.grantedDays + '일' : '미확정',
          period.decision ? '확정' : canEdit && period.token
            ? <button type="button" className="t-btn" aria-label={period.monthNumber + '개월차 검토'} disabled={posting.locked}
                aria-expanded={selected === period.monthNumber} onClick={() => { posting.clearError(); setSelected(period.monthNumber); }}>검토</button>
            : '조회',
        ] }))} />
      {canEdit && reviewing && <MonthEditor key={reviewing.accrualOn} period={reviewing} employeeId={monthly.employeeId}
        sources={sources.filter(source => source.accrualDates?.includes(reviewing.accrualOn))} posting={posting} refresh={refresh} />}
    </> : <p>입사일과 마지막 재직일을 기준으로 발생일이 도래한 월별 기간이 없습니다.</p>}
  </section>;
}
