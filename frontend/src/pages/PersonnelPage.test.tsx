// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from '../App';
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
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const details = { employeeId: 'e-1', version: 0, form: { expectedVersion: 0, commandId: 'details-1' }, entries: [], appointments: [],
  entryForm: { kind: 'FAMILY', title: '', detail: '', effectiveFrom: '2026-11-02', expectedVersion: 0, reason: '', commandId: 'entry-1' } };
const forms = ['personnel', 'personal-details', 'personal-entries'];
const labels = ['직급', '영문성명', '항목명'];
function showAll(onSubmit = vi.fn()) {
  return { onSubmit, ...render(<div onSubmit={onSubmit}><PersonnelPage page="org/personnel" data={{ personnel: model, personalDetails: details }} csrf={{ token: '', parameterName: '_csrf', headerName: '' }} reload={() => {}} /></div>) };
}
it.each([[0, 1], [0, 2], [1, 0], [1, 2], [2, 0], [2, 1]])('다른 폼 %i의 draft가 있으면 폼 %i 저장 요청을 막고 입력을 유지한다', (dirty, submitted) => {
  const view = showAll();
  fireEvent.change(view.getByLabelText(labels[dirty]), { target: { value: '보존할 입력' } });
  const form = view.container.querySelector(`form[action$="/${forms[submitted]}"]`)!;
  fireEvent.submit(form);
  expect(view.onSubmit).not.toHaveBeenCalled();
  expect(view.getByRole('alert').textContent).toContain('먼저 저장하거나 입력을 취소');
  expect((view.getByLabelText(labels[dirty]) as HTMLInputElement).value).toBe('보존할 입력');
});

it.each(labels)('%s만 변경해도 전체 새로고침·브라우저 이탈을 확인한다', (label) => {
  const view = showAll();
  const clean = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(clean);
  expect(clean.defaultPrevented).toBe(false);
  fireEvent.change(view.getByLabelText(label), { target: { value: '임시 입력' } });
  expect(view.getByLabelText(label).closest('form')!.getAttribute('data-unsaved-changes')).toBe('true');
  const dirty = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(dirty);
  expect(dirty.defaultPrevented).toBe(true);
});

it('두 폼을 편집해도 한 영역만 취소하면 다른 draft를 유지한 채 그 영역을 저장할 수 있다', () => {
  const view = showAll();
  fireEvent.change(view.getByLabelText('직급'), { target: { value: '저장할 직급' } });
  fireEvent.change(view.getByLabelText('영문성명'), { target: { value: '취소할 이름' } });
  fireEvent.click(view.getByRole('button', { name: '상세정보 입력 취소' }));
  expect((view.getByLabelText('영문성명') as HTMLInputElement).value).toBe('');
  expect((view.getByLabelText('직급') as HTMLInputElement).value).toBe('저장할 직급');
  fireEvent.submit(view.container.querySelector('form[action$="/personnel"]')!);
  expect(view.onSubmit).toHaveBeenCalledTimes(1);
});

it('사진 지우기 draft도 다른 영역 저장으로 유실되지 않는다', () => {
  const view = showAll();
  fireEvent.click(view.getByRole('button', { name: '등록된 사진 지우기' }));
  fireEvent.submit(view.container.querySelector('form[action$="/personal-details"]')!);
  expect(view.onSubmit).not.toHaveBeenCalled();
  expect(new FormData(view.container.querySelector('form')!).get('photoAction')).toBe('CLEAR');
});

it.each(['영문성명', '항목명'])('%s draft는 기존 앱 메뉴·뒤로 이동의 버리기 확인에도 포함된다', (label) => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
  const fetch = vi.spyOn(globalThis, 'fetch');
  const view = render(<App initial={{ page: 'org/personnel', data: { personnel: model, personalDetails: details, workspacePrincipal: { roles: ['EMPLOYEE', 'ATTENDANCE_ADMIN'] } }, csrf: { token: '', parameterName: '_csrf', headerName: '' } }} />);
  fireEvent.change(view.getByLabelText(label), { target: { value: '유지할 입력' } });
  fireEvent.click(view.getByRole('link', { name: '기본정보·소속' }));
  expect(confirm).toHaveBeenCalled(); expect(fetch).not.toHaveBeenCalled();
  confirm.mockClear();
  history.pushState({}, '', '/me/profile');
  window.dispatchEvent(new PopStateEvent('popstate', { state: {} }));
  expect(confirm).toHaveBeenCalled(); expect(fetch).not.toHaveBeenCalled();
  expect((view.getByLabelText(label) as HTMLInputElement).value).toBe('유지할 입력');
  confirm.mockRestore(); fetch.mockRestore();
});

it('담당자는 상세 개인정보와 최소 이력을 별도 버전·명령으로 등록한다', () => {
  const view = render(<CsrfContext value={{ token: 'csrf-1', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' }}>
    <PersonnelPage page="org/personnel" data={{ personnel: model, personalDetails: { employeeId: 'e-1', version: 0, form: { expectedVersion: 0, commandId: 'details-1' }, entries: [], appointments: [] } }} csrf={{ token: 'csrf-1', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' }} reload={() => {}} />
  </CsrfContext>);
  const form = view.container.querySelector('form[action="/admin/employees/e-1/personal-details"]') as HTMLFormElement;
  expect(form).toBeTruthy();
  expect(new FormData(form).get('expectedVersion')).toBe('0');
  expect(view.getByLabelText('영문성명')).toBeTruthy();
  expect(view.getByLabelText('인정경력 (개월)')).toBeTruthy();
  expect(view.queryByLabelText('주민등록번호')).toBeNull();
  expect(view.container.querySelector('form[action="/admin/employees/e-1/personal-entries"]')).toBeTruthy();
});

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
