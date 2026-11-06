import { useId, useRef, useState, useSyncExternalStore } from 'react';
import { Field, Form, SelectField, TextAreaField } from '../ui';
import './PersonalDetails.css';

export interface PersonalDetailsForm {
  admissionType?: string; recognizedMonths?: number | null; groupJoinedOn?: string | null;
  englishName?: string | null; maritalStatus?: string; marriedOn?: string | null;
  veteranStatus?: string; disabilityStatus?: string; militaryStatus?: string; militaryBranch?: string | null;
  mobilePhone?: string | null; postalCode?: string | null; address?: string | null; addressDetail?: string | null;
  contactEmail?: string | null; expectedVersion?: number; reason?: string; commandId?: string;
}
interface Entry {
  id: string; kind: string; title: string; detail: string | null;
  effectiveFrom: string; effectiveTo: string | null; revision: number;
}
interface EntryForm {
  kind: string; title: string; detail: string | null; effectiveFrom: string; effectiveTo?: string | null;
  expectedVersion: number; reason: string; commandId: string;
}
export interface PersonalDetailsModel {
  employeeId: string; version: number; form: PersonalDetailsForm; currentForm?: PersonalDetailsForm;
  entries: Entry[]; appointments: { id: string; organizationName: string; validFrom: string; validTo: string | null }[];
  entryForm?: EntryForm;
}
interface Identity { employeeName: string; joinedOn: string; birthDate?: string | null }
const tabs = ['기본사항', '발령내역', '가족사항', '학력사항', '자격사항', '어학사항', '주소·연락처'];
const wideSheet = '(min-width: 70rem)';

function subscribeToSheetWidth(notify: () => void) {
  const media = window.matchMedia?.(wideSheet);
  media?.addEventListener('change', notify);
  return () => media?.removeEventListener('change', notify);
}

function sheetIsWide() { return window.matchMedia?.(wideSheet).matches ?? false; }

const kinds = ['FAMILY', 'EDUCATION', 'QUALIFICATION', 'LANGUAGE'];
const choices: Record<string, string> = {
  UNKNOWN: '미등록', YES: '해당', NO: '해당 없음', NEW: '신입', EXPERIENCED: '경력', TRANSFER: '전입',
  COMPLETED: '군필', EXEMPT: '면제', PENDING: '미필', NOT_APPLICABLE: '해당 없음',
};
const sheetIcons = {
  identity: 'M4 5h16v14H4z M7 16v-1a3 3 0 0 1 6 0v1 M10 12a2 2 0 1 0 0-4 2 2 0 0 0 0 4 M15 10h2 M15 14h2',
  employment: 'M4 8h16v12H4z M9 8V5h6v3 M4 12a19 19 0 0 0 16 0 M12 12v2',
  additional: 'M7 4h10v16H7z M10 8h4 M10 12h4 M10 16h4',
  address: 'M19 10c0 5-7 10-7 10S5 15 5 10a7 7 0 1 1 14 0Z M12 12a2 2 0 1 0 0-4 2 2 0 0 0 0 4',
  contact: 'M4 6h16v12H4z M4 7l8 6 8-6',
};
function display(value: string | number | null | undefined) { return value == null || value === '' ? '미등록' : value; }
function status(value: string | undefined) { return choices[value ?? 'UNKNOWN'] ?? '미등록'; }
function Info({ values }: { values: [string, string | number | null | undefined][] }) {
  return <dl className="t-person-info">{values.map(([label, value]) => <div key={label}><dt>{label}</dt><dd data-empty={display(value) === '미등록' || undefined}>{display(value)}</dd></div>)}</dl>;
}
function InfoGroup({ title, icon, values }: { title: string; icon: keyof typeof sheetIcons; values: [string, string | number | null | undefined][] }) {
  const titleId = useId();
  return <section className="t-personal-group" aria-labelledby={titleId}>
    <h3 id={titleId}>
      <span className="t-personal-group-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d={sheetIcons[icon]} /></svg></span>
      {title}
    </h3>
    <Info values={values} />
  </section>;
}
function EmptyDetails({ title }: { title: string }) {
  return <div className="t-personal-empty">
    <span aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M9 4h6l2 3h3v13H4V7h3l2-3Z M9 4v4h6V4 M8 12h8 M8 16h5" /></svg></span>
    <p>등록된 {title}이 없습니다.</p><small>등록된 정보가 생기면 이곳에 표시됩니다.</small>
  </div>;
}

export function PersonalDetailsTabs({ model: m, identity }: { model: PersonalDetailsModel; identity: Identity }) {
  const [tab, setTab] = useState(0), id = useId(), buttons = useRef<(HTMLButtonElement | null)[]>([]);
  const vertical = useSyncExternalStore(subscribeToSheetWidth, sheetIsWide, () => false);
  const f = m.form;
  function move(event: React.KeyboardEvent, index: number) {
    let next = index;
    if (event.key === 'ArrowRight' || (vertical && event.key === 'ArrowDown')) next = (index + 1) % tabs.length;
    else if (event.key === 'ArrowLeft' || (vertical && event.key === 'ArrowUp')) next = (index + tabs.length - 1) % tabs.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = tabs.length - 1;
    else return;
    event.preventDefault(); setTab(next); buttons.current[next]?.focus();
  }
  const entries = m.entries.filter(entry => entry.kind === kinds[tab - 2]);
  const descriptions = ['개인 정보와 입사 정보를 한눈에 확인하세요.', '소속 변경 이력을 적용 기간별로 확인하세요.', '등록된 가족 관계와 상세 정보를 확인하세요.', '등록된 학력과 전공 정보를 확인하세요.', '보유 자격과 발급 정보를 확인하세요.', '등록된 언어와 평가 정보를 확인하세요.', '등록된 주소와 연락 수단을 확인하세요.'];
  return <section className="t-card t-private-personnel" aria-label="상세 인사정보">
    <div className="t-personnel-index">
      <p className="t-personnel-index-title">인사기록</p>
      <div className="t-personnel-tabs" role="tablist" aria-label="인사정보 항목" aria-orientation={vertical ? 'vertical' : 'horizontal'}>
        {tabs.map((label, index) => (
          <button className="t-btn" key={label} type="button" role="tab" id={`${id}-tab-${index}`} aria-controls={`${id}-panel`}
            aria-selected={tab === index} tabIndex={tab === index ? 0 : -1} ref={node => { buttons.current[index] = node; }}
            onClick={() => setTab(index)} onKeyDown={event => move(event, index)}>{label}</button>
        ))}
      </div>
    </div>
    <div role="tabpanel" id={`${id}-panel`} aria-labelledby={`${id}-tab-${tab}`} tabIndex={0} className="t-personnel-tabpanel">
      <div className="t-personal-panel-heading"><div><h2>{tabs[tab]}</h2><p>{descriptions[tab]}</p></div>
        {tab > 0 && tab < 6 && <span className="t-personal-count">총 {tab === 1 ? m.appointments.length : entries.length}건</span>}
      </div>
      {tab === 0 && <>
        <div className="t-personal-groups">
          <InfoGroup title="신원 정보" icon="identity" values={[
            ['한글성명', identity.employeeName], ['영문성명', f.englishName], ['생년월일', identity.birthDate], ['휴대전화', f.mobilePhone],
          ]} />
          <InfoGroup title="입사 정보" icon="employment" values={[
            ['입사구분', status(f.admissionType)], ['인정경력 (개월)', f.recognizedMonths], ['회사 입사일', identity.joinedOn], ['그룹입사일', f.groupJoinedOn],
          ]} />
          <InfoGroup title="추가 정보" icon="additional" values={[
            ['결혼여부', f.maritalStatus === 'YES' ? '기혼' : f.maritalStatus === 'NO' ? '미혼' : '미등록'], ['결혼일자', f.marriedOn],
            ['보훈여부', status(f.veteranStatus)], ['장애여부', status(f.disabilityStatus)], ['병역여부', status(f.militaryStatus)], ['군별', f.militaryBranch],
          ]} />
        </div>
        <p className="t-personal-privacy"><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="m12 3 8 3v5c0 5-4 8-8 10-4-2-8-5-8-10V6l8-3Z m-3 9 2 2 4-4" /></svg><span>주민등록번호 <strong>미수집</strong> · 원문 입력·저장하지 않습니다.</span></p>
      </>}
      {tab === 1 && (m.appointments.length ? <div className="t-personal-table"><table aria-label="발령내역"><thead><tr><th scope="col">발령일</th><th scope="col">종료일 (미포함)</th><th scope="col">소속</th></tr></thead><tbody>{m.appointments.map(a => <tr key={a.id}><td data-label="발령일">{a.validFrom}</td><td data-label="종료일 (미포함)">{a.validTo ?? <span className="t-personal-current">현재</span>}</td><td data-label="소속"><strong>{a.organizationName}</strong></td></tr>)}</tbody></table></div> : <EmptyDetails title={tabs[tab]} />)}
      {tab >= 2 && tab <= 5 && (entries.length ? <div className="t-personal-table"><table aria-label={tabs[tab]}><thead><tr><th scope="col">항목</th><th scope="col">상세</th><th scope="col">적용일</th><th scope="col">종료일</th><th scope="col">이력 버전</th></tr></thead><tbody>{entries.map(e => <tr key={e.id}><td data-label="항목"><strong>{e.title}</strong></td><td data-label="상세">{display(e.detail)}</td><td data-label="적용일">{e.effectiveFrom}</td><td data-label="종료일">{display(e.effectiveTo)}</td><td data-label="이력 버전"><span className="t-personal-version">{e.revision}</span></td></tr>)}</tbody></table></div> : <EmptyDetails title={tabs[tab]} />)}
      {tab === 6 && <div className="t-personal-groups">
        <InfoGroup title="주소 정보" icon="address" values={[
          ['우편번호', f.postalCode], ['주소', f.address], ['상세주소', f.addressDetail],
        ]} />
        <InfoGroup title="연락 정보" icon="contact" values={[
          ['휴대전화', f.mobilePhone], ['연락 이메일', f.contactEmail],
        ]} />
      </div>}
    </div>
  </section>;
}

function Options({ values }: { values: string[] }) { return <>{values.map(value => <option key={value} value={value}>{choices[value]}</option>)}</>; }
export function PersonalDetailsEditor({ model: m, today, busy }: { model: PersonalDetailsModel; today: string; busy: boolean }) {
  const [reset, setReset] = useState(0), [entryReset, setEntryReset] = useState(0);
  const f = reset ? m.currentForm ?? m.form : m.form, e = entryReset ? undefined : m.entryForm;
  const [version, setVersion] = useState(f.expectedVersion ?? m.version);
  const [command, setCommand] = useState(f.commandId ?? crypto.randomUUID());
  const [dirty, setDirty] = useState(Boolean(m.currentForm && JSON.stringify(m.form) !== JSON.stringify(m.currentForm)));
  const [entryVersion, setEntryVersion] = useState(e?.expectedVersion ?? m.version);
  const [entryCommand, setEntryCommand] = useState(e?.commandId ?? crypto.randomUUID());
  const [entryDirty, setEntryDirty] = useState(Boolean(e && (e.title || e.detail || e.reason || e.expectedVersion !== m.version)));
  const conflict = version !== m.version;
  const path = `/admin/employees/${encodeURIComponent(m.employeeId)}`;
  function changed() { setCommand(crypto.randomUUID()); setDirty(true); }
  function entryChanged() { setEntryCommand(crypto.randomUUID()); setEntryDirty(true); }
  const entryConflict = entryVersion !== m.version;
  return <>
    <section className="t-card t-personnel-section">
      <h2>상세 기본사항·주소·연락처</h2>
      <p className="t-muted">본인과 담당자만 조회합니다. 한글성명·회사 입사일은 기본정보·소속에서, 생년월일은 위 인사정보에서 관리합니다. 주민등록번호는 수집하지 않습니다.</p>
      <Form key={reset} action={`${path}/personal-details`} onChange={changed} data-unsaved-changes={dirty ? 'true' : undefined}
        onReset={event => { event.preventDefault(); setDirty(false); setVersion(m.version); setCommand(crypto.randomUUID()); setReset(value => value + 1); }}>
        <input type="hidden" name="expectedVersion" value={version} /><input type="hidden" name="commandId" value={command} />
        <fieldset disabled={busy}><div className="t-personnel-fields">
          <div><SelectField label="입사구분" name="admissionType" value={f.admissionType ?? 'UNKNOWN'}><Options values={['UNKNOWN', 'NEW', 'EXPERIENCED', 'TRANSFER']} /></SelectField></div>
          <div><Field label="인정경력 (개월)" name="recognizedMonths" type="number" value={f.recognizedMonths ?? null} min={0} max={1200} /></div>
          <div><Field label="그룹입사일" name="groupJoinedOn" type="date" value={f.groupJoinedOn ?? null} /></div>
          <div><Field label="영문성명" name="englishName" value={f.englishName ?? null} maxLength={120} /></div>
          <div><SelectField label="결혼여부" name="maritalStatus" value={f.maritalStatus ?? 'UNKNOWN'}><option value="UNKNOWN">미등록</option><option value="YES">기혼</option><option value="NO">미혼</option></SelectField></div>
          <div><Field label="결혼일자" name="marriedOn" type="date" value={f.marriedOn ?? null} max={today} /></div>
          <div><SelectField label="보훈여부" name="veteranStatus" value={f.veteranStatus ?? 'UNKNOWN'}><Options values={['UNKNOWN', 'YES', 'NO']} /></SelectField></div>
          <div><SelectField label="장애여부" name="disabilityStatus" value={f.disabilityStatus ?? 'UNKNOWN'}><Options values={['UNKNOWN', 'YES', 'NO']} /></SelectField></div>
          <div><SelectField label="병역여부" name="militaryStatus" value={f.militaryStatus ?? 'UNKNOWN'}><Options values={['UNKNOWN', 'COMPLETED', 'EXEMPT', 'PENDING', 'NOT_APPLICABLE']} /></SelectField></div>
          <div><Field label="군별" name="militaryBranch" value={f.militaryBranch ?? null} maxLength={60} /></div>
          <div><Field label="휴대전화" name="mobilePhone" type="tel" value={f.mobilePhone ?? null} maxLength={40} /></div>
          <div><Field label="우편번호" name="postalCode" value={f.postalCode ?? null} maxLength={20} /></div>
          <div><Field label="주소" name="address" value={f.address ?? null} maxLength={200} /></div>
          <div><Field label="상세주소" name="addressDetail" value={f.addressDetail ?? null} maxLength={200} /></div>
          <div><Field label="연락 이메일" name="contactEmail" type="email" value={f.contactEmail ?? null} maxLength={254} /></div>
        </div><TextAreaField label="상세정보 변경 사유" name="reason" value={f.reason ?? ''} maxLength={500} required /></fieldset>
        {conflict && <div role="alert"><p>다른 상세정보가 저장되었습니다. 입력을 유지했습니다. 현재 저장값을 확인한 뒤 다시 저장하세요.</p><Info values={[
          ['현재 입사구분', status(m.currentForm?.admissionType)], ['현재 인정경력 (개월)', m.currentForm?.recognizedMonths],
          ['현재 그룹입사일', m.currentForm?.groupJoinedOn], ['현재 영문성명', m.currentForm?.englishName],
          ['현재 결혼여부', m.currentForm?.maritalStatus === 'YES' ? '기혼' : m.currentForm?.maritalStatus === 'NO' ? '미혼' : '미등록'],
          ['현재 결혼일자', m.currentForm?.marriedOn], ['현재 보훈여부', status(m.currentForm?.veteranStatus)],
          ['현재 장애여부', status(m.currentForm?.disabilityStatus)], ['현재 병역여부', status(m.currentForm?.militaryStatus)],
          ['현재 군별', m.currentForm?.militaryBranch], ['현재 휴대전화', m.currentForm?.mobilePhone],
          ['현재 우편번호', m.currentForm?.postalCode], ['현재 주소', m.currentForm?.address],
          ['현재 상세주소', m.currentForm?.addressDetail], ['현재 연락 이메일', m.currentForm?.contactEmail],
        ]} /><button type="button" disabled={busy} onClick={() => { setVersion(m.version); changed(); }}>상세정보 최신 버전 확인</button></div>}
        <div className="actions"><button type="reset" className="t-btn" disabled={busy}>상세정보 입력 취소</button><button type="submit" className="t-btn primary" disabled={busy || conflict}>상세정보 저장</button></div>
      </Form>
    </section>
    <section className="t-card t-personnel-section"><h2>가족·학력·자격·어학 이력 등록</h2>
      <p className="t-muted">필요한 최소 항목만 기록하세요. 이름·주민번호 등 불필요한 개인 식별정보를 입력하지 마세요. 수정 사항은 새 이력으로 추가하여 기존 기록을 보존합니다.</p>
      <Form key={entryReset} action={`${path}/personal-entries`} onChange={entryChanged} data-unsaved-changes={entryDirty ? 'true' : undefined}
        onReset={event => { event.preventDefault(); setEntryDirty(false); setEntryVersion(m.version); setEntryCommand(crypto.randomUUID()); setEntryReset(value => value + 1); }}>
        <input type="hidden" name="expectedVersion" value={entryVersion} /><input type="hidden" name="commandId" value={entryCommand} />
        <fieldset disabled={busy}><div className="t-personnel-fields">
          <div><SelectField label="이력 구분" name="kind" value={e?.kind ?? 'FAMILY'}>{kinds.map((kind, i) => <option key={kind} value={kind}>{tabs[i + 2]}</option>)}</SelectField></div>
          <div><Field label="항목명" name="title" value={e?.title ?? ''} maxLength={120} placeholder="관계·학교명·자격명·언어" required /></div>
          <div><Field label="상세" name="detail" value={e?.detail ?? null} maxLength={200} placeholder="인원·전공/졸업·발급기관·등급/점수" /></div>
          <div><Field label="적용 시작일" name="effectiveFrom" type="date" value={e?.effectiveFrom ?? today} required /></div>
          <div><Field label="적용 종료일" name="effectiveTo" type="date" value={e?.effectiveTo ?? null} /></div>
        </div><TextAreaField label="이력 등록 사유" name="reason" value={e?.reason ?? ''} maxLength={500} required /></fieldset>
        <div className="actions"><button type="reset" className="t-btn" disabled={busy}>이력 입력 취소</button><button type="submit" className="t-btn primary" disabled={busy || entryConflict}>이력 추가</button></div>
        {entryConflict && <section className="t-personnel-conflict" aria-label="최신 이력 비교"><h3>다른 이력이 반영되었습니다</h3>
          <p>입력은 유지했습니다. 현재 버전 {m.version}의 등록된 이력을 확인한 뒤 다시 저장하세요.</p>
          {m.entries.length ? <ul>{m.entries.map(entry => <li key={entry.id}><strong>{tabs[kinds.indexOf(entry.kind) + 2]}: {entry.title}</strong><p>{display(entry.detail)}</p><p>{entry.effectiveFrom}, 버전 {entry.revision}</p></li>)}</ul> : <p>현재 등록된 이력이 없습니다.</p>}
          <button type="button" className="t-btn" disabled={busy} onClick={() => { setEntryVersion(m.version); entryChanged(); }}>이력 최신 버전 확인 후 입력 유지</button>
        </section>}
      </Form>
    </section>
  </>;
}
