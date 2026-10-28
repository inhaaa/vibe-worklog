// @vitest-environment jsdom
import React, { createRef, useState } from 'react';
import { act, cleanup, fireEvent, render, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WorklogSheet, sheetEditorColumn } from './WorklogSheet';
import type { SheetEditorField, WorklogSheetHandle } from './WorklogSheet';
import { validateSheetField } from './SheetCellEditor';

interface Row { id: string; name: string; note: string }
const initial: Row[] = [{ id: 'a', name: '가람', note: '메모' }, { id: 'b', name: '나무', note: '' }];
afterEach(cleanup);

function Example({ field = {}, sheetRef }: { field?: Partial<SheetEditorField>; sheetRef?: React.Ref<WorklogSheetHandle> }) {
  const [rows, setRows] = useState(initial);
  const column = (key: 'name' | 'note') => sheetEditorColumn<Row>({ field: key, headerName: key === 'name' ? '이름' : '메모' }, (row, index) => ({
    value: row[key], label: `${key} ${index + 1}`, onChange: value => setRows(current => current.map(item => item.id === row.id ? { ...item, [key]: value } : item)),
    ...(key === 'name' ? field : {}),
  }));
  return <form><input type="hidden" name="rows" value={JSON.stringify(rows)} /><WorklogSheet label="셀 편집" ref={sheetRef}
    rows={rows} columns={[column('name'), column('note')]} getRowId={row => row.id} /><button type="submit">저장</button></form>;
}
function cell(view: ReturnType<typeof render>, row: string, field: string) {
  return view.container.querySelector<HTMLElement>(`[row-id="${row}"] [col-id="${field}"]`)!;
}

describe('공통 셀 편집기', () => {
  it('평소에는 값만 표시하고 클릭한 셀에서만 편집하며 입력 즉시 제출값을 갱신한다', async () => {
    const view = render(<Example />);
    const grid = await view.findByRole('grid', { name: '셀 편집' });
    expect(grid.querySelector('input,select')).toBeNull();
    fireEvent.click(cell(view, 'a', 'name'), { detail: 1 });
    const input = await view.findByRole('textbox', { name: 'name 1' });
    fireEvent.change(input, { target: { value: '수정 이름' } });
    expect(JSON.parse(String(new FormData(view.container.querySelector('form')!).get('rows')))[0].name).toBe('수정 이름');
    expect(document.activeElement).toBe(input);
    fireEvent.keyDown(input, { key: 'Escape', code: 'Escape' });
    await waitFor(() => expect(within(grid).queryByRole('textbox')).toBeNull());
    expect(JSON.parse(String(new FormData(view.container.querySelector('form')!).get('rows')))[0].name).toBe('가람');
  });

  it.each(['F2', 'Enter'])('%s로 선택 셀을 편집하고 Enter 확정 후 다음 행으로 이동한다', async key => {
    const view = render(<Example />);
    await view.findByRole('grid', { name: '셀 편집' });
    const target = cell(view, 'a', 'name'); fireEvent.mouseDown(target); target.focus();
    fireEvent.keyDown(target, { key, code: key });
    const input = await view.findByRole('textbox', { name: 'name 1' });
    fireEvent.change(input, { target: { value: '저장 이름' } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });
    await waitFor(() => expect(document.activeElement).toBe(cell(view, 'b', 'name')));
    expect(cell(view, 'a', 'name').textContent).toContain('저장 이름');
  });

  it('Tab으로 다음 열 편집기로 이동하고 Shift+Tab으로 돌아온다', async () => {
    const view = render(<Example />);
    await view.findByRole('grid', { name: '셀 편집' });
    fireEvent.click(cell(view, 'a', 'name'), { detail: 1 });
    fireEvent.keyDown(await view.findByRole('textbox', { name: 'name 1' }), { key: 'Tab', code: 'Tab' });
    const note = await view.findByRole('textbox', { name: 'note 1' });
    expect(document.activeElement).toBe(note);
    fireEvent.keyDown(note, { key: 'Tab', code: 'Tab', shiftKey: true });
    await waitFor(() => expect(document.activeElement).toBe(view.getByRole('textbox', { name: 'name 1' })));
  });

  it('한글 조합 중 Enter는 확정·이동·폼 제출을 하지 않는다', async () => {
    const view = render(<Example />);
    await view.findByRole('grid', { name: '셀 편집' });
    fireEvent.click(cell(view, 'a', 'name'), { detail: 1 });
    const input = await view.findByRole('textbox', { name: 'name 1' });
    for (const composing of [{ isComposing: true }, { keyCode: 229 }]) {
      const enter = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', ...composing, bubbles: true, cancelable: true });
      fireEvent(input, enter);
      expect(enter.defaultPrevented).toBe(false);
      expect(document.activeElement).toBe(input);
    }
  });

  it('선택 셀은 표시문구를 보이고 선택한 원시값을 페이지에 보낸다', async () => {
    const view = render(<Example field={{ options: [['가람', '첫 선택'], ['changed', '새 선택']] }} />);
    await view.findByRole('grid', { name: '셀 편집' });
    expect(cell(view, 'a', 'name').textContent).toContain('첫 선택');
    fireEvent.click(cell(view, 'a', 'name'), { detail: 1 });
    const select = await view.findByRole('combobox', { name: 'name 1' });
    fireEvent.change(select, { target: { value: 'changed' } });
    expect(JSON.parse(String(new FormData(view.container.querySelector('form')!).get('rows')))[0].name).toBe('changed');
    fireEvent.keyDown(select, { key: 'Enter', code: 'Enter' });
    expect(cell(view, 'a', 'name').textContent).toContain('새 선택');
  });

  it('읽기 전용 셀은 입력 없이 표시하고 클릭·F2로 편집하지 않는다', async () => {
    const view = render(<Example field={{ readOnly: true }} />);
    await view.findByRole('grid', { name: '셀 편집' });
    const target = cell(view, 'a', 'name');
    expect(target.querySelector('[aria-readonly="true"]')).toBeTruthy();
    fireEvent.click(target); fireEvent.keyDown(target, { key: 'F2', code: 'F2' });
    expect(view.queryByRole('textbox')).toBeNull();
  });

  it('저장 검증은 편집하지 않은 필수·길이 오류도 찾고 첫 오류 편집기를 연다', async () => {
    const sheetRef = createRef<WorklogSheetHandle>();
    const view = render(<Example sheetRef={sheetRef} field={{ maxLength: 1 }} />);
    await view.findByRole('grid', { name: '셀 편집' });
    let valid = true; act(() => { valid = sheetRef.current!.validate(); });
    expect(valid).toBe(false);
    expect(view.getByRole('navigation', { name: '셀 편집 오류 위치' })).toBeTruthy();
    await waitFor(() => expect(document.activeElement).toBe(view.getByRole('textbox', { name: 'name 1' })));
    fireEvent.change(view.getByRole('textbox', { name: 'name 1' }), { target: { value: '가' } });
    fireEvent.keyDown(view.getByRole('textbox', { name: 'name 1' }), { key: 'Enter', code: 'Enter' });
    fireEvent.click(cell(view, 'b', 'name'), { detail: 1 });
    fireEvent.change(await view.findByRole('textbox', { name: 'name 2' }), { target: { value: '나' } });
    act(() => { valid = sheetRef.current!.validate(); });
    expect(valid).toBe(true);
  });

  it('읽기 전용·비활성 셀은 필수값 검증에서 제외한다', async () => {
    const sheetRef = createRef<WorklogSheetHandle>();
    const view = render(<Example sheetRef={sheetRef} field={{ disabled: true, required: true, maxLength: 1 }} />);
    await view.findByRole('grid', { name: '셀 편집' });
    expect(sheetRef.current!.validate()).toBe(true);
  });

  it('숫자 열은 문자열 원시값을 유지하면서 숫자 크기로 정렬한다', async () => {
    const data = [{ id: 'a', name: '10', note: '' }, { id: 'b', name: '2', note: '' }];
    const view = render(<WorklogSheet label="숫자" rows={data} getRowId={row => row.id} columns={[
      sheetEditorColumn<Row>({ field: 'name', headerName: '정렬' }, row => ({ value: row.name, label: row.id, type: 'number', onChange: () => {} })),
    ]} />);
    const grid = await view.findByRole('grid', { name: '숫자' });
    fireEvent.click(within(view.getByRole('columnheader', { name: /정렬/ })).getByText('정렬'));
    await waitFor(() => expect(grid.querySelector('[row-index="0"][row-id]')?.getAttribute('row-id')).toBe('b'));
  });

  it('원본의 과거 적용일은 건너뛰고 변경 행만 저장 전에 검증한다', async () => {
    const sheetRef = createRef<WorklogSheetHandle>();
    const data = [{ id: 'a', name: '0001-01-01', note: '' }, { id: 'b', name: '2026-10-29', note: '' }];
    const view = render(<WorklogSheet label="날짜" ref={sheetRef} rows={data} getRowId={row => row.id} changedIds={new Set(['b'])} columns={[
      sheetEditorColumn<Row>({ field: 'name', headerName: '적용일' }, row => ({ value: row.name, label: row.id, type: 'date', min: '2026-10-29', onChange: () => {} })),
    ]} />);
    await view.findByRole('grid', { name: '날짜' });
    let valid = false; act(() => { valid = sheetRef.current!.validate(); });
    expect(valid).toBe(true);
  });

  it('POST 대기 중 표시 셀은 새 편집기를 열지 않는다', async () => {
    const view = render(<div id="tna-app" aria-busy="true"><Example /></div>);
    await view.findByRole('grid', { name: '셀 편집' });
    fireEvent.click(cell(view, 'a', 'name'), { detail: 1 });
    expect(view.queryByRole('textbox')).toBeNull();
  });

  it('Escape는 값이 같아도 편집 시작 snapshot 취소 콜백을 호출한다', async () => {
    const onCancel = vi.fn();
    const view = render(<Example field={{ onCancel }} />);
    await view.findByRole('grid', { name: '셀 편집' });
    fireEvent.click(cell(view, 'a', 'name'), { detail: 1 });
    fireEvent.keyDown(await view.findByRole('textbox', { name: 'name 1' }), { key: 'Escape', code: 'Escape' });
    expect(onCancel).toHaveBeenCalledOnce();
  });

  it('타이핑으로 편집을 시작하면 첫 문자를 반영하고 이어 입력할 위치에 커서를 둔다', async () => {
    const view = render(<Example />);
    await view.findByRole('grid', { name: '셀 편집' });
    const target = cell(view, 'a', 'name'); fireEvent.mouseDown(target); target.focus();
    fireEvent.keyDown(target, { key: 'x', code: 'KeyX' });
    const input = await view.findByRole('textbox', { name: 'name 1' }) as HTMLInputElement;
    expect(input.value).toBe('x');
    expect(input.selectionStart).toBe(1); expect(input.selectionEnd).toBe(1);
    expect(JSON.parse(String(new FormData(view.container.querySelector('form')!).get('rows')))[0].name).toBe('x');
  });

  it('셀의 Delete도 페이지 draft에 빈값을 반영한다', async () => {
    const view = render(<Example />);
    await view.findByRole('grid', { name: '셀 편집' });
    const target = cell(view, 'a', 'name'); fireEvent.mouseDown(target); target.focus();
    fireEvent.keyDown(target, { key: 'Delete', code: 'Delete' });
    await waitFor(() => expect(JSON.parse(String(new FormData(view.container.querySelector('form')!).get('rows')))[0].name).toBe(''));
  });

  it('편집 중 Tab은 읽기 전용 셀을 건너뛴다', async () => {
    const view = render(<Example field={{ readOnly: true }} />);
    await view.findByRole('grid', { name: '셀 편집' });
    fireEvent.click(cell(view, 'a', 'note'), { detail: 1 });
    fireEvent.keyDown(await view.findByRole('textbox', { name: 'note 1' }), { key: 'Tab', code: 'Tab' });
    const next = await view.findByRole('textbox', { name: 'note 2' });
    expect(document.activeElement).toBe(next);
  });

  it('행마다 선택 목록이 달라도 각각의 표시 문구를 기준으로 정렬한다', () => {
    const left = { id: 'left', name: 'left', note: '' }; const right = { id: 'right', name: 'a', note: '' };
    const column = sheetEditorColumn<Row>({ field: 'name' }, row => ({ value: row.name, label: row.id,
      options: row.id === 'left' ? [['left', '가']] : [['a', '나']], onChange: () => {},
    }));
    expect(typeof column.comparator).toBe('function');
    if (typeof column.comparator === 'function') expect(column.comparator('left', 'a', { data: left } as never, { data: right } as never, false)).toBeLessThan(0);
  });

  it.each([['a', false], ['b', false], ['a', true]] as const)('편집 Enter는 %s행/Shift %s 경계에서도 폼 제출을 막는다', async (id, shiftKey) => {
    const view = render(<Example />);
    await view.findByRole('grid', { name: '셀 편집' });
    fireEvent.click(cell(view, id, 'name'), { detail: 1 });
    const input = await view.findByRole('textbox', { name: `name ${id === 'a' ? 1 : 2}` });
    const enter = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', shiftKey, bubbles: true, cancelable: true });
    fireEvent(input, enter);
    expect(enter.defaultPrevented).toBe(true);
  });

  it('전체 취소로 변경 행이 없어지면 원본 과거 날짜의 로컬 오류도 지운다', async () => {
    const ref = createRef<WorklogSheetHandle>();
    function Dates() {
      const [date, setDate] = useState('0001-01-01');
      return <><WorklogSheet label="날짜 취소" ref={ref} rows={[{ id: 'a', name: date, note: '' }]} getRowId={row => row.id}
        changedIds={new Set(date === '0001-01-01' ? [] : ['a'])} columns={[
          sheetEditorColumn<Row>({ field: 'name', headerName: '적용일' }, row => ({ value: row.name, label: '적용일', type: 'date', min: '2026-10-29', required: true, onChange: setDate })),
        ]} /><button type="button" onClick={() => setDate('0001-01-01')}>전체 취소</button></>;
    }
    const view = render(<Dates />);
    await view.findByRole('grid', { name: '날짜 취소' });
    fireEvent.click(cell(view, 'a', 'name'), { detail: 1 });
    fireEvent.change(view.getByLabelText('적용일', { selector: 'input' }), { target: { value: '' } });
    let valid = true; act(() => { valid = ref.current!.validate(); });
    expect(valid).toBe(false);
    fireEvent.click(view.getByRole('button', { name: '전체 취소' }));
    await waitFor(() => expect(view.queryByRole('navigation', { name: '날짜 취소 오류 위치' })).toBeNull());
  });

  it.each([false, true])('로컬 검증 오류를 활성 편집기와 표시 셀에 연결하고 수정 후 해제한다 (기존 편집기 %s)', async editing => {
    const ref = createRef<WorklogSheetHandle>();
    function RequiredRows() {
      const [rows, setRows] = useState(initial.map(row => ({ ...row, name: '' })));
      return <WorklogSheet label="로컬 오류" ref={ref} rows={rows} getRowId={row => row.id} columns={[
        sheetEditorColumn<Row>({ field: 'name', headerName: '이름' }, (row, index) => ({ value: row.name, label: `이름 ${index + 1}`, required: true,
          onChange: value => setRows(current => current.map(item => item.id === row.id ? { ...item, name: value } : item)),
        })),
      ]} />;
    }
    const view = render(<RequiredRows />);
    await view.findByRole('grid', { name: '로컬 오류' });
    if (editing) fireEvent.click(cell(view, 'a', 'name'), { detail: 1 });
    let valid = true; act(() => { valid = ref.current!.validate(); });
    expect(valid).toBe(false);
    const input = await view.findByRole('textbox', { name: '이름 1' });
    const displayed = view.getByLabelText('이름 2', { selector: '.w-sheet-value' });
    for (const target of [input, displayed]) {
      await waitFor(() => expect(target.getAttribute('aria-invalid')).toBe('true'));
      const description = target.getAttribute('aria-describedby');
      expect(description).toBeTruthy();
      expect(document.getElementById(description!)?.textContent).toBe('필수 입력입니다.');
    }
    expect(document.activeElement).toBe(input);
    fireEvent.change(input, { target: { value: '완료' } });
    await waitFor(() => expect(input.getAttribute('aria-invalid')).toBe('false'));
    expect(input.getAttribute('aria-describedby')).toBeNull();
    expect(displayed.getAttribute('aria-invalid')).toBe('true');
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });
    expect(view.getByLabelText('이름 1', { selector: '.w-sheet-value' }).getAttribute('aria-invalid')).toBe('false');
  });

  it.each([
    [{ value: '', required: true }, '필수'],
    [{ value: '2026-02-31', type: 'date' }, '날짜'],
    [{ value: '-1', type: 'number', min: 0 }, '이상'],
    [{ value: '1.5', type: 'number', step: 1 }, '단위'],
  ])('필수·달력·숫자 제약을 검증한다: %j', (constraints, text) => {
    expect(validateSheetField({ label: '검증', onChange: () => {}, ...constraints } as SheetEditorField)).toContain(text);
  });
});
