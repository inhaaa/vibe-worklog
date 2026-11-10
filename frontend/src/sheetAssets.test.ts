import { build } from 'vite';
import { describe, expect, it } from 'vitest';
import configuration from '../vite.config';

async function buildStyles(input?: string) {
  const config = configuration({ command: 'build', mode: 'test' });
  const result = await build({
    ...config,
    configFile: false,
    logLevel: 'silent',
    build: {
      ...config.build,
      write: false,
      rollupOptions: { ...config.build?.rollupOptions, ...(input ? { input } : {}) },
    },
  });
  if (Array.isArray(result) || !('output' in result)) throw new Error('단일 빌드 출력이 필요합니다.');
  return result.output;
}

describe('시트 스타일의 CSP 자산 계약', () => {
  it('업무 화면과 최신 공통 그리드 CSS를 독립 자산으로 함께 생성한다', async () => {
    const output = await buildStyles();
    const app = output.find((asset) => asset.fileName === 'app.css');
    const sheet = output.find((asset) => asset.fileName === 'sheet.css');
    expect(app?.type, '신규 메뉴·사원·신청 UI 스타일').toBe('asset');
    expect(sheet?.type, '공개 AG Grid 스타일').toBe('asset');
    if (app?.type !== 'asset' || sheet?.type !== 'asset') throw new Error('앱과 시트 CSS가 모두 필요합니다.');
    expect(String(app.source)).toContain('.t-workspace-navigation');
    expect(String(app.source)).toContain('.t-employee-header');
    expect(String(app.source)).toContain('.t-request-dialog');
    expect(String(sheet.source)).toContain('.ag-theme-quartz');
    expect(String(sheet.source)).toContain('--ag-row-height:38px');
    expect(String(app.source)).toMatch(/@layer legacy\s*\{/);
    expect(String(sheet.source)).toMatch(/@layer vendor\s*\{/);
    expect(String(app.source)).toContain('Pretendard Variable');
    const fonts = output.filter(asset => asset.fileName.includes('PretendardVariable.subset') && asset.fileName.endsWith('.woff2'));
    expect(fonts.length).toBeGreaterThan(80);
    expect(String(app.source)).toContain(`/react/${fonts[0].fileName}`);
    expect(String(app.source) + String(sheet.source)).not.toMatch(/url\(["']?data:/);
  });

  it('Quartz 글꼴을 같은 출처 파일로 제공하고 sheet.css 이름을 고정한다', async () => {
    const output = await buildStyles('node_modules/ag-grid-community/styles/ag-theme-quartz.css');
    const css = output.find((asset) => asset.fileName === 'sheet.css');
    expect(css, 'HTML shell에서 참조할 고정 CSS 경로').toBeDefined();
    if (!css || css.type !== 'asset') throw new Error('CSS 자산이 필요합니다.');
    expect(String(css.source).match(/url\(["']?data:/)).toBeNull();
    const font = output.find((asset) => asset.fileName.endsWith('.woff2'));
    expect(font?.type).toBe('asset');
    expect(String(css.source)).toContain(`/react/${font?.fileName}`);
    if (font?.type === 'asset') {
      expect(new TextDecoder().decode((font.source as Uint8Array).subarray(0, 4))).toBe('wOF2');
    }
  });

  it('base64 및 UTF-8 SVG를 외부 요청 없는 자산으로 추출한다', async () => {
    const output = await buildStyles('node_modules/ag-grid-community/styles/ag-grid.css');
    const css = output.find((asset) => asset.fileName.endsWith('.css'));
    if (!css || css.type !== 'asset') throw new Error('CSS 자산이 필요합니다.');
    const source = String(css.source);
    expect(source.match(/url\(["']?(?:data:|https?:|\/\/)/)).toBeNull();
    const images = output.filter((asset) => asset.fileName.endsWith('.svg'));
    expect(images).toHaveLength(4);
    for (const asset of images) {
      expect(source).toContain(`/react/${asset.fileName}`);
      if (asset.type === 'asset') expect(new TextDecoder().decode(asset.source as Uint8Array)).toContain('<svg');
    }
  });

  it('공통 시트 CSS의 legacy 테마와 업무 스타일을 한 파일로 제공한다', async () => {
    const output = await buildStyles('src/components/worklog-sheet.css');
    const css = output.find((asset) => asset.fileName === 'sheet.css');
    if (!css || css.type !== 'asset') throw new Error('공통 CSS 자산이 필요합니다.');
    const source = String(css.source);
    expect(source).toContain('.ag-theme-quartz');
    expect(source).toContain('.w-sheet-grid');
    expect(source).toContain('--ag-row-height:38px');
    expect(source.match(/url\(["']?(?:data:|https?:|\/\/)/)).toBeNull();
    expect(source.includes('--ag-background-color:var(--g0)')).toBe(true);
    expect(source.includes('--ag-header-background-color:var(--g50)')).toBe(true);
    expect(source.includes('--ag-selected-row-background-color:var(--brand-tint)')).toBe(true);
    expect(source.includes('--ag-range-selection-border-color:var(--brand)')).toBe(true);
    expect(source.includes('--ag-font-family:var(--font-ui)')).toBe(true);
    expect(output.filter((asset) => /\.(svg|woff2)$/.test(asset.fileName))).toHaveLength(5);
  });
});
