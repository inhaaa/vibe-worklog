import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const css = readFileSync('src/design/tokens.css', 'utf8');
const block = selector => css.slice(css.indexOf(selector), css.indexOf('}', css.indexOf(selector)));
const value = (scope, name) => {
  const found = new RegExp(`--${name}:\\s*(#[0-9a-f]{6})`, 'i').exec(scope)?.[1];
  if (!found) throw new Error(`--${name} not found`);
  return found;
};
const luminance = hex => {
  const [r, g, b] = [1, 3, 5].map(index => parseInt(hex.slice(index, index + 2), 16) / 255)
    .map(channel => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const contrast = (a, b) => {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (light + 0.05) / (dark + 0.05);
};

describe('design tokens', () => {
  const root = block(':root {');
  const blue = block(':root[data-theme="blue"] {');

  it.each(['g600', 'g700', 'g900', 'ok', 'warn', 'danger', 'info', 'ev-work-ink', 'ev-off-ink', 'ev-leave-ink', 'ev-wait-ink'])(
    '%s text keeps 4.5:1 on white', name => {
      expect(contrast(value(root, name), '#ffffff')).toBeGreaterThanOrEqual(4.5);
    });

  it('keeps muted text readable on the label surface', () => {
    expect(contrast(value(root, 'g600'), value(root, 'g50'))).toBeGreaterThanOrEqual(4.5);
  });

  it('keeps field boundaries at 3:1 on white', () => {
    expect(contrast(value(root, 'g500'), '#ffffff')).toBeGreaterThanOrEqual(3);
    expect(root).toMatch(/--field-line:\s*var\(--g500\)/);
  });

  it.each([['charcoal', root], ['blue', blue]])('keeps white text on the %s brand fill at 4.5:1', (_, scope) => {
    expect(contrast(value(scope, 'brand'), '#ffffff')).toBeGreaterThanOrEqual(4.5);
    expect(contrast(value(scope, 'brand-ink'), value(scope, 'brand-tint'))).toBeGreaterThanOrEqual(4.5);
  });

  it('maps legacy tokens onto the new palette', () => {
    expect(root).toMatch(/--font-body:\s*var\(--font-ui\)/);
    expect(root).toMatch(/--t-blue:\s*var\(--brand\)/);
    expect(root).toMatch(/--t-ink:\s*var\(--g900\)/);
  });
});
