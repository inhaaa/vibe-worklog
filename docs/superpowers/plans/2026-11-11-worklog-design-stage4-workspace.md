# WorkLog 디자인 시스템 4단계(내 업무 화면) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 내 업무 화면을 시안 3번 틀(사원 맥락 줄 → 화면 머리 → 요약 줄 → 캘린더와 오른쪽 패널)로 바꾼다.
- 사원 헤더는 한 줄 맥락 줄로 줄인다.
- 근태 캘린더에 요약 줄, 종류별 막대, 오른쪽 패널(주간 근무·처리할 일)을 둔다.
- 내 정보·내 근태현황 본문(T4)은 밑줄 탭·정보 격자·문서형 표로 정리한다.

**Architecture:**

- 요약·주간·처리할 일 숫자는 순수 함수 모듈 `pages/scheduleSummary.ts`가 월 달력 자료(`WorkspaceDay`)만으로 계산한다. 새 API는 쓰지 않는다.
- `EmployeeHeader`에 `variant="line"`(맥락 줄)을 더한다. App은 내 정보·인사정보 등록 화면에서만 상세 카드를, 나머지 화면에서는 맥락 줄을 그린다.
- 캘린더는 테스트가 고정한 클래스·문구(`t-day`, `t-day-event.warning`·`.leave`, aria-label)를 그대로 둔다. 막대에 `data-kind`와 아이콘을 더하고, 새 층 CSS로 모양을 바꾼다.
- 새 CSS는 `frontend/src/design/workspace.css`(층 없음)에 둔다. App에서 `request.css` 다음에 불러온다.

**Tech Stack:** React 19.3.1, TypeScript 6.0.3, Vite 8.3.5, Vitest 5.0.5 + Testing Library(jsdom 26.1.0). 검증 도구는 git 밖의 Python `http.server` 미리보기와 `puppeteer-core`(로컬 Chrome)다.

**Spec:** [설계](../specs/2026-11-10-worklog-design-system-design.md) 9절 4단계, 7.9~7.11절, 8절 T2·T4 행. 3단계 결과는 [검증 결과](../../quality/worklog-design-system-20261110.md)의 3단계 절에 있다.

---

## 작업 위치와 공통 규칙

- **작업트리:** `C:\Users\ihpark\dev\vibe-attendance\target\worklog-design-20261110`, 브랜치 `claude/worklog-design-system-20261110`. 3단계 커밋은 Push했다(`94b8722`).
- **명령 실행:** Git Bash에서 아래 두 줄을 먼저 실행한다.
  ```bash
  cd /c/Users/ihpark/dev/vibe-attendance/target/worklog-design-20261110
  TOOLS=/c/Users/ihpark/dev/vibe-attendance/target/hallmark-tools/worklog-design-20261110
  ```
- **행 번호:** 계획에 적은 행 번호는 작성 시점(`94b8722`) 기준이다. 앞 작업에서 같은 파일을 고치면 밀리므로 함수 이름과 인용한 코드로 위치를 찾는다.
- **테스트·빌드:** `npm --prefix frontend test [-- <경로>]`, `npm --prefix frontend run build`.
- **커밋:** 작업마다 커밋하고 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`를 붙인다.
- **Push·배포:** 4단계를 마친 뒤 Push 여부를 묻는다. 공개 데모 배포는 하지 않는다. Java는 바꾸지 않는다.
- **지켜야 하는 테스트 계약:**
  - **캘린더(`CorePages.test.tsx`):** 다음 클래스·문구·이름을 그대로 둔다.
    - `.t-month-grid`, 빈칸 `span.t-day.outside[aria-hidden]`, 날짜 링크 `a.t-day`(`today`·`selected`·`weekend` 클래스)
    - 날짜 링크 aria-label `"날짜 · 일정 문구( · 선택한 날짜)"`, `time.t-day-number`
    - `.t-day-event.warning` 문구 `미확인`·`{유형} · {상태}`, `.t-day-event.leave`, `확인된 휴일`, `.t-day-detail h2 time`
    - region "선택한 날짜 상세", 버튼 "{날짜} 신청 메뉴", group "{날짜} 신청 메뉴"
  - **사원 헤더:**
    - region 이름 "사원 인사정보"
    - 상세 카드의 region "인사 요약"과 `dt`·`dd` 다섯 쌍
    - 요약만 있을 때 이름·회사만 표시
    - 홈에는 헤더 없음
    - 빌드 CSS에 `.t-employee-header`가 있어야 한다(`sheetAssets.test.ts`).
  - **내 정보:** tablist "인사정보 항목", tab 7개(각 아이콘 svg), tabpanel의 `aria-labelledby`, region "신원 정보" 등 정보 묶음, 표 "발령내역".

## 설계 보정 (설계 문서와 다르게 구현하는 결정)

12번 작업에서 설계 문서에 반영한다.

1. **맥락 줄을 쓰는 화면:**
   - 내 정보(`workspace/profile`)와 인사정보 등록(`org/personnel`)만 상세 카드를 쓰고, 나머지 업무 화면은 모두 맥락 줄을 쓴다.
   - 서버가 이름·회사만 주는 화면(관리 목록 등)에서는 맥락 줄에 이름과 회사만 둔다.
2. **맥락 줄 항목:** 설계 7.10대로 사번·소속·직급·직책·직무·입사일만 둔다. 생년월일은 빼므로, App 테스트 "공통 사원 헤더는 … 모든 인사정보를 한눈에 표시한다"를 맥락 줄 항목으로 바꾼다.
3. **요약 줄 항목:**
   - 예정 근무(일·시간), 승인 연차(일), 검토 중(건), 확인 필요(일)를 둔다.
   - 시안의 "잔여 연차"는 달력 자료에 없어 넣지 않는다(새 API 금지).
   - 검토 중이 1건 이상이면 warn 색, 확인 필요가 1일 이상이면 danger 색이다.
4. **하루 분류:** 하루를 아래 순서로 하나의 분류에만 넣는다. 시간은 예정 근무일만 세고 예정 휴게를 뺀다.
   - 승인 연차(`effectiveLeave`)
   - 미배정·미확인(일정 없음 또는 `UNCONFIRMED`): 0시간 근무로 세지 않고 "확인 필요"로 센다.
   - 비근로일·휴일(`workday=false`, `HOLIDAY`, `NON_WORKDAY`)
   - 예정 근무
5. **주간 근무:** 선택한 날짜가 속한 월요일~일요일이다. 앞뒤 달에 걸친 주는 이번 달 날짜만 세고 "이번 달 날짜만"이라고 적는다. 큰 숫자는 예정 근무 시간이다. 실제 근로 시간은 달력 자료에 없어 시안의 진행 막대는 넣지 않는다.
6. **처리할 일:** 이번 달의 검토 중 신청(여러 날짜에 걸친 신청은 한 번)과 미배정·미확인 날짜를 날짜순으로 둔다. 최대 6건만 보이고 나머지는 "외 N건"으로 적는다. 신청은 신청 상세로, 날짜는 그 날짜 선택으로 연결한다.
7. **화면 머리:** 월 조회 폼을 화면 머리 동작으로 옮기고, 시안의 주 동작 [근태 신청](`/me/requests/new?year=`, 기존 신청 모달)을 더한다. 범례는 시안의 다섯 종류로 바꾸고 "날짜 우클릭 또는 ⋯로 신청" 안내는 범례 끝에 남긴다.
8. **오른쪽 패널 위치:** 1200px 이상에서 캘린더 오른쪽 264px에 두고, 선택한 날짜 상세는 그 아래 전체 폭에 둔다. 1200px 미만에서는 캘린더 → 오른쪽 패널 → 선택한 날짜 상세 순서로 쌓는다.
9. **모바일 막대:** 600px 이하에서는 날짜 칸이 좁아 막대 글자를 숨기고 색·아이콘만 보인다. 날짜 링크의 aria-label과 아래의 선택한 날짜 상세가 내용을 전한다.
10. **내 정보 탭:** 넓은 화면에서 세로 목록으로 바뀌던 동작을 없애고 항상 가로 밑줄 탭으로 둔다(설계 8절 T4 "밑줄 탭"). 세로 방향 테스트는 가로 탭 테스트로 바꾸고, 쓰지 않게 된 화면 폭 감시 코드를 지운다.
11. **하지 않는 것:**
    - `today` 화면은 서버가 내보내지 않는다(`ReactPageViewResolver`에 등록되어 있지만 그 화면을 돌려주는 컨트롤러가 없다). 고치지 않고 기록만 한다.
    - `clock/calendar`(휴일 캘린더)는 월 달력이 아니라 버전 목록과 등록 폼이라 3단계 문서형 폼으로 옮긴다.
    - 내 근태현황의 신청 표는 600px 이하에서 legacy 카드 배치를 그대로 쓴다. 새 표 모양은 601px 이상에만 둔다.

## 파일 구조

| 파일 | 책임 | 작업 |
| --- | --- | --- |
| `frontend/src/pages/scheduleSummary.ts`, `.test.ts` | 하루 분류, 예정 시간, 집계, 주 범위, 검토 중 신청 | 생성 (1) |
| `frontend/src/components/EmployeeHeader.tsx`, `.test.tsx` | 맥락 줄 `variant="line"` | 수정 (2) |
| `frontend/src/App.tsx`, `App.test.tsx` | 화면별 헤더 모양 선택, `workspace.css` 불러오기 | 수정 (2) |
| `frontend/src/design/workspace.css` | 맥락 줄·요약 줄·캘린더·날짜 메뉴·오른쪽 패널·상세 카드·탭·내 근태현황 | 생성 (2), 수정 (3–5) |
| `frontend/src/cssLayers.test.js` | `workspace.css` 층 계약 | 수정 (2) |
| `frontend/src/pages/CorePages.tsx`, `.test.tsx` | 근태 캘린더 | 수정 (3) |
| `frontend/src/pages/PersonalDetails.tsx`, `.test.tsx`, `IdentityPages.tsx` | 내 정보 탭·정보 격자 | 수정 (4) |
| `frontend/src/pages/AnnualRequestPage.tsx` | 내 근태현황 본문 클래스 | 수정 (5) |
| `frontend/src/pages/AdminPages.tsx` | 휴일 캘린더 문서형 폼 | 수정 (6) |
| `design.md`, spec, 결과 문서, 이 계획 | 기준·결과 | 수정 (8) |

---

### Task 0: 내 업무 합성 화면과 기준 캡처 (커밋 없음)

**Files (git 밖):**
- Create: `$TOOLS/workspace_fixtures.py`
- Modify: `$TOOLS/preview.py`, `$TOOLS/capture.mjs`
- Create: `$TOOLS/workspace-check.mjs`

- [ ] **Step 1: 기준 브랜치 확인**

```bash
git fetch origin
git log --oneline HEAD..origin/codex/demo-entry-public-20261109
```

Expected: 출력 없음. 커밋이 있으면 멈추고 사용자에게 보고한다.

- [ ] **Step 2: `workspace_fixtures.py` 작성**

```python
"""4단계 내 업무 합성 화면. 합성 자료만 쓰며 실제 회사·사람 자료가 아니다."""
from copy import deepcopy


def fixtures(base, schedule):
    month_start = deepcopy(schedule)
    calendar = month_start['data']['workspaceCalendar']
    for entry in calendar['days']:
        entry['selected'] = entry['snapshot']['workDate'] == '2026-11-01'
    calendar['selectedDate'] = '2026-11-01'
    calendar['selectedDay'] = calendar['days'][0]
    month_start['data']['selectedDay'] = calendar['days'][0]['snapshot']

    holiday_calendar = base.envelope('clock/calendar', {
        'calendars': [{'id': 'cal-1', 'revision': 1, 'validFrom': '2026-01-01', 'validTo': '2027-01-01', 'reason': '2026년 휴일 검토'}],
        'form': {**base.COMMAND, 'from': '2027-01-01', 'to': '2027-12-31', 'basis': '', 'reviewed': False, 'exceptions': ''}})
    return {'/me/schedule-month-start': month_start, '/admin/calendar': holiday_calendar}
```

- [ ] **Step 3: `preview.py`·`capture.mjs` 연결**

`preview.py`의 `FIXTURES.update(modals.fixtures(base))` 줄 아래에 다음을 더한다.

```python
workspace_spec = importlib.util.spec_from_file_location('workspace_fixtures', HERE / 'workspace_fixtures.py')
workspace = importlib.util.module_from_spec(workspace_spec)
workspace_spec.loader.exec_module(workspace)
FIXTURES.update(workspace.fixtures(base, SCHEDULE))
```

`capture.mjs`의 `sets`에서 `modals` 묶음 뒤에 다음을 더한다.

```js
  workspace: [
    ['schedule', '/me/schedule'], ['schedule-month-start', '/me/schedule-month-start'], ['profile', '/me/profile'],
    ['requests', '/me/requests'], ['personnel-page', '/admin/employees/e-1/personnel'], ['admin-calendar', '/admin/calendar'],
    ['organizations', '/admin/organizations'],
  ],
```

- [ ] **Step 4: `workspace-check.mjs` 작성 (7번 작업에서 실행)**

```js
// Usage: node workspace-check.mjs <outDir> [baseUrl]
// 근태 캘린더의 맥락 줄 한 줄, 요약 줄·주간 근무 값, ⋯ 표시, 오른쪽 패널 위치(1440·1100), 390px 넘침을 확인한다.
import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
import path from 'node:path';

const require = createRequire('C:/Users/ihpark/dev/vibe-attendance/target/hallmark-tools/package.json');
const puppeteer = require('puppeteer-core');
const [out, base = 'http://127.0.0.1:18131'] = process.argv.slice(2);
mkdirSync(out, { recursive: true });
const browser = await puppeteer.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
const result = {};
const errors = [];
async function open(width, route = '/me/schedule') {
  const page = await browser.newPage();
  page.on('pageerror', error => errors.push(String(error)));
  await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }]);
  await page.setViewport({ width, height: 900, deviceScaleFactor: 1 });
  await page.goto(base + route, { waitUntil: 'networkidle0' });
  await page.evaluate(() => document.fonts.ready);
  return page;
}
const pairs = (page, selector) => page.$$eval(`${selector} dt`, terms => terms.map(term => [term.textContent.trim(), term.nextElementSibling?.textContent.trim()]));
try {
  let page = await open(1440);
  result.contextOneLine = await page.$eval('.wl-context', element => element.getBoundingClientRect().height <= 60);
  result.summary = JSON.stringify(await pairs(page, '.wl-summary'));
  result.week = JSON.stringify(await pairs(page, '.wl-side-facts'));
  result.weekHours = await page.$eval('.wl-side-big', element => element.firstChild.textContent.trim());
  result.panelBeside = await page.evaluate(() => document.querySelector('.wl-side').getBoundingClientRect().left
    > document.querySelector('.t-attendance-board').getBoundingClientRect().right - 1);
  const more = '.t-attendance-slot:has(a[href$="date=2026-11-03"]) .t-attendance-more';
  result.moreHidden = await page.$eval(more, button => getComputedStyle(button).opacity === '0');
  await page.hover('a[href$="date=2026-11-03"]');
  result.moreOnHover = await page.$eval(more, button => getComputedStyle(button).opacity === '1');
  await page.screenshot({ path: path.join(out, 'schedule-charcoal-1440.png') });
  await page.close();
  page = await open(1100);
  result.panelBelow = await page.evaluate(() => document.querySelector('.wl-side').getBoundingClientRect().top
    >= document.querySelector('.t-attendance-board').getBoundingClientRect().bottom - 1);
  await page.close();
  page = await open(390);
  result.overflow390 = await page.evaluate(() => document.documentElement.scrollWidth - innerWidth);
  await page.close();
  result.errors = errors.length;
} finally {
  await browser.close();
}
const expected = { contextOneLine: true,
  summary: JSON.stringify([['예정 근무', '19일 · 152:00'], ['승인 연차', '1일'], ['검토 중', '1건'], ['확인 필요', '1일']]),
  week: JSON.stringify([['예정 근무', '4일'], ['승인 연차', '1일'], ['비근로일·휴일', '2일']]), weekHours: '32:00',
  panelBeside: true, moreHidden: true, moreOnHover: true, panelBelow: true, overflow390: 0, errors: 0 };
const failed = Object.keys(expected).filter(key => result[key] !== expected[key]);
console.log(JSON.stringify(result), failed.length ? `FAIL ${failed.join(',')}` : 'PASS');
process.exit(failed.length ? 1 : 0);
```

합성 11월 자료의 기대값을 계산하면 다음과 같다.
- **한 달:** 평일 21일에서 승인 연차 1일(12일)과 미확인 1일(19일)을 빼면 예정 근무 19일, 19×8시간 = 152:00이다. 검토 중 1건(17일), 확인 필요 1일(19일)이다.
- **10일이 속한 주(9~15일):** 예정 근무 4일(32:00), 승인 연차 1일, 비근로일·휴일 2일이다.

- [ ] **Step 5: 서버 재시작과 기준 캡처**

켜져 있는 미리보기 서버를 멈추고 `python "$TOOLS/preview.py"`를 백그라운드로 다시 실행한다.

```bash
for p in /me/schedule-month-start /admin/calendar; do printf "%s %s\n" "$p" "$(curl -s -H 'Accept: application/vnd.worklog.page+json' "http://127.0.0.1:18131$p" | python -c 'import json,sys; print(json.load(sys.stdin)["page"])')"; done
npm --prefix frontend run build
node "$TOOLS/capture.mjs" "$TOOLS/shots/stage4-before" http://127.0.0.1:18131 workspace
```

Expected:
- 두 경로가 각각 `workspace/schedule`, `clock/calendar`를 출력한다.
- 캡처는 `28 captures, 0 with JS errors`다.

---

### Task 1: 캘린더 계산 모듈

**Files:**
- Create: `frontend/src/pages/scheduleSummary.ts`
- Create: `frontend/src/pages/scheduleSummary.test.ts`

- [ ] **Step 1: 실패하는 테스트 작성 (주 경계·월 경계·미배정)**

```ts
import { describe, expect, it } from 'vitest';
import { dayClass, formatMinutes, pendingRequests, plannedMinutes, tally, weekOf, type SummaryDay } from './scheduleSummary';

const work = { workday: true, startTime: '09:00', endTime: '18:00', breaks: [{ startTime: '12:00', endTime: '13:00' }] };
const rest = { workday: false, startTime: null, endTime: null, breaks: [] };
function day(date: string, changes: Partial<SummaryDay['snapshot']> = {}, requests: SummaryDay['requests'] = []): SummaryDay {
  const weekday = new Date(`${date}T00:00:00Z`).getUTCDay();
  const workday = weekday !== 0 && weekday !== 6;
  return { snapshot: { workDate: date, dayKind: workday ? 'WORKDAY' : 'NON_WORKDAY', schedule: workday ? work : rest, ...changes }, requests };
}
const request = (id: string, tone: string, effectiveLeave = false) => ({ request: { id }, tone, effectiveLeave });
const november = Array.from({ length: 30 }, (_, index) => day(`2026-11-${String(index + 1).padStart(2, '0')}`));

describe('내 근무 일정 요약 계산', () => {
  it('하루를 승인 연차·미배정/미확인·비근로일/휴일·예정 근무 순으로 하나만 분류하고 예정 휴게를 뺀다', () => {
    expect(dayClass(day('2026-11-10'))).toBe('work');
    expect(plannedMinutes(day('2026-11-10'))).toBe(480);
    expect(dayClass(day('2026-11-10', {}, [request('r-1', 'green', true)]))).toBe('leave');
    expect(dayClass(day('2026-11-10', { dayKind: 'HOLIDAY' }))).toBe('off');
    expect(dayClass(day('2026-11-14'))).toBe('off');
    expect(dayClass(day('2026-11-19', { schedule: null, dayKind: 'UNCONFIRMED' }))).toBe('unknown');
    expect(dayClass(day('2026-11-18', { dayKind: 'UNCONFIRMED' }))).toBe('unknown');
    expect(plannedMinutes(day('2026-11-19', { schedule: null }))).toBe(0);
    expect(plannedMinutes(day('2026-11-10', { schedule: { ...work, startTime: '09:00:00.000', endTime: '18:30:00' } }))).toBe(510);
  });

  it('미배정: 일정이 없거나 미확인인 날은 0시간 근무로 세지 않고 따로 센다', () => {
    const days = [day('2026-11-16'), day('2026-11-17', { schedule: null }), day('2026-11-18', { dayKind: 'UNCONFIRMED' })];
    expect(tally(days)).toEqual({ workDays: 1, workMinutes: 480, leaveDays: 0, offDays: 0, unknownDays: 2 });
  });

  it('주 경계: 선택한 날짜가 속한 월요일~일요일을 고른다', () => {
    const week = weekOf(november, '2026-11-12');
    expect([week.from, week.to, week.days.length, week.partial]).toEqual(['2026-11-09', '2026-11-15', 7, false]);
    expect(tally(week.days)).toEqual({ workDays: 5, workMinutes: 2400, leaveDays: 0, offDays: 2, unknownDays: 0 });
    expect(weekOf(november, '2026-11-15').from).toBe('2026-11-09');
    expect(weekOf(november, '2026-11-16').from).toBe('2026-11-16');
  });

  it('월 경계: 앞뒤 달에 걸친 주는 이번 달 날짜만 집계하고 걸쳤다고 알린다', () => {
    const first = weekOf(november, '2026-11-01');
    expect([first.from, first.to, first.days.map(entry => entry.snapshot.workDate), first.partial])
      .toEqual(['2026-10-26', '2026-11-01', ['2026-11-01'], true]);
    const last = weekOf(november, '2026-11-30');
    expect([last.from, last.to, last.days.map(entry => entry.snapshot.workDate), last.partial])
      .toEqual(['2026-11-30', '2026-12-06', ['2026-11-30'], true]);
    expect(tally(last.days).workDays).toBe(1);
  });

  it('검토 중 신청은 여러 날에 걸쳐도 한 번만 세고 처음 나온 날짜 순으로 둔다', () => {
    const days = [day('2026-11-17', {}, [request('r-2', 'amber')]), day('2026-11-18', {}, [request('r-2', 'amber')]),
      day('2026-11-05', {}, [request('r-1', 'amber'), request('r-3', 'green', true)])];
    expect(pendingRequests(days).map(entry => [entry.item.request.id, entry.date])).toEqual([['r-1', '2026-11-05'], ['r-2', '2026-11-17']]);
    expect(formatMinutes(9120)).toBe('152:00');
    expect(formatMinutes(510)).toBe('8:30');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/pages/scheduleSummary.test.ts`
Expected: FAIL. 모듈이 없다.

- [ ] **Step 3: 모듈 구현**

```ts
/** 내 근무 일정의 요약 줄·주간 근무·처리할 일 계산(설계 7.11). 새 API 없이 월 달력 자료만 쓴다. */
export interface SummaryRequest { request: { id: string }; tone: string; effectiveLeave: boolean }
export interface SummaryDay<R extends SummaryRequest = SummaryRequest> {
  snapshot: {
    workDate: string;
    dayKind: string;
    schedule: { workday: boolean; startTime: string | null; endTime: string | null; breaks: { startTime: string; endTime: string }[] } | null;
  };
  requests: R[];
}
export type DayClass = 'work' | 'off' | 'leave' | 'unknown';
export interface Tally { workDays: number; workMinutes: number; leaveDays: number; offDays: number; unknownDays: number }

const DAY = 86_400_000;
const clock = (time: string | null) => (time ? Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5)) : 0);

/** 승인 연차 → 미배정·미확인 → 비근로일·휴일 → 예정 근무 순으로 하루를 하나로 분류한다. */
export function dayClass(day: SummaryDay): DayClass {
  const { schedule, dayKind } = day.snapshot;
  if (day.requests.some(item => item.effectiveLeave)) return 'leave';
  if (!schedule || dayKind === 'UNCONFIRMED') return 'unknown';
  if (!schedule.workday || dayKind === 'HOLIDAY' || dayKind === 'NON_WORKDAY') return 'off';
  return 'work';
}

/** 예정 근무일의 근무 시간(분). 예정 휴게를 뺀다. 다른 분류는 0이다. */
export function plannedMinutes(day: SummaryDay) {
  const schedule = day.snapshot.schedule;
  if (dayClass(day) !== 'work' || !schedule) return 0;
  const breaks = schedule.breaks.reduce((sum, pause) => sum + Math.max(0, clock(pause.endTime) - clock(pause.startTime)), 0);
  return Math.max(0, clock(schedule.endTime) - clock(schedule.startTime) - breaks);
}

export function tally(days: SummaryDay[]): Tally {
  const result: Tally = { workDays: 0, workMinutes: 0, leaveDays: 0, offDays: 0, unknownDays: 0 };
  for (const day of days) {
    const kind = dayClass(day);
    if (kind === 'work') { result.workDays += 1; result.workMinutes += plannedMinutes(day); }
    else if (kind === 'leave') result.leaveDays += 1;
    else if (kind === 'off') result.offDays += 1;
    else result.unknownDays += 1;
  }
  return result;
}

/** date가 속한 월~일 한 주 중 달력에 있는 날만 고른다. partial은 그 주가 앞뒤 달에 걸쳤다는 뜻이다. */
export function weekOf<D extends SummaryDay>(days: D[], date: string) {
  const time = Date.parse(`${date}T00:00:00Z`);
  const monday = time - ((new Date(time).getUTCDay() + 6) % 7) * DAY;
  const from = new Date(monday).toISOString().slice(0, 10);
  const to = new Date(monday + 6 * DAY).toISOString().slice(0, 10);
  return { from, to, partial: from.slice(0, 7) !== to.slice(0, 7), days: days.filter(day => day.snapshot.workDate >= from && day.snapshot.workDate <= to) };
}

/** 검토 중(tone amber) 신청. 여러 날에 걸친 신청은 처음 나온 날짜 하나로 센다. */
export function pendingRequests<R extends SummaryRequest>(days: SummaryDay<R>[]) {
  const seen = new Map<string, { item: R; date: string }>();
  for (const day of days)
    for (const item of day.requests)
      if (item.tone === 'amber' && !seen.has(item.request.id)) seen.set(item.request.id, { item, date: day.snapshot.workDate });
  return [...seen.values()].sort((a, b) => a.date.localeCompare(b.date));
}

export const formatMinutes = (total: number) => `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix frontend test -- src/pages/scheduleSummary.test.ts`
Expected: PASS (5개)

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/pages/scheduleSummary.ts frontend/src/pages/scheduleSummary.test.ts
git commit -m "feat: compute monthly and weekly schedule summaries from calendar days" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 사원 맥락 줄

**Files:**
- Modify: `frontend/src/components/EmployeeHeader.tsx`, `EmployeeHeader.test.tsx`
- Modify: `frontend/src/App.tsx:27, 232-234`, `frontend/src/App.test.tsx:170-182`
- Create: `frontend/src/design/workspace.css`
- Modify: `frontend/src/cssLayers.test.js`

- [ ] **Step 1: 실패하는 테스트 작성**

`EmployeeHeader.test.tsx` 끝에 다음을 더한다.

```tsx
it('내 업무 화면의 맥락 줄은 한 줄에 재직 상태와 사번·소속·직급·직책·직무·입사일을 둔다', () => {
  render(<EmployeeHeader employee={employee} variant="line" />);
  const line = screen.getByRole('region', { name: '사원 인사정보' });
  expect(line.className).toBe('wl-context');
  const facts = within(line).getAllByRole('term').map(term => [term.textContent, term.nextElementSibling?.textContent]);
  expect(facts).toEqual([['사번', 'E001'], ['소속', '개발팀'], ['직급·직책', '선임 · 팀원'], ['직무', '개발'], ['입사일', '2024-03-04']]);
  expect(within(line).getByText('휴직').className).toBe('t-tag amber');
  expect(within(line).getByRole('img', { name: '김직원 사원사진' })).toBeTruthy();
  expect(line.textContent).not.toContain('1990-01-01');
  expect(within(line).queryByRole('region', { name: '인사 요약' })).toBeNull();
});

it('요약만 있는 화면의 맥락 줄은 이름과 회사만 표시한다', () => {
  render(<EmployeeHeader employee={{ employeeName: '김직원', companyName: '합성회사' }} variant="line" />);
  expect(screen.getByRole('region', { name: '사원 인사정보' }).textContent).toBe('김김직원합성회사');
});
```

`App.test.tsx`의 `'공통 사원 헤더는 탭 없이 허용된 대상의 모든 인사정보를 한눈에 표시한다'` 테스트에서 이름과 값 목록 두 줄을 바꾼다.

```tsx
  it('공통 사원 맥락 줄은 한 줄에 사번·소속·직급·직책·직무·입사일과 재직 상태를 표시한다', () => {
```

```tsx
    for (const value of ['선택사원', 'E002', '개발팀', '선임', '팀원', '개발', '2024-01-02', '재직'])
      expect(header.textContent).toContain(value);
    expect(header.textContent).not.toContain('1991-03-04');
```

`cssLayers.test.js`의 `it.each(['src/components/Modal.css', 'src/design/request.css'])`에 `'src/design/workspace.css'`를 더한다.

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/components/EmployeeHeader.test.tsx src/App.test.tsx src/cssLayers.test.js`
Expected: FAIL. `variant`가 없어 상세 카드가 나오고, `workspace.css`가 없다.

- [ ] **Step 3: `EmployeeHeader` 구현**

함수 머리와 `entries` 다음에 맥락 줄을 더한다. 상세 카드 반환문은 그대로다.

```tsx
const stateTones: Record<string, string> = { WORKING: 'green', ON_LEAVE: 'amber', SUSPENDED: 'amber', PRE_JOINING: 'blue' };

export function EmployeeHeader({ employee, variant = 'card' }: { employee: EmployeeHeaderData | EmployeeSummary; variant?: 'card' | 'line' }) {
```

`const entries = …;` 다음 줄에 넣는다.

```tsx
  const initial = Array.from(employee.employeeName)[0] || 'W';
  if (variant === 'line') return <section className="wl-context" aria-label="사원 인사정보">
    <span className="wl-context-avatar">
      {photo ? <img src={photo} alt={`${employee.employeeName} 사원사진`} onError={() => setFailedUrl(photo)} />
        : <span aria-hidden="true">{initial}</span>}
    </span>
    <strong className="wl-context-name">{employee.employeeName}</strong>
    {details ? <>
      <span className={`t-tag ${Object.hasOwn(stateTones, details.employmentStatus) ? stateTones[details.employmentStatus] : ''}`.trim()}>
        {employmentStatusName(details.employmentStatus)}
      </span>
      <dl className="wl-context-facts">
        <div><dt>사번</dt><dd>{details.employeeNo}</dd></div>
        <div><dt>소속</dt><dd>{details.organizationName ?? '소속 미배정'}</dd></div>
        <div><dt>직급·직책</dt><dd>{[details.gradeName, details.positionName].filter(Boolean).join(' · ') || '미등록'}</dd></div>
        <div><dt>직무</dt><dd>{details.jobName || '미등록'}</dd></div>
        <div><dt>입사일</dt><dd>{details.joinedOn}</dd></div>
      </dl>
    </> : <span className="wl-context-company">{employee.companyName}</span>}
  </section>;
```

상세 카드의 이니셜 `{Array.from(employee.employeeName)[0] || 'W'}`도 `{initial}`로 바꾼다.

- [ ] **Step 4: App에서 모양 선택과 CSS 불러오기**

`App.tsx`의 `import './design/request.css';` 아래에 `import './design/workspace.css';`를 더한다. `<EmployeeHeader employee={…} />`를 아래로 바꾼다.

```tsx
          <EmployeeHeader variant={page === 'workspace/profile' || page === 'org/personnel' ? 'card' : 'line'}
            employee={demo ? { ...headerEmployee, companyName: workspaceName } : headerEmployee} />
```

- [ ] **Step 5: `design/workspace.css` 작성 (맥락 줄·상세 카드)**

```css
/* 내 업무 화면(설계 7.9–7.11, 시안 3번). Unlayered: wins over legacy EmployeeWorkspace.css, ScheduleCalendar.css,
   PersonalDetails.css and RequestWorkspace.css for the properties set here. */

/* Context bar: avatar, name and state, then 사번·소속·직급·직책·직무·입사일 on one line (wraps when narrow). */
.wl-context { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; margin: 0 0 18px; padding: 10px 14px; border: 1px solid var(--g200); border-radius: var(--r-pop); background: var(--g0); color: var(--g700); font-size: 13px; }
.wl-context-avatar { display: inline-grid; place-items: center; flex: none; width: 34px; height: 34px; overflow: hidden; border-radius: 50%; background: var(--g800); color: var(--g0); font-weight: 700; }
.wl-context-avatar img { width: 100%; height: 100%; object-fit: cover; }
.wl-context-name { color: var(--g900); font-size: 14px; font-weight: 700; }
.wl-context-company { color: var(--g600); }
.wl-context-facts { display: flex; flex-wrap: wrap; gap: 4px 16px; margin: 0 0 0 4px; padding-left: 16px; border-left: 1px solid var(--g200); }
.wl-context-facts > div { display: flex; gap: 6px; }
.wl-context-facts dt { color: var(--g600); }
.wl-context-facts dd { margin: 0; color: var(--g800); font-weight: 500; font-variant-numeric: tabular-nums; }

/* Detail card (내 정보, 인사정보 등록): one bordered card, identity on the left, facts as an info grid. */
.t-employee-header { display: grid; grid-template-columns: minmax(240px, auto) minmax(0, 1fr); align-items: center; gap: 16px 24px; margin: 0 0 20px; padding: 16px 20px; border: 1px solid var(--g200); border-radius: var(--r-pop); background: var(--g0); box-shadow: none; }
.t-employee-identity-card { display: flex; align-items: center; gap: 14px; min-width: 0; padding: 0; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
.t-employee-portrait { display: grid; place-items: center; flex: none; width: 56px; height: 56px; overflow: hidden; border-radius: 50%; background: var(--g800); color: var(--g0); font: 700 20px/1 var(--font-ui); }
.t-employee-portrait img { width: 100%; height: 100%; object-fit: cover; }
.t-employee-name-row { display: flex; align-items: center; gap: 8px; }
.t-employee-name-row h2 { margin: 0; color: var(--g900); font: 700 18px/1.3 var(--font-ui); letter-spacing: -.02em; }
.t-employment-state { display: inline-flex; align-items: center; min-height: 22px; padding: 0 7px; border-radius: var(--r-ctl); background: var(--g100); color: var(--g700); font: 600 12px/1 var(--font-ui); }
.t-employment-state.t-state-working { background: var(--ok-bg); color: var(--ok); }
.t-employee-company, .t-employee-meta { margin: 2px 0 0; color: var(--g600); font-size: 12.5px; }
.t-employee-meta { display: flex; flex-wrap: wrap; gap: 4px 12px; }
.t-employee-meta svg { width: 14px; height: 14px; margin-right: 4px; vertical-align: -2px; }
.t-employee-summary { min-width: 0; padding: 0 0 0 24px; border: 0; border-left: 1px solid var(--g200); border-radius: 0; background: transparent; box-shadow: none; }
.t-employee-facts { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px 20px; margin: 0; }
.t-employee-facts > div { min-width: 0; padding: 0; border: 0; }
.t-employee-facts dt { color: var(--g600); font: 500 12px/1.5 var(--font-ui); }
.t-employee-facts dd { margin: 2px 0 0; color: var(--g900); font: 600 14px/1.5 var(--font-ui); font-variant-numeric: tabular-nums; }

@media (max-width: 900px) {
  .t-employee-header { grid-template-columns: minmax(0, 1fr); }
  .t-employee-summary { padding: 12px 0 0; border-top: 1px solid var(--g150); border-left: 0; }
  .t-employee-facts { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 600px) {
  .wl-context-facts { width: 100%; margin: 0; padding: 8px 0 0; border-top: 1px solid var(--g150); border-left: 0; }
  .t-employee-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
```

- [ ] **Step 6: 통과 확인**

Run: `npm --prefix frontend test -- src/components/EmployeeHeader.test.tsx src/App.test.tsx src/cssLayers.test.js src/sheetAssets.test.ts`
Expected: PASS

Run: `npm --prefix frontend test && npm --prefix frontend run build`
Expected: 전체 통과, 빌드 성공

- [ ] **Step 7: 커밋**

```bash
git add frontend/src/components/EmployeeHeader.tsx frontend/src/components/EmployeeHeader.test.tsx frontend/src/App.tsx frontend/src/App.test.tsx frontend/src/design/workspace.css frontend/src/cssLayers.test.js
git commit -m "feat: shrink the workspace employee header to a one-line context bar" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 근태 캘린더

**Files:**
- Modify: `frontend/src/pages/CorePages.tsx:1-8, 314-554`
- Modify: `frontend/src/pages/CorePages.test.tsx` (테스트 추가)
- Modify: `frontend/src/design/workspace.css` (끝에 추가)

- [ ] **Step 1: 실패하는 테스트 작성**

`CorePages.test.tsx`의 `'달력은 기존 7열·빈칸·주말·오늘·선택·미확인 표시와 날짜 접근성 계약을 유지한다'` 테스트 바로 위에 다음을 더한다.

```tsx
  it('요약 줄·막대·오른쪽 패널은 이번 달 자료로 예정 근무·연차·검토 중·확인 필요와 선택한 주를 계산한다', () => {
    const work = { workday: true, startTime: '09:00', endTime: '18:00', breaks: [{ startTime: '12:00', endTime: '13:00' }] };
    const entry = (date: string, changes: Record<string, unknown> = {}, requests: unknown[] = []) => ({
      ...day, today: false, selected: date === '2026-11-10', requests, scheduleText: '09:00–18:00',
      snapshot: { ...snapshot, workDate: date, dayKind: 'WORKDAY', schedule: work, ...changes },
    });
    const days = [
      entry('2026-11-09'), entry('2026-11-10'),
      entry('2026-11-11', {}, [{ request: { id: 'leave', fromDate: '2026-11-11', toDate: '2026-11-11' }, kindLabel: '종일 연차', statusLabel: '승인 유효', tone: 'green', effectiveLeave: true }]),
      entry('2026-11-12', {}, [{ request: { id: 'wait', fromDate: '2026-11-12', toDate: '2026-11-12' }, kindLabel: '종일 연차', statusLabel: '검토 중', tone: 'amber', effectiveLeave: false }]),
      entry('2026-11-13', { schedule: null, dayKind: 'UNCONFIRMED' }),
      entry('2026-11-14', { dayKind: 'NON_WORKDAY', schedule: { ...work, workday: false } }),
      entry('2026-11-16'),
    ];
    const view = render(<CorePages {...props('workspace/schedule', { workspaceCalendar: {
      month: '2026-11', selectedDate: '2026-11-10', days, leadingBlanks: [], previousMonth: null, nextMonth: null, selectedDay: days[1] } })} />);
    const pairs = (root: Element) => [...root.querySelectorAll('dt')].map(term => [term.textContent, term.nextElementSibling?.textContent]);
    expect(pairs(view.container.querySelector('.wl-summary')!)).toEqual([['예정 근무', '4일 · 32:00'], ['승인 연차', '1일'], ['검토 중', '1건'], ['확인 필요', '1일']]);
    const panel = view.getByRole('complementary', { name: '주간 근무와 처리할 일' });
    expect(within(panel).getByText('11.09 (월) – 11.15 (일)')).toBeTruthy();
    expect(panel.querySelector('.wl-side-big')?.firstChild?.textContent).toBe('24:00');
    expect(pairs(panel.querySelector('.wl-side-facts')!)).toEqual([['예정 근무', '3일'], ['승인 연차', '1일'], ['비근로일·휴일', '1일'], ['확인 필요', '1일']]);
    expect(within(panel).getAllByRole('link').map(link => [link.textContent, link.getAttribute('href')])).toEqual([
      ['11.12 (목) 종일 연차', '/requests/wait'], ['11.13 (금) 근무 미배정', '/me/schedule?month=2026-11&date=2026-11-13'],
    ]);
    const bar = (date: string, selector: string) => view.container.querySelector(`a[href$="date=${date}"] ${selector}`);
    expect(bar('2026-11-11', '.t-day-event.leave')?.getAttribute('data-kind')).toBe('leave');
    expect(bar('2026-11-11', '.t-day-event.leave svg')).not.toBeNull();
    expect(bar('2026-11-12', '.t-day-event.warning')?.getAttribute('data-kind')).toBe('wait');
    expect(bar('2026-11-13', '.t-attendance-plan')?.getAttribute('data-kind')).toBe('unknown');
    expect(bar('2026-11-14', '.t-attendance-plan')?.getAttribute('data-kind')).toBe('off');
    expect(bar('2026-11-09', '.t-attendance-plan')?.getAttribute('data-kind')).toBe('work');
    expect(view.getByRole('link', { name: '근태 신청' }).getAttribute('href')).toBe('/me/requests/new?year=2026');
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/pages/CorePages.test.tsx`
Expected: FAIL. `.wl-summary`가 없다.

- [ ] **Step 3: 캘린더 구현**

`CorePages.tsx` import에 다음을 더한다(`ReactNode`는 type import에 함께 둔다).

```tsx
import type { ReactNode } from 'react';
import { dayClass, formatMinutes, pendingRequests, tally, weekOf } from './scheduleSummary';
```

`calendarRequestRestriction` 함수 아래에 막대 부품을 둔다.

```tsx
const barIcons = {
  work: 'M12 7v5l3 2M20 12a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z',
  off: 'M5 21V4m0 0h11l-2 4 2 4H5',
  leave: 'm5 12 4 4 10-10',
  wait: 'M7 3h10M7 21h10M8 3c0 5 8 6 8 9s-8 4-8 9M16 3c0 5-8 6-8 9',
} as const;
type BarKind = keyof typeof barIcons | 'unknown' | 'other';
/** 캘린더 막대: 종류별 색·아이콘·문구(설계 7.11). 클래스와 문구는 기존 계약을 그대로 쓴다. */
function Bar({ kind, className = '', children }: { kind: BarKind; className?: string; children: ReactNode }) {
  const icon = kind in barIcons ? barIcons[kind as keyof typeof barIcons] : null;
  return <span className={`t-day-event ${className}`.trim()} data-kind={kind}>
    {icon && <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d={icon} /></svg>}
    {children}
  </span>;
}
const shortDate = (date: string) =>
  `${date.slice(5, 7)}.${date.slice(8, 10)} (${weekdays[(new Date(`${date}T00:00:00Z`).getUTCDay() + 6) % 7]})`;
```

`Schedule` 안 `const today = …;` 다음에 계산을 둔다.

```tsx
  const sideId = useId();
  const summary = tally(calendar.days);
  const pending = pendingRequests(calendar.days);
  const week = weekOf(calendar.days, calendar.selectedDate);
  const weekTally = tally(week.days);
  const todo = [
    ...pending.map(({ item, date }) => ({ key: `request-${item.request.id}`, date, label: item.kindLabel, state: item.statusLabel, tone: 'amber', href: `/requests/${item.request.id}` })),
    ...calendar.days.filter(entry => dayClass(entry) === 'unknown').map(entry => ({
      key: `day-${entry.snapshot.workDate}`, date: entry.snapshot.workDate, label: entry.snapshot.schedule ? '캘린더 미확인' : '근무 미배정',
      state: '담당자 확인', tone: '', href: `/me/schedule?month=${calendar.month}&date=${entry.snapshot.workDate}`,
    })),
  ].sort((a, b) => a.date.localeCompare(b.date));
  const planKind = (entry: WorkspaceDay): BarKind => {
    const schedule = entry.snapshot.schedule;
    if (!schedule) return 'unknown';
    return !schedule.workday || ['HOLIDAY', 'NON_WORKDAY'].includes(entry.snapshot.dayKind) ? 'off' : 'work';
  };
```

`return (` 아래 `<>`부터 `<div className="t-month-grid">` 바로 앞까지를 아래로 바꾼다. 화면 머리에 월 조회 폼과 [근태 신청]을 두고, 요약 줄을 더하고, 범례를 다섯 종류로 바꾼다.

```tsx
    <>
      <PageHeader title="근태 캘린더" help="배정된 근무와 신청 상태를 확인하고 날짜에서 바로 신청하세요." actions={<>
        <Form method="get" action="/me/schedule" className="wl-month-jump">
          <label className="t-sr-only" htmlFor="workspace-month">조회 월</label>
          <input className="t-input" id="workspace-month" type="month" name="month"
            defaultValue={calendar.month} min="0001-01" max="9999-12" required />
          <button className="t-btn" type="submit">월 조회</button>
        </Form>
        <a className="t-btn primary" href={`/me/requests/new?${new URLSearchParams({ year })}`}>근태 신청</a>
      </>} />
      <dl className="wl-summary" aria-label={`${year}년 ${Number(month)}월 요약`}>
        <div><dt>예정 근무</dt><dd>{summary.workDays}일 · {formatMinutes(summary.workMinutes)}</dd></div>
        <div><dt>승인 연차</dt><dd>{summary.leaveDays}일</dd></div>
        <div data-tone={pending.length ? 'warn' : undefined}><dt>검토 중</dt><dd>{pending.length}건</dd></div>
        <div data-tone={summary.unknownDays ? 'danger' : undefined}><dt>확인 필요</dt><dd>{summary.unknownDays}일</dd></div>
      </dl>
      <div className="t-attendance-calendar">
        <section className="t-card t-calendar-card t-attendance-board" aria-label="월별 근무 일정">
          <div className="t-calendar-toolbar">
            <h2 className="t-attendance-month"><span>{year}년</span>{' '}{Number(month)}월</h2>
            <div className="t-month-navigation" aria-label="표시 월 이동">
              {calendar.previousMonth && (
                <a className="t-icon-button" href={`/me/schedule?month=${calendar.previousMonth}`} aria-label="이전 달">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="m14 6-6 6 6 6" /></svg>
                </a>
              )}
              <a className="t-btn secondary" href="/me/schedule">오늘</a>
              {calendar.nextMonth && (
                <a className="t-icon-button" href={`/me/schedule?month=${calendar.nextMonth}`} aria-label="다음 달">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="m10 6 6 6-6 6" /></svg>
                </a>
              )}
            </div>
            <div className="t-calendar-legend">
              {([['work', '예정 근무'], ['off', '비근로일·휴일'], ['leave', '승인 연차'], ['wait', '검토 중'], ['unknown', '미배정·미확인']] as const)
                .map(([kind, label]) => <span key={kind}><i className="wl-legend-swatch" data-kind={kind} aria-hidden="true" />{label}</span>)}
              <span className="t-attendance-hint">날짜 우클릭 또는 ⋯로 신청</span>
            </div>
          </div>
          <div className="t-weekdays" aria-hidden="true">
            {weekdays.map((day) => (
              <span key={day}>{day}</span>
            ))}
          </div>
```

날짜 칸의 `<a className={['t-day', …]}` 여는 태그에 `data-weekend`를 더한다(`weekend` 계산 다음 줄에 `const weekday = new Date(`${date}T00:00:00Z`).getUTCDay();`를 두고 `weekend`도 그 값으로 계산한다).

```tsx
              const weekday = new Date(`${date}T00:00:00Z`).getUTCDay();
              const weekend = weekday === 0 || weekday === 6;
```

```tsx
                  <a
                    className={['t-day', entry.selected && 'selected', entry.today && 'today', weekend && 'weekend'].filter(Boolean).join(' ')}
                    data-weekend={weekday === 6 ? 'sat' : weekday === 0 ? 'sun' : undefined}
```

막대 다섯 종류(현재 461–472행)를 `Bar`로 바꾼다. 클래스와 문구는 그대로다.

```tsx
                    <Bar kind={planKind(entry)} className="t-attendance-plan">
                      {entry.snapshot.schedule == null ? '미배정' : entry.scheduleText}
                    </Bar>
                    {entry.snapshot.dayKind === 'UNCONFIRMED' && <Bar kind="unknown" className="warning">미확인</Bar>}
                    {entry.snapshot.dayKind === 'HOLIDAY' && <Bar kind="off">확인된 휴일</Bar>}
                    {entry.requests.slice(0, 2).map(item => (
                      <Bar key={item.request.id} kind={item.effectiveLeave ? 'leave' : item.tone === 'amber' ? 'wait' : 'other'}
                        className={item.effectiveLeave ? 'leave' : item.tone === 'amber' ? 'warning' : ''}>
                        {item.kindLabel} · {item.statusLabel}
                      </Bar>
                    ))}
                    {entry.requests.length > 2 && <Bar kind="other">+{entry.requests.length - 2}건, 날짜 상세</Bar>}
```

`<p className="t-calendar-note">…</p>`와 `</section>` 다음, 선택한 날짜 상세 `<section …t-day-detail…>` 앞에 오른쪽 패널을 넣는다.

```tsx
        {(todo.length > 0 || weekTally.workDays + weekTally.leaveDays + weekTally.offDays > 0) && (
          <aside className="wl-side" aria-label="주간 근무와 처리할 일">
            {weekTally.workDays + weekTally.leaveDays + weekTally.offDays > 0 && (
              <section className="wl-side-block" aria-labelledby={`${sideId}-week`}>
                <h3 id={`${sideId}-week`}>주간 근무</h3>
                <p className="wl-side-period">{shortDate(week.from)} – {shortDate(week.to)}{week.partial && ' · 이번 달 날짜만'}</p>
                <p className="wl-side-big">{formatMinutes(weekTally.workMinutes)}<small>예정</small></p>
                <dl className="wl-side-facts">
                  <div data-kind="work"><dt>예정 근무</dt><dd>{weekTally.workDays}일</dd></div>
                  <div data-kind="leave"><dt>승인 연차</dt><dd>{weekTally.leaveDays}일</dd></div>
                  <div data-kind="off"><dt>비근로일·휴일</dt><dd>{weekTally.offDays}일</dd></div>
                  {weekTally.unknownDays > 0 && <div data-kind="unknown"><dt>확인 필요</dt><dd>{weekTally.unknownDays}일</dd></div>}
                </dl>
              </section>
            )}
            {todo.length > 0 && (
              <section className="wl-side-block" aria-labelledby={`${sideId}-todo`}>
                <h3 id={`${sideId}-todo`}>처리할 일</h3>
                <ul className="wl-side-list">
                  {todo.slice(0, 6).map(item => (
                    <li key={item.key}><a href={item.href}>{shortDate(item.date)} {item.label}</a><span className={`t-tag ${item.tone}`.trim()}>{item.state}</span></li>
                  ))}
                </ul>
                {todo.length > 6 && <p className="wl-side-more">외 {todo.length - 6}건</p>}
              </section>
            )}
          </aside>
        )}
```

선택한 날짜 상세의 `<dl className="t-attendance-facts">`는 `<dl className="wl-info-grid">`로 바꾼다.

- [ ] **Step 4: 캘린더 CSS 추가**

`design/workspace.css` 끝에 다음을 더한다.

```css
/* Summary line: g50, items split by vertical lines, warn/danger only for values that need attention. */
.wl-summary { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 0; margin: 0 0 16px; padding: 10px 6px; border-radius: var(--r-pop); background: var(--g50); color: var(--g700); font-size: 13px; }
.wl-summary > div { display: flex; align-items: baseline; gap: 6px; padding: 0 14px; border-left: 1px solid var(--g200); }
.wl-summary > div:first-child { border-left: 0; }
.wl-summary dd { margin: 0; color: var(--g900); font-weight: 700; font-variant-numeric: tabular-nums; }
.wl-summary [data-tone="warn"] dd { color: var(--warn); }
.wl-summary [data-tone="danger"] dd { color: var(--danger); }
.wl-month-jump { display: flex; align-items: center; gap: 6px; margin: 0; }
.wl-month-jump .t-input { width: 150px; }

/* Calendar frame: calendar and the 264px side panel from 1200px, stacked below that. */
.t-main:has(.t-attendance-calendar) { background: var(--g0); }
.t-attendance-calendar { display: grid; grid-template-columns: minmax(0, 1fr); gap: 20px; min-width: 0; }
.t-attendance-board { margin: 0; padding: 0; overflow: visible; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
.t-attendance-board .t-calendar-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; margin: 0 0 10px; padding: 0; }
.t-attendance-board .t-attendance-month { display: block; min-width: 0; margin: 0; color: var(--g900); font: 700 16px/1.3 var(--font-ui); letter-spacing: -.02em; font-variant-numeric: tabular-nums; }
.t-attendance-board .t-attendance-month span { color: inherit; font-size: inherit; }
.t-attendance-board .t-month-navigation { display: flex; align-items: center; gap: 4px; }
.t-attendance-board .t-month-navigation .t-icon-button { width: 28px; height: 28px; }
.t-attendance-board .t-month-navigation .t-btn { min-height: 28px; padding: 0 10px; }
.t-attendance-board .t-calendar-legend { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 14px; margin: 0 0 0 auto; padding: 0; color: var(--g600); font-size: 12px; }
.t-attendance-board .t-calendar-legend > span { display: inline-flex; align-items: center; gap: 5px; }
.t-attendance-board .t-attendance-hint { margin: 0; color: var(--g600); }
.wl-legend-swatch { width: 10px; height: 10px; border-radius: 2px; background: var(--g100); }
.wl-legend-swatch[data-kind="work"] { background: var(--ev-work); box-shadow: inset 0 0 0 1px var(--ev-work-ink); }
.wl-legend-swatch[data-kind="off"] { background: var(--ev-off); box-shadow: inset 0 0 0 1px var(--ev-off-ink); }
.wl-legend-swatch[data-kind="leave"] { background: var(--ev-leave); box-shadow: inset 0 0 0 1px var(--ev-leave-ink); }
.wl-legend-swatch[data-kind="wait"] { background: var(--ev-wait); box-shadow: inset 0 0 0 1px var(--ev-wait-line); }
.wl-legend-swatch[data-kind="unknown"] { background: repeating-linear-gradient(135deg, var(--g0) 0 3px, var(--g200) 3px 4px); box-shadow: inset 0 0 0 1px var(--g300); }

/* Month grid: 2px top line, g50 weekday head (Sat blue, Sun red), 98px day cells. */
.t-attendance-board .t-weekdays { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); margin: 0; padding: 0; border-top: 2px solid var(--g800); border-bottom: 1px solid var(--g200); background: var(--g50); }
.t-attendance-board .t-weekdays span { display: grid; place-items: center; height: 32px; color: var(--g700); font-size: 12.5px; font-weight: 600; }
.t-attendance-board .t-weekdays span:nth-child(6) { color: var(--info); }
.t-attendance-board .t-weekdays span:nth-child(7) { color: var(--danger); }
.t-attendance-board .t-month-grid { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 0; border: 0; border-bottom: 1px solid var(--g200); }
.t-attendance-slot { position: relative; display: grid; min-width: 0; }
.t-attendance-board .t-day {
  display: flex; flex-direction: column; align-items: stretch; gap: 4px; min-width: 0; min-height: 98px; height: 100%; margin: 0; padding: 6px 6px 8px;
  border: 0; border-right: 1px solid var(--g150); border-bottom: 1px solid var(--g150); border-radius: 0;
  background: var(--g0); color: var(--g900); box-shadow: none; text-decoration: none;
}
.t-attendance-board .t-day.outside { background: #fcfcfd; }
.t-attendance-board :is(.t-attendance-slot:nth-child(7n) > .t-day, .t-day.outside:nth-child(7n)) { border-right: 0; }
.t-attendance-board .t-day:hover:not(.selected, .outside) { background: var(--g50); }
.t-attendance-board .t-day.selected { background: var(--brand-tint); box-shadow: inset 0 0 0 1.5px var(--brand); }
.t-attendance-board .t-day:focus-visible { outline: 2px solid var(--brand); outline-offset: -2px; }
.t-attendance-board .t-day-number { display: inline-grid; place-items: center; align-self: flex-start; width: 24px; height: 24px; border-radius: 50%; background: transparent; color: var(--g900); font: 600 12.5px/1 var(--font-ui); font-variant-numeric: tabular-nums; }
.t-attendance-board .t-day[data-weekend="sat"] .t-day-number { color: var(--info); }
.t-attendance-board .t-day[data-weekend="sun"] .t-day-number { color: var(--danger); }
.t-attendance-board .t-day.today .t-day-number { background: var(--brand); color: var(--g0); }

/* Bars: 22px, 12/600, color + icon + words per kind. Unassigned/unconfirmed are white and hatched. */
.t-attendance-board .t-day-event {
  display: flex; align-items: center; gap: 4px; min-width: 0; height: 22px; margin: 0; padding: 0 6px; overflow: hidden;
  border: 0; border-radius: var(--r-ctl); background: var(--g100); color: var(--g700); box-shadow: none;
  font: 600 12px/22px var(--font-ui); white-space: nowrap; font-variant-numeric: tabular-nums;
}
.t-attendance-board .t-day-event svg { flex: none; width: 12px; height: 12px; fill: none; stroke: currentColor; stroke-width: 2.1; stroke-linecap: round; stroke-linejoin: round; }
.t-attendance-board .t-day-event[data-kind="work"] { background: var(--ev-work); color: var(--ev-work-ink); }
.t-attendance-board .t-day-event[data-kind="off"] { background: var(--ev-off); color: var(--ev-off-ink); }
.t-attendance-board .t-day-event[data-kind="leave"] { background: var(--ev-leave); color: var(--ev-leave-ink); }
.t-attendance-board .t-day-event[data-kind="wait"] { background: var(--ev-wait); color: var(--ev-wait-ink); box-shadow: inset 0 0 0 1px var(--ev-wait-line); }
.t-attendance-board .t-day-event[data-kind="unknown"] { background: repeating-linear-gradient(135deg, var(--g0) 0 5px, var(--g100) 5px 6px); color: var(--g600); box-shadow: inset 0 0 0 1px var(--g300); }

/* ⋯ request button: visible on hover, keyboard focus and the selected day; always on touch. */
.t-attendance-slot .t-attendance-more {
  position: absolute; z-index: 2; inset: 5px 5px auto auto; display: grid; place-items: center; width: 24px; min-width: 24px; height: 24px; min-height: 24px; padding: 0;
  border: 0; border-radius: var(--r-ctl); background: var(--g0); color: var(--g600); box-shadow: none; opacity: 0; cursor: pointer;
}
.t-attendance-slot .t-attendance-more svg { width: 14px; height: 14px; }
.t-attendance-slot:is(:hover, :focus-within) .t-attendance-more, .t-attendance-slot:has(.t-day.selected) .t-attendance-more, .t-attendance-more[aria-expanded="true"] { opacity: 1; }
.t-attendance-slot .t-attendance-more:hover { background: var(--g100); color: var(--g900); }
@media (pointer: coarse) { .t-attendance-slot .t-attendance-more { opacity: 1; } }

/* Date menu (spec 7.9): 232px popover, date head with close, 34px items with icon, words and the blocked reason. */
@media (min-width: 601px) {
  .t-attendance-request-menu { position: absolute; z-index: 8; inset: 32px auto auto 0; width: 232px; max-width: calc(100vw - 32px); }
  .t-attendance-slot:is(:nth-child(7n+5), :nth-child(7n+6), :nth-child(7n)) .t-attendance-request-menu { inset: 32px 0 auto auto; }
  .t-attendance-slot:nth-last-child(-n + 14) .t-attendance-request-menu { top: auto; bottom: 32px; }
  .t-attendance-detail > header .t-attendance-request-menu { inset: calc(100% + 4px) 0 auto auto; }
}
.t-attendance-request-menu { padding: 4px; border: 1px solid var(--g200); border-radius: var(--r-pop); background: var(--g0); box-shadow: var(--shadow-pop); text-align: start; }
.t-attendance-request-menu header { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; margin: 0 0 4px; padding: 8px 8px 6px; border-bottom: 1px solid var(--g150); }
.t-attendance-request-menu header span { display: block; color: var(--g600); font-size: 12px; }
.t-attendance-request-menu time { color: var(--g900); font: 700 14px/1.4 var(--font-ui); font-variant-numeric: tabular-nums; }
.t-attendance-request-menu header .t-icon-button { width: 28px; height: 28px; border: 0; }
.t-attendance-request-option { display: flex; align-items: flex-start; gap: 8px; min-height: 34px; padding: 8px; border-radius: var(--r-ctl); color: var(--g900); font: 500 13px/1.4 var(--font-ui); text-decoration: none; }
.t-attendance-request-option svg { flex: none; width: 16px; height: 16px; margin-top: 1px; fill: none; stroke: var(--g600); stroke-width: 1.7; }
.t-attendance-request-option > span { display: grid; gap: 2px; }
.t-attendance-request-option small { color: var(--g600); font-size: 12px; }
.t-attendance-request-option[aria-disabled="true"] { color: var(--g600); }
a.t-attendance-request-option:is(:hover, :focus-visible) { background: var(--g50); }

/* Side panel: 주간 근무, 처리할 일. */
.wl-side { display: grid; gap: 12px; min-width: 0; }
.wl-side-block { padding: 16px; border: 1px solid var(--g200); border-radius: var(--r-pop); background: var(--g0); }
.wl-side-block h3 { margin: 0 0 4px; color: var(--g900); font: 700 14px/1.4 var(--font-ui); }
.wl-side-period { margin: 0 0 12px; color: var(--g600); font-size: 12px; font-variant-numeric: tabular-nums; }
.wl-side-big { margin: 0 0 10px; color: var(--g900); font: 700 24px/1.2 var(--font-ui); letter-spacing: -.02em; font-variant-numeric: tabular-nums; }
.wl-side-big small { margin-left: 4px; color: var(--g600); font-size: 13px; font-weight: 600; }
.wl-side-facts { margin: 0; }
.wl-side-facts > div { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 7px 0; border-bottom: 1px solid var(--g150); font-size: 13px; }
.wl-side-facts > div:last-child { border-bottom: 0; }
.wl-side-facts dt { display: flex; align-items: center; gap: 6px; color: var(--g700); }
.wl-side-facts dt::before { content: ""; width: 8px; height: 8px; border-radius: 2px; background: var(--g300); }
.wl-side-facts [data-kind="work"] dt::before { background: var(--ev-work-ink); }
.wl-side-facts [data-kind="leave"] dt::before { background: var(--ev-leave-ink); }
.wl-side-facts [data-kind="off"] dt::before { background: var(--ev-off-ink); }
.wl-side-facts dd { margin: 0; color: var(--g900); font-weight: 700; font-variant-numeric: tabular-nums; }
.wl-side-list { margin: 0; padding: 0; list-style: none; }
.wl-side-list li { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 0; border-bottom: 1px solid var(--g150); font-size: 13px; }
.wl-side-list li:last-child { border-bottom: 0; }
.wl-side-list a { min-width: 0; color: var(--g900); text-decoration: none; font-variant-numeric: tabular-nums; }
.wl-side-list a:hover { text-decoration: underline; }
.wl-side-more { margin: 6px 0 0; color: var(--g600); font-size: 12px; }

/* Selected day detail: a flat section under the calendar. */
.t-attendance-detail { position: relative; margin: 0; padding: 20px 0 0; border: 0; border-top: 1px solid var(--g200); border-radius: 0; background: transparent; box-shadow: none; }
.t-attendance-detail > header { position: relative; display: flex; flex-wrap: wrap; align-items: flex-end; justify-content: space-between; gap: 8px; margin: 0 0 12px; padding: 0; }
.t-attendance-detail > header .t-context { margin: 0; color: var(--g600); font-size: 12px; }
.t-attendance-detail > header h2 { margin: 0; color: var(--g900); font: 700 15px/1.4 var(--font-ui); font-variant-numeric: tabular-nums; }
.t-attendance-detail .wl-info-grid { margin: 0 0 16px; }
.t-attendance-detail-body { display: flex; flex-wrap: wrap; gap: 12px 32px; margin: 0 0 12px; }
.t-attendance-detail-body > div { flex: 1 1 13rem; min-width: 0; }
.t-attendance-detail-body h3 { margin: 0 0 6px; color: var(--g900); font: 700 13px/1.4 var(--font-ui); }
.t-attendance-detail .t-listrow { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 6px 0; border-bottom: 1px solid var(--g150); font-size: 13px; }
.t-attendance-detail :is(.t-subtle, .t-muted) { margin: 0; color: var(--g600); font-size: 12.5px; }
.t-attendance-detail-actions { display: flex; flex-wrap: wrap; gap: 6px; margin: 12px 0 0; }

@media (min-width: 1200px) {
  .t-attendance-calendar { grid-template-columns: minmax(0, 1fr) 264px; align-items: start; }
  .t-attendance-calendar > .t-attendance-detail { grid-column: 1 / -1; }
}
@media (max-width: 600px) {
  .wl-summary > div { padding: 0 10px; }
  .t-attendance-board .t-calendar-legend { margin-left: 0; }
  .t-attendance-board .t-day { min-height: 64px; padding: 4px 2px 6px; }
  .t-attendance-board .t-day-event { justify-content: center; height: 8px; padding: 0; font-size: 0; }
  .t-attendance-board .t-day-event svg { display: none; }
}
```

- [ ] **Step 5: 통과 확인**

Run: `npm --prefix frontend test -- src/pages/CorePages.test.tsx src/pages/scheduleSummary.test.ts src/App.test.tsx`
Expected: PASS. 기존 캘린더 계약 테스트도 그대로 통과한다.

Run: `npm --prefix frontend test && npm --prefix frontend run build`
Expected: 전체 통과, 빌드 성공(`tsc`가 `WorkspaceDay`를 `SummaryDay`로 받는지 확인)

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/pages/CorePages.tsx frontend/src/pages/CorePages.test.tsx frontend/src/design/workspace.css
git commit -m "feat: give the schedule calendar a summary line, kind bars and a weekly side panel" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 내 정보

**Files:**
- Modify: `frontend/src/pages/PersonalDetails.tsx:1-40, 60-62, 80-95`
- Modify: `frontend/src/pages/PersonalDetails.test.tsx:66-90`
- Modify: `frontend/src/pages/IdentityPages.tsx:194-204`
- Modify: `frontend/src/design/workspace.css` (끝에 추가)

- [ ] **Step 1: 세로 탭 테스트를 가로 밑줄 탭 테스트로 바꾸기**

`'화면이 넓어지면 기록 목차가 수직 방향을 알리고 위아래 키로 같은 탭을 탐색한다'` 테스트 전체를 아래로 바꾼다.

```tsx
it('기록 목차는 넓은 화면에서도 가로 밑줄 탭이며 좌우 키로 탐색한다', () => {
  vi.stubGlobal('matchMedia', () => Object.assign(new EventTarget(), { matches: true }));
  const view = render(<PersonalDetailsTabs model={model} identity={{ employeeName: '합성사원', joinedOn: '2026-01-01' }} />);
  const list = view.getByRole('tablist', { name: '인사정보 항목' });
  const tabs = within(list).getAllByRole('tab');
  expect(tabs.map(tab => tab.querySelector('svg[aria-hidden="true"][focusable="false"]') !== null)).toEqual(Array(7).fill(true));
  expect(within(list).getByRole('tab', { name: '기본사항', selected: true })).toBe(tabs[0]);
  expect(list.getAttribute('aria-orientation')).toBe('horizontal');
  fireEvent.keyDown(tabs[0], { key: 'ArrowDown' });
  expect(tabs[0].getAttribute('aria-selected')).toBe('true');
  fireEvent.keyDown(tabs[0], { key: 'ArrowRight' });
  expect(tabs[1].getAttribute('aria-selected')).toBe('true');
  expect(document.activeElement).toBe(tabs[1]);
  expect(view.getByRole('tabpanel', { name: '발령내역' }).textContent).toContain('합성팀');
});
```

이 테스트만 쓰던 `act`는 파일 첫 줄 import에서 지운다.

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/pages/PersonalDetails.test.tsx`
Expected: FAIL. 넓은 화면에서 `aria-orientation`이 `vertical`이다.

- [ ] **Step 3: 탭과 정보 목록 구현**

`PersonalDetails.tsx`에서 다음 다섯 가지를 바꾼다.
1. `wideSheet` 상수, `subscribeToSheetWidth`, `sheetIsWide` 함수를 지운다.
2. react import에서 `useSyncExternalStore`를 지운다.
3. `PersonalDetailsTabs`의 `const vertical = useSyncExternalStore(…);` 줄을 지운다.
4. `move`의 두 조건을 아래로 바꾼다.

```tsx
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') next = (index + tabs.length - 1) % tabs.length;
```

5. tablist의 `aria-orientation={vertical ? 'vertical' : 'horizontal'}`를 `aria-orientation="horizontal"`로 바꾼다.

이어서 `Info`의 `<dl className="t-person-info">`를 `<dl className="wl-info-grid">`로 바꾼다.

`IdentityPages.tsx`의 `ProfilePage`에서 아래 두 가지를 바꾼다.
- `<section className="panel t-profile-details" aria-label="본인 정보">`를 `<section className="wl-section t-profile-details" aria-label="본인 정보">`로 바꾼다.
- `<dl className="t-person-info">`를 `<dl className="wl-info-grid">`로 바꾼다.

- [ ] **Step 4: 내 정보 CSS 추가**

`design/workspace.css` 끝에 다음을 더한다.

```css
/* 내 정보 (T4): underline tabs, info grids under a 2px line, document tables. */
.t-private-personnel { display: block; margin: 0 0 24px; padding: 0; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
.t-personnel-index { display: block; margin: 0 0 16px; padding: 0; border: 0; background: transparent; }
.t-personnel-index-title { position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.t-personnel-tabs { display: flex; flex-wrap: nowrap; gap: 0; overflow-x: auto; border-bottom: 1px solid var(--g200); }
.t-personnel-tabs .t-btn {
  flex: none; display: inline-flex; align-items: center; gap: 6px; min-height: 40px; height: auto; margin: 0 0 -1px; padding: 0 14px;
  border: 0; border-bottom: 2px solid transparent; border-radius: 0; background: transparent; color: var(--g600); box-shadow: none; font: 600 13px/1.2 var(--font-ui);
}
.t-personnel-tabs .t-btn:hover:not(:disabled) { border-color: transparent; border-bottom-color: var(--g300); background: transparent; color: var(--g900); }
.t-personnel-tabs .t-btn[aria-selected="true"] { border-bottom-color: var(--brand); color: var(--g900); }
.t-personnel-tab-icon { width: 15px; height: 15px; }
.t-personnel-tabpanel { padding: 0; border: 0; background: transparent; }
.t-personnel-tabpanel:focus-visible { outline: 2px solid var(--brand); outline-offset: 4px; }
.t-personal-panel-heading { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 6px 16px; margin: 0 0 12px; padding: 0; border: 0; }
.t-personal-panel-heading h2 { margin: 0; color: var(--g900); font: 700 15px/1.4 var(--font-ui); letter-spacing: -.02em; }
.t-personal-panel-heading p { margin: 2px 0 0; color: var(--g600); font-size: 12.5px; }
.t-personal-count { color: var(--g600); font-size: 13px; }
.t-personal-groups { display: grid; gap: 20px; }
.t-personal-group { margin: 0; padding: 0; border: 0; }
.t-personal-group h3 { display: flex; align-items: center; gap: 6px; margin: 0 0 8px; color: var(--g900); font: 700 13px/1.4 var(--font-ui); }
.t-personal-group-icon { display: inline-grid; place-items: center; width: auto; height: auto; padding: 0; border: 0; border-radius: 0; background: transparent; color: var(--g600); }
.t-personal-group-icon svg { width: 15px; height: 15px; }
.t-personal-group .wl-info-grid, .t-profile-details .wl-info-grid { padding: 12px 0 0; border-top: 2px solid var(--g800); }
.wl-info-grid dd[data-empty] { color: var(--g600); font-weight: 500; }
.t-personal-privacy { display: flex; align-items: center; gap: 8px; margin: 16px 0 0; padding: 10px 12px; border: 0; border-radius: var(--r-pop); background: var(--g50); color: var(--g700); font-size: 12.5px; }
.t-personal-privacy svg { flex: none; width: 16px; height: 16px; color: var(--g500); }
.t-personal-empty { padding: 24px 12px; border: 0; background: transparent; color: var(--g600); font-size: 13px; text-align: center; }
.t-personal-empty > span { display: none; }
.t-profile-details { margin: 0; padding: 0; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
.t-profile-asof { margin: 0 0 10px; color: var(--g600); font-size: 12.5px; }
.t-profile-footnote { margin: 12px 0 0; color: var(--g600); font-size: 12.5px; }

/* Raw document tables in T4 screens; phones keep the legacy card layout. */
@media (min-width: 601px) {
  :is(.t-personal-table, .t-request-board, .t-annual-summary-scroll, .t-personnel-history .table-scroll) table { width: 100%; border-collapse: collapse; border-top: 2px solid var(--g800); font-size: 13px; font-variant-numeric: tabular-nums; }
  :is(.t-personal-table, .t-request-board, .t-annual-summary-scroll, .t-personnel-history .table-scroll) th { height: 36px; padding: 0 12px; border-bottom: 1px solid var(--g200); background: var(--g50); color: var(--g700); font-weight: 600; text-align: left; white-space: nowrap; }
  :is(.t-personal-table, .t-request-board, .t-annual-summary-scroll, .t-personnel-history .table-scroll) td { padding: 9px 12px; border-bottom: 1px solid var(--g150); color: var(--g900); vertical-align: top; }
}
```

- [ ] **Step 5: 통과 확인**

Run: `npm --prefix frontend test -- src/pages/PersonalDetails.test.tsx src/pages/IdentityPages.test.tsx src/pages/PersonnelPage.test.tsx`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/pages/PersonalDetails.tsx frontend/src/pages/PersonalDetails.test.tsx frontend/src/pages/IdentityPages.tsx frontend/src/design/workspace.css
git commit -m "feat: show my profile with underline tabs, info grids and a restyled detail card" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 내 근태현황 본문

**Files:**
- Modify: `frontend/src/pages/AnnualRequestPage.tsx` (`Annual` 함수)
- Modify: `frontend/src/design/workspace.css` (끝에 추가)

- [ ] **Step 1: 기준 테스트 실행**

Run: `npm --prefix frontend test -- src/pages/AnnualRequestPage.test.tsx src/App.requestModal.test.tsx src/pages/AnnualRequestPage.responsive.test.tsx`
Expected: PASS

- [ ] **Step 2: 클래스 바꾸기**

`Annual`에서 아래를 바꾼다. 문구·순서·이름은 그대로다.
1. 조회 폼 `className="t-card t-annual-filters"`를 `className="wl-querybar t-annual-filters"`로 바꾼다.
   - `<div className="t-request-filter-actions"><button className="t-btn">조회</button></div>`를 `<div className="wl-querybar-actions"><button className="t-btn wl-query-submit">조회</button></div>`로 바꾼다.
2. 네 구역의 `section className="t-card"`를 `className="wl-section"`으로 바꾼다. 대상은 `aria-labelledby="annual-request-history"`가 붙은 구역, "내가 대리 작성한 이력", "기초 묶음·귀속 정보", "유형별 연간 신청"이다.
   - 지표 카드(`t-annual-metrics` 안의 `t-card`)는 그대로 둔다.

- [ ] **Step 3: 내 근태현황 CSS 추가**

`design/workspace.css` 끝에 다음을 더한다.

```css
/* 내 근태현황 (T4): one metrics strip, a summary line for the balance basis, flat sections. */
.t-annual-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 0; margin: 0 0 12px; overflow: hidden; border: 1px solid var(--g200); border-radius: var(--r-pop); }
.t-annual-metrics > .t-card { margin: 0; padding: 14px 16px; border: 0; border-left: 1px solid var(--g200); border-radius: 0; background: var(--g0); box-shadow: none; }
.t-annual-metrics > .t-card:first-child { border-left: 0; }
.t-annual-metrics > .t-request-available { background: var(--brand-tint); }
.t-annual-metrics h2 { margin: 0; color: var(--g600); font: 600 12.5px/1.4 var(--font-ui); }
.t-annual-metrics strong { display: block; margin: 4px 0 2px; color: var(--g900); font: 700 22px/1.25 var(--font-ui); letter-spacing: -.02em; font-variant-numeric: tabular-nums; }
.t-annual-metrics p { margin: 0; color: var(--g600); font-size: 12px; }
.t-request-balance-basis { display: flex; flex-wrap: wrap; gap: 6px 0; margin: 0 0 20px; padding: 10px 6px; border: 0; border-radius: var(--r-pop); background: var(--g50); font-size: 13px; }
.t-request-balance-basis > div { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 8px; padding: 0 14px; border: 0; border-left: 1px solid var(--g200); background: transparent; }
.t-request-balance-basis > div:first-child { border-left: 0; }
.t-request-balance-basis dt { color: var(--g700); }
.t-request-balance-basis dd { margin: 0; color: var(--g900); font-weight: 700; font-variant-numeric: tabular-nums; }
.t-request-balance-basis dd small { margin-left: 6px; color: var(--g600); font-size: 12px; font-weight: 400; }
.t-annual-page .wl-section h2 { margin: 0; color: var(--g900); font: 700 15px/1.4 var(--font-ui); letter-spacing: -.02em; }
.t-annual-page .wl-section > .t-muted, .t-request-list-head p { margin: 2px 0 10px; color: var(--g600); font-size: 12.5px; }
.t-request-list-head { display: flex; flex-wrap: wrap; align-items: flex-end; justify-content: space-between; gap: 8px 16px; margin: 0 0 10px; }
.t-request-list-search { display: grid; gap: 4px; margin: 0; color: var(--g700); font: 600 12.5px/1.4 var(--font-ui); }
.t-request-list-search .t-input { width: 240px; }
.t-request-list-search span { color: var(--g600); font-size: 12px; font-weight: 400; }
.t-annual-page .pagination { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 8px; margin: 10px 0 0; color: var(--g600); font-size: 12.5px; }
.t-request-empty { padding: 24px 12px; color: var(--g600); font-size: 13px; text-align: center; }
.t-request-empty > span { display: none; }
.t-request-empty :is(p, small) { margin: 0; }
@media (max-width: 900px) {
  .t-annual-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .t-annual-metrics > .t-card:nth-child(3) { border-left: 0; }
  .t-annual-metrics > .t-card:nth-child(n + 3) { border-top: 1px solid var(--g200); }
}
@media (max-width: 600px) {
  .t-request-list-search, .t-request-list-search .t-input { width: 100%; }
}
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix frontend test -- src/pages/AnnualRequestPage.test.tsx src/App.requestModal.test.tsx src/pages/AnnualRequestPage.responsive.test.tsx`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/pages/AnnualRequestPage.tsx frontend/src/design/workspace.css
git commit -m "feat: flatten my request overview into a metrics strip, query bar and sections" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 휴일 캘린더 등록 화면

**Files:**
- Modify: `frontend/src/pages/AdminPages.tsx` (`CalendarPage`)

- [ ] **Step 1: 기준 테스트 실행**

Run: `npm --prefix frontend test -- src/pages/AdminPages.test.tsx`
Expected: PASS

- [ ] **Step 2: 화면 구현**

`CalendarPage`의 `return (…)`을 아래로 바꾼다. 필드 이름·라벨·안내 문장은 그대로다.

```tsx
  return (
    <div className="t-legacy-page admin-shell">
      <PageHeader title="휴일 캘린더" help="담당자가 근거를 확인한 날짜만 명시합니다. 미등록 날짜는 미확인입니다. 요일만으로 법정 휴일을 추정하지 않습니다."
        actions={<a className="t-btn" href="/admin/networks">허용 네트워크</a>} />
      <ErrorNotice message={m.error} />
      <section className="wl-section">
        <h2>불변 버전</h2>
        {m.calendars.length === 0 && <p>등록된 캘린더 버전이 없습니다.</p>}
        <ul>
          {m.calendars.map((version) => (
            <li key={version.id}>
              v{version.revision} · {fmtDate(version.validFrom)} ~ {fmtDate(version.validTo)} (종료
              제외) · {version.reason}
            </li>
          ))}
        </ul>
      </section>
      <section className="wl-section">
        <h2>검토한 기간 등록</h2>
        <p className="hint">
          아래 기간 전체를 일반일로 등록하고 예외 날짜를 지정합니다. 기존 기간과 중첩할 수 없습니다.
        </p>
        <Form action="/admin/calendar/versions" method="post" className="wl-form">
          <Hidden name="commandId" value={m.form.commandId} />
          <FormTable>
            <Field id="calendar-from" label="시작일 (포함)" name="from" type="date" value={m.form.from} required />
            <Field id="calendar-to" label="종료일 (포함)" name="to" type="date" value={m.form.to} required />
            <TextAreaField id="calendar-basis" label="기간 전체의 검토 근거" name="basis" value={m.form.basis} maxLength={500} required />
            <FormRow label="검토 확인" required>
              <Check name="reviewed" label="기간의 일반일·비근로일·휴일 근거를 모두 검토했습니다" checked={m.form.reviewed} required />
            </FormRow>
            <TextAreaField id="calendar-exceptions" label="예외 날짜 (한 줄에 날짜|종류|이름|근거)" name="exceptions" rows={5}
              value={m.form.exceptions} placeholder="2026-12-25|HOLIDAY|공휴일|확인한 공식 근거" />
            <p className="hint">
              종류: WORKDAY(일반일), NON_WORKDAY(명시 비근로일), HOLIDAY(근거 있는 휴일). 주간
              템플릿의 비근로일은 별도로 유지됩니다.
            </p>
            <Reason id="calendar-reason" form={m.form} label="등록 사유" />
          </FormTable>
          <div className="wl-form-actions">
            <button type="submit" className="t-btn primary">캘린더 버전 저장</button>
          </div>
        </Form>
      </section>
    </div>
  );
```

- [ ] **Step 3: 통과 확인**

Run: `npm --prefix frontend test -- src/pages/AdminPages.test.tsx && npm --prefix frontend run build`
Expected: PASS, 빌드 성공

- [ ] **Step 4: 커밋**

```bash
git add frontend/src/pages/AdminPages.tsx
git commit -m "feat: move the holiday calendar registration form to a document table" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 합성 화면 검증 (고친 것이 있으면 작업별 커밋)

- [ ] **Step 1: 자동 검증**

```bash
npm --prefix frontend test
npm --prefix frontend run build
```

- [ ] **Step 2: 캡처와 검사**

```bash
node "$TOOLS/capture.mjs" "$TOOLS/shots/stage4" http://127.0.0.1:18131 workspace
node "$TOOLS/capture.mjs" "$TOOLS/shots/stage4-modals" http://127.0.0.1:18131 modals
node "$TOOLS/capture.mjs" "$TOOLS/shots/stage4-sheets" http://127.0.0.1:18131 sheets
node "$TOOLS/capture.mjs" "$TOOLS/shots/stage4-stage1" http://127.0.0.1:18131 stage1
node "$TOOLS/workspace-check.mjs" "$TOOLS/shots/stage4-check"
node "$TOOLS/modal-check.mjs" "$TOOLS/shots/stage4-check"
node "$TOOLS/sheet-check.mjs" "$TOOLS/shots/stage4-check"
```

Expected: 네 캡처 모두 JS 오류 0, 390px 넘침 0이다. 세 검사는 `PASS`다.

- [ ] **Step 3: 눈으로 확인**

`$TOOLS/shots/stage4`의 1440·390px 캡처를 차콜·블루로 확인한다.
- **근태 캘린더:**
  - 맥락 줄이 한 줄이고, 화면 머리에 [월 조회]·[근태 신청]이 있다.
  - 요약 줄 네 값, 2px 윗선 달력, 토(파랑)·일(빨강), 오늘 원, 선택한 날 brand-tint
  - 종류별 막대 색·아이콘, 오른쪽 패널(1440), 선택한 날짜 상세
- **월 시작 주:** 주간 근무에 "이번 달 날짜만"이 붙는다.
- **내 정보:** 상세 카드 한 장, 밑줄 탭, 정보 격자 2px 윗선
- **내 근태현황:** 지표 띠, 잔액 요약 줄, 조회 조건 바, 평평한 구역과 표
- **인사정보 등록(직접 연 화면):** 상세 카드와 3단계 문서형 표
- **휴일 캘린더, 조직 목록:** 이름·회사만 있는 맥락 줄
- **390px:** 맥락 줄이 줄바꿈되고, 달력 막대는 색만 보이며, 지표는 2열이다.

고칠 것이 있으면 작업 단위로 고치고 `fix:` 커밋한다.

- [ ] **Step 4: 비교 이미지**

`pair.mjs`로 `$TOOLS/shots/stage4-before`와 `$TOOLS/shots/stage4`의 아래 화면을 나란히 붙여 보낸다.
- `schedule-charcoal-1440`
- `profile-charcoal-1440`
- `requests-blue-1440`
- `schedule-charcoal-390`

---

### Task 8: 문서와 결과 기록

- [ ] **Step 1: `design.md`에 내 업무 절 추가**

`## 모달·문서형 화면` 절 다음에 넣는다.

```markdown
## 내 업무 화면

- **사원 맥락 줄(`EmployeeHeader variant="line"`):** 아바타 34px, 이름, 재직 상태 칩 | 사번·소속·직급·직책·직무·입사일. 내 정보와 인사정보 등록만 상세 카드를 쓴다.
- **요약 줄(`.wl-summary`):** g50 바탕에 항목을 세로선으로 나눈다. 값은 700·`tabular-nums`이고, 경고성 값만 warn·danger 색이다.
- **근태 캘린더:**
  - 2px 윗선, 요일 머리(토 파랑·일 빨강), 98px 날짜 칸, 오늘 brand 원, 선택한 날 brand-tint와 1.5px 안쪽 선
  - 막대: 22px, 종류별 색·아이콘·문구. 미배정·미확인은 빗금이다.
  - ⋯ 버튼: 마우스, 키보드 초점, 선택한 날짜에서만 보이고, 터치 기기에서는 항상 보인다.
- **계산(`pages/scheduleSummary.ts`):** 하루를 승인 연차 → 미배정·미확인 → 비근로일·휴일 → 예정 근무 순으로 하나로 분류한다. 예정 시간은 휴게를 뺀다. 미배정·미확인은 0시간으로 세지 않는다.
- **오른쪽 패널(`.wl-side`):** 주간 근무(선택한 날이 속한 월~일, 이번 달 날짜만)와 처리할 일(검토 중 신청, 미배정·미확인 날짜)을 둔다. 1200px 이상에서는 캘린더 오른쪽 264px, 그보다 좁으면 아래다.
- **날짜 메뉴:** 232px 팝오버, 34px 항목, 막힌 이유를 작은 글자로 둔다.
- **내 정보:** 가로 밑줄 탭, 정보 격자(2px 윗선), 문서형 표.
```

- [ ] **Step 2: 설계 문서 보정**

- 7.10절 첫 항목 끝에 "내 정보와 인사정보 등록 외의 업무 화면은 모두 맥락 줄을 쓰고, 이름·회사만 있으면 그 둘만 둔다."를 더한다.
- 7.11절 요약 줄 항목에 "항목은 예정 근무(일·시간)·승인 연차·검토 중·확인 필요다. 잔여 연차는 달력 자료에 없어 두지 않는다."를 더한다.
- 7.11절 오른쪽 패널 항목에 "앞뒤 달에 걸친 주는 이번 달 날짜만 센다. 처리할 일은 최대 6건이다."를 더한다.
- 8절 T4 행 아래에 "`today`는 서버가 내보내지 않아 옮기지 않는다. `clock/calendar`는 버전 목록·등록 폼이라 문서형 폼으로 옮긴다."를 더한다.

- [ ] **Step 3: 결과 문서에 4단계 절 추가**

`docs/quality/worklog-design-system-20261110.md` 끝에 `## 4단계 내 업무 화면 (날짜)` 절을 두고 아래를 측정한 값 그대로 적는다.
- 테스트·빌드 결과
- 캡처 수·JS 오류·넘침
- `workspace-check`·`modal-check`·`sheet-check` 결과
- 커밋 목록
- 계획과 달라진 점, 설계 보정 1–11번 요약
- 다음 단계로 넘긴 것
- 실행하지 않은 검증

- [ ] **Step 4: 계획 끝에 실행 기록 추가 후 커밋**

```bash
git add design.md docs/superpowers/specs/2026-11-10-worklog-design-system-design.md docs/quality/worklog-design-system-20261110.md docs/superpowers/plans/2026-11-11-worklog-design-stage4-workspace.md
git commit -m "docs: record the WorkLog workspace stage" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 5: Push 여부 확인**

사용자에게 4단계 커밋을 Push할지 묻는다. 공개 데모 배포는 하지 않는다.
