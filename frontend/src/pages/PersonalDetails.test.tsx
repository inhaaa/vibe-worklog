// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { PersonalDetailsEditor, PersonalDetailsTabs, type PersonalDetailsModel } from './PersonalDetails';

const model: PersonalDetailsModel = {
  employeeId: 'e-1', version: 1, form: { recognizedMonths: 0, veteranStatus: 'NO', disabilityStatus: 'UNKNOWN' },
  entries: [{ id: 'entry', kind: 'QUALIFICATION', title: '<script>합성 자격</script>', detail: '합성 기관', effectiveFrom: '2026-01-01', effectiveTo: null, revision: 1 }],
  appointments: [{ id: 'appointment', organizationName: '합성팀', validFrom: '2026-01-01', validTo: null }],
};
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it('기본사항과 연락처는 화면 제목에 연결된 용어·값 목록으로 읽을 수 있다', () => {
  const view = render(<PersonalDetailsTabs model={{ ...model, form: {
    ...model.form, englishName: 'Synthetic Employee', address: '합성 주소', addressDetail: '테스트 동', contactEmail: 'synthetic@example.test',
  } }} identity={{ employeeName: '합성사원', joinedOn: '2026-01-01' }} />);
  const identity = view.getByRole('region', { name: '신원 정보' });
  const heading = within(identity).getByRole('heading', { name: '신원 정보', level: 3 });
  expect(identity.getAttribute('aria-labelledby')).toBe(heading.id);
  const term = within(identity).getByText('영문성명', { selector: 'dt' });
  expect(term.nextElementSibling).toBe(within(identity).getByText('Synthetic Employee'));
  expect(term.nextElementSibling?.tagName).toBe('DD');
  expect(within(view.getByRole('region', { name: '입사 정보' })).getByText('0')).toBeTruthy();
  expect(view.getByRole('region', { name: '추가 정보' }).textContent).toContain('해당 없음');
  fireEvent.click(view.getByRole('tab', { name: '주소·연락처' }));
  const selected = view.getByRole('tab', { selected: true, name: '주소·연락처' });
  expect(view.getByRole('tabpanel', { name: '주소·연락처' }).getAttribute('aria-labelledby')).toBe(selected.id);
  expect(view.getByRole('region', { name: '주소 정보' }).textContent).toContain('합성 주소');
  expect(view.getByRole('region', { name: '연락 정보' }).textContent).toContain('synthetic@example.test');
});

it('이력 탭은 해당 구분의 기록과 건수를 보여주며 발령 종료일의 의미를 보존한다', () => {
  const view = render(<PersonalDetailsTabs model={model} identity={{ employeeName: '합성사원', joinedOn: '2026-01-01' }} />);
  fireEvent.click(view.getByRole('tab', { name: '발령내역' }));
  const appointments = view.getByRole('table', { name: '발령내역' });
  expect(within(appointments).getByRole('columnheader', { name: '종료일 (미포함)' })).toBeTruthy();
  expect(within(appointments).getByText('현재')).toBeTruthy();
  expect(view.getByText('총 1건')).toBeTruthy();
  fireEvent.click(view.getByRole('tab', { name: '자격사항' }));
  expect(view.getByRole('table', { name: '자격사항' }).textContent).toContain('<script>합성 자격</script>');
  fireEvent.click(view.getByRole('tab', { name: '가족사항' }));
  expect(view.queryByRole('table')).toBeNull();
  expect(view.getByText('등록된 가족사항이 없습니다.')).toBeTruthy();
});

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

it('화면이 넓어지면 기록 목차가 수직 방향을 알리고 위아래 키로 같은 탭을 탐색한다', () => {
  const media = Object.assign(new EventTarget(), { matches: false });
  vi.stubGlobal('matchMedia', () => media);
  const view = render(<PersonalDetailsTabs model={model} identity={{ employeeName: '합성사원', joinedOn: '2026-01-01' }} />);
  const list = view.getByRole('tablist', { name: '인사정보 항목' });
  const tabs = within(list).getAllByRole('tab');
  expect(list.getAttribute('aria-orientation')).toBe('horizontal');
  fireEvent.keyDown(tabs[0], { key: 'ArrowDown' });
  expect(tabs[0].getAttribute('aria-selected')).toBe('true');
  act(() => {
    media.matches = true;
    media.dispatchEvent(new Event('change'));
  });
  expect(list.getAttribute('aria-orientation')).toBe('vertical');
  fireEvent.keyDown(tabs[0], { key: 'ArrowDown' });
  expect(tabs[1].getAttribute('aria-selected')).toBe('true');
  expect(document.activeElement).toBe(tabs[1]);
  expect(view.getByRole('tabpanel', { name: '발령내역' }).textContent).toContain('합성팀');
  fireEvent.keyDown(tabs[1], { key: 'ArrowUp' });
  expect(tabs[0].getAttribute('aria-selected')).toBe('true');
  expect(document.activeElement).toBe(tabs[0]);
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
