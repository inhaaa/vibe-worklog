// @vitest-environment jsdom
import React from 'react';
import { cleanup, fireEvent, render as renderDom, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AdminPages } from './AdminPages';
import type { PageProps } from '../types';

const employee = {
  id: 'e-1',
  employeeNo: 'E001',
  displayName: '김직원',
  joinedOn: '2026-10-01',
  leftOn: null,
  active: true,
  version: 7,
};
const organization = {
  id: 'o-1',
  code: 'DEV',
  name: '개발팀',
  parentId: null,
  active: true,
  version: 3,
};
const command = { commandId: 'command-1', expectedVersion: 7, reason: '검토함' };
const employeeForm = { ...employee, ...command };
const organizationForm = { ...organization, ...command };
const scheduleForm = {
  ...command,
  code: 'FIXED',
  name: '고정 근무',
  validFrom: '2026-10-26',
  days: Array.from({ length: 7 }, (_, i) => ({
    weekday: i + 1,
    workday: i < 5,
    startTime: i < 5 ? '09:00' : '',
    endTime: i < 5 ? '18:00' : '',
    breaks: i < 5 ? '12:00-13:00' : '',
  })),
};
const template = { id: 's-1', code: 'FIXED', name: '고정 근무', active: true, version: 4 };
const version = {
  id: 'v-1',
  templateId: 's-1',
  revision: 2,
  validFrom: '2026-10-26',
  reason: '주간 일정',
};
const access = {
  employee,
  account: null,
  accountStatus: '계정 없음',
  accountState: 'NONE',
  ownAccount: false,
  eligibleEmployee: true,
  resetForm: command,
  rolesForm: { ...command, roles: ['EMPLOYEE', 'APPROVER'] },
  approverForm: {
    ...command,
    employeeId: employee.id,
    approverEmployeeId: 'e-2',
    validFrom: '2026-10-26',
  },
  assignments: [],
  currentAssignment: null,
  candidates: [employee, { ...employee, id: 'e-2', displayName: '박승인' }],
  selectedApproverMissing: false,
};

async function render(page: string, data: Record<string, unknown>) {
  const props: PageProps = {
    page,
    data,
    csrf: { token: 'csrf-token', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' },
    reload: () => {},
  };
  const view = renderDom(<AdminPages {...props} />);
  await waitFor(() => {
    for (const sheet of view.container.querySelectorAll('.w-sheet'))
      expect(sheet.querySelector('[row-id]')).not.toBeNull();
  });
  return view.container.innerHTML;
}

afterEach(cleanup);

describe('관리 React 페이지', () => {
  it('다른 페이지는 처리하지 않는다', async () => {
    expect(await render('me/today', {})).toBe('');
  });

  it('직원 검색·필터·선택과 페이지 이동에 조건을 보존한다', async () => {
    const html = await render('org/employees', {
      employees: [employee],
      selectedEmployee: employee,
      employeeOrganizations: { 'e-1': '개발팀' },
      options: [organization],
      page: 1,
      q: '김 & 개발',
      organizationId: 'o-1',
      status: 'active',
      hasNext: true,
      pageEmployeeCount: 1,
      pageActiveCount: 1,
      pageInactiveCount: 0,
      form: employeeForm,
    });
    expect(html).toContain('name="q"');
    expect(html).toContain('value="김 &amp; 개발"');
    expect(html).toContain('value="o-1" selected=""');
    expect(html).toContain('value="active" selected=""');
    expect(html).toContain('employeeId=e-1');
    expect(html).toContain('page=2');
    expect(html).toContain('aria-current="true"');
    expect(html).toContain('href="/admin/employees/e-1/access"');
    expect(html).toContain('name="employeeNo"');
    expect(html).toContain('name="_active"');
    expect(html).toContain('role="grid"');
    expect(html).toContain('row-id="e-1"');
  });

  it('직원 목록의 빈 상태와 오류 입력을 보존한다', async () => {
    const html = await render('org/employees', {
      employees: [],
      selectedEmployee: null,
      employeeOrganizations: {},
      options: [],
      page: 0,
      q: '',
      status: 'all',
      hasNext: false,
      pageEmployeeCount: 0,
      pageActiveCount: 0,
      pageInactiveCount: 0,
      form: employeeForm,
      error: '사번이 중복됩니다',
    });
    expect(html).toContain('등록된 사원이 없습니다');
    expect(html).toContain('role="alert"');
    expect(html).toContain('사번이 중복됩니다');
    expect(html).toContain('open=""');
    expect(html).toContain('value="김직원"');
  });

  it('사원 수정과 소속 이동의 독립 명령·버전을 유지한다', async () => {
    const html = await render('org/employee', {
      employee,
      form: employeeForm,
      assignmentForm: {
        ...command,
        commandId: 'assignment-1',
        organizationId: 'o-1',
        validFrom: '2026-10-27',
      },
      assignments: [
        {
          id: 'a-1',
          organizationName: '과거팀',
          validFrom: '2026-10-01',
          validTo: null,
          reason: '첫 소속',
        },
      ],
      options: [organization, { ...organization, id: 'inactive', active: false }],
    });
    expect(html).toContain('action="/admin/employees/e-1/changes"');
    expect(html).toContain('action="/admin/employees/e-1/assignments"');
    expect(html.match(/name="expectedVersion"/g)).toHaveLength(2);
    expect(html).toContain('value="assignment-1"');
    expect(html).toContain('readonly=""');
    expect(html).toContain('종료일 없음');
    expect(html).not.toContain('value="inactive"');
    expect(html).toContain('role="grid"');
    expect(html).toContain('row-id="a-1"');
  });

  it('조직 목록은 검색·페이지와 신규 등록 필드를 렌더링한다', async () => {
    const html = await render('org/organizations', {
      organizations: [organization],
      orgNames: {},
      options: [organization],
      q: '개발',
      page: 0,
      hasNext: true,
      form: organizationForm,
    });
    expect(html).toContain('href="/admin/organizations/o-1"');
    expect(html).toContain('name="parentId"');
    expect(html).toContain('name="commandId"');
    expect(html).toContain('page=1');
    expect(html).toContain('role="grid"');
    expect(html).toContain('row-id="o-1"');
  });

  it('조직 상세는 자신을 부모에서 제외하며 기존 비활성 부모를 보존한다', async () => {
    const html = await render('org/organization', {
      organization,
      form: { ...organizationForm, parentId: 'inactive' },
      options: [
        organization,
        { ...organization, id: 'inactive', name: '기존 부모', active: false },
        { ...organization, id: 'other-inactive', name: '선택 불가', active: false },
      ],
    });
    expect(html).toContain('action="/admin/organizations/o-1/changes"');
    expect(html).toContain('value="inactive" selected=""');
    expect(html).toContain('기존 부모 (비활성)');
    expect(html).not.toContain('value="o-1"');
    expect(html).not.toContain('선택 불가');
  });

  it('초대 폼 없이 계정 상태와 초기화 폼을 보이고 자기 승인자 선택을 제외한다', async () => {
    const html = await render('org/access', {
      ...access,
      account: { id: 'a-1', email: '', active: false, credentialSet: false, roles: ['EMPLOYEE'] },
      accountStatus: '최초 접속 대기',
      accountState: 'PENDING',
      selectedApproverMissing: true,
      approverForm: { ...access.approverForm, approverEmployeeId: 'missing' },
    });
    const doc = new DOMParser().parseFromString(html, 'text/html');
    expect(doc.body.textContent).toContain('최초 접속 대기');
    expect(doc.querySelector('form[action="/admin/employees/e-1/account-reset"] button')?.textContent?.trim()).toBe('비밀번호 초기화');
    expect(doc.querySelector('label[for="invitation-email"]')).toBeNull();
    expect(html).not.toContain('/invitations"');
    expect(html).toContain('action="/admin/employees/e-1/roles"');
    expect(html).toContain('현재 선택값 (재지정 필요)');
    expect(html).toContain('value="missing" selected=""');
    expect(html).not.toContain('<option value="e-1"');
    expect(html).toContain('action="/admin/approver-assignments"');
  });

  it('계정이 없으면 초기화 폼을 보이지 않고 생년월일이 없으면 안내한다', async () => {
    const none = await render('org/access', access);
    expect(none).not.toContain('account-reset"');
    expect(none).toContain('입사 확정 시 계정이 만들어집니다');
    const noBirth = await render('org/access', {
      ...access,
      account: { id: 'a-1', email: '', active: false, credentialSet: false, roles: ['EMPLOYEE'] },
      accountStatus: '생년월일 필요',
      accountState: 'BIRTH_DATE_REQUIRED',
    });
    expect(noBirth).toContain('생년월일을 등록해 주세요');
    expect(noBirth).toContain('account-reset"');
    expect(noBirth).toContain('action="/admin/employees/e-1/roles"');
  });

  it('본인 계정에는 초기화 폼 대신 안내를 보이고 이전 초대 상태는 안내 문구를 바꾼다', async () => {
    const active = { id: 'a-1', email: '', active: true, credentialSet: true, roles: ['EMPLOYEE'] };
    const own = await render('org/access', { ...access, account: active, accountState: 'ACTIVE', ownAccount: true });
    expect(own).not.toContain('account-reset"');
    expect(own).toContain('본인 계정은 초기화할 수 없습니다');
    const old = await render('org/access', {
      ...access, account: { ...active, active: false, credentialSet: false }, accountState: 'PREVIOUS_INVITATION',
    });
    expect(old).toContain('이전 초대 대기 계정은 비밀번호 초기화 후 역할을 변경할 수 있습니다.');
  });

  it('초기화 확인 창을 취소하면 제출하지 않는다', async () => {
    const active = { id: 'a-1', email: '', active: true, credentialSet: true, roles: ['EMPLOYEE'] };
    const props: PageProps = {
      page: 'org/access', data: { ...access, account: active, accountState: 'ACTIVE' },
      csrf: { token: 'csrf-token', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' }, reload: () => {},
    };
    const view = renderDom(<AdminPages {...props} />);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const form = view.container.querySelector('form[action$="/account-reset"]')!;
    expect(fireEvent.submit(form)).toBe(false);
    confirm.mockReturnValue(true);
    expect(fireEvent.submit(form)).toBe(true);
    confirm.mockRestore();
  });

  it('활성 계정 역할은 EMPLOYEE와 선택 역할 값으로 제출한다', async () => {
    const html = await render('org/access', {
      ...access,
      account: {
        id: 'account-1',
        employeeId: employee.id,
        email: 'employee@example.test',
        active: true,
        credentialSet: true,
        authVersion: 2,
        roles: ['EMPLOYEE', 'APPROVER'],
      },
    });
    expect(html).not.toContain('/invitations"');
    expect(html).toContain('action="/admin/employees/e-1/roles"');
    expect(new DOMParser().parseFromString(html, 'text/html').querySelector('input[name="roles"][value="EMPLOYEE"]')).not.toBeNull();
    expect(html).toContain('value="APPROVER"');
    expect(html).toContain('value="ATTENDANCE_ADMIN"');
    expect(html).toContain('checked=""');
  });

  it('승인자 배정 이력은 안정된 ID의 조회 시트에서 교체 상태와 사유를 보존한다', async () => {
    const html = await render('org/access', {
      ...access,
      assignments: [{ id: 'approver-a-1', approverEmployeeId: 'e-2', approverName: '박승인', validFrom: '2026-10-26', validTo: null, supersededAt: '2026-10-26T01:00:00Z', reason: '담당 변경', eligible: false }],
    });
    expect(html).toContain('role="grid"');
    expect(html).toContain('row-id="approver-a-1"');
    expect(html).toContain('당일 교체·해제');
    expect(html).toContain('담당 변경');
    expect(html).toContain('action="/admin/approver-assignments"');
  });

  it('부적격 사원에게 변경 폼을 제공하지 않는다', async () => {
    const html = await render('org/access', { ...access, eligibleEmployee: false });
    expect(html).toContain('현재 재직 중인 활성 사원만');
    expect(html).not.toContain('<form');
  });

  it('고정 일정의 일곱 요일과 Spring 바인딩 명칭을 보존한다', async () => {
    const html = await render('schedule/list', {
      templates: [],
      form: scheduleForm,
      today: '2026-10-26',
    });
    expect(html).toContain('등록된 템플릿이 없습니다');
    expect(html).toContain('action="/admin/schedules"');
    for (let i = 0; i < 7; i++) {
      expect(html).toContain(`name="days[${i}].weekday"`);
      expect(html).toContain(`name="_days[${i}].workday"`);
      expect(html).toContain(`name="days[${i}].startTime"`);
      expect(html).toContain(`name="days[${i}].endTime"`);
      expect(html).toContain(`name="days[${i}].breaks"`);
    }
    expect(html).toContain('min="2026-10-26"');
  });

  it('일정 폼 조각도 수정 대상을 유지한 React 폼으로 표시한다', async () => {
    const html = await render('schedule/form', { template, form: scheduleForm, today: '2026-10-26' });
    expect(html).toContain('action="/admin/schedules/s-1/versions"');
    expect(html).toContain('name="days[6].weekday"');
    expect(html).toContain('name="reason"');
  });

  it('일정 상세에 불변 버전과 근무·휴게를 표시한다', async () => {
    const html = await render('schedule/detail', {
      template,
      versions: [version],
      versionDays: {
        'v-1': [
          {
            weekday: 1,
            workday: true,
            startTime: '09:00:00',
            endTime: '18:00:00',
            breaks: [{ startTime: '12:00:00', endTime: '13:00:00' }],
          },
          { weekday: 7, workday: false, startTime: null, endTime: null, breaks: [] },
        ],
      },
      form: scheduleForm,
      today: '2026-10-26',
    });
    expect(html).toContain('action="/admin/schedules/s-1/versions"');
    expect(html).toContain('버전 2');
    expect(html).toContain('비근로일');
    expect(html).toContain('해당 없음');
    expect(html).toContain('12:00');
    expect(html).toContain('readonly=""');
    expect(html).toContain('role="grid"');
    expect(html).toContain('row-id="v-1:1"');
    expect(new DOMParser().parseFromString(html, 'text/html').querySelectorAll('table')).toHaveLength(1);
  });

  it('일정 배정에 현재 선택한 버전·사원 버전을 보존한다', async () => {
    const html = await render('schedule/assignment', {
      employee,
      assignments: [],
      versions: [version],
      templateNames: { 's-1': '고정 근무' },
      today: '2026-10-26',
      form: { ...command, scheduleVersionId: 'v-1', validFrom: '2026-10-27' },
    });
    expect(html).toContain('일정 누락 날짜를 정상 근로일로 간주하지 않습니다');
    expect(html).toContain('action="/admin/employees/e-1/schedule"');
    expect(html).toContain('value="v-1" selected=""');
    expect(html).toContain('name="expectedVersion"');
  });

  it('근무 배정 이력은 안정된 ID의 조회 시트에 기간과 사유를 표시한다', async () => {
    const html = await render('schedule/assignment', {
      employee,
      assignments: [
        { id: 'schedule-a-1', templateName: '운영조', revision: 2, validFrom: '2026-10-26', validTo: '2026-11-01', reason: '신규 배정' },
        { id: 'schedule-a-1', templateName: '운영조', revision: 3, validFrom: '2026-11-01', validTo: null, reason: '신규 배정' },
      ],
      versions: [version], templateNames: { 's-1': '고정 근무' }, today: '2026-10-26',
      form: { ...command, scheduleVersionId: 'v-1', validFrom: '2026-10-27' },
    });
    expect(html).toContain('role="grid"');
    expect(html).toContain('row-id="schedule-a-1:2026-10-26"');
    expect(html).toContain('row-id="schedule-a-1:2026-11-01"');
    expect(html).toContain('종료 없음');
    expect(html).toContain('신규 배정');
  });

  it('허용망은 유효 이력만 종료 폼과 위험 확인 항목을 표시한다', async () => {
    const network = {
      id: 'n-1',
      network: '192.0.2.0/24',
      label: '사무실',
      validFrom: '2026-10-25T15:00:00Z',
      validTo: null,
      reason: '검토',
      version: 2,
    };
    const html = await render('clock/networks', {
      networks: [network, { ...network, id: 'n-2', validTo: '2026-10-26T00:00:00Z' }],
      pageCommand: 'close-command',
      form: { ...command, network: '', label: '', wideConfirmed: false },
    });
    expect(html).toContain('action="/admin/networks/n-1/close"');
    expect(html).not.toContain('action="/admin/networks/n-2/close"');
    expect(html).toContain('value="close-command"');
    expect(html).toContain('name="_wideConfirmed"');
    expect(html).toContain('action="/admin/networks/changes"');
  });

  it('휴일 캘린더는 명시 검토와 예외·기간·근거 필드를 요구한다', async () => {
    const html = await render('clock/calendar', {
      calendars: [],
      form: {
        ...command,
        from: '2026-10-26',
        to: '2026-11-25',
        reviewed: false,
        basis: '공식 근거',
        exceptions: '2026-11-01|HOLIDAY|휴일|검토',
      },
    });
    expect(html).toContain('action="/admin/calendar/versions"');
    for (const name of ['from', 'to', 'basis', 'reviewed', 'exceptions', 'reason', 'commandId'])
      expect(html).toContain(`name="${name}"`);
    expect(html.match(/<input[^>]*name="reviewed"[^>]*>/)?.[0]).toContain('required');
    expect(html).toContain('미등록 날짜는 미확인');
  });

  it('사원·조직 이름과 오류를 React 텍스트로 이스케이프한다', async () => {
    const html = await render('org/organization', {
      organization: { ...organization, name: '<script>alert(1)</script>' },
      form: { ...organizationForm, name: '<img src=x onerror=alert(1)>' },
      options: [],
      error: '<b>오류</b>',
    });
    expect(html).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');
    const doc = new DOMParser().parseFromString(html, 'text/html');
    expect(doc.querySelector<HTMLInputElement>('input[name="name"]')?.value).toBe('<img src=x onerror=alert(1)>');
    expect(doc.querySelector('img')).toBeNull();
    expect(html).toContain('&lt;b&gt;오류&lt;/b&gt;');
    expect(html).not.toContain('<script>');
  });

  it('사원 정보를 실제로 편집하고 체크를 해제해도 명령·버전을 제출한다', () => {
    const props: PageProps = {
      page: 'org/employee',
      data: {
        employee,
        form: employeeForm,
        assignments: [],
        assignmentForm: { ...command, organizationId: null, validFrom: '' },
        options: [],
      },
      csrf: { token: '', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' },
      reload: () => {},
    };
    const view = renderDom(<AdminPages {...props} />);
    fireEvent.change(view.getByLabelText('이름'), { target: { value: '수정한 이름' } });
    fireEvent.click(view.getByLabelText('활성 사원'));
    const form = view.container.querySelector(
      'form[action="/admin/employees/e-1/changes"]',
    ) as HTMLFormElement;
    const submitted = new FormData(form);
    expect(submitted.get('displayName')).toBe('수정한 이름');
    expect(submitted.get('employeeNo')).toBe('E001');
    expect(submitted.get('active')).toBeNull();
    expect(submitted.get('_active')).toBe('on');
    expect(submitted.get('commandId')).toBe('command-1');
    expect(submitted.get('expectedVersion')).toBe('7');
  });

  it('역할 체크 변경은 고정 직원 역할과 새 선택값만 제출한다', () => {
    const props: PageProps = {
      page: 'org/access',
      data: {
        ...access,
        account: {
          id: 'account-1',
          email: 'employee@example.test',
          active: true,
          credentialSet: true,
          roles: ['EMPLOYEE', 'APPROVER'],
        },
      },
      csrf: { token: '', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' },
      reload: () => {},
    };
    const view = renderDom(<AdminPages {...props} />);
    fireEvent.click(view.getByLabelText('승인자 (APPROVER)'));
    fireEvent.click(view.getByLabelText('근태 담당자 (ATTENDANCE_ADMIN)'));
    const form = view.container.querySelector(
      'form[action="/admin/employees/e-1/roles"]',
    ) as HTMLFormElement;
    expect(new FormData(form).getAll('roles')).toEqual(['EMPLOYEE', 'ATTENDANCE_ADMIN']);
  });

  it('대표를 추가해도 기존 결재 담당 기능과 필수 공통 역할을 보존한다', () => {
    const props: PageProps = {
      page: 'org/access',
      data: {
        ...access,
        account: { id: 'account-1', email: 'employee@example.test', active: true, credentialSet: true, roles: ['EMPLOYEE', 'APPROVER'] },
      },
      csrf: { token: '', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' },
      reload: () => {},
    };
    const view = renderDom(<AdminPages {...props} />);
    fireEvent.click(view.getByLabelText('대표 (COMPANY_REPRESENTATIVE)'));
    const form = view.container.querySelector('form[action="/admin/employees/e-1/roles"]') as HTMLFormElement;
    expect(new FormData(form).getAll('roles')).toEqual(['EMPLOYEE', 'APPROVER', 'COMPANY_REPRESENTATIVE']);
  });
});
