import type { Plugin } from 'vite';

/** Legacy AG Grid ships embedded fonts/images; keep the existing self-only CSP. */
export function sheetAssets(): Plugin {
  let base = '/react/';
  return {
    name: 'worklog-sheet-assets',
    enforce: 'post',
    configResolved(config) { base = config.base; },
    transformIndexHtml: {
      order: 'pre',
      handler(_html, context) {
        if (!context.server) return;
        return [{ tag: 'link', attrs: { rel: 'stylesheet', href: '/src/components/worklog-sheet.css?direct' }, injectTo: 'head' }];
      },
    },
    async generateBundle(_options, bundle) {
      const emitted = new Set<string>();
      for (const asset of Object.values(bundle)) {
        if (asset.type !== 'asset' || !asset.fileName.endsWith('.css')) continue;
        let source = String(asset.source);
        for (const match of source.matchAll(/url\(\s*(?:(["'])(.*?)\1|([^)]*?))\s*\)/gs)) {
          const url = (match[2] ?? match[3] ?? '').trim();
          if (!url.startsWith('data:')) continue;
          const data = /^data:([^;,]+)((?:;[^,]*)?),(.*)$/s.exec(url);
          const extension = data?.[1] === 'font/woff2' ? 'woff2' : data?.[1] === 'image/svg+xml' ? 'svg' : null;
          if (!data || !extension) throw new Error('지원하지 않는 시트 내장 자산입니다.');
          const bytes = data[2].split(';').includes('base64')
            ? Uint8Array.from(atob(data[3]), (character) => character.charCodeAt(0))
            : new TextEncoder().encode(decodeURIComponent(data[3]));
          const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
          const hash = Array.from(digest.slice(0, 6), (byte) => byte.toString(16).padStart(2, '0')).join('');
          const fileName = `assets/sheet-${hash}.${extension}`;
          if (!emitted.has(fileName)) {
            this.emitFile({ type: 'asset', fileName, source: bytes });
            emitted.add(fileName);
          }
          source = source.replace(match[0], `url("${base}${fileName}")`);
        }
        asset.source = source;
      }
    },
  };
}
