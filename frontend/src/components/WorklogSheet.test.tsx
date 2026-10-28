// @vitest-environment jsdom
import React, { createRef, useState } from 'react';
import { cleanup, fireEvent, render, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { WorklogSheet, sheetColumn } from './WorklogSheet';
import type { WorklogSheetHandle } from './WorklogSheet';

interface Row { id: string; name: string; locked?: boolean }
const rows: Row[] = [{ id: 'b', name: '나무' }, { id: 'a', name: '가람', locked: true }];
const columns = [sheetColumn<Row>({ field: 'name', headerName: '이름' }, row => <span>{row.name}</span>)];
const initialViewportWidth = window.innerWidth;
function resizeViewport(width: number) {
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: width });
  fireEvent(window, new Event('resize'));
}
afterEach(() => { cleanup(); resizeViewport(initialViewportWidth); });

describe('WorklogSheet 공통 업무 그리드', () => {
  it('작은 화면은 선택 열만 고정하고 데스크톱으로 돌아오면 식별 열 고정을 복원한다', async () => {
    resizeViewport(390);
    const view = render(<WorklogSheet label="모바일 시트" rows={rows} getRowId={row => row.id}
      selectedIds={new Set(['b'])} onSelectionChange={() => {}}
      columns={[
        sheetColumn<Row>({ field: 'id', headerName: '식별 코드', pinned: 'left', minWidth: 190 }, row => row.id),
        ...columns,
      ]} />);
    await view.findByRole('grid', { name: '모바일 시트' });
    const pinned = (name: string) => view.getByRole('columnheader', { name: new RegExp(name) }).closest('.ag-grid-pinned-left-cells');
    expect(pinned('식별 코드')).toBeNull();
    expect(pinned('선택')).toBeTruthy();
    resizeViewport(1280);
    await waitFor(() => expect(pinned('식별 코드')).toBeTruthy());
    resizeViewport(599);
    await waitFor(() => expect(pinned('식별 코드')).toBeNull());
    resizeViewport(600);
    await waitFor(() => expect(pinned('식별 코드')).toBeTruthy());
    expect(pinned('선택')).toBeTruthy();
    expect((view.getByLabelText('모바일 시트 1행 선택') as HTMLInputElement).checked).toBe(true);
  });

  it('접근성 이름과 현재 페이지 범위, 실제 그리드의 행과 열을 표시한다', async () => {
    const view = render(<WorklogSheet label="사원 목록" rows={rows} columns={columns} getRowId={row => row.id} />);
    expect(await view.findByRole('grid', { name: '사원 목록' })).toBeTruthy();
    expect(view.getByRole('columnheader', { name: /이름/ })).toBeTruthy();
    expect(view.getByText('나무')).toBeTruthy();
    expect(view.getByText(/정렬·필터는 현재 페이지/)).toBeTruthy();
    expect(view.container.querySelector('table')).toBeNull();
  });

  it('정렬과 필터로 표시 순서가 바뀌어도 행 ID와 원래 순번을 유지한다', async () => {
    const view = render(<WorklogSheet label="사원 목록" rows={rows} columns={columns} getRowId={row => row.id} />);
    await view.findByRole('grid', { name: '사원 목록' });
    fireEvent.click(within(view.getByRole('columnheader', { name: /이름/ })).getByText('이름'));
    await waitFor(() => expect(view.container.querySelector('[row-index="0"][row-id]')?.getAttribute('row-id')).toBe('a'));
    fireEvent.change(view.getByLabelText('사원 목록 현재 페이지 필터'), { target: { value: '나무' } });
    await waitFor(() => expect(view.queryByText('가람')).toBeNull());
    expect(view.container.querySelector('[row-id="b"]')).toBeTruthy();
  });

  it('필터로 숨은 선택·변경 행을 유지하고 건수를 알린다', async () => {
    function Example() {
      const [selected, setSelected] = useState(new Set<string>());
      return <WorklogSheet label="사원 목록" rows={rows} columns={columns} getRowId={row => row.id}
        selectedIds={selected} onSelectionChange={setSelected} changedIds={new Set(['b'])} />;
    }
    const view = render(<Example />);
    fireEvent.click(await view.findByLabelText('사원 목록 1행 선택'));
    fireEvent.change(view.getByLabelText('사원 목록 현재 페이지 필터'), { target: { value: '가람' } });
    await waitFor(() => expect(view.getByText('숨은 선택 1행 · 숨은 변경 1행')).toBeTruthy());
    fireEvent.change(view.getByLabelText('사원 목록 현재 페이지 필터'), { target: { value: '' } });
    await waitFor(() => expect((view.getByLabelText('사원 목록 1행 선택') as HTMLInputElement).checked).toBe(true));
  });

  it('보호 행의 선택을 막는다', async () => {
    const view = render(<WorklogSheet label="사원 목록" rows={rows} columns={columns} getRowId={row => row.id}
      selectedIds={new Set()} onSelectionChange={() => {}} isRowSelectable={row => !row.locked} />);
    expect((await view.findByLabelText('사원 목록 2행 선택') as HTMLInputElement).disabled).toBe(true);
  });

  it('선택 상태와 오류 셀을 공통 시각 상태로 표시한다', async () => {
    const view = render(<WorklogSheet label="사원 목록" rows={rows} columns={columns} getRowId={row => row.id}
      selectedIds={new Set(['b'])} onSelectionChange={() => {}}
      errors={[{ clientRowId: 'b', field: 'name', message: '이름 확인' }]} />);
    await view.findByRole('grid', { name: '사원 목록' });
    await waitFor(() => expect(view.container.querySelector('[row-id="b"].ag-row-selected')).toBeTruthy());
    expect(view.container.querySelector('[row-id="b"] [col-id="name"].w-sheet-cell-error')).toBeTruthy();
  });

  it('제어형 셀 입력은 변경 즉시 반영되고 다시 그려도 입력 초점을 유지한다', async () => {
    function Example() {
      const [data, setData] = useState(rows);
      return <WorklogSheet label="사원 편집" rows={data} getRowId={row => row.id}
        columns={[sheetColumn<Row>({ field: 'name', headerName: '이름' }, (row, index) => <input
          aria-label={`이름 ${index + 1}`} value={row.name} onChange={event => setData(current => current.map(item => item.id === row.id ? { ...item, name: event.target.value } : item))} />)]} />;
    }
    const view = render(<Example />);
    const input = await view.findByLabelText('이름 1') as HTMLInputElement;
    input.focus();
    fireEvent.change(input, { target: { value: '새 이름' } });
    expect((view.getByLabelText('이름 1') as HTMLInputElement).value).toBe('새 이름');
    expect(document.activeElement).toBe(input);
  });

  it('오류 이동은 숨은 행의 필터를 해제하고 해당 셀 입력에 초점을 둔다', async () => {
    const view = render(<WorklogSheet label="사원 편집" rows={rows} getRowId={row => row.id}
      errors={[{ clientRowId: 'b', field: 'name', message: '이름 확인' }]}
      columns={[sheetColumn<Row>({ field: 'name', headerName: '이름' }, row => <input aria-label={`${row.id} 이름`} defaultValue={row.name} />)]} />);
    await view.findByRole('grid', { name: '사원 편집' });
    fireEvent.change(view.getByLabelText('사원 편집 현재 페이지 필터'), { target: { value: '가람' } });
    fireEvent.click(view.getByRole('button', { name: /1행 · 이름: 이름 확인/ }));
    await waitFor(() => expect(document.activeElement).toBe(view.getByLabelText('b 이름')));
    expect((view.getByLabelText('사원 편집 현재 페이지 필터') as HTMLInputElement).value).toBe('');
  });

  it('새 행 포커스를 안정적인 행 ID로 지정한다', async () => {
    const ref = createRef<WorklogSheetHandle>();
    const view = render(<WorklogSheet label="사원 편집" rows={rows} getRowId={row => row.id} ref={ref}
      columns={[sheetColumn<Row>({ field: 'name', headerName: '이름' }, row => <input aria-label={`${row.id} 이름`} defaultValue={row.name} />)]} />);
    await view.findByRole('grid', { name: '사원 편집' });
    ref.current!.focusCell('a', 'name');
    await waitFor(() => expect(document.activeElement).toBe(view.getByLabelText('a 이름')));
  });

  it('빈 결과를 공통 안내로 표시한다', async () => {
    const view = render(<WorklogSheet label="사원 목록" rows={[]} columns={columns} getRowId={row => row.id} emptyMessage="등록된 사원이 없습니다." />);
    expect(within(view.getByRole('region', { name: '사원 목록' })).getByText('등록된 사원이 없습니다.')).toBeTruthy();
  });

  it('Tab으로 다음 셀에 이동하면 그 셀의 입력기에 초점을 둔다', async () => {
    const view = render(<WorklogSheet label="키보드 편집" rows={rows} getRowId={row => row.id} columns={[
      sheetColumn<Row>({ colId: 'name', field: 'name', headerName: '이름' }, row => <input aria-label={`${row.id} 이름`} defaultValue={row.name} />),
      sheetColumn<Row>({ colId: 'note', headerName: '설명' }, row => <input aria-label={`${row.id} 설명`} />),
    ]} />);
    const input = await view.findByLabelText('b 이름');
    input.focus();
    fireEvent.keyDown(input, { key: 'Tab', code: 'Tab', keyCode: 9 });
    await waitFor(() => expect(document.activeElement).toBe(view.getByLabelText('b 설명')));
  });

  it.each(['ArrowDown', 'Enter'])('%s 이동은 키를 누른 입력의 현재 행과 열을 기준으로 한다', async (key) => {
    const view = render(<WorklogSheet label="키보드 편집" rows={rows} getRowId={row => row.id} columns={[
      sheetColumn<Row>({ field: 'name', headerName: '이름' }, row => <input aria-label={`${row.id} 이름`} defaultValue={row.name} />),
    ]} />);
    const input = await view.findByLabelText('b 이름');
    fireEvent.keyDown(input, { key, code: key });
    await waitFor(() => expect(document.activeElement).toBe(view.getByLabelText('a 이름')));
  });

  it('경계 셀과 선택 체크박스에서 Enter가 저장 폼을 제출하지 않게 한다', async () => {
    const view = render(<form><WorklogSheet label="키보드 편집" rows={rows} getRowId={row => row.id}
      selectedIds={new Set()} onSelectionChange={() => {}}
      columns={[sheetColumn<Row>({ field: 'name', headerName: '이름' }, row => <input aria-label={`${row.id} 이름`} defaultValue={row.name} />)]} />
      <button type="submit">저장</button></form>);
    const last = await view.findByLabelText('a 이름');
    for (const [target, shiftKey] of [[last, false], [view.getByLabelText('b 이름'), true], [view.getByLabelText('키보드 편집 1행 선택'), false]] as const) {
      const enter = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', shiftKey, bubbles: true, cancelable: true });
      fireEvent(target, enter);
      expect(enter.defaultPrevented).toBe(true);
    }
  });

  it('현재 페이지 필터의 Enter가 저장 폼을 제출하지 않게 한다', async () => {
    const view = render(<form><WorklogSheet label="사원 편집" rows={rows} columns={columns} getRowId={row => row.id} />
      <button type="submit">저장</button></form>);
    const filter = view.getByLabelText('사원 편집 현재 페이지 필터');
    const enter = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true, cancelable: true });
    fireEvent(filter, enter);
    expect(enter.defaultPrevented).toBe(true);
  });

  it('선택 입력·textarea·링크와 명시 저장 버튼의 기본 Enter 동작은 유지한다', async () => {
    const view = render(<form><WorklogSheet label="키보드 편집" rows={rows} getRowId={row => row.id} columns={[
      sheetColumn<Row>({ colId: 'choice', headerName: '유형' }, row => <select aria-label={`${row.id} 유형`}><option>유형</option></select>),
      sheetColumn<Row>({ colId: 'note', headerName: '메모' }, row => <textarea aria-label={`${row.id} 메모`} />),
      sheetColumn<Row>({ colId: 'link', headerName: '상세' }, row => <a href={`/employees/${row.id}`}>{row.id} 상세</a>),
    ]} /><button type="submit">저장</button></form>);
    await view.findByLabelText('b 유형');
    for (const target of [view.getByLabelText('b 유형'), view.getByLabelText('b 메모'), view.getByRole('link', { name: 'b 상세' }), view.getByRole('button', { name: '저장' })]) {
      const enter = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true, cancelable: true });
      fireEvent(target, enter);
      expect(enter.defaultPrevented).toBe(false);
    }
  });

  it('한글 조합 중 Enter·방향키는 셀과 로컬 필터에서 기본 동작과 입력 초점을 유지한다', async () => {
    const view = render(<WorklogSheet label="한글 편집" rows={rows} getRowId={row => row.id}
      columns={[sheetColumn<Row>({ field: 'name', headerName: '이름' }, row => <input aria-label={`${row.id} 이름`} defaultValue={row.name} />)]} />);
    const input = await view.findByLabelText('b 이름');
    for (const target of [input, view.getByLabelText('한글 편집 현재 페이지 필터')]) {
      for (const key of ['Enter', 'ArrowDown']) {
        for (const composition of [{ isComposing: true }, { keyCode: 229 }]) {
          target.focus();
          const event = new KeyboardEvent('keydown', { key, code: key, ...composition, bubbles: true, cancelable: true });
          fireEvent(target, event);
          expect(event.defaultPrevented).toBe(false);
          expect(document.activeElement).toBe(target);
        }
      }
    }
  });
});
