// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Modal } from './Modal';

const showModal = vi.fn(function (this: HTMLDialogElement) { this.open = true; });
const close = vi.fn(function (this: HTMLDialogElement) { this.open = false; });

beforeEach(() => {
  showModal.mockClear();
  close.mockClear();
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: showModal });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: close });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('공통 업무 모달', () => {
  it('자식 작성 화면이 먼저 초점을 옮겨도 닫을 때 원래 열기 버튼으로 돌아간다', () => {
    function Composer() {
      const input = React.useRef<HTMLInputElement>(null);
      React.useEffect(() => { input.current?.focus(); }, []);
      return <input ref={input} aria-label="신청 사유" />;
    }
    const original = render(<button>신청 열기</button>);
    const trigger = original.getByRole('button');
    trigger.focus();
    const modal = render(<Modal title="신청" onClose={vi.fn()}><Composer /></Modal>);
    modal.unmount();
    expect(document.activeElement).toBe(trigger);
  });

  it('제목과 처음·마지막 버튼에서 Tab·Shift+Tab을 모달 내부로 순환시킨다', () => {
    vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([new DOMRect()] as unknown as DOMRectList);
    render(<Modal title="권한 변경" onClose={vi.fn()}><input aria-label="사유" /></Modal>);
    const title = screen.getByRole('heading', { name: '권한 변경' });
    const [first, last] = screen.getAllByRole('button', { name: '닫기' });
    title.focus();
    fireEvent.keyDown(title, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(last);
    fireEvent.keyDown(last, { key: 'Tab' });
    expect(document.activeElement).toBe(first);
    fireEvent.keyDown(first, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(last);
  });

  it('Tab 순환에서 숨김·비활성 항목은 제외하고 조작 가능한 항목이 없으면 제목을 유지한다', () => {
    vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([new DOMRect()] as unknown as DOMRectList);
    const { rerender } = render(<Modal title="저장 중" onClose={vi.fn()} busy>
      <button disabled>비활성 항목</button><div hidden><button>숨김 항목</button></div>
      <input aria-label="첫 입력" /><button>마지막 항목</button>
      <button style={{ visibility: 'hidden' }}>보이지 않는 항목</button>
    </Modal>);
    const last = screen.getByRole('button', { name: '마지막 항목' });
    last.focus(); fireEvent.keyDown(last, { key: 'Tab' });
    expect(document.activeElement).toBe(screen.getByRole('textbox'));
    rerender(<Modal title="저장 중" onClose={vi.fn()} busy>저장 결과 확인 중</Modal>);
    const title = screen.getByRole('heading', { name: '저장 중' });
    title.focus();
    expect(fireEvent.keyDown(title, { key: 'Tab' })).toBe(false);
    expect(document.activeElement).toBe(title);
  });

  it('접근 가능한 제목으로 native modal을 열고 제목에 초점을 둔다', () => {
    const { container } = render(<Modal title="권한 변경" onClose={vi.fn()}><label>사유<input /></label></Modal>);
    const dialog = screen.getByRole('dialog', { name: '권한 변경' });
    expect(container.contains(dialog)).toBe(false);
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(showModal).toHaveBeenCalledTimes(1);
    expect(document.activeElement).toBe(within(dialog).getByRole('heading', { name: '권한 변경' }));
  });

  it('X와 하단 닫기만 닫기를 요청하고 배경 클릭은 입력을 보존한다', () => {
    const onClose = vi.fn();
    render(<Modal title="권한 변경" onClose={onClose}>내용</Modal>);
    const dialog = screen.getByRole('dialog');
    fireEvent.click(dialog);
    expect(onClose).not.toHaveBeenCalled();
    const buttons = within(dialog).getAllByRole('button', { name: '닫기' });
    expect(buttons).toHaveLength(2);
    buttons.forEach(button => fireEvent.click(button));
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('Esc의 기본 닫기를 막고 상위의 미저장 확인을 거치도록 요청한다', () => {
    const onClose = vi.fn();
    render(<Modal title="신청 작성" onClose={onClose}>내용</Modal>);
    const cancel = new Event('cancel', { cancelable: true });
    fireEvent(screen.getByRole('dialog'), cancel);
    expect(cancel.defaultPrevented).toBe(true);
    expect(onClose).toHaveBeenCalledOnce();
    expect((screen.getByRole('dialog') as HTMLDialogElement).open).toBe(true);
  });

  it('저장 중 X·닫기·Esc를 차단하고 상태 변경으로 입력 초점을 빼앗지 않는다', () => {
    const onClose = vi.fn();
    const content = <label>사유<input /></label>;
    const { rerender } = render(<Modal title="권한 변경" onClose={onClose}>{content}</Modal>);
    const input = screen.getByRole('textbox');
    input.focus();
    rerender(<Modal title="권한 변경" onClose={onClose} busy>{content}</Modal>);
    screen.getAllByRole('button', { name: '닫기' }).forEach(button => {
      expect((button as HTMLButtonElement).disabled).toBe(true);
      fireEvent.click(button);
    });
    const cancel = new Event('cancel', { cancelable: true });
    fireEvent(screen.getByRole('dialog'), cancel);
    expect(cancel.defaultPrevented).toBe(true);
    expect(onClose).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(input);
    expect(showModal).toHaveBeenCalledTimes(1);
  });

  it('배경 스크롤을 잠그고 닫을 때 기존 스크롤 설정과 열기 버튼 초점을 복원한다', () => {
    const { unmount: unmountTrigger } = render(<button>권한 변경 열기</button>);
    const trigger = screen.getByRole('button');
    trigger.focus();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'auto';
    const { unmount } = render(<Modal title="권한 변경" onClose={vi.fn()}>내용</Modal>);
    expect(document.body.style.overflow).toBe('hidden');
    unmount();
    expect(close).toHaveBeenCalledOnce();
    expect(document.body.style.overflow).toBe('auto');
    expect(document.activeElement).toBe(trigger);
    document.body.style.overflow = previousOverflow;
    unmountTrigger();
  });

  it('portal 내부 링크와 폼 이벤트를 상위 앱의 이동·저장 처리로 전달한다', () => {
    const onClick = vi.fn((event: React.MouseEvent) => event.preventDefault());
    const onSubmit = vi.fn((event: React.SubmitEvent) => event.preventDefault());
    render(<div onClick={onClick} onSubmit={onSubmit}><Modal title="조직장 변경" onClose={vi.fn()}>
      <a href="/admin/organizations">목록</a><form aria-label="변경"><button>저장</button></form>
    </Modal></div>);
    fireEvent.click(screen.getByRole('link', { name: '목록' }));
    expect(onClick).toHaveBeenCalledOnce();
    fireEvent.submit(screen.getByRole('form', { name: '변경' }));
    expect(onSubmit).toHaveBeenCalledOnce();
  });

  it('저장 후 목록이 다시 그려져도 같은 업무 링크에 초점을 복원한다', () => {
    const original = render(<main><a href="/admin/employees/e-1/access" aria-label="직원 권한 관리">권한 관리</a></main>);
    original.getByRole('link').focus();
    const modal = render(<Modal title="권한 변경" onClose={vi.fn()}>내용</Modal>);
    original.unmount();
    const refreshed = render(<main>
      <a href="/admin/employees/e-2/access" aria-label="다른 직원 권한 관리">권한 관리</a>
      <a href="/admin/employees/e-1/access" aria-label="직원 권한 관리">권한 관리</a>
    </main>);
    modal.unmount();
    expect(document.activeElement).toBe(refreshed.getByRole('link', { name: '직원 권한 관리' }));
  });

  it('저장으로 대상이 목록에서 사라지면 배경 화면 제목으로 초점을 복원한다', () => {
    const original = render(<main><a href="/admin/employees/e-1/access">권한 관리</a></main>);
    original.getByRole('link').focus();
    const modal = render(<Modal title="권한 변경" onClose={vi.fn()}>내용</Modal>);
    original.unmount();
    const refreshed = render(<main><h1>권한 목록</h1><p>조회된 직원이 없습니다.</p></main>);
    modal.unmount();
    expect(document.activeElement).toBe(refreshed.getByRole('heading', { name: '권한 목록' }));
  });
});
