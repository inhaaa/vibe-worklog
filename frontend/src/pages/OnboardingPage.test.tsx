// @vitest-environment jsdom
import { cleanup, fireEvent, render } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { OnboardingPage } from './OnboardingPage';

const onboarding = {
  today: '2026-11-17', rows: [], page: 0,
  organizations: [{ id: 'org', code: 'DEV', name: '개발', version: 3 }],
  scheduleVersions: [{ id: 'schedule', templateName: '주간', revision: 1, validFrom: '2026-11-01', templateVersion: 4 }],
  detail: { id: 'planned', displayName: '신규 사원', joinedOn: '2026-12-01', version: 2, state: 'PLANNED' },
  form: { commandId: 'command', displayName: '신규 사원', joinedOn: '2026-12-01' },
};

afterEach(cleanup);

it('입사 예정일을 바꾸면 선택한 조의 유효 개정과 요일별 시간코드를 실제 전송값과 함께 바꾼다', () => {
  const group={templateId:'g',templateName:'운영조',group:true,templateVersion:1};
  const model={...onboarding,detail:null,scheduleVersions:[
    {...group,id:'v1',revision:1,validFrom:'2026-11-01',validTo:'2026-12-01'},
    {...group,id:'v2',revision:2,validFrom:'2026-12-01',validTo:null}],
    scheduleDays:[
      {versionId:'v1',weekday:2,group:true,templateCode:'G01',workCode:'W01',workday:true,startTime:'09:00:00',endTime:'18:00:00'},
      {versionId:'v2',weekday:2,group:true,templateCode:'G01',workCode:'W02',workday:true,startTime:'10:00:00',endTime:'19:00:00'},
      {versionId:'v2',weekday:6,group:true,templateCode:'G01',workCode:null,workday:false}]};
  const view=render(<OnboardingPage data={{onboarding:model}}/>);
  const select=view.getByLabelText('최초 근무') as HTMLSelectElement;
  fireEvent.change(select,{target:{value:'v1'}});
  expect(view.getByText(/G01 운영조 · W01 · 09:00~18:00/)).toBeTruthy();
  fireEvent.change(view.getByLabelText('입사 예정일'),{target:{value:'2026-12-01'}});
  expect(select.value).toBe('v2');
  expect(view.getByText(/G01 운영조 · W02 · 10:00~19:00/)).toBeTruthy();
  fireEvent.change(view.getByLabelText('입사 예정일'),{target:{value:'2026-12-05'}});
  expect(view.getByText(/G01 운영조 · 비근로일/)).toBeTruthy();
  expect(new FormData(select.form!).get('scheduleVersionId')).toBe('v2');
});

it('입사일에 적용되는 근무조 개정만 선택할 수 있다', () => {
  const group = { templateName: '운영조', group: true, revision: 1, templateVersion: 2 };
  const view = render(<OnboardingPage data={{ onboarding: { ...onboarding, scheduleVersions: [
    { ...group, id: 'old', validFrom: '2026-11-01', validTo: '2026-12-01' },
    { ...group, id: 'new', validFrom: '2026-12-01', validTo: null, revision: 2 },
  ] } }} />);
  const select = view.getByLabelText('확정할 근무 버전') as HTMLSelectElement;
  expect([...select.options].map(option => option.value)).toEqual(['', 'new']);
  expect(select.options[1].textContent).toContain('근무조');
});

it('확정 폼은 검토한 조직과 근무 버전을 함께 보내고 공개 데모에는 표시하지 않는다', () => {
  const view = render(<OnboardingPage data={{ onboarding }} />);
  const form = view.container.querySelector<HTMLFormElement>('form[action="/admin/employees/onboarding/planned/confirm"]')!;
  fireEvent.change(form.querySelector('select[name="organizationId"]')!, { target: { value: 'org' } });
  fireEvent.change(form.querySelector('select[name="scheduleVersionId"]')!, { target: { value: 'schedule' } });
  const values = new FormData(form);
  expect(values.get('expectedVersion')).toBe('2');
  expect(values.get('expectedOrganizationVersion')).toBe('3');
  expect(values.get('expectedTemplateVersion')).toBe('4');
  view.rerender(<OnboardingPage data={{ onboarding: { ...onboarding, publicDemo: true } }} />);
  expect(view.getByText('신규 사원')).toBeTruthy();
  expect(view.queryByRole('button', { name: '입사 확정' })).toBeNull();
});

it('확정된 미래 사원은 발령을 보여 주고 현재 재직 확인 후에만 초대를 연결한다', () => {
  const confirmed = { ...onboarding, detail: { ...onboarding.detail, state: 'CONFIRMED', employeeId: 'employee', employeeNo: '26001' } };
  const view = render(<OnboardingPage data={{ onboarding: confirmed }} />);
  expect(view.getByText('계정 초대는 입사일부터 할 수 있습니다.')).toBeTruthy();
  expect(view.queryByRole('link', { name: '계정 초대' })).toBeNull();
  view.rerender(<OnboardingPage data={{ onboarding: { ...confirmed, today: '2026-12-01', inviteEligible: true } }} />);
  expect(view.getByRole('link', { name: '계정 초대' })).toHaveProperty('pathname', '/admin/employees/employee/access');
});
