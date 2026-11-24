import { describe, expect, it } from 'vitest';
import { parseClipboardTable, toImportDrafts } from './clipboardTable';

describe('parseClipboardTable', () => {
  it('splits tabs and CRLF rows and drops trailing blank lines', () => {
    expect(parseClipboardTable('a\tb\r\nc\td\r\n\r\n')).toEqual([['a', 'b'], ['c', 'd']]);
  });
  it('keeps tabs, newlines and doubled quotes inside quoted cells', () => {
    expect(parseClipboardTable('"x\ty"\t"line1\nline2"\t"say ""hi"""\n')).toEqual([['x\ty', 'line1\nline2', 'say "hi"']]);
  });
  it('reads a quote that never closes as plain text', () => {
    expect(parseClipboardTable('\t"홍길동\t2019-03-04\r\n19001\t김\t2020-01-02')).toEqual([['', '"홍길동', '2019-03-04'], ['19001', '김', '2020-01-02']]);
  });
  it('keeps empty middle cells', () => {
    expect(parseClipboardTable('1\t\t3')).toEqual([['1', '', '3']]);
  });
});

describe('toImportDrafts', () => {
  it('maps seven columns, pads missing ones and gives each row an id', () => {
    const drafts = toImportDrafts([['19001', '홍길동', '2019-03-04', '1990-05-12', 'D10'], ['', '김', '2020.01.02', '19910203', 'D10', '대리', '팀장', '남는 열']]);
    expect(drafts.map(({ clientRowId, ...rest }) => rest)).toEqual([
      { employeeNo: '19001', displayName: '홍길동', joinedOn: '2019-03-04', birthDate: '1990-05-12', organizationCode: 'D10', gradeName: '', positionName: '' },
      { employeeNo: '', displayName: '김', joinedOn: '2020.01.02', birthDate: '19910203', organizationCode: 'D10', gradeName: '대리', positionName: '팀장' },
    ]);
    expect(new Set(drafts.map(d => d.clientRowId)).size).toBe(2);
  });
});
