// @vitest-environment jsdom
import React from 'react';
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { SheetState, sheetStateOf } from './SheetState';

afterEach(cleanup);

describe('SheetState 행 상태', () => {
  it.each([['new', '신규'], ['modified', '수정'], ['deactivate', '비활성 예정']] as const)('%s 상태를 점과 문구로 표시한다', (state, text) => {
    const badge = render(<SheetState state={state} />).container.querySelector('.wl-state')!;
    expect(badge.getAttribute('data-state')).toBe(state);
    expect(badge.textContent).toBe(text);
  });

  it('저장된 행은 상태를 표시하지 않는다', () => {
    expect(render(<SheetState state="saved" />).container.textContent).toBe('');
  });

  it('행 작업과 변경 여부로 상태를 정한다', () => {
    expect(sheetStateOf({ operation: 'CREATE' }, true)).toBe('new');
    expect(sheetStateOf({ id: null, operation: 'UPDATE' }, false)).toBe('new');
    expect(sheetStateOf({ id: 'a', operation: 'DEACTIVATE' }, false)).toBe('deactivate');
    expect(sheetStateOf({ id: 'a', operation: 'UPDATE' }, true)).toBe('modified');
    expect(sheetStateOf({ id: 'a', operation: 'UPDATE' }, false)).toBe('saved');
  });
});
