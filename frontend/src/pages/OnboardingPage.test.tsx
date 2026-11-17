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
