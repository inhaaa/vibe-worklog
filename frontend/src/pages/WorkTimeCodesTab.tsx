import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { CsrfToken } from '../types';
import './WorkTimeCodesTab.css';

const endpoint = '/admin/schedules/workgroups/codes';
type BreakKind = 'REGULAR' | 'BEFORE' | 'AFTER';
type Pause = {kind:BreakKind; start:string; end:string};
type GroupUse = {groupId:string; code:string; name:string};
export type WorkTimeCodeVersion = {
  id:string; revision:number; effectiveFrom:string; enabled:boolean; name:string;
  startTime:string; endTime:string; beforeStart:string|null; beforeEnd:string|null;
  afterStart:string|null; afterEnd:string|null; breaks:Pause[]; scheduledMinutes:number;
  overtimeMinutes:number; reason:string; groups:GroupUse[];
};
export type WorkTimeCode = {id:string; code:string; version:number; versions:WorkTimeCodeVersion[]};
export type WorkTimeCatalog = {date:string; codes:WorkTimeCode[]};
type Csrf = Pick<CsrfToken,'headerName'|'token'>;
type Props = {date:string; today?:string; csrf:Csrf; onSaved?:()=>void; onBusy?:(busy:boolean)=>void};
type Times = {startTime:string; endTime:string; beforeStart:string; beforeEnd:string; afterStart:string; afterEnd:string};
const labels:Record<BreakKind,string> = {REGULAR:'정규 휴게',BEFORE:'출근 전 연장 휴게',AFTER:'퇴근 후 연장 휴게'};
const time = (value:string|null|undefined) => value?.slice(0,5) ?? '';
const hm = (minutes:number) => [minutes>=60 ? Math.floor(minutes/60)+'시간' : '',minutes%60 ? minutes%60+'분' : ''].filter(Boolean).join(' ') || '0분';
function span(start:string,end:string) {
  function minutes(value:string) { const [h,m]=value.split(':').map(Number); return h*60+m; }
  return Math.max(0,minutes(end)-minutes(start)) || 0;
}
function TimeInput({label,value,change}:{label:string;value:string;change:(value:string)=>void}) {
  return <label>{label}<input type="time" className="t-input" value={value} required step={60} onChange={e=>change(e.target.value)}/></label>;
}
function Editor({date,today,code,version,csrf,finish,cancel,onBusy}:{date:string;today:string;code?:WorkTimeCode;version?:WorkTimeCodeVersion;
  csrf:Csrf;finish:(id:string)=>void;cancel:()=>void;onBusy:(busy:boolean)=>void}) {
  const [key,setKey]=useState(code?.code??'');
  const [name,setName]=useState(version?.name??'');
  const [effective,setEffective]=useState(date);
  const [enabled,setEnabled]=useState(version?.enabled??true);
  const [reason,setReason]=useState('');
  const [confirmed,setConfirmed]=useState('');
  const attempt=useRef('');
  const sending=useRef(false);
  const [uncertain,setUncertain]=useState(false);
  const [times,setTimes]=useState<Times>({startTime:time(version?.startTime)||'09:00',endTime:time(version?.endTime)||'18:00',
    beforeStart:time(version?.beforeStart),beforeEnd:time(version?.beforeEnd),afterStart:time(version?.afterStart),afterEnd:time(version?.afterEnd)});
  const [windows,setWindows]=useState({BEFORE:!!version?.beforeStart,AFTER:!!version?.afterStart});
  const [breaks,setBreaks]=useState<Pause[]>(version ? version.breaks.map(b=>({...b,start:time(b.start),end:time(b.end)}))
    : [{kind:'REGULAR',start:'12:00',end:'13:00'}]);
  const [error,setError]=useState('');
  const [pending,setPending]=useState(false);
  const reserved=code?.versions.find(v=>v.effectiveFrom===effective);
  const regular=span(times.startTime,times.endTime);
  const paused=(kind:BreakKind)=>breaks.filter(b=>b.kind===kind).reduce((sum,b)=>sum+span(b.start,b.end),0);
  const overtime=(windows.BEFORE ? span(times.beforeStart,times.beforeEnd)-paused('BEFORE'):0)
    +(windows.AFTER ? span(times.afterStart,times.afterEnd)-paused('AFTER'):0);

  function input(field:keyof Times,label:string) {
    return <TimeInput label={label} value={times[field]} change={value=>setTimes(previous=>({...previous,[field]:value}))}/>;
  }
  function pauseRows(kind:BreakKind) {
    return <div className="wt-pauses">
      {breaks.map((b,index)=> {
        if(b.kind!==kind) return null;
        const ordinal=breaks.slice(0,index+1).filter(p=>p.kind===kind).length;
        const prefix=labels[kind]+' '+ordinal;
        const update=(field:'start'|'end',value:string)=>setBreaks(breaks.map((p,i)=>i===index?{...p,[field]:value}:p));
        return <div key={index} className="wt-fields">
          <TimeInput label={prefix+' 시작'} value={b.start} change={value=>update('start',value)}/>
          <TimeInput label={prefix+' 종료'} value={b.end} change={value=>update('end',value)}/>
          <button type="button" className="t-btn" aria-label={prefix+' 삭제'} onClick={()=>setBreaks(breaks.filter((_,i)=>i!==index))}>삭제</button>
        </div>;
      })}
      <button className="t-btn" type="button" onClick={()=>setBreaks([...breaks,{kind,start:'',end:''}])}>{labels[kind]} 추가</button>
    </div>;
  }
  async function submit(e:FormEvent) {
    e.preventDefault();
    if(sending.current || (reserved && confirmed!==reserved.id))return;
    sending.current=true;
    setError('');setPending(true);onBusy(true);
    if(!uncertain) attempt.current=JSON.stringify({id:code?.id??null,expectedVersion:code?.version??null,code:key,name,effectiveFrom:effective,
      enabled,startTime:times.startTime,endTime:times.endTime,beforeStart:windows.BEFORE?times.beforeStart:null,
      beforeEnd:windows.BEFORE?times.beforeEnd:null,afterStart:windows.AFTER?times.afterStart:null,afterEnd:windows.AFTER?times.afterEnd:null,
      breaks:breaks.filter(b=>b.kind==='REGULAR'||windows[b.kind]),reason,commandId:crypto.randomUUID(),replaceVersionId:reserved?.id??null});
    try {
      const response=await fetch(endpoint,{method:'POST',credentials:'same-origin',cache:'no-store',
        headers:{'Content-Type':'application/json','Accept':'application/json',[csrf.headerName]:csrf.token},
        body:attempt.current});
      const result=await response.json().catch(()=>({}));
      if(response.redirected || response.status===401 || response.status===403) {
        setUncertain(false);setError('로그인 상태와 근무 설정 권한을 확인해 주세요.');onBusy(false);return;
      }
      if(response.status>=400 && response.status<500) {
        setUncertain(false);setError(result.message||'입력 내용을 확인해 주세요.');onBusy(false);return;
      }
      if(!response.ok || !result.id)throw new Error();
      onBusy(false);
      finish(result.id);
    } catch {
      setUncertain(true);setError('저장 결과를 확인할 수 없습니다. 같은 명령으로 다시 확인해 주세요.');
    } finally {sending.current=false;setPending(false);}
  }
  return <form onSubmit={submit} className="wt-editor">
    <fieldset disabled={pending||uncertain}><legend>{code?'근무시간코드 개정':'근무시간코드 등록'}</legend>
      <div className="wt-fields">
        <label>코드<input className="t-input" value={key} readOnly={!!code} required pattern="[A-Z0-9]{2,8}" minLength={2} maxLength={8}
          onChange={e=>setKey(e.target.value.toUpperCase())}/></label>
        <label>코드명<input className="t-input" value={name} maxLength={120} required onChange={e=>setName(e.target.value)}/></label>
        <label>사용 시작일<input className="t-input" type="date" value={effective} min={today} required onChange={e=>{setEffective(e.target.value);setConfirmed('');}}/></label>
        <label>사용 상태<select className="t-select" value={enabled?'yes':'no'} onChange={e=>setEnabled(e.target.value==='yes')}>
          <option value="yes">사용</option><option value="no">사용 중지</option></select></label>
      </div>
      <h4>정규 근무와 휴게</h4>
      <div className="wt-fields">{input('startTime','정규 출근')}{input('endTime','정규 퇴근')}</div>
      {pauseRows('REGULAR')}
      <p className="wt-totals"><span>정규 구간 {hm(regular)}</span><span>예정 휴게 {hm(paused('REGULAR'))}</span>
        <strong>소정시간 {hm(Math.max(0,regular-paused('REGULAR')))}</strong></p>
      <h4>연장 신청 기준</h4>
      {(['BEFORE','AFTER'] as const).map(kind=>{
        const before=kind==='BEFORE'; const label=before?'출근 전':'퇴근 후';
        return <div key={kind} className="wt-window">
          <label><input type="checkbox" checked={windows[kind]} onChange={e=>setWindows({...windows,[kind]:e.target.checked})}/>{label} 연장 허용</label>
          {windows[kind] && <><div className="wt-fields">{input(before?'beforeStart':'afterStart',label+' 허용 시작')}
            {input(before?'beforeEnd':'afterEnd',label+' 허용 종료')}</div>{pauseRows(kind)}</>}
        </div>;
      })}
      <p><strong>최대 신청 가능 시간 {hm(Math.max(0,overtime))}</strong></p>
      <label>변경 사유<input className="t-input" value={reason} required maxLength={500} onChange={e=>setReason(e.target.value)}/></label>
      {reserved && <label className="wt-confirm"><input type="checkbox" checked={confirmed===reserved.id}
        onChange={e=>setConfirmed(e.target.checked?reserved.id:'')}/>{effective} 적용 예약 v{reserved.revision} 정정</label>}
    </fieldset>
    <div className="wt-actions"><button type="submit" className="t-btn t-btn-primary" disabled={pending || (!!reserved && confirmed!==reserved.id)}>
      {uncertain?'저장 결과 다시 확인':'저장'}</button>
      <button type="button" disabled={pending} className="t-btn" onClick={()=>{onBusy(false);cancel();}}>{uncertain?'조회로 돌아가기':'취소'}</button></div>
    {error && <p role="alert">{error}</p>}
  </form>;
}

export function WorkTimeCodesTab({date,today=date,csrf,onSaved,onBusy}:Props) {
  const [rows,setRows]=useState<WorkTimeCode[]>([]);
  const [refresh,setRefresh]=useState(0);
  const [selected,setSelected]=useState('');
  const [editing,setEditing]=useState(false);
  const [source,setSource]=useState<WorkTimeCodeVersion>();
  const [busy,setBusy]=useState(false);
  const [search,setSearch]=useState('');
  const [message,setMessage]=useState('');
  const [error,setError]=useState('');
  const [loading,setLoading]=useState(true);
  useEffect(()=>{
    const abort=new AbortController();
    setLoading(true);setError('');setEditing(false);
    async function load() {
      try {
        const response=await fetch(endpoint+'?'+new URLSearchParams({date}),{signal:abort.signal,cache:'no-store',credentials:'same-origin',
          headers:{Accept:'application/json'}});
        if(response.redirected || response.status===401 || response.status===403)throw new Error('로그인 상태와 근무 설정 권한을 확인해 주세요.');
        const result=await response.json().catch(()=>({}));
        if(!response.ok || !Array.isArray(result.codes))throw new Error(result.message??'근무시간코드를 조회하지 못했습니다.');
        if(!abort.signal.aborted)setRows(result.codes);
      } catch(cause) {if(!abort.signal.aborted){setRows([]);setError(cause instanceof Error && !(cause instanceof TypeError)?cause.message:'조회 연결을 확인하고 다시 시도해 주세요.');}}
      finally {if(!abort.signal.aborted)setLoading(false);}
    }
    void load();
    return ()=>abort.abort();
  },[date,refresh]);
  const code=rows.find(r=>r.id===selected);
  const effective=(row:WorkTimeCode)=>row.versions.find(v=>v.effectiveFrom<=date);
  const displayVersion=(row:WorkTimeCode)=>effective(row)??row.versions.find(v=>v.effectiveFrom===row.versions.at(-1)?.effectiveFrom);
  const version=code && displayVersion(code);
  const term=search.trim().toLowerCase();
  const visible=rows.filter(r=>r.code.toLowerCase().includes(term)||r.versions.some(v=>v.name.toLowerCase().includes(term)));
  function started(value:boolean){setBusy(value);onBusy?.(value);}
  function finish(id:string) {
    setSelected(id);setEditing(false);setMessage('근무시간코드를 저장했습니다.');setRefresh(x=>x+1);onSaved?.();
  }
  return <section className="wt-panel" aria-label="근무시간코드 설정">
    <div className="wt-actions"><h2>근무시간코드</h2>
      <button type="button" className="t-btn" disabled={busy || loading || !!error} onClick={()=>{setSelected('');setSource(undefined);setMessage('');setEditing(true);}}>코드 등록</button></div>
    {loading && <p role="status">조회 중…</p>}
    {message && <p role="status">{message}</p>}
    {error && <p role="alert">{error} <button className="t-btn" type="button" onClick={()=>setRefresh(x=>x+1)}>다시 조회</button></p>}
    {!loading && !error && <div className="wt-layout">
      <div aria-label="코드 목록"><label>코드 검색<input type="search" className="t-input" value={search} placeholder="코드 또는 이름"
        disabled={busy} onChange={e=>setSearch(e.target.value)}/></label>
        {rows.length===0 && <p>등록된 근무시간코드가 없습니다.</p>}{rows.length>0 && visible.length===0 && <p>검색 결과가 없습니다.</p>}
        {visible.map(row=>{
          const current=effective(row);const display=displayVersion(row);
          return <button type="button" disabled={busy} className="wt-row" key={row.id} aria-pressed={selected===row.id} onClick={()=>{setSelected(row.id);setEditing(false);setMessage('');}}>
            <strong>{row.code} {display?.name}</strong><span>{!current?'사용 예정':current.enabled?'사용':'사용 중지'}</span>
          </button>;
        })}
      </div>
      <div>{editing ? <Editor key={code?.id??'new'} today={today} date={source?.effectiveFrom??(date<today?today:date)} code={code} version={source??version} csrf={csrf} onBusy={started}
        finish={finish} cancel={()=>{setEditing(false);setRefresh(a=>a+1);}}/>
        : code && version && <section aria-label="코드 상세">
          <div className="wt-actions"><h3>{code.code} {version.name}</h3><button className="t-btn" type="button" onClick={()=>{setSource(undefined);setEditing(true);setMessage('');}}>코드 개정</button></div>
          <h4>버전별 근무 기준</h4>
          {code.versions.map(v=><article key={v.id} className="wt-revision">
            <h4>v{v.revision} · {v.name}</h4><p>{v.effectiveFrom}부터 · {v.enabled?'사용':'사용 중지'}</p>
            <p>정규 {time(v.startTime)}~{time(v.endTime)} · 소정시간 {hm(v.scheduledMinutes)}</p>
            {v.beforeStart && <p>출근 전 연장 {time(v.beforeStart)}~{time(v.beforeEnd)}</p>}
            {v.afterStart && <p>퇴근 후 연장 {time(v.afterStart)}~{time(v.afterEnd)}</p>}
            <p>최대 신청 가능 시간 {hm(v.overtimeMinutes)}</p>
            {v.breaks.map(b=><p key={b.kind+b.start}>{labels[b.kind]} {time(b.start)}~{time(b.end)}</p>)}
            <p>조회일 사용 근무조 {v.groups?.map(g=>g.code+' '+g.name).join(', ')||'없음'}</p><p>변경 사유 {v.reason}</p>
            {code.versions.find(r=>r.effectiveFrom===v.effectiveFrom)?.id===v.id && v.effectiveFrom>=today &&
              <button type="button" className="t-btn" onClick={()=>{setSource(v);setEditing(true);setMessage('');}}>v{v.revision} 적용 예약 정정</button>}
          </article>)}
        </section>}
      </div>
    </div>}
  </section>;
}
