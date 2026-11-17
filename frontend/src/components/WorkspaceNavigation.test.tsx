// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Breadcrumbs, WorkspaceNavigation } from './WorkspaceNavigation';

afterEach(cleanup);
describe('업무 트리', () => {
  it('현재 상세의 조상은 열리고 실제 링크만 현재 페이지로 표시한다', () => {
    render(<WorkspaceNavigation roles={['EMPLOYEE', 'ATTENDANCE_ADMIN']} page="org/personnel" pathname="/admin/employees/e-1/personnel" />);
    expect(screen.getByRole('button', { name: '인사·조직' }).getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByRole('button', { name: '조직·사원' }).getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByRole('link', { name: '인사기본' }).getAttribute('aria-current')).toBe('page');
    expect(screen.queryByRole('link', { name: '홈' })).toBeNull();
    expect(screen.queryByRole('tree')).toBeNull();
  });

  it('현재 업무의 조상 강조는 접힘과 무관하게 유지되고 화면 이동을 따른다', () => {
    const roles = ['EMPLOYEE', 'ATTENDANCE_ADMIN'];
    const view = render(<WorkspaceNavigation roles={roles} page="org/personnel" pathname="/admin/employees/e-1/personnel" />);
    const people = screen.getByRole('button', { name: '인사·조직' });
    expect(people.getAttribute('data-active')).toBe('true');
    expect(screen.getByRole('button', { name: '조직·사원' }).getAttribute('data-active')).toBe('true');
    expect(screen.getByRole('button', { name: '근태 운영' }).getAttribute('data-active')).toBe('false');
    fireEvent.click(people);
    expect(people.getAttribute('aria-expanded')).toBe('false');
    expect(people.getAttribute('data-active')).toBe('true');

    view.rerender(<WorkspaceNavigation roles={roles} page="worktime/month" pathname="/admin/attendance" data={{ admin: true }} />);
    expect(people.getAttribute('data-active')).toBe('false');
    expect(screen.getByRole('button', { name: '근태 운영' }).getAttribute('data-active')).toBe('true');
    expect(screen.getByRole('button', { name: '회사 현황' }).getAttribute('data-active')).toBe('true');
    expect(screen.getByRole('link', { name: '근태 집계·Excel' }).getAttribute('aria-current')).toBe('page');
  });

  it('업무 묶음 버튼은 제어하는 목록과 연결되고 접은 목록은 접근성 탐색에서 제외한다', () => {
    render(<WorkspaceNavigation roles={['EMPLOYEE']} page="clock/history" pathname="/me/attendance" />);
    const section = screen.getByRole('button', { name: '근태관리' });
    const panel = document.getElementById(section.getAttribute('aria-controls')!);
    expect(panel?.contains(screen.getByRole('link', { name: '출퇴근 기록' }))).toBe(true);
    fireEvent.click(section);
    expect(section.getAttribute('aria-expanded')).toBe('false');
    expect(panel?.hidden).toBe(true);
    expect(screen.queryByRole('link', { name: '출퇴근 기록' })).toBeNull();
    fireEvent.click(section);
    expect(screen.getByRole('link', { name: '출퇴근 기록' }).getAttribute('aria-current')).toBe('page');
  });

  it('모두 펼치기와 모두 접기로 전체 묶음을 한 번에 연다', () => {
    render(<WorkspaceNavigation roles={['EMPLOYEE', 'ATTENDANCE_ADMIN']} page="workspace/profile" pathname="/me/profile" />);
    const toggles = () => screen.getAllByRole('button').filter(button => button.hasAttribute('aria-expanded'));
    fireEvent.click(screen.getByRole('button', { name: '모두 펼치기' }));
    expect(toggles().every(button => button.getAttribute('aria-expanded') === 'true')).toBe(true);
    expect(screen.getByRole('link', { name: '근태 집계·Excel' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '모두 접기' }));
    expect(toggles().every(button => button.getAttribute('aria-expanded') === 'false')).toBe(true);
    expect(screen.queryByRole('link', { name: '내 정보' })).toBeNull();
  });

  it('묶음을 접고 펼칠 수 있고 화면 이동 시 현재 조상을 다시 연다', () => {
    const view = render(<WorkspaceNavigation roles={['EMPLOYEE']} page="workspace/profile" pathname="/me/profile" />);
    fireEvent.click(screen.getByRole('button', { name: '내 업무' }));
    expect(screen.getByRole('button', { name: '내 업무' }).getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByRole('link', { name: '내 정보' })).toBeNull();
    view.rerender(<WorkspaceNavigation roles={['EMPLOYEE']} page="clock/history" pathname="/me/attendance" />);
    expect(screen.getByRole('button', { name: '내 업무' }).getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByRole('button', { name: '근태관리' }).getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByRole('link', { name: '출퇴근 기록' }).getAttribute('aria-current')).toBe('page');
  });

  it('링크 선택 시 상위 모바일 패널의 닫기 동작을 호출하고 query를 추가하지 않는다', () => {
    const close = vi.fn();
    render(<WorkspaceNavigation roles={['EMPLOYEE']} page="request/mine" pathname="/me/requests" onNavigate={close} />);
    const link = screen.getByRole('link', { name: '내 근태현황·신청' });
    expect(link.getAttribute('href')).toBe('/me/requests');
    link.addEventListener('click', event => event.preventDefault());
    fireEvent.click(link);
    expect(close).toHaveBeenCalledOnce();
  });

  it('같은 메뉴의 다른 상세로 이동해도 현재 조상을 다시 연다', () => {
    const view = render(<WorkspaceNavigation roles={['ATTENDANCE_ADMIN']} page="org/employee" pathname="/admin/employees/e-1" />);
    fireEvent.click(screen.getByRole('button', { name: '인사·조직' }));
    view.rerender(<WorkspaceNavigation roles={['ATTENDANCE_ADMIN']} page="org/personnel" pathname="/admin/employees/e-1/personnel" />);
    expect(screen.getByRole('button', { name: '인사·조직' }).getAttribute('aria-expanded')).toBe('true');
  });

  it('브레드크럼은 3단계 실제 경로와 상세 제목을 제공한다', () => {
    render(<Breadcrumbs page="org/personnel" pathname="/admin/employees/e-1/personnel" />);
    const crumb = screen.getByRole('navigation', { name: '현재 위치' });
    expect(crumb.textContent).toContain('인사·조직');
    expect(crumb.textContent).toContain('조직·사원');
    expect(screen.getByRole('link', { name: '인사기본' }).getAttribute('href')).toBe('/admin/employees');
    expect(screen.getByText('인사정보 등록').getAttribute('aria-current')).toBe('page');
  });
});
