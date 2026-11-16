// @vitest-environment jsdom
import { cleanup, fireEvent, render, within } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { WorkflowPages } from './WorkflowPages';

afterEach(cleanup);

it('예외 정정도 구조화된 휴게 입력을 제공하고 원천 참조와 입력을 보존한다', () => {
  const view = render(<WorkflowPages page="request/correction" csrf={{ token:'csrf', parameterName:'_csrf', headerName:'X-CSRF-TOKEN' }} reload={() => {}} data={{
    day: { workDate:'2026-11-04', events:[{id:'event-1',kind:'IN',occurredAt:'2026-11-04T00:00:00Z'}] },
    exceptional:true, actions:['KEEP','SET','CLEAR'], eventTimes:{},
    form:{ targetEmployeeId:'employee-1', workDate:'2026-11-04', baseInputRevision:3, sourceFingerprint:'source', commandId:'command', inAction:'KEEP', requestedInAt:'', outAction:'SET', requestedOutAt:'2026-11-04T18:00:15.750', presenceAsWork:true, breaksConfirmed:true, actualBreaks:'2026-11-04T12:00/2026-11-04T13:00', replacesRequestId:'original', reason:'기록 확인' },
  }}/>);
  expect(view.getByRole('region',{name:'현재 출퇴근 기록'})).toBeTruthy();
  const breaks = within(view.getByRole('region',{name:'실제 휴게 구간'}));
  fireEvent.change(breaks.getByLabelText('휴게 1 종료'),{target:{value:'2026-11-04T13:30'}});
  const form = view.container.querySelector('form')!;
  const data = new FormData(form);
  expect(data.get('actualBreaks')).toBe('2026-11-04T12:00/2026-11-04T13:30');
  expect(data.get('sourceFingerprint')).toBe('source');
  expect(data.get('baseInputRevision')).toBe('3');
  expect(data.get('replacesRequestId')).toBe('original');
  expect(data.get('_presenceAsWork')).toBe('on');
  expect(within(view.getByLabelText('출근 변경')).getByRole('option',{name:'시각 수정'}).getAttribute('value')).toBe('SET');
  const text = view.container.textContent ?? '';
  for (const hidden of ['원천', '(서울)', 'Asia/Seoul', '변경할 현재 승인 정정 ID', '민감정보를 적지 마세요', '작성자·대상자와 다른 승인자가'])
    expect(text).not.toContain(hidden);
  expect(view.container.querySelector('input[type="hidden"][name="replacesRequestId"]')?.getAttribute('value')).toBe('original');
  const out = view.getByLabelText('퇴근 시각') as HTMLInputElement;
  expect(out.step).toBe('1');
  expect(out.value).toMatch(/^2026-11-04T18:00:15(?:\.000)?$/);
  expect(data.get('requestedOutAt')).toBe('2026-11-04T18:00:15.750');
  fireEvent.change(out,{target:{value:'2026-11-04T18:30:45'}});
  expect(new FormData(form).get('requestedOutAt')).toMatch(/^2026-11-04T18:30:45(?:\.000)?$/);
});
