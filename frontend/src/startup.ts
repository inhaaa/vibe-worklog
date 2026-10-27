import type { PageEnvelope } from './types';
import { fetchPage, isPageEnvelope, PageRequestError } from './transport';

export async function loadStartupPage(
  bootstrap: HTMLElement | null,
  url: string,
  send: typeof fetch = fetch,
): Promise<{ envelope: PageEnvelope; url: string }> {
  if (bootstrap) {
    const source = bootstrap.textContent;
    bootstrap.remove();
    const envelope: unknown = JSON.parse(source ?? 'null');
    if (!isPageEnvelope(envelope)) throw new Error('화면 데이터를 확인할 수 없습니다.');
    return { envelope, url };
  }
  const requested = new URL(url);
  let result: Awaited<ReturnType<typeof fetchPage>>;
  try {
    result = await fetchPage(url, {}, send);
  } catch (error) {
    if (!(error instanceof PageRequestError) || error.status !== 401) throw error;
    const operator =
      requested.pathname === '/operator' || requested.pathname.startsWith('/operator/');
    result = await fetchPage(
      new URL(operator ? '/operator/login' : '/login', requested.origin).href,
      {},
      send,
    );
  }
  const destination = new URL(result.url, url);
  if (destination.origin !== requested.origin) throw new Error('이동할 주소를 확인할 수 없습니다.');
  if (destination.pathname === requested.pathname && destination.search === requested.search)
    destination.hash = requested.hash;
  return { envelope: result.envelope, url: destination.href };
}
