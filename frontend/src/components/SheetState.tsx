import type { ColDef } from 'ag-grid-community';
import { sheetColumn } from './WorklogSheet';

export type SheetStateKind = 'saved' | 'new' | 'modified' | 'deactivate';
const text: Record<SheetStateKind, string> = { saved: '', new: '신규', modified: '수정', deactivate: '비활성 예정' };

/** 저장 전 행 상태. 저장된 행은 표시하지 않는다. */
export function sheetStateOf(row: { id?: string | null; operation: string }, modified: boolean): SheetStateKind {
  if (row.operation === 'DEACTIVATE') return 'deactivate';
  if (!row.id || row.operation === 'CREATE') return 'new';
  return modified ? 'modified' : 'saved';
}

export function SheetState({ state }: { state: SheetStateKind }) {
  return state === 'saved' ? null : <span className="wl-state" data-state={state}>{text[state]}</span>;
}

/** 선택 칸 다음에 고정하는 공통 상태 열. 정렬·찾기 값도 같은 문구를 쓴다. */
export function sheetStateColumn<T>(stateOf: (row: T) => SheetStateKind): ColDef<T> {
  return sheetColumn<T>({
    colId: 'state', headerName: '상태', width: 104, minWidth: 104, maxWidth: 120, flex: 0, pinned: 'left', cellClass: 'w-sheet-center',
    valueGetter: ({ data }) => data ? text[stateOf(data)] : '',
  }, row => <SheetState state={stateOf(row)} />);
}
