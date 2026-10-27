// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { loadStartupPage } from './startup';
import type { PageEnvelope } from './types';

const origin = 'http://127.0.0.1:5173';
const page: PageEnvelope = {
  page: 'org/problem',
  data: { message: '합성 화면' },
  csrf: { token: 'csrf+token=', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' },
};

function response(body: unknown, path: string, status = 200) {
  const result = new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/vnd.worklog.page+json' },
  });
  Object.defineProperty(result, 'url', { value: new URL(path, origin).href });
  return result;
}

function bootstrap(value: string) {
  const script = document.createElement('script');
  script.id = 'worklog-page';
  script.type = 'application/json';
  script.textContent = value;
  document.body.append(script);
  return script;
}

afterEach(() => {
  document.body.replaceChildren();
});

describe('React 초기 화면 로드', () => {
  it('서버 bootstrap이 있으면 우선 소비하고 다시 요청하지 않는다', async () => {
    const send = vi.fn<typeof fetch>();
    const result = await loadStartupPage(bootstrap(JSON.stringify(page)), `${origin}/login`, send);
    expect(result).toEqual({ envelope: page, url: `${origin}/login` });
    expect(document.getElementById('worklog-page')).toBeNull();
    expect(send).not.toHaveBeenCalled();
  });

  it('개발 HTML에는 현재 URL의 JSON을 같은 출처·no-store로 조회한다', async () => {
    const send = vi
      .fn<typeof fetch>()
      .mockResolvedValue(response(page, '/admin/employees?status=active'));
    const url = `${origin}/admin/employees?status=active#employee-detail`;
    const result = await loadStartupPage(null, url, send);
    expect(send.mock.calls[0][0]).toBe(url);
    expect(send.mock.calls[0][1]).toMatchObject({ credentials: 'same-origin', cache: 'no-store' });
    expect(new Headers(send.mock.calls[0][1]?.headers).get('Accept')).toBe(
      'application/vnd.worklog.page+json',
    );
    expect(result).toEqual({ envelope: page, url });
  });

  it.each([
    ['/me/today', '/login', 'login'],
    ['/operator/tenants', '/operator/login', 'operator/login'],
  ])('%s의 401은 같은 출처 %s 화면으로 전환한다', async (path, loginPath, loginPage) => {
    const login: PageEnvelope = { ...page, page: loginPage, data: {} };
    const send = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        response({ ...page, data: { message: '로그인이 필요합니다.' } }, path, 401),
      )
      .mockResolvedValueOnce(response(login, loginPath));
    const result = await loadStartupPage(null, `${origin}${path}`, send);
    expect(send).toHaveBeenCalledTimes(2);
    expect(send.mock.calls[1][0]).toBe(`${origin}${loginPath}`);
    expect(result).toEqual({ envelope: login, url: `${origin}${loginPath}` });
  });

  it.each(['null', '{invalid'])(
    '잘못된 bootstrap %s은 다시 조회하지 않고 오류로 처리한다',
    async (value) => {
      const send = vi.fn<typeof fetch>();
      await expect(loadStartupPage(bootstrap(value), `${origin}/login`, send)).rejects.toThrow();
      expect(document.getElementById('worklog-page')).toBeNull();
      expect(send).not.toHaveBeenCalled();
    },
  );

  it('화면 계약이 아닌 JSON은 렌더할 초기 자료로 받지 않는다', async () => {
    const send = vi.fn<typeof fetch>().mockResolvedValue(response({ data: {} }, '/login'));
    await expect(loadStartupPage(null, `${origin}/login`, send)).rejects.toThrow('화면 응답 형식');
  });

  it('403을 로그인 재시도로 숨기지 않는다', async () => {
    const send = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        response({ ...page, data: { message: '권한이 없습니다.' } }, '/admin/employees', 403),
      );
    await expect(loadStartupPage(null, `${origin}/admin/employees`, send)).rejects.toMatchObject({
      status: 403,
    });
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('다른 출처의 최종 주소를 초기 화면 경로로 채택하지 않는다', async () => {
    const send = vi
      .fn<typeof fetch>()
      .mockResolvedValue(response(page, 'https://outside.example.test/login'));
    await expect(loadStartupPage(null, `${origin}/login`, send)).rejects.toThrow('주소');
  });
});
