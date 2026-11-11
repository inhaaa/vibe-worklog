// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { Check, Field, FormRow, FormTable, SelectField, TextAreaField } from './ui';

afterEach(cleanup);

describe('문서형 표', () => {
  it('표 안의 필드는 항목명·값 칸 한 행으로 그리고 라벨 연결과 필수 점을 유지한다', () => {
    render(<FormTable>
      <Field label="이름" name="displayName" required />
      <SelectField label="상위 조직" name="parentId"><option value="">없음</option></SelectField>
      <TextAreaField label="변경 사유" name="reason" required />
    </FormTable>);
    const name = screen.getByLabelText('이름');
    const row = name.closest('.wl-form-row')!;
    expect(row.parentElement?.classList.contains('wl-form-table')).toBe(true);
    expect(row.querySelector('label.wl-form-label')?.className).toContain('wl-required');
    expect(name.parentElement?.classList.contains('wl-form-value')).toBe(true);
    expect(screen.getByLabelText('상위 조직').closest('.wl-form-row')?.querySelector('.wl-required')).toBeNull();
    expect(screen.getByLabelText('변경 사유').tagName).toBe('TEXTAREA');
    expect(document.querySelectorAll('.wl-form-row')).toHaveLength(3);
  });

  it('묶음 행은 항목명으로 이름 붙인 group이고 안의 필드는 칸 안에 그대로 둔다', () => {
    render(<FormTable>
      <FormRow label="재직 기간" required>
        <Field label="입사일" name="joinedOn" type="date" />
        <Field label="마지막 재직일 (선택)" name="leftOn" type="date" />
      </FormRow>
      <FormRow label="상태"><Check name="active" label="활성 사원" checked /></FormRow>
    </FormTable>);
    const period = screen.getByRole('group', { name: '재직 기간' });
    expect(within(period).getByLabelText('입사일').closest('.wl-form-row')).toBe(period);
    expect(period.querySelectorAll('.wl-form-row')).toHaveLength(0);
    expect((screen.getByRole('checkbox', { name: '활성 사원' }) as HTMLInputElement).checked).toBe(true);
    expect(screen.getByRole('group', { name: '상태' })).toBeTruthy();
  });

  it('연결 라벨 행과 2열 표를 지원한다', () => {
    render(<FormTable columns={2}>
      <FormRow label="분류 상태" htmlFor="status" required><select id="status"><option>미분류</option></select></FormRow>
    </FormTable>);
    expect(screen.getByLabelText('분류 상태').tagName).toBe('SELECT');
    expect(document.querySelector('.wl-form-table')?.getAttribute('data-columns')).toBe('2');
    expect(screen.queryByRole('group', { name: '분류 상태' })).toBeNull();
  });

  it('표 밖의 필드는 지금처럼 라벨과 컨트롤만 그린다', () => {
    const { container } = render(<form><Field label="이름" name="displayName" /></form>);
    expect(container.querySelector('form')?.children[0].tagName).toBe('LABEL');
    expect(container.querySelector('.wl-form-row')).toBeNull();
  });
});
