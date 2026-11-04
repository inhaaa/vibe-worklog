// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import type { PageEnvelope } from './types';
import type { AnnualModel, ComposeView } from './pages/AnnualRequestTypes';

const csrf = { token:'request-csrf', parameterName:'_csrf', headerName:'X-CSRF-TOKEN' };
const shared = { workspacePrincipal:{roles:['EMPLOYEE']}, workspaceEmployee:{employeeName:'합성 직원',companyName:'합성 회사'} };
const approval = { source:'ORGANIZATION', routeName:'조직장 결재선', approverName:'합성 조직장', eligible:true, message:'결재 가능' };
const leave = { bucketId:'bucket-1', from:'2026-11-05', to:'2026-11-06', reason:'', commandId:'leave-command', predecessorRequestId:null };
const correction = { targetEmployeeId:null, workDate:'2026-11-04', baseInputRevision:3, sourceFingerprint:'a'.repeat(64), inAction:'KEEP', requestedInAt:'', outAction:'KEEP', requestedOutAt:'', presenceAsWork:false, breaksConfirmed:false, actualBreaks:'', replacesRequestId:null, predecessorRequestId:null, reason:'', commandId:'correction-command' };
const compose: ComposeView = { mode:'MODAL', kind:'LEAVE_DAY', leaveForm:leave, correctionForm:correction, day:{employeeId:'employee-1',workDate:correction.workDate,inputRevision:3,reviewRevision:0,sourceFingerprint:correction.sourceFingerprint,effectiveEvents:[],events:[],schedule:null,scheduleVersionId:null,calendarVersionId:null,dayKind:'UNKNOWN',confirmation:null}, preview:null, approval, conflict:false };
const annual: AnnualModel['annual'] = { year:2026, today:'2026-11-04', asOf:'2026-11-04T01:00:00Z', typeId:null, status:'', page:0, hasNext:false, authoredPage:0, authoredHasNext:false, coverage:'REGISTERED_DATA_ONLY', usedDays:3, plannedDays:2, pendingDays:2, currentAvailableDays:9, registeredDays:null, attributedBalanceDays:null, unknownBucketCount:0, attributionPartial:false, types:[], summaries:[], requests:[], canResubmitRequestIds:[], authoredRequests:[], attributionBuckets:[], createdId:null, outsideYear:null };
function envelope(current: ComposeView | null = null, changes: Partial<AnnualModel['annual']> = {}, error?: string): PageEnvelope {
  return { page:'request/mine', csrf, data:{ ...shared, annual:{...annual,...changes}, compose:current, error, buckets:[{id:'bucket-1',usableFrom:'2026-01-01',usableTo:'2027-12-31',balanceDays:9,availableDays:9,pendingDays:2}] } };
}
function preview() {
  return envelope({ ...compose, leaveForm:{...leave,reason:'합성 휴가'}, preview:{quantityDays:2,availableDays:9,selectedBucketAvailableDays:9,policy:{code:'ANNUAL_LEAVE',name:'연차',revision:1},approval,eligible:true,message:'미리보기 완료'} });
}
function response(data: PageEnvelope, path: string, status = 200) {
  const result = new Response(JSON.stringify(data), { status, headers:{'Content-Type':'application/vnd.worklog.page+json'} });
  Object.defineProperty(result, 'url', { value:new URL(path, location.href).href });
  return result;
}
beforeEach(() => {
  history.replaceState(null, '', '/me/requests?year=2026');
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable:true, value:function(this:HTMLDialogElement) { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable:true, value:function(this:HTMLDialogElement) { this.open = false; } });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
async function open(fetcher: ReturnType<typeof vi.fn<typeof fetch>>) {
  vi.stubGlobal('fetch', fetcher);
  render(<App initial={envelope()} />);
  const search = screen.getByRole('searchbox',{name:'현재 페이지 신청 검색'}) as HTMLInputElement;
  fireEvent.change(search,{target:{value:'검색 유지'}});
  const trigger = screen.getByRole('button',{name:'신청'});
  trigger.focus(); fireEvent.click(trigger);
  return { dialog:await screen.findByRole('dialog',{name:'근태 신청'}), search, trigger };
}
function write(dialog: HTMLElement) {
  fireEvent.click(within(dialog).getByRole('button',{name:'내용 작성으로'}));
  fireEvent.change(within(dialog).getByLabelText('신청 사유'),{target:{value:'합성 휴가'}});
  fireEvent.click(within(dialog).getByRole('button',{name:'미리보기'}));
}

describe('신청 공통 모달 통합', () => {
  it('작성창을 하나만 열고 X로 닫으면 배경의 검색·URL·초점을 보존한다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response(envelope(compose),'/me/requests/new?year=2026'));
    const { dialog, search, trigger } = await open(fetcher);
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    expect(within(dialog).queryByRole('heading',{name:'내 근태현황·신청'})).toBeNull();
    expect(document.contains(search)).toBe(true);
    expect(search.value).toBe('검색 유지');
    fireEvent.click(dialog.querySelector<HTMLButtonElement>('.t-modal-close')!);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(location.pathname+location.search).toBe('/me/requests?year=2026'));
    expect(screen.getByRole('searchbox',{name:'현재 페이지 신청 검색'})).toBe(search);
    expect(search.value).toBe('검색 유지');
    expect(document.activeElement).toBe(trigger);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('자체 미리보기 요청 중 공통 X·닫기·Esc를 잠그고 응답 뒤 해제한다', async () => {
    let resolve!: (value:Response) => void;
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response(envelope(compose),'/me/requests/new?year=2026'))
      .mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    const confirm = vi.spyOn(window,'confirm').mockReturnValue(true);
    const { dialog } = await open(fetcher);
    write(dialog);
    const close = within(dialog).getAllByRole('button',{name:'닫기'}) as HTMLButtonElement[];
    await waitFor(() => expect(close.every(button => button.disabled)).toBe(true));
    fireEvent.click(close[0]); fireEvent(dialog,new Event('cancel',{cancelable:true}));
    expect(screen.getByRole('dialog')).toBe(dialog);
    expect(confirm).not.toHaveBeenCalled();
    await act(async () => resolve(response(preview(),'/me/requests/preview')));
    await waitFor(() => expect(close.every(button => !button.disabled)).toBe(true));
    expect(within(dialog).getByRole('button',{name:'신청 제출'})).toBeTruthy();
    fireEvent.click(close[0]);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(confirm).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(location.pathname).toBe('/me/requests'));
  });

  it('최종 제출 뒤 배경을 다시 읽고 접수 안내와 생성 상세 링크를 유지한다', async () => {
    const submitted = { id:'created-request', targetEmployeeName:'합성 직원', kind:'LEAVE_DAY', fromDate:leave.from, toDate:leave.to, quantityDays:2, status:'PENDING', effectStatus:'NONE', version:0, reason:'합성 휴가', approverName:'합성 조직장', originalRequestId:null };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response(envelope(compose),'/me/requests/new?year=2026'))
      .mockResolvedValueOnce(response(preview(),'/me/requests/preview'))
      .mockResolvedValueOnce(response(envelope(null,{createdId:submitted.id}),'/me/requests?year=2026&created='+submitted.id))
      .mockResolvedValueOnce(response(envelope(null,{requests:[submitted],pendingDays:4}),'/me/requests?year=2026'));
    const { dialog } = await open(fetcher);
    write(dialog);
    const submit = await within(dialog).findByRole('button',{name:'신청 제출'});
    await waitFor(() => expect((submit as HTMLButtonElement).disabled).toBe(false));
    fireEvent.submit(submit.closest('form')!);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const receipt = within(await screen.findByRole('status',{name:'신청 접수 결과'}));
    expect(receipt.getByText('신청을 접수했습니다.')).toBeTruthy();
    expect(receipt.getByRole('link',{name:'생성한 신청 상세'}).getAttribute('href')).toBe('/requests/created-request');
    expect(within(screen.getByRole('region',{name:'내 대상 신청 이력'})).getByText('합성 휴가')).toBeTruthy();
    expect(screen.getByText('4 DAY')).toBeTruthy();
    expect(fetcher).toHaveBeenCalledTimes(4);
    expect(String(fetcher.mock.calls[2][0])).toContain('/me/requests/leave');
    expect(new URLSearchParams(fetcher.mock.calls[2][1]?.body as URLSearchParams).get('_csrf')).toBe(csrf.token);
    expect(String(fetcher.mock.calls[3][0])).toContain('/me/requests?year=2026');
    await waitFor(() => expect(location.pathname+location.search).toBe('/me/requests?year=2026'));
  });

  it('유형·날짜 조회의 검증 오류는 배경을 바꾸지 않고 같은 모달 안에 표시한다', async () => {
    const next = { ...compose, kind:'ATTENDANCE_CORRECTION' as const };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response(envelope(compose),'/me/requests/new?year=2026'))
      .mockResolvedValueOnce(response(envelope(next,{},'근무일 자료를 확인해 주세요.'),'/me/requests/new?year=2026&mode=MODAL&kind=ATTENDANCE_CORRECTION&date=2026-11-04',422));
    const { dialog, search } = await open(fetcher);
    fireEvent.click(within(dialog).getByRole('radio',{name:/출퇴근·휴게 정정/}));
    fireEvent.click(within(dialog).getByRole('button',{name:'내용 작성으로'}));
    const error = await within(dialog).findByRole('alert');
    expect(error.textContent).toBe('근무일 자료를 확인해 주세요.');
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    expect(within(screen.getByRole('main')).queryByRole('alert')).toBeNull();
    expect(document.contains(search)).toBe(true);
    expect(search.value).toBe('검색 유지');
    expect(document.activeElement).toBe(error);
    expect(new URL(String(fetcher.mock.calls[1][0])).searchParams.get('mode')).toBe('MODAL');
  });
});
