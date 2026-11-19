import { vi } from 'vitest';

export function stubOpenMonth(send: typeof fetch = vi.fn()) {
  vi.stubGlobal('fetch', (url: RequestInfo | URL, options?: RequestInit) => {
    const request = new URL(String(url), location.origin);
    if (request.pathname === '/admin/attendance/month-close' && options?.method === 'GET') {
      const month = request.searchParams.get('month');
      return Promise.resolve(Response.json({ month, status: 'OPEN', version: 0, pastMonth: true, history: [] }));
    }
    return send(url, options);
  });
}
