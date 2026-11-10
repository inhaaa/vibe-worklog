// @vitest-environment jsdom
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { SectionHeader } from './SectionHeader';

afterEach(cleanup);

describe('SectionHeader 구역 머리', () => {
  it('제목·총 건수·구역 동작을 한 줄에 둔다', () => {
    const { container } = render(<SectionHeader title="조직 목록" count={1234} actions={<button type="button">행 추가</button>} />);
    expect(screen.getByRole('heading', { level: 2, name: '조직 목록' })).toBeTruthy();
    expect(container.querySelector('.wl-count')?.textContent).toBe('총 1,234건');
    expect(screen.getByRole('button', { name: '행 추가' }).closest('.wl-section-actions')).toBeTruthy();
  });

  it('제목 없이 건수만 둘 수 있다', () => {
    const { container } = render(<SectionHeader count={0} />);
    expect(container.querySelector('h2')).toBeNull();
    expect(container.textContent).toBe('총 0건');
  });
});
