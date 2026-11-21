import type { CsrfToken, PageEnvelope } from './types';
export const PAGE_ACCEPT = 'application/vnd.worklog.page+json';
export class PageRequestError extends Error {
  constructor(
    public status: number,
    message: string,
    public reauthenticate = false,
  ) {
    super(message);
  }
}
export class PageDownload extends Error {
  constructor(
    public blob: Blob,
    public filename: string,
  ) {
    super('파일 다운로드');
  }
  save() {
    const objectUrl = URL.createObjectURL(this.blob);
    const link = document.createElement('a');
    link.href = objectUrl;
    link.download = this.filename;
    try { link.click(); }
    finally { setTimeout(() => URL.revokeObjectURL(objectUrl), 0); }
  }
}
export async function requestJson<T>(url: string, post?: { csrf: CsrfToken; body: object }, signal?: AbortSignal): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json, ' + PAGE_ACCEPT + ';q=0.9' };
  if (post) { headers['Content-Type'] = 'application/json'; headers[post.csrf.headerName] = post.csrf.token; }
  const response = await fetch(url, { credentials: 'same-origin', cache: 'no-store', signal, headers,
    method: post ? 'POST' : 'GET', body: post ? JSON.stringify(post.body) : undefined });
  if (response.redirected) throw new PageRequestError(401, '다시 로그인해 주세요.');
  const data = await response.json().catch(() => null);
  if (data?.page === 'login') throw new PageRequestError(401, '다시 로그인해 주세요.');
  if (!response.ok || data?.data?.reauthenticate === true) {
    const message = data?.message || data?.data?.message;
    throw new PageRequestError(response.status, typeof message === 'string' ? message : '현재 상태를 다시 확인해 주세요.',
      data?.data?.reauthenticate === true);
  }
  return data as T;
}
export function encodeForm(data: FormData): URLSearchParams {
  const encoded = new URLSearchParams();
  for (const [key, value] of data) {
    if (typeof value !== 'string')
      throw new PageRequestError(400, '파일 업로드는 지원하지 않습니다.');
    encoded.append(key, value);
  }
  return encoded;
}
export function isPageEnvelope(value: unknown): value is PageEnvelope {
  if (!value || typeof value !== 'object') return false;
  const envelope = value as Partial<PageEnvelope>;
  return (
    typeof envelope.page === 'string' &&
    !!envelope.data &&
    typeof envelope.data === 'object' &&
    !Array.isArray(envelope.data) &&
    !!envelope.csrf &&
    typeof envelope.csrf === 'object'
  );
}
export async function fetchPage(
  url: string,
  options: RequestInit = {},
  send: typeof fetch = fetch,
): Promise<{ envelope: PageEnvelope; url: string; status: number }> {
  const headers = new Headers(options.headers);
  if (!headers.has('Accept')) headers.set('Accept', PAGE_ACCEPT);
  const response = await send(url, {
    ...options,
    headers,
    credentials: 'same-origin',
    cache: 'no-store',
  });
  const attachment = response.headers.get('Content-Disposition');
  if (response.ok && attachment?.toLowerCase().startsWith('attachment')) {
    const filename =
      attachment
        .match(/filename="?([^";]+)"?/i)?.[1]
        ?.split(/[\\/]/)
        .pop() ?? 'attendance-review.xlsx';
    throw new PageDownload(await response.blob(), filename);
  }
  let body: unknown;
  if (response.headers.get('Content-Type')?.includes('json')) {
    try {
      body = await response.json();
    } catch {
      throw new PageRequestError(
        response.status,
        '서버 응답을 확인할 수 없습니다. 다시 조회해 주세요.',
      );
    }
  }
  const envelope = isPageEnvelope(body) ? body : null;
  if ([401, 403, 503].includes(response.status)) {
    const message = envelope?.data.message;
    throw new PageRequestError(
      response.status,
      typeof message === 'string' ? message : '요청을 처리할 수 없습니다. 다시 확인해 주세요.',
      envelope?.data.reauthenticate === true,
    );
  }
  if (!envelope)
    throw new PageRequestError(
      response.status,
      '화면 응답 형식이 올바르지 않습니다. 다시 로그인하거나 새로 고침해 주세요.',
    );
  return { envelope, url: response.url || url, status: response.status };
}
