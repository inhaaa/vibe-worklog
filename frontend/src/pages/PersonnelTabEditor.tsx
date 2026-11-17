import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import { ErrorNotice, Form } from '../ui';
import type { AdminPersonnelView, PersonnelTabId } from './AdminPersonnelPage';
import type { PersonalDetailsModel } from './PersonalDetails';
import type { PersonnelModel } from './PersonnelPage';

export type TabDraft = Record<string, string>;

const choices = {
  employment: [['', '유지'], ['WORKING', '재직'], ['ON_LEAVE', '휴직'], ['SUSPENDED', '정직'], ['UNKNOWN', '미등록']],
  admission: [['UNKNOWN', '미등록'], ['NEW', '신입'], ['EXPERIENCED', '경력'], ['TRANSFER', '전입']],
  marital: [['UNKNOWN', '미등록'], ['YES', '기혼'], ['NO', '미혼']],
  yesNo: [['UNKNOWN', '미등록'], ['YES', '해당'], ['NO', '해당 없음']],
  military: [['UNKNOWN', '미등록'], ['COMPLETED', '복무 완료'], ['EXEMPT', '면제'], ['PENDING', '미필'], ['NOT_APPLICABLE', '해당 없음']],
  relationship: [['SPOUSE', '배우자'], ['CHILD', '자녀'], ['PARENT', '부모'], ['OTHER', '기타']],
};

const entryLabels = {
  education: ['학교명', '전공·졸업 구분', '적용 시작일', '종료일'],
  qualification: ['자격명', '발급기관', '취득일', '만료일'],
  language: ['언어·시험명', '등급 또는 점수', '취득일', '만료일'],
} as const;

export function PersonnelTabEditor({ tab, selection, personnel, details, today, busy, draft, conflict, error, cancel }: {
  tab: PersonnelTabId; selection: AdminPersonnelView; personnel: PersonnelModel; details: PersonalDetailsModel;
  today: string; busy: boolean; draft?: TabDraft; conflict: boolean; error?: string; cancel: () => void;
}) {
  const [dirty, setDirty] = useState(Boolean(draft));
  const [reviewed, setReviewed] = useState(false);
  const [commandId, setCommandId] = useState(() => draft?.commandId || crypto.randomUUID());
  const [photoAction, setPhotoAction] = useState(draft?.photoAction || 'KEEP');
  const [photoData, setPhotoData] = useState(draft?.photoData || '');
  const [photoError, setPhotoError] = useState('');
  const [reading, setReading] = useState(false);
  const reader = useRef<FileReader | null>(null);
  const f: Record<string, unknown> = { ...details.form, ...personnel.form, ...selection.employee };
  const currentVersion = tab === 'basic'
    ? { employeeVersion: selection.employee.version, personnelVersion: personnel.version, detailsVersion: details.version }
    : tab === 'appointments' ? { employeeVersion: selection.employee.version } : { detailsVersion: details.version };

  useEffect(() => {
    const unload = (event: BeforeUnloadEvent) => {
      if (dirty) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', unload);
    return () => window.removeEventListener('beforeunload', unload);
  }, [dirty]);
  useEffect(() => () => reader.current?.abort(), []);

  function changed() {
    setDirty(true);
    setCommandId(crypto.randomUUID());
  }

  function photo(event: ChangeEvent<HTMLInputElement>) {
    reader.current?.abort();
    const selected = event.target.files?.[0];
    setPhotoError('');
    setReading(false);
    if (!selected) return;
    if (!['image/png', 'image/jpeg'].includes(selected.type) || !selected.size || selected.size > 131072) {
      setPhotoError('PNG 또는 JPG 사진을 128KB 이하로 선택해 주세요.');
      return;
    }
    const next = new FileReader();
    reader.current = next;
    setReading(true);
    next.onload = () => {
      setPhotoData(String(next.result).split(',')[1] ?? '');
      setPhotoAction('REPLACE');
      setReading(false);
    };
    next.onerror = () => { setPhotoError('사진을 읽지 못했습니다. 다시 선택해 주세요.'); setReading(false); };
    next.readAsDataURL(selected);
  }

  const latest = (name: string, fallback = '') => String(f[name] ?? fallback);
  const value = (name: string, fallback?: string) => draft?.[name] ?? latest(name, fallback);
  const input = (name: string, label: string, options: React.InputHTMLAttributes<HTMLInputElement> = {}, fallback?: string) =>
    <label key={name}>{label}<input name={name} defaultValue={value(name, fallback)} {...options} /></label>;
  const select = (name: string, label: string, options: string[][], fallback?: string) =>
    <label>{label}<select name={name} defaultValue={draft?.[name] ?? fallback ?? latest(name)}>
      {options.map(([option, text]) => <option key={option} value={option}>{text}</option>)}
    </select></label>;
  const date = (name: string, label: string, required = false, fallback?: string) =>
    input(name, label, { type: 'date', required }, fallback);

  return <Form action={`/admin/employees/${selection.employee.id}/personnel/tabs/${tab}`}
    className="t-personnel-tab-editor" data-unsaved-changes={dirty ? 'true' : undefined} onChange={changed}>
    <ErrorNotice message={error || photoError} />
    {conflict && <div className="notice" role="status">
      <p>다른 변경을 먼저 저장했습니다. 이 탭의 저장값과 입력값을 확인해 주세요.</p>
      {Object.keys(draft ?? {}).filter(name => name in f && name !== 'photoData' && latest(name) !== draft?.[name])
        .map(name => <p key={name}>{latest(name) || '미등록'} → {draft?.[name] || '미등록'}</p>)}
      <button type="button" className="t-btn" disabled={reviewed || busy} onClick={() => { setReviewed(true); changed(); }}>최신값 확인 후 편집 유지</button>
    </div>}
    <input type="hidden" name="commandId" value={commandId} />
    {Object.entries(currentVersion).map(([name, version]) => <input type="hidden" key={name} name={name}
      value={(reviewed ? undefined : draft?.[name]) ?? version} />)}
    <fieldset className="t-personnel-edit-fields" disabled={busy}>
      <legend className="sr-only">현재 탭 입력 항목</legend>
      {tab === 'basic' && <>
        {input('displayName', '한글성명', { required: true, maxLength: 120 })}
        {input('englishName', '영문성명', { maxLength: 120 })}
        {input('joinedOn', '회사 입사일', { type: 'date', required: true, readOnly: selection.joinedOnLocked })}
        {date('leftOn', '마지막 재직일')}
        {date('birthDate', '생년월일')}
        {input('gradeName', '직급', { maxLength: 120 })}
        {input('positionName', '직책', { maxLength: 120 })}
        {input('jobName', '직무', { maxLength: 120 })}
        {select('employmentStatus', '재직 상태', choices.employment, '')}
        {input('effectiveFrom', '재직 상태 적용일', { type: 'date', min: today }, today)}
        {select('admissionType', '입사구분', choices.admission)}
        {input('recognizedMonths', '인정경력 (개월)', { type: 'number', min: 0, max: 1200 })}
        {date('groupJoinedOn', '그룹입사일')}
        {select('maritalStatus', '결혼여부', choices.marital)}
        {date('marriedOn', '결혼일자')}
        {select('veteranStatus', '보훈여부', choices.yesNo)}
        {select('disabilityStatus', '장애여부', choices.yesNo)}
        {select('militaryStatus', '병역여부', choices.military)}
        {input('militaryBranch', '군별', { maxLength: 60 })}
        <label>사원사진 선택<input type="file" accept="image/png,image/jpeg" onChange={photo} /></label>
        <input type="hidden" name="photoAction" value={photoAction} />
        <input type="hidden" name="photoData" value={photoData} />
        <button className="t-btn" type="button" onClick={() => {
          reader.current?.abort(); setReading(false); setPhotoError(''); setPhotoAction('CLEAR'); setPhotoData(''); changed();
        }}>등록된 사진 지우기</button>
      </>}
      {tab === 'appointments' && <>
        <label>배정 조직<select name="organizationId" defaultValue={draft?.organizationId ?? ''} required>
          <option value="">조직을 선택하세요</option>
          {(selection.organizations ?? []).map(org => <option value={org.id} key={org.id}>{org.code} · {org.name}</option>)}
        </select></label>
        {input('validFrom', '적용 시작일', { type: 'date', required: true, min: today }, today)}
      </>}
      {tab === 'family' && <>
        {select('relationship', '관계', choices.relationship, 'SPOUSE')}
        {input('count', '인원', { type: 'number', min: 1, step: 1, required: true }, '1')}
        {input('effectiveFrom', '적용 시작일', { type: 'date', required: true, defaultValue: draft?.effectiveFrom ?? today })}
        {date('effectiveTo', '종료일')}
      </>}
      {tab in entryLabels && (() => {
        const [title, detail, from, to] = entryLabels[tab as keyof typeof entryLabels];
        return <>
          {input('title', title, { required: true, maxLength: 120 })}
          {input('detail', detail, { maxLength: 177 })}
          {input('effectiveFrom', from, { type: 'date', required: true, defaultValue: draft?.effectiveFrom ?? today })}
          {date('effectiveTo', to)}
        </>;
      })()}
      {tab === 'contact' && <>
        {input('postalCode', '우편번호', { maxLength: 20 })}
        {input('address', '기본주소', { maxLength: 200 })}
        {input('addressDetail', '상세주소', { maxLength: 200 })}
        {input('mobilePhone', '휴대전화', { type: 'tel', maxLength: 40 })}
        {input('contactEmail', '연락 이메일', { inputMode: 'email', maxLength: 254 })}
      </>}
      <label className="t-personnel-edit-reason">변경 사유<textarea name="reason" required maxLength={500} defaultValue={draft?.reason} /></label>
    </fieldset>
    <div className="wl-form-actions">
      <button type="button" className="t-btn" disabled={busy} onClick={cancel}>취소</button>
      <button type="submit" className="t-btn primary" disabled={busy || reading || Boolean(photoError) || (conflict && !reviewed)}>저장</button>
    </div>
  </Form>;
}
