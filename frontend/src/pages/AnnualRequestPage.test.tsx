// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import type { PageEnvelope } from '../types';
import type { ComposeView } from './AnnualRequestTypes';
import { AnnualRequestPage } from './AnnualRequestPage';

const csrf={token:'csrf',parameterName:'_csrf',headerName:'X-CSRF-TOKEN'};
const principal={workspacePrincipal:{roles:['EMPLOYEE']},workspaceEmployee:{employeeName:'합성 직원',companyName:'합성 회사'}};
const bucket={id:'bucket-1',usableFrom:'2026-01-01',usableTo:'2027-12-31',balanceDays:9,availableDays:9,pendingDays:2};
const leave={bucketId:'bucket-1',from:'2026-11-01',to:'2026-11-02',reason:'',commandId:'leave-command',predecessorRequestId:null};
const correction={targetEmployeeId:null,workDate:'2026-10-28',baseInputRevision:3,sourceFingerprint:'a'.repeat(64),inAction:'KEEP',requestedInAt:'',outAction:'KEEP',requestedOutAt:'',presenceAsWork:false,breaksConfirmed:false,actualBreaks:'',replacesRequestId:'current-correction',predecessorRequestId:null,reason:'',commandId:'correction-command'};
const approval={source:'ORGANIZATION',routeName:'조직장 결재선',approverName:'합성 조직장',eligible:true,message:'결재 가능'};
const type={id:'type-1',code:'ANNUAL_LEAVE',name:'연차',systemKind:'ANNUAL_LEAVE',supported:true};
const request={id:'request-1',targetEmployeeName:'합성 직원',kind:'LEAVE_DAY',fromDate:'2026-11-01',toDate:'2026-11-02',quantityDays:2,status:'PENDING',effectStatus:'NONE',version:0,reason:'',approverName:'합성 조직장',originalRequestId:null};
const annual={year:2026,today:'2026-10-28',asOf:'2026-10-28T01:00:00Z',typeId:null,status:'',page:0,hasNext:false,authoredPage:0,authoredHasNext:false,coverage:'REGISTERED_DATA_ONLY',usedDays:3,plannedDays:2,pendingDays:2,currentAvailableDays:9,registeredDays:null,attributedBalanceDays:null,unknownBucketCount:1,attributionPartial:true,types:[type,{id:'draft',code:'SICK',name:'병가 초안',systemKind:null,supported:false}],summaries:[{typeId:type.id,code:type.code,name:type.name,kind:'LEAVE_DAY',requestCount:4,pendingCount:1,approvedCount:2,rejectedCount:1,withdrawnCount:0,usedDays:3,plannedDays:2,pendingDays:2}],requests:[request],canResubmitRequestIds:['request-1'],authoredRequests:[],attributionBuckets:[],createdId:null,outsideYear:null};
const compose={mode:'MODAL',kind:'LEAVE_DAY',leaveForm:leave,correctionForm:correction,day:{workDate:correction.workDate,inputRevision:3,sourceFingerprint:correction.sourceFingerprint,effectiveEvents:[],events:[],schedule:null,confirmation:null},preview:null,approval,conflict:false};
function envelope(a:Record<string,unknown>={},c:Record<string,unknown>|null=null,error?:string):PageEnvelope{return{page:'request/mine',csrf,data:{...principal,buckets:[bucket],requests:[request],form:leave,annual:{...annual,...a},compose:c?{...compose,...c}:null,error}};}
function response(data:PageEnvelope,status=200){return new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/vnd.worklog.page+json'}});}
function preview(c:Record<string,unknown>={}){return envelope({}, {...compose,preview:{quantityDays:2,availableDays:9,selectedBucketAvailableDays:9,policy:{code:'ANNUAL_LEAVE',name:'연차',revision:1},approval,eligible:true,message:'미리보기 완료'},...c});}
beforeEach(()=>{history.replaceState(null,'','/me/requests?year=2026');Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value:function(this:HTMLDialogElement){this.open=true;}});Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value:function(this:HTMLDialogElement){this.open=false;}});});
afterEach(()=>{cleanup();vi.restoreAllMocks();vi.unstubAllGlobals();});

describe('연도별 본인 현황과 모달 신청',()=>{
 it('신청 창의 X로 닫고 입력이 있으면 기존 손실 확인을 지킨다',()=>{
  const confirm=vi.spyOn(window,'confirm').mockReturnValue(false);
  const view=render(<App initial={envelope({},compose)}/>);
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  fireEvent.change(view.getByLabelText('신청 사유'),{target:{value:'보존할 입력'}});
  fireEvent.click(view.getByRole('button',{name:'신청 창 닫기'}));
  expect(confirm).toHaveBeenCalledTimes(1);
  expect(view.getByRole('dialog')).toBeTruthy();
  confirm.mockReturnValue(true);
  fireEvent.click(view.getByRole('button',{name:'신청 창 닫기'}));
  expect(view.queryByRole('dialog')).toBeNull();
 });
 it('공통 모달을 열면 결재선·작성자·신청서가 보이고 입력 보호를 공통 모달에 위임한다',()=>{
  const onClose=vi.fn();
  const initial=envelope({},compose);
  initial.data.employeeHeader={employeeName:'합성 직원',employeeNo:'24001',organizationName:'제품팀'};
  const view=render(<AnnualRequestPage {...initial} reload={()=>{}} onClose={onClose}/>);
  expect(view.queryByRole('dialog')).toBeNull();
  expect(view.queryByRole('heading',{name:'내 근태현황·신청'})).toBeNull();
  expect(view.queryByRole('heading',{name:'근태 신청'})).toBeNull();
  const route=within(view.getByRole('region',{name:'결재선'}));
  expect(route.getByText('조직장 결재선')).toBeTruthy();
  expect(route.getByText('합성 조직장')).toBeTruthy();
  const applicant=within(view.getByRole('region',{name:'신청자'}));
  expect(applicant.getByText('합성 직원')).toBeTruthy();
  expect(applicant.getByText('24001')).toBeTruthy();
  expect(applicant.getByText('제품팀')).toBeTruthy();
  expect(view.getByRole('heading',{name:'신청서'})).toBeTruthy();
  fireEvent.change(view.getByLabelText('신청 사유'),{target:{value:'작성 중'}});
  expect(view.container.querySelector('[data-unsaved-changes="true"]')).toBeTruthy();
  expect(new FormData(view.container.querySelector('form[action="/me/requests/leave"]')!).get('mode')).toBe('MODAL');
  expect(view.queryByRole('button',{name:'닫기'})).toBeNull();
  expect(view.container.querySelector('[data-modal-busy="false"]')).toBeTruthy();
 });
 it('신청 유형을 변경하면 새 근거 조회 전까지 상단에 이전 결재선을 표시하지 않는다',()=>{
  const view=render(<AnnualRequestPage {...envelope({},compose)} reload={()=>{}} onClose={()=>{}}/>);
  fireEvent.click(view.getByRole('button',{name:'유형 변경'}));
  fireEvent.click(view.getByRole('radio',{name:/출퇴근·휴게 정정/}));
  const route=within(view.getByRole('region',{name:'결재선'}));
  expect(route.getByText('유형·일자 조회 후 확인')).toBeTruthy();
  expect(route.queryByText('합성 조직장')).toBeNull();
 });
 it('연차 선택 시트에서 기간과 잔여를 비교하고 고른 묶음으로 미리보기 한다',async()=>{
  const second={...bucket,id:'bucket-2',usableFrom:'2026-10-01',usableTo:null,availableDays:4,pendingDays:1};
  const selected={...leave,bucketId:second.id,reason:'합성 연차 신청'};
  const fetcher=vi.fn<typeof fetch>().mockResolvedValue(response(preview({leaveForm:selected})));
  vi.stubGlobal('fetch',fetcher);
  const initial=envelope({},compose);
  initial.data.buckets=[bucket,second];
  const view=render(<App initial={initial}/>);
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  const sheet=within(view.getByRole('group',{name:'사용할 연차'}));
  const first=sheet.getByRole('radio',{name:/연차 1.*사용 가능 9일/}) as HTMLInputElement;
  const next=sheet.getByRole('radio',{name:/연차 2.*2026-10-01.*종료 없음.*사용 가능 4일.*결재 대기 1일/}) as HTMLInputElement;
  expect(first.checked).toBe(true);
  fireEvent.click(next);
  expect(next.checked).toBe(true);
  expect(first.checked).toBe(false);
  fireEvent.change(view.getByLabelText('신청 사유'),{target:{value:selected.reason}});
  fireEvent.click(view.getByRole('button',{name:'미리보기'}));
  await view.findByRole('region',{name:'신청 미리보기'});
  expect(new URLSearchParams(fetcher.mock.calls[0][1]?.body as URLSearchParams).getAll('bucketId')).toEqual([second.id]);
 });
 it('신청서 안의 날짜 시트에서 시작일과 종료일을 선택해 미리보기로 전송한다',async()=>{
  const edited={...leave,from:'2026-11-03',to:'2026-11-06',reason:'기간 확인'};
  const fetcher=vi.fn<typeof fetch>().mockResolvedValue(response(preview({leaveForm:edited})));
  vi.stubGlobal('fetch',fetcher);
  const view=render(<App initial={envelope({},compose)}/>);
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  const sheet=within(view.getByRole('group',{name:'신청 기간'}));
  const from=sheet.getByRole('button',{name:/^시작일/});
  const to=sheet.getByRole('button',{name:/^종료일/});
  expect(sheet.queryByRole('textbox')).toBeNull();
  fireEvent.click(from);
  const fromCalendar=within(sheet.getByRole('region',{name:'시작일 선택'}));
  fireEvent.click(fromCalendar.getByRole('button',{name:edited.from}));
  expect(document.activeElement).toBe(from);
  expect(from.getAttribute('aria-expanded')).toBe('false');
  expect(to.getAttribute('aria-invalid')).toBe('true');
  fireEvent.change(view.getByLabelText('신청 사유'),{target:{value:edited.reason}});
  fireEvent.click(view.getByRole('button',{name:'미리보기'}));
  expect(fetcher).not.toHaveBeenCalled();
  expect(document.activeElement).toBe(to);
  fireEvent.click(to);
  const toCalendar=within(sheet.getByRole('region',{name:'종료일 선택'}));
  expect(toCalendar.getByRole('button',{name:leave.to}).hasAttribute('disabled')).toBe(true);
  fireEvent.click(toCalendar.getByRole('button',{name:edited.to}));
  expect(to.getAttribute('aria-invalid')).not.toBe('true');
  fireEvent.click(view.getByRole('button',{name:'미리보기'}));
  await view.findByRole('region',{name:'신청 미리보기'});
  const sent=new URLSearchParams(fetcher.mock.calls[0][1]?.body as URLSearchParams);
  expect(sent.getAll('from')).toEqual([edited.from]);
  expect(sent.getAll('to')).toEqual([edited.to]);
 });
 it('달력에서 키보드로 월을 넘기고 Escape로 신청서는 유지한 채 날짜 선택만 닫는다',()=>{
  const view=render(<App initial={envelope({}, {...compose,leaveForm:{...leave,from:'2026-12-31',to:'2027-01-04'}})}/>);
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  const trigger=view.getByRole('button',{name:/^시작일/});
  fireEvent.click(trigger);
  const calendar=within(view.getByRole('region',{name:'시작일 선택'}));
  const lastDay=calendar.getByRole('button',{name:'2026-12-31'});
  expect(document.activeElement).toBe(lastDay);
  fireEvent.keyDown(lastDay,{key:'ArrowRight'});
  const firstDay=calendar.getByRole('button',{name:'2027-01-01'});
  expect(document.activeElement).toBe(firstDay);
  fireEvent.keyDown(firstDay,{key:'Escape'});
  expect(view.queryByRole('region',{name:'시작일 선택'})).toBeNull();
  expect(document.activeElement).toBe(trigger);
  expect(view.getByRole('dialog')).toBeTruthy();
  expect(trigger.textContent).toContain('2026-12-31');
 });
 it('고를 수 있는 연차 묶음이 없으면 신청 미리보기 전송을 막는다',()=>{
  const fetcher=vi.fn<typeof fetch>();
  vi.stubGlobal('fetch',fetcher);
  const initial=envelope({}, {...compose,leaveForm:{...leave,bucketId:null}});
  initial.data.buckets=[];
  const view=render(<App initial={initial}/>);
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  const sheet=within(view.getByRole('group',{name:'사용할 연차'}));
  expect(sheet.getByText('선택할 수 있는 연차 묶음이 없습니다.')).toBeTruthy();
  expect((view.getByRole('button',{name:'미리보기'}) as HTMLButtonElement).disabled).toBe(true);
  fireEvent.submit(view.getByRole('form',{name:'신청서'}));
  expect(fetcher).not.toHaveBeenCalled();
 });
 it('페이지 제목 옆에서 신청을 시작하고 조회 필터와 분리한다',()=>{
  const view=render(<App initial={envelope()}/>);
  const head=view.getByRole('heading',{name:'내 근태현황·신청'}).closest('header')!;
  expect(within(head).getByRole('button',{name:'신청'}).getAttribute('href')).toContain('/me/requests/new?year=2026');
  expect(within(view.getByRole('search',{name:'연도별 근태 조회'})).queryByRole('button',{name:'신청'})).toBeNull();
 });
 it('연차 내용 단계는 중복 날짜 조회를 숨기고 정정은 근거 날짜 조회를 유지한다',()=>{
  const leaveView=render(<App initial={envelope({},compose)}/>);
  fireEvent.click(leaveView.getByRole('button',{name:'내용 작성으로'}));
  expect(leaveView.queryByRole('button',{name:'유형·일자 조회'})).toBeNull();
  expect(leaveView.getByRole('button',{name:/^시작일/})).toBeTruthy();
  leaveView.unmount();
  const correctionView=render(<App initial={envelope({}, {...compose,kind:'ATTENDANCE_CORRECTION'})}/>);
  fireEvent.click(correctionView.getByRole('button',{name:'내용 작성으로'}));
  expect(correctionView.getByRole('button',{name:'유형·일자 조회'})).toBeTruthy();
 });
 it('작성 요약은 입력 기간과 현재 잔여를 보여주며 서버 확인 전 차감량을 만들지 않는다',()=>{
  const view=render(<App initial={envelope({},compose)}/>);
  const summary=within(view.getByRole('complementary',{name:'신청 요약'}));
  expect(summary.getByText('종일 연차')).toBeTruthy();
  expect(summary.getByText(/9 DAY/)).toBeTruthy();
  expect(summary.queryByText(/예상 차감/)).toBeNull();
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  fireEvent.click(view.getByRole('button',{name:/^종료일/}));
  fireEvent.click(within(view.getByRole('region',{name:'종료일 선택'})).getByRole('button',{name:'2026-11-04'}));
  expect(summary.getByText(/2026-11-01 ~ 2026-11-04/)).toBeTruthy();
  expect(summary.getByText('합성 조직장')).toBeTruthy();
 });
 it('새 유형의 근거를 조회하기 전에는 이전 결재 담당을 요약에 확정 표시하지 않는다',()=>{
  const view=render(<App initial={envelope({},compose)}/>);
  fireEvent.click(view.getByRole('radio',{name:/출퇴근·휴게 정정/}));
  const summary=within(view.getByRole('complementary',{name:'신청 요약'}));
  expect(summary.getByText('출퇴근·휴게 정정')).toBeTruthy();
  expect(summary.getByText('유형·일자 조회 후 확인')).toBeTruthy();
  expect(summary.queryByText('합성 조직장')).toBeNull();
 });
 it('미리보기 후 요약도 서버가 다시 확인한 현재 잔여로 갱신한다',async()=>{
  const data=preview();
  const c=data.data.compose as ComposeView;
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response(preview({leaveForm:{...leave,reason:'새 잔여 확인'},preview:{...c.preview,availableDays:7,selectedBucketAvailableDays:7}}))));
  const view=render(<App initial={envelope({},compose)}/>);
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  fireEvent.change(view.getByLabelText('신청 사유'),{target:{value:'새 잔여 확인'}});
  fireEvent.click(view.getByRole('button',{name:'미리보기'}));
  await waitFor(()=>expect(within(view.getByRole('navigation',{name:'신청 작성 단계'})).getByText('제출 전 확인').getAttribute('aria-current')).toBe('step'));
  const summary=within(view.getByRole('complementary',{name:'신청 요약'}));
  expect(summary.getByText('7 DAY')).toBeTruthy();
  expect(summary.queryByText('9 DAY')).toBeNull();
 });
 it('실제 생성 ID가 있을 때 접수 안내와 상세 링크를 상태 영역으로 제공한다',()=>{
  const view=render(<App initial={envelope({createdId:'created-request'})}/>);
  const status=within(view.getByRole('status',{name:'신청 접수 결과'}));
  expect(status.getByText('신청을 접수했습니다.')).toBeTruthy();
  expect(status.getByRole('link',{name:'생성한 신청 상세'}).getAttribute('href')).toBe('/requests/created-request');
  expect(status.getByText(/승인 결과는 신청 상세/)).toBeTruthy();
 });
 it('미래 연차에서 정정으로 바꾸면 오늘 날짜를 제안하고 한 번의 다음 동작으로 내용에 진입한다',async()=>{
  const future='2026-11-03';
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response(envelope({}, {...compose,kind:'ATTENDANCE_CORRECTION',correctionForm:correction}))));
  const view=render(<App initial={envelope({}, {...compose,leaveForm:{...leave,from:future},correctionForm:{...correction,workDate:future}})}/>);
  fireEvent.click(view.getByRole('radio',{name:/출퇴근·휴게 정정/}));
  expect((view.getByLabelText('정정할 날짜') as HTMLInputElement).value).toBe(annual.today);
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  await waitFor(()=>expect(within(view.getByRole('navigation',{name:'신청 작성 단계'})).getByText('내용 작성').getAttribute('aria-current')).toBe('step'));
  expect(view.getByLabelText('신청 사유')).toBeTruthy();
 });
 it('새 신청은 지원하는 유형 카드와 세 단계로 시작하고 내용 작성으로 이동한다',()=>{
  const view=render(<App initial={envelope({},compose)}/>);
  const steps=view.getByRole('navigation',{name:'신청 작성 단계'});
  expect(within(steps).getByText('신청 유형').getAttribute('aria-current')).toBe('step');
  expect(view.getByRole('radio',{name:/종일 연차/})).toBeTruthy();
  expect(view.getByRole('radio',{name:/출퇴근·휴게 정정/})).toBeTruthy();
  expect(view.queryByRole('radio',{name:/병가/})).toBeNull();
  expect(view.queryByRole('textbox',{name:'신청 사유'})).toBeNull();
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  expect(within(steps).getByText('내용 작성').getAttribute('aria-current')).toBe('step');
  expect(view.getByRole('textbox',{name:'신청 사유'})).toBeTruthy();
 });
 it('미리보기 후 확인 단계에서 내용을 수정하면 같은 입력을 유지하고 다시 확인한다',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response(preview({leaveForm:{...leave,reason:'확인할 내용'}}))));
  const view=render(<App initial={envelope({},compose)}/>);
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  fireEvent.change(view.getByLabelText('신청 사유'),{target:{value:'확인할 내용'}});
  fireEvent.click(view.getByRole('button',{name:'미리보기'}));
  await waitFor(()=>expect(within(view.getByRole('navigation',{name:'신청 작성 단계'})).getByText('제출 전 확인').getAttribute('aria-current')).toBe('step'));
  expect(view.getByRole('button',{name:'신청 제출'}).hasAttribute('disabled')).toBe(false);
  fireEvent.click(view.getByRole('button',{name:'내용 수정'}));
  expect((view.getByRole('textbox',{name:'신청 사유'}) as HTMLTextAreaElement).value).toBe('확인할 내용');
  expect(view.queryByRole('button',{name:'신청 제출'})).toBeNull();
  expect(new FormData(view.container.querySelector('form[action="/me/requests/leave"]')!).get('commandId')).toBe('leave-command');
 });
 it('현재 페이지 검색은 이력만 좁히며 연간 요약과 서버 필터를 바꾸지 않는다',()=>{
  const view=render(<App initial={envelope({requests:[request,{...request,id:'correction-1',kind:'ATTENDANCE_CORRECTION',reason:'퇴근 누락'}],hasNext:true})}/>);
  fireEvent.change(view.getByRole('searchbox',{name:'현재 페이지 신청 검색'}),{target:{value:'퇴근 누락'}});
  const board=view.getByRole('region',{name:'내 대상 신청 이력'});
  expect(within(board).queryByRole('link',{name:'종일 연차'})).toBeNull();
  expect(within(board).getByRole('link',{name:'출퇴근·휴게 정정'})).toBeTruthy();
  expect(view.getByText('3 DAY')).toBeTruthy();
  expect(within(view.getByRole('search',{name:'연도별 근태 조회'})).getByLabelText('상태')).toBeTruthy();
  expect(view.getByRole('navigation',{name:'내 신청 페이지'}).querySelector('a')?.getAttribute('href')).toContain('year=2026');
 });
 it('정정 동작은 한글 문구로 표시하며 KEEP·SET·CLEAR 전송값을 보존한다',()=>{
  const view=render(<App initial={envelope({}, {...compose,kind:'ATTENDANCE_CORRECTION'})}/>);
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  const action=view.getByLabelText('출근 변경');
  expect(within(action).getByRole('option',{name:'유지'}).getAttribute('value')).toBe('KEEP');
  expect(within(action).getByRole('option',{name:'시각 수정'}).getAttribute('value')).toBe('SET');
  expect(within(action).getByRole('option',{name:'누락으로 정정'}).getAttribute('value')).toBe('CLEAR');
  expect(view.queryByText(/KEEP은/)).toBeNull();
 });
 it('내용 작성에서 유형 단계로 돌아오면 선택 카드로 초점을 옮기고 입력을 유지한다',()=>{
  const view=render(<App initial={envelope({},compose)}/>);
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  fireEvent.change(view.getByLabelText('신청 사유'),{target:{value:'유지할 사유'}});
  fireEvent.click(view.getByRole('button',{name:'이전'}));
  expect(document.activeElement).toBe(view.getByRole('radio',{name:/종일 연차/}));
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  expect((view.getByRole('textbox',{name:'신청 사유'}) as HTMLTextAreaElement).value).toBe('유지할 사유');
 });
 it('정정 제출 전 확인에는 제안 출퇴근·실제 근로·휴게 내용을 함께 보여준다',()=>{
  const view=render(<App initial={preview({kind:'ATTENDANCE_CORRECTION',correctionForm:{...correction,inAction:'SET',requestedInAt:'2026-10-28T09:00',outAction:'CLEAR',presenceAsWork:true,breaksConfirmed:true,actualBreaks:'2026-10-28T12:00/2026-10-28T13:00',reason:'정정 내용'}})}/>);
  const review=within(view.getByRole('region',{name:'신청 미리보기'}));
  expect(review.getByText(/시각 수정 · 2026-10-28 09:00/)).toBeTruthy();
  expect(review.getByText('누락으로 정정')).toBeTruthy();
  expect(review.getByText('실제 근로 확인 · 실제 휴게 확인')).toBeTruthy();
  expect(review.getByText('2026-10-28 12:00 → 2026-10-28 13:00')).toBeTruthy();
 });
 it('유형만 바꿨다가 돌아오면 원래 조회일과 작성 내용을 유지한다',()=>{
  const fetcher=vi.fn();vi.stubGlobal('fetch',fetcher);
  const view=render(<App initial={envelope({},compose)}/>);
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  fireEvent.change(view.getByLabelText('신청 사유'),{target:{value:'유형 전환 보존'}});
  fireEvent.click(view.getByRole('button',{name:'유형 변경'}));
  fireEvent.click(view.getByRole('radio',{name:/출퇴근·휴게 정정/}));
  fireEvent.click(view.getByRole('radio',{name:/종일 연차/}));
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  expect(fetcher).not.toHaveBeenCalled();
  expect((view.getByRole('textbox',{name:'신청 사유'}) as HTMLTextAreaElement).value).toBe('유형 전환 보존');
 });
 it('적격하지 않은 미리보기의 사유를 확인 단계에 보여주고 제출을 차단한다',()=>{
  const data=preview();
  const c=data.data.compose as ComposeView;
  const result=preview({preview:{...c.preview,eligible:false,message:'결재 담당자의 현재 자격을 확인하세요'}});
  const view=render(<App initial={result}/>);
  expect(within(view.getByRole('region',{name:'신청 미리보기'})).getByText('결재 담당자의 현재 자격을 확인하세요')).toBeTruthy();
  expect(view.getByRole('button',{name:'신청 제출'}).hasAttribute('disabled')).toBe(true);
 });
 it('미리보기 중 중복 요청을 막고 422 응답은 내용 단계·입력·동일 명령을 유지한다',async()=>{
  let resolve!:(value:Response)=>void;
  const fetcher=vi.fn().mockReturnValue(new Promise<Response>(done=>{resolve=done;}));vi.stubGlobal('fetch',fetcher);
  const view=render(<App initial={envelope({},compose)}/>);
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  fireEvent.change(view.getByLabelText('신청 사유'),{target:{value:'검증 응답 보존'}});
  const getForm=()=>view.container.querySelector('form[action="/me/requests/leave"]') as HTMLFormElement;
  const commandId=String(new FormData(getForm()).get('commandId'));
  const button=view.getByRole('button',{name:'미리보기'});
  fireEvent.click(button);fireEvent.click(button);
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(view.getByRole('button',{name:'닫기'}).hasAttribute('disabled')).toBe(true);
  resolve(response(envelope({}, {...compose,leaveForm:{...leave,reason:'검증 응답 보존',commandId}},'기간을 다시 확인하세요'),422));
  await waitFor(()=>expect(view.getByRole('alert').textContent).toContain('기간을 다시 확인하세요'));
  expect((view.getByRole('textbox',{name:'신청 사유'}) as HTMLTextAreaElement).value).toBe('검증 응답 보존');
  expect(new FormData(getForm()).get('commandId')).toBe(commandId);
  expect(within(view.getByRole('navigation',{name:'신청 작성 단계'})).getByText('내용 작성').getAttribute('aria-current')).toBe('step');
  expect(document.activeElement).toBe(view.getByRole('alert'));
 });
 it('연도별 조회를 먼저 제공하고 기본 화면에 즉시 제출 폼을 두지 않는다',()=>{
  const view=render(<App initial={envelope()}/>);
  expect(view.getByRole('heading',{name:'내 근태현황·신청'})).toBeTruthy();
  expect(view.getByRole('button',{name:'신청'}).getAttribute('href')).toContain('/me/requests/new?year=2026');
  expect(view.container.querySelector('form[action="/me/requests/leave"]')).toBeNull();
  expect(view.getByLabelText('조회 연도')).toBeTruthy();
  expect(view.getAllByText(/귀속 미확인/)[0]).toBeTruthy();
  expect(view.getByText(/조회 시점 오늘/)).toBeTruthy();
 });
 it('빈 자료와 확인된 부분합을 연간 전체 0으로 단정하지 않는다',()=>{
  const view=render(<App initial={envelope({coverage:'NO_REGISTERED_DATA',requests:[],summaries:[]})}/>);
  expect(view.getByText(/선택 연도의 등록 자료가 없습니다/)).toBeTruthy();
  expect(view.getByText(/연간 전체 사용량이나 자동 발생량/)).toBeTruthy();
 });
 it('목록 필터와 별개 연간 요약·본인 대상과 대리 작성 이력을 구분한다',()=>{
  const view=render(<App initial={envelope({status:'PENDING',hasNext:true,authoredHasNext:true,authoredRequests:[{...request,id:'proxy',targetEmployeeName:'대리 대상'}]})}/>);
  expect(view.getByRole('heading',{name:'내 대상 신청 이력'})).toBeTruthy();
  expect(view.getByRole('heading',{name:'내가 대리 작성한 이력'})).toBeTruthy();
  expect(view.getByText(/목록 필터와 무관한 연도 전체/)).toBeTruthy();
  expect(view.getByRole('navigation',{name:'내 신청 페이지'}).querySelector('a')?.getAttribute('href')).toContain('status=PENDING');
 });
 it('작성 GET의 모달에 레이블·최초 초점·서버 참조와 일반 페이지 링크를 제공한다',()=>{
  const view=render(<App initial={envelope({},compose)}/>);
  const dialog=view.getByRole('dialog',{name:'근태 신청'});
  expect(dialog.getAttribute('aria-modal')).toBe('true');
  expect(view.getByLabelText('신청 사유')).toBeTruthy();
  expect(view.getByRole('link',{name:'일반 작성 페이지'}).getAttribute('href')).toContain('mode=PAGE');
  expect(view.queryByRole('button',{name:'신청 제출'})).toBeNull();
  expect(dialog.contains(document.activeElement)).toBe(true);
 });
 it('미리보기는 기존 transport로 POST하고 URL·입력·명령을 유지한다',async()=>{
  const fetcher=vi.fn().mockResolvedValue(response(preview({leaveForm:{...leave,reason:'합성 휴가'}})));vi.stubGlobal('fetch',fetcher);
  const view=render(<App initial={envelope({},compose)}/>);
  fireEvent.change(view.getByLabelText('신청 사유'),{target:{value:'합성 휴가'}});
  const id=new FormData(view.container.querySelector('form[action="/me/requests/leave"]')!).get('commandId');
  fireEvent.click(view.getByRole('button',{name:'미리보기'}));
  await waitFor(()=>expect(view.getByText(/예상 차감 2 DAY/)).toBeTruthy());
  expect(String(fetcher.mock.calls[0][0])).toContain('/me/requests/preview');
  expect(new URLSearchParams(fetcher.mock.calls[0][1].body).get('commandId')).toBe(id);
  expect(location.pathname).toBe('/me/requests');
  expect(view.getByRole('button',{name:'신청 제출'}).hasAttribute('disabled')).toBe(false);
  expect(view.getAllByText(/합성 조직장/)[0]).toBeTruthy();
  await waitFor(()=>expect(document.activeElement).toBe(view.getByRole('heading',{name:'신청 미리보기'})));
 });
 it('미리보기 이후 편집하면 다시 미리보기를 요구한다',()=>{
  const view=render(<App initial={preview()}/>);
  expect(view.getByRole('button',{name:'신청 제출'}).hasAttribute('disabled')).toBe(false);
  fireEvent.change(view.getByLabelText('신청 사유'),{target:{value:'변경된 사유'}});
  expect(view.queryByRole('button',{name:'신청 제출'})).toBeNull();
  expect(view.getByRole('button',{name:'미리보기'})).toBeTruthy();
 });
 it('입력 중 Escape·닫기는 손실 확인 후 신청 버튼에 초점을 돌린다',()=>{
  const confirm=vi.spyOn(window,'confirm').mockReturnValue(false);
  const view=render(<App initial={envelope({},compose)}/>);
  fireEvent.change(view.getByLabelText('신청 사유'),{target:{value:'입력 중'}});
  fireEvent(view.getByRole('dialog'),new Event('cancel',{cancelable:true}));
  expect(confirm).toHaveBeenCalled();expect(view.getByRole('dialog')).toBeTruthy();
  confirm.mockReturnValue(true);fireEvent.click(view.getByRole('button',{name:'닫기'}));
  expect(view.queryByRole('dialog')).toBeNull();expect(document.activeElement).toBe(view.getByRole('button',{name:'신청'}));
 });
 it('정정은 날짜로 근거를 조회하고 현재 개정·fingerprint·대체 참조를 hidden으로 보낸다',()=>{
  const view=render(<App initial={envelope({}, {...compose,kind:'ATTENDANCE_CORRECTION'})}/>);
  const form=view.container.querySelector('form[action="/me/requests/correction"]')!;
  const data=new FormData(form as HTMLFormElement);
  expect(data.get('baseInputRevision')).toBe('3');expect(data.get('sourceFingerprint')).toBe('a'.repeat(64));expect(data.get('replacesRequestId')).toBe('current-correction');
  expect(view.queryByLabelText(/정정 ID/)).toBeNull();expect(view.getByLabelText('정정할 날짜')).toBeTruthy();
 });
 it('PAGE 모드는 같은 입력·CSRF를 가진 일반 React 폼이다',()=>{
  const view=render(<App initial={envelope({}, {...compose,mode:'PAGE'})}/>);
  expect(view.queryByRole('dialog')).toBeNull();expect(view.getByRole('heading',{name:'근태 신청'})).toBeTruthy();
  expect(new FormData(view.container.querySelector('form[action="/me/requests/leave"]')!).get('_csrf')).toBe('csrf');
 });
 it('422는 입력·선택 연도·명령과 열린 모달을 보존하고 오류에 초점을 둔다',()=>{
  const view=render(<App initial={envelope({}, {...compose,leaveForm:{...leave,reason:'보존할 사유'}},'휴일 자료 확인 필요')}/>);
  expect(view.getByRole('dialog')).toBeTruthy();expect((view.getByLabelText('신청 사유') as HTMLTextAreaElement).value).toBe('보존할 사유');
  const form=view.container.querySelector('form[action="/me/requests/leave"]')!;
  expect(new FormData(form as HTMLFormElement).get('commandId')).toBe('leave-command');expect(new FormData(form as HTMLFormElement).get('year')).toBe('2026');
  expect(document.activeElement?.textContent).toContain('휴일 자료 확인 필요');
 });
 it('생성 건 상세와 조회 연도 밖 대상연도 안내를 제공한다',()=>{
  const view=render(<App initial={envelope({createdId:'created-1',outsideYear:2027})}/>);
  expect(view.getByRole('link',{name:'생성한 신청 상세'}).getAttribute('href')).toBe('/requests/created-1');
  expect(view.getByRole('link',{name:'2027년 현황 보기'}).getAttribute('href')).toContain('year=2027');
 });
 it('503 미리보기 실패는 입력과 같은 명령을 유지해 재확인할 수 있다',async()=>{
  const fetcher=vi.fn().mockRejectedValue(new Error('network'));vi.stubGlobal('fetch',fetcher);
  const view=render(<App initial={envelope({},compose)}/>);
  fireEvent.change(view.getByLabelText('신청 사유'),{target:{value:'보존할 입력'}});
  const form=view.container.querySelector('form[action="/me/requests/leave"]') as HTMLFormElement;
  const id=new FormData(form).get('commandId');
  fireEvent.click(view.getByRole('button',{name:'미리보기'}));
  await waitFor(()=>expect(view.getByRole('alert').textContent).toContain('같은 내용'));
  expect((view.getByLabelText('신청 사유') as HTMLTextAreaElement).value).toBe('보존할 입력');
  expect(new FormData(form).get('commandId')).toBe(id);
 });
 it('409 최신 사실 확인은 제안을 유지하며 근거와 명령을 명시적으로 갱신한다',()=>{
  const view=render(<App initial={envelope({}, {...compose,kind:'ATTENDANCE_CORRECTION',conflict:true,currentCorrectionRequestId:'latest-correction',day:{...compose.day,inputRevision:8,sourceFingerprint:'b'.repeat(64)},correctionForm:{...correction,reason:'보존 제안',inAction:'CLEAR'}},'현재 사실 충돌')}/>);
  const form=()=>view.container.querySelector('form[action="/me/requests/correction"]') as HTMLFormElement;
  expect(new FormData(form()).get('baseInputRevision')).toBe('3');
  fireEvent.click(view.getByRole('button',{name:'최신 근거 확인 후 편집 유지'}));
  expect(new FormData(form()).get('baseInputRevision')).toBe('8');expect(new FormData(form()).get('sourceFingerprint')).toBe('b'.repeat(64));expect(new FormData(form()).get('replacesRequestId')).toBe('latest-correction');
  expect(new FormData(form()).get('commandId')).not.toBe('correction-command');expect(new FormData(form()).get('reason')).toBe('보존 제안');
 });
 it('귀속 관리에서 미분류는 연도를 보내지 않고 원장은 별도로 유지한다',()=>{
  const data={...principal,leaveAttributions:{bucket:{...bucket,employeeName:'합성 직원'},history:[],form:{expectedRevision:0,classificationStatus:'UNCLASSIFIED',entitlementYear:null,singleYearConfirmed:false,basis:'',reason:'',commandId:'attribution-command'}}};
  const view=render(<App initial={{page:'request/attributions',csrf,data}}/>);
  expect(view.getByRole('heading',{name:'기초 묶음 귀속연도'})).toBeTruthy();
  expect(view.getByRole('link',{name:'원장 보기'}).getAttribute('href')).toBe('/admin/leave-balances/buckets/bucket-1');
  const form=view.container.querySelector('form[action$="/attributions"]') as HTMLFormElement;
  expect(new FormData(form).get('entitlementYear')).toBeNull();
  fireEvent.change(view.getByLabelText('분류 상태'),{target:{value:'CONFIRMED'}});
  expect(view.getByLabelText('귀속연도')).toBeTruthy();expect(view.getByLabelText(/묶음 전체가 단일 귀속연도/)).toBeTruthy();
 });
 it('귀속 충돌은 최신 이력 확인 전 저장을 막고 기존 입력을 유지한다',()=>{
  const data={...principal,error:'귀속 정보 변경 충돌',leaveAttributions:{bucket:{...bucket,employeeName:'합성 직원'},history:[{id:'a',revision:3,classificationStatus:'CONFIRMED',entitlementYear:2027,basis:'최신 근거',reason:'분류',createdByName:'담당자',createdAt:'2026-10-28T01:00:00Z'}],form:{expectedRevision:2,classificationStatus:'CONFIRMED',entitlementYear:2026,singleYearConfirmed:true,basis:'기존 근거',reason:'수정 이유',commandId:'old-command'}}};
  const view=render(<App initial={{page:'request/attributions',csrf,data}}/>);
  expect(view.getByRole('button',{name:'귀속 이력 저장'}).hasAttribute('disabled')).toBe(true);
  fireEvent.click(view.getByRole('button',{name:'최신 이력 확인 후 편집 유지'}));
  const form=view.container.querySelector('form[action$="/attributions"]') as HTMLFormElement;
  expect(new FormData(form).get('expectedRevision')).toBe('3');expect(new FormData(form).get('commandId')).not.toBe('old-command');
  expect(new FormData(form).get('basis')).toBe('기존 근거');expect(new FormData(form).get('entitlementYear')).toBe('2026');
 });
 it('반려·철회한 본인 대상만 연도 문맥과 predecessor를 가진 재신청 링크를 제공한다',()=>{
  const view=render(<App initial={envelope({requests:[{...request,status:'REJECTED'}],authoredRequests:[{...request,id:'proxy',status:'WITHDRAWN'}]})}/>);
  const links=view.getAllByRole('link',{name:'재신청'});expect(links).toHaveLength(1);
  expect(links[0].getAttribute('href')).toContain('year=2026');expect(links[0].getAttribute('href')).toContain('predecessorRequestId=request-1');
 });
 it('native dialog 미지원 환경에는 같은 일반 작성 폼을 표시한다',()=>{
  Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value:undefined});
  const view=render(<App initial={envelope({},compose)}/>);
  expect(view.queryByRole('dialog')).toBeNull();expect(view.getByLabelText('신청 사유')).toBeTruthy();
 });
 it('정정 모달에서 현재 유효 출퇴근 근거를 서울 시각으로 확인한다',()=>{
  const view=render(<App initial={envelope({}, {...compose,kind:'ATTENDANCE_CORRECTION',day:{...compose.day,effectiveEvents:[{id:'event-1',kind:'IN',occurredAt:'2026-10-28T00:00:00Z'}]}})}/>);
  fireEvent.click(view.getByRole('button',{name:'내용 작성으로'}));
  expect(within(view.getByRole('region',{name:'현재 정정 근거'})).getByText(/2026-10-28 09:00:00/)).toBeTruthy();
  expect(view.queryByText('현재 사실 개정 3')).toBeNull();
 });
 it('빈 날짜의 422 응답은 연도와 빈 입력을 경고 없이 유지한다',()=>{
  const error=vi.spyOn(console,'error').mockImplementation(()=>{});
  const view=render(<App initial={envelope({}, {...compose,leaveForm:{...leave,from:null,to:null}},'시작일과 종료일을 확인하세요')}/>);
  const sheet=within(view.getByRole('group',{name:'신청 기간'}));
  expect(sheet.getByRole('button',{name:/^시작일/}).textContent).toContain('날짜 선택');
  expect(sheet.getByRole('button',{name:/^종료일/}).textContent).toContain('날짜 선택');
  const form=new FormData(view.getByRole('form',{name:'신청서'}) as HTMLFormElement);
  expect(form.get('from')).toBe('');expect(form.get('to')).toBe('');
  expect(error).not.toHaveBeenCalled();
 });
 it('최종 제출 중 닫기와 Escape를 막고 503 후 같은 입력·명령으로 재시도한다',async()=>{
  let resolve!: (value: Response)=>void;
  const pending=new Promise<Response>(done=>{resolve=done;});
  const fetcher=vi.fn().mockReturnValue(pending);vi.stubGlobal('fetch',fetcher);
  const confirm=vi.spyOn(window,'confirm').mockReturnValue(true);
  const view=render(<App initial={preview({leaveForm:{...leave,reason:'그대로 재시도'}})}/>);
  const getForm=()=>view.container.querySelector('form[action="/me/requests/leave"]') as HTMLFormElement;
  fireEvent.submit(getForm());
  fireEvent.submit(getForm());
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(view.getByRole('button',{name:'닫기'}).hasAttribute('disabled')).toBe(true);
  fireEvent(view.getByRole('dialog'),new Event('cancel',{cancelable:true}));
  expect(view.getByRole('dialog')).toBeTruthy();expect(confirm).not.toHaveBeenCalled();
  resolve(new Response('unavailable',{status:503}));
  await waitFor(()=>expect(view.getByRole('button',{name:'닫기'}).hasAttribute('disabled')).toBe(false));
  expect(new FormData(getForm()).get('commandId')).toBe('leave-command');expect(new FormData(getForm()).get('reason')).toBe('그대로 재시도');
  fireEvent.submit(getForm());await waitFor(()=>expect(fetcher).toHaveBeenCalledTimes(2));
  expect(new URLSearchParams(fetcher.mock.calls[1][1].body).get('commandId')).toBe('leave-command');
 });
 it('서버가 재신청을 허용하지 않은 담당자 대리 작성 건에는 재신청 링크를 숨긴다',()=>{
  const view=render(<App initial={envelope({requests:[{...request,kind:'ATTENDANCE_CORRECTION',status:'REJECTED'}],canResubmitRequestIds:[]})}/>);
  expect(view.queryByRole('link',{name:'재신청'})).toBeNull();
 });
 it('재신청의 날짜 근거를 다시 조회할 때도 같은 종류의 이전 신청 참조를 유지한다',()=>{
  const view=render(<App initial={envelope({}, {...compose,leaveForm:{...leave,predecessorRequestId:'previous-request'}})}/>);
  const getForm=()=>view.container.querySelector('form[action="/me/requests/new"]') as HTMLFormElement;
  expect(new FormData(getForm()).get('predecessorRequestId')).toBe('previous-request');
  fireEvent.click(view.getByRole('radio',{name:/출퇴근·휴게 정정/}));
  expect(new FormData(getForm()).get('predecessorRequestId')).toBeNull();
 });
});
