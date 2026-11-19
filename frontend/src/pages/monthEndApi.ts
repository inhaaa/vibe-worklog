import { PAGE_ACCEPT, PageRequestError } from '../transport';
import type { CsrfToken } from '../types';

export const monthEndAccessDenied = (error: unknown): error is PageRequestError =>
  error instanceof PageRequestError && (error.status === 401 || error.status === 403 || error.reauthenticate);

// OrgCommands.requiredText uses Java String.strip(), whose whitespace set excludes non-breaking spaces.
const whitespace = '[\\u0009-\\u000d\\u001c-\\u0020\\u1680\\u2000-\\u2006\\u2008-\\u200a\\u2028\\u2029\\u205f\\u3000]';
const reasonEdges = new RegExp('^' + whitespace + '+|' + whitespace + '+$', 'gu');

export function monthEndReason(reason: string) {
  const trimmed = reason.replace(reasonEdges, '');
  const length = Array.from(trimmed).length;
  const forbiddenCharacters = /[\p{Cc}\p{Cs}]/u.test(trimmed);
  return { trimmed, length, forbiddenCharacters, validReason: length >= 1 && length <= 500 && !forbiddenCharacters };
}

export async function monthEndRequest<T>(url: string, post?: { csrf: CsrfToken; body: object }, signal?: AbortSignal): Promise<T> {
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
