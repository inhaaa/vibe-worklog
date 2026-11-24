// @vitest-environment jsdom
import { cleanup, createEvent, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EmployeeImportPage } from './EmployeeImportPage';

const csrf = { token: 't', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const model = { organizations: [{ id: 'o1', code: 'D10', name: '개발팀' }], maxRows: 500 };

function paste(text: string) {
  const event = createEvent.paste(screen.getByLabelText('엑셀에서 복사한 행 붙여넣기'));
  Object.defineProperty(event, 'clipboardData', { value: { getData: () => text } });
  fireEvent(screen.getByLabelText('엑셀에서 복사한 행 붙여넣기'), event);
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('EmployeeImportPage', () => {
  it('turns pasted rows into sheet rows and shows server errors per cell', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      valid: false, rows: [{ clientRowId: 'x', employeeNo: '19001', errors: [{ field: 'birthDate', message: '생년월일은 입사일 이전이어야 합니다.' }] }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const { container } = render(<EmployeeImportPage model={model} csrf={csrf} />);
    paste('\t홍길동\t2019-03-04\t2019-03-04\tD10\t\t\r\n');
    expect(container.querySelector('.wl-count')?.textContent).toBe('총 1건');
    fireEvent.click(screen.getByRole('button', { name: '검증' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/admin/employees/import/preview', expect.objectContaining({ method: 'POST' })));
    expect(await screen.findByText('오류 1행')).toBeTruthy();
    expect((screen.getByRole('button', { name: '저장' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('enables save only after a clean preview and a reason, then shows next steps', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify({ valid: true, rows: [{ clientRowId: 'x', employeeNo: '19001', errors: [] }] }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ batchId: 'b', employees: 1, accounts: 1, employeeNos: ['19001'] }), { status: 200 }));
    render(<EmployeeImportPage model={model} csrf={csrf} />);
    paste('\t홍길동\t2019-03-04\t1990-05-12\tD10\t\t\r\n');
    fireEvent.click(screen.getByRole('button', { name: '검증' }));
    await screen.findByText('모든 행 정상');
    fireEvent.change(screen.getByLabelText('등록 사유'), { target: { value: '기존 재직자 이관' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(await screen.findByText('등록 1명 · 계정 1개')).toBeTruthy();
    expect(screen.getByRole('link', { name: '사용자 관리' }).getAttribute('href')).toBe('/admin/accounts');
    expect(screen.getByRole('link', { name: '근무 설정' }).getAttribute('href')).toBe('/admin/schedules');
    expect(screen.getByRole('link', { name: '연차 관리' }).getAttribute('href')).toBe('/admin/leave-balances');
  });

  it('keeps the command id on retry and issues a new one when the pasted content changes', async () => {
    const ok = () => new Response(JSON.stringify({ valid: true, rows: [{ clientRowId: 'x', employeeNo: '19001', errors: [] }] }), { status: 200 });
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(ok())
      .mockRejectedValueOnce(new Error('network'))
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce(ok())
      .mockRejectedValueOnce(new Error('network'));
    render(<EmployeeImportPage model={model} csrf={csrf} />);
    const commandIds = () => fetchMock.mock.calls.filter(([url]) => url === '/admin/employees/import/save')
      .map(([, init]) => JSON.parse(String((init as RequestInit).body)).commandId);
    paste('\t홍길동\t2019-03-04\t1990-05-12\tD10\t\t\r\n');
    fireEvent.click(screen.getByRole('button', { name: '검증' }));
    await screen.findByText('모든 행 정상');
    fireEvent.change(screen.getByLabelText('등록 사유'), { target: { value: '이관' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(commandIds()).toHaveLength(1));
    await screen.findByText(/저장 결과를 확인하지 못했습니다/);
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(commandIds()).toHaveLength(2));
    paste('\t홍길동\t2019-03-04\t1991-05-12\tD10\t\t\r\n');
    fireEvent.click(screen.getByRole('button', { name: '검증' }));
    await screen.findByText('모든 행 정상');
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(commandIds()).toHaveLength(3));
    const [first, retry, changed] = commandIds();
    expect(retry).toBe(first);
    expect(changed).not.toBe(first);
  });
});
