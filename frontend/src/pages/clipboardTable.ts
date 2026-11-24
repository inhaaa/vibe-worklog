export interface ImportDraft {
  clientRowId: string;
  employeeNo: string;
  displayName: string;
  joinedOn: string;
  birthDate: string;
  organizationCode: string;
  gradeName: string;
  positionName: string;
}

export const IMPORT_FIELDS = ['employeeNo', 'displayName', 'joinedOn', 'birthDate', 'organizationCode', 'gradeName', 'positionName'] as const;

/** 엑셀이 클립보드에 넣는 TSV를 행·열로 나눈다. 따옴표 셀 안의 탭·줄바꿈과 "" 이스케이프를 지킨다. */
export function parseClipboardTable(text: string): string[][] {
  // 닫히지 않은 따옴표(엑셀이 `"홍길동`처럼 복사한 셀)는 그 따옴표부터 일반 글자로 다시 읽는다.
  const literal = new Set<number>();
  for (;;) {
    const { rows, unclosedAt } = parseOnce(text, literal);
    if (unclosedAt === null) return rows;
    literal.add(unclosedAt);
  }
}

function parseOnce(text: string, literal: Set<number>): { rows: string[][]; unclosedAt: number | null } {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  let openedAt = -1;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c !== '"') cell += c;
      else if (text[i + 1] === '"') { cell += '"'; i++; }
      else quoted = false;
    } else if (c === '"' && cell === '' && !literal.has(i)) {
      quoted = true; openedAt = i;
    } else if (c === '\t') {
      row.push(cell); cell = '';
    } else if (c === '\r' || c === '\n') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(cell); rows.push(row); row = []; cell = '';
    } else {
      cell += c;
    }
  }
  if (quoted) return { rows, unclosedAt: openedAt };
  if (cell !== '' || row.length > 0) { row.push(cell); rows.push(row); }
  return { rows: rows.filter(cells => cells.some(value => value.trim() !== '')), unclosedAt: null };
}

export function toImportDrafts(table: string[][]): ImportDraft[] {
  return table.map(cells => {
    const draft = { clientRowId: crypto.randomUUID() } as ImportDraft;
    IMPORT_FIELDS.forEach((field, index) => { draft[field] = cells[index] ?? ''; });
    return draft;
  });
}
