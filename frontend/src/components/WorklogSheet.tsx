import { useCallback, useEffect, useId, useImperativeHandle, useMemo, useRef, useState } from 'react';
import type { ReactNode, Ref } from 'react';
import { AgGridReact } from 'ag-grid-react';
import { AllCommunityModule, ModuleRegistry } from 'ag-grid-community';
import type { ColDef, GridApi, ICellRendererParams } from 'ag-grid-community';

ModuleRegistry.registerModules([AllCommunityModule]);

interface SheetContext<T> { index: Map<string, number>; getRowId: (row: T) => string; errors: SheetFieldError[] }
function RenderCell<T>({ data, context, render }: ICellRendererParams<T> & {
  render: (row: T, index: number) => ReactNode;
}) {
  if (!data) return null;
  const sheet = context as SheetContext<T>;
  return render(data, sheet.index.get(sheet.getRowId(data)) ?? 0);
}

/** Keep the renderer component stable while page-owned controlled inputs receive fresh props. */
export function sheetColumn<T>(column: ColDef<T>, render: (row: T, index: number) => ReactNode): ColDef<T> {
  return { ...column, cellRenderer: RenderCell<T>, cellRendererParams: { render } };
}

export interface WorklogSheetHandle { focusCell: (rowId: string, field?: string) => void }
export interface SheetFieldError { clientRowId: string; field: string; message: string }
interface Props<T> {
  label: string;
  rows: T[];
  columns: ColDef<T>[];
  getRowId: (row: T) => string;
  rowHeight?: number;
  selectedIds?: ReadonlySet<string>;
  onSelectionChange?: (selected: Set<string>) => void;
  selectionLabel?: (row: T, index: number) => string;
  isRowSelectable?: (row: T) => boolean;
  changedIds?: ReadonlySet<string>;
  errors?: SheetFieldError[];
  emptyMessage?: string;
  scope?: 'page' | 'all';
  ref?: Ref<WorklogSheetHandle>;
}
const defaults = {
  flex: 1, minWidth: 130, sortable: true, filter: 'agTextColumnFilter', resizable: true,
  suppressKeyboardEvent: ({ event }) => {
    const target = event.target as HTMLElement;
    // Native input keys edit values; Tab and grid-cell arrow keys remain grid navigation.
    return Boolean(target.closest('input,select,textarea')) && event.key !== 'Tab';
  },
} satisfies ColDef;
const localeText = {
  noRowsToShow: '조회된 항목이 없습니다.', loadingOoo: '불러오는 중…',
  filterOoo: '필터 값', equals: '같음', notEqual: '다름', contains: '포함', notContains: '포함하지 않음',
  startsWith: '시작', endsWith: '끝', blank: '비어 있음', notBlank: '비어 있지 않음',
  andCondition: '그리고', orCondition: '또는', applyFilter: '적용', resetFilter: '초기화', clearFilter: '지우기',
  ariaColumn: '열', ariaRow: '행', ariaFilterColumn: '열 필터', ariaColumnSelectAll: '전체 선택',
};

export function WorklogSheet<T>({ label, rows, columns, getRowId, rowHeight = 40,
  selectedIds, onSelectionChange, selectionLabel, isRowSelectable, changedIds, errors = [],
  emptyMessage = '조회된 항목이 없습니다.', scope = 'page', ref,
}: Props<T>) {
  const element = useRef<HTMLDivElement>(null);
  const filterId = useId();
  const api = useRef<GridApi<T> | null>(null);
  const rowId = useRef(getRowId);
  rowId.current = getRowId;
  const [query, setQuery] = useState('');
  const [narrowViewport, setNarrowViewport] = useState(() => typeof window !== 'undefined' && window.innerWidth < 600);
  useEffect(() => {
    const resize = () => setNarrowViewport(window.innerWidth < 600);
    window.addEventListener('resize', resize);
    return () => window.removeEventListener('resize', resize);
  }, []);
  const viewportColumns = narrowViewport ? columns.map(column => ({ ...column, pinned: null })) : columns;
  const [visible, setVisible] = useState(() => new Set(rows.map(getRowId)));
  const context = useMemo<SheetContext<T>>(() => ({ index: new Map(rows.map((row, index) => [getRowId(row), index])), getRowId, errors }), [rows, getRowId, errors]);
  const stableRowId = useCallback(({ data }: { data: T }) => rowId.current(data), []);

  function renderedCell(id: string, columnId: string) {
    for (const row of element.current?.querySelectorAll<HTMLElement>('[row-id]') ?? []) {
      if (row.getAttribute('row-id') !== id) continue;
      const cell = Array.from(row.querySelectorAll<HTMLElement>('[col-id]')).find(item => item.getAttribute('col-id') === columnId);
      if (cell) return cell;
    }
    return undefined;
  }

  const focusCell = useCallback((id: string, field?: string) => {
    const grid = api.current;
    if (!grid || grid.isDestroyed()) return;
    setQuery('');
    grid.setGridOption('quickFilterText', '');
    grid.setFilterModel(null);
    const node = grid.getRowNode(id);
    if (!node || node.rowIndex == null) return;
    const column = field && grid.getColumn(field) || grid.getAllDisplayedColumns().find(item => item.getColId() !== '__select');
    if (!column) return;
    grid.ensureNodeVisible(node);
    grid.ensureColumnVisible(column);
    grid.setFocusedCell(node.rowIndex, column);
    const focusInput = () => {
      const cell = renderedCell(id, column.getColId());
      (cell?.querySelector<HTMLElement>('input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a,button:not(:disabled)') ?? cell)?.focus();
    };
    focusInput();
    setTimeout(focusInput, 0);
  }, []);
  useImperativeHandle(ref, () => ({ focusCell }), [focusCell]);

  function syncSelection() {
    if (!selectedIds || !api.current || api.current.isDestroyed()) return;
    api.current.forEachNode(node => {
      if (node.data) node.setSelected(selectedIds.has(getRowId(node.data)));
    });
  }
  useEffect(syncSelection, [selectedIds]);

  function updateVisible() {
    const ids = new Set<string>();
    api.current?.forEachNodeAfterFilter(node => { if (node.data) ids.add(rowId.current(node.data)); });
    setVisible(previous => previous.size === ids.size && [...ids].every(id => previous.has(id)) ? previous : ids);
  }
  const selection: ColDef<T>[] = selectedIds && onSelectionChange ? [sheetColumn<T>({
    colId: '__select', headerName: '선택', width: 58, minWidth: 58, maxWidth: 58, flex: 0,
    pinned: 'left', sortable: false, filter: false, resizable: false, cellClass: 'w-sheet-center',
    getQuickFilterText: () => '',
  }, (row, index) => <input type="checkbox" className="t-sheet-check"
    aria-label={selectionLabel?.(row, index) ?? `${label} ${index + 1}행 선택`}
    checked={selectedIds.has(getRowId(row))} disabled={isRowSelectable ? !isRowSelectable(row) : false}
    onChange={event => {
      const next = new Set(selectedIds);
      if (event.target.checked) next.add(getRowId(row)); else next.delete(getRowId(row));
      onSelectionChange(next);
    }} />)] : [];
  const hiddenSelected = rows.filter(row => selectedIds?.has(getRowId(row)) && !visible.has(getRowId(row))).length;
  const hiddenChanged = rows.filter(row => changedIds?.has(getRowId(row)) && !visible.has(getRowId(row))).length;

  return <section className="w-sheet" aria-label={label} role="region" ref={element}
    onKeyDownCapture={event => {
      if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return;
      const input = event.target as HTMLElement;
      const cell = input.closest<HTMLElement>('.ag-cell');
      if (event.key === 'Enter' && input.matches('input') && (cell || input.id === filterId)) {
        event.preventDefault();
      }
      if (!cell || !input.matches('input:not([type=checkbox]),textarea') || event.altKey || event.ctrlKey || event.metaKey) return;
      if (!['ArrowUp', 'ArrowDown', 'Enter'].includes(event.key) || input.matches('textarea')) return;
      if (input.matches('input[type=number],input[type=date]') && event.key !== 'Enter') return;
      const currentId = cell.closest('[row-id]')?.getAttribute('row-id');
      const columnId = cell.getAttribute('col-id');
      const current = currentId ? api.current?.getRowNode(currentId) : undefined;
      if (current?.rowIndex == null || !columnId) return;
      const next = current.rowIndex + (event.key === 'ArrowUp' || event.shiftKey ? -1 : 1);
      const node = api.current?.getDisplayedRowAtIndex(next);
      if (!node?.data) return;
      event.preventDefault(); event.stopPropagation();
      api.current?.setFocusedCell(next, columnId);
      renderedCell(getRowId(node.data), columnId)?.querySelector<HTMLElement>('input:not(:disabled)')?.focus();
    }}>
    <div className="w-sheet-toolbar">
      <label className="t-sr-only" htmlFor={filterId}>{label} 현재 페이지 필터</label>
      <input id={filterId} className="t-input" type="search" value={query} maxLength={120}
        placeholder={scope === 'page' ? '현재 페이지에서 찾기' : '목록에서 찾기'} onChange={event => setQuery(event.target.value)} />
      <span className="w-sheet-note">{scope === 'page' ? '정렬·필터는 현재 페이지에 적용됩니다.' : '정렬·필터는 불러온 전체 목록에 적용됩니다.'}</span>
    </div>
    {errors.length > 0 && <nav className="w-sheet-errors" aria-label={`${label} 오류 위치`}>
      {errors.map((error, index) => <button className="t-btn" type="button" key={`${error.clientRowId}-${error.field}-${index}`}
        onClick={() => focusCell(error.clientRowId, error.field)}>
        {(context.index.get(error.clientRowId) ?? 0) + 1}행 · {columns.find(column => (column.colId ?? column.field) === error.field)?.headerName ?? error.field}: {error.message}
      </button>)}
    </nav>}
    <div className="ag-theme-quartz w-sheet-grid" style={{ height: Math.min(480, Math.max(152, rows.length * rowHeight + 64)) }}>
      <AgGridReact<T> theme="legacy" rowData={rows} columnDefs={[...selection, ...viewportColumns]}
        defaultColDef={{ ...defaults, cellClassRules: {
          'w-sheet-cell-error': params => Boolean(params.data && (params.context as SheetContext<T>).errors.some(error => error.clientRowId === getRowId(params.data!) && error.field === params.column.getColId())),
        } }} context={context} getRowId={stableRowId} rowHeight={rowHeight} headerHeight={40}
        rowSelection={selectedIds ? { mode: 'multiRow', checkboxes: false, headerCheckbox: false, enableClickSelection: false,
          isRowSelectable: node => Boolean(node.data && (!isRowSelectable || isRowSelectable(node.data))),
        } : undefined}
        onSelectionChanged={event => {
          if (event.source !== 'api' && onSelectionChange) onSelectionChange(new Set(event.api.getSelectedRows().map(getRowId)));
        }}
        quickFilterText={query} localeText={{ ...localeText, noRowsToShow: rows.length ? '필터와 일치하는 항목이 없습니다.' : emptyMessage }}
        suppressColumnVirtualisation suppressRowVirtualisation suppressAnimationFrame animateRows={false}
        suppressMultiSort enableCellTextSelection ensureDomOrder
        onGridReady={event => { api.current = event.api; event.api.setGridAriaProperty('label', label); updateVisible(); syncSelection(); }}
        onRowDataUpdated={syncSelection}
        onCellFocused={event => {
          if (event.rowIndex == null || !event.column || typeof event.column === 'string') return;
          const node = event.api.getDisplayedRowAtIndex(event.rowIndex);
          if (!node?.data) return;
          const cell = renderedCell(getRowId(node.data), event.column.getColId());
          if (cell?.contains(document.activeElement)) {
            cell.querySelector<HTMLElement>('input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a,button:not(:disabled)')?.focus();
          }
        }}
        onFirstDataRendered={() => {
          updateVisible();
          if (errors.length) focusCell(errors[0].clientRowId, errors[0].field);
        }}
        onModelUpdated={updateVisible}
        getRowClass={({ data }) => {
          const operation = (data as { operation?: string } | undefined)?.operation;
          return operation === 'DEACTIVATE' ? 'w-sheet-row-inactive' : operation === 'CREATE' ? 'w-sheet-row-new' : data && changedIds?.has(getRowId(data)) ? 'w-sheet-row-changed' : '';
        }} />
    </div>
    <div className="w-sheet-footer" role="status">
      <span>표시 {visible.size} / {rows.length}행{selectedIds ? ` · 선택 ${selectedIds.size}행` : ''}{changedIds ? ` · 변경 ${changedIds.size}행` : ''}</span>
      {(hiddenSelected > 0 || hiddenChanged > 0) && <span>숨은 선택 {hiddenSelected}행 · 숨은 변경 {hiddenChanged}행</span>}
    </div>
  </section>;
}
