import { defineConfig } from 'vitest/config';
import type { ProxyOptions } from 'vite';
import react from '@vitejs/plugin-react';
import { sheetAssets } from './build/sheetAssets.ts';

const backend = 'http://127.0.0.1:18080';
const pages: ProxyOptions = {
  target: backend,
  bypass(request) {
    const accept = request.headers.accept ?? '';
    const pathname = request.url?.split('?')[0] ?? '';
    if (
      request.method === 'GET' &&
      !/^\/admin\/reports\/exports\/[^/]+\/download$/.test(pathname) &&
      accept.includes('text/html') &&
      !accept.includes('application/vnd.worklog.page+json')
    )
      return '/index.html';
  },
};

export default defineConfig(({ command }) => ({
  plugins: [react(), sheetAssets()],
  base: command === 'serve' ? '/' : '/react/',
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '^/(?:$|\\?|(?:login|logout|demo|password-reset|invite|operator|me|admin|company|approvals|requests|worktime|error)(?:/|\\?|$))':
        pages,
      '^/app\\.css(?:\\?|$)': { target: backend },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: false,
    rollupOptions: {
      input: { app: 'src/main.tsx', sheet: 'src/components/worklog-sheet.css' },
      output: {
        entryFileNames: 'app.js',
        chunkFileNames: 'chunks/[name]-[hash].js',
        assetFileNames: (asset) => asset.names.some((name) => name.endsWith('.css'))
          ? asset.names.includes('app.css') ? 'app.css' : 'sheet.css'
          : '[name]-[hash][extname]',
      },
    },
  },
  test: { environment: 'node', maxWorkers: 2, testTimeout: 15000 },
}));
