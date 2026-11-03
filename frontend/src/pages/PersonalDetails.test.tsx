// @vitest-environment jsdom
import { cleanup, fireEvent, render } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { PersonalDetailsEditor, PersonalDetailsTabs, type PersonalDetailsModel } from './PersonalDetails';

const model: PersonalDetailsModel = {
  employeeId: 'e-1', version: 1, form: { recognizedMonths: 0, veteranStatus: 'NO', disabilityStatus: 'UNKNOWN' },
  entries: [{ id: 'entry', kind: 'QUALIFICATION', title: '<script>합성 자격</script>', detail: '합성 기관', effectiveFrom: '2026-01-01', effectiveTo: null, revision: 1 }],
  appointments: [{ id: 'appointment', organizationName: '합성팀', validFrom: '2026-01-01', validTo: null }],
};
afterEach(cleanup);

it('0개월과 비해당·미등록을 구분하고 모든 탭을 키보드로 순환하며 원문은 이스케이프한다', () => {
  const view = render(<PersonalDetailsTabs model={model} identity={{ employeeName: '합성사원', joinedOn: '2026-01-01', birthDate: '2000-01-01' }} />);
  const panel = view.getByRole('tabpanel');
  expect(panel.textContent).toContain('인정경력 (개월)0');
  expect(panel.textContent).toContain('보훈여부해당 없음');
  expect(panel.textContent).toContain('장애여부미등록');
  const tabs = view.getAllByRole('tab');
  fireEvent.keyDown(tabs[0], { key: 'ArrowLeft' });
  expect(tabs[6].getAttribute('aria-selected')).toBe('true');
  fireEvent.keyDown(tabs[6], { key: 'Home' });
  expect(tabs[0].getAttribute('aria-selected')).toBe('true');
  fireEvent.keyDown(tabs[0], { key: 'End' });
  expect(tabs[6].getAttribute('aria-selected')).toBe('true');
  fireEvent.click(tabs[4]);
  expect(panel.textContent).toContain('<script>합성 자격</script>');
  expect(view.container.querySelector('script')).toBeNull();
});

it('상세정보 충돌 시 현재 저장된 모든 항목을 비교하고 입력을 보존한 채 새 버전을 사용한다', () => {
  const view = render(<PersonalDetailsEditor model={{ ...model, version: 2,
    form: { expectedVersion: 1, englishName: 'Submitted', commandId: 'old-command' },
    currentForm: { englishName: 'Current', militaryBranch: '육군', recognizedMonths: 24, groupJoinedOn: '2020-01-01', contactEmail: 'synthetic@example.test' },
  }} today="2026-11-03" busy={false} />);
  const form = view.container.querySelector('form')!;
  expect(view.getByText('현재 군별')).toBeTruthy();
  expect(view.getByText('육군')).toBeTruthy();
  expect(view.getByText('현재 인정경력 (개월)')).toBeTruthy();
  expect(view.getByText('synthetic@example.test')).toBeTruthy();
  fireEvent.click(view.getByRole('button', { name: '상세정보 최신 버전 확인' }));
  expect(new FormData(form).get('expectedVersion')).toBe('2');
  expect(new FormData(form).get('englishName')).toBe('Submitted');
  expect(new FormData(form).get('commandId')).not.toBe('old-command');
});

it('이력409은 현재 기록을 확인하고 최신 버전·새 명령으로 입력을 유지하여 재시도한다', () => {
  const view = render(<PersonalDetailsEditor model={{ ...model, version: 2, entryForm: {
    kind: 'QUALIFICATION', title: '입력한 자격', detail: '보존 상세', effectiveFrom: '2026-11-03', expectedVersion: 1, reason: '이력 사유', commandId: 'stale-entry',
  } }} today="2026-11-03" busy={false} />);
  const form = view.container.querySelector<HTMLFormElement>('form[action$="/personal-entries"]')!;
  expect((view.getByRole('button', { name: '이력 추가' }) as HTMLButtonElement).disabled).toBe(true);
  expect(view.getByLabelText('최신 이력 비교').textContent).toContain('합성 기관');
  fireEvent.click(view.getByRole('button', { name: '이력 최신 버전 확인 후 입력 유지' }));
  expect(new FormData(form).get('expectedVersion')).toBe('2');
  expect(new FormData(form).get('title')).toBe('입력한 자격');
  expect(new FormData(form).get('detail')).toBe('보존 상세');
  expect(new FormData(form).get('reason')).toBe('이력 사유');
  expect(new FormData(form).get('commandId')).not.toBe('stale-entry');
  expect((view.getByRole('button', { name: '이력 추가' }) as HTMLButtonElement).disabled).toBe(false);
});

it('이력503의 같은 입력 재시도는 명령을 유지하고 입력 변경 시에만 명령을 바꾼다', () => {
  const submitted = { ...model, entryForm: { kind: 'FAMILY', title: '배우자', detail: '1명', effectiveFrom: '2026-11-03', expectedVersion: 1, reason: '합성 등록', commandId: 'retry-entry' } };
  const view = render(<PersonalDetailsEditor model={submitted} today="2026-11-03" busy={false} />);
  const form = view.container.querySelector<HTMLFormElement>('form[action$="/personal-entries"]')!;
  view.rerender(<PersonalDetailsEditor model={submitted} today="2026-11-03" busy />);
  view.rerender(<PersonalDetailsEditor model={submitted} today="2026-11-03" busy={false} />);
  expect(new FormData(form).get('commandId')).toBe('retry-entry');
  fireEvent.change(view.getByLabelText('항목명'), { target: { value: '자녀' } });
  expect(new FormData(form).get('commandId')).not.toBe('retry-entry');
  expect(form.getAttribute('data-unsaved-changes')).toBe('true');
});
