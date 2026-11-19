// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { WorkTimeCodesTab } from './WorkTimeCodesTab';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
it('선택근무 코드는 선택 가능 시간, 코어, 일 기준과 정산 기간을 저장하고 고정 연장 기준을 제외한다', async () => {
  const send = vi.fn().mockResolvedValueOnce(Response.json({ codes: [] }))
    .mockResolvedValueOnce(Response.json({ id: 'sel1' })).mockResolvedValueOnce(Response.json({ codes: [] }));
  vi.stubGlobal('fetch', send);
  render(<WorkTimeCodesTab date="2026-12-01" csrf={{ headerName: 'X-CSRF-TOKEN', token: 'token' }} />);
  await screen.findByText('등록된 근무시간코드가 없습니다.');
  fireEvent.click(screen.getByRole('button', { name: '코드 등록' }));
  fireEvent.click(screen.getByLabelText('퇴근 후 연장 허용'));
  fireEvent.change(screen.getByLabelText('근무 방식'), { target: { value: 'SELECTIVE' } });
  expect(screen.queryByLabelText('퇴근 후 연장 허용')).toBeNull();
  fireEvent.change(screen.getByLabelText('코드', { exact: true }), { target: { value: 'SEL1' } });
  fireEvent.change(screen.getByLabelText('코드명'), { target: { value: '선택 근무조' } });
  fireEvent.change(screen.getByLabelText('선택 가능 시작'), { target: { value: '07:00' } });
  fireEvent.change(screen.getByLabelText('선택 가능 종료'), { target: { value: '22:00' } });
  expect(screen.getByLabelText('표준 1일 근로시간(분)').getAttribute('max')).toBe('840');
  fireEvent.click(screen.getByLabelText('코어시간 사용'));
  expect(screen.getByLabelText('코어 시작').getAttribute('min')).toBe('07:00');
  expect(screen.getByLabelText('코어 종료').getAttribute('max')).toBe('22:00');
  fireEvent.change(screen.getByLabelText('코어 시작'), { target: { value: '10:00' } });
  fireEvent.change(screen.getByLabelText('코어 종료'), { target: { value: '15:00' } });
  fireEvent.change(screen.getByLabelText('표준 1일 근로시간(분)'), { target: { value: '450' } });
  fireEvent.change(screen.getByLabelText('정산 기간'), { target: { value: 'ISO_WEEK' } });
  fireEvent.change(screen.getByLabelText('변경 사유'), { target: { value: '주 단위 선택근무' } });
  fireEvent.click(screen.getByRole('button', { name: '저장' }));
  await screen.findByText('근무시간코드를 저장했습니다.');
  expect(JSON.parse(send.mock.calls[1][1].body)).toMatchObject({
    workMode: 'SELECTIVE', startTime: '07:00', endTime: '22:00', coreStart: '10:00', coreEnd: '15:00',
    standardMinutes: 450, settlementPeriod: 'ISO_WEEK', beforeStart: null, afterStart: null,
    breaks: [{ kind: 'REGULAR', start: '12:00', end: '13:00' }],
  });
});

it('코어시간 없는 기존 선택근무의 상세와 예약 개정에서 적용 기준을 보존한다', async () => {
  const version = { id: 'v1', name: '선택 근무조', revision: 1, effectiveFrom: '2026-12-01', enabled: true,
    startTime: '07:00', endTime: '22:00', workMode: 'SELECTIVE', coreStart: null, coreEnd: null,
    standardMinutes: 450, settlementPeriod: 'CALENDAR_MONTH', breaks: [], groups: [], scheduledMinutes: 450, overtimeMinutes: 0 };
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ codes: [{ id: 'sel1', code: 'SEL1', versions: [version] }] })));
  render(<WorkTimeCodesTab date="2026-12-01" csrf={{ headerName: 'X-CSRF-TOKEN', token: 'token' }} />);
  fireEvent.click(await screen.findByRole('button', { name: /SEL1 선택 근무조/ }));
  expect(screen.getByText(/코어시간 없음/)).toBeTruthy();
  expect(screen.queryByText(/최대 신청 가능 시간/)).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'v1 적용 예약 정정' }));
  expect((screen.getByLabelText('코어시간 사용') as HTMLInputElement).checked).toBe(false);
  expect((screen.getByLabelText('표준 1일 근로시간(분)') as HTMLInputElement).value).toBe('450');
  expect((screen.getByLabelText('정산 기간') as HTMLSelectElement).value).toBe('CALENDAR_MONTH');
});

it('등록 전에 소정시간과 연장 예정 휴게를 차감한 최대 신청시간을 표시한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({date:'2026-12-01',codes:[]}))));
  render(<WorkTimeCodesTab date="2026-12-01" csrf={{headerName:'X-CSRF-TOKEN',token:'token'}}/>);
  await screen.findByText('등록된 근무시간코드가 없습니다.');
  fireEvent.click(await screen.findByRole('button',{name:'코드 등록'}));
  expect(screen.getByText('소정시간 8시간')).toBeTruthy();
  fireEvent.click(screen.getByLabelText('퇴근 후 연장 허용'));
  fireEvent.change(screen.getByLabelText('퇴근 후 허용 시작'),{target:{value:'18:30'}});
  fireEvent.change(screen.getByLabelText('퇴근 후 허용 종료'),{target:{value:'21:00'}});
  fireEvent.click(screen.getByRole('button',{name:'퇴근 후 연장 휴게 추가'}));
  fireEvent.change(screen.getByLabelText('퇴근 후 연장 휴게 1 시작'),{target:{value:'20:00'}});
  fireEvent.change(screen.getByLabelText('퇴근 후 연장 휴게 1 종료'),{target:{value:'20:15'}});
  expect(screen.getByText('최대 신청 가능 시간 2시간 15분')).toBeTruthy();
});

it('저장 응답 유실 후 입력과 같은 명령을 보존하고 다시 확인한다', async () => {
  const send=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({codes:[]})))
    .mockRejectedValueOnce(new TypeError('Failed to fetch'))
    .mockResolvedValueOnce(new Response(JSON.stringify({id:'c1'})))
    .mockResolvedValueOnce(new Response(JSON.stringify({codes:[]})));
  vi.stubGlobal('fetch',send);
  render(<WorkTimeCodesTab date="2026-12-01" csrf={{headerName:'X-CSRF-TOKEN',token:'token'}}/>);
  await screen.findByText('등록된 근무시간코드가 없습니다.');
  fireEvent.click(screen.getByRole('button',{name:'코드 등록'}));
  fireEvent.change(screen.getByLabelText('코드',{exact:true}),{target:{value:'001'}});
  fireEvent.change(screen.getByLabelText('코드명'),{target:{value:'주간'}});
  fireEvent.change(screen.getByLabelText('변경 사유'),{target:{value:'운영 기준 등록'}});
  fireEvent.click(screen.getByRole('button',{name:'저장'}));
  expect((await screen.findByRole('alert')).textContent).toContain('같은 명령');
  expect((screen.getByLabelText('코드명') as HTMLInputElement).value).toBe('주간');
  fireEvent.click(screen.getByRole('button',{name:'저장 결과 다시 확인'}));
  await screen.findByText('근무시간코드를 저장했습니다.');
  expect(send.mock.calls[1][1].body).toBe(send.mock.calls[2][1].body);
  expect(send.mock.calls[1][1].headers['X-CSRF-TOKEN']).toBe('token');
});

it('예약 정정은 선택한 버전의 시각을 열고 검증 실패 시 이름 검색과 입력을 유지한다', async () => {
  const v1={id:'v1',name:'주간',revision:1,effectiveFrom:'2026-12-01',enabled:true,startTime:'09:00',endTime:'18:00',
    beforeStart:null,beforeEnd:null,afterStart:null,afterEnd:null,breaks:[],groups:[],reason:'등록',scheduledMinutes:540,overtimeMinutes:0};
  const v2={...v1,id:'v2',name:'늦은 주간',revision:2,effectiveFrom:'2026-12-07',startTime:'10:00',endTime:'19:00'};
  const send=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({codes:[{id:'c',code:'001',version:1,versions:[v2,v1]}]})))
    .mockResolvedValueOnce(new Response(JSON.stringify({message:'예정 휴게 시간이 겹칩니다.'}),{status:422}));
  vi.stubGlobal('fetch',send);
  render(<WorkTimeCodesTab date="2026-12-01" csrf={{headerName:'X-CSRF-TOKEN',token:'token'}}/>);
  const select=await screen.findByRole('button',{name:/001 주간/});
  fireEvent.change(screen.getByLabelText('코드 검색'),{target:{value:'늦은'}});
  fireEvent.click(select);
  fireEvent.click(screen.getByRole('button',{name:'v2 적용 예약 정정'}));
  expect((screen.getByLabelText('정규 출근') as HTMLInputElement).value).toBe('10:00');
  expect((screen.getByLabelText('사용 시작일') as HTMLInputElement).value).toBe('2026-12-07');
  fireEvent.change(screen.getByLabelText('변경 사유'),{target:{value:'예약 확인'}});
  fireEvent.click(screen.getByLabelText('2026-12-07 적용 예약 v2 정정'));
  fireEvent.click(screen.getByRole('button',{name:'저장'}));
  expect((await screen.findByRole('alert')).textContent).toContain('예정 휴게 시간이 겹칩니다.');
  expect((screen.getByLabelText('코드 검색') as HTMLInputElement).value).toBe('늦은');
  expect((screen.getByLabelText('변경 사유') as HTMLInputElement).value).toBe('예약 확인');
  expect(JSON.parse(send.mock.calls[1][1].body).replaceVersionId).toBe('v2');
});
