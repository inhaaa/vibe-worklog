// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { PersonnelPage, type PersonnelModel } from './PersonnelPage';
import { CsrfContext } from '../ui';

const model: PersonnelModel = {
  employeeId: 'e-1', version: 3, today: '2026-11-02', statusHistory: [],
  form: { birthDate: '1990-01-01', gradeName: '선임', positionName: '팀원', jobName: '개발', employmentStatus: 'WORKING',
    effectiveFrom: '2026-11-02', expectedVersion: 3, reason: '', commandId: 'command-1', photoAction: 'KEEP', photoData: '' },
};
function show(personnel = model, error?: string) {
  return render(<CsrfContext value={{ token: 'csrf-1', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' }}>
    <PersonnelPage page="org/personnel" data={{ personnel, error }} csrf={{ token: 'csrf-1', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' }} reload={() => {}} />
  </CsrfContext>);
}
afterEach(cleanup);

it('실제 등록 폼에 선택 인사정보와 버전·CSRF·명령을 제출한다', () => {
  const view = show();
  const form = view.container.querySelector('form')!;
  expect(form.getAttribute('action')).toBe('/admin/employees/e-1/personnel');
  expect(new FormData(form).get('_csrf')).toBe('csrf-1');
  expect(new FormData(form).get('expectedVersion')).toBe('3');
  expect(new FormData(form).get('commandId')).toBe('command-1');
  expect((screen.getByLabelText('생년월일') as HTMLInputElement).value).toBe('1990-01-01');
  fireEvent.change(screen.getByLabelText('직급'), { target: { value: '책임' } });
  expect(new FormData(form).get('gradeName')).toBe('책임');
  expect(form.getAttribute('data-unsaved-changes')).toBe('true');
  expect((screen.getByLabelText('상태 적용일') as HTMLInputElement).min).toBe('2026-11-02');
});

it('충돌한 입력을 보존하고 최신 버전은 명시적 확인 후 사용한다', () => {
  const view = show({ ...model, version: 5 }, '다른 변경이 반영되었습니다.');
  const form = view.container.querySelector('form')!;
  expect(new FormData(form).get('expectedVersion')).toBe('3');
  expect((screen.getByLabelText('직급') as HTMLInputElement).value).toBe('선임');
  expect((screen.getByRole('button', { name: '인사정보 저장' }) as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: '최신 버전 확인 후 입력 유지' }));
  expect(new FormData(form).get('expectedVersion')).toBe('5');
  expect(new FormData(form).get('commandId')).not.toBe('command-1');
});

it('사진 파일은 직접 폼에 넣지 않고 허용한 이미지 문자열로 전송한다', async () => {
  const view = show();
  const form = view.container.querySelector('form')!;
  const file = screen.getByLabelText('사원사진 선택') as HTMLInputElement;
  expect(file.name).toBe('');
  fireEvent.change(file, { target: { files: [new File([new Uint8Array([1, 2, 3])], 'person.png', { type: 'image/png' })] } });
  await waitFor(() => expect(new FormData(form).get('photoAction')).toBe('REPLACE'));
  expect(new FormData(form).get('photoData')).toBe('AQID');
  fireEvent.click(screen.getByRole('button', { name: '등록된 사진 지우기' }));
  expect(new FormData(form).get('photoAction')).toBe('CLEAR');
  expect(new FormData(form).get('photoData')).toBe('');
});

it('허용하지 않는 파일은 저장을 막고 기존 인사 입력은 유지한다', async () => {
  show();
  fireEvent.change(screen.getByLabelText('사원사진 선택'), { target: { files: [new File(['<svg/>'], 'bad.svg', { type: 'image/svg+xml' })] } });
  await screen.findByRole('alert');
  expect(screen.getByRole('alert').textContent).toContain('PNG');
  expect((screen.getByLabelText('직급') as HTMLInputElement).value).toBe('선임');
  expect((screen.getByRole('button', { name: '인사정보 저장' }) as HTMLButtonElement).disabled).toBe(true);
});
