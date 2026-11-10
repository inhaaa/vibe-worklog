# WorkLog 디자인 시스템 1단계(기반) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 화면 동작은 그대로 두고 새 디자인의 기반을 적용한다. 대상은 Pretendard, 새 토큰, 밝은 사이드바·상단바, 기본 컨트롤, 화면 머리 도움말이다. 이 기반 위에서 2단계(시트)와 3단계(모달)를 옮긴다.

**Architecture:**

- 기존 CSS 전체를 `@layer legacy` 층으로 낮춘다. AG Grid CSS는 그보다 아래인 `@layer vendor` 층에 둔다.
- `frontend/src/design/`의 새 CSS는 층 없이 두어 항상 우선하게 한다.
- 기본 컨트롤과 셸은 기존 클래스 이름을 유지한 채 새 CSS로 다시 정의한다. 새로 만드는 부품(`PageHeader`)만 `wl-` 클래스를 쓴다.
- 기존 `--t-*` 토큰은 새 토큰의 별칭으로 바꾼다. 그래서 아직 옮기지 않은 화면도 새 색과 글꼴을 따른다.

**Tech Stack:** React 19.3.1, TypeScript 6.0.3, Vite 8.3.5, Vitest 5.0.5 + Testing Library, AG Grid Community 36.2.0(legacy CSS), Pretendard 1.3.9. 검증 전용 도구로 Python 3 `http.server`와 `puppeteer-core`(로컬 Chrome)를 쓰며, 이 도구는 git 밖에 둔다.

**Spec:** [설계](../specs/2026-11-10-worklog-design-system-design.md)의 9절 1단계. 2~5단계는 이 단계 결과를 사용자와 확인한 뒤 별도 계획으로 쓴다.

---

## 작업 위치와 공통 규칙

- **작업트리:** `C:\Users\ihpark\dev\vibe-attendance\target\worklog-design-20261110`, 브랜치 `claude/worklog-design-system-20261110`.
- **명령 실행:** 모든 명령은 Git Bash에서 실행한다. 아래 두 줄을 먼저 실행한다.
  ```bash
  cd /c/Users/ihpark/dev/vibe-attendance/target/worklog-design-20261110
  TOOLS=/c/Users/ihpark/dev/vibe-attendance/target/hallmark-tools/worklog-design-20261110
  ```
- **검증 도구 위치:** `$TOOLS` 폴더는 git 밖이며 커밋하지 않는다. 이전 합성 검증 도구와 같은 위치 규칙을 따른다.
- **테스트:** 전체는 `npm --prefix frontend test`, 파일 하나는 `npm --prefix frontend test -- <frontend 기준 경로>`.
- **빌드:** `npm --prefix frontend run build`를 실행한다. tsc 검사 후 vite 빌드가 `frontend/dist`에 결과를 만든다.
- **커밋:** 각 작업 끝에 커밋한다. 커밋 메시지 끝에는 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` 줄을 붙인다. Push와 공개 배포는 하지 않는다(사용자 확인 사항).
- **Java:** 이 단계는 Java 코드를 바꾸지 않는다. `ReactPageViewTest`는 CSS 링크 순서만 확인하며, 그 순서는 바뀌지 않는다.

## 파일 구조

| 파일 | 책임 | 작업 |
| --- | --- | --- |
| `src/main/resources/static/app.css` | 층 순서 선언, 백엔드 legacy CSS 전체를 legacy 층으로 | 수정 (1) |
| 기존 React CSS 16개 | 각 파일을 legacy 층으로, AG Grid import는 vendor 층으로 | 수정 (1) |
| `frontend/src/cssLayers.test.ts` | 층 계약 | 생성 (1) |
| `frontend/src/design/tokens.css` | 새 토큰, 테마, 밀도, legacy 별칭, Pretendard import | 생성 (2) |
| `frontend/src/design/tokens.test.ts` | 글자·경계 대비 계약 | 생성 (2) |
| `frontend/src/design/ui.css` | 기본 컨트롤, 화면 머리·도움말 | 생성 (3), 수정 (5) |
| `frontend/src/design/shell.css` | 사이드바, 상단바, 본문 영역 | 생성 (4) |
| `frontend/src/App.tsx` | design CSS import | 수정 (2, 3, 4) |
| `frontend/src/components/PageHeader.tsx`, `PageHeader.test.tsx` | 화면 제목·도움말·화면 동작 | 생성 (5) |
| 페이지 9개 | `t-pagehead` 19곳을 `PageHeader`로 | 수정 (6) |
| `frontend/src/pages/OrgSheetPages.tsx`, `OrgSheetPages.test.tsx` | 조직장 칸 띄어쓰기 | 수정 (7) |
| `frontend/package.json`, `frontend/package-lock.json` | pretendard 1.3.9 | 수정 (2) |
| `frontend/src/sheetAssets.test.ts` | 빌드 자산에 층과 글꼴이 포함되는지 확인 | 수정 (1, 2) |
| `docs/adr/0006-worklog-design-layer.md` | 글꼴·층 결정 근거 | 생성 (2) |
| 고지 파일 4개 | Pretendard OFL 고지 | 수정·생성 (2) |
| `design.md`, spec, `docs/quality/worklog-design-system-20261110.md` | 기준·결과 문서 | 수정·생성 (8) |

고지 파일 4개: `THIRD_PARTY_NOTICES.md`, `LICENSES/Pretendard-OFL-1.1.txt`, `frontend/public/THIRD_PARTY_LICENSES.txt`, `docs/development/frontend-licenses.csv`.

---

### Task 0: 합성 미리보기·캡처 도구와 기준 캡처 (커밋 없음)

DB 없이 빌드 결과에 합성 envelope를 넣어 화면을 띄운다. 그 화면을 1440·390px, 차콜·블루 조합으로 캡처하고, 두 캡처를 픽셀 단위로 비교한다.

**Files (git 밖):**
- Create: `$TOOLS/base_fixtures.py` (기존 파일 복사)
- Create: `$TOOLS/preview.py`
- Create: `$TOOLS/capture.mjs`
- Create: `$TOOLS/compare.mjs`

- [ ] **Step 1: 기존 합성 자료 복사**

```bash
mkdir -p "$TOOLS"
cp /c/Users/ihpark/dev/vibe-attendance/target/hallmark-tools/main-integration-smoke-20261107/fixture.py "$TOOLS/base_fixtures.py"
```

`base_fixtures.py`는 `envelope`, `HEADER`, `PROFILE`, `ORGANIZATIONS`, `MINE`, `NEW`, `APPROVAL`을 정의한다. 불러올 때 자기 폴더에 `fixtures.json`을 쓰는데, 이것은 무해하다.

- [ ] **Step 2: `preview.py` 작성**

```python
"""WorkLog 디자인 시스템 합성 미리보기. 127.0.0.1 전용, 읽기 전용, DB 없음."""
import importlib.util
import json
import mimetypes
import sys
from copy import deepcopy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parent
ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else 'C:/Users/ihpark/dev/vibe-attendance/target/worklog-design-20261110')
DIST = (ROOT / 'frontend' / 'dist').resolve()
PORT = 18131

spec = importlib.util.spec_from_file_location('base_fixtures', HERE / 'base_fixtures.py')
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


def calendar_day(d):
    date = f'2026-11-{d:02d}'
    work = (d + 5) % 7 < 5  # 2026-11-01은 일요일, 0=월요일
    schedule = ({'workday': True, 'startTime': '09:00', 'endTime': '18:00', 'breaks': [{'startTime': '12:00', 'endTime': '13:00'}]}
                if work else {'workday': False, 'startTime': None, 'endTime': None, 'breaks': []})
    entry = {
        'snapshot': {'employeeId': 'e-1', 'workDate': date, 'inputRevision': 0, 'reviewRevision': 0, 'schedule': schedule,
                     'scheduleVersionId': 'schedule-1', 'calendarVersionId': 'calendar-1',
                     'dayKind': 'WORKDAY' if work else 'NON_WORKDAY', 'events': [], 'effectiveEvents': [], 'confirmation': None},
        'requests': [], 'today': d == 10, 'selected': d == 10, 'timeline': [],
        'scheduleText': '09:00–18:00' if work else '소정 비근로일', 'calendarText': '근로일' if work else '소정 비근로일',
        'confirmationText': '실제 근로 미확인', 'inTime': '미기록', 'outTime': '미기록',
    }
    if d == 12:
        entry['requests'] = [{'request': {'id': 'request-2', 'fromDate': date, 'toDate': date}, 'kindLabel': '종일 연차',
                              'statusLabel': '승인', 'tone': 'green', 'effectiveLeave': True}]
    if d == 17:
        entry['requests'] = [{'request': {'id': 'request-1', 'fromDate': date, 'toDate': date}, 'kindLabel': '종일 연차',
                              'statusLabel': '검토 중', 'tone': 'amber', 'effectiveLeave': False}]
    if d == 15:
        entry['snapshot']['dayKind'] = 'HOLIDAY'
    if d == 19:
        entry['snapshot'].update({'schedule': None, 'dayKind': 'UNCONFIRMED'})
        entry.update({'scheduleText': '근무 일정 미배정', 'calendarText': '캘린더 미확인'})
    return entry


DAYS = [calendar_day(d) for d in range(1, 31)]
SCHEDULE = base.envelope('workspace/schedule', {
    'employeeHeader': base.HEADER,
    'workspaceCalendar': {'month': '2026-11', 'selectedDate': '2026-11-10', 'days': DAYS, 'leadingBlanks': [0, 1, 2, 3, 4, 5],
                          'previousMonth': '2026-10', 'nextMonth': '2026-12', 'selectedDay': DAYS[9]},
    'selectedDay': DAYS[9]['snapshot'],
})
HOME = base.envelope('workspace/home', {'workspaceHome': {
    'employee': {'employeeName': '김하늘', 'companyName': '합성 디자인 검증회사'},
    'today': DAYS[9], 'week': DAYS[8:15], 'availableLeaveDays': 9, 'pendingRequests': 1,
    'recentRequests': [DAYS[16]['requests'][0], DAYS[11]['requests'][0]],
}})
FIXTURES = {'/me/home': HOME, '/admin/organizations': base.ORGANIZATIONS, '/me/schedule': SCHEDULE,
            '/me/requests': base.MINE, '/me/requests/new': base.NEW, '/me/profile': base.PROFILE}
SHELL = ('<!DOCTYPE html><html lang="ko"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">'
         '<title>WorkLog 합성 미리보기</title><link rel="stylesheet" href="/app.css"><link rel="stylesheet" href="/react/app.css">'
         '<link rel="stylesheet" href="/react/sheet.css"></head><body><div id="root"></div>'
         '<script id="worklog-page" type="application/json">{boot}</script><script type="module" src="/react/app.js"></script></body></html>')


class Handler(BaseHTTPRequestHandler):
    def send(self, payload, content_type, status=200):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(payload)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == '/app.css':
            self.send((ROOT / 'src/main/resources/static/app.css').read_bytes(), 'text/css; charset=utf-8')
            return
        if path.startswith('/react/'):
            file = (DIST / path.removeprefix('/react/')).resolve()
            if DIST not in file.parents or not file.is_file():
                self.send(b'not found', 'text/plain', 404)
                return
            kind = mimetypes.guess_type(file.name)[0] or 'application/octet-stream'
            if file.suffix in {'.js', '.css'}:
                kind += '; charset=utf-8'
            self.send(file.read_bytes(), kind)
            return
        data = deepcopy(FIXTURES.get(path, FIXTURES['/me/profile']))
        if 'application/vnd.worklog.page+json' in self.headers.get('Accept', ''):
            self.send(json.dumps(data, ensure_ascii=False).encode(), 'application/vnd.worklog.page+json; charset=utf-8')
            return
        boot = json.dumps(data, ensure_ascii=False).replace('<', '\\u003c')
        self.send(SHELL.format(boot=boot).encode(), 'text/html; charset=utf-8')

    def do_POST(self):
        self.send(b'read-only synthetic preview', 'text/plain', 405)

    def log_message(self, *args):
        pass


if __name__ == '__main__':
    print(f'Synthetic preview: http://127.0.0.1:{PORT}/me/schedule', flush=True)
    ThreadingHTTPServer(('127.0.0.1', PORT), Handler).serve_forever()
```

- [ ] **Step 3: `capture.mjs` 작성**

```js
// Usage: node capture.mjs <outDir> [baseUrl]
// 6개 화면 × 차콜·블루 × 1440·390px. 시계를 고정하고 축소 모션을 켜서 같은 빌드는 같은 픽셀을 내도록 한다.
import { createRequire } from 'node:module';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const require = createRequire('C:/Users/ihpark/dev/vibe-attendance/target/hallmark-tools/package.json');
const puppeteer = require('puppeteer-core');
const [out, base = 'http://127.0.0.1:18131'] = process.argv.slice(2);
const routes = [
  ['home', '/me/home'], ['organizations', '/admin/organizations'], ['schedule', '/me/schedule'],
  ['requests', '/me/requests'], ['request-modal', '/me/requests/new'], ['profile', '/me/profile'],
];
mkdirSync(out, { recursive: true });
const browser = await puppeteer.launch({ executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', headless: true });
const report = [];
try {
  for (const theme of ['charcoal', 'blue']) for (const width of [1440, 390]) for (const [name, route] of routes) {
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
    await page.evaluateOnNewDocument(value => {
      const fixed = new Date('2026-11-10T01:20:00Z').valueOf();
      const NativeDate = Date;
      globalThis.Date = class extends NativeDate {
        constructor(...args) { super(...(args.length ? args : [fixed])); }
        static now() { return fixed; }
      };
      try { localStorage.setItem('worklog-theme', value); } catch { /* storage blocked */ }
    }, theme);
    await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }]);
    await page.setViewport({ width, height: width === 390 ? 844 : 1000, deviceScaleFactor: 1 });
    await page.goto(base + route, { waitUntil: 'networkidle0' });
    await page.waitForSelector('#workspace-content, dialog[open]', { timeout: 10000 });
    await page.evaluate(() => document.fonts.ready);
    await page.evaluate(() => { if (document.activeElement instanceof HTMLElement) document.activeElement.blur(); });
    const metrics = await page.evaluate(() => ({
      overflow: document.documentElement.scrollWidth - window.innerWidth,
      font: getComputedStyle(document.body).fontFamily.split(',')[0].trim(),
    }));
    const file = `${name}-${theme}-${width}.png`;
    await page.screenshot({ path: path.join(out, file), fullPage: !route.endsWith('/new') });
    report.push({ file, errors, ...metrics });
    await page.close();
  }
} finally {
  await browser.close();
}
writeFileSync(path.join(out, 'report.json'), JSON.stringify(report, null, 2));
const failed = report.filter(item => item.errors.length);
console.log(`${report.length} captures, ${failed.length} with JS errors, ${report.filter(item => item.overflow > 0).length} with page overflow`);
for (const item of report.filter(item => item.errors.length || item.overflow > 0)) console.log(item.file, `overflow=${item.overflow}`, item.errors.join(' | '));
process.exit(failed.length ? 1 : 0);
```

- [ ] **Step 4: `compare.mjs` 작성**

```js
// Usage: node compare.mjs <dirA> <dirB> — 같은 이름 PNG의 픽셀 차이 수. 차이가 있으면 종료 코드 1.
import { createRequire } from 'node:module';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';

const require = createRequire('C:/Users/ihpark/dev/vibe-attendance/target/hallmark-tools/package.json');
const puppeteer = require('puppeteer-core');
const [left, right] = process.argv.slice(2);
const files = readdirSync(left).filter(file => file.endsWith('.png'));
const browser = await puppeteer.launch({ executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', headless: true });
let different = 0;
try {
  const page = await browser.newPage();
  for (const file of files) {
    if (!existsSync(path.join(right, file))) { console.log(`${file}: missing`); different++; continue; }
    const [a, b] = [left, right].map(dir => `data:image/png;base64,${readFileSync(path.join(dir, file)).toString('base64')}`);
    const result = await page.evaluate(async (a, b) => {
      const load = src => new Promise((resolve, reject) => { const image = new Image(); image.onload = () => resolve(image); image.onerror = reject; image.src = src; });
      const [x, y] = await Promise.all([load(a), load(b)]);
      if (x.width !== y.width || x.height !== y.height) return `size ${x.width}x${x.height} vs ${y.width}x${y.height}`;
      const read = image => { const canvas = new OffscreenCanvas(image.width, image.height); const context = canvas.getContext('2d'); context.drawImage(image, 0, 0); return context.getImageData(0, 0, image.width, image.height).data; };
      const p = read(x), q = read(y);
      let count = 0;
      for (let i = 0; i < p.length; i += 4) if (p[i] !== q[i] || p[i + 1] !== q[i + 1] || p[i + 2] !== q[i + 2] || p[i + 3] !== q[i + 3]) count++;
      return count;
    }, a, b);
    if (result !== 0) different++;
    console.log(`${file}: ${typeof result === 'number' ? `${result} px` : result}`);
  }
} finally {
  await browser.close();
}
console.log(`${files.length} compared, ${different} different`);
process.exit(different ? 1 : 0);
```

- [ ] **Step 5: 기준 빌드와 미리보기 시작**

```bash
npm --prefix frontend run build
python "$TOOLS/preview.py"
```

Expected: 빌드가 성공한다. `preview.py`는 백그라운드로 띄우며 `Synthetic preview: http://127.0.0.1:18131/me/schedule`을 출력한다.

- [ ] **Step 6: 기준 캡처를 두 번 찍어 도구가 결정적인지 확인**

```bash
node "$TOOLS/capture.mjs" "$TOOLS/shots/baseline"
node "$TOOLS/capture.mjs" "$TOOLS/shots/baseline-repeat"
node "$TOOLS/compare.mjs" "$TOOLS/shots/baseline" "$TOOLS/shots/baseline-repeat"
```

Expected: `24 captures, 0 with JS errors`가 두 번 나오고, `24 compared, 0 different`가 나온다.

- JS 오류가 있으면 해당 화면의 envelope를 같은 화면 테스트의 자료와 비교해 빠진 필드를 `preview.py`에 채운다. 비교 대상은 `frontend/src/pages/*.test.tsx`와 `frontend/src/App*.test.tsx`다.
- 반복 캡처 사이에 차이가 있으면 원인(애니메이션, 현재 시각 등)을 고정한 뒤 다시 확인한다.
- 기준 상태의 `page overflow` 목록은 `$TOOLS/shots/baseline/report.json`에 그대로 기록해 둔다. 이후 단계는 이 목록보다 나빠지면 안 된다.

---

### Task 1: 기존 CSS를 legacy 층으로 (화면 변화 0)

**Files:**
- Create: `frontend/src/cssLayers.test.ts`
- Modify: `frontend/src/sheetAssets.test.ts:29-30`
- Modify: `src/main/resources/static/app.css` (앞뒤 감싸기)
- Modify: `frontend/src/WorkspacePolish.css:5-6` 및 파일 끝
- Modify: `frontend/src/components/worklog-sheet.css:3-4` 및 파일 끝
- Modify: 나머지 React CSS 14개(앞뒤 감싸기)

- [ ] **Step 1: 층 계약 테스트 작성**

`frontend/src/cssLayers.test.ts`:

```ts
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const legacy = [
  'src/WorkspacePolish.css', 'src/ThemeSwitcher.css',
  'src/components/DemoAccountSwitcher.css', 'src/components/EmployeeWorkspace.css', 'src/components/Modal.css',
  'src/components/WorkspaceNavigation.css', 'src/components/worklog-sheet.css',
  'src/pages/ApprovalWorkspace.css', 'src/pages/DemoAccess.css', 'src/pages/DemoLanding.css', 'src/pages/PersonalDetails.css',
  'src/pages/RecordDetails.css', 'src/pages/RequestDetail.css', 'src/pages/RequestWorkspace.css',
  'src/pages/ScheduleCalendar.css', 'src/pages/WorkspaceHome.css',
];
const code = (file: string) => readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').trim();
const body = (css: string) => css
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

  it('imports legacy tokens and base rules into the legacy layer', () => {
    const css = readFileSync('src/WorkspacePolish.css', 'utf8');
    expect(css).toContain("@import url('./tokens.css') layer(legacy);");
    expect(css).toContain("@import url('./base.css') layer(legacy);");
  });
});
```

- [ ] **Step 2: 빌드 자산 테스트에 층 확인 추가**

`frontend/src/sheetAssets.test.ts`의 다음 줄 바로 뒤에 두 줄을 넣는다.

```ts
    expect(String(sheet.source)).toContain('--ag-row-height:40px');
```

넣을 두 줄:

```ts
    expect(String(app.source)).toMatch(/@layer legacy\s*\{/);
    expect(String(sheet.source)).toMatch(/@layer vendor\s*\{/);
```

- [ ] **Step 3: 실패 확인**

```bash
npm --prefix frontend test -- src/cssLayers.test.ts src/sheetAssets.test.ts
```

Expected: FAIL. 새 테스트 대부분과 `@layer legacy` 확인이 실패한다.

- [ ] **Step 4: `WorkspacePolish.css`와 `worklog-sheet.css`의 import 바꾸기**

`frontend/src/WorkspacePolish.css`에서

```css
@import url('./tokens.css');
@import url('./base.css');
```

를 다음으로 바꾼다.

```css
@layer vendor, legacy;
@import url('./tokens.css') layer(legacy);
@import url('./base.css') layer(legacy);
@layer legacy {
```

`frontend/src/components/worklog-sheet.css`에서

```css
@import 'ag-grid-community/styles/ag-grid.css';
@import 'ag-grid-community/styles/ag-theme-quartz.css';
```

를 다음으로 바꾼다.

```css
@layer vendor, legacy;
@import 'ag-grid-community/styles/ag-grid.css' layer(vendor);
@import 'ag-grid-community/styles/ag-theme-quartz.css' layer(vendor);
@layer legacy {
```

두 파일 모두 파일 끝에 줄을 바꿔 `}`를 붙인다.

```bash
printf '\n}\n' >> frontend/src/WorkspacePolish.css
printf '\n}\n' >> frontend/src/components/worklog-sheet.css
```

- [ ] **Step 5: 나머지 14개 파일과 `app.css` 감싸기**

```bash
node -e '
const fs = require("fs");
for (const file of process.argv.slice(1)) {
  const css = fs.readFileSync(file, "utf8");
  if (/@layer legacy/.test(css)) continue;
  const head = file.endsWith("static/app.css") ? "@layer vendor, legacy;\n" : "";
  fs.writeFileSync(file, `${head}@layer legacy {\n${css.trimEnd()}\n}\n`);
}' src/main/resources/static/app.css frontend/src/ThemeSwitcher.css \
  frontend/src/components/DemoAccountSwitcher.css frontend/src/components/EmployeeWorkspace.css \
  frontend/src/components/Modal.css frontend/src/components/WorkspaceNavigation.css \
  frontend/src/pages/ApprovalWorkspace.css frontend/src/pages/DemoAccess.css frontend/src/pages/DemoLanding.css \
  frontend/src/pages/PersonalDetails.css frontend/src/pages/RecordDetails.css frontend/src/pages/RequestDetail.css \
  frontend/src/pages/RequestWorkspace.css frontend/src/pages/ScheduleCalendar.css frontend/src/pages/WorkspaceHome.css
```

`frontend/src/tokens.css`와 `frontend/src/base.css`는 `WorkspacePolish.css`가 `layer(legacy)`로 불러오므로 감싸지 않는다.

- [ ] **Step 6: 테스트 통과 확인**

```bash
npm --prefix frontend test -- src/cssLayers.test.ts src/sheetAssets.test.ts
```

Expected: PASS.

- [ ] **Step 7: 화면 변화 0 확인**

```bash
npm --prefix frontend run build
node "$TOOLS/capture.mjs" "$TOOLS/shots/t1-layer"
node "$TOOLS/compare.mjs" "$TOOLS/shots/baseline" "$TOOLS/shots/t1-layer"
```

Expected: `24 compared, 0 different`.

차이가 나면 그 화면에서 legacy 규칙과 AG Grid 규칙이 같은 속성을 다투는 곳을 찾는다. 원인 후보는 두 가지다.

- vendor 층으로 내린 AG Grid 규칙이 낮은 우선순위의 legacy 규칙에 진 경우. 예: `base.css`의 `button`·`input` 기본값.
- legacy 층 안의 `!important`. 현재 11곳이다.

해결은 다음 원칙으로 한다.

- 차이가 1px 이하의 의도하지 않은 변화이면, 해당 legacy 규칙의 범위를 `#tna-app .w-sheet-grid` 밖으로 한정한다.
- 시트 화면 차이 중 2단계에서 다시 쓸 부분이면 report에 남기고 진행한다. 이 경우 품질 문서에 이유를 적는다.

- [ ] **Step 8: 전체 프런트 테스트**

```bash
npm --prefix frontend test
```

Expected: 전체 PASS. 실패가 있으면 이 작업에서 생긴 것인지 `git stash`를 쓰지 말고 `git diff`로 확인한다.

- [ ] **Step 9: 커밋**

```bash
git add src/main/resources/static/app.css frontend/src
git commit -F - <<'EOF'
style: move existing styles into a legacy cascade layer

Wrap the server stylesheet and every React stylesheet in @layer legacy and
import AG Grid into a lower vendor layer, so new unlayered design styles can
override them without #tna-app specificity. Synthetic captures are
pixel-identical before and after.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 2: Pretendard와 새 토큰

**Files:**
- Modify: `frontend/package.json`, `frontend/package-lock.json`
- Create: `frontend/src/design/tokens.css`
- Create: `frontend/src/design/tokens.test.ts`
- Modify: `frontend/src/App.tsx:24`
- Modify: `frontend/src/sheetAssets.test.ts`
- Create: `docs/adr/0006-worklog-design-layer.md`
- Create: `LICENSES/Pretendard-OFL-1.1.txt`
- Modify: `THIRD_PARTY_NOTICES.md`, `frontend/public/THIRD_PARTY_LICENSES.txt`, `docs/development/frontend-licenses.csv`

- [ ] **Step 1: 대비 계약 테스트 작성**

`frontend/src/design/tokens.test.ts`:

```ts
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const css = readFileSync('src/design/tokens.css', 'utf8');
const block = (selector: string) => css.slice(css.indexOf(selector), css.indexOf('}', css.indexOf(selector)));
const value = (scope: string, name: string) => {
  const found = new RegExp(`--${name}:\\s*(#[0-9a-f]{6})`, 'i').exec(scope)?.[1];
  if (!found) throw new Error(`--${name} not found`);
  return found;
};
const luminance = (hex: string) => {
  const [r, g, b] = [1, 3, 5].map(index => parseInt(hex.slice(index, index + 2), 16) / 255)
    .map(channel => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const contrast = (a: string, b: string) => {
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
```

- [ ] **Step 2: 실패 확인**

```bash
npm --prefix frontend test -- src/design/tokens.test.ts
```

Expected: FAIL. `ENOENT: src/design/tokens.css`.

- [ ] **Step 3: Pretendard 설치**

```bash
npm --prefix frontend install pretendard@1.3.9 --save-exact
ls frontend/node_modules/pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css frontend/node_modules/pretendard/dist/LICENSE.txt
```

Expected: `package.json`의 dependencies에 `"pretendard": "1.3.9"`가 생기고 두 파일 경로가 출력된다.

- [ ] **Step 4: `tokens.css` 작성**

`frontend/src/design/tokens.css`:

```css
/* WorkLog design tokens: the single source for colour, type and size.
   Legacy --t-* names alias these values until stage 5 removes them. */
@import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css';

:root {
  --font-ui: "Pretendard Variable", Pretendard, -apple-system, BlinkMacSystemFont, "Apple SD Gothic Neo", "Malgun Gothic", sans-serif;
  --g0: #ffffff; --g50: #f8f9fa; --g100: #f2f4f6; --g150: #eceef1; --g200: #e3e6ea; --g300: #d0d5db;
  --g400: #adb5bf; --g500: #8a939e; --g600: #6a737e; --g700: #4d5662; --g800: #323a45; --g900: #1a1f27;
  --field-line: var(--g500);
  --brand: #252c37; --brand-hover: #151a22; --brand-tint: #f0f2f5; --brand-tint-2: #dfe3e9; --brand-ink: #252c37;
  --link: #2a62e0;
  --ok: #0b7a4b; --ok-bg: #e8f5ee; --warn: #9a5800; --warn-bg: #fff3de;
  --danger: #cf3348; --danger-bg: #fdecee; --info: #2a62e0; --info-bg: #ebf1fe;
  --ev-work: #edf1fb; --ev-work-ink: #33449a; --ev-off: #fdf0ea; --ev-off-ink: #a8461b;
  --ev-leave: #e6f4ec; --ev-leave-ink: #0b6b42; --ev-wait: #fff6e5; --ev-wait-ink: #875000; --ev-wait-line: #e5ad52;
  --h-ctl: 34px; --h-row: 38px; --h-head: 36px; --page-pad: 28px;
  --r-ctl: 4px; --r-pop: 6px; --r-modal: 10px;
  --shadow-pop: 0 12px 32px rgba(18, 24, 36, .16), 0 2px 6px rgba(18, 24, 36, .06);
  --shadow-modal: 0 24px 64px rgba(10, 14, 22, .32), 0 2px 8px rgba(10, 14, 22, .12);

  /* Legacy aliases */
  --font-body: var(--font-ui); --font-display: var(--font-ui);
  --t-ink: var(--g900); --t-muted: var(--g600); --t-paper: var(--g0);
  --t-canvas: var(--g50); --t-surface: var(--g50); --t-section: var(--g50); --t-label: var(--g50);
  --t-line: var(--g200); --t-field-border: var(--field-line); --t-disabled: var(--g100);
  --t-blue: var(--brand); --t-blue-hover: var(--brand-hover); --t-blue-soft: var(--brand-tint); --t-focus: var(--brand);
  --t-accent-border: var(--brand-tint-2); --t-accent-ink: var(--brand-ink);
  --t-green: var(--ok); --t-green-soft: var(--ok-bg); --t-amber: var(--warn); --t-amber-soft: var(--warn-bg);
  --t-danger: var(--danger); --t-danger-soft: var(--danger-bg);
  --t-overlay: rgba(26, 31, 39, .48); --t-shadow: var(--shadow-modal);
  --t-radius: var(--r-pop); --radius-card: var(--r-pop); --radius-input: var(--r-ctl);
  --t-nav: var(--g0); --t-nav-ink: var(--g800); --t-nav-muted: var(--g600); --t-nav-rule: var(--g200);
  --t-nav-hover: var(--g100); --t-nav-selected: var(--brand-tint); --t-nav-focus: var(--brand);
  --t-nav-work: var(--g600); --t-nav-approval: var(--g600); --t-nav-people: var(--g600); --t-nav-operations: var(--g600);
}
:root[data-theme="blue"] {
  --brand: #2356d4; --brand-hover: #1a45b4; --brand-tint: #eef3fd; --brand-tint-2: #d9e5fb; --brand-ink: #1c49b6;
  --link: #1c49b6;
}
@media (max-width: 600px), (pointer: coarse) {
  :root { --h-ctl: 44px; --h-row: 44px; --page-pad: 16px; }
}
```

- [ ] **Step 5: App에서 불러오기**

`frontend/src/App.tsx` 24행의 `import './WorkspacePolish.css';` 바로 다음 줄에 추가한다.

```ts
import './design/tokens.css';
```

- [ ] **Step 6: 토큰 테스트 통과 확인**

```bash
npm --prefix frontend test -- src/design/tokens.test.ts
```

Expected: PASS.

- [ ] **Step 7: 빌드 자산 테스트에 글꼴 확인 추가**

`frontend/src/sheetAssets.test.ts` 첫 번째 `it`에서 Task 1이 넣은 `@layer vendor` 확인 줄 바로 뒤에 추가한다.

```ts
    expect(String(app.source)).toContain('Pretendard Variable');
    const fonts = output.filter(asset => asset.fileName.includes('PretendardVariable.subset') && asset.fileName.endsWith('.woff2'));
    expect(fonts.length).toBeGreaterThan(80);
    expect(String(app.source)).toContain(`/react/${fonts[0].fileName}`);
```

같은 테스트에 이미 있는 `url(data:` 금지 검사가 글꼴 인라인을 막는다.

- [ ] **Step 8: 빌드 자산 테스트 통과 확인**

```bash
npm --prefix frontend test -- src/sheetAssets.test.ts
```

Expected: PASS.

- [ ] **Step 9: 라이선스 고지**

```bash
cp frontend/node_modules/pretendard/dist/LICENSE.txt LICENSES/Pretendard-OFL-1.1.txt
{ printf '\n\n---\nPretendard 1.3.9 — /react/PretendardVariable.subset.*.woff2\nhttps://github.com/orioncactus/pretendard\n\n'; cat frontend/node_modules/pretendard/dist/LICENSE.txt; } >> frontend/public/THIRD_PARTY_LICENSES.txt
node -e '
const lock = require("./frontend/package-lock.json").packages["node_modules/pretendard"];
console.log(["pretendard", lock.version, "runtime", lock.license ?? "OFL-1.1", lock.integrity, lock.resolved].map(v => JSON.stringify(v)).join(","));
' >> docs/development/frontend-licenses.csv
tail -1 docs/development/frontend-licenses.csv
```

Expected: 마지막 줄이 `"pretendard","1.3.9","runtime","OFL-1.1","sha512-…","https://registry.npmjs.org/pretendard/-/pretendard-1.3.9.tgz"` 형식이다.

`THIRD_PARTY_NOTICES.md`의 `## React 프론트엔드` 절 바로 앞에 다음 절을 넣는다.

```markdown
## Pretendard 글꼴

- 버전: Pretendard 1.3.9 (npm `pretendard`, 정확한 버전 고정).
- 사용 파일: `dist/web/variable/pretendardvariable-dynamic-subset.css`와 같은 폴더의 `woff2-dynamic-subset/*.woff2` 92개. 빌드가 `/react/`에 같은 출처 파일로 내보낸다. CDN·`data:` URL을 쓰지 않는다.
- 라이선스: SIL Open Font License 1.1. Copyright (c) 2021 Kil Hyung-jin, with Reserved Font Name Pretendard. [전문](LICENSES/Pretendard-OFL-1.1.txt)을 보존하고 빌드 결과의 `/react/THIRD_PARTY_LICENSES.txt`에도 포함한다.
- 글꼴 파일을 수정하거나 단독 판매하지 않는다. 결정 근거는 [ADR-0006](docs/adr/0006-worklog-design-layer.md)을 따른다.
```

- [ ] **Step 10: ADR 작성**

`docs/adr/0006-worklog-design-layer.md`:

```markdown
# ADR-0006: Pretendard 글꼴과 CSS 디자인 층

- 결정일: 2026-11-10 `Asia/Seoul`
- 상태: 사용자 승인. 1단계 로컬 구현·검증 대상.

## 결정

1. UI 글꼴로 Pretendard 1.3.9를 쓴다. 동적 분할 woff2를 앱 빌드에 포함해 같은 출처에서 제공한다.
2. 기존 CSS 전체를 `@layer legacy`, AG Grid legacy CSS를 `@layer vendor`에 둔다. `frontend/src/design/`의 새 CSS는 층 없이 둔다.

## 근거

- 현재 `Noto Sans KR`은 글꼴 파일 없이 이름만 지정돼 있다. 그래서 Windows에서는 맑은 고딕으로 표시되고, 사용자가 낡아 보인다고 지적한 주요 원인이 된다.
- Pretendard는 한국어 UI용으로 숫자 폭 고정(`tnum`)과 가변 굵기를 지원한다. SIL OFL 1.1이라 상업 서비스에 포함해 배포할 수 있다.
- 2026-11-10 npm registry에서 `pretendard` 1.3.9의 `license: OFL-1.1`을 확인했다. jsDelivr 파일 목록에서는 `dist/web/variable/pretendardvariable-dynamic-subset.css`와 woff2 92개(합계 2,957,724 bytes)를 확인했다.
- 현재 CSP는 `default-src 'self'; style-src 'self'`이다. 그래서 CDN 글꼴과 `data:` URL은 쓸 수 없다. Vite가 woff2를 `/react/`로 내보내고, 기존 `sheetAssets` 플러그인이 `data:` 인라인을 파일로 바꾼다.
- 브라우저는 `unicode-range`가 맞는 조각만 내려받는다. 그래서 화면당 전송량은 전체보다 훨씬 작다. 다만 dist는 약 3MB 늘어난다.
- 기존 CSS에는 `#tna-app` 선택자 2,000곳과 `!important` 11곳이 섞여 있다. 새 규칙이 이를 이기려고 특이성 경쟁을 하지 않도록 cascade layers를 쓴다. 층 없는 규칙은 특이성과 관계없이 층 안의 일반 규칙보다 우선한다.
- 2026-11-10 이 저장소의 Vite 8.3.5로 `@import … layer(vendor)`를 빌드했다. 결과 `sheet.css`에서 AG Grid 규칙이 `@layer vendor{…}`로 펼쳐진 것을 확인했다.

## 경계

- 층 감싸기 자체는 화면을 바꾸지 않는다. 합성 캡처의 픽셀 비교로 이를 확인한다.
- legacy 층 안의 `!important`는 새 규칙보다 우선한다. 충돌하는 곳만 지운다.
- 로그인 전 화면도 legacy 토큰 별칭을 통해 글꼴과 색이 바뀐다. 배치는 바꾸지 않는다.

## 공식 자료

- [Pretendard 저장소와 라이선스](https://github.com/orioncactus/pretendard)
- [npm pretendard 1.3.9](https://www.npmjs.com/package/pretendard/v/1.3.9)
- [CSS Cascade Layers (MDN)](https://developer.mozilla.org/docs/Web/CSS/@layer)
- [`@import` layer (MDN)](https://developer.mozilla.org/docs/Web/CSS/@import)
```

- [ ] **Step 11: 화면 확인**

```bash
npm --prefix frontend run build
node "$TOOLS/capture.mjs" "$TOOLS/shots/t2-tokens"
```

Expected:

- `0 with JS errors`.
- `report.json`의 모든 항목에서 `font`가 `"Pretendard Variable"`이다.
- `page overflow` 목록이 기준(`baseline/report.json`)보다 늘지 않는다.
- `$TOOLS/shots/t2-tokens/organizations-charcoal-1440.png`에서 글꼴이 Pretendard로 보이고, 사이드바는 legacy 별칭 때문에 흰 바탕에 가깝게 보인다.

- [ ] **Step 12: 커밋**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/design frontend/src/App.tsx frontend/src/sheetAssets.test.ts \
  docs/adr/0006-worklog-design-layer.md LICENSES/Pretendard-OFL-1.1.txt THIRD_PARTY_NOTICES.md \
  frontend/public/THIRD_PARTY_LICENSES.txt docs/development/frontend-licenses.csv
git commit -F - <<'EOF'
feat: ship Pretendard and the WorkLog design tokens

Bundle Pretendard 1.3.9 as same-origin dynamic subsets, add the design
token sheet with charcoal and blue brands, density and radius scales, and
alias legacy --t-* tokens to it. Contrast contracts guard text and field
boundaries.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 3: 기본 컨트롤

`t-btn`, `t-icon-button`, `t-input`, `t-select`, `t-tag`를 새 층에서 다시 정의한다. 너비·flex·margin 같은 배치 속성은 정하지 않는다. 배치는 각 화면의 legacy 규칙에 남긴다.

**Files:**
- Create: `frontend/src/design/ui.css`
- Modify: `frontend/src/App.tsx` (import 한 줄)

- [ ] **Step 1: `ui.css` 작성**

`frontend/src/design/ui.css`:

```css
/* WorkLog shared controls. Unlayered, so these win over the legacy layer without #tna-app selectors.
   Layout properties (width, flex, margin) stay with each screen. */
.t-btn {
  display: inline-flex; align-items: center; justify-content: center; gap: 6px;
  min-height: var(--h-ctl); height: auto; padding: 0 12px;
  border: 1px solid var(--g300); border-radius: var(--r-ctl);
  background: var(--g0); color: var(--g800); box-shadow: none;
  font: 600 13px/1.2 var(--font-ui); letter-spacing: -.01em; text-decoration: none; white-space: nowrap;
  cursor: pointer; transition: background-color 120ms, border-color 120ms;
}
.t-btn:hover:not(:disabled) { background: var(--g50); border-color: var(--g400); }
.t-btn:active:not(:disabled) { background: var(--g100); opacity: 1; }
.t-btn.primary { background: var(--brand); border-color: var(--brand); color: var(--g0); }
.t-btn.primary:hover:not(:disabled) { background: var(--brand-hover); border-color: var(--brand-hover); }
.t-btn:disabled, .t-btn[aria-disabled="true"] { background: var(--g50); border-color: var(--g200); color: var(--g500); cursor: not-allowed; }
.t-btn svg { width: 16px; height: 16px; flex: none; }

.t-icon-button {
  display: inline-grid; place-items: center; width: var(--h-ctl); height: var(--h-ctl); padding: 0;
  border: 1px solid var(--g300); border-radius: var(--r-ctl); background: var(--g0); color: var(--g700); cursor: pointer;
}
.t-icon-button:hover { background: var(--g50); border-color: var(--g400); }
.t-icon-button svg { width: 16px; height: 16px; stroke-width: 1.7; }

.t-input, .t-select {
  min-height: var(--h-ctl); height: auto; padding: 0 10px;
  border: 1px solid var(--field-line); border-radius: var(--r-ctl);
  background-color: var(--g0); color: var(--g900); box-shadow: none;
  font: 400 13px/1.4 var(--font-ui);
}
textarea.t-input { padding: 8px 10px; line-height: 1.55; }
.t-input::placeholder { color: var(--g600); }
.t-input:focus, .t-select:focus { border-color: var(--brand); outline: 2px solid var(--brand); outline-offset: 1px; }
.t-input[aria-invalid="true"], .t-select[aria-invalid="true"] { border-color: var(--danger); }

.t-tag {
  display: inline-flex; align-items: center; gap: 4px; min-height: 22px; padding: 0 7px;
  border: 0; border-radius: var(--r-ctl); background: var(--g100); color: var(--g700);
  font: 600 12px/1.2 var(--font-ui); white-space: nowrap;
}
.t-tag.blue { background: var(--info-bg); color: var(--info); }
.t-tag.green { background: var(--ok-bg); color: var(--ok); }
.t-tag.amber { background: var(--warn-bg); color: var(--warn); }
.t-tag.red { background: var(--danger-bg); color: var(--danger); }

@media (max-width: 600px) {
  .t-input, .t-select { font-size: 16px; }
}
```

모바일에서 입력 글자를 16px로 둔 것은 iOS의 입력 시 확대를 막기 위해서다. legacy에도 같은 규칙이 있었다.

- [ ] **Step 2: App에서 불러오기**

`frontend/src/App.tsx`의 `import './design/tokens.css';` 다음 줄에 추가한다.

```ts
import './design/ui.css';
```

- [ ] **Step 3: 빌드와 화면 확인**

```bash
npm --prefix frontend run build
node "$TOOLS/capture.mjs" "$TOOLS/shots/t3-controls"
```

Expected: `0 with JS errors`이고 overflow 목록이 기준보다 늘지 않는다. 다음 캡처를 직접 열어 확인한다.

- `organizations-charcoal-1440.png`: [조회]·[저장] 같은 버튼이 34px, 4px 모서리, 13px 굵은 글자다. 입력 경계가 회색이다.
- `requests-blue-1440.png`: 채운 버튼이 블루다.

어색한 곳(예: 버튼 안 아이콘 정렬)이 있으면 위 규칙 안에서 고친다.

- [ ] **Step 4: 전체 테스트와 커밋**

```bash
npm --prefix frontend test
git add frontend/src/design/ui.css frontend/src/App.tsx
git commit -F - <<'EOF'
style: restyle shared buttons, fields and tags

Redefine t-btn, t-icon-button, t-input, t-select and t-tag in the unlayered
design sheet: 34px controls, 4px radius, Pretendard 13px, one brand fill,
3:1 field boundaries.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

Expected: 전체 PASS 뒤 커밋.

---

### Task 4: 밝은 사이드바와 상단바

마크업은 바꾸지 않는다. `App.tsx`의 `t-app-shell`·`t-sidebar`·`t-topbar`·`t-main`과 `WorkspaceNavigation`의 클래스를 새 CSS로 다시 정의한다. 메뉴 동작, 권한별 노출, 모바일 열고 닫기, 건너뛰기 링크는 그대로 둔다.

**Files:**
- Create: `frontend/src/design/shell.css`
- Modify: `frontend/src/App.tsx` (import 한 줄)

- [ ] **Step 1: `shell.css` 작성**

`frontend/src/design/shell.css`:

```css
/* WorkLog workspace shell: light sidebar, 56px top bar, centred main area. Markup lives in App.tsx and WorkspaceNavigation.tsx. */
.t-app-shell {
  display: grid; grid-template-columns: 232px minmax(0, 1fr); grid-template-rows: 56px 1fr;
  grid-template-areas: "side top" "side main"; min-height: 100vh;
  background: var(--g0); color: var(--g900); font-size: 14px;
}
.t-sidebar {
  grid-area: side; position: sticky; top: 0; display: flex; flex-direction: column; height: 100vh; overflow-y: auto;
  padding: 0 0 14px; border-right: 1px solid var(--g200); background: var(--g0); color: var(--g800);
  scrollbar-color: var(--g300) var(--g0);
}
.t-sidebar .t-brand-button {
  display: flex; align-items: center; gap: 10px; flex: none; height: 56px; margin: 0; padding: 0 18px;
  border-bottom: 1px solid var(--g150); color: var(--g900); text-decoration: none;
}
.t-logo {
  display: grid; place-items: center; width: 26px; height: 26px; border-radius: var(--r-pop);
  background: var(--brand); color: var(--g0); font: 800 14px/1 var(--font-ui); letter-spacing: -.04em;
}
.t-brand-button > span:last-child { display: flex; flex-direction: column; font: 800 16px/1.15 var(--font-ui); letter-spacing: -.03em; }
.t-brand-sub { color: var(--g600); font-size: 11px; font-weight: 500; letter-spacing: 0; }
.t-workspace-name {
  display: flex; align-items: center; gap: 10px; margin: 12px 12px 6px; padding: 8px 10px;
  border: 1px solid var(--g200); border-radius: var(--r-pop); background: var(--g0);
  color: var(--g900); font-size: 13px; font-weight: 600; overflow-wrap: anywhere;
}
.t-workspace-icon {
  display: grid; place-items: center; flex: none; width: 24px; height: 24px; border-radius: var(--r-ctl);
  background: var(--g100); color: var(--g700); font-size: 12px; font-weight: 700;
}
.t-workspace-name small { display: block; margin-top: 2px; color: var(--g600); font-size: 12px; font-weight: 500; }

.t-workspace-navigation { display: block; padding: 4px 10px; }
.t-navigation-group { padding: 2px 0; }
.t-workspace-navigation .t-nav-item {
  display: flex; align-items: center; gap: 10px; width: 100%; min-height: 34px; margin: 1px 0; padding: 0 10px;
  border: 0; border-radius: 5px; background: transparent; color: var(--g700);
  font: 500 14px/1.3 var(--font-ui); text-align: left; text-decoration: none; cursor: pointer;
}
.t-workspace-navigation .t-nav-item:hover:not([aria-current="page"]) { background: var(--g100); }
.t-navigation-icon { display: grid; place-items: center; flex: none; width: 18px; height: 18px; border-radius: 0; background: none; color: var(--g600); }
.t-navigation-group-icon { width: 16px; height: 16px; }
.t-workspace-navigation .t-navigation-major[data-active="true"] { background: transparent; color: var(--g900); font-weight: 700; }
.t-navigation-chevron { flex: none; width: 14px; height: 14px; margin-left: auto; color: var(--g500); transition: transform 120ms; }
.t-navigation-toggle[aria-expanded="true"] .t-navigation-chevron { transform: rotate(90deg); }
.t-navigation-sections { margin: 0 0 4px 19px; padding-left: 6px; border-left: 1px solid var(--g200); }
.t-workspace-navigation .t-navigation-section { min-height: 32px; color: var(--g600); font-size: 13px; font-weight: 600; }
.t-workspace-navigation .t-navigation-section[data-active="true"] { color: var(--g900); }
.t-navigation-links { margin: 0 0 4px; padding: 0; list-style: none; }
.t-workspace-navigation .t-navigation-link { min-height: 32px; padding-left: 12px; color: var(--g700); font-size: 13.5px; }
.t-workspace-navigation .t-navigation-link[aria-current="page"] { background: var(--brand-tint); color: var(--brand-ink); font-weight: 700; }

.t-sidebar-bottom { margin-top: auto; padding: 12px 20px 0; border-top: 1px solid var(--g150); color: var(--g600); font-size: 12px; line-height: 1.6; }
.t-sidebar-bottom p { margin: 2px 0 0; font-size: 12px; }
.t-sidebar-bottom .t-demo-label { color: var(--g800); font-weight: 700; }
.t-sidebar-bottom a { color: var(--link); }

.t-topbar {
  grid-area: top; display: flex; align-items: center; justify-content: space-between; gap: 12px;
  min-height: 56px; padding: 0 var(--page-pad); border-bottom: 1px solid var(--g150); background: var(--g0);
}
.t-breadcrumb { display: flex; align-items: center; gap: 8px; min-width: 0; color: var(--g600); font-size: 13px; }
.t-topbar .t-brand-button { display: none; }
.t-location-breadcrumbs ol { display: flex; flex-wrap: wrap; gap: 4px 6px; margin: 0; padding: 0; list-style: none; }
.t-location-breadcrumbs li { display: flex; align-items: center; gap: 6px; }
.t-location-breadcrumbs li + li::before { content: "›"; color: var(--g500); }
.t-location-breadcrumbs a { color: var(--g600); text-decoration: none; }
.t-location-breadcrumbs a:hover { color: var(--g900); text-decoration: underline; }
.t-location-breadcrumbs [aria-current="page"] { color: var(--g900); font-weight: 600; }
.t-profile { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 8px; min-width: 0; font-size: 12.5px; }
.t-theme-picker, .t-role-picker { display: inline-flex; align-items: center; gap: 6px; margin: 0; color: var(--g600); font-size: 12px; white-space: nowrap; }
.t-theme-picker select, .t-role-picker select {
  min-height: 30px; padding: 0 26px 0 8px; border: 1px solid var(--field-line); border-radius: var(--r-ctl);
  background-color: var(--g0); color: var(--g800); font: 500 12.5px var(--font-ui);
}
.t-profile-copy { max-width: 180px; color: var(--g900); font-size: 13px; font-weight: 600; line-height: 1.3; text-align: right; text-decoration: none; overflow-wrap: anywhere; }
.t-profile-copy small { display: block; color: var(--g600); font-size: 12px; font-weight: 500; }
.t-logout { min-height: 30px; padding: 0 10px; font-size: 12.5px; }
.t-main { grid-area: main; width: 100%; max-width: var(--container, 1440px); min-width: 0; margin: 0 auto; padding: 22px var(--page-pad) 40px; }
.t-mobile-menu { display: none; }

@media (max-width: 960px) {
  .t-app-shell { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto; grid-template-areas: "top" "main"; }
  .t-app-shell[data-menu-open="true"] { grid-template-areas: "top" "side" "main"; }
  .t-sidebar { display: none; position: static; height: auto; overflow: visible; border-right: 0; border-bottom: 1px solid var(--g200); }
  .t-app-shell[data-menu-open="true"] .t-sidebar { display: flex; }
  .t-sidebar .t-brand-button { display: none; }
  .t-topbar { flex-wrap: wrap; padding-block: 6px; }
  .t-topbar .t-brand-button { display: inline-flex; align-items: center; gap: 8px; min-height: 44px; color: var(--g900); font-weight: 800; text-decoration: none; }
  .t-topbar .t-brand-sub { display: none; }
  .t-mobile-menu {
    display: inline-grid; place-items: center; width: 44px; height: 44px; padding: 0;
    border: 0; border-radius: var(--r-ctl); background: transparent; color: var(--g800);
  }
}
@media (max-width: 680px) {
  .t-theme-picker > span, .t-role-picker > span { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
  .t-profile-copy small { display: none; }
}
```

- [ ] **Step 2: App에서 불러오기**

`frontend/src/App.tsx`의 `import './design/ui.css';` 다음 줄에 추가한다.

```ts
import './design/shell.css';
```

- [ ] **Step 3: 내비게이션 회귀 테스트**

```bash
npm --prefix frontend test -- src/components/WorkspaceNavigation.test.tsx src/App.accessibility.test.tsx src/ThemeSwitcher.test.tsx
```

Expected: PASS. 마크업을 바꾸지 않았으므로 실패하면 안 된다.

- [ ] **Step 4: 빌드와 화면 확인**

```bash
npm --prefix frontend run build
node "$TOOLS/capture.mjs" "$TOOLS/shots/t4-shell"
```

Expected: `0 with JS errors`이고 390px overflow가 기준보다 늘지 않는다. 다음을 직접 확인한다.

- 1440px: 사이드바가 흰 바탕이고 오른쪽에 1px 선이 있다. 선택한 메뉴는 brand-tint 바탕이다. 상단바는 56px이고 위치 경로가 `›`로 이어진다.
- 390px: 사이드바가 숨고 메뉴 버튼(44px)과 WorkLog 로고가 상단바에 보인다.

메뉴 열림은 Step 5에서 확인한다.

- [ ] **Step 5: 모바일 메뉴 열림 확인 (브라우저 창)**

미리보기를 브라우저 창으로 연다(`http://127.0.0.1:18131/me/schedule`). 390×844로 바꾼 뒤 다음을 확인한다.

- 메뉴 버튼을 누르면 사이드바가 상단바 아래에 펼쳐진다.
- Esc를 누르면 닫힌다.

결과는 Task 8 품질 문서에 적는다.

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/design/shell.css frontend/src/App.tsx
git commit -F - <<'EOF'
style: give the workspace a light sidebar and compact top bar

Restyle the existing shell classes in the design layer: white 232px
sidebar with neutral icons and a tinted current item, 56px top bar with
breadcrumbs, and the same mobile menu behaviour below 960px.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 5: `PageHeader` 컴포넌트와 도움말

**Files:**
- Create: `frontend/src/components/PageHeader.test.tsx`
- Create: `frontend/src/components/PageHeader.tsx`
- Modify: `frontend/src/design/ui.css` (파일 끝에 추가)

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/src/components/PageHeader.test.tsx`:

```tsx
// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { PageHeader } from './PageHeader';

afterEach(cleanup);

describe('PageHeader', () => {
  it('shows the title and screen actions without a help button when there is no help', () => {
    render(<PageHeader title="조직 관리" actions={<a href="/company/organizations">조직도</a>} />);
    expect(screen.getByRole('heading', { level: 1, name: '조직 관리' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '조직도' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '도움말' })).toBeNull();
  });

  it('keeps the help text in the document but hidden until the help button opens it', () => {
    render(<PageHeader title="조직 관리" help="여러 행을 편집하고 한 번에 저장하세요." />);
    const button = screen.getByRole('button', { name: '도움말' });
    const panel = screen.getByText('여러 행을 편집하고 한 번에 저장하세요.');
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(button.getAttribute('aria-controls')).toBe(panel.id);
    expect(panel.hidden).toBe(true);
    fireEvent.click(button);
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(panel.hidden).toBe(false);
    fireEvent.click(button);
    expect(panel.hidden).toBe(true);
  });

  it('closes on Escape and returns focus to the help button', () => {
    render(<PageHeader title="조직 관리" help="도움말 내용" />);
    const button = screen.getByRole('button', { name: '도움말' });
    fireEvent.click(button);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.getByText('도움말 내용').hidden).toBe(true);
    expect(document.activeElement).toBe(button);
  });

  it('closes when the pointer goes down outside the header', () => {
    render(<><PageHeader title="조직 관리" help="도움말 내용" /><p>바깥</p></>);
    fireEvent.click(screen.getByRole('button', { name: '도움말' }));
    fireEvent.pointerDown(screen.getByText('바깥'));
    expect(screen.getByText('도움말 내용').hidden).toBe(true);
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
npm --prefix frontend test -- src/components/PageHeader.test.tsx
```

Expected: FAIL. `Failed to resolve import "./PageHeader"`.

- [ ] **Step 3: 컴포넌트 구현**

`frontend/src/components/PageHeader.tsx`:

```tsx
import { useEffect, useId, useRef, useState } from 'react';
import type { ReactNode } from 'react';

interface PageHeaderProps {
  title: ReactNode;
  /** 화면 설명. 문서에는 남기고 도움말 버튼을 눌렀을 때만 보인다. */
  help?: ReactNode;
  /** 화면 단위 동작(링크·버튼). */
  actions?: ReactNode;
}

export function PageHeader({ title, help, actions }: PageHeaderProps) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const root = useRef<HTMLElement>(null);
  const button = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      button.current?.focus();
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  return (
    <header ref={root} className="wl-pagehead">
      <div className="wl-pagehead-title">
        <h1>{title}</h1>
        {help && <>
          <button ref={button} type="button" className="wl-help-button" aria-label="도움말"
            aria-expanded={open} aria-controls={panelId} onClick={() => setOpen(!open)}>
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <circle cx="12" cy="12" r="8.5" />
              <path d="M9.6 9.4a2.5 2.5 0 0 1 4.8.9c0 1.7-2.4 2.2-2.4 3.4M12 16.8h.01" />
            </svg>
          </button>
          <div id={panelId} className="wl-help-panel" hidden={!open}>{help}</div>
        </>}
      </div>
      {actions && <div className="wl-pagehead-actions">{actions}</div>}
    </header>
  );
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
npm --prefix frontend test -- src/components/PageHeader.test.tsx
```

Expected: PASS (4 tests).

- [ ] **Step 5: 화면 머리 CSS 추가**

`frontend/src/design/ui.css` 파일 끝에 추가한다.

```css
/* Page header: title, optional help (?) popover, screen-level actions. */
.wl-pagehead { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px 16px; margin: 0 0 16px; }
.wl-pagehead-title { position: relative; display: flex; align-items: center; gap: 4px; min-width: 0; }
.wl-pagehead h1 { margin: 0; color: var(--g900); font: 700 22px/1.35 var(--font-ui); letter-spacing: -.025em; }
.wl-pagehead-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.wl-help-button {
  display: inline-grid; place-items: center; width: 28px; height: 28px; padding: 0;
  border: 0; border-radius: var(--r-ctl); background: transparent; color: var(--g500); cursor: pointer;
}
.wl-help-button:hover, .wl-help-button[aria-expanded="true"] { background: var(--g100); color: var(--g800); }
.wl-help-button svg { width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
.wl-help-panel {
  position: absolute; z-index: 30; top: calc(100% + 6px); left: 0; width: min(360px, calc(100vw - 32px));
  padding: 12px 14px; border: 1px solid var(--g200); border-radius: var(--r-pop);
  background: var(--g0); box-shadow: var(--shadow-pop); color: var(--g700); font: 400 13px/1.6 var(--font-ui);
}
@media (max-width: 600px) {
  .wl-pagehead h1 { font-size: 20px; }
  .wl-help-button { width: 44px; height: 44px; }
}
```

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/components/PageHeader.tsx frontend/src/components/PageHeader.test.tsx frontend/src/design/ui.css
git commit -F - <<'EOF'
feat: add a page header with an on-demand help popover

PageHeader renders the screen title, screen actions and a help (?) button
whose panel keeps the former subtitle text in the document but hidden until
opened; Escape and outside pointer close it and return focus.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 6: 화면 머리 19곳을 `PageHeader`로

각 파일에 import를 추가하고, 아래 표의 옛 마크업을 새 마크업으로 정확히 바꾼다. 설명 문장은 `help`로 옮기며 문구는 바꾸지 않는다.

**Files:** `frontend/src/pages/` 아래 9개 파일. 각 파일의 import 추가 위치는 다음과 같다.

- `AdminPages.tsx`: 3행(`import { WorklogSheet, sheetColumn } …`) 다음
- `AnnualRequestPage.tsx`: 8행(`RequestBreakEditor` import) 다음
- `ApprovalRoutePages.tsx`: 6행 다음
- `CodePages.tsx`: 6행 다음
- `CorePages.tsx`: 6행 다음
- `IdentityPages.tsx`: 5행 다음
- `OrgSheetPages.tsx`: 6행 다음
- `PersonnelPage.tsx`: 4행 다음
- `WorkflowPages.tsx`: 8행 다음

추가할 줄은 모두 같다.

```ts
import { PageHeader } from '../components/PageHeader';
```

- [ ] **Step 1: AdminPages (직원 목록)**

옛:

```tsx
      <header className="t-pagehead">
        <div>

          <h1>직원 목록</h1>
          <p className="t-muted">조직과 재직 정보를 한곳에서.</p>
        </div>
      </header>
```

새:

```tsx
      <PageHeader title="직원 목록" help="조직과 재직 정보를 한곳에서." />
```

- [ ] **Step 2: AnnualRequestPage (2곳)**

옛 (327행 안의 일부):

```tsx
<header className="t-pagehead"><div><h1>내 근태현황·신청</h1><p className="t-muted">대상 휴가일·근무일 기준 {a.year}년 현황 · 모든 상태는 조회 시점 현재입니다.</p></div><a ref={trigger} role="button" className="t-btn primary t-request-start" href={url('/me/requests/new',{year:a.year})} onKeyDown={event=>{if(event.key===' '){event.preventDefault();event.currentTarget.click();}}}><span aria-hidden="true">＋</span> 신청</a></header>
```

새:

```tsx
<PageHeader title="내 근태현황·신청" help={`대상 휴가일·근무일 기준 ${a.year}년 현황 · 모든 상태는 조회 시점 현재입니다.`} actions={<a ref={trigger} role="button" className="t-btn primary t-request-start" href={url('/me/requests/new',{year:a.year})} onKeyDown={event=>{if(event.key===' '){event.preventDefault();event.currentTarget.click();}}}><span aria-hidden="true">＋</span> 신청</a>} />
```

옛 (382행):

```tsx
    <header className="t-pagehead"><div><h1>기초 묶음 귀속연도</h1><p>{m.bucket.employeeName} · {m.bucket.balanceDays} DAY 현재 잔액</p></div><a className="t-btn" href={`/admin/leave-balances/buckets/${m.bucket.id}`}>원장 보기</a></header>
```

새. 이 줄은 설명이 아니라 자료이므로 머리 아래에 보이는 채로 둔다.

```tsx
    <PageHeader title="기초 묶음 귀속연도" actions={<a className="t-btn" href={`/admin/leave-balances/buckets/${m.bucket.id}`}>원장 보기</a>} />
    <p className="t-muted">{m.bucket.employeeName} · {m.bucket.balanceDays} DAY 현재 잔액</p>
```

- [ ] **Step 3: ApprovalRoutePages (2곳)**

옛:

```tsx
    <header className="t-pagehead">
      <div>

        <h1>결재선 관리</h1>
        <p className="t-muted">한 명의 결재자 또는 대상자의 현재 조직장을 지정하세요.</p>
      </div>
      <a className="t-btn" href="/admin/approval-route-assignments">사원·조직별 배정</a>
    </header>
```

새:

```tsx
    <PageHeader title="결재선 관리" help="한 명의 결재자 또는 대상자의 현재 조직장을 지정하세요."
      actions={<a className="t-btn" href="/admin/approval-route-assignments">사원·조직별 배정</a>} />
```

옛:

```tsx
    <header className="t-pagehead">
      <div>

        <h1>결재선 배정</h1>
        <p className="t-muted">사원 또는 조직에 적용할 1단계 결재선 버전을 지정하세요.</p>
      </div>
      <a className="t-btn" href="/admin/approval-routes">결재선 관리</a>
    </header>
```

새:

```tsx
    <PageHeader title="결재선 배정" help="사원 또는 조직에 적용할 1단계 결재선 버전을 지정하세요."
      actions={<a className="t-btn" href="/admin/approval-routes">결재선 관리</a>} />
```

- [ ] **Step 4: CodePages (2곳)**

옛:

```tsx
<header className="t-pagehead"><div><h1>공통코드 관리</h1><p className="t-muted">회사별 표시·분류 코드를 관리하세요. 권한·신청 상태·계산 규칙은 변경되지 않습니다.</p></div><a className="t-btn" href="/admin/attendance-types">근태코드 관리</a></header>
```

새:

```tsx
<PageHeader title="공통코드 관리" help="회사별 표시·분류 코드를 관리하세요. 권한·신청 상태·계산 규칙은 변경되지 않습니다." actions={<a className="t-btn" href="/admin/attendance-types">근태코드 관리</a>} />
```

옛:

```tsx
<header className="t-pagehead"><div><h1>근태코드 관리</h1><p className="t-muted">신청 정책을 적용일별 버전으로 관리하세요. 기존 대기·완료 신청은 제출 당시 정책을 유지합니다.</p></div><a className="t-btn" href="/admin/common-codes">공통코드 관리</a></header>
```

새:

```tsx
<PageHeader title="근태코드 관리" help="신청 정책을 적용일별 버전으로 관리하세요. 기존 대기·완료 신청은 제출 당시 정책을 유지합니다." actions={<a className="t-btn" href="/admin/common-codes">공통코드 관리</a>} />
```

- [ ] **Step 5: CorePages (근태 캘린더)**

옛:

```tsx
      <header className="t-pagehead">
        <div>
          <h1>근태 캘린더</h1>
          <p className="t-muted">배정된 근무와 신청 상태를 확인하고 날짜에서 바로 신청하세요.</p>
        </div>
      </header>
```

새:

```tsx
      <PageHeader title="근태 캘린더" help="배정된 근무와 신청 상태를 확인하고 날짜에서 바로 신청하세요." />
```

- [ ] **Step 6: IdentityPages (4곳)**

옛과 새를 차례로 바꾼다.

```tsx
      <header className="t-pagehead">
        <div>

          <h1>내 정보</h1>
          <p className="t-muted">현재 계정과 업무 배정 정보를 확인하세요.</p>
        </div>
      </header>
```

```tsx
      <PageHeader title="내 정보" help="현재 계정과 업무 배정 정보를 확인하세요." />
```

```tsx
      <header className="t-pagehead">
        <div>

          <h1>권한 관리</h1>
          <p className="t-muted">계정별 역할을 확인하고 기존 관리 화면에서 변경하세요.</p>
        </div>
      </header>
```

```tsx
      <PageHeader title="권한 관리" help="계정별 역할을 확인하고 기존 관리 화면에서 변경하세요." />
```

```tsx
      <header className="t-pagehead">
        <div>
          <h1>회사 근무현황</h1>
          <p className="t-muted">사원을 선택해 해당 월의 일별 근무 결과를 확인하세요.</p>
        </div>
      </header>
```

```tsx
      <PageHeader title="회사 근무현황" help="사원을 선택해 해당 월의 일별 근무 결과를 확인하세요." />
```

```tsx
      <header className="t-pagehead">
        <div>
          <h1>회사 신청 현황</h1>
          <p className="t-muted">조회연도의 회사 신청 유형과 처리 상태를 확인하세요.</p>
        </div>
      </header>
```

```tsx
      <PageHeader title="회사 신청 현황" help="조회연도의 회사 신청 유형과 처리 상태를 확인하세요." />
```

- [ ] **Step 7: OrgSheetPages (3곳)**

```tsx
<header className="t-pagehead"><div><h1>{title}</h1><p className="t-muted">여러 행을 편집하고 한 번에 저장하세요.</p></div><a className="t-btn" href="/company/organizations">조직도</a></header>
```

```tsx
<PageHeader title={title} help="여러 행을 편집하고 한 번에 저장하세요." actions={<a className="t-btn" href="/company/organizations">조직도</a>} />
```

```tsx
<header className="t-pagehead"><div><h1>조직장 배정</h1><p className="t-muted">조직별 한 명을 지정하고 적용일별 이력을 보존합니다.</p></div><a className="t-btn" href="/company/organizations">조직도</a></header>
```

```tsx
<PageHeader title="조직장 배정" help="조직별 한 명을 지정하고 적용일별 이력을 보존합니다." actions={<a className="t-btn" href="/company/organizations">조직도</a>} />
```

```tsx
<header className="t-pagehead"><div><h1>조직도</h1><p className="t-muted">현재 조직과 조직장을 확인하고 조직별 직원을 조회하세요.</p></div></header>
```

```tsx
<PageHeader title="조직도" help="현재 조직과 조직장을 확인하고 조직별 직원을 조회하세요." />
```

- [ ] **Step 8: PersonnelPage**

```tsx
<header className="t-pagehead"><div><h1>인사정보 등록</h1><p className="t-muted">사원의 기본 인사정보와 적용일별 재직 상태를 관리하세요.</p></div><a className="t-btn" href={path}>기본정보·소속</a></header>
```

```tsx
<PageHeader title="인사정보 등록" help="사원의 기본 인사정보와 적용일별 재직 상태를 관리하세요." actions={<a className="t-btn" href={path}>기본정보·소속</a>} />
```

- [ ] **Step 9: WorkflowPages (3곳)**

옛 (신청 상세):

```tsx
      <header className="t-pagehead">
        <div>

          <h1>신청 상세</h1>
          <p className="t-muted">신청 내용과 실제 처리 이력을 확인하세요. 시각 기준: Asia/Seoul (서울)</p>
        </div>
        <div className="t-request-detail-backlinks">
          {m.canDecide ? <a className="t-btn t-request-detail-back" href="/approvals">대기함으로</a>
            : completedApproval && <a className="t-btn t-request-detail-back" href="/approvals/completed">내가 처리한 결재</a>}
          <a className="t-btn t-request-detail-back" href="/me/requests">내 신청 </a>
        </div>
      </header>
```

새:

```tsx
      <PageHeader title="신청 상세" help="신청 내용과 실제 처리 이력을 확인하세요. 시각 기준: Asia/Seoul (서울)"
        actions={<div className="t-request-detail-backlinks">
          {m.canDecide ? <a className="t-btn t-request-detail-back" href="/approvals">대기함으로</a>
            : completedApproval && <a className="t-btn t-request-detail-back" href="/approvals/completed">내가 처리한 결재</a>}
          <a className="t-btn t-request-detail-back" href="/me/requests">내 신청 </a>
        </div>} />
```

나머지 두 곳은 다음과 같다.

```tsx
<header className="t-pagehead"><div><h1>출퇴근·실제 휴게 정정 신청</h1><p className="t-muted">확인한 기록과 실제 근로 내용을 입력해 주세요. 시각 기준은 Asia/Seoul (서울)입니다.</p></div></header>
```

```tsx
<PageHeader title="출퇴근·실제 휴게 정정 신청" help="확인한 기록과 실제 근로 내용을 입력해 주세요. 시각 기준은 Asia/Seoul (서울)입니다." />
```

```tsx
<header className="t-pagehead"><div><h1>출퇴근 기록</h1><p className="t-muted">출퇴근 시각과 실제 근로를 확인하세요.</p></div></header>
```

```tsx
<PageHeader title="출퇴근 기록" help="출퇴근 시각과 실제 근로를 확인하세요." />
```

- [ ] **Step 10: 남은 `t-pagehead` 없음 확인**

```bash
grep -rn "t-pagehead" frontend/src --include=*.tsx
```

Expected: 출력 없음.

- [ ] **Step 11: 타입 검사와 전체 테스트**

```bash
npm --prefix frontend run build
npm --prefix frontend test
```

Expected: 둘 다 성공한다.

설명 문장을 `getByText`나 `innerHTML`로 확인하던 테스트는 문장이 DOM에 남아 있으므로 통과해야 한다. 실패하는 테스트가 있으면 테스트를 고치기 전에 원인을 먼저 본다.

- `h1`의 형제 요소나 `header > div` 구조에 의존한 테스트라면, 테스트를 `getByRole('heading')` 기준으로 고친다.
- 그 밖의 원인이면 구현을 고친다.

- [ ] **Step 12: 화면 확인**

```bash
node "$TOOLS/capture.mjs" "$TOOLS/shots/t6-pagehead"
```

Expected: `0 with JS errors`. 다음 캡처를 직접 확인한다.

- `organizations-*-1440.png`: 제목 옆에 `?`가 있고, 설명 문장은 보이지 않는다.
- `requests-*-1440.png`: [신청] 버튼이 머리 오른쪽에 있다.

브라우저 창에서 `?`를 누르면 팝오버가 열리고, Esc를 누르면 닫히는지 확인한다.

- [ ] **Step 13: 커밋**

```bash
git add frontend/src/pages
git commit -F - <<'EOF'
refactor: move screen descriptions into page header help

Replace the 19 t-pagehead blocks with PageHeader. Descriptive sentences
move into the help popover unchanged; data lines and screen actions stay
visible.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 7: 조직 시트의 조직장 칸 띄어쓰기

"조직장 미배정"과 "조직장 배정" 링크가 붙어 보이는 문제를 고친다.

**Files:**
- Modify: `frontend/src/pages/OrgSheetPages.test.tsx` (파일 끝에 추가)
- Modify: `frontend/src/pages/OrgSheetPages.tsx:120`

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/src/pages/OrgSheetPages.test.tsx` 파일 끝에 추가한다. `show`는 이 파일에 이미 있는 도우미다.

```tsx
describe('조직장 칸 표시', () => {
  it('상태 문구와 배정 링크 사이를 띄운다', async () => {
    const view = await show();
    const cell = view.container.querySelector('.ag-row [col-id="organizationHead"]');
    expect(cell?.textContent).toBe('조직장 미배정 조직장 배정');
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
npm --prefix frontend test -- src/pages/OrgSheetPages.test.tsx -t "조직장 칸 표시"
```

Expected: FAIL. 기대값은 `'조직장 미배정 조직장 배정'`인데 실제 값은 `'조직장 미배정조직장 배정'`이다.

- [ ] **Step 3: 최소 수정**

`frontend/src/pages/OrgSheetPages.tsx` 120행에서 `HeadStatus`와 `<a>` 사이에 공백 텍스트를 넣는다.

옛:

```tsx
    return <><HeadStatus name={head?.employeeName ?? null} eligible={head?.eligible ?? false} approverEligible={head?.approverEligible ?? false} /><a href={queryHref('/admin/organization-heads', { organizationId: row.id })}>조직장 배정</a></>;
```

새:

```tsx
    return <><HeadStatus name={head?.employeeName ?? null} eligible={head?.eligible ?? false} approverEligible={head?.approverEligible ?? false} />{' '}<a href={queryHref('/admin/organization-heads', { organizationId: row.id })}>조직장 배정</a></>;
```

- [ ] **Step 4: 통과 확인과 커밋**

```bash
npm --prefix frontend test -- src/pages/OrgSheetPages.test.tsx
git add frontend/src/pages/OrgSheetPages.tsx frontend/src/pages/OrgSheetPages.test.tsx
git commit -F - <<'EOF'
fix: separate the organization head status from its assign link

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

Expected: 파일 전체 PASS.

---

### Task 8: 1단계 최종 검증과 문서

**Files:**
- Modify: `docs/superpowers/specs/2026-11-10-worklog-design-system-design.md` (5·6절 보정)
- Modify: `design.md`
- Create: `docs/quality/worklog-design-system-20261110.md`

- [ ] **Step 1: 전체 검증**

```bash
npm --prefix frontend test
npm --prefix frontend run build
node "$TOOLS/capture.mjs" "$TOOLS/shots/stage1"
git status --short
```

Expected:

- 테스트 전체 PASS, 빌드 성공.
- `24 captures, 0 with JS errors`이고 overflow는 기준 이하.
- `git status`에 커밋하지 않은 파일이 없다.

통과한 테스트 수는 출력의 `Tests  N passed` 값을 기록한다.

- [ ] **Step 2: 설계 문서 보정**

구현 중 정한 세부 사항을 설계에 반영한다.

5절 토큰 표의 `글자색` 행 뒤에 다음 행을 넣는다.

```markdown
| 경계·자리표시 | 입력·선택 경계는 g500(흰 바탕 3.1:1, WCAG 1.4.11). 자리표시 글자는 g600. 버튼 테두리와 표 선은 장식으로 보고 g300·g150을 쓴다 |
```

6절의 `- **새 파일:**` 항목과 그 아래 하위 항목 두 줄을 다음으로 바꾼다.

```markdown
- **새 파일:**
  - `frontend/src/design/tokens.css`: 토큰, 호환 별칭, Pretendard 동적 분할 CSS import
  - `frontend/src/design/ui.css`: 기본 컨트롤, 화면 머리·도움말. 이후 단계의 조건 바·구역 머리·문서형 표·정보 격자·안내·팝오버도 여기에 둔다
  - `frontend/src/design/shell.css`: 사이드바, 상단바, 본문 영역
```

같은 절의 `- **클래스 이름:**` 항목을 다음으로 바꾼다.

```markdown
- **클래스 이름:** 기본 컨트롤(`t-btn`·`t-icon-button`·`t-input`·`t-select`·`t-tag`)과 셸은 기존 이름을 유지한 채 새 층에서 다시 정의한다. 그래서 모든 화면에 바로 적용된다. 새로 만드는 부품만 `wl-`로 시작한다. 옮긴 부품에서는 옛 전용 클래스를 지워 legacy 규칙이 닿지 않게 한다.
```

- [ ] **Step 3: `design.md` 다시 쓰기**

루트 `design.md` 전체를 다음으로 바꾼다. 이전 이력은 git과 기존 문서 링크에 남는다.

```markdown
# WorkLog 디자인 시스템

여러 화면이 공유하는 업무 앱 디자인 기준이다. 설계와 결정 근거는 [공통 디자인 시스템 설계](docs/superpowers/specs/2026-11-10-worklog-design-system-design.md)와 [ADR-0006](docs/adr/0006-worklog-design-layer.md)을 따른다. 테마 전환 계약은 [workspace-themes](docs/design/workspace-themes.md)를 따른다.

## 방향

- **분위기:** 촘촘하고 정돈된 한국어 업무 화면이다. 흰 바탕에 선과 간격으로 구획한다. 그라디언트, 장식 그림자, 큰 둥근 카드는 쓰지 않는다.
- **테마:** 차콜(기본)과 블루. 두 테마는 brand 색만 다르다.
- **위계:** 채운 버튼은 영역마다 하나만 둔다. 설명 문장은 화면 머리의 도움말(?)로 옮긴다. 표는 2px 윗선과 1px 칸선을 쓴다.

## 토큰

색, 글꼴, 크기의 단일 기준은 `frontend/src/design/tokens.css`이다.

- **글꼴:** Pretendard Variable. 앱에 자체 포함하며 숫자는 `tabular-nums`로 쓴다.
- **글자:** 화면 제목 22/700, 모달 제목 18/700, 구역 제목 15/700, 본문 14, 표·버튼 13, 보조 12.
- **색:**
  - 회색 g0~g900. 흐린 글자는 g600 이상, 입력 경계는 g500을 쓴다.
  - 상태는 ok, warn, danger, info. 일정 막대는 `--ev-*`를 쓴다.
- **밀도:** 컨트롤 34px, 행 38px, 머리 36px. 모바일과 터치에서는 44px.
- **모서리:** 4(컨트롤), 6(팝오버·조건 바), 10(모달).
- **호환 별칭:** 기존 `--t-*`는 새 토큰의 별칭이다. 5단계에서 쓰는 곳이 없어지면 지운다.

## CSS 층

- 기존 CSS는 `@layer legacy`, AG Grid는 `@layer vendor`에 둔다.
- `frontend/src/design/*.css`는 층 없이 두어 legacy보다 우선한다. 그래서 새 규칙에 `#tna-app` 선택자를 쓰지 않는다.
- 기본 컨트롤과 셸은 기존 클래스 이름을 새 층에서 다시 정의한다. 새 부품은 `wl-` 클래스를 쓴다.

## 상태와 접근성

- 대비: 글자 4.5:1, 입력 경계 3:1 이상. `frontend/src/design/tokens.test.ts`가 이를 검사한다.
- 초점: 2px brand 선을 즉시 표시한다.
- 축소 모션 설정에서는 애니메이션을 끈다.
- 상태는 색과 문구를 함께 표시한다.

## 변경 경계

업무 동작, 링크, 폼 필드, 권한, 모달 탐색은 유지한다. 로그인 전 소개 화면은 토큰 별칭으로 글꼴과 색만 바뀌며 배치는 [입체 메인 화면](docs/design/worklog-landing.md)을 따른다.
```

- [ ] **Step 4: 품질 결과 문서 작성**

`docs/quality/worklog-design-system-20261110.md`를 만든다. 다음 표의 `실제 값` 칸은 Step 1 출력과 각 작업의 비교 결과로 채운다. 실행하지 않은 항목은 "미실행"으로 쓴다.

```markdown
# WorkLog 디자인 시스템 검증 결과

- 기준: 브랜치 `claude/worklog-design-system-20261110`
- 범위: 1단계 기반. 2~5단계 결과는 아래에 단계별로 추가한다.
- 자료: 합성 envelope만 사용했다(DB·업무 POST·공개 서버 없음). 캡처와 도구는 git 밖 `target/hallmark-tools/worklog-design-20261110`에 있다.

## 1단계 기반

| 항목 | 기대 | 실제 값 |
| --- | --- | --- |
| 프런트 테스트 | 전체 통과 | |
| 타입·빌드 | 성공 | |
| legacy 층 감싸기 전후 픽셀 비교 | 24장 차이 0 | |
| 합성 캡처 JS 오류 | 0 | |
| 390px 페이지 가로 넘침 | 기준 이하 | |
| 글꼴 | 24장 모두 Pretendard Variable | |
| 대비 계약 | `tokens.test.ts` 통과 | |
| 모바일 메뉴 열기·Esc 닫기 | 브라우저 창에서 확인 | |
| 도움말 팝오버 열기·Esc·초점 복귀 | 테스트와 브라우저 창 | |
| Java 테스트 | Java 변경 없음 | 미실행 |
| 실제 DB·공개 서버·실기기 | 범위 밖 | 미실행 |

## 남은 일

2단계(공통 시트)부터는 별도 계획으로 진행한다.
```

- [ ] **Step 5: 문서 확인과 커밋**

```bash
git diff --stat HEAD
git add docs/superpowers/specs/2026-11-10-worklog-design-system-design.md design.md docs/quality/worklog-design-system-20261110.md
git commit -F - <<'EOF'
docs: record the WorkLog design system foundation

Update design.md to the new token, layer and component rules, adjust the
spec for field contrast and file layout, and record stage 1 verification.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

- [ ] **Step 6: 정리**

미리보기 서버를 끈다. 백그라운드 작업이면 해당 작업을 중지한다.

브라우저 창의 화면 크기 흉내를 desktop으로 되돌린다.

사용자에게 다음을 보고하고 2단계 계획 작성을 확인받는다. Push는 사용자 확인 전에는 하지 않는다.

- 1단계 결과: 커밋 목록, 테스트 수, 캡처 전후 비교
- 남은 차이와 한계
