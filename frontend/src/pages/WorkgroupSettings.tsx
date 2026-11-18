import { useState } from 'react';
import type { CsrfToken } from '../types';
import { WorkTimeCodesTab } from './WorkTimeCodesTab';
import { GroupEditor, MemberAssignment } from './WorkgroupOperations';
import { useScheduleQuery } from './workgroupApi';
import type { Group } from './workgroupApi';
import './WorkgroupSettings.css';

const tabs = { codes: '근무시간코드', groups: '근무조', assignments: '사원 배정' } as const;
type Tab = keyof typeof tabs;
export function WorkgroupSettings({ today, csrf }: { today: string; csrf: CsrfToken }) {
  const [initial] = useState(() => new URLSearchParams(window.location.search));
  const requestedTab = initial.get('tab');
  const requestedDate = initial.get('date') || '';
  const [tab, setTab] = useState<Tab>(requestedTab === 'groups' || requestedTab === 'assignments' ? requestedTab : 'codes');
  const [date, setDate] = useState(/^\d{4}-\d{2}-\d{2}$/.test(requestedDate) && Number.isFinite(Date.parse(requestedDate)) ? requestedDate : today);
  const [selected, setSelected] = useState(initial.get('groupId') || '');
  const [busy, setBusy] = useState(false);
  const [receipt, setReceipt] = useState('');
  const groups = useScheduleQuery<Group[]>('/groups?date=' + date);
  const group = groups.data?.find(item => item.id === selected);
  function groupSaved(id: string) {
    setSelected(id); setReceipt(date + '부터 적용할 근무조 개정을 저장했습니다.'); groups.refresh();
  }
  return <div className="wg-settings">
    <fieldset className="wg-controls" disabled={busy}>
      <nav className="wg-tabs" aria-label="근무 설정 업무">{(Object.keys(tabs) as Tab[]).map(id =>
        <button key={id} type="button" className={tab === id ? 't-btn primary' : 't-btn'} aria-pressed={tab === id}
          onClick={() => { setTab(id); setReceipt(''); }}>{tabs[id]}</button>)}</nav>
      <div className="wg-fields">
        <label>조회·적용 기준일<input type="date" required value={date} onChange={e => {
          if (e.target.value && /^\d{4}-\d{2}-\d{2}$/.test(e.target.value)) { setDate(e.target.value); setReceipt(''); }
        }} /></label>
        {tab !== 'codes' && <label>근무조 선택<select disabled={!groups.data} value={selected} onChange={e => { setSelected(e.target.value); setReceipt(''); }}>
          <option value="">{tab === 'groups' ? '새 근무조' : '근무조를 선택하세요'}</option>
          {(groups.data || []).map(g => <option key={g.id} value={g.id}>{g.code} · {g.name}</option>)}</select></label>}
      </div>
    </fieldset>
    {receipt && <p role="status">{receipt}</p>}
    {tab === 'codes' ? <WorkTimeCodesTab key={date} date={date} today={today} csrf={csrf} onBusy={setBusy} />
      : groups.error ? <p role="alert">{groups.error} <button className="t-btn" onClick={groups.refresh}>근무조 다시 조회</button></p>
      : !groups.data ? <p role="status">근무조 조회 중</p>
      : tab === 'groups' ? <GroupEditor key={date + selected + (group?.version ?? '')} date={date} today={today} csrf={csrf} group={group} onBusy={setBusy}
          onSaved={groupSaved} onRefresh={groups.refresh} />
      : <MemberAssignment key={date + selected} date={date} today={today} csrf={csrf} group={group} initialQuery={initial.get('q') || ''}
          onBusy={setBusy} onSaved={() => setReceipt(date + '부터 적용할 사원 배정을 저장했습니다.')} />}
  </div>;
}
