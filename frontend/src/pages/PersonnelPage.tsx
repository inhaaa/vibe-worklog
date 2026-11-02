import type { PageProps } from '../types';
import { useEffect, useRef, useState } from 'react';
import { ErrorNotice, Field, Form, SelectField, TextAreaField, fmtDateTime } from '../ui';
import { employmentStatusName } from '../components/EmployeeHeader';
export interface PersonnelForm {
  birthDate: string | null; gradeName: string | null; positionName: string | null; jobName: string | null;
  employmentStatus: string; effectiveFrom: string; expectedVersion: number; reason: string;
  commandId: string; photoAction: string; photoData: string;
}
export interface PersonnelModel {
  employeeId: string; version: number; today: string; form: PersonnelForm; currentForm?: PersonnelForm;
  statusHistory: { id: string; employmentStatus: string; effectiveFrom: string; revision: number; reason: string; createdAt: string }[];
}
export function PersonnelPage({ page, data, busy = false }: PageProps) {
  if (page !== 'org/personnel') return null;
  const personnel = data.personnel as PersonnelModel;
  return <PersonnelEditor model={{ ...personnel, today: data.today as string ?? personnel.today }} error={data.error as string | undefined} busy={busy} />;
}

function PersonnelEditor({ model: m, error, busy }: { model: PersonnelModel; error?: string; busy: boolean }) {
  const f = m.form;
  const [version, setVersion] = useState(f.expectedVersion), [command, setCommand] = useState(f.commandId);
  const [dirty, setDirty] = useState(Boolean(error));
  const [photoAction, setPhotoAction] = useState(f.photoAction || 'KEEP'), [photoData, setPhotoData] = useState(f.photoData || '');
  const [photoError, setPhotoError] = useState(''), [photoName, setPhotoName] = useState(''), [reading, setReading] = useState(false);
  const file = useRef<HTMLInputElement>(null), reader = useRef<FileReader | null>(null);
  const conflict = version !== m.version;
  const path = `/admin/employees/${encodeURIComponent(m.employeeId)}`;
  function changed() { setDirty(true); setCommand(crypto.randomUUID()); }
  useEffect(() => {
    const unload = (event: BeforeUnloadEvent) => { if (dirty) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', unload);
    return () => window.removeEventListener('beforeunload', unload);
  }, [dirty]);
  useEffect(() => () => reader.current?.abort(), []);
  function choosePhoto(selected?: File) {
    reader.current?.abort();
    setPhotoError(''); setReading(false);
    if (!selected) return;
    changed();
    if (!['image/png', 'image/jpeg'].includes(selected.type) || !selected.size || selected.size > 131072) {
      setPhotoError('PNG 또는 JPG 사진을 128KB 이하로 선택해 주세요.');
      return;
    }
    const next = new FileReader(); reader.current = next; setReading(true);
    next.onload = () => {
      setPhotoData(String(next.result).split(',')[1] ?? ''); setPhotoAction('REPLACE');
      setPhotoName(selected.name); setReading(false);
    };
    next.onerror = () => { setPhotoError('사진을 읽지 못했습니다. 다시 선택해 주세요.'); setReading(false); };
    next.readAsDataURL(selected);
  }
  function clearPhoto() {
    reader.current?.abort(); setReading(false); setPhotoError(''); setPhotoName('');
    setPhotoAction('CLEAR'); setPhotoData(''); if (file.current) file.current.value = ''; changed();
  }
  return <div className="t-personnel-page">
    <header className="t-pagehead"><div><p className="t-eyebrow">PERSONNEL</p><h1>인사정보 등록</h1><p className="t-muted">사원의 기본 인사정보와 적용일별 재직 상태를 관리하세요.</p></div><a className="t-btn" href={path}>기본정보·소속</a></header>
    <ErrorNotice message={error} />
    <Form action={`${path}/personnel`} className="t-personnel-form" data-unsaved-changes={dirty ? 'true' : undefined} onChange={changed}>
      <input type="hidden" name="expectedVersion" value={version} /><input type="hidden" name="commandId" value={command} />
      <input type="hidden" name="photoAction" value={photoAction} /><input type="hidden" name="photoData" value={photoData} />
      <fieldset disabled={busy}>
        <section className="t-card t-personnel-section"><div className="t-section-heading"><span className="t-section-number">01</span><div><h2>기본 인사정보</h2><p>등록된 정보는 사원 헤더에 표시됩니다. 필수 항목 외에는 비워둘 수 있습니다.</p></div></div>
          <div className="t-personnel-fields">
            <div><Field label="생년월일" name="birthDate" type="date" value={f.birthDate} max={m.today} /></div>
            <div><Field label="직급" name="gradeName" value={f.gradeName} maxLength={120} placeholder="예: 선임" /></div>
            <div><Field label="직책" name="positionName" value={f.positionName} maxLength={120} placeholder="예: 팀장" /></div>
            <div><Field label="직무" name="jobName" value={f.jobName} maxLength={120} placeholder="예: 제품 개발" /></div>
          </div>
        </section>
        <section className="t-card t-personnel-section"><div className="t-section-heading"><span className="t-section-number">02</span><div><h2>재직 상태</h2><p>적용일부터 표시됩니다. 계정 활성 여부와 근무·결재 권한은 각각 관리합니다.</p></div></div>
          <div className="t-personnel-fields"><div><SelectField label="재직 상태" name="employmentStatus" value={f.employmentStatus} required>{['UNKNOWN', 'WORKING', 'ON_LEAVE', 'SUSPENDED'].map(status => <option key={status} value={status}>{employmentStatusName(status)}</option>)}</SelectField></div>
          <div><Field label="상태 적용일" name="effectiveFrom" type="date" value={f.effectiveFrom} min={m.today} required /></div></div>
        </section>
        <section className="t-card t-personnel-section"><div className="t-section-heading"><span className="t-section-number">03</span><div><h2>사원사진</h2><p>PNG·JPG, 최대 128KB. 사진이 없으면 이름의 첫 글자를 표시합니다.</p></div></div>
          <div className="t-photo-upload"><div className="t-photo-upload-icon" aria-hidden="true">↑</div><div><label htmlFor="personnel-photo">사원사진 선택</label><input ref={file} id="personnel-photo" type="file" accept="image/png,image/jpeg" disabled={busy} onChange={event => choosePhoto(event.target.files?.[0])} /><p className="t-muted">{reading ? '사진을 읽고 있습니다.' : photoName || (photoAction === 'CLEAR' ? '저장하면 사진이 지워집니다.' : photoAction === 'REPLACE' ? '선택한 사진이 저장됩니다.' : '사진은 저장 후 헤더에서 확인할 수 있습니다.')}</p></div><button type="button" className="t-btn" onClick={clearPhoto} disabled={busy}>등록된 사진 지우기</button></div>
          <ErrorNotice message={photoError} />
        </section>
        <section className="t-card t-personnel-section"><div className="t-section-heading"><span className="t-section-number">04</span><div><h2>변경 기록</h2><p>변경 사유와 처리 이력을 남깁니다.</p></div></div><TextAreaField label="변경 사유" name="reason" value={f.reason} rows={3} maxLength={500} required /></section>
      </fieldset>
      {conflict && <section className="t-personnel-conflict" aria-label="최신 인사정보 비교"><h2>다른 변경이 반영되었습니다</h2><p>입력은 유지했습니다. 최신 저장 정보와 비교한 뒤 다시 저장하세요.</p>
        {m.currentForm && <dl>{([['생년월일', 'birthDate'], ['직급', 'gradeName'], ['직책', 'positionName'], ['직무', 'jobName'], ['상태 적용일', 'effectiveFrom']] as const).map(([label, key]) => <div key={key}><dt>{label}</dt><dd>{m.currentForm![key] || '미등록'}</dd></div>)}<div><dt>재직 상태</dt><dd>{employmentStatusName(m.currentForm.employmentStatus)}</dd></div></dl>}
        <button className="t-btn" type="button" disabled={busy} onClick={() => { setVersion(m.version); changed(); }}>최신 버전 확인 후 입력 유지</button>
      </section>}
      <footer className="t-personnel-savebar"><span>{dirty ? '저장하지 않은 변경이 있습니다.' : '필수 항목과 변경 사유를 확인하세요.'}</span><div><a className="t-btn" href={`${path}/personnel`}>입력 취소</a><button className="t-btn primary" disabled={busy || reading || Boolean(photoError) || conflict}>인사정보 저장</button></div></footer>
    </Form>
    <section className="t-card t-personnel-history"><h2>재직 상태 변경 이력</h2><p className="t-muted">같은 적용일의 후속 변경도 이력으로 보존됩니다.</p>{m.statusHistory.length ? <div className="table-scroll"><table><thead><tr><th>적용일</th><th>상태</th><th>변경 사유</th><th>등록 시각 (서울)</th></tr></thead><tbody>{m.statusHistory.map(entry => <tr key={entry.id}><td>{entry.effectiveFrom}</td><td>{employmentStatusName(entry.employmentStatus)}</td><td>{entry.reason}</td><td>{fmtDateTime(entry.createdAt)}</td></tr>)}</tbody></table></div> : <p className="t-empty-note">아직 등록된 상태 이력이 없습니다.</p>}</section>
  </div>;
}
