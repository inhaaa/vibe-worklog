// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { WorkgroupSettings } from './WorkgroupSettings';
const csrf = { token: 't', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' };
afterEach(() => { cleanup(); vi.unstubAllGlobals(); history.replaceState(null, '', '/'); });
it('근무 기준 탭을 이동해도 조회일과 선택한 근무조를 유지하고 날짜 변경 후 이전 결과를 비운다', async () => {
  const firstDate = '2026-11-30', nextDate = '2026-12-01';
  history.replaceState(null, '', '/admin/schedules?tab=groups&date=' + firstDate);
  const reads: string[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    reads.push(url);
    const data = url.includes('/groups?') ? [{ id: 'g1', code: 'G01', name: '주간', version: 1, days: [], revisions: [] }]
      : url.includes('/codes?') ? { codes: [], date: firstDate } : [];
    return new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } });
  }));
  const view = render(<WorkgroupSettings today={firstDate} csrf={csrf} />);
  await view.findByRole('option', { name: /G01/ });
  fireEvent.change(view.getByLabelText('근무조 선택'), { target: { value: 'g1' } });
  fireEvent.click(view.getByRole('button', { name: '사원 배정' }));
  expect((view.getByLabelText('근무조 선택') as HTMLSelectElement).value).toBe('g1');
  expect((view.getByLabelText('조회·적용 기준일') as HTMLInputElement).value).toBe(firstDate);
  fireEvent.change(view.getByLabelText('조회·적용 기준일'), { target: { value: nextDate } });
  await view.findByText('조회된 사원이 없습니다.');
  expect(reads.some(url => url.includes('/employees?') && url.includes(nextDate))).toBe(true);
  fireEvent.click(view.getByRole('button', { name: '근무조' }));
  expect((view.getByLabelText('근무조 선택') as HTMLSelectElement).value).toBe('g1');
  expect((view.getByLabelText('조회·적용 기준일') as HTMLInputElement).value).toBe(nextDate);
});
