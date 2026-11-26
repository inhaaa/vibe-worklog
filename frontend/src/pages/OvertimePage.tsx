import { useRef, useState } from 'react';
import { PageHeader } from '../components/PageHeader';
import type { CsrfToken, PageProps } from '../types';
import { Form } from '../ui';
import './OvertimePage.css';

interface Interval { start: string; end: string }
interface Window extends Interval { kind: string; breaks: Interval[]; minutes: number }
interface Day { date: string; groupName?: string; workCode?: string; dayKind: string; unavailableReason?: string; windows: Window[] }
interface OvertimeRow {
  id: string; employeeName: string; employeeNo: string; kind: string; status: string; effectStatus: string;
  workDate: string; startTime: string; endTime: string; requestedMinutes: number; approvedMinutes: number; recognizedMillis?: number | null;
  actualIn?: string; actualOut?: string; multipleIn?: boolean; multipleOut?: boolean; currentStep: number; totalSteps: number;
}
interface Model {
  today: string; date: string; day?: Day; rows: OvertimeRow[];
  from: string | null; to: string | null; status: string; q: string; page: number; hasNext: boolean;
}
interface Preview { requestedMinutes: number; basisToken: string; routeToken: string; steps: {role?: string; approverName: string}[] }
const statuses: Record<string,string> = {PENDING:'대기', APPROVED:'승인', REJECTED:'반려', WITHDRAWN:'철회'};
const employeeUrl = '/me/overtime';
const adminUrl = '/admin/overtime';
const hhmm = (value?: string) => value?.slice(0, 5) || '미기록';
const recognized = (millis?: number | null) => {
  if (millis == null) return '계산 전';
  const minutes = Math.round(millis / 60000), h = Math.floor(minutes / 60), m = minutes % 60;
  return h && m ? `${h}시간 ${m}분` : h ? `${h}시간` : `${m}분`;
};
const windowLabel = (w: Window) => (w.kind === 'BEFORE' ? '출근 전' : '퇴근 후') + ' ' + hhmm(w.start) + '~' + hhmm(w.end);
class HttpError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}
async function post<T>(path: string, value: unknown, csrf: CsrfToken): Promise<T> {
  const res = await fetch(employeeUrl + path, {method:'POST', credentials:'same-origin',
    headers:{ 'Content-Type':'application/json', Accept:'application/json', [csrf.headerName]:csrf.token }, body:JSON.stringify(value)});
  if (res.redirected || res.status === 401 || res.status === 403) throw new HttpError('로그인 상태와 신청 권한을 확인해 주세요.', 403);
  if (!res.ok) {
    const error = await res.json().catch(() => ({}));
    throw new HttpError(error.message || '요청을 처리할 수 없습니다.', res.status);
  }
  return res.json();
}

function Compose({day, csrf}: {day: Day; csrf: CsrfToken}) {
  const [index, setIndex] = useState(0);
  const first = day.windows[0];
  const [start, setStart] = useState(hhmm(first?.start));
  const [end, setEnd] = useState(hhmm(first?.end));
  const [reason, setReason] = useState('');
  const [preview, setPreview] = useState<Preview>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [uncertain, setUncertain] = useState(false);
  const inflight = useRef(false);
  const commandId = useRef('');
  const selected = day.windows[index];
  const draft = {date:day.date, start, end, reason};
  function clear() { setPreview(undefined); setError(''); setUncertain(false); }
  async function run(submit: boolean) {
    if (inflight.current || (submit && !preview)) return;
    inflight.current = true; setBusy(true); setError('');
    try {
      if (!submit) {
        const result = await post<Preview>('/preview', {date:day.date,start,end}, csrf);
        setPreview(result); commandId.current = crypto.randomUUID();
      } else {
        const result = await post<{id: string}>('/submit', {...draft,basisToken:preview!.basisToken,routeToken:preview!.routeToken,commandId:commandId.current}, csrf);
        location.assign('/requests/' + encodeURIComponent(result.id));
      }
    } catch (e) {
      if (submit && (!(e instanceof HttpError) || e.status >= 500)) {
        setUncertain(true); setError('제출 결과를 확인할 수 없습니다. 같은 신청으로 다시 확인해 주세요.');
      } else {
        clear(); setError(e instanceof HttpError ? e.message : '미리보기를 불러올 수 없습니다. 다시 시도해 주세요.');
      }
    } finally { inflight.current = false; setBusy(false); }
  }
  return <section className="wl-section ot-compose"><h2>신청 시간</h2>
    {day.groupName && <p>{day.groupName}{day.workCode && ' · '+day.workCode}</p>}
    {day.unavailableReason || !selected ? <p role="status">{day.unavailableReason || '이 근무일에는 연장 허용 구간이 없습니다.'}</p>
      : <form onSubmit={e=>{e.preventDefault();void run(false);}}>
        <fieldset disabled={busy || uncertain} onChange={clear}>
          <label>허용 구간<select className="t-select" value={index} onChange={e=>{
            const i=Number(e.target.value);setIndex(i);setStart(hhmm(day.windows[i].start));setEnd(hhmm(day.windows[i].end));
          }}>{day.windows.map((w,i)=><option key={w.kind} value={i}>{windowLabel(w)}</option>)}</select></label>
          <p>최대 {selected.minutes}분 · 예정 휴게 {selected.breaks.length
            ? selected.breaks.map(b=>hhmm(b.start)+'~'+hhmm(b.end)).join(', ') : '없음'}</p>
          <div className="ot-times"><label>시작 시각<input required type="time" className="t-input" value={start} onChange={e=>setStart(e.target.value)} /></label>
            <label>종료 시각<input required type="time" className="t-input" value={end} onChange={e=>setEnd(e.target.value)} /></label></div>
          <label>신청 사유<textarea className="t-input" required maxLength={500} rows={2} value={reason} onChange={e=>setReason(e.target.value)} /></label>
          <button type="submit" className="t-btn" disabled={!reason.trim()}>결재선·신청 시간 확인</button>
        </fieldset>
        {preview && <section aria-label="제출 전 확인" className="ot-review"><h3>제출 전 확인</h3>
          <p>{day.date} · {start}~{end} · <strong>{preview.requestedMinutes}분</strong></p>
          <ol>{preview.steps.map((step,i)=><li key={i}>{step.role && step.role+' · '}{step.approverName}</li>)}</ol>
          <button type="button" className="t-btn primary" disabled={busy} onClick={()=>void run(true)}>{uncertain ? '같은 신청 다시 제출' : '연장근무 제출'}</button>
        </section>}
        {error && <p role="alert">{error}</p>}
      </form>}
  </section>;
}

function Results({m,admin}: {m: Model; admin: boolean}) {
  function href(page: number) {
    const p = new URLSearchParams({page:String(page)});
    if (admin) {if (m.from) p.set('from',m.from);if (m.to) p.set('to',m.to);if(m.status)p.set('status',m.status);if(m.q)p.set('q',m.q);}
    else p.set('date',m.date);
    return (admin?adminUrl:employeeUrl)+'?'+p;
  }
  const columns = [...(admin?['사원']:[]),'근무일','신청 구분','신청 시간','처리 상태','유효 승인',
    ...(admin?['인정 시간(일 합계)','실제 출근','실제 퇴근']:[]),'상세'];
  return <section className="wl-section"><h2>{admin?'신청·출퇴근 대조':'내 연장근무 신청 이력'}</h2>
    {!m.rows.length ? <p>조회된 신청이 없습니다.</p> : <table className="ot-rows"><thead><tr>{columns.map(h=><th scope="col" key={h}>{h}</th>)}</tr></thead>
      <tbody>{m.rows.map(r=><tr key={r.id}>
        {admin && <td data-label="사원">{r.employeeName} · {r.employeeNo}</td>}
        <td data-label="근무일">{r.workDate}</td><td data-label="신청 구분">{r.kind==='CANCEL_OVERTIME'?'연장 취소':'연장근무'}</td>
        <td data-label="신청 시간">{hhmm(r.startTime)}~{hhmm(r.endTime)} · {r.requestedMinutes}분</td>
        <td data-label="처리 상태">{r.effectStatus==='CANCELED'?'취소됨':statuses[r.status] || r.status}
          {r.status==='PENDING' && ' · '+r.currentStep+'/'+r.totalSteps+'단계'}</td>
        <td data-label="유효 승인">{r.approvedMinutes == null?'—':r.approvedMinutes+'분'}</td>
        {admin && <><td data-label="인정 시간(일 합계)">{r.kind==='OVERTIME'&&r.status==='APPROVED'&&r.effectStatus==='EFFECTIVE'?recognized(r.recognizedMillis):'—'}</td><td data-label="실제 출근">{r.multipleIn ? '복수 기록 · 확인 필요' : hhmm(r.actualIn)}</td><td data-label="실제 퇴근">{r.multipleOut ? '복수 기록 · 확인 필요' : hhmm(r.actualOut)}</td></>}
        <td data-label="상세"><a href={'/requests/'+encodeURIComponent(r.id)}>상세 보기</a></td>
      </tr>)}</tbody></table>}
    <nav className="ot-pagination" aria-label="신청 목록 페이지">
      {m.page>0 && <a className="t-btn" href={href(m.page-1)}>이전</a>}<span>{m.page+1}페이지</span>
      {m.hasNext && <a className="t-btn" href={href(m.page+1)}>다음</a>}
    </nav>
  </section>;
}

function AdminFilters({m}: {m: Model}) {
  const [from,setFrom] = useState(m.from ?? '');
  const [to,setTo] = useState(m.to ?? '');
  const invalid = Boolean(from && to && to < from);
  return <Form className="wl-querybar" method="get" action={adminUrl}>
      <label>시작일<input type="date" className="t-input" name="from" value={from} onChange={e=>setFrom(e.target.value)} /></label>
      <label>종료일<input type="date" className="t-input" name="to" value={to} onChange={e=>setTo(e.target.value)} /></label>
      <label>처리 상태<select className="t-select" name="status" defaultValue={m.status}><option value="">전체</option>{Object.entries(statuses).map(([v,s])=><option value={v} key={v}>{s}</option>)}</select></label>
      <label>사원 검색<input className="t-input" name="q" defaultValue={m.q} placeholder="사번 또는 이름"/></label>
      <button className="t-btn primary" disabled={invalid}>조회</button>
      {invalid && <p role="alert">종료일은 시작일 이후로 선택해 주세요.</p>}
    </Form>;
}

function EmployeeDay({m,csrf}: {m:Model;csrf:CsrfToken}) {
  const [selectedDate,setSelectedDate] = useState(m.date);
  return <><Form key={m.date} className="wl-querybar" method="get" action={employeeUrl}>
      <label>신청 근무일<input className="t-input" type="date" name="date" required min={m.today} value={selectedDate} onChange={e=>setSelectedDate(e.target.value)}/></label>
      <button className="t-btn">근무 기준 조회</button></Form>
      {selectedDate===m.date && m.day && <Compose key={m.date} day={m.day} csrf={csrf}/>}</>;
}

export function OvertimePage({page,data,csrf}: PageProps) {
  if(page!=='overtime/employee' && page!=='overtime/admin') return null;
  const m=data.overtime as Model;
  const admin=page==='overtime/admin';
  return <div className="ot-page"><PageHeader title={admin?'연장근무 신청현황':'연장근무 신청'}/>
    {admin ? <AdminFilters key={[m.from,m.to,m.status,m.q].join('/')} m={m}/> : <EmployeeDay key={m.date} m={m} csrf={csrf}/>}
    <Results m={m} admin={admin}/>
  </div>;
}
