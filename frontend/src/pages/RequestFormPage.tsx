import { useEffect, useRef, useState } from 'react';
import { PageHeader } from '../components/PageHeader';
import { ErrorNotice, Field, Form, FormRow, FormTable, fmtDateTime } from '../ui';
import { fetchPage } from '../transport';
import './RequestFormPage.css';

const path='/admin/request-forms';
const kinds:Record<string,string>={LEAVE_DAY:'휴가 신청',CANCEL_LEAVE:'휴가 취소',ATTENDANCE_CORRECTION:'출퇴근 정정',OVERTIME:'연장근무',CANCEL_OVERTIME:'연장근무 취소'};
type Row={id:string;code:string;name:string;kind:string;version:number;enabled:boolean;routeVersionId:string|null;routeName:string|null;routeRevision:number|null;nextFrom:string|null};
type History={id:string;revision:number;effectiveFrom:string;enabled:boolean;name:string;routeVersionId:string|null;reason:string;changedBy:string|null;createdAt:string};
type Route={id:string;name:string;code:string;active:boolean;revision:number;approverKind:string;terminalUnit:string|null};
type Employee={id:string;employeeNo:string;name:string};
type Model={today:string;q:string;forms:Row[];selected:Row|null;history:History[];routes:Route[];employees:Employee[];replacementOptions:Row[]};
type Draft={commandId:string;id:string|null;code:string;name:string;kind:string;expectedVersion:number|null;effectiveFrom:string;enabled:boolean;routeVersionId:string|null;replaceFormId:string|null;reason:string};
type Preview={eligible:boolean;message?:string;approverName?:string;steps:{stepNumber:number;role:string;approverName:string;organizationName?:string}[]};

function href(q:string,params:Record<string,string>={}) {
  const search=new URLSearchParams({q,...params});return path+'?'+search;
}
function routeLabel(route:Route) {
  const type=route.approverKind==='ORGANIZATION_CHAIN'?(route.terminalUnit==='OFFICE'?'실장까지':'팀장까지'):
    route.approverKind==='DIRECT_EMPLOYEE'?'지정 사원':'소속 조직장';
  return route.code+' · '+route.name+' v'+route.revision+' · '+type;
}
function PreviewPanel({employees,routeId,enabled,edited}:{employees:Employee[];routeId:string;enabled:boolean;edited:boolean}) {
  const [search,setSearch]=useState('');
  const [result,setResult]=useState<Preview|null>(null);
  const [error,setError]=useState('');
  const [busy,setBusy]=useState(false);
  const request=useRef<AbortController|null>(null);
  const employee=employees.find(e=>e.employeeNo+' '+e.name===search || e.employeeNo===search);
  useEffect(()=>{
    request.current?.abort();setResult(null);setError('');setBusy(false);
    return ()=>request.current?.abort();
  },[routeId,search,enabled]);
  async function preview() {
    if(!employee)return;
    request.current?.abort();
    const controller=new AbortController();
    request.current=controller;setBusy(true);setResult(null);setError('');
    try {
      const params=new URLSearchParams({employeeId:employee.id});
      if(routeId)params.set('routeVersionId',routeId);
      const {envelope}=await fetchPage(path+'/preview?'+params,{signal:controller.signal});
      if(!controller.signal.aborted){
        const value=envelope.data.routePreview as Preview|undefined;
        if(!value)throw Error('결재선 정보를 불러오지 못했습니다.');
        setResult(value);
      }
    } catch(e) {if(!controller.signal.aborted)setError(e instanceof Error?e.message:'미리보기를 다시 시도해 주세요.');}
    finally {if(!controller.signal.aborted)setBusy(false);}
  }
  return <section aria-label="사원별 결재선 미리보기" className="rf-preview">
    <h3>사원별 결재선 미리보기</h3>
    <label htmlFor="form-example">예시 사원</label>
    <input id="form-example" className="t-input" list="form-employees" value={search} placeholder="사번 또는 이름" onChange={e=>setSearch(e.target.value)}/>
    <datalist id="form-employees">{employees.map(e=><option key={e.id} value={e.employeeNo+' '+e.name}/>)}</datalist>
    <button type="button" className="t-btn" onClick={preview} disabled={!employee||busy||!enabled}>결재선 미리보기</button>
    {!enabled && <p>사용 중지 설정에서는 신규 신청을 접수할 수 없습니다.</p>}
    <div aria-live="polite">{result && <>
      <strong>{edited?'저장 전 설정':'저장된 결재선'} · 오늘 본인 신청 기준</strong>
      {result.eligible?<ol>{result.steps.length?result.steps.map(s=><li key={s.stepNumber}>{[s.organizationName,s.role,s.approverName].filter(Boolean).join(' · ')}</li>):
        <li>{result.approverName}</li>}</ol>:<p>{result.message} <a href="/admin/approval-routes">결재선 관리</a> · <a href="/company/organizations">조직도</a></p>}
    </>}{error && <p role="alert">{error}</p>}</div>
  </section>;
}

function Editor({model,draft}:{model:Model;draft?:Draft}) {
  const row=draft?.id===null?null:model.selected;
  const next=model.history.some(h=>h.effectiveFrom<=model.today)?undefined:model.history.find(h=>h.effectiveFrom===row?.nextFrom);
  const initial=row?.name??'';
  const [kind,setKind]=useState(draft?.kind??row?.kind??'LEAVE_DAY');
  const [enabled,setEnabled]=useState(draft?.enabled??next?.enabled??row?.enabled??true);
  const [routeId,setRouteId]=useState(draft?draft.routeVersionId??'':row?.routeVersionId??(!row?.enabled?next?.routeVersionId:null)??'');
  const [replace,setReplace]=useState(draft?.replaceFormId??'');
  const [command]=useState(()=>draft?.commandId??crypto.randomUUID());
  const candidates=model.replacementOptions.filter(r=>r.kind===kind && r.id!==row?.id);
  const previous=candidates.find(r=>r.id===replace);
  return <section className="rf-editor" aria-label="신청서 설정">
    <a className="rf-back" href={href(model.q,row?{focus:row.id}:{})}>목록으로 돌아가기</a>
    <h2>{row?row.code+' '+row.name:'신청서코드 등록'}</h2>
    <Form action={path+'/commands'}>
      <input type="hidden" name="q" value={model.q}/><input type="hidden" name="commandId" value={command}/>
      {row && <><input type="hidden" name="id" value={row.id}/><input type="hidden" name="expectedVersion" value={draft?.expectedVersion??row.version}/></>}
      <FormTable>
        <Field name="code" label="신청서코드" value={draft?.code??row?.code??''} readOnly={!!row} pattern="[A-Z0-9]{2,8}" minLength={2} maxLength={8} required/>
        <Field name="name" label="신청서명" value={draft?.name??initial} maxLength={120} required/>
        <FormRow label="업무 종류" htmlFor="form-kind"><select id="form-kind" disabled={!!row} className="t-select" value={kind}
          onChange={e=>{setKind(e.target.value);setReplace('');}}>{Object.entries(kinds).map(([k,label])=><option key={k} value={k}>{label}</option>)}</select>
          <input type="hidden" name="kind" value={kind}/></FormRow>
        <Field label="적용 시작일" name="effectiveFrom" type="date" value={draft?.effectiveFrom??next?.effectiveFrom??model.today} min={model.today} max="9999-12-31" required/>
        <FormRow label="사용 상태" htmlFor="form-enabled"><select className="t-select" name="enabled" id="form-enabled" value={String(enabled)}
          onChange={e=>setEnabled(e.target.value==='true')}><option value="true">사용</option><option value="false">사용 중지</option></select></FormRow>
        <FormRow label="연결할 결재선" htmlFor="form-route"><select id="form-route" className="t-select" name="routeVersionId" value={routeId} onChange={e=>setRouteId(e.target.value)}>
          <option value="">기존 배정 사용</option>{model.routes.filter(r=>r.active||r.id===routeId).map(r=><option key={r.id} value={r.id} disabled={!r.active}>{routeLabel(r)}{!r.active?' (비활성)':''}</option>)}</select></FormRow>
        {enabled && candidates.length>0 && <FormRow label="교체할 기존 코드" htmlFor="form-replace"><select id="form-replace" className="t-select" name="replaceFormId" value={replace} onChange={e=>setReplace(e.target.value)}>
          <option value="">교체 없음</option>{candidates.map(c=><option key={c.id} value={c.id}>{c.code} {c.name}</option>)}</select>
          {previous && <input type="hidden" name="replaceExpectedVersion" value={previous.version}/>}</FormRow>}
        <Field label="변경 사유" name="reason" value={draft?.reason??''} maxLength={500} required/>
      </FormTable>
      <PreviewPanel employees={model.employees} routeId={routeId} enabled={enabled}
        edited={!row || routeId !== (row.routeVersionId ?? '') || enabled !== row.enabled}/>
      <button type="submit" className="t-btn t-btn-primary">저장</button>
    </Form>
    {model.history.length>0 && <section><h3>적용 이력</h3><ol className="rf-history">{model.history.map((v,i)=>{
      const newer=model.history[i-1];const route=model.routes.find(r=>r.id===v.routeVersionId);
      const end=newer?(newer.effectiveFrom===v.effectiveFrom?'같은 날 대체됨':newer.effectiveFrom+' 전까지'):'다음 변경까지';
      return <li key={v.id}><strong>{v.name} · {v.enabled?'사용':'사용 중지'}</strong>
        <div>{v.effectiveFrom==='0001-01-01'?'기초 설정':v.effectiveFrom} ~ {end}</div>
        <div>{route?routeLabel(route):'기존 배정 사용'} · {v.changedBy??'시스템'} · {fmtDateTime(v.createdAt)}</div><div>{v.reason}</div></li>;
    })}</ol></section>}
  </section>;
}

export function RequestFormPage({data}:{data:Record<string,unknown>}) {
  const model=data.requestForms as Model|undefined;
  const draft=data.draft as Draft|undefined;
  if(!model)return <ErrorNotice message="신청서 설정을 다시 조회해 주세요."/>;
  const params=new URLSearchParams(location.search);
  const showEditor=!!model.selected||!!draft||params.get('new')==='1';
  const selected=model.selected?.id??params.get('focus');
  return <><PageHeader title="신청서코드 관리" actions={<a className="t-btn" href={href(model.q,{new:'1'})}>신청서코드 등록</a>}/>
    <ErrorNotice message={data.error as string|undefined}/>{(data.flags as {saved?:boolean})?.saved && <p role="status">신청서 설정을 저장했습니다.</p>}
    <div className="rf-layout" data-editor={showEditor}>
      <section className="rf-list" aria-label="신청서 목록">
        <Form method="get" action={path} className="rf-search"><Field label="신청서 검색" name="q" value={model.q} placeholder="코드 또는 이름"/><button className="t-btn">조회</button></Form>
        <ul>{model.forms.map(r=><li key={r.id} aria-current={selected===r.id?'true':undefined}>
          <a href={href(model.q,{id:r.id})}>{r.code} {r.name}</a><span>{kinds[r.kind]} · {r.enabled?'사용':'사용 중지'}</span>
          <span>{r.routeName?r.routeName+' v'+r.routeRevision:'기존 배정 사용'}{r.nextFrom?' · 다음 적용 '+r.nextFrom:''}</span>
        </li>)}</ul>{!model.forms.length && <p>검색 조건에 맞는 신청서가 없습니다.</p>}
      </section>
      {showEditor?<Editor key={(model.selected?.id??'new')+'-'+(model.selected?.version??0)+'-'+(draft?.commandId??'')} model={model} draft={draft}/>:
        <p className="rf-empty">조회하거나 편집할 신청서를 선택해 주세요.</p>}
    </div>
  </>;
}
