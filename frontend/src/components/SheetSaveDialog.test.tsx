// @vitest-environment jsdom
import React, { useState } from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { SheetSaveDialog, sheetChangeCounts } from './SheetSaveDialog';

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value(this: HTMLDialogElement) { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value(this: HTMLDialogElement) { this.open = false; } });
});
afterEach(cleanup);

function Harness({ onSubmit, error, busy = false }: { onSubmit: (data: FormData) => void; error?: string; busy?: boolean }) {
  const [reason, setReason] = useState('');
  const [open, setOpen] = useState(true);
  return <>
    <form id="sheet-form" onSubmit={event => { event.preventDefault(); onSubmit(new FormData(event.currentTarget)); }}>
      <input type="hidden" name="rows" value="[1,2,3]" />
    </form>
    {open && <SheetSaveDialog formId="sheet-form" label="일괄 변경 사유" reason={reason} error={error} busy={busy}
      counts={{ create: 1, update: 2, deactivate: 0 }} onReasonChange={setReason} onClose={() => setOpen(false)} />}
  </>;
}

describe('SheetSaveDialog 저장 확인 창', () => {
  it('건수를 보여 주고 사유와 저장 버튼을 시트 폼으로 제출한다', () => {
    const submitted: FormData[] = [];
    render(<Harness onSubmit={data => submitted.push(data)} />);
    const dialog = screen.getByRole('dialog', { name: '변경 내용 저장' });
    expect(within(dialog).getByText('신규 1건')).toBeTruthy();
    expect(within(dialog).getByText('수정 2건')).toBeTruthy();
    expect(within(dialog).queryByText(/비활성화/)).toBeNull();
    const save = within(dialog).getByRole('button', { name: '3건 저장' });
    fireEvent.click(save);
    expect(submitted).toHaveLength(0);
    fireEvent.change(within(dialog).getByLabelText('일괄 변경 사유'), { target: { value: '조직 개편' } });
    expect(within(dialog).getByText('5/500')).toBeTruthy();
    fireEvent.click(save);
    expect(submitted).toHaveLength(1);
    expect(submitted[0].get('reason')).toBe('조직 개편');
    expect(submitted[0].get('rows')).toBe('[1,2,3]');
  });

  it('서버 사유 오류를 사유 칸에 연결하고 오류 초점 대상으로 표시한다', () => {
    render(<Harness onSubmit={() => {}} error="변경 사유를 입력하세요." />);
    const reason = screen.getByLabelText('일괄 변경 사유');
    expect(reason.getAttribute('aria-invalid')).toBe('true');
    expect(reason.getAttribute('data-error-focus')).toBe('true');
    expect(document.getElementById(reason.getAttribute('aria-describedby')!.split(' ')[0])?.textContent).toBe('변경 사유를 입력하세요.');
  });

  it('처리 중에는 취소·저장·닫기를 막고 취소는 창을 닫는다', () => {
    const { unmount } = render(<Harness onSubmit={() => {}} busy />);
    const dialog = screen.getByRole('dialog', { name: '변경 내용 저장' });
    for (const name of ['취소', '3건 저장', '닫기']) expect((within(dialog).getByRole('button', { name }) as HTMLButtonElement).disabled).toBe(true);
    unmount();
    render(<Harness onSubmit={() => {}} />);
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '취소' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('변경 행을 신규·수정·비활성화로 센다', () => {
    expect(sheetChangeCounts([{ operation: 'CREATE' }, { id: 'a', operation: 'UPDATE' }, { id: 'b', operation: 'DEACTIVATE' }, { id: 'c', operation: 'UPDATE' }]))
      .toEqual({ create: 1, update: 2, deactivate: 1 });
  });
});
