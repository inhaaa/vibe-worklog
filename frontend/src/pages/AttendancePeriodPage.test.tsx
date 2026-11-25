// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { WorkflowPages } from './WorkflowPages';

afterEach(cleanup);

const csrf = { token: 'csrf', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };

it('선택근무의 기간 목록에서 선택 가능 시간과 실제 기록을 구분한다', () => {
  render(<WorkflowPages page="clock/period" csrf={csrf} reload={() => {}} data={{ attendancePeriod: {
    today: '2026-11-17', from: '2026-11-01', to: '2026-11-17', missingOnly: false,
    rows: [{ workDate: '2026-11-16', scheduleKind: 'WORKDAY', workMode: 'SELECTIVE', startTime: '07:00', endTime: '22:00',
      inAt: null, outAt: null, recordStatus: 'NONE', workStatus: 'ABSENT', actualWorkMillis: null }],
  } }} />);
  expect(screen.getByRole('cell', { name: '선택 가능 07:00 ~ 22:00' })).toBeTruthy();
});

it('보이는 기간과 누락 조건을 URL에 유지하고 날짜 상세와 정정을 연다', () => {
  render(<WorkflowPages page="clock/period" csrf={csrf} reload={() => {}} data={{
    attendancePeriod: {
      today: '2026-11-17', from: '2026-10-01', to: '2026-10-31', missingOnly: true,
      rows: [{ workDate: '2026-10-26', scheduleKind: 'WORKDAY', startTime: '09:00:00', endTime: '18:00:00',
        inAt: '2026-10-26T00:00:00.543Z', outAt: null, recordStatus: 'MISSING', corrected: false,
        pendingCorrection: false, workStatus: 'PENDING', actualWorkMillis: null, correctionSuggested: true }],
    },
  }} />);

  expect(screen.getByRole('link', { name: '이번 달' }).getAttribute('href'))
    .toBe('/me/attendance?view=period&from=2026-11-01&to=2026-11-17&missingOnly=true');
  expect(screen.getByRole('link', { name: '지난달' }).getAttribute('href'))
    .toBe('/me/attendance?view=period&from=2026-10-01&to=2026-10-31&missingOnly=true');
  expect((screen.getByLabelText('시작일') as HTMLInputElement).value).toBe('2026-10-01');
  expect((screen.getByLabelText('종료일') as HTMLInputElement).value).toBe('2026-10-31');
  expect((screen.getByRole('checkbox', { name: '출퇴근 누락만' }) as HTMLInputElement).checked).toBe(true);
  expect(screen.getByRole('link', { name: '2026-10-26' }).getAttribute('href')).toBe('/me/attendance?date=2026-10-26');
  expect(screen.getByRole('link', { name: /정정 신청/ }).getAttribute('href')).toBe('/me/requests/correction?date=2026-10-26');
  expect(screen.getByText('09:00:00')).toBeTruthy();
  expect(screen.getByText('확인 전')).toBeTruthy();
  expect(screen.queryByRole('button', { name: '출근 기록' })).toBeNull();
});

it('빈 누락 결과를 알리고 오늘 상세를 여는 링크를 제공한다', () => {
  render(<WorkflowPages page="clock/period" csrf={csrf} reload={() => {}} data={{
    attendancePeriod: { today: '2026-11-17', from: '2026-08-18', to: '2026-11-17', missingOnly: true, rows: [] },
  }} />);
  expect(screen.getByText('선택한 기간에 출퇴근 누락이 없습니다.')).toBeTruthy();
  expect(screen.getByRole('link', { name: '오늘 기록' }).getAttribute('href')).toBe('/me/attendance');
});

it('이번 달과 지난달은 조회 버튼을 누르기 전 바꾼 누락 조건을 사용한다', () => {
  render(<WorkflowPages page="clock/period" csrf={csrf} reload={() => {}} data={{
    attendancePeriod: { today: '2026-11-17', from: '2026-08-18', to: '2026-11-17', missingOnly: false, rows: [] },
  }} />);
  const filter = screen.getByRole('checkbox', { name: '출퇴근 누락만' });
  const thisMonth = screen.getByRole('link', { name: '이번 달' });
  const lastMonth = screen.getByRole('link', { name: '지난달' });

  fireEvent.click(filter);
  expect(thisMonth.getAttribute('href')).toBe('/me/attendance?view=period&from=2026-11-01&to=2026-11-17&missingOnly=true');
  expect(lastMonth.getAttribute('href')).toBe('/me/attendance?view=period&from=2026-10-01&to=2026-10-31&missingOnly=true');
  fireEvent.click(filter);
  expect(thisMonth.getAttribute('href')).toBe('/me/attendance?view=period&from=2026-11-01&to=2026-11-17');
  expect(lastMonth.getAttribute('href')).toBe('/me/attendance?view=period&from=2026-10-01&to=2026-10-31');
});

it('근태 열에 서버가 정한 문구를 보이고 판정이 없으면 대시를 보인다', () => {
  render(<WorkflowPages page="clock/period" csrf={csrf} reload={() => {}} data={{ attendancePeriod: {
    today: '2026-11-17', from: '2026-11-16', to: '2026-11-17', missingOnly: false,
    rows: [
      { workDate: '2026-11-17', scheduleKind: 'WORKDAY', startTime: '09:00', endTime: '18:00', inAt: null, outAt: null,
        recordStatus: 'NONE', workStatus: 'ABSENT', actualWorkMillis: null, attendanceLabel: null },
      { workDate: '2026-11-16', scheduleKind: 'WORKDAY', startTime: '09:00', endTime: '18:00', inAt: null, outAt: null,
        recordStatus: 'PRESENT', workStatus: 'AVAILABLE', actualWorkMillis: 0, attendanceLabel: '지각 7분' },
    ],
  } }} />);
  expect(screen.getByRole('columnheader', { name: '근태' })).toBeTruthy();
  expect(screen.getByText('지각 7분').className).toContain('t-tag amber');
  expect(screen.getAllByRole('cell').filter(cell => cell.getAttribute('data-label') === '근태').map(cell => cell.textContent))
    .toEqual(['-', '지각 7분']);
});
