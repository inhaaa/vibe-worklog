// @vitest-environment jsdom
import { cleanup, createEvent, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EmployeeImportPage } from './EmployeeImportPage';

const csrf = { token: 't', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const JSON_HEADERS = { 'Content-Type': 'application/json' };
const model = { organizations: [{ id: 'o1', code: 'D10', name: '개발팀' }], maxRows: 500 };

function paste(text: string) {
  const event = createEvent.paste(screen.getByLabelText('엑셀에서 복사한 행 붙여넣기'));
  Object.defineProperty(event, 'clipboardData', { value: { getData: () => text } });
  fireEvent(screen.getByLabelText('엑셀에서 복사한 행 붙여넣기'), event);
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('EmployeeImportPage', () => {
  it('keeps the organization code list folded into a table', () => {
    render(<EmployeeImportPage model={model} csrf={csrf} />);
    expect(screen.getByText('조직코드 보기 (1개)')).toBeTruthy();
    expect(screen.getByRole('table', { name: '조직코드', hidden: true }).textContent).toContain('D10개발팀');
  });

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
      .mockResolvedValueOnce(new Response(JSON.stringify({ valid: true, rows: [{ clientRowId: 'x', employeeNo: '19001', errors: [] }] }), { status: 200, headers: JSON_HEADERS }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ batchId: 'b', employees: 1, accounts: 1, employeeNos: ['19001'] }), { status: 200, headers: JSON_HEADERS }));
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

  const ROW = '\t홍길동\t2019-03-04\t1990-05-12\tD10\t\t\r\n';
  const clean = () => new Response(JSON.stringify({ valid: true, rows: [{ clientRowId: 'x', employeeNo: '19001', errors: [] }] }), { status: 200, headers: JSON_HEADERS });
  const reasoned = async () => {
    fireEvent.click(screen.getByRole('button', { name: '검증' }));
    await screen.findByText('모든 행 정상');
    fireEvent.change(screen.getByLabelText('등록 사유'), { target: { value: '이관' } });
  };

  it('disables save when a cell is edited after validation', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(clean());
    render(<EmployeeImportPage model={model} csrf={csrf} />);
    paste(ROW);
    await reasoned();
    expect((screen.getByRole('button', { name: '저장' }) as HTMLButtonElement).disabled).toBe(false);
    const cell = await screen.findByLabelText('이름 1');
    fireEvent.keyDown(cell, { key: 'F2' });
    fireEvent.change(await screen.findByDisplayValue('홍길동'), { target: { value: '김철수' } });
    await waitFor(() => expect((screen.getByRole('button', { name: '저장' }) as HTMLButtonElement).disabled).toBe(true));
  });

  it('puts row errors back on a 422 save and sends command id, reason and CSRF header', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(clean()).mockResolvedValueOnce(new Response(JSON.stringify({
      valid: false, rows: [{ clientRowId: 'x', employeeNo: null, errors: [{ field: 'employeeNo', message: '이미 등록된 사번입니다.' }] }],
    }), { status: 422, headers: JSON_HEADERS }));
    render(<EmployeeImportPage model={model} csrf={csrf} />);
    paste(ROW);
    await reasoned();
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(await screen.findByText('오류 1행')).toBeTruthy();
    const [, init] = fetchMock.mock.calls[1];
    const request = init as RequestInit;
    expect((request.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('t');
    expect(JSON.parse(String(request.body))).toMatchObject({ reason: '이관', commandId: expect.any(String) });
  });

  it('limits pasted rows and ignores an empty paste', async () => {
    render(<EmployeeImportPage model={{ ...model, maxRows: 2 }} csrf={csrf} />);
    paste(ROW.repeat(3));
    expect(await screen.findByText('한 번에 2행까지 등록할 수 있습니다.')).toBeTruthy();
    paste(ROW);
    paste('  \r\n');
    expect(document.querySelector('.wl-count')?.textContent).toBe('총 1건');
  });

  it('shows the login message on 403 without offering a retry', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('', { status: 403 }));
    render(<EmployeeImportPage model={model} csrf={csrf} />);
    paste(ROW);
    fireEvent.click(screen.getByRole('button', { name: '검증' }));
    expect(await screen.findByText('로그인 상태와 권한을 다시 확인하세요.')).toBeTruthy();
    expect(screen.getByRole('link', { name: '다시 로그인' }).getAttribute('href')).toBe('/login');
    expect(screen.queryByRole('button', { name: '저장' })).toBeNull();
  });

  it('keeps the command id on retry and issues a new one when the pasted content changes', async () => {
    const ok = () => new Response(JSON.stringify({ valid: true, rows: [{ clientRowId: 'x', employeeNo: '19001', errors: [] }] }), { status: 200, headers: JSON_HEADERS });
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
