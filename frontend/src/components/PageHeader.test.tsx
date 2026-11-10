// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { PageHeader } from './PageHeader';

afterEach(cleanup);

describe('PageHeader', () => {
  it('shows the title and screen actions without a help button when there is no help', () => {
    render(<PageHeader title="조직 관리" actions={<a href="/company/organizations">조직도</a>} />);
    expect(screen.getByRole('heading', { level: 1, name: '조직 관리' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '조직도' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '도움말' })).toBeNull();
  });

  it('keeps the help text in the document but hidden until the help button opens it', () => {
    render(<PageHeader title="조직 관리" help="여러 행을 편집하고 한 번에 저장하세요." />);
    const button = screen.getByRole('button', { name: '도움말' });
    const panel = screen.getByText('여러 행을 편집하고 한 번에 저장하세요.');
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(button.getAttribute('aria-controls')).toBe(panel.id);
    expect(panel.hidden).toBe(true);
    fireEvent.click(button);
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(panel.hidden).toBe(false);
    fireEvent.click(button);
    expect(panel.hidden).toBe(true);
  });

  it('closes on Escape and returns focus to the help button', () => {
    render(<PageHeader title="조직 관리" help="도움말 내용" />);
    const button = screen.getByRole('button', { name: '도움말' });
    fireEvent.click(button);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.getByText('도움말 내용').hidden).toBe(true);
    expect(document.activeElement).toBe(button);
  });

  it('closes when the pointer goes down outside the header', () => {
    render(<><PageHeader title="조직 관리" help="도움말 내용" /><p>바깥</p></>);
    fireEvent.click(screen.getByRole('button', { name: '도움말' }));
    fireEvent.pointerDown(screen.getByText('바깥'));
    expect(screen.getByText('도움말 내용').hidden).toBe(true);
  });
});
