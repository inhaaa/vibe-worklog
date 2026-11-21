import { PageHeader } from '../components/PageHeader';
import { Field, Form, fmtDate } from '../ui';
import { AnnualLeaveTable } from './AnnualLeaveTable';
import type { PersonalLedger } from './annualLeaveTypes';
import './AnnualLeave.css';

const kinds: Record<string, string> = {
  OPENING: '기초 등록', ADJUSTMENT: '잔여 조정', GRANT: '연차 부여', CARRY_IN: '전년도에서 이월',
  CARRY_OUT: '다음 연도로 이월', EXPIRE: '연차 소멸', USE: '휴가 사용', RESTORE: '취소 복원',
};
export const ledgerKindName = (kind: string) => kinds[kind] ?? kind;

export function PersonalLeaveLedger({ ledger }: { ledger: PersonalLedger }) {
  return <div className="t-annual-page annual-leave-page">
    <PageHeader title="내 연차 원장" actions={<a className="t-btn" href="/me/requests">내 신청 이력</a>} />
    <section className="annual-ledger-balance" aria-labelledby="annual-usable">
      <div><h2 id="annual-usable">오늘 사용 가능 연차</h2><strong>{ledger.availableDays}일</strong></div>
      <span><time dateTime={ledger.today}>{fmtDate(ledger.today)}</time> 기준 · 오늘 사용할 수 있는 모든 묶음의 합계</span>
    </section>
    <Form action="/me/leave-ledger" method="get" className="wl-querybar" role="search" aria-label="연차 원장 조회">
      <div><Field label="조회 연도" name="year" type="number" min={1900} max={9998} value={ledger.year} required /></div>
      <div className="wl-querybar-actions"><button type="submit" className="t-btn wl-query-submit">조회</button></div>
    </Form>
    <section className="wl-section" aria-labelledby="annual-entries-title">
      <h2 id="annual-entries-title">연차 변동 이력</h2>
      <p className="hint">{ledger.year}년 귀속 묶음의 내역과 해당 회계기간에 효력이 생긴 변동을 표시합니다.</p>
      {ledger.entries.length ? <AnnualLeaveTable label="연차 변동 이력"
        columns={['구분', '일수 증감', '귀속연도', '효력 기준일', '휴가 사용일', '근거·사유']}
        rows={ledger.entries.map(entry => ({ id: entry.id, cells: [
          <strong>{ledgerKindName(entry.entryKind)}</strong>,
          (entry.signedQuantityDays > 0 ? '+' : '') + entry.signedQuantityDays + '일',
          entry.entitlementYear == null ? '귀속 미확인' : entry.entitlementYear + '년',
          fmtDate(entry.effectiveOn), entry.leaveDate ? fmtDate(entry.leaveDate) : '해당 없음',
          <>{entry.sourceReference && <span className="annual-entry-source">{entry.sourceReference}</span>}
            {entry.reason || '사유 미등록'}</>,
        ] }))} /> : <p>{ledger.year}년에 조회할 연차 변동이 없습니다.</p>}
    </section>
  </div>;
}
