// @vitest-environment jsdom
import { cleanup, fireEvent, render, within } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { WorkflowPages } from './WorkflowPages';

afterEach(cleanup);

const csrf = { token:'csrf', parameterName:'_csrf', headerName:'X-CSRF-TOKEN' };
const missingOut = {
  day: { workDate:'2026-11-04', events:[{ id:'event-1', kind:'IN', occurredAt:'2026-11-04T00:00:00Z' }] },
  exceptional:false, checkoutOnly:true, actions:['KEEP','SET','CLEAR'], eventTimes:{},
  form:{ workDate:'2026-11-04', baseInputRevision:3, sourceFingerprint:'source', commandId:'command',
    inAction:'KEEP', requestedInAt:'', outAction:'SET', requestedOutAt:'', presenceAsWork:false,
    breaksConfirmed:false, actualBreaks:'', reason:'' },
};

it('퇴근 누락일에는 기존 출근을 표시하고 퇴근 및 실제 근로·휴게만 입력한다', () => {
  const view = render(<WorkflowPages page="request/correction" csrf={csrf} reload={() => {}} data={missingOut} />);
  expect(view.getByRole('heading', { name:'퇴근 누락 정정 신청' })).toBeTruthy();
  expect(view.getByText('2026-11-04 09:00:00')).toBeTruthy();
  expect(view.queryByLabelText('출근 변경')).toBeNull();
  expect(view.queryByLabelText('퇴근 변경')).toBeNull();
  expect(view.getByRole('link', { name:'일반 정정 양식' }).getAttribute('href')).toBe('/me/requests/correction?date=2026-11-04&mode=full');
  const out = view.getByLabelText('실제 퇴근 시각') as HTMLInputElement;
  const reason = view.getByLabelText('정정 사유와 사실 근거');
  const presence = view.getByLabelText('제안 구간의 실제 근로를 확인했습니다');
  expect(out.required).toBe(true);
  expect(reason.compareDocumentPosition(presence) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  fireEvent.change(out, { target:{ value:'2026-11-04T18:00:00' } });
  fireEvent.click(presence);
  fireEvent.click(view.getByLabelText('실제 휴게를 확인했습니다 (없으면 아래 비움)'));
  fireEvent.change(reason, { target:{ value:'퇴근 기록 누락' } });
  const form = view.container.querySelector('form')!;
  const data = new FormData(form);
  expect(form.getAttribute('action')).toBe('/me/requests/correction?mode=checkout');
  expect(data.get('inAction')).toBe('KEEP');
  expect(data.get('outAction')).toBe('SET');
  expect(data.get('requestedOutAt')).toMatch(/^2026-11-04T18:00(?::00(?:\.000)?)?$/);
  expect(data.get('presenceAsWork')).toBe('true');
  expect(data.get('breaksConfirmed')).toBe('true');
  expect(data.get('reason')).toBe('퇴근 기록 누락');
});

it('같은 날 대기 중인 정정이 있으면 신청을 열 수 있고 새 제출 양식이 나오지 않는다', () => {
  const view = render(<WorkflowPages page="request/correction" csrf={csrf} reload={() => {}}
    data={{...missingOut, pendingCorrectionId:'pending-1'}} />);
  expect(view.getByRole('link', { name:'대기 중인 정정 보기' }).getAttribute('href')).toBe('/requests/pending-1');
  expect(view.container.querySelector('form')).toBeNull();
});

it('결재자는 정정 상세에서 원천, 유효, 제안 시각과 실제 근로·휴게 확인을 비교한다', () => {
  const view = render(<WorkflowPages page="request/detail" csrf={csrf} reload={() => {}} data={{
    request:{ id:'request-1', kind:'ATTENDANCE_CORRECTION', status:'PENDING', effectStatus:'NONE',
      targetEmployeeName:'합성 직원', approverName:'합성 결재자', fromDate:'2026-11-04', toDate:'2026-11-04', reason:'퇴근 기록 누락' },
    detail:{ days:[], history:[], correction:{ workDate:'2026-11-04', inAction:'KEEP', outAction:'SET',
      requestedOutAt:'2026-11-04T09:00:00Z', presenceAsWork:true, breaksConfirmed:true,
      actualBreaks:'[{"startAt":"2026-11-04T03:00:00Z","endAt":"2026-11-04T04:00:00Z"}]' },
      correctionComparison:{ source:{ in:['2026-11-04T00:00:00Z'], out:[] },
        current:{ in:['2026-11-04T00:00:00Z'], out:['2026-11-04T08:30:00Z'] },
        proposed:{ in:['2026-11-04T00:00:00Z'], out:['2026-11-04T09:00:00Z'] } } },
    canDecide:true, pageCommand:'decision',
  }} />);
  const table = within(view.getByRole('table', { name:'출퇴근 시각 비교' }));
  expect(table.getByRole('row', { name:/원천 기록.*09:00:00.*미기록/ })).toBeTruthy();
  expect(table.getByRole('row', { name:/현재 유효 기록.*09:00:00.*17:30:00/ })).toBeTruthy();
  expect(table.getByRole('row', { name:/제안 기록.*09:00:00.*18:00:00/ })).toBeTruthy();
  expect(view.getByText('실제 근로 확인')).toBeTruthy();
  expect(view.getByText(/12:00:00.*13:00:00/)).toBeTruthy();
  expect(view.getByRole('form', { name:'승인 검토' })).toBeTruthy();
});

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
