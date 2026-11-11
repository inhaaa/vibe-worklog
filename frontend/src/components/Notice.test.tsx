// @vitest-environment jsdom
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { Notice } from './Notice';

afterEach(cleanup);

describe('안내', () => {
  it('아이콘은 읽지 않고 문장만 읽는다', () => {
    const { container } = render(<Notice>승인되면 잔여 연차에서 차감됩니다.</Notice>);
    expect(container.querySelector('p.wl-notice svg')?.getAttribute('aria-hidden')).toBe('true');
    expect(container.textContent).toBe('승인되면 잔여 연차에서 차감됩니다.');
  });
});
