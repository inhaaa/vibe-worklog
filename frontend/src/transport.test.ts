import { describe, expect, it, vi } from 'vitest';
import { encodeForm, fetchPage, PAGE_ACCEPT, PageRequestError } from './transport';

const page = {
  page: 'org/employees',
  data: { employees: [] },
  csrf: { token: 't', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' },
};
describe('화면 HTTP 계약', () => {
  it('CSRF와 복수 역할을 포함한 폼 값을 손실 없이 인코딩한다', () => {
    const data = new FormData();
    data.append('_csrf', 'a+b/c=');
    data.append('roles', 'EMPLOYEE');
    data.append('roles', 'APPROVER');
    expect(encodeForm(data).getAll('roles')).toEqual(['EMPLOYEE', 'APPROVER']);
    expect(encodeForm(data).get('_csrf')).toBe('a+b/c=');
  });
  it('같은 출처 세션·no-store와 명시 Accept로 JSON 화면을 읽는다', async () => {
    const send = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify(page), { headers: { 'Content-Type': 'application/json' } }),
      );
    const result = await fetchPage('/admin/employees', {}, send);
    expect(result.envelope).toEqual(page);
    expect(send.mock.calls[0][1]).toMatchObject({ credentials: 'same-origin', cache: 'no-store' });
    expect(new Headers(send.mock.calls[0][1].headers).get('Accept')).toBe(PAGE_ACCEPT);
  });
  it('403을 빈 목록 성공으로 숨기지 않는다', async () => {
    const send = vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({ page: 'org/problem', data: { message: '권한 없음' }, csrf: {} }),
          { status: 403, headers: { 'Content-Type': 'application/json' } },
        ),
      );
    await expect(fetchPage('/admin/employees', {}, send)).rejects.toMatchObject({
      status: 403,
      message: '권한 없음',
    });
  });
  it('로그인 HTML과 형식이 다른 JSON을 화면 데이터로 받지 않는다', async () => {
    for (const response of [
      new Response('<html>login</html>'),
      new Response('{"data":{}}', { headers: { 'Content-Type': 'application/json' } }),
    ]) {
      await expect(
        fetchPage('/me/today', {}, vi.fn().mockResolvedValue(response)),
      ).rejects.toBeInstanceOf(PageRequestError);
    }
  });
  it('422 화면은 입력 보존을 위해 상태와 모델을 함께 반환한다', async () => {
    const send = vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({ ...page, data: { error: '사번 중복', form: { employeeNo: 'E1' } } }),
          { status: 422, headers: { 'Content-Type': 'application/json' } },
        ),
      );
    const result = await fetchPage('/admin/employees', {}, send);
    expect(result.status).toBe(422);
    expect(result.envelope.data).toHaveProperty('form.employeeNo', 'E1');
  });
  it('세션을 파기한 503과 입력 재시도가 가능한 일반 503을 구분한다', async () => {
    for (const reauthenticate of [true, false]) {
      const send = vi
        .fn()
        .mockResolvedValue(
          new Response(
            JSON.stringify({
              page: 'org/problem',
              data: { message: '일시 장애', ...(reauthenticate ? { reauthenticate: true } : {}) },
              csrf: {},
            }),
            { status: 503, headers: { 'Content-Type': 'application/json' } },
          ),
        );
      await expect(fetchPage('/me/today', {}, send)).rejects.toMatchObject({
        status: 503,
        reauthenticate,
      });
    }
  });
});
