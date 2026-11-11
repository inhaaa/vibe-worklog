import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const legacy = [
  'src/WorkspacePolish.css', 'src/ThemeSwitcher.css',
  'src/components/EmployeeWorkspace.css',
  'src/components/WorkspaceNavigation.css',
  'src/pages/ApprovalWorkspace.css', 'src/pages/DemoAccess.css', 'src/pages/PersonalDetails.css',
  'src/pages/RecordDetails.css', 'src/pages/RequestDetail.css', 'src/pages/RequestWorkspace.css',
  'src/pages/ScheduleCalendar.css', 'src/pages/WorkspaceHome.css',
];
const code = file => readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').trim();
const body = css => css
  .replace(/^@layer vendor, legacy;\s*/, '')
  .replace(/^(?:@import [^;]+ layer\((?:legacy|vendor)\);\s*)+/, '')
  .trim();

describe('CSS cascade layers', () => {
  it('declares vendor < legacy before any rule in the server stylesheet', () => {
    const css = code('../src/main/resources/static/app.css');
    expect(css.startsWith('@layer vendor, legacy;')).toBe(true);
    expect(body(css).startsWith('@layer legacy {')).toBe(true);
    expect(css.endsWith('}')).toBe(true);
  });

  it.each(legacy)('keeps %s inside the legacy layer', file => {
    const css = body(code(file));
    expect(css.startsWith('@layer legacy {')).toBe(true);
    expect(css.endsWith('}')).toBe(true);
  });

  it('imports AG Grid below legacy styles', () => {
    const css = readFileSync('src/components/worklog-sheet.css', 'utf8');
    expect(css).toContain("@import 'ag-grid-community/styles/ag-grid.css' layer(vendor);");
    expect(css).toContain("@import 'ag-grid-community/styles/ag-theme-quartz.css' layer(vendor);");
  });

  it('keeps the shared sheet in the design layer above legacy rules', () => {
    const css = code('src/components/worklog-sheet.css');
    expect(css.startsWith('@layer vendor, legacy;')).toBe(true);
    expect(body(css)).not.toMatch(/^@layer legacy/);
    expect(css).not.toContain('#tna-app');
    expect(css).not.toContain('!important');
  });

  it.each(['src/components/Modal.css', 'src/design/request.css', 'src/design/workspace.css', 'src/design/screens.css'])('keeps %s in the design layer above legacy rules', file => {
    const css = code(file);
    expect(css).not.toMatch(/^@layer/);
    expect(css).not.toContain('#tna-app');
    expect(css).not.toContain('!important');
  });

  it('imports legacy tokens and base rules into the legacy layer', () => {
    const css = readFileSync('src/WorkspacePolish.css', 'utf8');
    expect(css).toContain("@import url('./tokens.css') layer(legacy);");
    expect(css).toContain("@import url('./base.css') layer(legacy);");
  });
});
