// @vitest-environment jsdom
import React, { useState } from 'react';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { RequestBreakEditor } from './RequestBreakEditor';

afterEach(cleanup);
function Editor({ initial = '', disabled = false }: { initial?: string; disabled?: boolean }) {
  const [value, setValue] = useState(initial);
  return <form><RequestBreakEditor value={value} onChange={setValue} disabled={disabled} /></form>;
}
describe('실제 휴게 행 편집', () => {
  it('시작·종료 행을 추가하고 서버의 쉼표·슬래시 형식으로 직렬화한다', () => {
    const view = render(<Editor />);
    fireEvent.click(view.getByRole('button', { name: '휴게 추가' }));
    fireEvent.change(view.getByLabelText('휴게 1 시작'), { target: { value: '2026-11-02T12:00' } });
    fireEvent.change(view.getByLabelText('휴게 1 종료'), { target: { value: '2026-11-02T13:00' } });
    fireEvent.click(view.getByRole('button', { name: '휴게 추가' }));
    fireEvent.change(view.getByLabelText('휴게 2 시작'), { target: { value: '2026-11-02T15:00' } });
    fireEvent.change(view.getByLabelText('휴게 2 종료'), { target: { value: '2026-11-02T15:10' } });
    const form = view.container.querySelector('form')!;
    expect(new FormData(form).get('actualBreaks')).toBe('2026-11-02T12:00/2026-11-02T13:00,2026-11-02T15:00/2026-11-02T15:10');
    fireEvent.click(view.getByRole('button', { name: '휴게 1 제거' }));
    expect(new FormData(form).get('actualBreaks')).toBe('2026-11-02T15:00/2026-11-02T15:10');
  });
  it('빈 구간과 종료가 시작보다 이른 구간은 미리보기 전에 유효성 검증한다', () => {
    const view = render(<Editor initial="2026-11-02T13:00/2026-11-02T12:00" />);
    const form = view.container.querySelector('form')!;
    expect(form.checkValidity()).toBe(false);
    fireEvent.change(view.getByLabelText('휴게 1 종료'), { target: { value: '2026-11-02T14:00' } });
    expect(form.checkValidity()).toBe(true);
    fireEvent.click(view.getByRole('button', { name: '휴게 추가' }));
    expect(form.checkValidity()).toBe(false);
  });
  it('잘못된 기존 값과 빈 조각을 원문 그대로 남기고 명시적 수정·제거를 요구한다', () => {
    const original = '2026-11-02T12:00/2026-11-02T13:00,잘못된 값,';
    const view = render(<Editor initial={original} />);
    const form = view.container.querySelector('form')!;
    expect(new FormData(form).get('actualBreaks')).toBe(original);
    expect((view.getByLabelText('휴게 2 원본 입력') as HTMLInputElement).value).toBe('잘못된 값');
    expect(view.getAllByText(/기존 입력을 보존했습니다/)).toHaveLength(2);
    expect(form.checkValidity()).toBe(false);
    fireEvent.change(view.getByLabelText('휴게 2 원본 입력'), { target: { value: '2026-11-02T15:00/2026-11-02T15:10' } });
    fireEvent.click(view.getByRole('button', { name: '휴게 3 제거' }));
    expect(form.checkValidity()).toBe(true);
  });
  it('지원하지 않는 날짜·시간 형식은 빈 datetime 입력으로 바꾸지 않는다', () => {
    const original = '2026-02-30T12:00/2026-02-30T13:00';
    const view = render(<Editor initial={original} disabled />);
    expect((view.getByLabelText('휴게 1 원본 입력') as HTMLInputElement).value).toBe(original);
    expect(view.getByRole('button', { name: '휴게 추가' }).hasAttribute('disabled')).toBe(true);
  });
  it('휴게 입력은 초 단위이고 라벨에 서울 표기를 붙이지 않는다', () => {
    const view = render(<RequestBreakEditor value="2026-11-04T12:00:30.125/2026-11-04T13:00" onChange={() => {}} />);
    const start = view.getByLabelText('휴게 1 시작') as HTMLInputElement;
    expect(start.getAttribute('step')).toBe('1');
    expect(start.getAttribute('value')).toBe('2026-11-04T12:00:30');
  });
});
