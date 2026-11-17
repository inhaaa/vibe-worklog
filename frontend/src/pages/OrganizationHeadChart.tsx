import { useEffect, useRef, useState } from 'react';
import { Check, Field, Form, FormRow, FormTable, SelectField, TextAreaField } from '../ui';
import './OrganizationHeadChart.css';

export const chartPath = '/admin/organization-heads';

type Operation = 'ASSIGN' | 'REPLACE' | 'CHANGE_END' | 'RESCHEDULE' | 'CANCEL';
interface HeadPeriod { employeeName: string; validFrom: string; lastDay: string | null }
type Vacancy = { from: string; through: string | null };
export interface HeadOverview { current: HeadPeriod | null; next: HeadPeriod | null }
export interface HeadPreview {
  before: HeadPeriod | null; after: HeadPeriod | null; previousLastDay: string | null;
  next: HeadPeriod | null; vacancy: Vacancy | null; additionalVacancy: Vacancy | null;
}
export interface HeadCommand {
  operation: Operation; assignmentId?: string | null; employeeId?: string | null;
  validFrom?: string | null; lastDay?: string | null; endToday?: boolean;
  expectedVersion?: number | null; reason?: string; commandId?: string;
}
type Candidate = { id: string; employeeNo: string; employeeName: string; joinedOn: string; leftOn: string | null };
interface HeadAssignment extends Omit<HeadPeriod, 'lastDay'> {
  id: string; employeeId: string; employeeNo: string; validTo: string | null; supersededAt: string | null;
  reason: string; eligible: boolean; approverEligible: boolean;
}
export interface HeadChartData {
  today: string; candidates: Candidate[]; history: HeadAssignment[];
  form: { commandId: string; expectedVersion: number | null };
}
interface Props {
  chart: { today: string; organizationId: string | null; q: string; page: number };
  organization: { id: string; name: string; code: string; active: boolean };
  data: HeadChartData;
  command?: HeadCommand; preview?: HeadPreview; error?: string; conflict?: boolean; saved?: boolean;
}

const labels: Record<Operation, string> = {
  ASSIGN: '조직장 배정', REPLACE: '후임 변경', CHANGE_END: '종료일 변경', RESCHEDULE: '예약 수정', CANCEL: '예약 취소',
};

function lastDay(boundary?: string | null) {
  if (!boundary) return null;
  const [year, month, day] = boundary.split('-').map(Number);
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day - 1);
  return date.toISOString().split('T')[0];
}

function dates(from: string, end?: string | null) {
  return from === end ? from : `${from} ~ ${end ?? '종료일 미지정'}`;
}

function vacancyDates(gap: Vacancy) {
  return gap.through ? dates(gap.from, gap.through) : `${gap.from} 이후`;
}

export function OrganizationHeadOverview({ overview }: { overview: HeadOverview }) {
  return <dl className="wl-head-summary">
    <div><dt>현재 담당 기간</dt><dd>{overview.current ? dates(overview.current.validFrom, overview.current.lastDay) : '미배정'}</dd></div>
    <div><dt>다음 예약</dt><dd>{overview.next ? `${overview.next.employeeName} · ${dates(overview.next.validFrom, overview.next.lastDay)}` : '없음'}</dd></div>
  </dl>;
}

function Preview({ preview }: { preview: HeadPreview }) {
  return <section className="wl-head-preview" aria-labelledby="head-preview-heading" tabIndex={-1} data-error-focus="true">
    <h3 id="head-preview-heading">변경 미리보기</h3>
    <dl className="wl-head-summary">
      <div><dt>변경 전</dt><dd>{preview.before ? `${preview.before.employeeName} · ${dates(preview.before.validFrom, preview.before.lastDay)}` : '미배정'}</dd></div>
      <div><dt>변경 후</dt><dd>{preview.after ? `${preview.after.employeeName} · ${dates(preview.after.validFrom, preview.after.lastDay)}` : '미배정'}</dd></div>
      {preview.previousLastDay && <div><dt>전임 마지막 담당일</dt><dd>{preview.previousLastDay}</dd></div>}
      <div><dt>다음 예약</dt><dd>{preview.next ? `${preview.next.employeeName} · ${dates(preview.next.validFrom, preview.next.lastDay)}` : '없음'}</dd></div>
      {preview.vacancy && <div><dt>공석</dt><dd>{vacancyDates(preview.vacancy)}</dd></div>}
      {preview.additionalVacancy && <div><dt>추가 공석</dt><dd>{vacancyDates(preview.additionalVacancy)}</dd></div>}
    </dl>
  </section>;
}

export function OrganizationHeadChart({ chart, organization, data, command, preview, error, conflict, saved }: Props) {
  const [draft, setDraft] = useState(command);
  const [search, setSearch] = useState('');
  const [chosen, setChosen] = useState(command?.employeeId ?? '');
  const [endToday, setEndToday] = useState(command?.endToday ?? false);
  const [dirty, setDirty] = useState(false);
  const [rebased, setRebased] = useState(false);
  const [editorVersion, setEditorVersion] = useState(0);
  const heading = useRef<HTMLHeadingElement>(null);
  const focusEditor = useRef(false);
  useEffect(() => {
    if (focusEditor.current && draft) {
      heading.current?.focus();
      focusEditor.current = false;
    }
  }, [draft]);

  const editing = draft?.operation;
  const candidates = data.candidates.filter(person => person.id === chosen
    || `${person.employeeNo} ${person.employeeName}`.toLowerCase().includes(search.trim().toLowerCase()));
  const lostCandidate = chosen && !data.candidates.some(person => person.id === chosen);
  const visiblePreview = !dirty && !conflict ? preview : undefined;
  const current = data.history.find(row => !row.supersededAt && row.validFrom <= chart.today
    && (!row.validTo || row.validTo > chart.today));
  const history = [...data.history].sort((left, right) => left.validFrom.localeCompare(right.validFrom));

  function select(operation: Operation, target?: HeadAssignment) {
    focusEditor.current = true;
    setEditorVersion(value => value + 1);
    setSearch('');
    setEndToday(false);
    setDirty(true);
    setRebased(false);
    const employeeId = operation === 'RESCHEDULE' ? target?.employeeId : '';
    setChosen(employeeId ?? '');
    setDraft({
      operation, assignmentId: target?.id, employeeId, validFrom: operation === 'RESCHEDULE' ? target?.validFrom : chart.today,
      lastDay: operation === 'RESCHEDULE' || operation === 'CHANGE_END' ? lastDay(target?.validTo) : '',
      expectedVersion: data.form.expectedVersion, commandId: crypto.randomUUID(), reason: '',
    });
  }

  const selectingEmployee = editing === 'ASSIGN' || editing === 'REPLACE' || editing === 'RESCHEDULE';
  return <section className="wl-head-chart" aria-labelledby="head-chart-heading">
    <h2 id="head-chart-heading">조직장 관리</h2>
    <p>{organization.code} · {organization.name}</p>
    {saved && !editing && <p className="notice" role="status">조직장 변경을 저장했습니다.</p>}
    <div className="wl-head-actions">
      {organization.active && <button className="t-btn" type="button" onClick={() => select('ASSIGN')}>{labels.ASSIGN}</button>}
      {current && organization.active && <button className="t-btn" type="button" onClick={() => select('REPLACE', current)}>{labels.REPLACE}</button>}
      {current && <button className="t-btn" type="button" onClick={() => select('CHANGE_END', current)}>{labels.CHANGE_END}</button>}
    </div>
    {draft && <div className="wl-head-editor">
      <h3 ref={heading} tabIndex={-1}>{labels[draft.operation]}</h3>
      <Form key={editorVersion} action={`${chartPath}/commands`} className="wl-form" onChange={event => {
        if (event.target instanceof HTMLElement && event.target.getAttribute('name')) setDirty(true);
      }}>
        <input type="hidden" name="operation" value={draft.operation} />
        <input type="hidden" name="organizationId" value={organization.id} />
        <input type="hidden" name="assignmentId" value={draft.assignmentId ?? ''} />
        <input type="hidden" name="expectedVersion" value={rebased ? data.form.expectedVersion ?? '' : draft.expectedVersion ?? ''} />
        <input type="hidden" name="commandId" value={draft.commandId ?? data.form.commandId} />
        <input type="hidden" name="q" value={chart.q} />
        <input type="hidden" name="page" value={chart.page} />
        {(error && !dirty || conflict && !rebased) && <p id="head-command-error" role="alert" className="notice error" data-error-focus="true" tabIndex={-1}>{error}</p>}
        {conflict && !rebased && <p>최신 조직 버전 {data.form.expectedVersion} · 작성한 버전 {draft.expectedVersion}.
          이 화면의 최신 배정과 편집값을 확인한 뒤 미리보기를 다시 실행하세요.</p>}
        <FormTable>
          {selectingEmployee && <>
            <Field label="조직장 후보 사번·이름 검색" type="search" maxLength={100} onChange={event => setSearch(event.currentTarget.value)} />
            <SelectField label="새 조직장" name="employeeId" value={draft.employeeId} required onChange={event => setChosen(event.currentTarget.value)}>
              <option value="">사원을 선택하세요</option>
              {lostCandidate && <option value={chosen}>기존 선택 · 재지정 필요</option>}
              {candidates.map(person => <option key={person.id} value={person.id}>{person.employeeNo} · {person.employeeName}
                {person.leftOn ? ` · ${person.leftOn}까지 재직` : ''}</option>)}
            </SelectField>
            <Field label="시작일" name="validFrom" type="date" min={chart.today} max="9999-12-31" value={draft.validFrom} required />
          </>}
          {editing !== 'CANCEL' && <>
            <Field label="종료일 (마지막 담당일)" name="lastDay" type="date" min={chart.today} max="9999-12-31"
              value={visiblePreview?.after?.lastDay ?? draft.lastDay} disabled={endToday} />
            {editing === 'CHANGE_END' && <FormRow label="즉시 종료"><Check label="오늘부터 미배정" name="endToday"
              checked={endToday} onChange={event => setEndToday(event.currentTarget.checked)} /></FormRow>}
          </>}
          <TextAreaField label="변경 사유" name="reason" value={draft.reason} maxLength={500} required rows={2} />
        </FormTable>
        {editing !== 'CANCEL' && <p className="hint">종료일까지 담당합니다. 공란이면 다음 예약과 재직 기간이 허용하는 범위를 미리 보여 드립니다.</p>}
        {visiblePreview && <Preview preview={visiblePreview} />}
        <div className="wl-head-actions">
          {conflict && !rebased && <button type="button" className="t-btn" onClick={() => {
            setDraft({ ...draft, commandId: crypto.randomUUID() });
            setRebased(true);
          }}>최신 배정 확인</button>}
          {visiblePreview ? <button className="t-btn primary" name="intent" value="SAVE" type="submit">배정 저장</button>
            : <button className="t-btn primary" name="intent" value="PREVIEW" type="submit" disabled={conflict && !rebased}>미리보기</button>}
          <button className="t-btn" type="button" onClick={() => { setDraft(undefined); setDirty(true); }}>입력 닫기</button>
        </div>
      </Form>
    </div>}
    <details open={Boolean(current || saved || draft)}>
      <summary>배정·변경 이력</summary>
      {history.length ? <ul className="wl-head-history">
        {history.map(row => {
          const upcoming = !row.supersededAt && row.validFrom > chart.today;
          const status = row.supersededAt ? '변경·취소 이력' : upcoming ? '예정' : row.id === current?.id ? '현재 배정' : '종료';
          return <li key={row.id}>
            <strong>{row.employeeName} · {row.employeeNo}</strong>
            <span>{dates(row.validFrom, lastDay(row.validTo))} · {status}</span>
            {(upcoming || row.id === current?.id) && !row.eligible && <span>재지정 필요</span>}
            {(upcoming || row.id === current?.id) && !row.approverEligible && <span>현재 결재 자격 없음</span>}
            <span>배정 사유 {row.reason}</span>
            {upcoming && <div className="wl-head-actions">
              {organization.active && <button type="button" className="t-btn" onClick={() => select('RESCHEDULE', row)}>예약 수정</button>}
              <button type="button" className="t-btn" onClick={() => select('CANCEL', row)}>예약 취소</button>
            </div>}
          </li>;
        })}
      </ul> : <p>등록된 조직장이 없습니다.</p>}
    </details>
  </section>;
}
