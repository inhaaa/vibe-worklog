import { createServer as createHttpServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import { describe, expect, it } from 'vitest';
import configuration from '../vite.config';

const config = configuration({ command: 'serve', mode: 'test' });
const proxies = config.server.proxy;
const downloadPath = '/admin/reports/exports/12345678-1234-1234-1234-123456789012/download';
const nativeAccept = 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8';

function matchingProxy(path) {
  const entry = Object.entries(proxies).find(([pattern]) => new RegExp(pattern).test(path));
  return entry?.[1];
}

function bypass(path, method = 'GET', accept = nativeAccept) {
  const options = matchingProxy(path);
  return options.bypass?.(
    { url: path, method, headers: { accept } },
    {},
    options,
  );
}

describe('Vite 업무 프록시', () => {
  it('운영 HTML이 읽을 수 있도록 추출 CSS 이름을 고정한다', () => {
    const build = configuration({ command: 'build', mode: 'production' }).build;
    expect(build.rollupOptions.output.assetFileNames({ names: ['app.css'] })).toBe('app.css');
    expect(build.rollupOptions.output.assetFileNames({ names: ['sheet.css'] })).toBe('sheet.css');
  });
  it.each(['', '?filename=review.xlsx'])(
    '네이티브 다운로드 GET을 백엔드에 전달한다 (%s)',
    (query) => {
      expect(bypass(`${downloadPath}${query}`)).toBeUndefined();
    },
  );

  it('HTML 페이지만 index로 보내고 기존 JSON·POST·CSS·경로 범위를 유지한다', () => {
    expect(bypass('/admin/attendance?month=2026-10')).toBe('/index.html');
    expect(bypass('/me/today', 'GET', 'application/vnd.worklog.page+json')).toBeUndefined();
    expect(
      bypass('/me/today', 'GET', `${nativeAccept},application/vnd.worklog.page+json`),
    ).toBeUndefined();
    expect(bypass('/admin/reports/exports', 'POST')).toBeUndefined();
    expect(matchingProxy('/app.css?version=1')?.bypass).toBeUndefined();
    expect(matchingProxy('/administrator')).toBeUndefined();
    expect(matchingProxy('/unknown/download')).toBeUndefined();
  });

  it('회사 조회의 HTML과 JSON을 기존 표현 계약으로 전달한다', () => {
    expect(matchingProxy('/company/attendance?month=2026-10')).toBeDefined();
    expect(bypass('/company/attendance?month=2026-10')).toBe('/index.html');
    expect(bypass('/company/requests', 'GET', 'application/vnd.worklog.page+json')).toBeUndefined();
    expect(matchingProxy('/companywide')).toBeUndefined();
  });

  it('실제 개발 서버가 XLSX 헤더·바이트와 쿼리를 보존한다', async () => {
    const bytes = Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x00, 0xff, 0x80, 0x42]);
    const disposition = 'attachment; filename=attendance-review.xlsx';
    const received = [];
    const backend = createHttpServer((request, response) => {
      received.push(`${request.method} ${request.url}`);
      if (request.url?.split('?')[0] === downloadPath) {
        response.setHeader(
          'Content-Type',
          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        );
        response.setHeader('Content-Disposition', disposition);
        response.end(bytes);
      } else {
        response.setHeader('Content-Type', 'text/plain');
        response.end(`backend ${request.method} ${request.url}`);
      }
    });
    let vite;
    try {
      await new Promise((resolve, reject) => {
        backend.once('error', reject);
        backend.listen(0, '127.0.0.1', resolve);
      });
      const backendUrl = `http://127.0.0.1:${backend.address().port}`;
      vite = await createServer({
        ...config,
        configFile: false,
        root: fileURLToPath(new URL('..', import.meta.url)),
        server: {
          ...config.server,
          port: 0,
          proxy: Object.fromEntries(
            Object.entries(proxies).map(([pattern, options]) => [
              pattern,
              typeof options === 'string' ? backendUrl : { ...options, target: backendUrl },
            ]),
          ),
        },
      });
      await vite.listen();
      const origin = `http://127.0.0.1:${vite.httpServer.address().port}`;

      for (const query of ['', '?filename=review.xlsx']) {
        const response = await fetch(`${origin}${downloadPath}${query}`, {
          headers: { Accept: nativeAccept },
        });
        expect(response.status).toBe(200);
        expect(response.headers.get('Content-Type')).toBe(
          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        );
        expect(response.headers.get('Content-Disposition')).toBe(disposition);
        expect(Buffer.from(await response.arrayBuffer())).toEqual(bytes);
      }
      const html = await fetch(`${origin}/me/today`, { headers: { Accept: nativeAccept } });
      expect(await html.text()).toContain('<div id="root"></div>');
      for (const [path, method, accept] of [
        ['/me/today', 'GET', 'application/vnd.worklog.page+json'],
        ['/admin/reports/exports', 'POST', nativeAccept],
        ['/app.css?version=1', 'GET', 'text/css'],
      ]) {
        const response = await fetch(`${origin}${path}`, { method, headers: { Accept: accept } });
        expect(await response.text()).toBe(`backend ${method} ${path}`);
      }
      const unknown = await fetch(`${origin}/unknown/download`, {
        headers: { Accept: 'application/vnd.worklog.page+json' },
      });
      expect(unknown.status).toBe(404);
      expect(received).toEqual([
        `GET ${downloadPath}`,
        `GET ${downloadPath}?filename=review.xlsx`,
        'GET /me/today',
        'POST /admin/reports/exports',
        'GET /app.css?version=1',
      ]);
    } finally {
      await vite?.close();
      await new Promise((resolve) => {
        backend.close(() => resolve());
        backend.closeAllConnections();
      });
    }
  }, 30_000);
});
