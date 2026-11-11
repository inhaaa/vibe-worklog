// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ApprovalStamps } from './ApprovalStamps';

afterEach(cleanup);

describe('결재란', () => {
  it('단계마다 역할·이름·소속·상태를 한 칸에 두고 상태는 색과 문구로 함께 표시한다', () => {
    render(<ApprovalStamps steps={[
      { role: '기안', name: '김하늘', detail: '제품개발팀', state: '작성 중' },
      { role: '결재', name: '박서준', detail: null, state: '결재 대기', tone: 'warn' },
    ]} />);
    const items = within(screen.getByRole('list', { name: '결재란' })).getAllByRole('listitem');
    expect(items.map(item => item.textContent)).toEqual(['기안김하늘제품개발팀작성 중', '결재박서준결재 대기']);
    expect(within(items[0]).getByText('작성 중').className).toBe('t-tag');
    expect(within(items[1]).getByText('결재 대기').className).toBe('t-tag amber');
  });
});
