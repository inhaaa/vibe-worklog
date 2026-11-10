# WorkLog 디자인 시스템 2단계(공통 시트) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 시트(`WorklogSheet`)를 쓰는 25곳에 새 공통 시트 모양을 적용한다. 일괄 저장 시트 세 곳의 변경 사유 입력은 저장 확인 창으로 옮긴다. `WorklogSheet`를 쓰는 7개 파일의 T1 목록 화면은 "화면 머리 → 조회 조건 바 → 구역 머리와 시트 → 하단(건수·페이지)" 틀로 바꾼다.

**Architecture:**

- `WorklogSheet`가 구역 머리(`SectionHeader`), 오류 줄, 하단(건수·페이지 이동)을 직접 그린다. 새 props는 `title`·`count`·`actions`·`pagination`이다.
- `components/worklog-sheet.css`를 legacy 층에서 꺼내 층 없이 다시 쓴다. AG Grid 기본 CSS는 계속 vendor 층에 둔다.
- 저장 확인 창(`SheetSaveDialog`)의 사유 칸과 저장 버튼은 HTML `form` 속성으로 기존 시트 form에 붙는다.
  - 시트 form의 제출 처리는 확인 창이 닫혀 있으면 제출을 막고 창을 연다.
  - 창의 [N건 저장]은 같은 form을 다시 제출한다. 그래서 제출 필드, 검증, `commandId`, 50행 제한, 충돌 차단, 미저장 보호는 지금 경로를 그대로 탄다.
- 각 화면은 GET form에 `wl-querybar`를 붙인다. 바깥 제목·편집 도구·페이지 이동은 시트 props로 옮긴다.

**Tech Stack:** React 19.3.1, TypeScript 6.0.3, Vite 8.3.5, Vitest 5.0.5 + Testing Library(jsdom 26.1.0), AG Grid Community 36.2.0(legacy CSS 테마). 검증 전용 도구는 Python 3 `http.server`와 `puppeteer-core`(로컬 Chrome)이며 git 밖에 둔다.

**Spec:** [설계](../specs/2026-11-10-worklog-design-system-design.md) 9절 2단계, 7.3~7.6절. 1단계 결과는 [검증 결과](../../quality/worklog-design-system-20261110.md)에 있다.

---

## 작업 위치와 공통 규칙

- **작업트리:** `C:\Users\ihpark\dev\vibe-attendance\target\worklog-design-20261110`, 브랜치 `claude/worklog-design-system-20261110`. 1단계 커밋은 2026-11-10 사용자 요청으로 `origin`에 Push했다.
- **명령 실행:** 모든 명령은 Git Bash에서 실행한다. 아래 두 줄을 먼저 실행한다.
  ```bash
  cd /c/Users/ihpark/dev/vibe-attendance/target/worklog-design-20261110
  TOOLS=/c/Users/ihpark/dev/vibe-attendance/target/hallmark-tools/worklog-design-20261110
  ```
- **검증 도구:** `$TOOLS`는 git 밖이며 커밋하지 않는다. 1단계에서 만든 `preview.py`(127.0.0.1:18131), `capture.mjs`, `compare.mjs`를 이어서 쓴다.
- **테스트:** 전체는 `npm --prefix frontend test`, 파일 하나는 `npm --prefix frontend test -- <frontend 기준 경로>`이다.
- **빌드:** `npm --prefix frontend run build`(tsc 검사 후 vite 빌드).
- **커밋:** 작업마다 커밋하고, 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`를 붙인다.
- **Push·배포:** 2단계를 마친 뒤 Push 여부를 사용자에게 묻는다. 공개 데모 배포는 하지 않는다.
- **Java:** 이 단계는 Java 코드를 바꾸지 않는다. 서버의 사유 오류 계약(`BulkException(..., "", "reason")`, 422)은 그대로 쓴다.
- **jsdom 제약:** jsdom 26에는 `HTMLDialogElement.showModal`·`close`가 없다. 확인 창을 여는 테스트 파일은 기존 `App.test.tsx`처럼 `beforeEach`에서 두 메서드를 정의한다. jsdom에서 다음은 확인했다.
  - `form` 속성으로 붙은 바깥 textarea·버튼이 form에 연결된다.
  - 비어 있는 `required` 사유는 제출을 막는다.
  - `SubmitEvent.submitter`가 전달되고, `FormData`에 사유가 담긴다.

## 설계 보정 (설계 문서와 다르게 구현하는 결정)

설계 문서의 의도는 유지하고, 구현 중 드러난 충돌만 아래처럼 정한다. 11번 작업에서 설계 문서에 반영한다.

1. **시트 제목은 선택이다.** `title`을 주지 않으면 제목 없이 "총 N건"만 표시한다.
   - 설계는 기본 제목을 `label`로 정했다. 그러면 바깥 제목을 가진 T3·T4 화면의 이력 시트(소속 이력, 조직장 이력 등)에서 제목이 두 번 보인다.
   - 제목을 주지 않는 시트는 11곳이다. 도달하지 않는 2곳과 신청 목록 fragment를 포함한다. 이 화면들은 3~5단계에서 구조를 옮기므로 이번에는 손대지 않는다.
2. **정렬:** 다음 순서로 정한다. 머리 칸은 첫 행 값과 같은 정렬을 쓴다.
   - 숫자: 오른쪽
   - `w-sheet-center` 열, 날짜 편집 칸, 날짜·시각처럼 보이는 25자 이하 값: 가운데
   - 그 밖의 글자: 왼쪽

   지금의 "짧은 글자 가운데" 규칙은 없앤다(설계 4절).
3. **저장 흐름:** 도구의 [저장]은 지금처럼 submit 버튼이다.
   - form 제출 처리가 확인 창을 연다. Enter 같은 우회 제출도 창을 연다.
   - 서버 422의 `reason` 오류가 오면 창을 자동으로 연다. 사유 칸에 `data-error-focus="true"`를 붙여, App의 오류 초점 처리가 사유 칸으로 초점을 보내게 한다.
   - 이때 페이지 오류 안내와 시트 오류 줄에는 사유 오류를 싣지 않는다.
4. **도구 문구와 상태 열:**
   - 도구 문구는 시안대로 바꾼다. [입력]은 [행 추가], [취소]는 [변경 취소]다. 확인 창의 [취소]와 이름이 겹치지 않게 하기 위해서다.
   - 상태 열은 머리를 "상태"로 하고, 선택 칸 다음에 고정한다.
   - 결재선 배정 화면의 [입력]·[저장]·[취소]는 단건 폼이라 그대로 둔다.
5. **모달 크기:** `Modal`에 `size` 속성을 추가하되, 이번에는 `s`(440px)만 스타일을 둔다. 다른 크기, 머리·하단, 모바일 시트는 3단계에서 정한다.
6. **안내 문장:** 시트 화면의 설명 문장은 화면 머리 도움말로 옮기고 문구는 유지한다. "조회 기준 (서울): …"처럼 자료를 담은 문장은 화면에 남긴다.
7. **페이지 이동:** 각 화면의 기존 `nav`를 그대로 `pagination`으로 넘긴다. 새 페이지 부품은 만들지 않는다.
8. **빈 목록:** 시트를 항상 그리고, 빈 안내는 `emptyMessage`로 표시한다.
9. **[전체 보기]:** 조회 조건 바의 [초기화]로 이름을 바꾼다. 링크 주소는 같다.
10. **손대지 않는 화면:** `AdminPages`의 `EmployeesPage`·`OrganizationsPage`는 바꾸지 않는다. 서버(`OrgAdminController`)가 시트 모델을 항상 함께 보내, 이 두 화면에는 도달하지 않기 때문이다. 결과 문서에 기록만 한다.

## 파일 구조

| 파일 | 책임 | 작업 |
| --- | --- | --- |
| `frontend/src/components/WorklogSheet.tsx`, `WorklogSheet.test.tsx` | 정렬 규칙, 구역 머리·오류 줄·하단 구조, 행·칸 상태 | 수정 (1, 3) |
| `frontend/src/components/SectionHeader.tsx`, `.test.tsx` | 제목·총 건수·구역 동작 | 생성 (2) |
| `frontend/src/components/SheetState.tsx`, `.test.tsx` | 행 상태 판정·표시, 공통 상태 열 | 생성 (2) |
| `frontend/src/design/ui.css` | 구역 머리, 상태, 조회 조건 바, 도움말 문단, 모달 `s`, 저장 확인 창 | 수정 (2, 5) |
| `frontend/src/components/worklog-sheet.css` | 시트 모양 전체(층 없음) | 다시 씀 (4) |
| `frontend/src/cssLayers.test.js`, `frontend/src/sheetAssets.test.ts` | 층·자산 계약 | 수정 (4) |
| `frontend/src/components/Modal.tsx`, `Modal.test.tsx` | `size` 속성 | 수정 (5) |
| `frontend/src/components/SheetSaveDialog.tsx`, `.test.tsx` | 저장 확인 창, 변경 건수 | 생성 (5) |
| `frontend/src/pages/OrgSheetPages.tsx`, `.test.tsx` | 조직·사원 시트(6), 조직도 오른쪽 표(9) | 수정 |
| `frontend/src/pages/CodePages.tsx`, `.test.tsx` | 공통코드·근태코드 시트 | 수정 (7) |
| `frontend/src/pages/ApprovalRoutePages.tsx`, `.test.tsx` | 결재선 시트·버전·배정 이력 | 수정 (8) |
| `frontend/src/pages/IdentityPages.tsx` | 권한 목록, 회사 근무현황, 회사 신청 | 수정 (9) |
| `frontend/src/pages/WorkflowPages.tsx` | 신청 목록, 연차 원장, 근로시간 검토, 감사 이력, 출퇴근 기록 조회 조건 | 수정 (9) |
| `design.md`, spec, `docs/quality/worklog-design-system-20261110.md`, 이 계획 | 기준·결과 문서 | 수정 (11) |

`AdminPages.tsx`와 `CorePages.tsx`는 바꾸지 않는다. 두 파일의 시트는 바깥 제목을 그대로 두고, 시트 머리에 건수와 찾기만 표시한다.

---

### Task 0: 시트 합성 화면과 기준 캡처 (커밋 없음)

1단계의 미리보기 서버는 세션이 바뀌며 멈췄다(종료 코드 4). 시트 화면용 합성 자료를 더해 다시 띄우고, 바꾸기 전 화면을 기준으로 캡처한다.

**Files (git 밖):**
- Create: `$TOOLS/sheet_fixtures.py`
- Modify: `$TOOLS/preview.py`
- Modify: `$TOOLS/capture.mjs`
- Create: `$TOOLS/sheet-check.mjs`

- [ ] **Step 1: `sheet_fixtures.py` 작성**

```python
"""2단계 공통 시트 합성 화면. 합성 자료만 쓰며 실제 회사·사람 자료가 아니다."""


def row(entry, **changes):
    return {**entry, 'clientRowId': 'row-' + entry['id'], 'expectedVersion': entry['version'], 'operation': 'UPDATE', **changes}


def sheet(entries, prefix, rows=None, errors=()):
    return {'commandId': 'synthetic-' + prefix, 'reason': '', 'rows': rows if rows is not None else [row(e) for e in entries],
            'latestRows': entries, 'errors': list(errors)}


def fixtures(base):
    envelope, orgs = base.envelope, base.ORGS
    heads = {'o-1': {'employeeName': '박서준', 'eligible': True, 'approverEligible': True}}

    def organizations(rows, errors, error=None):
        data = {'organizations': orgs, 'options': orgs, 'q': '', 'page': 0, 'hasNext': True, 'organizationHeadsById': heads,
                'organizationSheet': sheet(orgs, 'org', rows, errors)}
        if error:
            data['error'] = error
        return envelope('org/organizations', data)

    new_row = {'clientRowId': 'row-new', 'id': None, 'expectedVersion': None, 'operation': 'CREATE',
               'code': '', 'name': '디자인시스템팀', 'parentId': 'o-1', 'active': True}
    pending = organizations([row(orgs[0], name='제품개발본부'), row(orgs[1], operation='DEACTIVATE'), row(orgs[2]), new_row],
                            [{'clientRowId': 'row-new', 'field': 'code', 'message': '조직 코드를 입력하세요.'}],
                            '입력을 확인해 주세요. 편집 내용은 보존되어 있습니다.')
    reason = organizations([row(orgs[0], name='제품개발본부'), row(orgs[1]), row(orgs[2])],
                           [{'clientRowId': '', 'field': 'reason', 'message': '변경 사유를 입력하세요.'}], '변경 사유를 입력하세요.')

    group = {'id': 'g-1', 'code': 'WORK_PLACE', 'name': '근무 장소', 'description': '출근 위치 분류', 'sortOrder': 1,
             'active': True, 'systemOwned': False, 'version': 2}
    groups = [group, {**group, 'id': 'g-2', 'code': 'LEAVE_REASON', 'name': '휴가 사유', 'description': '휴가 신청 분류', 'sortOrder': 2},
              {**group, 'id': 'g-3', 'code': 'SYSTEM_STATE', 'name': '시스템 상태', 'description': '시스템 보호 분류', 'sortOrder': 3, 'systemOwned': True}]
    code = {**group, 'id': 'c-1', 'groupId': 'g-1', 'code': 'OFFICE', 'name': '사무실', 'description': '본사 근무', 'sortOrder': 1, 'version': 3}
    codes = [code, {**code, 'id': 'c-2', 'code': 'REMOTE', 'name': '재택', 'description': '재택 근무', 'sortOrder': 2},
             {**code, 'id': 'c-3', 'code': 'FIELD', 'name': '외근', 'description': '고객사 방문', 'sortOrder': 3, 'active': False}]
    common = envelope('org/common-codes', {'commonCodes': {
        'q': '', 'page': 0, 'hasNext': False, 'groups': groups, 'groupId': 'g-1', 'selectedGroup': group,
        'codeQ': '', 'codePage': 0, 'codeHasNext': False, 'codes': codes,
        'groupSheet': sheet(groups, 'group'), 'codeSheet': sheet(codes, 'code')}})

    route = {'id': 'route-1', 'code': 'PRODUCT_HEAD', 'name': '제품개발팀 조직장', 'active': True, 'version': 3, 'revision': 2,
             'approverKind': 'ORGANIZATION_HEAD', 'employeeId': None}
    routes = [route, {**route, 'id': 'route-2', 'code': 'HR_DIRECT', 'name': '인사 담당 지정', 'version': 1, 'revision': 1,
                      'approverKind': 'DIRECT_EMPLOYEE', 'employeeId': 'e-2', 'employeeName': '박서준'}]
    versions = [{'id': 'v-2', 'routeId': 'route-1', 'code': 'PRODUCT_HEAD', 'name': '제품개발팀 조직장', 'active': True, 'revision': 2,
                 'approverKind': 'ORGANIZATION_HEAD', 'employeeId': None, 'employeeName': None, 'reason': '조직장 결재로 변경', 'createdAt': base.AS_OF},
                {'id': 'v-3', 'routeId': 'route-2', 'code': 'HR_DIRECT', 'name': '인사 담당 지정', 'active': True, 'revision': 1,
                 'approverKind': 'DIRECT_EMPLOYEE', 'employeeId': 'e-2', 'employeeName': '박서준', 'reason': '최초 등록', 'createdAt': base.AS_OF}]
    approval_routes = envelope('org/approval-routes', {'approvalRoutes': {
        'q': '', 'page': 0, 'hasNext': False, 'candidates': [{'id': 'e-2', 'employeeNo': '22001', 'displayName': '박서준'}],
        'sheet': sheet(routes, 'route'), 'routes': routes, 'versions': versions}})

    request = {'id': 'cr-1', 'employeeNo': '24001', 'employeeName': '김하늘', 'organizationName': '제품개발팀', 'kind': 'LEAVE_DAY',
               'status': 'PENDING', 'fromDate': '2026-11-09', 'toDate': '2026-11-10', 'quantityDays': 2, 'effectStatus': 'NONE',
               'submittedAt': base.AS_OF}
    company_requests = envelope('company/requests', {'companyRequests': {
        'year': 2026, 'asOf': base.AS_OF, 'organizationId': None, 'status': '', 'page': 0, 'hasNext': True, 'organizations': orgs,
        'requests': [request,
                     {**request, 'id': 'cr-2', 'employeeNo': '22001', 'employeeName': '박서준', 'status': 'APPROVED', 'fromDate': '2026-10-23',
                      'toDate': '2026-10-23', 'quantityDays': 1, 'effectStatus': 'EFFECTIVE', 'submittedAt': '2026-10-20T01:00:00Z'},
                     {**request, 'id': 'cr-3', 'employeeNo': '25003', 'employeeName': '이도윤', 'organizationName': '피플팀',
                      'kind': 'ATTENDANCE_CORRECTION', 'status': 'REJECTED', 'fromDate': '2026-10-28', 'toDate': '2026-10-28',
                      'quantityDays': 0, 'submittedAt': '2026-10-29T00:30:00Z'}]}})

    return {'/admin/organizations-pending': pending, '/admin/organizations-reason': reason, '/admin/common-codes': common,
            '/admin/approval-routes': approval_routes, '/admin/access': base.ACCESS_LIST, '/company/requests': company_requests}
```

- [ ] **Step 2: `preview.py`에 시트 자료 연결**

`preview.py`의 `FIXTURES = {...}` 두 줄 바로 아래에 다음을 추가한다.

```python
sheet_spec = importlib.util.spec_from_file_location('sheet_fixtures', HERE / 'sheet_fixtures.py')
sheets = importlib.util.module_from_spec(sheet_spec)
sheet_spec.loader.exec_module(sheets)
FIXTURES.update(sheets.fixtures(base))
```

- [ ] **Step 3: `capture.mjs`에 화면 묶음 추가**

2번 줄 주석을 `// 화면 묶음(stage1·sheets) × 차콜·블루 × 1440·390px. 시계를 고정하고 축소 모션을 켜서 같은 빌드는 같은 픽셀을 내도록 한다.`로 바꾼다. 이어서 `const [out, base = ...]`와 `const routes = [...]` 다섯 줄을 아래로 바꾼다.

```js
const [out, base = 'http://127.0.0.1:18131', set = 'stage1'] = process.argv.slice(2);
const sets = {
  stage1: [
    ['home', '/me/home'], ['organizations', '/admin/organizations'], ['schedule', '/me/schedule'],
    ['requests', '/me/requests'], ['request-modal', '/me/requests/new'], ['profile', '/me/profile'],
  ],
  sheets: [
    ['organizations', '/admin/organizations'], ['organizations-pending', '/admin/organizations-pending'],
    ['organizations-reason', '/admin/organizations-reason'], ['common-codes', '/admin/common-codes'],
    ['approval-routes', '/admin/approval-routes'], ['access-list', '/admin/access'], ['company-requests', '/company/requests'],
  ],
};
const routes = sets[set];
if (!routes) throw new Error(`unknown set: ${set}`);
```

스크린샷 줄은 확인 창 화면을 화면 크기로 찍게 바꾼다.

```js
    await page.screenshot({ path: path.join(out, file), fullPage: !route.endsWith('/new') && !route.endsWith('-reason') });
```

- [ ] **Step 4: `sheet-check.mjs` 작성 (10번 작업에서 실행)**

```js
// Usage: node sheet-check.mjs <outDir> [baseUrl]
// 1440px 조직 관리에서 다음을 확인한다: 셀 수정 표시, 머리 필터 창, 저장 확인 창의 초점·가두기·Esc 복귀, 사유 422 자동 열기.
import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
import path from 'node:path';

const require = createRequire('C:/Users/ihpark/dev/vibe-attendance/target/hallmark-tools/package.json');
const puppeteer = require('puppeteer-core');
const [out, base = 'http://127.0.0.1:18131'] = process.argv.slice(2);
mkdirSync(out, { recursive: true });
const browser = await puppeteer.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
const result = {};
try {
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(String(error)));
  await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }]);
  await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 });
  await page.goto(`${base}/admin/organizations`, { waitUntil: 'networkidle0' });
  await page.evaluate(() => document.fonts.ready);
  await page.click('[aria-label="조직 이름 1"]');
  await page.waitForSelector('input[aria-label="조직 이름 1"]');
  await page.keyboard.down('Control'); await page.keyboard.press('KeyA'); await page.keyboard.up('Control');
  await page.keyboard.type('제품개발본부');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => !document.querySelector('input[aria-label="조직 이름 1"]'));
  result.changedCell = await page.$eval('[row-id="row-o-1"] [col-id="name"]', cell => cell.classList.contains('w-sheet-cell-changed'));
  result.state = await page.$eval('[row-id="row-o-1"] [col-id="state"]', cell => cell.textContent.trim());
  result.pending = await page.$eval('.w-sheet-pending', element => element.textContent.trim());
  const filterButton = await page.$('.ag-header-cell[col-id="name"] :is(.ag-header-cell-filter-button, .ag-header-cell-menu-button)');
  await filterButton?.click();
  result.filterOpened = Boolean(await page.waitForSelector('.ag-filter, .ag-menu', { timeout: 3000 }).catch(() => null));
  await page.screenshot({ path: path.join(out, 'filter-popup-charcoal-1440.png'), clip: { x: 232, y: 140, width: 1000, height: 480 } });
  await page.keyboard.press('Escape');
  const save = await page.evaluateHandle(() => [...document.querySelectorAll('.w-sheet-tools button')].find(button => button.textContent.trim() === '저장'));
  await save.click();
  await page.waitForSelector('dialog[open]');
  result.titleFocused = await page.evaluate(() => document.activeElement?.classList.contains('t-modal-title') === true);
  for (let i = 0; i < 6; i += 1) await page.keyboard.press('Tab');
  result.focusTrapped = await page.evaluate(() => Boolean(document.activeElement?.closest('dialog[open]')));
  await page.screenshot({ path: path.join(out, 'save-dialog-charcoal-1440.png') });
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => !document.querySelector('dialog[open]'));
  result.focusReturned = await page.evaluate(() => document.activeElement?.textContent?.trim() === '저장');
  await page.goto(`${base}/admin/organizations-reason`, { waitUntil: 'networkidle0' });
  await page.waitForSelector('dialog[open]');
  await page.waitForFunction(() => document.activeElement?.getAttribute('name') === 'reason', { timeout: 3000 }).catch(() => {});
  result.reasonFocused = await page.evaluate(() => document.activeElement?.getAttribute('name') === 'reason');
  result.errors = errors.length;
} finally {
  await browser.close();
}
const expected = { changedCell: true, state: '수정', pending: '저장 전 변경 1건', filterOpened: true, titleFocused: true,
  focusTrapped: true, focusReturned: true, reasonFocused: true, errors: 0 };
const failed = Object.keys(expected).filter(key => result[key] !== expected[key]);
console.log(JSON.stringify(result), failed.length ? `FAIL ${failed.join(',')}` : 'PASS');
process.exit(failed.length ? 1 : 0);
```

- [ ] **Step 5: 미리보기 서버를 다시 띄우고 자료 확인**

`python "$TOOLS/preview.py"`를 백그라운드로 실행한 뒤 아래를 실행한다.

```bash
for p in /admin/organizations /admin/organizations-pending /admin/organizations-reason /admin/common-codes /admin/approval-routes /admin/access /company/requests; do printf "%s %s\n" "$p" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Accept: application/vnd.worklog.page+json' http://127.0.0.1:18131$p)"; done
```

Expected: 일곱 줄 모두 `200`.

- [ ] **Step 6: 바꾸기 전 기준 캡처**

```bash
npm --prefix frontend run build
node "$TOOLS/capture.mjs" "$TOOLS/shots/stage2-before" http://127.0.0.1:18131 sheets
```

Expected: `28 captures, 0 with JS errors, …`가 출력된다. 이 시점의 넘침 수는 기준값으로 기록한다.

---

### Task 1: 값 종류별 정렬

**Files:**
- Modify: `frontend/src/components/WorklogSheet.tsx`
- Test: `frontend/src/components/WorklogSheet.test.tsx`

- [ ] **Step 1: 실패하는 테스트로 바꾸기**

`WorklogSheet.test.tsx`의 첫 테스트("숫자·숫자 편집값은 우측…")를 아래로 바꾼다.

```tsx
  it('글자는 왼쪽, 숫자는 오른쪽, 날짜·시각·체크는 가운데로 두고 머리도 같은 정렬을 쓴다', async () => {
    const data = [{ id: 'a', count: 0, code: '001', name: '인사 운영 팀', note: '확인이 필요합니다.', date: '2026-10-01', time: '09:00', period: '2026-11-01 ~ 2026-11-03', active: true }];
    const view = render(<WorklogSheet label="정렬 기준" rows={data} getRowId={row => row.id} columns={[
      { field: 'count', headerName: '수량' },
      { field: 'code', headerName: '식별 코드' },
      { field: 'name', headerName: '이름' },
      { field: 'note', headerName: '메모' },
      { field: 'date', headerName: '날짜' },
      { field: 'time', headerName: '시각' },
      { field: 'period', headerName: '기간' },
      { field: 'active', headerName: '활성', cellClass: 'w-sheet-center' },
      sheetEditorColumn<typeof data[number]>({ colId: 'quantity', headerName: '입력 수량' }, () => ({ label: '수량 입력', value: '12.5', type: 'number', onChange: () => {} })),
      sheetEditorColumn<typeof data[number]>({ colId: 'joined', headerName: '입사일' }, () => ({ label: '입사일 입력', value: '', type: 'date', onChange: () => {} })),
    ]} />);
    await view.findByRole('grid', { name: '정렬 기준' });
    const expected = { 'w-sheet-number': ['count', 'quantity'], 'w-sheet-center': ['date', 'time', 'period', 'active', 'joined'], 'w-sheet-left': ['code', 'name', 'note'] };
    await waitFor(() => {
      for (const [alignment, ids] of Object.entries(expected)) for (const id of ids) {
        expect(view.container.querySelector(`[row-id="a"] [col-id="${id}"]`)?.classList.contains(alignment)).toBe(true);
        expect(view.container.querySelector(`.ag-header-cell[col-id="${id}"]`)?.classList.contains(alignment)).toBe(true);
      }
    });
  });
```

두 번째 테스트("값이 바뀌면 이전 정렬을 제거하고…")를 아래로 바꾼다.

```tsx
  it('값이 바뀌면 이전 정렬을 지우고 새 값으로 다시 정렬한다', async () => {
    const props = { label: '정렬 갱신', getRowId: (row: Row) => row.id, columns: [{ field: 'name' as const, headerName: '이름' }] };
    const view = render(<WorklogSheet {...props} rows={[{ id: 'a', name: '09:00' }]} />);
    const cell = () => view.container.querySelector('[row-id="a"] [col-id="name"]');
    await waitFor(() => expect(cell()?.classList.contains('w-sheet-center')).toBe(true));
    view.rerender(<WorklogSheet {...props} rows={[{ id: 'a', name: '완료' }]} />);
    await waitFor(() => {
      expect(cell()?.classList.contains('w-sheet-left')).toBe(true);
      expect(cell()?.classList.contains('w-sheet-center')).toBe(false);
    });
  });
```

세 번째 테스트("복합 셀은 실제 표시 문구로 정렬하고…")의 마지막 세 줄을 아래로 바꾼다.

```tsx
    expect(link.closest('.w-sheet-content')?.getAttribute('title')).toBe('휴가 일정 변경 사유 상세 보기');
    expect(link.closest('.ag-cell')?.classList.contains('w-sheet-left')).toBe(true);
```

다섯 번째 테스트("별도 컴포넌트가 출력한 경고 문구도…")의 마지막 세 줄을 아래로 바꾼다.

```tsx
    const content = (await view.findAllByRole('link', { name: '상세' }))[0].closest('.w-sheet-content');
    expect(content?.getAttribute('title')).toBe('후속 승인 담당자 지정 필요 상세');
    expect(content?.closest('.ag-cell')?.classList.contains('w-sheet-left')).toBe(true);
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/components/WorklogSheet.test.tsx`
Expected: 첫 테스트 FAIL. `code`·`name`이 지금은 `w-sheet-center`이고, 머리 칸에는 정렬 클래스가 없다.

- [ ] **Step 3: 정렬 구현**

import에 `HeaderClassParams`를 더한다.

```ts
import type { CellClassParams, ColDef, GridApi, HeaderClassParams, ICellRendererParams } from 'ag-grid-community';
```

`textAlignment` 함수(4줄)를 지운다. `numericCell` 함수(7줄)는 아래로 바꾼다.

```ts
type Alignment = 'w-sheet-number' | 'w-sheet-center' | 'w-sheet-left';
/** ISO 날짜·월·기간·시각으로 시작하는 짧은 값. 날짜로 시작해도 25자를 넘는 문장은 글자로 본다. */
const DATE_LIKE = /^(\d{4}-\d{2}(-\d{2})?|\d{1,2}:\d{2})(\D|$)/;
/** 글자는 왼쪽, 숫자는 오른쪽, 날짜·시각·체크·상태는 가운데. 머리 칸도 같은 규칙을 쓴다. */
function alignment<T>(colDef: ColDef<T>, value: unknown, text: string, field?: SheetEditorField, params?: CellClassParams<T>): Alignment {
  const cellClass = typeof colDef.cellClass === 'function' ? (params ? colDef.cellClass(params) : undefined) : colDef.cellClass;
  const classes = Array.isArray(cellClass) ? cellClass : cellClass?.split(/\s+/) ?? [];
  const types = Array.isArray(colDef.type) ? colDef.type : [colDef.type];
  if (typeof value === 'number' || field?.type === 'number' || classes.includes('w-sheet-number') || types.includes('numericColumn')) return 'w-sheet-number';
  const shown = text.trim();
  if (classes.includes('w-sheet-center') || field?.type === 'date' || (shown.length <= 25 && DATE_LIKE.test(shown))) return 'w-sheet-center';
  return 'w-sheet-left';
}
function cellAlignment<T>(params: CellClassParams<T>) {
  return alignment(params.colDef, params.value, cellText(params), fieldFor(params.colDef, params.data, params.context), params);
}
function headerAlignment<T>({ colDef, column, api, context }: HeaderClassParams<T>) {
  const definition = colDef as ColDef<T>;
  const node = api.getDisplayedRowAtIndex(0);
  if (!node?.data || !column) return alignment(definition, undefined, '');
  const text = String(api.getCellValue({ rowNode: node, colKey: column, useFormatter: true }) ?? '');
  return alignment(definition, api.getCellValue({ rowNode: node, colKey: column }), text, fieldFor(definition, node.data, context));
}
```

`RenderCell`의 반환 줄에서 정렬 클래스를 뺀다. 정렬은 칸이 맡고 내용은 상속한다.

```tsx
  return <span ref={element} className="w-sheet-content" title={text || undefined}>{content}</span>;
```

`defaultColDef`의 앞 세 규칙을 바꾸고 `headerClass`를 더한다.

```tsx
        defaultColDef={{ ...defaults, headerClass: (params: HeaderClassParams<T>) => headerAlignment(params), cellClassRules: {
          'w-sheet-number': params => cellAlignment(params) === 'w-sheet-number',
          'w-sheet-left': params => cellAlignment(params) === 'w-sheet-left',
          'w-sheet-center': params => cellAlignment(params) === 'w-sheet-center',
```

(`'w-sheet-cell-error'` 규칙은 그대로 둔다.) 첫 자료가 그려진 뒤에도 머리 칸이 첫 행 기준 정렬을 다시 받도록 `onFirstDataRendered`를 바꾼다.

```tsx
        onFirstDataRendered={event => {
          event.api.refreshHeader();
          updateVisible();
          if (errors.length) focusCell(errors[0].clientRowId, errors[0].field);
        }}
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix frontend test -- src/components/WorklogSheet.test.tsx`
Expected: PASS.
Run: `npm --prefix frontend test`
Expected: 전체 PASS. 페이지 테스트는 정렬 클래스에 기대지 않는다.

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/components/WorklogSheet.tsx frontend/src/components/WorklogSheet.test.tsx
git commit -m "feat: align sheet cells and headers by value type" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 구역 머리·상태 칸·조회 조건 바

**Files:**
- Create: `frontend/src/components/SectionHeader.tsx`, `frontend/src/components/SectionHeader.test.tsx`
- Create: `frontend/src/components/SheetState.tsx`, `frontend/src/components/SheetState.test.tsx`
- Modify: `frontend/src/design/ui.css`

- [ ] **Step 1: 실패하는 테스트 작성**

`SectionHeader.test.tsx`:

```tsx
// @vitest-environment jsdom
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { SectionHeader } from './SectionHeader';

afterEach(cleanup);

describe('SectionHeader 구역 머리', () => {
  it('제목·총 건수·구역 동작을 한 줄에 둔다', () => {
    const { container } = render(<SectionHeader title="조직 목록" count={1234} actions={<button type="button">행 추가</button>} />);
    expect(screen.getByRole('heading', { level: 2, name: '조직 목록' })).toBeTruthy();
    expect(container.querySelector('.wl-count')?.textContent).toBe('총 1,234건');
    expect(screen.getByRole('button', { name: '행 추가' }).closest('.wl-section-actions')).toBeTruthy();
  });

  it('제목 없이 건수만 둘 수 있다', () => {
    const { container } = render(<SectionHeader count={0} />);
    expect(container.querySelector('h2')).toBeNull();
    expect(container.textContent).toBe('총 0건');
  });
});
```

`SheetState.test.tsx`:

```tsx
// @vitest-environment jsdom
import React from 'react';
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { SheetState, sheetStateOf } from './SheetState';

afterEach(cleanup);

describe('SheetState 행 상태', () => {
  it.each([['new', '신규'], ['modified', '수정'], ['deactivate', '비활성 예정']] as const)('%s 상태를 점과 문구로 표시한다', (state, text) => {
    const badge = render(<SheetState state={state} />).container.querySelector('.wl-state')!;
    expect(badge.getAttribute('data-state')).toBe(state);
    expect(badge.textContent).toBe(text);
  });

  it('저장된 행은 상태를 표시하지 않는다', () => {
    expect(render(<SheetState state="saved" />).container.textContent).toBe('');
  });

  it('행 작업과 변경 여부로 상태를 정한다', () => {
    expect(sheetStateOf({ operation: 'CREATE' }, true)).toBe('new');
    expect(sheetStateOf({ id: null, operation: 'UPDATE' }, false)).toBe('new');
    expect(sheetStateOf({ id: 'a', operation: 'DEACTIVATE' }, false)).toBe('deactivate');
    expect(sheetStateOf({ id: 'a', operation: 'UPDATE' }, true)).toBe('modified');
    expect(sheetStateOf({ id: 'a', operation: 'UPDATE' }, false)).toBe('saved');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/components/SectionHeader.test.tsx src/components/SheetState.test.tsx`
Expected: FAIL. 두 모듈을 찾을 수 없다.

- [ ] **Step 3: 부품 구현**

`SectionHeader.tsx`:

```tsx
import type { ReactNode } from 'react';

/** 구역 머리: 제목(15/700)·총 건수 | 구역 동작. 시트·목록·폼 구역이 함께 쓴다. */
export function SectionHeader({ title, count, actions }: { title?: ReactNode; count?: number; actions?: ReactNode }) {
  return <div className="wl-section-head">
    <div className="wl-section-title">
      {title && <h2>{title}</h2>}
      {count !== undefined && <span className="wl-count">총 <b>{count.toLocaleString('ko-KR')}</b>건</span>}
    </div>
    {actions && <div className="wl-section-actions">{actions}</div>}
  </div>;
}
```

`SheetState.tsx`:

```tsx
import type { ColDef } from 'ag-grid-community';
import { sheetColumn } from './WorklogSheet';

export type SheetStateKind = 'saved' | 'new' | 'modified' | 'deactivate';
const text: Record<SheetStateKind, string> = { saved: '', new: '신규', modified: '수정', deactivate: '비활성 예정' };

/** 저장 전 행 상태. 저장된 행은 표시하지 않는다. */
export function sheetStateOf(row: { id?: string | null; operation: string }, modified: boolean): SheetStateKind {
  if (row.operation === 'DEACTIVATE') return 'deactivate';
  if (!row.id || row.operation === 'CREATE') return 'new';
  return modified ? 'modified' : 'saved';
}

export function SheetState({ state }: { state: SheetStateKind }) {
  return state === 'saved' ? null : <span className="wl-state" data-state={state}>{text[state]}</span>;
}

/** 선택 칸 다음에 고정하는 공통 상태 열. 정렬·찾기 값도 같은 문구를 쓴다. */
export function sheetStateColumn<T>(stateOf: (row: T) => SheetStateKind): ColDef<T> {
  return sheetColumn<T>({
    colId: 'state', headerName: '상태', width: 104, minWidth: 104, maxWidth: 120, flex: 0, pinned: 'left', cellClass: 'w-sheet-center',
    valueGetter: ({ data }) => data ? text[stateOf(data)] : '',
  }, row => <SheetState state={stateOf(row)} />);
}
```

`design/ui.css`의 마지막 `@media (max-width: 600px) { .wl-pagehead h1 … }` 블록 뒤에 다음을 붙인다.

```css
.wl-help-panel p { margin: 0; }
.wl-help-panel p + p { margin-top: 6px; }

/* Section header: title (15/700) and total count, section actions on the right. */
.wl-section { min-width: 0; margin: 0 0 32px; }
.wl-section-head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; min-height: var(--h-ctl); margin: 0 0 10px; }
.wl-section-title { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
.wl-section-title h2 { margin: 0; color: var(--g900); font: 700 15px/1.4 var(--font-ui); letter-spacing: -.02em; }
.wl-count { color: var(--g600); font-size: 13px; font-weight: 500; white-space: nowrap; }
.wl-count b { color: var(--brand-ink); font-weight: 700; font-variant-numeric: tabular-nums; }
.wl-section-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-left: auto; }

/* Sheet row state: a dot and a word, never color alone. */
.wl-state { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; font-weight: 600; white-space: nowrap; }
.wl-state::before { content: ""; width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.wl-state[data-state="new"] { color: var(--ok); }
.wl-state[data-state="modified"] { color: var(--info); }
.wl-state[data-state="deactivate"] { color: var(--danger); }

/* Query bar on an existing GET form: label/control pairs in a row, [초기화][조회] at the end. */
.wl-querybar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 16px; margin: 0 0 20px; padding: 12px 16px; border: 1px solid var(--g200); border-radius: var(--r-pop); background: var(--g50); }
.wl-querybar > div:not(.wl-querybar-actions) { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; }
.wl-querybar label:not(.t-sr-only) { margin: 0; color: var(--g700); font: 600 13px/1.4 var(--font-ui); white-space: nowrap; }
.wl-querybar :is(.t-input, .t-select) { width: 180px; min-width: 0; margin: 0; }
.wl-querybar-actions { display: flex; align-items: center; gap: 6px; margin-left: auto; }
.wl-querybar .t-btn.wl-query-submit { border-color: var(--g800); background: var(--g800); color: var(--g0); }
.wl-querybar .t-btn.wl-query-submit:hover:not(:disabled) { border-color: var(--g900); background: var(--g900); }
.wl-querybar .t-btn.wl-query-reset { border-color: transparent; background: transparent; color: var(--g700); }
.wl-querybar .t-btn.wl-query-reset:hover:not(:disabled) { border-color: transparent; background: var(--g150); }

@media (max-width: 600px) {
  .wl-section-actions { width: 100%; margin-left: 0; }
  .wl-querybar, .wl-querybar > div:not(.wl-querybar-actions) { flex-direction: column; align-items: stretch; }
  .wl-querybar :is(.t-input, .t-select) { width: 100%; }
  .wl-querybar-actions { margin-left: 0; }
  .wl-querybar-actions > * { flex: 1; }
}
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix frontend test -- src/components/SectionHeader.test.tsx src/components/SheetState.test.tsx`
Expected: PASS (6개).

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/components/SectionHeader.tsx frontend/src/components/SectionHeader.test.tsx frontend/src/components/SheetState.tsx frontend/src/components/SheetState.test.tsx frontend/src/design/ui.css
git commit -m "feat: add section header, sheet state and query bar parts" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 공통 시트 구조

**Files:**
- Modify: `frontend/src/components/WorklogSheet.tsx`
- Test: `frontend/src/components/WorklogSheet.test.tsx`

- [ ] **Step 1: 실패하는 테스트 작성**

"호출부의 줄바꿈·자동 높이 요청보다…" 테스트의 높이 기대값을 `'40px'`에서 `'38px'`로 바꾼다. 그다음 `describe` 블록 끝(마지막 테스트 뒤)에 아래 테스트를 붙인다.

```tsx
  it('제목·총 건수·찾기·편집 동작을 머리에, 페이지 이동을 하단에 둔다', async () => {
    const view = render(<WorklogSheet label="사원 목록" title="사원" rows={rows} columns={columns} getRowId={row => row.id}
      actions={<button type="button">행 추가</button>} pagination={<nav aria-label="사원 페이지"><a href="?page=1">다음</a></nav>} />);
    await view.findByRole('grid', { name: '사원 목록' });
    const region = view.getByRole('region', { name: '사원 목록' });
    const head = region.querySelector<HTMLElement>('.wl-section-head')!;
    expect(within(head).getByRole('heading', { level: 2, name: '사원' })).toBeTruthy();
    expect(head.textContent).toContain('총 2건');
    expect(within(head).getByLabelText('사원 목록 현재 페이지 필터')).toBeTruthy();
    expect(within(head).getByRole('button', { name: '행 추가' })).toBeTruthy();
    const footer = region.querySelector<HTMLElement>('.w-sheet-footer')!;
    expect(within(footer).getByRole('link', { name: '다음' })).toBeTruthy();
    expect(head.compareDocumentPosition(footer) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('제목이 없으면 바깥 제목과 겹치지 않게 건수만 표시한다', async () => {
    const view = render(<WorklogSheet label="소속 이력" rows={rows} columns={columns} getRowId={row => row.id} count={7} />);
    await view.findByRole('grid', { name: '소속 이력' });
    const head = view.container.querySelector('.wl-section-head')!;
    expect(head.querySelector('h2')).toBeNull();
    expect(head.textContent).toContain('총 7건');
  });

  it('저장 전 변경 건수를 머리에 알린다', async () => {
    const view = render(<WorklogSheet label="사원 편집" rows={rows} columns={columns} getRowId={row => row.id} changedIds={new Set(['b'])} />);
    await view.findByRole('grid', { name: '사원 편집' });
    expect(view.container.querySelector('.wl-section-head')?.textContent).toContain('저장 전 변경 1건');
  });

  it('오류는 시트 아래에 행·열·메시지와 이동 동작으로 두고 행이 없는 오류는 화면에 맡긴다', async () => {
    const view = render(<WorklogSheet label="사원 편집" rows={rows} columns={columns} getRowId={row => row.id}
      errors={[{ clientRowId: 'b', field: 'name', message: '이름 확인' }, { clientRowId: '', field: 'reason', message: '사유 필요' }]} />);
    const grid = await view.findByRole('grid', { name: '사원 편집' });
    const errors = view.getByRole('navigation', { name: '사원 편집 오류 위치' });
    expect(within(errors).getByRole('button', { name: '1행 · 이름: 이름 확인 해당 칸으로 이동' })).toBeTruthy();
    expect(within(errors).queryByText(/사유 필요/)).toBeNull();
    expect(grid.compareDocumentPosition(errors) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('신규·비활성 예정 행과 수정한 칸을 표시한다', async () => {
    interface Edit { id: string; name: string; operation: 'CREATE' | 'UPDATE' | 'DEACTIVATE' }
    const data: Edit[] = [{ id: 'n', name: '새 행', operation: 'CREATE' }, { id: 'u', name: '바뀐 값', operation: 'UPDATE' }, { id: 'd', name: '끝난 행', operation: 'DEACTIVATE' }];
    const view = render(<WorklogSheet label="행 상태" rows={data} getRowId={row => row.id} columns={[
      sheetEditorColumn<Edit>({ colId: 'name', headerName: '이름' }, row => ({ label: `이름 ${row.id}`, value: row.name, changed: true, onChange: () => {} })),
    ]} />);
    await view.findByRole('grid', { name: '행 상태' });
    const row = (id: string) => view.container.querySelector(`.ag-row[row-id="${id}"]`)!;
    const cell = (id: string) => view.container.querySelector(`[row-id="${id}"] [col-id="name"]`)!;
    await waitFor(() => {
      expect(row('n').classList.contains('w-sheet-row-new')).toBe(true);
      expect(row('d').classList.contains('w-sheet-row-inactive')).toBe(true);
      expect(cell('u').classList.contains('w-sheet-cell-changed')).toBe(true);
    });
    expect(cell('n').classList.contains('w-sheet-cell-changed')).toBe(false);
    expect(cell('d').classList.contains('w-sheet-cell-changed')).toBe(false);
  });

  it('600px 미만 화면은 터치용 44px 행을 쓴다', async () => {
    resizeViewport(390);
    const view = render(<WorklogSheet label="모바일 행" rows={rows} columns={columns} getRowId={row => row.id} />);
    await view.findByRole('grid', { name: '모바일 행' });
    expect((view.container.querySelector('.ag-row') as HTMLElement).style.height).toBe('44px');
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/components/WorklogSheet.test.tsx`
Expected: 새 테스트 6개와 38px 테스트가 FAIL한다. 지금 구조에는 `.wl-section-head`·`.w-sheet-cell-changed`가 없고, 행 높이는 40px이다.

- [ ] **Step 3: 구조 구현**

import에 구역 머리를 더한다(`import { SheetCellEditor, … }` 줄 아래).

```ts
import { SectionHeader } from './SectionHeader';
```

`interface Props<T>`의 앞부분(`label`부터 `rowHeight`까지)을 아래로 바꾼다.

```ts
interface Props<T> {
  label: string;
  /** 구역 머리 제목. 바깥 제목이 있는 화면은 비워 두고 건수만 표시한다. */
  title?: ReactNode;
  /** 머리의 총 건수. 기본은 rows.length. */
  count?: number;
  /** 머리 오른쪽의 편집 동작(행 추가·저장 등). */
  actions?: ReactNode;
  /** 하단 오른쪽 페이지 이동. 화면의 기존 nav를 그대로 넘긴다. */
  pagination?: ReactNode;
  rows: T[];
  columns: ColDef<T>[];
  getRowId: (row: T) => string;
  /** 기본 38px, 600px 미만 44px. */
  rowHeight?: number;
```

함수 머리를 바꾼다.

```ts
export function WorklogSheet<T>({ label, title, count, actions, pagination, rows, columns, getRowId, rowHeight,
```

`const filterId = useId();` 다음 줄에 `const noteId = useId();`를 더한다. `viewportColumns` 정의를 아래로 바꾼다.

```ts
  const height = rowHeight ?? (narrowViewport ? 44 : 38);
  const viewportColumns = columns.map(column => ({ ...column, wrapText: false, autoHeight: false,
    ...(narrowViewport ? { pinned: null, wrapHeaderText: true, autoHeaderHeight: true } : {}),
  }));
```

`const context = useMemo<SheetContext<T>>(…);` 줄 다음에 행이 있는 오류만 고르는 줄을 더한다.

```ts
  const rowErrors = allErrors.filter(error => context.index.has(error.clientRowId));
```

`<div className="w-sheet-toolbar">`부터 오류 `</nav>}`까지를 아래 머리로 바꾼다.

```tsx
    <SectionHeader title={title} count={count ?? rows.length} actions={<>
      <label className="t-sr-only" htmlFor={filterId}>{label} 현재 페이지 필터</label>
      <input id={filterId} className="t-input w-sheet-find" type="search" value={query} maxLength={120} aria-describedby={noteId}
        placeholder={scope === 'page' ? '현재 페이지에서 찾기' : '목록에서 찾기'} onChange={event => setQuery(event.target.value)} />
      <span id={noteId} className="t-sr-only">{scope === 'page' ? '정렬·필터는 현재 페이지에 적용됩니다.' : '정렬·필터는 불러온 전체 목록에 적용됩니다.'}</span>
      {changedIds && changedIds.size > 0 && <span className="w-sheet-pending">저장 전 변경 {changedIds.size}건</span>}
      {actions}
    </>} />
```

그리드 높이 줄을 바꾼다. 가로 스크롤 막대 자리는 좁은 화면에서만 둔다.

```tsx
    <div className="ag-theme-quartz w-sheet-grid" style={{ height: Math.min(480, Math.max(150, rows.length * height + (narrowViewport ? 56 : 40))) }}>
```

`cellClassRules`의 `'w-sheet-center'` 줄 다음에 수정한 칸 규칙을 더한다.

```tsx
          'w-sheet-cell-changed': params => {
            const operation = (params.data as { operation?: string } | undefined)?.operation;
            return operation !== 'CREATE' && operation !== 'DEACTIVATE' && Boolean(fieldFor(params.colDef, params.data, params.context)?.changed);
          },
```

`rowHeight={rowHeight} headerHeight={40}`를 `rowHeight={height} headerHeight={36}`로 바꾼다. `onFirstDataRendered`의 오류 줄은 행이 있는 첫 오류로 바꾼다.

```tsx
          const first = errors.find(error => context.index.has(error.clientRowId));
          if (first) focusCell(first.clientRowId, first.field);
```

`<div className="w-sheet-footer" role="status">…</div>`를 오류 줄과 새 하단으로 바꾼다. 그리드 `</div>` 바로 뒤에 둔다.

```tsx
    {rowErrors.length > 0 && <nav className="w-sheet-errors" aria-label={`${label} 오류 위치`}>
      {rowErrors.map((error, index) => <button className="w-sheet-error" type="button" key={`${error.clientRowId}-${error.field}-${index}`}
        onClick={() => focusCell(error.clientRowId, error.field)}>
        <span>{(context.index.get(error.clientRowId) ?? 0) + 1}행 · {columns.find(column => (column.colId ?? column.field) === error.field)?.headerName ?? error.field}: {error.message}</span>
        {' '}<span className="w-sheet-error-jump">해당 칸으로 이동</span>
      </button>)}
    </nav>}
    <div className="w-sheet-footer">
      <div className="w-sheet-counts" role="status">
        <span>표시 {visible.size} / {rows.length}행{selectedIds ? ` · 선택 ${selectedIds.size}행` : ''}{changedIds ? ` · 변경 ${changedIds.size}행` : ''}</span>
        {(hiddenSelected > 0 || hiddenChanged > 0) && <span>숨은 선택 {hiddenSelected}행 · 숨은 변경 {hiddenChanged}행</span>}
      </div>
      {pagination && <div className="w-sheet-pager">{pagination}</div>}
    </div>
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix frontend test -- src/components/WorklogSheet.test.tsx`
Expected: PASS.
Run: `npm --prefix frontend test`
Expected: 전체 PASS.
- 오류 버튼 이름은 "…: 메시지 해당 칸으로 이동"으로 바뀐다. 페이지 테스트는 정규식(`/조직 코드가 중복됩니다/` 등)으로 찾으므로 그대로 통과한다.
- 페이지 도구의 "변경 N행" 태그는 6~8번 작업까지 남는다.

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/components/WorklogSheet.tsx frontend/src/components/WorklogSheet.test.tsx
git commit -m "feat: give the shared sheet a section header, error line and pager slot" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 시트 CSS를 새 층으로

**Files:**
- Rewrite: `frontend/src/components/worklog-sheet.css`
- Modify: `frontend/src/cssLayers.test.js`, `frontend/src/sheetAssets.test.ts`

- [ ] **Step 1: 계약 테스트를 먼저 바꾸기**

`cssLayers.test.js`의 `legacy` 목록에서 `'src/components/worklog-sheet.css', `를 지운다(`'src/components/WorkspaceNavigation.css', ` 다음 항목). `imports AG Grid below legacy styles` 테스트 다음에 아래 테스트를 더한다.

```js
  it('keeps the shared sheet in the design layer above legacy rules', () => {
    const css = code('src/components/worklog-sheet.css');
    expect(css.startsWith('@layer vendor, legacy;')).toBe(true);
    expect(body(css)).not.toMatch(/^@layer legacy/);
    expect(css).not.toContain('#tna-app');
    expect(css).not.toContain('!important');
  });
```

`sheetAssets.test.ts`의 '공통 시트 CSS의 legacy 테마와 업무 스타일…' 테스트에서 다음 줄들을 바꾼다.

```ts
    expect(source).toContain('--ag-row-height:38px');
    expect(source.match(/url\(["']?(?:data:|https?:|\/\/)/)).toBeNull();
    expect(source.includes('--ag-background-color:var(--g0)')).toBe(true);
    expect(source.includes('--ag-header-background-color:var(--g50)')).toBe(true);
    expect(source.includes('--ag-selected-row-background-color:var(--brand-tint)')).toBe(true);
    expect(source.includes('--ag-range-selection-border-color:var(--brand)')).toBe(true);
    expect(source.includes('--ag-font-family:var(--font-ui)')).toBe(true);
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/cssLayers.test.js src/sheetAssets.test.ts`
Expected: 새 층 테스트와 변수 기대값이 FAIL한다. 지금 파일은 legacy 층이고 `#tna-app`·`!important`가 있다.

- [ ] **Step 3: `worklog-sheet.css` 다시 쓰기**

파일 전체를 아래로 바꾼다.

```css
/* WorkLog shared sheet. AG Grid base CSS stays in the vendor layer; these rules are unlayered and win over legacy
   without #tna-app. Spec: docs/superpowers/specs/2026-11-10-worklog-design-system-design.md 7.5 */
@layer vendor, legacy;
@import 'ag-grid-community/styles/ag-grid.css' layer(vendor);
@import 'ag-grid-community/styles/ag-theme-quartz.css' layer(vendor);

.w-sheet { min-width: 0; max-width: 100%; margin: 0 0 16px; }
.w-sheet-find { width: 200px; }
.w-sheet-pending { display: inline-flex; align-items: center; gap: 6px; margin-right: 4px; color: var(--warn); font-size: 12.5px; font-weight: 600; white-space: nowrap; }
.w-sheet-pending::before { content: ""; width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.w-sheet-tools { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.w-sheet-divider { width: 1px; height: 20px; margin: 0 4px; background: var(--g200); }

.w-sheet-grid.ag-theme-quartz {
  --ag-font-family: var(--font-ui);
  --ag-font-size: 13px;
  --ag-grid-size: 4px;
  --ag-row-height: 38px;
  --ag-header-height: 36px;
  --ag-cell-horizontal-padding: 12px;
  --ag-background-color: var(--g0);
  --ag-foreground-color: var(--g900);
  --ag-data-color: var(--g900);
  --ag-header-background-color: var(--g50);
  --ag-header-foreground-color: var(--g700);
  --ag-header-cell-hover-background-color: var(--g100);
  --ag-border-color: var(--g200);
  --ag-row-border-color: var(--g150);
  --ag-cell-horizontal-border: solid var(--g150);
  --ag-header-column-separator-display: block;
  --ag-header-column-separator-color: var(--g200);
  --ag-header-column-separator-height: 100%;
  --ag-header-column-resize-handle-display: none;
  --ag-odd-row-background-color: var(--g0);
  --ag-row-hover-color: #fbfbfc;
  --ag-selected-row-background-color: var(--brand-tint);
  --ag-range-selection-border-color: var(--brand);
  --ag-input-focus-border-color: var(--brand);
  --ag-input-focus-box-shadow: none;
  --ag-checkbox-checked-color: var(--brand);
  --ag-wrapper-border-radius: 0;
  --ag-icon-size: 14px;
  width: 100%; min-width: 0; overflow: hidden; line-height: normal;
}
.w-sheet-grid .ag-root-wrapper { border: 0; border-top: 2px solid var(--g800); border-bottom: 1px solid var(--g200); border-radius: 0; }

/* Header: 13/600 g700 on g50. Icons stay quiet until hover, focus, sort or filter. */
.w-sheet-grid .ag-header-cell { font-weight: 600; }
.w-sheet-grid .ag-header-cell-text { white-space: nowrap; }
.w-sheet-grid .ag-header-cell.w-sheet-center .ag-header-cell-label { justify-content: center; }
.w-sheet-grid .ag-header-cell.w-sheet-number .ag-header-cell-label { flex-direction: row-reverse; }
.w-sheet-grid .ag-header-cell :is(.ag-header-cell-filter-button, .ag-header-cell-menu-button, .ag-sort-indicator-icon) { opacity: .4; }
.w-sheet-grid .ag-header-cell:is(:hover, :focus-within, .ag-header-cell-filtered, .ag-header-cell-sorted-asc, .ag-header-cell-sorted-desc) :is(.ag-header-cell-filter-button, .ag-header-cell-menu-button, .ag-sort-indicator-icon) { opacity: 1; }

/* Cells: text left, numbers right, dates/checks/states centered (alignment class comes from WorklogSheet). */
.w-sheet-grid .ag-cell { display: flex; align-items: center; line-height: 1.45; white-space: nowrap; font-variant-numeric: tabular-nums; }
.w-sheet-grid .ag-cell-wrapper { min-width: 0; width: 100%; }
.w-sheet-grid .ag-cell-value { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.w-sheet-grid .ag-cell.w-sheet-left { justify-content: flex-start; text-align: left; }
.w-sheet-grid .ag-cell.w-sheet-number { justify-content: flex-end; text-align: right; }
.w-sheet-grid .ag-cell.w-sheet-center { justify-content: center; text-align: center; }
.w-sheet-content, .w-sheet-value { display: block; width: 100%; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.w-sheet-grid .ag-cell a { color: var(--link); text-underline-offset: 3px; }
.w-sheet-grid .w-sheet-content :is(div, p, small, strong, .t-code-help, .t-head-status) { display: inline; white-space: nowrap; margin-block: 0; }
.w-sheet-grid .w-sheet-content :is(small:not(:first-child), p + p) { margin-inline-start: 6px; }
.w-sheet-grid .w-sheet-content small { color: var(--g600); font-size: 12px; }
.w-sheet-grid .w-sheet-content .t-sheet-links { display: inline-flex; max-width: 100%; flex-wrap: nowrap; gap: 6px; vertical-align: middle; font-size: 13px; }
.w-sheet-grid .t-sheet-links > span { flex: 0 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--g700); }
.w-sheet-grid .t-sheet-links > a { flex-shrink: 0; white-space: nowrap; }
.w-sheet-grid .w-sheet-readonly { color: var(--g600); }
.w-sheet-editable { cursor: text; }

/* Row and cell state. */
.w-sheet-grid .ag-row-selected .ag-cell { background: var(--ag-selected-row-background-color); }
.w-sheet-grid .w-sheet-row-new .ag-cell.ag-column-first { box-shadow: inset 3px 0 0 var(--ok); }
.w-sheet-grid .w-sheet-row-inactive .ag-cell.ag-column-first { box-shadow: inset 3px 0 0 var(--danger); }
.w-sheet-grid .w-sheet-row-inactive .ag-cell:not([col-id="__select"], [col-id="state"]) { color: var(--g600); text-decoration: line-through; }
.w-sheet-grid .ag-cell.w-sheet-cell-changed::before { content: ""; position: absolute; top: 0; left: 0; border-top: 7px solid var(--info); border-right: 7px solid transparent; pointer-events: none; }
.w-sheet-grid .ag-cell:has(.w-sheet-editable):hover:not(.ag-cell-inline-editing, .w-sheet-cell-error) { box-shadow: inset 0 0 0 1px var(--g300); }
.w-sheet-grid .ag-cell.w-sheet-cell-error { box-shadow: inset 0 0 0 1.5px var(--danger); background: var(--danger-bg); }
.w-sheet-grid .ag-cell-focus:not(.ag-cell-range-selected) { border-color: var(--brand); }
.w-sheet-grid .ag-cell-inline-editing { padding: 0; border: 2px solid var(--brand); border-radius: 0; background: var(--g0); box-shadow: none; }
.w-sheet-grid .ag-cell-edit-wrapper { width: 100%; height: 100%; }

/* Controls inside cells (inline editor, page-rendered checkboxes) and AG Grid popup buttons. */
.w-sheet-grid .ag-cell :is(input:not([type=checkbox]), select, textarea) {
  width: 100%; min-width: 0; height: 100%; min-height: 0; margin: 0; padding: 0 10px;
  border: 0; border-radius: 0; background: var(--g0); color: var(--g900); box-shadow: none; outline: 0;
  font: 400 13px/1.4 var(--font-ui); text-align: inherit;
}
.w-sheet-grid input[type=checkbox] { width: 16px; min-width: 16px; height: 16px; min-height: 16px; margin: 0; padding: 0; accent-color: var(--brand); }
.w-sheet-grid button { min-height: 28px; margin: 0; padding: 4px 8px; border: 1px solid var(--g300); border-radius: var(--r-ctl); background: var(--g0); color: var(--g800); box-shadow: none; font: 600 12.5px/1.2 var(--font-ui); }
.w-sheet-grid :is(input, select, textarea, button, a):focus-visible { outline: 2px solid var(--brand); outline-offset: -2px; }

/* Error line under the grid and footer. */
.w-sheet-errors { display: grid; gap: 2px; margin-top: 10px; padding: 8px 12px; border-radius: var(--r-pop); background: var(--danger-bg); }
.w-sheet-error {
  display: flex; flex-wrap: wrap; align-items: baseline; gap: 2px 10px; width: 100%; min-height: 0; margin: 0; padding: 3px 0;
  border: 0; border-radius: 0; background: transparent; box-shadow: none; color: var(--danger);
  font: 600 13px/1.5 var(--font-ui); text-align: left; cursor: pointer;
}
.w-sheet-error-jump { text-decoration: underline; text-underline-offset: 3px; }
.w-sheet-error:focus-visible { outline: 2px solid var(--danger); outline-offset: 2px; }
.w-sheet-footer { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 16px; margin-top: 12px; color: var(--g700); font-size: 12.5px; font-variant-numeric: tabular-nums; }
.w-sheet-counts { display: flex; flex-wrap: wrap; gap: 4px 12px; }
.w-sheet-pager { margin-left: auto; }
.w-sheet-pager :is(nav, .pagination) { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; margin: 0; padding: 0; }
.w-sheet-pager a { display: inline-grid; place-items: center; min-width: 30px; min-height: 30px; padding: 0 10px; border: 1px solid var(--g300); border-radius: var(--r-ctl); background: var(--g0); color: var(--g800); font-size: 12.5px; font-weight: 600; text-decoration: none; }
.w-sheet-pager a:hover { border-color: var(--g400); background: var(--g50); }
.w-sheet-pager span { padding: 0 6px; color: var(--g700); font-weight: 600; }

@media (max-width: 600px), (pointer: coarse) {
  .w-sheet-pager a, .w-sheet-error { min-height: 44px; }
  .w-sheet-pager a { min-width: 44px; }
}
@media (max-width: 600px) {
  .w-sheet .wl-section-actions { width: 100%; }
  .w-sheet-find { flex: 1 1 100%; width: auto; }
  .w-sheet-tools { width: 100%; }
  .w-sheet-tools .t-btn { flex: 1 0 auto; }
  .w-sheet-divider { display: none; }
  .w-sheet-pager { margin-left: 0; }
}
```

- [ ] **Step 4: 통과·빌드 확인**

Run: `npm --prefix frontend test -- src/cssLayers.test.js src/sheetAssets.test.ts`
Expected: PASS.
Run: `npm --prefix frontend run build && grep -c "@layer vendor" frontend/dist/sheet.css && grep -c "w-sheet-cell-changed" frontend/dist/sheet.css`
Expected: 빌드 성공, 두 수 모두 1 이상.

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/components/worklog-sheet.css frontend/src/cssLayers.test.js frontend/src/sheetAssets.test.ts
git commit -m "style: restyle the shared sheet in the design layer" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 모달 크기와 저장 확인 창

**Files:**
- Modify: `frontend/src/components/Modal.tsx`, `frontend/src/components/Modal.test.tsx`
- Create: `frontend/src/components/SheetSaveDialog.tsx`, `frontend/src/components/SheetSaveDialog.test.tsx`
- Modify: `frontend/src/design/ui.css`

- [ ] **Step 1: 실패하는 테스트 작성**

`Modal.test.tsx`의 `describe('공통 업무 모달', …)` 블록 끝에 더한다.

```tsx
  it('크기를 지정하면 data-size로 노출하고 지정하지 않으면 기존 너비를 쓴다', () => {
    const { unmount } = render(<Modal title="저장 확인" size="s" onClose={() => {}}>본문</Modal>);
    expect(screen.getByRole('dialog', { name: '저장 확인' }).getAttribute('data-size')).toBe('s');
    unmount();
    render(<Modal title="상세" onClose={() => {}}>본문</Modal>);
    expect(screen.getByRole('dialog', { name: '상세' }).hasAttribute('data-size')).toBe(false);
  });
```

`SheetSaveDialog.test.tsx`:

```tsx
// @vitest-environment jsdom
import React, { useState } from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { SheetSaveDialog, sheetChangeCounts } from './SheetSaveDialog';

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value(this: HTMLDialogElement) { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value(this: HTMLDialogElement) { this.open = false; } });
});
afterEach(cleanup);

function Harness({ onSubmit, error, busy = false }: { onSubmit: (data: FormData) => void; error?: string; busy?: boolean }) {
  const [reason, setReason] = useState('');
  const [open, setOpen] = useState(true);
  return <>
    <form id="sheet-form" onSubmit={event => { event.preventDefault(); onSubmit(new FormData(event.currentTarget)); }}>
      <input type="hidden" name="rows" value="[1,2,3]" />
    </form>
    {open && <SheetSaveDialog formId="sheet-form" label="일괄 변경 사유" reason={reason} error={error} busy={busy}
      counts={{ create: 1, update: 2, deactivate: 0 }} onReasonChange={setReason} onClose={() => setOpen(false)} />}
  </>;
}

describe('SheetSaveDialog 저장 확인 창', () => {
  it('건수를 보여 주고 사유와 저장 버튼을 시트 폼으로 제출한다', () => {
    const submitted: FormData[] = [];
    render(<Harness onSubmit={data => submitted.push(data)} />);
    const dialog = screen.getByRole('dialog', { name: '변경 내용 저장' });
    expect(within(dialog).getByText('신규 1건')).toBeTruthy();
    expect(within(dialog).getByText('수정 2건')).toBeTruthy();
    expect(within(dialog).queryByText(/비활성화/)).toBeNull();
    const save = within(dialog).getByRole('button', { name: '3건 저장' });
    fireEvent.click(save);
    expect(submitted).toHaveLength(0);
    fireEvent.change(within(dialog).getByLabelText('일괄 변경 사유'), { target: { value: '조직 개편' } });
    expect(within(dialog).getByText('5/500')).toBeTruthy();
    fireEvent.click(save);
    expect(submitted).toHaveLength(1);
    expect(submitted[0].get('reason')).toBe('조직 개편');
    expect(submitted[0].get('rows')).toBe('[1,2,3]');
  });

  it('서버 사유 오류를 사유 칸에 연결하고 오류 초점 대상으로 표시한다', () => {
    render(<Harness onSubmit={() => {}} error="변경 사유를 입력하세요." />);
    const reason = screen.getByLabelText('일괄 변경 사유');
    expect(reason.getAttribute('aria-invalid')).toBe('true');
    expect(reason.getAttribute('data-error-focus')).toBe('true');
    expect(document.getElementById(reason.getAttribute('aria-describedby')!.split(' ')[0])?.textContent).toBe('변경 사유를 입력하세요.');
  });

  it('처리 중에는 취소·저장·닫기를 막고 취소는 창을 닫는다', () => {
    const { unmount } = render(<Harness onSubmit={() => {}} busy />);
    const dialog = screen.getByRole('dialog', { name: '변경 내용 저장' });
    for (const name of ['취소', '3건 저장', '닫기']) expect((within(dialog).getByRole('button', { name }) as HTMLButtonElement).disabled).toBe(true);
    unmount();
    render(<Harness onSubmit={() => {}} />);
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '취소' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('변경 행을 신규·수정·비활성화로 센다', () => {
    expect(sheetChangeCounts([{ operation: 'CREATE' }, { id: 'a', operation: 'UPDATE' }, { id: 'b', operation: 'DEACTIVATE' }, { id: 'c', operation: 'UPDATE' }]))
      .toEqual({ create: 1, update: 2, deactivate: 1 });
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/components/Modal.test.tsx src/components/SheetSaveDialog.test.tsx`
Expected: FAIL. `size` 속성이 없고, `SheetSaveDialog` 모듈도 없다.

- [ ] **Step 3: 구현**

`Modal.tsx`의 `ModalProps`에 속성을 더하고, 함수 머리와 `<dialog>`를 바꾼다.

```ts
  footer?: ReactNode;
  /** s 440, m 720, l 920, xl 1200(px). 2단계는 s만 스타일을 둔다. 지정하지 않으면 기존 너비. */
  size?: 's' | 'm' | 'l' | 'xl';
}

export function Modal({ title, children, onClose, busy = false, className = '', footer, size }: ModalProps) {
```

```tsx
    <dialog ref={dialogRef} className={`t-modal ${className}`.trim()} data-size={size} aria-labelledby={titleId}
```

`SheetSaveDialog.tsx`:

```tsx
import { useId } from 'react';
import { Modal } from './Modal';

export interface SheetChangeCounts { create: number; update: number; deactivate: number }

/** 저장할 변경 행을 신규·수정·비활성화로 센다. */
export function sheetChangeCounts(rows: readonly { id?: string | null; operation: string }[]): SheetChangeCounts {
  const create = rows.filter(row => row.operation === 'CREATE' || !row.id).length;
  const deactivate = rows.filter(row => row.operation === 'DEACTIVATE').length;
  return { create, update: rows.length - create - deactivate, deactivate };
}

/**
 * 일괄 저장 확인 창. 사유 칸과 저장 버튼은 HTML form 속성으로 시트 폼에 붙는다.
 * 제출 필드·검증·commandId·50행 제한·충돌 차단은 시트 폼의 제출 처리를 그대로 탄다.
 */
export function SheetSaveDialog({ formId, label, reason, error, counts, busy = false, onReasonChange, onClose }: {
  formId: string; label: string; reason: string; error?: string; counts: SheetChangeCounts; busy?: boolean;
  onReasonChange: (value: string) => void; onClose: () => void;
}) {
  const id = useId();
  const total = counts.create + counts.update + counts.deactivate;
  return <Modal title="변경 내용 저장" size="s" busy={busy} onClose={onClose} footer={<>
    <button type="button" className="t-btn" disabled={busy} onClick={onClose}>취소</button>
    <button type="submit" form={formId} className="t-btn primary" disabled={busy}>{total}건 저장</button>
  </>}>
    <ul className="wl-save-counts" aria-label="저장할 변경">
      {counts.create > 0 && <li className="t-tag green">신규 {counts.create}건</li>}
      {counts.update > 0 && <li className="t-tag blue">수정 {counts.update}건</li>}
      {counts.deactivate > 0 && <li className="t-tag red">비활성화 {counts.deactivate}건</li>}
    </ul>
    <div className="wl-save-reason">
      <label className="wl-required" htmlFor={`${id}-reason`}>{label}</label>
      <textarea id={`${id}-reason`} className="t-input" form={formId} name="reason" value={reason} rows={4} maxLength={500} required
        aria-invalid={error ? true : undefined} aria-describedby={error ? `${id}-error ${id}-count` : `${id}-count`}
        data-error-focus={error ? 'true' : undefined} onChange={event => onReasonChange(event.target.value)} />
      <div className="wl-save-reason-meta">
        {error
          ? <p id={`${id}-error`} className="wl-field-error">{error}</p>
          : <p>변경한 모든 행에 적용됩니다. 500자 이내로 입력하고 불필요한 개인정보는 적지 마세요.</p>}
        <span id={`${id}-count`} className="wl-char-count">{reason.length}/500</span>
      </div>
    </div>
  </Modal>;
}
```

`design/ui.css` 끝에 더한다.

```css
/* Modal size: stage 2 styles only s (save confirmation). Other sizes, header and footer move in stage 3. */
.t-modal[data-size="s"] { width: min(440px, calc(100% - 48px)); }

/* Sheet save confirmation. */
.wl-save-counts { display: flex; flex-wrap: wrap; gap: 6px; margin: 0 0 16px; padding: 0; list-style: none; }
.wl-save-reason { display: grid; gap: 6px; }
.wl-save-reason > label { color: var(--g800); font: 600 13px/1.4 var(--font-ui); }
.wl-save-reason textarea { width: 100%; resize: vertical; }
.wl-required::after { content: ""; display: inline-block; width: 5px; height: 5px; margin-left: 4px; border-radius: 50%; background: var(--danger); vertical-align: middle; }
.wl-save-reason-meta { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; color: var(--g600); font-size: 12px; line-height: 1.5; }
.wl-save-reason-meta p { margin: 0; }
.wl-field-error { color: var(--danger); font-weight: 600; }
.wl-char-count { flex: none; font-variant-numeric: tabular-nums; }
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix frontend test -- src/components/Modal.test.tsx src/components/SheetSaveDialog.test.tsx`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/components/Modal.tsx frontend/src/components/Modal.test.tsx frontend/src/components/SheetSaveDialog.tsx frontend/src/components/SheetSaveDialog.test.tsx frontend/src/design/ui.css
git commit -m "feat: confirm sheet saves in a dialog that owns the change reason" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 조직·사원 시트

**Files:**
- Modify: `frontend/src/pages/OrgSheetPages.tsx` (`SheetPage`, `OrgSheetPages`)
- Test: `frontend/src/pages/OrgSheetPages.test.tsx`

- [ ] **Step 1: 테스트를 새 흐름으로 바꾸기**

1. import에 `within`을 더한다: `import { act, cleanup, fireEvent, render, waitFor, within } from '@testing-library/react';`
2. `cellValue` 함수 다음에 저장 도우미를 더한다.

```tsx
async function save(view: ReturnType<typeof render>, reason?: string) {
  fireEvent.click(view.getByRole('button', { name: '저장' }));
  const dialog = await view.findByRole('dialog', { name: '변경 내용 저장' });
  if (reason !== undefined) fireEvent.change(within(dialog).getByLabelText('일괄 변경 사유'), { target: { value: reason } });
  fireEvent.click(within(dialog).getByRole('button', { name: /건 저장$/ }));
  return dialog;
}
```

3. `beforeEach`를 바꾼다.

```tsx
beforeEach(() => {
  history.replaceState(null, '', '/admin/organizations?q=개발&page=1');
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value(this: HTMLDialogElement) { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value(this: HTMLDialogElement) { this.open = false; } });
});
```

4. 파일 전체에서 `{ name: '입력' }`을 `{ name: '행 추가' }`로, `{ name: '취소' }`를 `{ name: '변경 취소' }`로 바꾼다. 조직·사원 시트 테스트에만 이 두 버튼이 있다.
5. '도구 모음과 고정 코드·사번…' 테스트의 이름 목록을 `['조회', '행 추가', '비활성화', '저장', '변경 취소']`로 바꾼다.
6. '변경 행만 보내고…' 테스트의 `expect(view.getByText('변경 1행')).toBeTruthy();`를 `expect(view.getByText('저장 전 변경 1건')).toBeTruthy();`로 바꾼다.
7. 아래 다섯 테스트에서 사유 입력과 직접 제출 줄을 저장 도우미로 바꾼다.

| 테스트 | 바꿀 줄 | 새 줄 |
| --- | --- | --- |
| 클릭한 셀만 편집하고… | 사유 `fireEvent.change` + `fireEvent.submit(bulkForm(view))` | `await save(view, '이름 변경');` |
| 불명확한503 재전송은… | 사유 `fireEvent.change` + 첫 `fireEvent.submit(bulkForm(view))` | `await save(view, '업무 검토');` |
| 422는 전체 입력·삭제예정… | `fireEvent.submit(bulkForm(view));` | `await save(view, '업무 검토');` |
| 저장 중 이동은… | `fireEvent.submit(bulkForm(view));` | `await save(view, '저장 확인');` |
| POST 대기 중 시트·조회… | 첫 `fireEvent.submit(bulkForm(view));` | `await save(view);` |

이 테스트들에서 두 번째로 하는 직접 제출(`fireEvent.submit(bulkForm(view))`)은 그대로 둔다. 확인 창이 열린 채라 재전송 경로를 그대로 확인한다.

8. `describe('조직·사원 시트 편집', …)` 블록의 마지막 테스트 뒤에 다음을 더한다.

```tsx
  it('저장은 확인 창에서 건수와 사유를 확인한 뒤 시트 폼으로 보내고 창을 닫으면 편집을 유지한다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(() => new Promise(() => {}));
    vi.stubGlobal('fetch', fetcher);
    const view = await show();
    expect(view.container.querySelector('[row-id="row-org"] [col-id="state"]')?.textContent).toBe('');
    await changeCell(view, '조직 이름 1', '개발본부');
    expect(view.container.querySelector('[row-id="row-org"] [col-id="state"]')?.textContent).toBe('수정');
    fireEvent.click(view.getByRole('button', { name: '저장' }));
    const dialog = await view.findByRole('dialog', { name: '변경 내용 저장' });
    expect(within(dialog).getByText('수정 1건')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }));
    await waitFor(() => expect(view.queryByRole('dialog')).toBeNull());
    expect(cellValue(view, '조직 이름 1')).toBe('개발본부');
    await save(view, '조직 개편');
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    const body = fetcher.mock.calls[0][1]?.body as URLSearchParams;
    expect(body.get('reason')).toBe('조직 개편');
    expect(JSON.parse(body.get('rows')!)[0]).toMatchObject({ clientRowId: 'row-org', name: '개발본부' });
    expect(body.get('_csrf')).toBe('csrf');
    expect(body.get('q')).toBe('개발');
  });

  it('확인 창을 거치지 않은 제출은 보내지 않고 확인 창을 연다', async () => {
    const fetcher = vi.fn<typeof fetch>(); vi.stubGlobal('fetch', fetcher);
    const view = await show();
    await changeCell(view, '조직 이름 1', '개발본부');
    fireEvent.submit(bulkForm(view));
    expect(await view.findByRole('dialog', { name: '변경 내용 저장' })).toBeTruthy();
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('사유 422는 확인 창을 다시 열고 사유 칸에 오류와 초점을 둔다', async () => {
    const failed = page('organizations', { error: '변경 사유를 입력하세요.', organizationSheet: {
      commandId: 'posted-command', reason: '', rows: [{ ...orgRow, name: '새 이름' }], latestRows: [organization],
      errors: [{ clientRowId: '', field: 'reason', message: '변경 사유를 입력하세요.' }],
    } });
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(response(failed, 422)));
    const view = await show();
    await changeCell(view, '조직 이름 1', '새 이름');
    await save(view, ' ');
    const dialog = await waitFor(() => {
      const element = view.getByRole('dialog', { name: '변경 내용 저장' });
      expect(within(element).getByText('변경 사유를 입력하세요.')).toBeTruthy();
      return element;
    });
    const reason = within(dialog).getByLabelText('일괄 변경 사유');
    await waitFor(() => expect(document.activeElement).toBe(reason));
    expect(reason.getAttribute('aria-invalid')).toBe('true');
    expect(view.container.querySelector('.t-org-sheet .notice.error')).toBeNull();
    expect(view.queryByRole('navigation', { name: '조직 편집 시트 오류 위치' })).toBeNull();
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/pages/OrgSheetPages.test.tsx`
Expected: FAIL. 확인 창, [행 추가]·[변경 취소], 상태 열, "저장 전 변경" 문구가 아직 없다.

- [ ] **Step 3: 화면 구현**

다음 순서로 고친다.

1. **import:**
   - `import { useEffect, useMemo, useRef, useState } from 'react';`를 `import { useEffect, useId, useMemo, useRef, useState } from 'react';`로 바꾼다.
   - `import { PageHeader } from '../components/PageHeader';` 다음에 두 줄을 더한다.

```ts
import { SheetSaveDialog, sheetChangeCounts } from '../components/SheetSaveDialog';
import { sheetStateColumn, sheetStateOf } from '../components/SheetState';
```

2. **함수 머리:** `function SheetPage({ model: m, organization }: { model: SheetPageModel; organization: boolean }) {`를 `function SheetPage({ model: m, organization, busy = false }: { model: SheetPageModel; organization: boolean; busy?: boolean }) {`로 바꾼다.
3. **상태 추가:** `const [errors, setErrors] = useState(model.errors ?? []);` 다음 줄에 넣는다.

```ts
  const [confirming, setConfirming] = useState(() => (model.errors ?? []).some(error => error.field === 'reason'));
  const formId = useId();
```

4. **사유 오류:** `const unsaved = dirty.length > 0 || reason.length > 0;` 다음 줄에 `const reasonError = errors.find(error => error.field === 'reason')?.message;`를 넣는다.
5. **제출 처리:** `function submit(event: FormEvent) { … }` 네 줄을 아래로 바꾼다.

```ts
  function submit(event: FormEvent) {
    if (!sheet.current?.validate() || dirty.length < 1 || dirty.length > 50 || conflicts.length) { event.preventDefault(); return; }
    // 확인 창 밖에서 들어온 제출(저장 버튼·Enter)은 보내지 않고 확인 창을 연다. 창의 저장 버튼이 form 속성으로 이 폼을 다시 제출한다.
    if (!confirming) { event.preventDefault(); setConfirming(true); }
  }
  function changeReason(value: string) {
    setReason(value);
    setErrors(current => current.filter(error => error.field !== 'reason'));
    renew();
  }
```

6. **상태 열:**
   - `const columns = [` 바로 다음 줄에 `    sheetStateColumn<Row>(row => sheetStateOf(row, changed(row, row.id ? latest.get(row.id) : undefined, organization))),`를 넣는다.
   - 마지막 `sheetColumn<Row>({ colId: 'state', headerName: '변경 상태', … }),` 블록(4줄)을 지운다.
7. **화면 머리:** `<PageHeader title={title} help="여러 행을 편집하고 한 번에 저장하세요." actions={<a className="t-btn" href="/company/organizations">조직도</a>} />`를 아래로 바꾼다.

```tsx
    <PageHeader title={title} help={<>
      <p>여러 행을 편집하고 한 번에 저장하세요.</p>
      <p id="sheet-limit">한 번에 최대 50행을 저장합니다. 기존 행 삭제는 이력을 보존하고 비활성화합니다. 저장 전까지 서버 자료는 바뀌지 않습니다.</p>
      {!organization && <p>신규 사번을 비우면 입사연도 두 자리와 순번 세 자리로 자동 발급합니다. 발급한 사번은 변경하거나 재사용하지 않습니다.</p>}
    </>} actions={<a className="t-btn" href="/company/organizations">조직도</a>} />
```

8. **구역과 조회 조건 바:** 다음 두 줄을 바꾼다.

```tsx
    <section className="t-card">
      <form action={path} method="get" className="t-toolbar" role="search" aria-label={`${label} 검색`}>
```

```tsx
    <section className="wl-section">
      <form action={path} method="get" className="wl-querybar" role="search" aria-label={`${label} 검색`}>
```

9. **조회 버튼:** `        <button className="t-btn primary" type="submit">조회</button><a className="t-btn" href={path}>전체 보기</a>`를 `        <div className="wl-querybar-actions"><a className="t-btn wl-query-reset" href={path}>초기화</a><button className="t-btn wl-query-submit" type="submit">조회</button></div>`로 바꾼다.
10. **시트 폼:** `      <Form action={`${path}/bulk`} onSubmit={submit} className="t-sheet-form">`를 `      <Form id={formId} action={`${path}/bulk`} onSubmit={submit} className="t-sheet-form">`로 바꾼다.
11. **옛 도구 지우기:** `        <div className="t-sheet-toolbar" role="group" …>`부터 `{!organization && <p className="t-subtle">신규 사번을 비우면…</p>}`까지 9줄을 지운다. 내용은 도움말과 시트 머리로 옮겼다.
12. **오류 안내:** 줄 앞부분 `        {(m.error || errors.length > 0) && <div className="notice error"`를 `        {!reasonError && (m.error || errors.length > 0) && <div className="notice error"`로 바꾼다.
13. **시트:** `<WorklogSheet ref={sheet} …` 세 줄과 그다음 `{rows.length === 0 && <p className="t-muted">…</p>}` 줄을 아래로 바꾼다.

```tsx
        <WorklogSheet ref={sheet} label={`${label} 편집 시트`} title={`${label} 목록`} rows={rows} columns={columns} getRowId={row => row.clientRowId}
          selectedIds={selected} onSelectionChange={setSelected}
          selectionLabel={(_row, index) => `${index + 1}행 선택`} changedIds={new Set(dirty.map(row => row.clientRowId))} errors={errors}
          emptyMessage={`조회된 ${label}이 없습니다. 행 추가 버튼으로 새 행을 추가하세요.`}
          actions={<div className="w-sheet-tools" role="group" aria-label={`${label} 시트 편집`}>
            <button className="t-btn" type="button" onClick={add} disabled={dirty.length >= 50} aria-describedby="sheet-limit">행 추가</button>
            <button className="t-btn" type="button" onClick={remove} disabled={selected.size === 0} title="미저장 신규 행은 제거하고 저장된 행은 비활성 예정으로 표시합니다.">비활성화</button>
            <span className="w-sheet-divider" aria-hidden="true" />
            <button className="t-btn" type="button" onClick={cancel} disabled={!unsaved}>변경 취소</button>
            <button className="t-btn primary" type="submit" disabled={dirty.length < 1 || dirty.length > 50 || conflicts.length > 0} title={conflicts.length ? '최신값을 비교하고 확인하세요.' : dirty.length ? '변경 행을 함께 저장합니다.' : '변경한 행이 없습니다.'}>저장</button>
          </div>}
          pagination={<nav className="actions pagination" aria-label={`${label} 페이지`}><span>{m.page + 1}페이지 · 조회 {model.latestRows.length}행</span>{m.page > 0 && <a href={queryHref(path, { ...values, page: m.page - 1 })}>이전</a>}{m.hasNext && <a href={queryHref(path, { ...values, page: m.page + 1 })}>다음</a>}</nav>} />
```

14. **함수 끝:** `<div className="t-sheet-reason">…</div>` 줄부터 함수 끝까지(사유 칸, `</Form>`, 페이지 `nav`, `</section>` 두 개)를 아래로 바꾼다.

```tsx
      </Form>
    </section>
    {confirming && <SheetSaveDialog formId={formId} label="일괄 변경 사유" reason={reason} error={reasonError} counts={sheetChangeCounts(dirty)}
      busy={busy} onReasonChange={changeReason} onClose={() => setConfirming(false)} />}
  </section>;
```

15. **진입 함수:**
    - `export function OrgSheetPages({ page, data }: PageProps): ReactNode {`를 `export function OrgSheetPages({ page, data, busy }: PageProps): ReactNode {`로 바꾼다.
    - 첫 두 `return <SheetPage … />`에 `busy={busy}`를 더한다.

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix frontend test -- src/pages/OrgSheetPages.test.tsx`
Expected: PASS.
Run: `npm --prefix frontend test && npm --prefix frontend run build`
Expected: 전체 PASS, 빌드 성공.

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/pages/OrgSheetPages.tsx frontend/src/pages/OrgSheetPages.test.tsx
git commit -m "feat: move organization and employee sheets to the common sheet frame" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 공통코드·근태코드 시트

**Files:**
- Modify: `frontend/src/pages/CodePages.tsx`
- Test: `frontend/src/pages/CodePages.test.tsx`

- [ ] **Step 1: 테스트를 새 흐름으로 바꾸기**

1. `payload` 함수 다음에 저장 도우미를 더하고, `beforeEach`를 바꾼다.

```tsx
async function save(view: ReturnType<typeof render>, action: string, title: string, reason?: string) {
  fireEvent.click(within(form(view, action)).getByRole('button', { name: '저장' }));
  const dialog = await view.findByRole('dialog', { name: '변경 내용 저장' });
  if (reason !== undefined) fireEvent.change(within(dialog).getByLabelText(`${title} 일괄 변경 사유`), { target: { value: reason } });
  fireEvent.click(within(dialog).getByRole('button', { name: /건 저장$/ }));
  return dialog;
}
beforeEach(() => {
  history.replaceState(null, '', '/admin/attendance-types');
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value(this: HTMLDialogElement) { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value(this: HTMLDialogElement) { this.open = false; } });
});
```

2. 파일 전체에서 `{ name: '입력' }`을 `{ name: '행 추가' }`로, `{ name: '취소' }`를 `{ name: '변경 취소' }`로 바꾼다.
3. '그룹과 상세를 다른 명령으로…' 테스트의 이름 목록을 `['조회', '행 추가', '비활성화', '저장', '변경 취소']`로 바꾼다.
4. 아래 다섯 테스트를 고친다.

| 테스트 | 바꿀 줄 | 새 줄 |
| --- | --- | --- |
| 편집 중 저장해도… | 사유 change + submit | `await save(view, '/admin/common-code-groups/bulk', '코드그룹', '표시명 정리');` |
| 비편집 상태의 필수값 누락도… | 사유 change 줄 | 지운다. 검증이 먼저 막으므로 창이 열리지 않는다 |
| 필터로 숨은 편집 행도… | 사유 change + submit | `await save(view, '/admin/common-code-groups/bulk', '코드그룹', '표시명 정리');` |
| 한 시트 저장이 다른 시트의… | `fireEvent.submit(form(view, '/admin/common-code-groups/bulk'));` | `await save(view, '/admin/common-code-groups/bulk', '코드그룹', '그룹 정리');` |
| 503 결과 미확정 재시도는… | 사유 change, `const command = …`, 첫 submit의 세 줄 | 아래 블록 |

```tsx
    await save(view, '/admin/attendance-types/bulk', '근태코드', '검토');
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    const command = (fetcher.mock.calls[0][1]?.body as URLSearchParams).get('commandId');
```

이 테스트의 바로 다음 줄 `await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));`는 중복이므로 지운다. 나머지 줄(두 번째 `fireEvent.submit(form(view))` 포함)은 그대로 둔다. 전송 중에는 App이 폼 입력을 잠가 `FormData`에서 빠지므로, 명령 ID는 전송 본문에서 읽는다. 폼 값은 기존 줄이 잠금이 풀린 뒤에 확인한다.

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/pages/CodePages.test.tsx`
Expected: FAIL. 확인 창과 새 버튼 문구가 아직 없다.

- [ ] **Step 3: 화면 구현**

1. **import:**
   - `useEffect, useMemo, …` 줄에 `useId`를 더한다.
   - `import type { FormEvent } from 'react';`를 `import type { FormEvent, ReactNode } from 'react';`로 바꾼다.
   - `PageHeader` import 다음에 `SheetSaveDialog`·`sheetChangeCounts`와 `sheetStateColumn`·`sheetStateOf` import 두 줄을 더한다(6번 작업과 같은 경로).
2. **`CodeSheet` 머리:** 아래로 바꾼다.

```ts
function CodeSheet({ title, heading, action, model, fields, context, today, group, error, pagination, busy = false }: {
  title: string; heading?: ReactNode; action: string; model: Sheet; fields: Field[];
  context: Record<string, string | number | null>; today?: string; group?: Entry | null; error?: string | null;
  pagination?: ReactNode; busy?: boolean;
}) {
```

3. **상태:**
   - `const [errors, setErrors] = useState(model.errors ?? []);` 다음에 `const [confirming, setConfirming] = useState(() => (model.errors ?? []).some(item => item.field === 'reason'));`와 `const formId = useId();`를 넣는다.
   - `const unsaved = …;` 다음에 `const reasonError = errors.find(item => item.field === 'reason')?.message;`를 넣는다.
4. **제출 처리:** `submit`에서 `if (sheet.current && !sheet.current.validate()) { … }` 블록 다음, `const anotherDraft = …` 앞에 넣는다.

```ts
    if (!confirming) {
      event.preventDefault();
      setConfirming(true);
      return;
    }
```

`submit` 함수 다음에 `changeReason`을 둔다.

```ts
  function changeReason(value: string) {
    setReason(value);
    setErrors(current => current.filter(item => item.field !== 'reason'));
    renew();
  }
```

5. **상태 열:**
   - `const state = (row: Row) => …;` 줄을 지운다.
   - `const columns = [` 다음 줄에 `    sheetStateColumn<Row>(row => sheetStateOf(row, changed(row))),`를 넣는다.
   - 마지막 `sheetColumn<Row>({ colId: 'state', headerName: '상태', … }),` 블록(3줄)을 지운다.
6. **시트 폼:** `    <Form action={action} onSubmit={submit} className="t-sheet-form">`를 `    <Form id={formId} action={action} onSubmit={submit} className="t-sheet-form">`로 바꾼다.
7. **옛 도구 지우기:** `      <div className="t-sheet-toolbar" …>`부터 `      <p className="t-subtle">한 번에 최대 50행을 저장합니다.…</p>`까지 8줄을 지운다.
8. **오류 안내:** `      {(error || errors.length > 0) && <div className="notice error"`를 `      {!reasonError && (error || errors.length > 0) && <div className="notice error"`로 바꾼다.
9. **시트:** `<WorklogSheet ref={sheet} …` 여섯 줄을 아래로 바꾼다.

```tsx
      <WorklogSheet ref={sheet} label={`${title} 편집 시트`} title={heading ?? title} rows={rows} columns={columns} getRowId={row => row.clientRowId}
        selectedIds={selected} onSelectionChange={setSelected}
        selectionLabel={(_row, index) => `${title} ${index + 1}행 선택`}
        isRowSelectable={row => !lockedGroup && !(row.id && latest.get(row.id)?.systemOwned)}
        changedIds={new Set(dirty.map(row => row.clientRowId))} errors={errors}
        emptyMessage={`등록된 ${title}가 없습니다. 행 추가 버튼으로 추가하세요.`}
        actions={<div className="w-sheet-tools" role="group" aria-label={`${title} 시트 편집`}>
          <button type="button" className="t-btn" onClick={add} disabled={dirty.length >= 50 || lockedGroup}>행 추가</button>
          <button type="button" className="t-btn" onClick={remove} disabled={!selected.size || lockedGroup} title="미저장 신규 행은 제거하고 저장된 행은 비활성 예정으로 표시합니다.">비활성화</button>
          <span className="w-sheet-divider" aria-hidden="true" />
          <button type="button" className="t-btn" onClick={cancel} disabled={!unsaved}>변경 취소</button>
          <button type="submit" className="t-btn primary" disabled={!dirty.length || dirty.length > 50 || Boolean(conflicts.length) || lockedGroup}>저장</button>
        </div>}
        pagination={pagination} />
```

10. **함수 끝:** `<div className="t-sheet-reason">…</div>` 줄을 지운다. 그다음 `    </Form>`과 `  </div>;` 사이에 넣는다.

```tsx
    {confirming && <SheetSaveDialog formId={formId} label={`${title} 일괄 변경 사유`} reason={reason} error={reasonError}
      counts={sheetChangeCounts(dirty)} busy={busy} onReasonChange={changeReason} onClose={() => setConfirming(false)} />}
```

11. **`CommonCodes` 머리와 화면 머리:**
    - 함수 머리를 `function CommonCodes({ model: m, error, busy }: { model: CommonModel; error?: string | null; busy?: boolean }) {`로 바꾼다.
    - `<PageHeader title="공통코드 관리" …/>`를 아래로 바꾼다.

```tsx
    <PageHeader title="공통코드 관리" help={<>
      <p>회사별 표시·분류 코드를 관리하세요. 권한·신청 상태·계산 규칙은 변경되지 않습니다.</p>
      <p>한 번에 최대 50행을 저장합니다. 기존 행 삭제는 이력을 보존하는 비활성 처리입니다.</p>
    </>} actions={<a className="t-btn" href="/admin/attendance-types">근태코드 관리</a>} />
```

12. **코드그룹 구역:** `<section className="t-card" role="region" aria-label="코드그룹 관리">`부터 그 `</section>`까지를 아래로 바꾼다.

```tsx
    <section className="wl-section" role="region" aria-label="코드그룹 관리">
      <form action="/admin/common-codes" method="get" className="wl-querybar" role="search" aria-label="코드그룹 검색"><label htmlFor="code-group-q">코드그룹 검색</label><input id="code-group-q" className="t-input" name="q" type="search" maxLength={120} defaultValue={m.q} placeholder="코드 또는 이름" /><div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div></form>
      <CodeSheet title="코드그룹" action="/admin/common-code-groups/bulk" model={m.groupSheet} fields={commonFields} context={context} error={m.groupSheet.errors.length ? error : undefined} busy={busy}
        pagination={<Pagination title="코드그룹" page={m.page} hasNext={m.hasNext} values={context} />} />
    </section>
```

13. **상세코드 구역:**
    - `<section className="t-card" role="region" aria-label="상세코드 관리">`를 `<section className="wl-section" role="region" aria-label="상세코드 관리">`로 바꾼다.
    - `<h2>상세코드 {selected && …}</h2>` 줄을 지운다.
    - 상세코드 검색 form의 `className="t-toolbar"`를 `className="wl-querybar"`로 바꾼다.
    - 그 form의 `<button className="t-btn primary">조회</button>`를 `<div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div>`로 바꾼다.
    - 상세코드 `CodeSheet`와 그 아래 `Pagination` 두 줄을 아래로 바꾼다.

```tsx
      <CodeSheet title="상세코드" heading={selected ? `상세코드 · ${selected.name}` : '상세코드'} action="/admin/common-codes/bulk" model={m.codeSheet} fields={commonFields} context={context} group={selected} error={m.codeSheet.errors.length ? error : undefined} busy={busy}
        pagination={<Pagination title="상세코드" page={m.codePage} hasNext={m.codeHasNext} values={context} />} />
```

14. **`AttendanceTypes`:** 함수 머리를 `function AttendanceTypes({ model: m, error, busy }: { model: TypeModel; error?: string | null; busy?: boolean }) {`로 바꾼다. 반환 블록(`return <section className="t-code-management">`부터 함수 끝 `</section>;`까지)은 아래로 바꾼다.

```tsx
  return <section className="t-code-management">
    <PageHeader title="근태코드 관리" help={<>
      <p>신청 정책을 적용일별 버전으로 관리하세요. 기존 대기·완료 신청은 제출 당시 정책을 유지합니다.</p>
      <p>한 번에 최대 50행을 저장합니다. 기존 행 삭제는 이력을 보존하는 비활성 처리입니다. 적용일 이후의 신규 신청에만 반영됩니다.</p>
      <p>유급 구분은 정책 메타데이터입니다. 급여·법정 계산이나 근무 인정시간을 산정하지 않습니다. 같은 적용일의 수정도 새 버전이며 미래 적용일을 예약할 수 있습니다.</p>
      <p>편집 시트는 최신 등록 정책(예약 포함)입니다. 대상일의 적용 정책은 이력의 적용일·버전으로 결정됩니다. 비활성 버전을 추가해도 이후 예약 정책은 보존되어 다시 사용될 수 있습니다.</p>
    </>} actions={<a className="t-btn" href="/admin/common-codes">공통코드 관리</a>} />
    <section className="wl-section">
      <form action="/admin/attendance-types" method="get" className="wl-querybar" role="search" aria-label="근태코드 검색"><label htmlFor="attendance-type-q">근태코드 검색</label><input id="attendance-type-q" className="t-input" type="search" name="q" maxLength={120} defaultValue={m.q} placeholder="코드 또는 이름" /><div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div></form>
      <p className="notice">현재는 시스템 종일 연차와 기록 정정을 지원합니다. 신규 코드는 초안으로 등록하며 계산·차감 계약 미지원으로 신청할 수 없습니다.</p>
      <CodeSheet title="근태코드" action="/admin/attendance-types/bulk" model={m.sheet} fields={typeFields} context={{ q: m.q, page: m.page }} today={m.today} error={error} busy={busy}
        pagination={<Pagination title="근태코드" page={m.page} hasNext={m.hasNext} values={{ q: m.q }} path="/admin/attendance-types" />} />
    </section>
    <section className="wl-section t-code-versions">
      <WorklogSheet label="근태코드 버전 이력" title="근태코드 버전 이력" rows={versions} columns={versionColumns} getRowId={version => version.id}
        emptyMessage="등록된 버전이 없습니다." />
      <p className="t-subtle">조회된 코드의 불변 정책입니다. 대상일 이하 가장 늦은 적용일에서 가장 높은 버전을 사용합니다.</p>
    </section>
  </section>;
```

15. **진입 함수:** `export function CodePages({ page, data }: PageProps) {`를 `export function CodePages({ page, data, busy }: PageProps) {`로 바꾼다. 두 `return` 줄 끝의 ` />`를 ` busy={busy} />`로 바꾼다.

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix frontend test -- src/pages/CodePages.test.tsx`
Expected: PASS.
Run: `npm --prefix frontend test && npm --prefix frontend run build`
Expected: 전체 PASS, 빌드 성공.

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/pages/CodePages.tsx frontend/src/pages/CodePages.test.tsx
git commit -m "feat: move common and attendance code sheets to the common sheet frame" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 결재선 시트와 배정 이력

**Files:**
- Modify: `frontend/src/pages/ApprovalRoutePages.tsx` (`RouteSheet`, `VersionTable`, `RouteAssignments`, `ApprovalRoutePages`)
- Test: `frontend/src/pages/ApprovalRoutePages.test.tsx`

- [ ] **Step 1: 테스트를 새 흐름으로 바꾸기**

1. import에 `within`을 더한다: `import { cleanup, fireEvent, render, waitFor, within } from '@testing-library/react';`
2. `posted` 함수 다음에 저장 도우미를 더하고, `beforeEach`를 바꾼다.

```tsx
async function save(view: ReturnType<typeof render>, reason?: string) {
  fireEvent.click(within(form(view)).getByRole('button', { name: '저장' }));
  const dialog = await view.findByRole('dialog', { name: '변경 내용 저장' });
  if (reason !== undefined) fireEvent.change(within(dialog).getByLabelText('일괄 변경 사유'), { target: { value: reason } });
  fireEvent.click(within(dialog).getByRole('button', { name: /건 저장$/ }));
  return dialog;
}
```

```tsx
beforeEach(() => {
  history.replaceState(null, '', '/admin/approval-routes');
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value(this: HTMLDialogElement) { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value(this: HTMLDialogElement) { this.open = false; } });
});
```

3. 결재선 시트 테스트만 문구를 바꾼다. 배정 화면 테스트의 [입력]·[취소]는 그대로 둔다.

| 테스트 | 바꿀 줄 | 새 줄 |
| --- | --- | --- |
| 이름 셀을 클릭해 편집한… | 사유 change + submit | `await save(view, '이름 변경');` |
| 시트 도구·고정 코드… | 이름 목록 | `['조회', '행 추가', '비활성화', '저장', '변경 취소']` |
| 지정 사원과 조직장을… | `{ name: '입력' }`, `{ name: '취소' }` | `{ name: '행 추가' }`, `{ name: '변경 취소' }` |
| 50행 제한과 미저장 이동… | `{ name: '입력' }` | `{ name: '행 추가' }` |
| 422 응답에 편집값… | `fireEvent.submit(form(view));` | `await save(view, '검토');` |
| 저장 결과가 불명확하면… | 사유 change, `const id = …`, 첫 submit의 세 줄 | `await save(view, '변경 검토');` |

마지막 테스트는 `await waitFor(() => expect(view.getByText('결과 확인 필요')).toBeTruthy());` 바로 다음에 `const id = (fetcher.mock.calls[0][1]?.body as URLSearchParams).get('commandId');`를 넣는다.

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/pages/ApprovalRoutePages.test.tsx`
Expected: FAIL. 확인 창과 새 버튼 문구가 아직 없다.

- [ ] **Step 3: 화면 구현**

1. **import:** `useId`를 더하고, `SheetSaveDialog`·`sheetChangeCounts`와 `sheetStateColumn`·`sheetStateOf` import를 더한다(6번 작업과 같다).
2. **`RouteSheet` 머리:** `function RouteSheet({ model: m, error }: {`부터 `}) {`까지를 아래로 바꾼다.

```ts
function RouteSheet({ model: m, error, busy = false }: {
  model: Routes;
  error?: string | null;
  busy?: boolean;
}) {
```

3. **상태:**
   - `const [errors, setErrors] = useState(m.sheet.errors ?? []);` 다음에 넣는다.

```ts
  const [confirming, setConfirming] = useState(() => (m.sheet.errors ?? []).some(item => item.field === 'reason'));
  const formId = useId();
```

   - `const unsaved = …;` 다음에 `const reasonError = errors.find(item => item.field === 'reason')?.message;`를 넣는다.
4. **제출 처리:** `function submit(event: FormEvent) { … }`(네 줄)를 아래로 바꾼다.

```ts
  function submit(event: FormEvent) {
    if (!sheet.current?.validate() || dirty.length < 1 || dirty.length > 50 || conflicts.length) { event.preventDefault(); return; }
    if (!confirming) { event.preventDefault(); setConfirming(true); }
  }
  function changeReason(value: string) {
    setReason(value);
    setErrors(current => current.filter(item => item.field !== 'reason'));
    renew();
  }
```

5. **상태 열:**
   - `const columns = [` 다음 줄에 `    sheetStateColumn<Row>(row => sheetStateOf(row, routeChanged(row, row.id ? latest.get(row.id) : undefined))),`를 넣는다.
   - 마지막 `sheetColumn<Row>({ colId: 'state', headerName: '변경 상태', … }),` 블록(4줄)을 지운다.
6. **화면 머리:** `<PageHeader title="결재선 관리" …`(두 줄)를 아래로 바꾼다.

```tsx
    <PageHeader title="결재선 관리" help={<>
      <p>한 명의 결재자 또는 대상자의 현재 조직장을 지정하세요.</p>
      <p>내용 변경은 새 버전으로 저장됩니다. 기존 배정은 유지되며 새 버전을 사용하려면 다시 배정하세요.</p>
      <p>한 번에 최대 50행을 저장합니다. 기존 행 삭제는 이력을 보존하고 비활성화합니다. 비활성 결재선은 신규 신청을 막지만 기존 신청의 결재자를 바꾸지 않습니다.</p>
    </>} actions={<a className="t-btn" href="/admin/approval-route-assignments">사원·조직별 배정</a>} />
```

7. **구역과 조회 조건 바:**
   - 바로 다음 `    <section className="t-card">`를 `    <section className="wl-section">`로 바꾼다.
   - 검색 form의 `className="t-toolbar"`를 `className="wl-querybar"`로 바꾼다.
   - `        <button className="t-btn primary">조회</button>`와 `        <a className="t-btn" href="/admin/approval-routes">전체 보기</a>` 두 줄을 아래로 바꾼다.

```tsx
        <div className="wl-querybar-actions">
          <a className="t-btn wl-query-reset" href="/admin/approval-routes">초기화</a>
          <button className="t-btn wl-query-submit" type="submit">조회</button>
        </div>
```

8. **설명 문장 지우기:** `      <p className="t-subtle">내용 변경은 새 버전으로 저장됩니다.…</p>`를 지운다(도움말로 옮겼다).
9. **시트 폼:** `      <Form action="/admin/approval-routes/bulk" onSubmit={submit} className="t-sheet-form">`에 `id={formId}`를 더한다.
10. **옛 도구 지우기:** `        <div className="t-sheet-toolbar" role="group" aria-label="결재선 시트 편집">`부터 `        <p className="t-subtle">한 번에 최대 50행을 저장합니다.…</p>`까지를 지운다.
11. **오류 안내:** `        {(error || errors.length > 0) && <div`를 `        {!reasonError && (error || errors.length > 0) && <div`로 바꾼다.
12. **시트:** `<WorklogSheet ref={sheet} label="결재선 편집 시트" …` 세 줄과 그다음 `{!rows.length && <p className="notice">…</p>}` 줄을 아래로 바꾼다.

```tsx
        <WorklogSheet ref={sheet} label="결재선 편집 시트" title="결재선 목록" rows={rows} columns={columns} getRowId={row => row.clientRowId}
          selectedIds={selected} onSelectionChange={setSelected} selectionLabel={(_row, index) => `${index + 1}행 선택`}
          changedIds={new Set(dirty.map(row => row.clientRowId))} errors={errors}
          emptyMessage="등록된 결재선이 없습니다. 행 추가 버튼으로 추가하세요."
          actions={<div className="w-sheet-tools" role="group" aria-label="결재선 시트 편집">
            <button type="button" className="t-btn" onClick={add} disabled={dirty.length >= 50}>행 추가</button>
            <button type="button" className="t-btn" onClick={remove} disabled={!selected.size} title="미저장 신규 행은 제거하고 저장된 행은 비활성 예정으로 표시합니다.">비활성화</button>
            <span className="w-sheet-divider" aria-hidden="true" />
            <button type="button" className="t-btn" onClick={cancel} disabled={!unsaved}>변경 취소</button>
            <button type="submit" className="t-btn primary" disabled={!dirty.length || dirty.length > 50 || Boolean(conflicts.length)}>저장</button>
          </div>}
          pagination={<nav className="actions pagination" aria-label="결재선 페이지">
            <span>{m.page + 1}페이지</span>
            {m.page > 0 && <a href={queryHref('/admin/approval-routes', { q: m.q, page: m.page - 1 })}>이전</a>}
            {m.hasNext && <a href={queryHref('/admin/approval-routes', { q: m.q, page: m.page + 1 })}>다음</a>}
          </nav>} />
```

13. **함수 끝:** `        <div className="t-sheet-reason">`부터 버전 이력 구역의 `</section>`까지를 아래로 바꾼다. 범위에는 사유 칸, `</Form>`, 페이지 `nav`, `</section>`, 버전 이력 구역이 들어간다.

```tsx
      </Form>
    </section>
    {confirming && <SheetSaveDialog formId={formId} label="일괄 변경 사유" reason={reason} error={reasonError}
      counts={sheetChangeCounts(dirty)} busy={busy} onReasonChange={changeReason} onClose={() => setConfirming(false)} />}
    <section className="wl-section t-route-versions">
      <VersionTable versions={m.versions.filter(version => m.sheet.latestRows.some(route => route.id === version.routeId))} />
      <p className="t-subtle">저장된 내용은 덮어쓰지 않습니다. 조회된 결재선의 버전입니다.</p>
    </section>
```

14. **`VersionTable`:** 반환 줄을 아래로 바꾼다.

```tsx
  return <WorklogSheet label="결재선 버전 이력" title="결재선 버전 이력" rows={versions} columns={columns} getRowId={version => version.id}
    emptyMessage="등록된 버전이 없습니다." />;
```

15. **`RouteAssignments`:** 다섯 곳을 고친다.
    - 화면 머리의 `help="사원 또는 조직에 적용할 1단계 결재선 버전을 지정하세요."`를 아래 첫 블록으로 바꾼다.
    - 조회 form을 감싼 `    <section className="t-card">`를 `    <section className="wl-section">`로, form의 `className="t-toolbar"`를 `className="wl-querybar"`로 바꾼다.
    - `        <button className="t-btn primary">조회</button>`를 `        <div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div>`로 바꾼다.
    - `      <p className="t-subtle">선택 우선순위: …</p>`를 지운다. 문장은 도움말로 옮겼다.
    - 적용 배정 이력 구역(`<section className="t-card">`, `<h2>적용 배정 이력</h2>`, 시트 줄, `</section>`)을 아래 둘째 블록으로 바꾼다.

```tsx
help={<>
      <p>사원 또는 조직에 적용할 1단계 결재선 버전을 지정하세요.</p>
      <p>선택 우선순위: 사원별 결재선 → 기존 지정 승인자 → 현재 소속 조직의 결재선. 선택된 경로가 공석·부적격이면 신청을 차단합니다.</p>
    </>}
```

```tsx
      <section className="wl-section">
        <WorklogSheet label="결재선 배정 이력" title="적용 배정 이력" rows={m.history} columns={columns} getRowId={assignment => assignment.id} scope="all" emptyMessage="등록된 배정이 없습니다." />
      </section>
```

16. **진입 함수:** `export function ApprovalRoutePages({ page, data }: PageProps): ReactNode {`에 `busy`를 더하고, `RouteSheet` 반환에 `busy={busy}`를 넘긴다.

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix frontend test -- src/pages/ApprovalRoutePages.test.tsx`
Expected: PASS.
Run: `npm --prefix frontend test && npm --prefix frontend run build`
Expected: 전체 PASS, 빌드 성공.

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/pages/ApprovalRoutePages.tsx frontend/src/pages/ApprovalRoutePages.test.tsx
git commit -m "feat: move approval route sheets to the common sheet frame" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 읽기 전용 T1 목록

권한 목록, 회사 근무현황, 회사 신청, 조직도 오른쪽 표, 신청 목록, 연차 원장, 근로시간 검토, 감사 이력, 출퇴근 기록 조회 조건을 T1 틀로 옮긴다. 기존 테스트가 이 화면들의 동작을 이미 확인한다. 이번 변경은 표시 구조만 바꾸므로, 기존 테스트가 그대로 통과하는지를 완료 기준으로 삼는다.

**Files:**
- Modify: `frontend/src/pages/IdentityPages.tsx`, `frontend/src/pages/WorkflowPages.tsx`, `frontend/src/pages/OrgSheetPages.tsx`(`CompanyOrganizationsPage`)

- [ ] **Step 1: 기준 테스트 실행**

Run: `npm --prefix frontend test -- src/pages/IdentityPages.test.tsx src/pages/WorkflowPages.test.tsx src/pages/OrgSheetPages.test.tsx`
Expected: PASS(변경 전 기준).

- [ ] **Step 2: `IdentityPages.tsx`**

`import { PageHeader } from '../components/PageHeader';` 다음에 `import { SectionHeader } from '../components/SectionHeader';`를 더한다. 세 함수를 아래로 바꾼다. 열 정의는 지금과 같다.

```tsx
function AccessListPage({ model: m }: { model: AccessList }) {
  return (
    <>
      <PageHeader title="권한 관리" help={<>
        <p>계정별 역할을 확인하고 기존 관리 화면에서 변경하세요.</p>
        <p>공통은 필수 역할입니다. 관리자·대표와 결재 담당 기능은 각각 부여하며 함께 보유할 수 있습니다.</p>
      </>} />
      <Form action="/admin/access" method="get" className="wl-querybar">
        <Field label="사번·이름 검색" name="q" value={m.q} maxLength={120} />
        <div className="wl-querybar-actions"><button type="submit" className="t-btn wl-query-submit">조회</button></div>
      </Form>
      <section className="wl-section">
        <WorklogSheet
          label="계정별 권한"
          title="계정 목록"
          rows={m.accessRows}
          getRowId={(row) => row.employeeId}
          scope="page"
          emptyMessage="조회된 계정이 없습니다."
          pagination={<Pagination path="/admin/access" values={{ q: m.q }} page={m.page} hasNext={m.hasNext} />}
          columns={[
            { field: 'employeeNo', headerName: '사번', pinned: 'left', minWidth: 120 },
            { field: 'employeeName', headerName: '이름', minWidth: 130 },
            { colId: 'roles', headerName: '역할·기능', valueGetter: ({ data }) => data && formatRoles(data.roles), minWidth: 250 },
            { colId: 'active', headerName: '계정 상태', valueGetter: ({ data }) => data?.active ? '활성' : '비활성', minWidth: 120 },
            sheetColumn<AccessList['accessRows'][number]>(
              { colId: 'manage', headerName: '관리', sortable: false, filter: false, minWidth: 120 },
              (row) => <a href={`/admin/employees/${row.employeeId}/access`} aria-label={`${row.employeeName} 권한 관리`}>권한 관리</a>,
            ),
          ]}
        />
      </section>
    </>
  );
}
function CompanyAttendancePage({ model: m }: { model: CompanyAttendance }) {
  const [employeeId, setEmployeeId] = useState(m.employeeId ?? '');
  const values = { month: m.month, organizationId: m.organizationId, employeeId: m.employeeId, q: m.q };
  return (
    <>
      <PageHeader title="회사 근무현황" help={<>
        <p>사원을 선택해 해당 월의 일별 근무 결과를 확인하세요.</p>
        <p>조직은 서울 기준 오늘의 소속으로 조회합니다.</p>
        <p>선택 사원의 검토용 결과입니다. 미확정·오래된 결과는 0시간으로 처리하지 않습니다.</p>
      </>} />
      <Form
        action="/company/attendance"
        method="get"
        className="wl-querybar"
        onChange={(event) => {
          const name = event.target.getAttribute('name');
          if (name === 'organizationId' || name === 'q') setEmployeeId('');
        }}
      >
        <input type="hidden" name="employeeId" value={employeeId} />
        <Field label="기준 월 (서울)" name="month" type="month" value={m.month} required />
        <OrganizationFilter organizations={m.organizations} organizationId={m.organizationId} />
        <Field label="사번·이름 검색" name="q" value={m.q} maxLength={100} />
        <div className="wl-querybar-actions"><button type="submit" className="t-btn wl-query-submit">조회</button></div>
      </Form>
      <p className="t-subtle">조회 기준 (서울): {fmtDateTime(m.asOf)}</p>
      <section className="wl-section">
        <WorklogSheet
          label="회사 현황 대상 사원"
          title="대상 사원"
          rows={m.employees}
          getRowId={(employee) => employee.id}
          scope="page"
          emptyMessage="조회할 사원이 없습니다."
          pagination={<Pagination path="/company/attendance" values={values} page={m.page} hasNext={m.hasNext} />}
          columns={[
            sheetColumn<Employee>(
              { colId: 'employee', headerName: '사번·이름', valueGetter: ({ data }) => data && `${data.employeeNo} · ${data.employeeName}`, minWidth: 200, pinned: 'left' },
              (employee) => <a
                href={queryHref('/company/attendance', { ...values, employeeId: employee.id, page: m.page })}
                aria-current={m.selectedEmployee?.id === employee.id ? 'true' : undefined}
              >{employee.employeeNo} · {employee.employeeName}</a>,
            ),
            { colId: 'organizationName', headerName: '현재 소속', valueGetter: ({ data }) => data?.organizationName ?? '미배정', minWidth: 160 },
          ]}
        />
      </section>
      <section className="wl-section">
        {m.selectedEmployee ? (
          <WorklogSheet
            label="사원 일별 근무현황"
            title={`${m.selectedEmployee.employeeName} · ${m.month} 일별 현황`}
            rows={m.days}
            getRowId={(day) => day.workDate}
            scope="all"
            emptyMessage="조회된 일별 현황이 없습니다."
            columns={[
              { colId: 'workDate', headerName: '근무일', valueGetter: ({ data }) => data && fmtDate(data.workDate), minWidth: 140, pinned: 'left' },
              { colId: 'status', headerName: '상태·최신성', valueGetter: ({ data }) => data && `${statuses[data.status] ?? data.status} · ${data.current ? '최신' : '미산정·재집계 필요'}`, minWidth: 250 },
              {
                colId: 'scheduledMillis', headerName: '예정 근무 (시:분:초.밀리초)',
                valueGetter: ({ data }) => data?.current ? data.scheduledMillis : null,
                valueFormatter: ({ value }) => duration(value),
                filterValueGetter: ({ data }) => duration(data?.current ? data.scheduledMillis : null),
                getQuickFilterText: ({ value }) => duration(value), minWidth: 230, cellClass: 'w-sheet-number',
              },
              {
                colId: 'actualWorkMillis', headerName: '실근로 (시:분:초.밀리초)',
                valueGetter: ({ data }) => data?.current ? data.actualWorkMillis : null,
                valueFormatter: ({ value }) => duration(value),
                filterValueGetter: ({ data }) => duration(data?.current ? data.actualWorkMillis : null),
                getQuickFilterText: ({ value }) => duration(value), minWidth: 220, cellClass: 'w-sheet-number',
              },
              { colId: 'leaveDays', headerName: '휴가 (DAY)', valueGetter: ({ data }) => data?.current && data.leaveDays != null ? data.leaveDays : '미확정', minWidth: 130, cellClass: 'w-sheet-number' },
            ]}
          />
        ) : <>
          <SectionHeader title="일별 현황" />
          <p>사원을 선택하면 해당 월의 일별 현황을 볼 수 있습니다.</p>
        </>}
      </section>
    </>
  );
}
function CompanyRequestsPage({ model: m }: { model: CompanyRequests }) {
  return (
    <>
      <PageHeader title="회사 신청 현황" help={<>
        <p>조회연도의 회사 신청 유형과 처리 상태를 확인하세요.</p>
        <p>조직은 서울 기준 오늘의 소속입니다. 신청일량은 조회연도에 포함된 일량이며 취소 신청은 원신청을 기준으로 합니다.</p>
      </>} />
      <Form action="/company/requests" method="get" className="wl-querybar">
        <Field label="조회연도 (서울)" name="year" type="number" value={m.year} min={1} max={9999} required />
        <OrganizationFilter organizations={m.organizations} organizationId={m.organizationId} />
        <SelectField label="신청 상태" name="status" value={m.status}>
          <option value="">전체 상태</option>
          {['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'].map((status) => (
            <option key={status} value={status}>{statuses[status]}</option>
          ))}
        </SelectField>
        <div className="wl-querybar-actions"><button type="submit" className="t-btn wl-query-submit">조회</button></div>
      </Form>
      <p className="t-subtle">조회 기준 (서울): {fmtDateTime(m.asOf)}</p>
      <section className="wl-section">
        <WorklogSheet
          label="회사 신청 목록"
          title="신청 목록"
          rows={m.requests}
          getRowId={(request) => request.id}
          scope="page"
          emptyMessage="조회된 신청이 없습니다."
          pagination={<Pagination
            path="/company/requests"
            values={{ year: m.year, organizationId: m.organizationId, status: m.status }}
            page={m.page}
            hasNext={m.hasNext}
          />}
          columns={[
            { colId: 'employee', headerName: '사번·이름', valueGetter: ({ data }) => data && `${data.employeeNo} · ${data.employeeName}`, minWidth: 200, pinned: 'left' },
            { colId: 'organizationName', headerName: '현재 소속', valueGetter: ({ data }) => data?.organizationName ?? '미배정', minWidth: 150 },
            { colId: 'kind', headerName: '종류', valueGetter: ({ data }) => data && (kinds[data.kind] ?? data.kind), minWidth: 140 },
            { colId: 'period', headerName: '기간', valueGetter: ({ data }) => data && `${fmtDate(data.fromDate)} ~ ${fmtDate(data.toDate)}`, minWidth: 250 },
            { field: 'quantityDays', headerName: '조회연도 신청일량 (DAY)', minWidth: 220, cellClass: 'w-sheet-number' },
            { colId: 'status', headerName: '상태·효력', valueGetter: ({ data }) => data && `${statuses[data.status] ?? data.status} · ${effects[data.effectStatus] ?? data.effectStatus}`, minWidth: 190 },
            { colId: 'submittedAt', headerName: '제출 시각 (서울)', valueGetter: ({ data }) => data && fmtDateTime(data.submittedAt), minWidth: 240 },
          ]}
        />
      </section>
    </>
  );
}
```

- [ ] **Step 3: `WorkflowPages.tsx`**

**1. 신청 목록:**

`RequestRows` 머리를 `function RequestRows({ requests, pagination }: { requests: RequestView[]; pagination?: React.ReactNode }) {`로 바꾼다. 반환 줄에는 ` pagination={pagination}`을 더한다.

`case 'request/list'`의 간단 목록 분기(`<>`부터 `</>`까지)를 아래로 바꾼다.

```tsx
        <>
          <PageHeader title={m.title} />
          <section className="wl-section">
            <RequestRows requests={m.requests} pagination={m.page !== undefined && <nav className="actions pagination" aria-label="결재 페이지"><span>{m.page + 1}페이지</span>{m.page > 0 && <a href={`${m.completed ? '/approvals/completed' : '/approvals'}?page=${m.page - 1}`}>이전</a>}{m.hasNext && <a href={`${m.completed ? '/approvals/completed' : '/approvals'}?page=${m.page + 1}`}>다음</a>}</nav>} />
          </section>
        </>
```

**2. 연차 원장:** `Ledger`의 `<h1>연차 원장</h1>`부터 첫 `</section>`까지를 아래로 바꾼다.

```tsx
      <PageHeader title="연차 원장" help={<>
        <p>분류는 원장 금액을 변경하지 않습니다.</p>
        <p>추가한 이력은 덮어쓰지 않습니다. 사용·복원은 원효력을 참조합니다.</p>
      </>} actions={<a className="t-btn" href={`/admin/leave-balances/buckets/${m.bucketId}/attributions`}>귀속연도 관리</a>} />
      <section className="wl-section">
        <WorklogSheet label="연차 원장" title="원장 이력" rows={m.ledger} columns={columns} getRowId={entry => entry.id} scope="all" />
      </section>
```

**3. 근로시간 검토:** `Month`의 `<h1>일·주·월 근로시간 검토</h1>`부터 조회 `</Form>`까지를 아래로 바꾼다.

```tsx
      <PageHeader title="일·주·월 근로시간 검토" help="법정 적합성·급여 확정 자료가 아닌 검토용 결과입니다. 미확정은0시간으로 바꾸지 않으며, 확인된 부분합과 구분합니다." />
      <Form
        action={m.admin ? '/admin/attendance' : '/me/worktime'}
        method="get"
        className="wl-querybar"
      >
        <Field label="기준 월 (서울)" name="month" type="month" value={view.month} required />
        {m.admin && (
          <SelectField label="대상 사원" name="employeeId" value={m.employeeId}>
            <EmployeeOptions employees={m.employees} />
          </SelectField>
        )}
        <div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div>
      </Form>
```

같은 함수의 일 결과 구역(`<section className="panel">`, `<h2>일 결과</h2>`, 시트, `</section>`)을 아래로 바꾼다.

```tsx
      <section className="wl-section">
        <WorklogSheet label="일별 계산 결과" title="일 결과" rows={view.days} columns={columns} getRowId={day => day.workDate} scope="all" />
      </section>
```

**4. 감사 이력:** `Audit`의 반환 블록 전체를 아래로 바꾼다. 기존 화면의 "(최대50건)" 표기는 도움말 문장으로 옮긴다.

```tsx
  return (
    <>
      <PageHeader title="고객사 감사 이력" help={<>
        <p>열람 자체를 감사합니다. 현재 담당자 권한으로 소속 고객사의 감사만 확인합니다. 시각 기준은 Asia/Seoul (UTC+09:00)입니다.</p>
        <p>이전·다음은 조회 시점 원장 기준이며 전체 원장의 고정 스냅샷을 보장하지 않습니다. 동일 시각에는 감사 ID로 순서를 구분합니다.</p>
        <p>한 번에 최대 50건을 표시합니다.</p>
        <p>비밀번호·토큰·이메일·IP와 저장 JSON 원문은 제공하지 않습니다. 상세에서는 제한된 전후 업무 요약을 확인할 수 있습니다.</p>
      </>} />
      <section className="wl-section">
        <WorklogSheet label="고객사 감사 목록" title="조회된 기록" rows={m.audits} columns={columns} getRowId={row => row.id}
          emptyMessage="이 범위에 감사 기록이 없습니다. 최신 목록에서 다시 확인할 수 있습니다."
          pagination={<nav aria-label="감사 페이지">
            {m.page.previousCursor && (
              <a href={`/admin/audit?after=${m.page.previousCursor}`}>이전 페이지 (더 최근)</a>
            )}{' '}
            <a href="/admin/audit">최신 목록</a>{' '}
            {m.page.nextCursor && (
              <a href={`/admin/audit?before=${m.page.nextCursor}`}>다음 페이지 (더 과거)</a>
            )}
          </nav>} />
      </section>
    </>
  );
```

**5. 출퇴근 기록 조회 조건:** `case 'clock/history'`의 `<Form action="/me/attendance" method="get" className="t-attendance-filter search-form">`부터 `</Form>`까지를 아래로 바꾼다. [오늘]은 오늘 날짜로 돌아가는 초기화 링크다.

```tsx
          <Form action="/me/attendance" method="get" className="wl-querybar">
            <Field
              label="기준일"
              name="date"
              type="date"
              value={(data as unknown as ClockModel).day.workDate}
              required
            />
            <div className="wl-querybar-actions">
              <a className="t-btn wl-query-reset" href="/me/attendance">오늘</a>
              <button className="t-btn wl-query-submit" type="submit">조회</button>
            </div>
          </Form>
```

- [ ] **Step 4: 조직도 오른쪽 표 (`OrgSheetPages.tsx`)**

`CompanyOrganizationsPage`의 `return <div className="t-company-organizations">…</div>;` 한 줄을 아래로 바꾼다.
- 조직 계층 카드는 그대로 둔다. 트리 패널은 5단계에서 옮긴다.
- 오른쪽 표는 구역으로 바꾼다.

```tsx
  return <div className="t-company-organizations">
    <PageHeader title="조직도" help={<>
      <p>현재 조직과 조직장을 확인하고 조직별 직원을 조회하세요.</p>
      <p>선택 조직의 오늘 유효 소속 · 검색 결과의 현재 페이지 최대 50명입니다. 하위 조직은 포함하지 않습니다.</p>
    </>} />
    <p className="t-subtle">조회 기준 (서울): {fmtDateTime(m.asOf)} · 유효 소속일 {fmtDate(m.today)}</p>
    <div className="t-org-layout">
      <section className="t-card"><h2>조직 계층</h2><p className="t-subtle">전체 조직 구조입니다. 화살표로 하위 조직을 접고 펼칠 수 있습니다.</p><nav aria-label="조직 계층" className="t-org-tree">{m.organizations.length ? <ul>{children(null).map(node)}</ul> : <p className="notice">등록된 조직이 없습니다.</p>}</nav></section>
      <section className="wl-section">{m.selectedOrganization ? <>
        <div className="t-org-selected-head"><span className="t-subtle">현재 조직장</span><HeadStatus name={m.selectedOrganization.headEmployeeName} eligible={m.selectedOrganization.headEligible} approverEligible={m.selectedOrganization.headApproverEligible} /></div>
        <form action="/company/organizations" method="get" className="wl-querybar">
          <input type="hidden" name="organizationId" value={m.organizationId ?? ''} />
          <Field label="선택 조직 사번·이름 검색" name="q" type="search" value={m.q} maxLength={100} />
          <div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div>
        </form>
        <WorklogSheet label="선택 조직 직원" title={`${m.selectedOrganization.name} 직원`} rows={m.employees} columns={columns} getRowId={employee => employee.id}
          emptyMessage="조회된 직원이 없습니다."
          pagination={<nav className="actions pagination" aria-label="조직 직원 페이지"><span>{m.page + 1}페이지 · {m.employees.length}명</span>{m.page > 0 && <a href={queryHref('/company/organizations', { organizationId: m.organizationId, q: m.q, page: m.page - 1 })}>이전</a>}{m.hasNext && <a href={queryHref('/company/organizations', { organizationId: m.organizationId, q: m.q, page: m.page + 1 })}>다음</a>}</nav>} />
      </> : <><h2>조직별 직원</h2><p className="notice">조직을 선택하면 현재 조직장과 직원 목록을 볼 수 있습니다.</p></>}</section>
    </div>
  </div>;
```

- [ ] **Step 5: 통과 확인**

Run: `npm --prefix frontend test -- src/pages/IdentityPages.test.tsx src/pages/WorkflowPages.test.tsx src/pages/OrgSheetPages.test.tsx`
Expected: PASS. 빈 목록 문구('조회된 계정이 없습니다.' 등)는 시트의 빈 안내로 표시되고, 페이지 링크는 시트 하단에 남는다.
Run: `npm --prefix frontend test && npm --prefix frontend run build`
Expected: 전체 PASS, 빌드 성공.

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/pages/IdentityPages.tsx frontend/src/pages/WorkflowPages.tsx frontend/src/pages/OrgSheetPages.tsx
git commit -m "feat: move read-only list screens to the common sheet frame" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: 합성 화면 검증 (고친 것이 있으면 작업별 커밋)

- [ ] **Step 1: 자동 검증**

```bash
npm --prefix frontend test
npm --prefix frontend run build
```

Expected: 전체 PASS, 빌드 성공.

- [ ] **Step 2: 캡처**

```bash
node "$TOOLS/capture.mjs" "$TOOLS/shots/stage2" http://127.0.0.1:18131 sheets
node "$TOOLS/capture.mjs" "$TOOLS/shots/stage2-stage1" http://127.0.0.1:18131 stage1
node "$TOOLS/sheet-check.mjs" "$TOOLS/shots/stage2"
```

Expected:
- 시트 묶음: `28 captures, 0 with JS errors, 0 with page overflow`
- 1단계 묶음(회귀): `24 captures, 0 with JS errors, 0 with page overflow`
- 동작 확인: 마지막 줄 `PASS`

- [ ] **Step 3: 눈으로 확인**

아래 캡처를 열어 승인 시안(디자인 보드 1번 화면)과 비교한다.
- `organizations-pending-charcoal-1440.png`: 상태 열 점·문구, 신규 초록 띠, 비활성 예정 빨간 띠·취소선, 수정 칸 삼각형, 오류 칸 테두리, 오류 줄, 하단 페이지 이동
- `organizations-reason-blue-1440.png`: 저장 확인 창 440px, 건수 칩, 사유 오류
- `common-codes-charcoal-1440.png`: 두 구역과 조회 조건 바
- `organizations-charcoal-390.png`: 조회 조건 바가 세로로 쌓이고 도구 버튼이 줄바꿈되는지
- `access-list-blue-1440.png`, `company-requests-charcoal-1440.png`: 읽기 전용 목록의 머리·건수·하단
- `filter-popup-charcoal-1440.png`, `save-dialog-charcoal-1440.png`: 필터 창, 확인 창

시안과 다른 점을 찾으면 원인을 고치고 해당 테스트·캡처를 다시 실행한 뒤 `fix: …`로 커밋한다.

- [ ] **Step 4: 사용자에게 보낼 비교 이미지**

`$TOOLS/shots/stage2-before`와 `$TOOLS/shots/stage2`에서 같은 화면 3쌍을 고른다(조직 관리 편집 상태, 공통코드, 회사 신청). 고른 이미지를 `SendUserFile`로 보낸다.

---

### Task 11: 문서와 결과 기록

**Files:**
- Modify: `design.md`
- Modify: `docs/superpowers/specs/2026-11-10-worklog-design-system-design.md`
- Modify: `docs/quality/worklog-design-system-20261110.md`
- Modify: `docs/superpowers/plans/2026-11-10-worklog-design-stage2-sheet.md`

- [ ] **Step 1: `design.md`에 시트 규칙 추가**

"## CSS 층" 절 앞에 다음 절을 넣는다.

```markdown
## 시트·조회 조건 바·저장 확인

- **시트(`WorklogSheet`):** 구성 순서는 다음과 같다.
  1. 구역 머리: 제목·총 N건 | 찾기·저장 전 변경·편집 동작
  2. 그리드: 행 38px, 머리 36px(600px 미만은 44px 행). 2px 윗선과 1px 칸선
  3. 오류 줄: n행 · 열: 메시지 · 해당 칸으로 이동
  4. 하단: 표시 건수 | 페이지 이동

  바깥 제목이 있는 화면은 `title`을 비워 건수만 표시한다.
- **정렬:** 글자는 왼쪽, 숫자는 오른쪽, 날짜·시각·체크·상태는 가운데에 둔다. 머리 칸은 첫 행 값과 같은 정렬을 쓴다.
- **행 상태:**
  - 신규: 왼쪽 ok 띠
  - 비활성 예정: danger 띠와 취소선
  - 수정한 칸: 왼쪽 위 info 삼각형
  - 오류 칸: danger 테두리와 바탕
  - 상태 열은 신규·수정·비활성 예정만 점과 문구로 표시한다.
- **조회 조건 바(`.wl-querybar`):** 기존 GET form에 붙인다. [조회]는 진한 중립색, [초기화]는 선 없는 버튼이다.
- **저장 확인(`SheetSaveDialog`):** 일괄 저장 시트의 변경 사유는 확인 창에서 받는다.
  - 사유 칸과 저장 버튼은 `form` 속성으로 시트 폼에 붙는다.
  - 확인 창 밖에서 들어온 제출은 창을 연다.
```

- [ ] **Step 2: 설계 문서 보정**

이 계획의 "설계 보정" 1~5를 설계 문서에 반영한다. 아래 다섯 곳을 고친다.

1. 4절의 "표는 2px 윗선과 1px 칸선을 쓴다. …머리 정렬은 내용 정렬을 따른다." 끝에 다음 문장을 붙인다.

   > 날짜·시각은 `YYYY-MM`·`YYYY-MM-DD`·`HH:MM`으로 시작하는 25자 이하 값으로 판정하고, 머리 칸은 첫 행 값의 정렬을 쓴다.

2. 7.5절 **props** 줄의 "`title?`(기본은 `label`)"을 다음으로 바꾼다.

   > `title?`(주지 않으면 제목 없이 건수만 표시한다. 바깥 제목이 있는 화면에서 제목이 겹치지 않게 하기 위해서다)

3. 7.5절 **상태 칸** 줄 끝에 다음 문장을 붙인다.

   > 상태 열은 머리를 "상태"로 하고 선택 칸 다음에 고정한다.

4. 7.6절 첫 줄 다음에 항목을 하나 더한다.

   > - 도구의 [저장]은 submit 버튼이다. 시트 form의 제출 처리는 확인 창이 닫혀 있으면 제출을 막고 창을 연다(Enter 같은 우회 제출 포함). 도구 문구는 [행 추가]·[비활성화]·[변경 취소]·[저장]이다.

5. 7.7절 **크기** 줄 끝에 다음 문장을 붙인다.

   > 2단계에서 `size`를 추가하고 `s`만 스타일을 둔다. 나머지 크기와 기본 너비 변경은 3단계에서 한다.

- [ ] **Step 3: 결과 문서에 2단계 절 추가**

`docs/quality/worklog-design-system-20261110.md` 끝에 "## 2단계 공통 시트 (2026-11-10)" 절을 더한다. 1단계 절과 같은 표 형식을 쓰고, 10번 작업의 실제 출력으로 행을 채운다.

| 항목 | 기대 |
| --- | --- |
| 프런트 테스트 | 전체 통과(파일 수·테스트 수 기록) |
| 타입·빌드 | `tsc --noEmit`과 `vite build` 성공 |
| 시트 합성 캡처 | 7개 화면 × 차콜·블루 × 1440·390px = 28장, JS 오류 0 |
| 390px 페이지 가로 넘침 | 0 |
| 1단계 화면 회귀 | 24장 JS 오류 0, 넘침 0 |
| 셀 수정 표시·상태·저장 전 변경 | `sheet-check.mjs` 통과 |
| 확인 창 초점·Tab 가두기·Esc 복귀 | `sheet-check.mjs` 통과 |
| 사유 422 자동 열기·초점 | 페이지 테스트와 `sheet-check.mjs` 통과 |
| 저장 확인 제출 필드 | 페이지 테스트에서 `reason`·`rows`·`commandId`·`_csrf`·조회 조건 전송 확인 |
| Java 테스트 | Java 변경 없음, 미실행 |
| 실제 DB·공개 서버·실기기 | 범위 밖, 미실행 |

이어서 다음 하위 절을 쓴다.
- "### 커밋": 2단계 커밋 목록
- "### 계획과 달라진 점": 실행 중 바꾼 것이 없으면 "없음"
- "### 다음 단계로 넘긴 것": 아래 항목
  - 제목 없이 건수만 표시하는 시트 11곳의 바깥 제목과 시트 머리 정리(3~5단계)
  - 조직도 트리 패널(5단계)
  - `AdminPages`의 도달하지 않는 목록 두 화면(기록만)
  - legacy 부품 변형 14개 중 시트 관련 정리 결과

- [ ] **Step 4: 계획 끝에 실행 기록 추가**

이 문서 끝에 "## 실행 기록" 절을 더한다. 실행 중 계획과 달라진 점과 그 이유를 적는다.

- [ ] **Step 5: 커밋**

```bash
git add design.md docs/superpowers/specs/2026-11-10-worklog-design-system-design.md docs/quality/worklog-design-system-20261110.md docs/superpowers/plans/2026-11-10-worklog-design-stage2-sheet.md
git commit -m "docs: record the WorkLog common sheet stage" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Push 여부 확인**

2단계 커밋 목록과 검증 결과를 사용자에게 보고하고, `claude/worklog-design-system-20261110` 일반 Push 여부를 묻는다.
