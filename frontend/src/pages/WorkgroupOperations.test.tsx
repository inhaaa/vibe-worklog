// @vitest-environment jsdom
import { cleanup, fireEvent, render, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { GroupEditor, MemberAssignment } from './WorkgroupOperations';

const date = '2026-12-01';
const csrf = { headerName: 'X-CSRF-TOKEN', token: 'token', parameterName: '_csrf' };
const employee = { id: 'e1', number: '25001', name: '김가상', organization: '운영팀', currentGroup: '기존 고정배정', version: 4 };
const version = { id: 'v1', revision: 1, effectiveFrom: date, enabled: true, name: '주간',
  startTime: '09:00', endTime: '18:00', breaks: [{ kind: 'REGULAR', start: '12:00', end: '13:00' }], scheduledMinutes: 480, overtimeMinutes: 0 };
const day = { weekday: 1, codeVersionId: 'v1', code: 'W01', startTime: '09:00', endTime: '18:00',
  breaks: [{ startTime: '12:00', endTime: '13:00' }], minutes: 480 };
const days = Array.from({ length: 7 }, (_, i) => ({ ...day, weekday: i + 1 }));
const group = { id: 'g1', code: 'G01', name: '주간조', version: 1, validFrom: date, revision: 1, revisionId: 'r1', days, revisions: [{ id: 'r1', revision: 1, validFrom: date, reason: '초기' }] };
const row = { employeeId: 'e1', number: '25001', name: '김가상', currentGroup: '기존 고정배정', newGroup: '주간조',
  lastDay: '2026-11-30', firstDay: date, nextStart: '2027-01-01', assignmentId: null, conflict: null };
const props = { date, today: '2026-11-18', csrf, onSaved: vi.fn(), onBusy: vi.fn() };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.clearAllMocks(); });

it('요일별 시간코드를 편성하고 주간 소정시간과 영향 명단을 확인한 개정만 저장한다', async () => {
  const writes: Record<string, unknown>[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (url.includes('/codes?')) return json({ date, codes: [{ id: 'c1', code: 'W01', version: 1, versions: [version] }] });
    writes.push(JSON.parse(String(init?.body)));
    if (url.endsWith('/preview')) return json({ token: 'review-1', beforeDays: [], days: [day], weeklyMinutes: 480,
      affected: [employee], departing: [], incoming: [], conflicts: [] });
    return json({ id: 'new-group' });
  }));
  const view = render(<GroupEditor {...props} group={undefined} />);
  fireEvent.change(view.getByLabelText('근무조 코드'), { target: { value: 'G09' } });
  fireEvent.change(view.getByLabelText('근무조명'), { target: { value: '지원조' } });
  await view.findAllByRole('option', { name: /W01/ });
  fireEvent.change(view.getByLabelText('월요일 근무시간코드'), { target: { value: 'v1' } });
  fireEvent.change(view.getByLabelText('변경 사유'), { target: { value: '근무조 신설' } });
  expect(view.queryByRole('button', { name: '개정 저장' })).toBeNull();
  fireEvent.click(view.getByRole('button', { name: '개정 미리보기' }));
  const preview = await view.findByRole('region', { name: '근무조 개정 미리보기' });
  expect(preview.textContent).toContain('주간 소정 8시간');
  expect(preview.textContent).toContain('2026-11-30');
  expect(preview.textContent).toContain('김가상');
  fireEvent.change(view.getByLabelText('변경 사유'), { target: { value: '운영 시간 변경' } });
  expect(view.queryByRole('button', { name: '개정 저장' })).toBeNull();
  fireEvent.click(view.getByRole('button', { name: '개정 미리보기' }));
  fireEvent.click(await view.findByRole('button', { name: '개정 저장' }));
  await waitFor(() => expect(props.onSaved).toHaveBeenCalledWith('new-group'));
  expect(writes.at(-1)).toMatchObject({ token: 'review-1', code: 'G09', days: Array.from({ length: 7 }, (_, i) => ({ weekday: i + 1, codeVersionId: i ? null : 'v1' })) });
});

it('배정 검색·기존 고정배정 전환을 확인하고 저장 결과 통신 실패 시 같은 명령으로 다시 확인한다', async () => {
  const commands: Record<string, unknown>[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (url.includes('/employees?')) return json(new URL(url, 'http://localhost').searchParams.get('q') === '운영' ? [employee] : []);
    if (url.endsWith('/preview')) return json({ token: 'review-2', rows: [row], conflicts: [] });
    commands.push(JSON.parse(String(init?.body)));
    if (commands.length === 1) throw new TypeError('network');
    return json({ id: 'g1' });
  }));
  const view = render(<MemberAssignment {...props} group={group} initialQuery="" />);
  fireEvent.change(view.getByLabelText('사원 검색'), { target: { value: '운영' } });
  fireEvent.click(view.getByRole('button', { name: '조회' }));
  fireEvent.click(await view.findByRole('checkbox', { name: /25001 김가상/ }));
  fireEvent.change(view.getByLabelText('배정 사유'), { target: { value: '근무조 전환' } });
  fireEvent.click(view.getByRole('button', { name: '배정 미리보기' }));
  const preview = await view.findByRole('region', { name: '사원 배정 미리보기' });
  for (const value of ['기존 고정배정', date, '2027-01-01']) expect(preview.textContent).toContain(value);
  fireEvent.click(within(preview).getByRole('button', { name: '배정 저장' }));
  expect((await view.findByRole('alert')).textContent).toContain('저장 결과');
  fireEvent.click(view.getByRole('button', { name: '같은 명령으로 저장 확인' }));
  await waitFor(() => expect(props.onSaved).toHaveBeenCalledWith('g1'));
  expect(commands[1]).toEqual(commands[0]);
  expect(commands[0]).toMatchObject({ employeeIds: ['e1'], groupId: 'g1', validFrom: date, token: 'review-2' });
});

it('예약 충돌을 표시하고 해당 예약을 명시적으로 선택해 다시 미리본다', async () => {
  const requests: Record<string, unknown>[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (url.includes('/employees?')) return json([employee]);
    const body = JSON.parse(String(init?.body)); requests.push(body);
    return body.replaceAssignmentIds?.includes('a1')
      ? json({ token: 'ok', rows: [{ ...row, assignmentId: 'a1' }], conflicts: [] })
      : json({ token: '', rows: [{ ...row, assignmentId: 'a1', conflict: '같은 날 예약이 있습니다.' }], conflicts: ['예약 충돌'] });
  }));
  const view = render(<MemberAssignment {...props} group={group} initialQuery="" />);
  fireEvent.click(await view.findByRole('checkbox', { name: /25001 김가상/ }));
  fireEvent.change(view.getByLabelText('배정 사유'), { target: { value: '예약 정정' } });
  fireEvent.click(view.getByRole('button', { name: '배정 미리보기' }));
  expect((await view.findByRole('region', { name: '사원 배정 미리보기' })).textContent).toContain('예약 충돌');
  expect(view.queryByRole('button', { name: '배정 저장' })).toBeNull();
  fireEvent.click(view.getByRole('checkbox', { name: /김가상 예약 교체/ }));
  fireEvent.click(view.getByRole('button', { name: '배정 미리보기' }));
  await view.findByRole('button', { name: '배정 저장' });
  expect(requests.at(-1)).toMatchObject({ replaceAssignmentIds: ['a1'] });
});

it('미리보기 후 다른 담당자가 개정하면 저장을 중단하고 최신 근무조를 조회한다', async () => {
  const reload = vi.fn();
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url.includes('/codes?')) return json({ date, codes: [{ id: 'c1', code: 'W01', version: 1, versions: [version] }] });
    if (url.endsWith('/preview')) return json({ token: 'old', beforeDays: days, days, weeklyMinutes: 3360,
      affected: [], departing: [], incoming: [], conflicts: [] });
    return json({ message: '근무 기준이 달라졌습니다.' }, 409);
  }));
  const view = render(<GroupEditor {...props} group={group} onRefresh={reload} />);
  await view.findAllByRole('option', { name: /W01/ });
  fireEvent.change(view.getByLabelText('변경 사유'), { target: { value: '근무 기준 정정' } });
  fireEvent.click(view.getByLabelText(/1차 개정 교체/));
  fireEvent.click(view.getByRole('button', { name: '개정 미리보기' }));
  fireEvent.click(await view.findByRole('button', { name: '개정 저장' }));
  await view.findByText('근무 기준이 달라졌습니다.');
  expect(view.queryByRole('button', { name: '개정 저장' })).toBeNull();
  fireEvent.click(view.getByRole('button', { name: '입력을 초기화하고 최신 근무조 조회' }));
  expect(reload).toHaveBeenCalledOnce();
});
