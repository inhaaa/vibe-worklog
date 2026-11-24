// @vitest-environment jsdom
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { render, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AuthPages } from './AuthPages';
import { CsrfContext } from '../ui';

const csrf = { token: 'csrf-value', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const page = (name: string, data: Record<string, unknown>) => (
  <AuthPages page={name} data={data} csrf={csrf} reload={() => {}} />
);
const html = (name: string, data: Record<string, unknown>) =>
  renderToStaticMarkup(page(name, data));
afterEach(cleanup);

describe('AuthPages', () => {
  it('공개 데모는 공유 가상 자료와 개인정보 입력 금지를 안내한다', () => {
    const result = html('demo', { publicDemo: true, demoLogin: 'admin', demoPassword: 'admin' });
    expect(result).toContain('모든 방문자가 함께 사용하는 가상 데이터');
    expect(result).toContain('실제 개인정보를 입력하지 마세요');
    expect(result).not.toContain('로컬 데모 계정');
  });
  it('메인 화면에서 입력 없이 서버가 제공한 기본 담당자로 로그인한다', () => {
    const view = render(<CsrfContext.Provider value={csrf}>{page('demo', {
      demoAccounts: [
        { employeeNo: '26001', employeeName: '가상 승인자', roles: ['EMPLOYEE', 'APPROVER'], password: 'approver' },
        { employeeNo: '99999', employeeName: '담당자', roles: ['EMPLOYEE', 'ATTENDANCE_ADMIN'], password: 'synthetic-admin' },
      ],
    })}</CsrfContext.Provider>);
    const start = view.getByRole('button', { name: 'WorkLog 시작하기' }) as HTMLButtonElement;
    const form = start.form!;
    expect(start.type).toBe('submit');
    expect(start.disabled).toBe(false);
    expect(form.getAttribute('action')).toBe('/login');
    expect(form.method).toBe('post');
    expect(Object.fromEntries(new FormData(form))).toEqual({ _csrf: 'csrf-value', email: '99999', password: 'synthetic-admin' });
    expect(view.container.querySelector('input:not([type="hidden"]), select')).toBeNull();
  });
  it('기본 담당자가 없으면 다른 사번이나 레거시 자격으로 로그인하지 않는다', () => {
    const view = render(page('demo', {
      demoLogin: 'admin', demoPassword: 'admin',
      demoAccounts: [{ employeeNo: '99999', employeeName: '직원', roles: ['EMPLOYEE'], password: 'synthetic-employee' }],
    }));
    expect((view.getByRole('button', { name: 'WorkLog 시작하기' }) as HTMLButtonElement).disabled).toBe(true);
    expect(view.getByText('기본 데모 관리자 계정을 사용할 수 없습니다.')).toBeTruthy();
    expect(view.container.querySelector('form[action="/login"]')).toBeNull();
  });
  it('다른 업무 페이지는 위임한다', () => {
    expect(html('me/today', {})).toBe('');
  });
  it('일반 로그인은 회사코드·사번 또는 초대 이메일과 설정한 비밀번호를 사용한다', () => {
    const result = html('login', {
      localDemo: false,
      demoLogin: 'admin',
      demoPassword: 'admin',
      flags: { error: true, invited: true, reset: true },
    });
    expect(result).toContain('name="companyCode"');
    expect(result).toContain('name="email"');
    expect(result).toContain('type="text"');
    expect(result).toContain('사번');
    expect(result).toContain('사번 또는 초대 이메일');
    expect(result).toContain('minLength="12"');
    expect(result).toContain('초대를 수락했습니다');
    expect(result).toContain('로그인 정보를 확인');
    expect(result).not.toContain('admin / admin');
    expect(result).not.toContain('value="admin"');
  });
  it('데모 소개만 서버가 제공한 사번과 합성 자격으로 정상 POST 로그인한다', () => {
    const result = html('demo', {
      demoAccounts: [{ employeeNo: '99999', employeeName: '담당자', roles: ['EMPLOYEE', 'ATTENDANCE_ADMIN'], password: 'admin' }],
      flags: { logout: true },
    });
    expect(result).toContain('action="/login"');
    expect(result).toContain('name="email"');
    expect(result).toContain('value="99999"');
    expect(result).toContain('value="admin"');
    expect(result).toContain('WorkLog 시작하기');
    expect(result).toContain('로그아웃했습니다');
    expect(result).not.toContain('name="companyCode"');
  });
  it('로컬 로그인 모드는 회사코드를 받지 않고 별도 최소 길이를 쓴다', () => {
    const result = html('login', { localDemo: true });
    expect(result).not.toContain('name="companyCode"');
    expect(result).toContain('minLength="5"');
    expect(result).toContain('99999');
    expect(result).not.toContain('/password-reset');
  });
  it('초대 수락은 코드 경로를 안전하게 인코딩하고 비밀번호·토큰을 재표시하지 않는다', () => {
    const result = html('invite', {
      companyCode: 'ACME/공백',
      token: 'secret-token',
      password: 'secret-pass',
      confirmation: 'secret-pass',
      error: '<script>error</script>',
    });
    expect(result).toContain('action="/invite/ACME%2F%EA%B3%B5%EB%B0%B1/accept"');
    for (const name of ['email', 'token', 'password', 'confirmation'])
      expect(result).toContain(`name="${name}"`);
    expect(result).not.toContain('secret-');
    expect(result).toContain('&lt;script&gt;error&lt;/script&gt;');
    expect(result).toContain('24시간');
  });
  it('경로에 넣기 어려운 회사 코드도 같은 회사에 수락 폼을 제출할 수 있다', () => {
    const companyCode = 'A/서울 & ./';
    const view = render(page('invite', { companyCode, inviteByCompany: true }));
    const form = view.getByRole('button', { name: '초대 수락·비밀번호 설정' }).closest('form')!;
    expect(form.getAttribute('action')).toBe('/invite/accept');
    expect(new FormData(form).get('companyCode')).toBe(companyCode);
    expect(new FormData(form).get('token')).toBe('');
  });
  it('복구 요청·완료는 별도 POST이고 계정 존재여부를 구분하지 않는다', () => {
    const result = html('password-reset', {
      flags: { requested: true },
      token: 'secret-token',
      password: 'secret-pass',
    });
    expect(result).toContain('action="/password-reset/request"');
    expect(result).toContain('action="/password-reset/complete"');
    expect(result).toContain('일치하는 계정이 있으면');
    expect(result).toContain('30분');
    expect(result).toContain('기존 로그인이 종료');
    expect(result).not.toContain('secret-');
  });
  it('메일 수신자는 초대와 복구 모두 최신 일회용 코드를 사용하고 첫 로그인 방법을 확인한다', () => {
    for (const name of ['invite', 'password-reset']) {
      const view = render(page(name, { companyCode: 'NEWCO', flags: { requested: true } }));
      const token = view.container.querySelector<HTMLInputElement>('input[name="token"]')!;
      expect(token.autocomplete).toBe('one-time-code');
      expect(view.container.textContent).toContain('만료 시각이 가장 늦은');
      expect(view.container.textContent).toContain('메일');
      expect(view.container.textContent).not.toContain('로컬 모의');
      view.unmount();
    }
    const login = render(page('login', { flags: { invited: true } }));
    expect(login.getByLabelText('사번 또는 초대 이메일')).toBeTruthy();
    expect(login.getByRole('status').textContent).toContain('회사 코드');
  });
  it('운영자 로그인은 독립 인증 경로와 로그인명을 유지한다', () => {
    const result = html('operator/login', { flags: { error: true }, password: 'secret-pass' });
    expect(result).toContain('action="/operator/login"');
    expect(result).toContain('name="loginId"');
    expect(result).not.toContain('name="companyCode"');
    expect(result).toContain('운영자 로그인 정보를 확인');
    expect(result).not.toContain('secret-pass');
  });
  it('고객사 상태와 최초 초대는 버전·독립 명령을 보존하고 렌더 중 명령을 재생성하지 않는다', () => {
    const tenants = [
      {
        id: 'tenant-active',
        companyCode: 'ACME',
        displayName: '활성 회사',
        active: true,
        version: 3,
      },
      {
        id: 'tenant-inactive',
        companyCode: 'STOP',
        displayName: '중지 회사',
        active: false,
        version: 5,
      },
    ];
    const data = {
      tenants,
      audit: [],
      form: {
        commandId: 'onboard-command',
        companyCode: 'NEW',
        companyName: '신규 회사',
        employeeNo: 'E001',
        employeeName: '최초 담당',
        joinedOn: '2026-10-26',
        email: 'first@example.test',
        relationshipBasis: '확인 근거',
        reason: '등록',
      },
    };
    const view = render(page('operator/tenants', data));
    const state = view.container.querySelector(
      'form[action="/operator/tenants/tenant-active/state"]',
    )!;
    const key = state.querySelector<HTMLInputElement>('[name="commandId"]')!.value;
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
    expect(state.querySelector<HTMLInputElement>('[name="expectedVersion"]')!.value).toBe('3');
    expect(state.querySelector<HTMLInputElement>('[name="active"]')!.value).toBe('false');
    expect(
      view.container.querySelector(
        'form[action="/operator/tenants/tenant-active/initial-invitations"]',
      ),
    ).not.toBeNull();
    expect(
      view.container.querySelector(
        'form[action="/operator/tenants/tenant-inactive/initial-invitations"]',
      ),
    ).toBeNull();
    view.rerender(page('operator/tenants', { ...data, error: '동일 페이지 입력 오류' }));
    expect(
      view.container.querySelector<HTMLInputElement>(
        'form[action="/operator/tenants/tenant-active/state"] [name="commandId"]',
      )!.value,
    ).toBe(key);
    const commands = [
      ...view.container.querySelectorAll<HTMLInputElement>('[name="commandId"]'),
    ].map((input) => input.value);
    expect(new Set(commands).size).toBe(commands.length);
    expect(commands).toContain('onboard-command');
    expect(view.container.textContent).toContain(
      '고객사 근태 열람·대리 승인 기능은 제공하지 않습니다',
    );
    view.rerender(
      page('operator/tenants', {
        ...data,
        tenants: [{ ...tenants[0], active: false, version: 4 }, tenants[1]],
      }),
    );
    expect(
      view.container.querySelector<HTMLInputElement>(
        'form[action="/operator/tenants/tenant-active/state"] [name="commandId"]',
      )!.value,
    ).not.toBe(key);
  });
});

describe('first login', () => {
  it('회사 코드와 사번을 채우고 생년월일과 새 비밀번호를 받는다', () => {
    const view = render(page('first-login', { companyCode: 'ACME', employeeNo: '2019031', flags: {} }));
    expect((view.getByLabelText('회사 코드') as HTMLInputElement).value).toBe('ACME');
    expect((view.getByLabelText('사번') as HTMLInputElement).value).toBe('2019031');
    expect(view.getByLabelText('생년월일').getAttribute('type')).toBe('date');
    expect(view.getByLabelText('새 비밀번호')).toBeTruthy();
    expect(view.getByRole('button', { name: '비밀번호 정하기' })).toBeTruthy();
  });
  it('활성화 뒤 로그인 화면이 새 비밀번호로 로그인하라고 안내한다', () => {
    const view = render(page('login', { flags: { activated: true } }));
    expect(view.getByText('비밀번호를 정했습니다. 사번과 새 비밀번호로 로그인해 주세요.')).toBeTruthy();
  });
});
