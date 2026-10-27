// @vitest-environment jsdom
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { render, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AuthPages } from './AuthPages';

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
  it('다른 업무 페이지는 위임한다', () => {
    expect(html('me/today', {})).toBe('');
  });
  it('일반 로그인은 회사코드·이메일·강한 비밀번호를 사용하며 데모 자격을 숨긴다', () => {
    const result = html('login', {
      localDemo: false,
      demoLogin: 'admin',
      demoPassword: 'admin',
      flags: { error: true, invited: true, reset: true },
    });
    expect(result).toContain('name="companyCode"');
    expect(result).toContain('name="email"');
    expect(result).toContain('type="email"');
    expect(result).toContain('minLength="12"');
    expect(result).toContain('초대를 수락했습니다');
    expect(result).toContain('로그인 정보를 확인');
    expect(result).not.toContain('admin / admin');
    expect(result).not.toContain('value="admin"');
  });
  it('데모 소개만 서버가 제공한 합성 자격으로 정상 POST 로그인한다', () => {
    const result = html('demo', {
      demoLogin: 'admin',
      demoPassword: 'admin',
      flags: { logout: true },
    });
    expect(result).toContain('action="/login"');
    expect(result).toContain('name="email" value="admin"');
    expect(result).toContain('name="password" value="admin"');
    expect(result).toContain('데모로 입장');
    expect(result).toContain('로그아웃했습니다');
    expect(result).not.toContain('name="companyCode"');
  });
  it('로컬 로그인 모드는 회사코드를 받지 않고 별도 최소 길이를 쓴다', () => {
    const result = html('login', { localDemo: true });
    expect(result).not.toContain('name="companyCode"');
    expect(result).toContain('minLength="5"');
    expect(result).toContain('admin / admin');
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
