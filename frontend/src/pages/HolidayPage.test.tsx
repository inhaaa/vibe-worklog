// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { HolidayPage } from './HolidayPage';
import type { HolidayModel } from './HolidayPage';

const csrf = { token: 't', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const JSON_HEADERS = { 'Content-Type': 'application/json' };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
const base: HolidayModel = {
  year: 2027, version: 0, applied: null, closedMonths: [], builtIn: true,
  rows: [{ id: 'h1', date: '2027-03-02', name: '창립기념일', category: 'COMPANY', basis: null }],
};

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('HolidayPage', () => {
  it('moves between years and shows whether the year reached attendance', () => {
    const { container } = render(<HolidayPage model={base} csrf={csrf} />);
    expect(screen.getByText('아직 근태 미반영')).toBeTruthy();
    expect(screen.getByRole('link', { name: '이전 연도' }).getAttribute('href')).toBe('/admin/holidays?year=2026');
    expect(screen.getByRole('link', { name: '다음 연도' }).getAttribute('href')).toBe('/admin/holidays?year=2028');
    expect(container.querySelector('.wl-count')?.textContent).toBe('총 1건');
    cleanup();
    render(<HolidayPage model={{ ...base, version: 1, applied: { at: '2026-11-24T01:05:00Z', by: '합성 담당자' } }} csrf={csrf} />);
    expect(screen.getByText('근태 반영됨 · 마지막 반영 11-24 10:05 · 합성 담당자')).toBeTruthy();
  });

  it('adds only built-in dates that the sheet does not have yet', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ year: 2027, notice: '상시 4인 이하 사업장은 노동절 외 공휴일의 유급휴일 의무가 없습니다.', holidays: [
      { date: '2027-01-01', name: '1월 1일', basis: '공휴일 · 월력요항' }, { date: '2027-03-02', name: '중복', basis: '공휴일' }] }));
    const { container } = render(<HolidayPage model={base} csrf={csrf} />);
    fireEvent.click(screen.getByRole('button', { name: '2027년 공휴일 불러오기' }));
    await waitFor(() => expect(container.querySelector('.wl-count')?.textContent).toBe('총 2건'));
    expect(fetchMock).toHaveBeenCalledWith('/admin/holidays/public-preview', expect.objectContaining({ body: JSON.stringify({ year: 2027 }) }));
    expect(screen.getByText(/1일을 불러왔습니다/).textContent).toContain('상시 4인 이하');
    expect(container.querySelector('[data-unsaved-changes]')?.getAttribute('data-unsaved-changes')).toBe('true');
  });

  it('tells the manager to add dates when the year is not built in', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ year: 2030, notice: '', holidays: [] }));
    render(<HolidayPage model={{ ...base, year: 2030, rows: [], builtIn: false }} csrf={csrf} />);
    fireEvent.click(screen.getByRole('button', { name: '2030년 공휴일 불러오기' }));
    expect(await screen.findByText('2030년 공휴일은 아직 내장되지 않았습니다. 날짜를 직접 추가하세요.')).toBeTruthy();
  });

  it('lists every rejected row and saves again with a new command', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json({ message: '행별 오류를 고친 뒤 다시 저장해 주세요.', errors: [
        { clientRowId: 'h1', field: 'name', message: '휴일명을 입력해 주세요.' },
        { clientRowId: null, field: 'date', message: '2027-01-04: 2027-01 근태가 마감돼 바꿀 수 없습니다.' }] }, 422))
      .mockResolvedValueOnce(json({ year: 2027, version: 1, added: 1, updated: 0, deleted: 0, changed: ['2027-03-02'], applied: Array(365).fill('2027-01-01') }));
    render(<HolidayPage model={base} csrf={csrf} />);
    fireEvent.change(screen.getByLabelText('저장 사유'), { target: { value: '2027년 휴일 정리' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(await screen.findByText('2027-03-02: 휴일명을 입력해 주세요.')).toBeTruthy();
    expect(screen.getByText('2027-01-04: 2027-01 근태가 마감돼 바꿀 수 없습니다.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(await screen.findByText('저장했습니다. 추가 1 · 수정 0 · 삭제 0 · 근태 반영 365일')).toBeTruthy();
    const bodies = fetchMock.mock.calls.map(call => JSON.parse(String((call[1] as RequestInit).body)));
    expect(bodies[0].commandId).not.toBe(bodies[1].commandId);
    expect(bodies[0]).toMatchObject({ year: 2027, expectedVersion: 0, reason: '2027년 휴일 정리',
      rows: [{ clientRowId: 'h1', date: '2027-03-02', name: '창립기념일', category: 'COMPANY', basis: '' }] });
  });
});
