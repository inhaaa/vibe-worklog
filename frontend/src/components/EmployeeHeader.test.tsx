// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { EmployeeHeader, EmployeeWorkTabs, type EmployeeHeaderData } from './EmployeeHeader';

const employee: EmployeeHeaderData = {
  employeeId: 'e-1', employeeNo: 'E001', employeeName: '김직원', companyName: '합성회사', organizationName: '개발팀',
  birthDate: '1990-01-01', gradeName: '선임', positionName: '팀원', jobName: '개발', joinedOn: '2024-03-04',
  employmentStatus: 'ON_LEAVE', photoUrl: '/me/profile/photo', asOf: '2026-11-01T15:00:00Z',
};
afterEach(cleanup);

it('서버가 제공한 대상 사원의 인사정보와 상태를 표시한다', () => {
  render(<EmployeeHeader employee={employee} />);
  const header = screen.getByRole('region', { name: '사원 인사정보' });
  for (const value of ['김직원', 'E001', '개발팀', '1990-01-01', '선임', '팀원', '개발', '2024-03-04', '휴직'])
    expect(header.textContent).toContain(value);
  expect(within(header).getByRole('img', { name: '김직원 사원사진' }).getAttribute('src')).toBe('/me/profile/photo');
});

it('사진이 없거나 실패하면 이니셜을 쓰고 누락 상태를 재직으로 추정하지 않는다', () => {
  render(<EmployeeHeader employee={{ ...employee, photoUrl: null, birthDate: null, gradeName: null, positionName: null, jobName: null, employmentStatus: 'UNKNOWN' }} />);
  expect(screen.queryByRole('img')).toBeNull();
  expect(screen.getByText('김')).toBeTruthy();
  expect(screen.getByText('상태 미등록')).toBeTruthy();
  expect(screen.getAllByText('미등록')).toHaveLength(4);
  cleanup();
  render(<EmployeeHeader employee={employee} />);
  fireEvent.error(screen.getByRole('img'));
  expect(screen.queryByRole('img')).toBeNull();
  expect(screen.getByText('김')).toBeTruthy();
});

it('같은 사원의 사진 개정이 바뀌면 이전 사진 오류와 무관하게 재조회한다', () => {
  const view = render(<EmployeeHeader employee={{ ...employee, photoUrl: '/me/profile/photo?v=1' }} />);
  fireEvent.error(screen.getByRole('img'));
  view.rerender(<EmployeeHeader employee={{ ...employee, photoUrl: '/me/profile/photo?v=2' }} />);
  expect(screen.getByRole('img', { name: '김직원 사원사진' }).getAttribute('src')).toBe('/me/profile/photo?v=2');
});

it('본인 업무 탭과 관리자 대상 사원의 업무 탭을 구분한다', () => {
  const view = render(<EmployeeWorkTabs page="request/mine" />);
  expect(screen.getByRole('link', { name: '휴가·신청' }).getAttribute('aria-current')).toBe('page');
  expect(screen.getByRole('link', { name: '출퇴근 기록' }).getAttribute('href')).toBe('/me/attendance');
  view.rerender(<EmployeeWorkTabs page="org/personnel" employeeId="e-2" />);
  expect(screen.getByRole('link', { name: '인사정보' }).getAttribute('href')).toBe('/admin/employees/e-2/personnel');
  expect(screen.getByRole('link', { name: '인사정보' }).getAttribute('aria-current')).toBe('page');
  expect(screen.getByRole('link', { name: '계정·권한' }).getAttribute('href')).toBe('/admin/employees/e-2/access');
  expect(screen.queryByRole('link', { name: '휴가·신청' })).toBeNull();
});
