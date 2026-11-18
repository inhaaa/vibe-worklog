// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { OvertimePage } from './OvertimePage';
import { getNavigationContext } from '../navigation';

const csrf = { parameterName: '_csrf', headerName: 'X-CSRF-TOKEN', token: 'token' };
const day = { date:'2026-12-02', groupName:'운영조', workCode:'W01', dayKind:'WORKDAY', windows:[
  {kind:'AFTER',start:'18:30',end:'21:00',minutes:135,breaks:[{kind:'AFTER',start:'20:00',end:'20:15'}]},
] };
const row = {id:'a',employeeName:'김사원',employeeNo:'26001',kind:'OVERTIME',status:'PENDING',effectStatus:'NONE',
  workDate:day.date,startTime:'18:30',endTime:'21:00',requestedMinutes:135,approvedMinutes:0,actualIn:'09:00',actualOut:'20:00',currentStep:2,totalSteps:2};
const model = {today:'2026-11-18',date:day.date,day,rows:[],from:'2026-12-01',to:'2026-12-05',page:0,status:'',q:'',hasNext:false};
const draw = (admin=false, extra: Record<string, unknown> = {}) => render(<OvertimePage page={'overtime/'+(admin?'admin':'employee')}
  data={{overtime:{...model,...extra}}} csrf={csrf} reload={()=>{}} />);
afterEach(()=>{cleanup();vi.unstubAllGlobals()});
it('미리보기의 계산 결과와 결재자를 보여 주고 변경하면 다시 확인한다', async()=>{
  const fetcher=vi.fn().mockResolvedValue(new Response(JSON.stringify({requestedMinutes:135,basisToken:'basis',routeToken:'route',steps:[{role:'팀장',approverName:'김팀장'},{role:'실장',approverName:'박실장'}]})));
  vi.stubGlobal('fetch',fetcher);
  draw();
  fireEvent.change(screen.getByLabelText('신청 사유'),{target:{value:'마감 자료 점검'}});
  fireEvent.click(screen.getByRole('button',{name:'결재선·신청 시간 확인'}));
  const preview=await screen.findByRole('region',{name:'제출 전 확인'});
  expect(within(preview).getByText('135분')).toBeTruthy();
  expect(preview.textContent).toContain('김팀장');expect(preview.textContent).toContain('박실장');
  expect(fetcher.mock.calls[0][0]).toBe('/me/overtime/preview');
  expect(fetcher.mock.calls[0][1].headers[csrf.headerName]).toBe(csrf.token);
  fireEvent.change(screen.getByLabelText('시작 시각'),{target:{value:'19:50'}});
  expect(screen.queryByRole('button',{name:'연장근무 제출'})).toBeNull();
});
it('불가능한 근무일에 신청서를 닫고 서버 판정 사유를 표시한다',()=>{
  draw(false,{day:{...day,windows:[],unavailableReason:'회사 휴일에는 신청할 수 없습니다.'}});
  expect(screen.getByText('회사 휴일에는 신청할 수 없습니다.')).toBeTruthy();
  expect(screen.queryByLabelText('신청 사유')).toBeNull();
});
it('담당자가 승인 상태와 실제 기록을 구분하고 필터를 페이지 링크에 유지한다',()=>{
  draw(true,{rows:[row],q:'김',status:'PENDING',hasNext:true});
  expect(screen.getByRole('heading',{name:'연장근무 신청현황'})).toBeTruthy();
  expect(screen.getByText(/2\/2단계/)).toBeTruthy();expect(screen.getByText('20:00')).toBeTruthy();
  expect(screen.getByRole('link',{name:'상세 보기'}).getAttribute('href')).toContain('/requests/a');
  const next=new URL(screen.getByRole('link',{name:'다음'}).getAttribute('href')!,location.origin);
  expect(next.searchParams.get('q')).toBe('김');expect(next.searchParams.get('status')).toBe('PENDING');expect(next.searchParams.get('page')).toBe('1');
  expect(getNavigationContext('request/detail','/requests/a',{request:{kind:'OVERTIME'},admin:true})?.href).toBe('/admin/overtime');
});
it('저장 응답을 받지 못하면 같은 명령으로 다시 제출할 수 있다',async()=>{
  const fetcher=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({requestedMinutes:135,steps:[{approverName:'팀장'}],routeToken:'route',basisToken:'basis'})))
    .mockRejectedValueOnce(new Error('offline')).mockRejectedValueOnce(new Error('offline'));
  vi.stubGlobal('fetch',fetcher);draw();
  fireEvent.change(screen.getByLabelText('신청 사유'),{target:{value:'필수 마감'}});
  fireEvent.click(screen.getByRole('button',{name:'결재선·신청 시간 확인'}));
  fireEvent.click(await screen.findByRole('button',{name:'연장근무 제출'}));
  fireEvent.click(await screen.findByRole('button',{name:'같은 신청 다시 제출'}));
  await waitFor(()=>expect(fetcher).toHaveBeenCalledTimes(3));
  expect(fetcher.mock.calls[1][1].body).toBe(fetcher.mock.calls[2][1].body);
  expect(JSON.parse(fetcher.mock.calls[1][1].body)).toMatchObject({start:'18:30',end:'21:00',reason:'필수 마감',basisToken:'basis',routeToken:'route'});
});

it('날짜를 변경하면 이전 근무 기준으로 미리보거나 제출할 수 없다',()=>{
  draw();fireEvent.change(screen.getByLabelText('신청 근무일'),{target:{value:'2026-12-03'}});
  expect(screen.queryByRole('button',{name:'결재선·신청 시간 확인'})).toBeNull();
});
it('담당자 복수 기록과 미기록을 구분한다',()=>{
  draw(true,{rows:[{...row,actualIn:null,actualOut:null,multipleIn:true,multipleOut:false}]});
  expect(screen.getByText('복수 기록 · 확인 필요')).toBeTruthy();expect(screen.getByText('미기록')).toBeTruthy();
});

it('기간 미지정 목록의 다음 페이지에서 null 검색어를 만들지 않고 역순 기간을 알려준다',()=>{
  draw(true,{rows:[row],from:null,to:null,hasNext:true});
  const next=new URL(screen.getByRole('link',{name:'다음'}).getAttribute('href')!,location.origin);
  expect([...next.searchParams]).toEqual([['page','1']]);
  fireEvent.change(screen.getByLabelText('시작일'),{target:{value:'2026-12-10'}});
  fireEvent.change(screen.getByLabelText('종료일'),{target:{value:'2026-12-01'}});
  expect(screen.getByRole('alert').textContent).toContain('종료일');
  expect((screen.getByRole('button',{name:'조회'}) as HTMLButtonElement).disabled).toBe(true);
});
