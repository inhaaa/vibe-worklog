import { useCallback, useContext, useEffect, useId, useImperativeHandle, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode, Ref } from 'react';
import { flushSync } from 'react-dom';
import { AgGridReact } from 'ag-grid-react';
import { AllCommunityModule, ModuleRegistry } from 'ag-grid-community';
import type { CellClassParams, ColDef, GridApi, ICellRendererParams } from 'ag-grid-community';
import { SheetCellEditor, SheetEditorFields, validateSheetField } from './SheetCellEditor';
import type { SheetEditorField } from './SheetCellEditor';
export type { SheetEditorField } from './SheetCellEditor';

ModuleRegistry.registerModules([AllCommunityModule]);

interface SheetContext<T> { index: Map<string, number>; getRowId: (row: T) => string; errors: SheetFieldError[] }
function textAlignment(text: string) {
  const value = text.trim();
  return /[\r\n]/.test(text) || /[.!?。！？]$/.test(value) || value.split(/\s+/).length >= 4 ? 'w-sheet-left' : 'w-sheet-center';
}
function renderedText(element: HTMLElement) {
  const parts: string[] = [];
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
  let node;
  while ((node = walker.nextNode())) parts.push(node.textContent ?? '');
  return parts.join(' ').replace(/\s+/g, ' ').trim();
}
function numericCell<T>(params: CellClassParams<T>) {
  const cellClass = typeof params.colDef.cellClass === 'function' ? params.colDef.cellClass(params) : params.colDef.cellClass;
  const classes = Array.isArray(cellClass) ? cellClass : cellClass?.split(/\s+/) ?? [];
  const types = Array.isArray(params.colDef.type) ? params.colDef.type : [params.colDef.type];
  return typeof params.value === 'number' || fieldFor(params.colDef, params.data, params.context)?.type === 'number'
    || classes.includes('w-sheet-number') || types.includes('numericColumn');
}
function cellText<T>(params: CellClassParams<T>) {
  return String(params.api.getCellValue({ rowNode: params.node, colKey: params.column, useFormatter: true }) ?? '');
}
function RenderCell<T>({ data, context, render }: ICellRendererParams<T> & {
  render: (row: T, index: number) => ReactNode;
}) {
  const element = useRef<HTMLSpanElement>(null);
  const [text, setText] = useState('');
  useLayoutEffect(() => {
    const next = element.current ? renderedText(element.current) : '';
    if (next !== text) setText(next);
  });
  if (!data) return null;
  const sheet = context as SheetContext<T>;
  const content = render(data, sheet.index.get(sheet.getRowId(data)) ?? 0);
  return <span ref={element} className={`w-sheet-content ${textAlignment(text)}`} title={text || undefined}>{content}</span>;
}

/** Keep the renderer component stable while page-owned controlled inputs receive fresh props. */
export function sheetColumn<T>(column: ColDef<T>, render: (row: T, index: number) => ReactNode): ColDef<T> {
  return { ...column, cellRenderer: RenderCell<T>, cellRendererParams: { render } };
}

type EditorField<T> = (row: T, index: number) => SheetEditorField;
function editorField<T>(column: ColDef<T>): EditorField<T> | undefined { return column.context?.sheetEditorField; }
function fieldKey(rowId: string, columnId: string) { return JSON.stringify([rowId, columnId]); }
function fieldFor<T>(column: ColDef<T>, data: T | undefined, context: SheetContext<T>) {
  if (!data) return;
  const id = context.getRowId(data);
  const field = editorField(column)?.(data, context.index.get(id) ?? 0);
  if (!field) return;
  const error = context.errors.find(item => item.clientRowId === id && item.field === String(column.colId ?? column.field))?.message ?? field.error;
  return error === field.error ? field : { ...field, error };
}
function displayField(field?: SheetEditorField) { return field?.options?.find(([key]) => key === field.value)?.[1] ?? field?.value ?? ''; }

function EditorCell<T>({ data, colDef, context }: ICellRendererParams<T>) {
  const fields = useContext(SheetEditorFields);
  const errorId = useId();
  const sheet = context as SheetContext<T>;
  const field = data && colDef ? fields?.get(fieldKey(sheet.getRowId(data), String(colDef.colId ?? colDef.field))) ?? fieldFor(colDef, data, sheet) : undefined;
  if (!field) return null;
  return <><span className={`w-sheet-value${field.disabled || field.readOnly ? ' w-sheet-readonly' : ' w-sheet-editable'}`}
    aria-label={field.label} aria-readonly={Boolean(field.readOnly)} aria-disabled={Boolean(field.disabled)}
    aria-invalid={Boolean(field.error)} aria-describedby={field.error ? errorId : undefined}
    data-changed={String(Boolean(field.changed))} title={field.error ?? displayField(field)}>{displayField(field) || '—'}</span>
    {field.error && <span id={errorId} className="t-sr-only">{field.error}</span>}</>;
}

export function sheetEditorColumn<T>(column: ColDef<T>, field: EditorField<T>): ColDef<T> {
  return {
    ...column, context: { ...column.context, sheetEditorField: field }, cellDataType: false,
    editable: params => {
      const item = fieldFor(params.colDef, params.data, params.context);
      return Boolean(item && !item.disabled && !item.readOnly && document.getElementById('tna-app')?.getAttribute('aria-busy') !== 'true');
    },
    valueGetter: params => fieldFor(params.colDef, params.data, params.context)?.value ?? '',
    valueFormatter: params => displayField(fieldFor(params.colDef, params.data, params.context)),
    getQuickFilterText: params => displayField(fieldFor(params.colDef, params.data, params.context)),
    comparator: column.comparator ?? ((left, right, nodeA, nodeB) => {
      const leftField = nodeA.data && field(nodeA.data, 0);
      const rightField = nodeB.data && field(nodeB.data, 0);
      if (leftField?.type === 'number') return Number(left) - Number(right);
      const a = leftField ? displayField({ ...leftField, value: String(left ?? '') }) : String(left ?? '');
      const b = rightField ? displayField({ ...rightField, value: String(right ?? '') }) : String(right ?? '');
      return a === b ? 0 : a > b ? 1 : -1;
    }),
    filterValueGetter: params => {
      const item = fieldFor(params.colDef, params.data, params.context);
      return item?.type === 'number' ? item.value : displayField(item);
    },
    cellEditor: SheetCellEditor<T>,
    cellEditorParams: (params: ICellRendererParams<T>) => ({ field: fieldFor(params.colDef!, params.data, params.context),
      fieldKey: fieldKey((params.context as SheetContext<T>).getRowId(params.data!), String(params.colDef!.colId ?? params.colDef!.field)),
    }),
    cellRenderer: EditorCell<T>,
  };
}

export interface WorklogSheetHandle { focusCell: (rowId: string, field?: string) => void; validate: () => boolean }
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
  wrapText: false, autoHeight: false,
  tooltipValueGetter: params => params.valueFormatted ?? String(params.value ?? ''),
  suppressKeyboardEvent: ({ event, editing }) => {
    if (event.isComposing || event.keyCode === 229) return true;
    if (editing) return !['Tab', 'Enter', 'Escape'].includes(event.key);
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
  const [validationErrors, setValidationErrors] = useState<SheetFieldError[]>([]);
  const [narrowViewport, setNarrowViewport] = useState(() => typeof window !== 'undefined' && window.innerWidth < 600);
  useEffect(() => {
    const resize = () => setNarrowViewport(window.innerWidth < 600);
    window.addEventListener('resize', resize);
    return () => window.removeEventListener('resize', resize);
  }, []);
  const viewportColumns = columns.map(column => ({ ...column, wrapText: false, autoHeight: false,
    ...(narrowViewport ? { pinned: null } : {}),
  }));
  const [visible, setVisible] = useState(() => new Set(rows.map(getRowId)));
  const allErrors = [...errors, ...validationErrors];
  const context = useMemo<SheetContext<T>>(() => ({ index: new Map(rows.map((row, index) => [getRowId(row), index])), getRowId, errors: allErrors }), [rows, getRowId, errors, validationErrors]);
  const editorFields = useMemo(() => {
    const fields = new Map<string, SheetEditorField>();
    rows.forEach(row => columns.forEach(column => {
      const field = fieldFor(column, row, context);
      if (field) fields.set(fieldKey(getRowId(row), String(column.colId ?? column.field)), field);
    }));
    return fields;
  }, [rows, columns, context, getRowId]);
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
    if (editorField(column.getColDef()) && column.isCellEditable(node)) {
      grid.startEditingCell({ rowIndex: node.rowIndex, colKey: column });
    }
    const focusInput = () => {
      const cell = renderedCell(id, column.getColId());
      (cell?.querySelector<HTMLElement>('input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a,button:not(:disabled)') ?? cell)?.focus();
    };
    focusInput();
    setTimeout(focusInput, 0);
  }, []);
  function validate() {
    const next: SheetFieldError[] = [];
    rows.forEach((row, index) => columns.forEach(column => {
      if (changedIds && !changedIds.has(getRowId(row))) return;
      const field = editorField(column)?.(row, index);
      const message = field && validateSheetField(field);
      if (message) next.push({ clientRowId: getRowId(row), field: String(column.colId ?? column.field), message });
    }));
    setValidationErrors(next);
    if (next.length) focusCell(next[0].clientRowId, next[0].field);
    return next.length === 0;
  }
  useImperativeHandle(ref, () => ({ focusCell, validate }));
  useEffect(() => {
    setValidationErrors(current => {
      const next = current.flatMap(error => {
        if (changedIds && !changedIds.has(error.clientRowId)) return [];
        const index = rows.findIndex(item => getRowId(item) === error.clientRowId);
        const row = rows[index];
        const column = columns.find(item => String(item.colId ?? item.field) === error.field);
        const field = row && column ? editorField(column)?.(row, index) : undefined;
        const message = field && validateSheetField(field);
        return message ? [message === error.message ? error : { ...error, message }] : [];
      });
      return next.length === current.length && next.every((error, index) => error === current[index]) ? current : next;
    });
  }, [rows, columns, getRowId, changedIds]);

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
      if (cell?.classList.contains('ag-cell-inline-editing')) return;
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
    {allErrors.length > 0 && <nav className="w-sheet-errors" aria-label={`${label} 오류 위치`}>
      {allErrors.map((error, index) => <button className="t-btn" type="button" key={`${error.clientRowId}-${error.field}-${index}`}
        onClick={() => focusCell(error.clientRowId, error.field)}>
        {(context.index.get(error.clientRowId) ?? 0) + 1}행 · {columns.find(column => (column.colId ?? column.field) === error.field)?.headerName ?? error.field}: {error.message}
      </button>)}
    </nav>}
    <div className="ag-theme-quartz w-sheet-grid" style={{ height: Math.min(480, Math.max(152, rows.length * rowHeight + 64)) }}>
      <SheetEditorFields.Provider value={editorFields}>
      <AgGridReact<T> theme="legacy" rowData={rows} columnDefs={[...selection, ...viewportColumns]}
        defaultColDef={{ ...defaults, cellClassRules: {
          'w-sheet-number': params => numericCell(params),
          'w-sheet-left': params => !numericCell(params) && textAlignment(cellText(params)) === 'w-sheet-left',
          'w-sheet-center': params => !numericCell(params) && textAlignment(cellText(params)) === 'w-sheet-center',
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
        suppressMultiSort enableCellTextSelection enableBrowserTooltips ensureDomOrder singleClickEdit readOnlyEdit
        stopEditingWhenCellsLoseFocus enterNavigatesVerticallyAfterEdit
        onCellEditRequest={event => {
          const field = fieldFor(event.colDef, event.data, event.context);
          const value = String(event.newValue ?? '');
          if (field && !field.disabled && !field.readOnly && field.value !== value) flushSync(() => field.onChange(value));
        }}
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
      </SheetEditorFields.Provider>
    </div>
    <div className="w-sheet-footer" role="status">
      <span>표시 {visible.size} / {rows.length}행{selectedIds ? ` · 선택 ${selectedIds.size}행` : ''}{changedIds ? ` · 변경 ${changedIds.size}행` : ''}</span>
      {(hiddenSelected > 0 || hiddenChanged > 0) && <span>숨은 선택 {hiddenSelected}행 · 숨은 변경 {hiddenChanged}행</span>}
    </div>
  </section>;
}
