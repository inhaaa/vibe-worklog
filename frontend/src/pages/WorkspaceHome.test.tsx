// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WorkspaceHome } from './WorkspaceHome';

const day = {
  snapshot: { workDate: '2026-11-06' },
  today: true,
  scheduleText: '09:00–18:00',
  calendarText: '확인된 근로일',
  confirmationText: '실제 근로 미확인',
  inTime: '08:56',
  outTime: '미기록',
  timeline: [
    { time: '08:56:00', title: '현재 반영 출근', kind: 'recorded' },
    { time: '09:00:00', title: '예정 근무 시작', kind: 'planned' },
  ],
  requests: [],
};
const home = {
  employee: { employeeName: '김하늘', companyName: '가상 회사' },
  today: day,
  week: [day],
  availableLeaveDays: 0,
  pendingRequests: 1,
  recentRequests: [{
    request: { id: 'request-1', fromDate: '2026-11-09', toDate: '2026-11-09' },
    kindLabel: '종일 연차', statusLabel: '검토 중', tone: 'amber', effectiveLeave: false,
  }],
};

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true, value(this: HTMLDialogElement) { this.open = true; },
  });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true, value(this: HTMLDialogElement) { this.open = false; },
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  localStorage.clear();
});

const homePage = (homeWidgetScope?: string) => <WorkspaceHome data={{ workspaceHome: home, homeWidgetScope }} />;
const widgetSettings = () => screen.getByRole('button', { name: '위젯 설정' });
function editWidgets() {
  fireEvent.click(widgetSettings());
  return within(screen.getByRole('dialog', { name: '위젯 설정' }));
}

describe('업무 홈', () => {
  it('서버가 제공한 오늘 일정, 출퇴근 및 내 신청 상태를 연결한다', () => {
    render(<WorkspaceHome data={{ workspaceHome: home }} />);

    expect(screen.getByRole('heading', { level: 1, name: '김하늘님, 안녕하세요' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '근태 신청' }).getAttribute('href')).toBe('/me/requests/new');
    expect(screen.getByRole('link', { name: '출퇴근 기록' }).getAttribute('href')).toBe('/me/attendance');
    expect(screen.getByText('0일')).toBeTruthy();
    expect(screen.getByText('1건')).toBeTruthy();
    expect(screen.getByText('실제 근로 미확인')).toBeTruthy();
    const today = screen.getByRole('region', { name: '오늘 근무' });
    const timeline = within(today).getByRole('list');
    expect(within(timeline).getByText('현재 반영 출근')).toBeTruthy();
    expect(within(timeline).getByText('예정 근무 시작')).toBeTruthy();
    expect(screen.getByRole('link', { name: /종일 연차.*검토 중/ }).getAttribute('href')).toBe('/requests/request-1');
    const week = screen.getByRole('region', { name: '이번 주 일정' });
    expect(within(week).getByRole('link', { current: 'date' }).getAttribute('href'))
      .toBe('/me/schedule?month=2026-11&date=2026-11-06');
  });

  it('미등록 연차와 미기록 상태를 실제 값 0과 구분한다', () => {
    render(<WorkspaceHome data={{ workspaceHome: {
      ...home, today: { ...day, timeline: [], inTime: '미기록' }, availableLeaveDays: null, recentRequests: [],
    } }} />);

    expect(screen.getByText('미등록')).toBeTruthy();
    expect(screen.queryByText('0일')).toBeNull();
    expect(screen.getByText('표시할 일정이나 출퇴근 기록이 없습니다.')).toBeTruthy();
    expect(screen.getByText('아직 본인 대상 신청이 없습니다.')).toBeTruthy();
  });

  it('저장한 위젯만 표시하고 다시 열었을 때도 계정별로 구성을 복원한다', () => {
    const view = render(homePage('scope-1'));
    expect(widgetSettings().textContent).toBe('');
    expect(widgetSettings().getAttribute('aria-haspopup')).toBe('dialog');
    expect(screen.queryByRole('checkbox')).toBeNull();
    const settings = editWidgets();
    expect(document.activeElement).toBe(settings.getByRole('heading', { name: '위젯 설정' }));
    const picker = settings.getByRole('group', { name: '표시할 위젯' });
    expect(within(picker).getAllByRole('checkbox')).toHaveLength(6);
    fireEvent.click(within(picker).getByRole('checkbox', { name: /사용 가능 연차/ }));
    expect(screen.getByText('0일')).toBeTruthy();
    fireEvent.click(settings.getByRole('button', { name: '구성 저장' }));

    expect(screen.queryByText('0일')).toBeNull();
    expect(screen.getByRole('link', { name: '근태 신청' })).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(widgetSettings());
    expect(JSON.parse(localStorage.getItem('worklog-home-widgets:scope-1')!)).toEqual([
      'schedule', 'pending', 'attendance', 'requests', 'week',
    ]);
    view.rerender(homePage('scope-2'));
    expect(screen.getByText('0일')).toBeTruthy();
    view.rerender(homePage('scope-1'));
    expect(screen.queryByText('0일')).toBeNull();

    editWidgets();
    fireEvent.click(screen.getByRole('checkbox', { name: /사용 가능 연차/ }));
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(screen.queryByText('0일')).toBeNull();
    editWidgets();
    fireEvent.click(screen.getByRole('checkbox', { name: /사용 가능 연차/ }));
    fireEvent.click(screen.getByRole('button', { name: '구성 저장' }));
    expect(screen.getByText('0일')).toBeTruthy();
  });

  it.each(['취소', '닫기', 'Escape'])('%s로 설정을 닫으면 수정 전 선택과 열기 버튼 초점으로 돌아간다', action => {
    render(homePage('scope-1'));
    const settings = editWidgets();
    const leave = () => settings.getByRole<HTMLInputElement>('checkbox', { name: /사용 가능 연차/ });
    fireEvent.click(leave());
    expect(leave().checked).toBe(false);

    if (action === 'Escape') fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }));
    else fireEvent.click(settings.getByRole('button', { name: action }));

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(widgetSettings());
    expect(localStorage.getItem('worklog-home-widgets:scope-1')).toBeNull();
    const reopened = editWidgets().getByRole<HTMLInputElement>('checkbox', { name: /사용 가능 연차/ });
    expect(reopened.checked).toBe(true);
  });

  it('다른 업무 화면이 열리는 중에는 위젯 설정을 열지 않는다', () => {
    const data = { workspaceHome: home, homeWidgetScope: 'scope-1' };
    const view = render(<WorkspaceHome data={data} busy />);
    expect((widgetSettings() as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(widgetSettings());
    expect(screen.queryByRole('dialog')).toBeNull();

    view.rerender(<WorkspaceHome data={data} />);
    expect(editWidgets().getAllByRole('checkbox')).toHaveLength(6);
  });

  it('모든 위젯을 뺀 상태를 저장하고 다시 선택할 수 있다', () => {
    const view = render(homePage('scope-1'));
    editWidgets();
    for (const checkbox of screen.getAllByRole('checkbox')) fireEvent.click(checkbox);
    fireEvent.click(screen.getByRole('button', { name: '구성 저장' }));
    view.unmount();
    render(homePage('scope-1'));

    expect(screen.getByText('표시 중인 위젯이 없습니다.')).toBeTruthy();
    expect(screen.getByRole('link', { name: '출퇴근 기록' })).toBeTruthy();
    editWidgets();
    fireEvent.click(screen.getByRole('checkbox', { name: /이번 주 일정/ }));
    fireEvent.click(screen.getByRole('button', { name: '구성 저장' }));
    expect(screen.getByRole('region', { name: '이번 주 일정' })).toBeTruthy();
    expect(screen.queryByRole('region', { name: '오늘 근무' })).toBeNull();
  });

  it('저장 형식이 깨졌거나 브라우저 저장소가 제한되어도 홈을 구성할 수 있다', () => {
    localStorage.setItem('worklog-home-widgets:scope-1', '{');
    render(homePage('scope-1'));
    expect(screen.getByText('0일')).toBeTruthy();
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException(); });
    editWidgets();
    fireEvent.click(screen.getByRole('checkbox', { name: /사용 가능 연차/ }));
    fireEvent.click(screen.getByRole('button', { name: '구성 저장' }));
    expect(screen.queryByText('0일')).toBeNull();
    expect(screen.getByText('이 화면에만 구성이 적용됩니다.')).toBeTruthy();
  });
});
