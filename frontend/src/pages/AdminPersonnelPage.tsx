import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { EmployeeHeader, employmentStatusName, type EmployeeHeaderData } from '../components/EmployeeHeader';
import { PageHeader } from '../components/PageHeader';
import type { PageProps } from '../types';
import { ErrorNotice, Form } from '../ui';
import { PersonalDetailsTabs, type PersonalDetailsModel } from './PersonalDetails';
import type { PersonnelModel } from './PersonnelPage';
import { PersonnelTabEditor, type TabDraft } from './PersonnelTabEditor';
import './AdminPersonnelPage.css';

export const personnelTabIds = ['basic', 'appointments', 'family', 'education', 'qualification', 'language', 'contact'] as const;
export type PersonnelTabId = typeof personnelTabIds[number];

export interface AdminPersonnelView {
  tab: PersonnelTabId;
  employee: { id: string; displayName: string; joinedOn: string; leftOn: string | null; active: boolean; version: number };
  editable: boolean;
  exportAllowed: boolean;
  joinedOnLocked?: boolean;
  organizations?: { id: string; code: string; name: string }[];
}

interface Match {
  employeeId: string; employeeNo: string; employeeName: string; organizationName: string | null; status: string;
}
interface SearchResult { rows: Match[]; page: number; hasNext: boolean }

function PersonnelLookup() {
  const [input, setInput] = useState('');
  const [query, setQuery] = useState('');
  const [result, setResult] = useState<SearchResult | null>(null);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState('');
  const request = useRef<AbortController | null>(null);
  useEffect(() => () => request.current?.abort(), []);

  async function search(q: string, page: number) {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setSearching(true);
    setError('');
    setQuery(q);
    try {
      const params = new URLSearchParams({ q, page: String(page) });
      const response = await fetch('/admin/employees/personnel/search?' + params.toString(), {
        headers: { Accept: 'application/json' }, signal: controller.signal,
      });
      if (!response.ok) throw new Error('사원 검색에 실패했습니다. 다시 조회해 주세요.');
      const data = await response.json() as SearchResult;
      if (request.current === controller) setResult(data);
    } catch {
      if (!controller.signal.aborted) { setResult(null); setError('사원 검색에 실패했습니다. 다시 조회해 주세요.'); }
    } finally {
      if (request.current === controller) setSearching(false);
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    event.stopPropagation();
    setResult(null);
    void search(input.trim(), 0);
  }

  return <div className="t-personnel-lookup">
    <form role="search" onSubmit={submit}>
      <label>사원 검색<input type="search" value={input} maxLength={120} placeholder="사번 또는 이름"
        onChange={event => setInput(event.target.value)} /></label>
      <button className="t-btn primary" type="submit" disabled={searching}>조회</button>
    </form>
    {searching && <p role="status">사원을 조회하고 있습니다.</p>}
    <ErrorNotice message={error} />
    {result && <div className="t-personnel-search-results" aria-busy={searching}>
      {result.rows.length ? <ul aria-label="사원 검색 결과">
        {result.rows.map(row => <li key={row.employeeId}><a href={`/admin/employees/${row.employeeId}/personnel`}>
          <strong>{row.employeeName}</strong><span>{row.employeeNo}</span>
          <span>{row.organizationName ?? '소속 미배정'}</span><span>{employmentStatusName(row.status)}</span>
        </a></li>)}
      </ul> : <p role="status">{query && `'${query}' 검색 `}결과 0건</p>}
      {(result.hasNext || result.page > 0) && <div className="t-personnel-search-pagination">
        <button className="t-btn" type="button" disabled={searching || result.page === 0}
          onClick={() => void search(query, result.page - 1)}>이전</button>
        <span>{result.page + 1}페이지</span>
        <button className="t-btn" type="button" disabled={searching || !result.hasNext}
          onClick={() => void search(query, result.page + 1)}>다음</button>
      </div>}
    </div>}
  </div>;
}

export function AdminPersonnelPage({ data, busy = false }: PageProps) {
  const selection = data.adminPersonnel as AdminPersonnelView | undefined;
  const header = data.employeeHeader as EmployeeHeaderData | undefined;
  const details = data.personalDetails as PersonalDetailsModel | undefined;
  const personnel = data.personnel as PersonnelModel | undefined;
  const draft = data.tabDraft as TabDraft | undefined;
  const lookup = <PersonnelLookup key={header?.employeeId ?? 'search'} />;
  return <>
    <PageHeader title="인사기본" actions={<a className="t-btn" href="/admin/employees/bulk-edit">인사정보 일괄관리</a>} />
    {selection && header && details && personnel
      ? <SelectedPersonnel key={header.employeeId + ':' + selection.tab + ':' + (draft?.commandId ?? '')}
          selection={selection} header={header} details={details} personnel={personnel} lookup={lookup}
          today={data.today as string} busy={busy} draft={draft} conflict={data.tabConflict === true} error={data.error as string | undefined} />
      : <section className="t-card t-personnel-search-empty" aria-label="사원 선택">
          {lookup}<p>인사정보를 볼 사원을 선택하세요.</p>
        </section>}
  </>;
}

function SelectedPersonnel({ selection, header, details, personnel, lookup, today, busy, draft, conflict, error }: {
  selection: AdminPersonnelView; header: EmployeeHeaderData; details: PersonalDetailsModel;
  personnel: PersonnelModel; lookup: React.ReactNode; today: string; busy: boolean;
  draft?: TabDraft; conflict: boolean; error?: string;
}) {
  const [index, setIndex] = useState(Math.max(0, personnelTabIds.indexOf(selection.tab)));
  const [editing, setEditing] = useState(Boolean(draft));
  const [submitted, setSubmitted] = useState(draft);
  const section = useRef<HTMLDivElement>(null);
  const tab = personnelTabIds[index];

  function selectTab(target: number) {
    if (target === index) return true;
    if (busy) return false;
    if (section.current?.querySelector('[data-unsaved-changes="true"]')
        && !window.confirm('저장하지 않은 변경이 있습니다. 입력을 버리고 다른 탭으로 이동할까요?')) return false;
    cancel();
    setIndex(target);
    return true;
  }

  function cancel() { setSubmitted(undefined); setEditing(false); }

  const editLabel = tab === 'basic' || tab === 'contact' ? '입력·수정'
    : tab === 'appointments' ? '소속 발령 등록' : '이력 추가';
  const employee = selection.employee.active === false && header.employmentStatus !== 'LEFT'
    ? { ...header, employmentStatus: 'INACTIVE' } : header;
  return <>
    <EmployeeHeader employee={employee} lookup={lookup} />
    {!selection.editable && <p className="notice">공유 데모 사원의 인사정보는 조회할 수 있습니다.</p>}
    {new URLSearchParams(location.search).has('saved') && !submitted && <p className="notice success" role="status">인사정보를 저장했습니다.</p>}
    <div ref={section}>
      <PersonalDetailsTabs model={details} identity={header} selectedTab={index} onSelectTab={selectTab} today={today}
        actions={(selection.exportAllowed || (selection.editable && !editing)) && <>
          {selection.editable && !editing && <button className="t-btn" type="button" disabled={busy}
            onClick={() => setEditing(true)}>{editLabel}</button>}
          {selection.exportAllowed && <Form action={`/admin/employees/${selection.employee.id}/personnel/tabs/${tab}/export`}
              data-native-submit="true">
            <button className="t-btn" type="submit" disabled={busy}>Excel 다운로드</button>
          </Form>}
        </>}
        editor={editing && selection.editable && <PersonnelTabEditor key={tab} tab={tab} selection={selection}
          personnel={personnel} details={details} today={today} busy={busy} draft={submitted}
          conflict={conflict && Boolean(submitted)} error={submitted ? error : undefined} cancel={cancel} />} />
    </div>
  </>;
}
