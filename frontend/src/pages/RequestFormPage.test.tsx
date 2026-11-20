// @vitest-environment jsdom
import { render, fireEvent, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { RequestFormPage } from './RequestFormPage';

const row={id:'l01',code:'L01',kind:'LEAVE_DAY',name:'휴가 신청서',version:0,enabled:true,nextFrom:null};
const data={requestForms:{q:'휴가',today:'2026-11-18',forms:[row],selected:row,history:[],routes:[],
  employees:[{id:'e',employeeNo:'0001',name:'김사원'}],replacementOptions:[row]}};

afterEach(()=>vi.unstubAllGlobals());
it('사원을 선택하고 기존 배정의 미리보기를 조회하며 코드·이름 검색 문맥을 유지한다',async()=>{
  const response={page:'org/request-forms',data:{routePreview:{eligible:true,steps:[{stepNumber:1,role:'팀장',approverName:'한팀장'}]}},csrf:{}};
  const send=vi.fn().mockResolvedValue(new Response(JSON.stringify(response),{headers:{'Content-Type':'application/json'}}));
  vi.stubGlobal('fetch',send);
  render(<RequestFormPage data={data}/>);
  fireEvent.change(screen.getByLabelText('예시 사원'),{target:{value:'0001 김사원'}});
  fireEvent.click(screen.getByRole('button',{name:'결재선 미리보기'}));
  expect(await screen.findByText(/한팀장/)).toBeTruthy();
  expect(screen.getByText('저장된 결재선 · 오늘 본인 신청 기준')).toBeTruthy();
  expect(send.mock.calls[0][0]).toContain('employeeId=e');
  expect(screen.getByRole('link',{name:'L01 휴가 신청서'}).getAttribute('href')).toContain('q=%ED%9C%B4%EA%B0%80');
});
