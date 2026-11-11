# WorkLog 디자인 시스템 5단계(정리) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 아직 옛 모양인 T1 목록과 T5 홈을 새 디자인 층으로 옮긴다. 쓰지 않는 legacy CSS 규칙을 지우고 남은 양을 기록한다. 마지막으로 README·설계·결과 문서를 마무리한다.

**Architecture:**

- 남은 목록 화면은 3~4단계와 같은 규칙으로 옮긴다:
  - `PageHeader`, 조회 조건 바, `wl-section`
  - 문서형 표(`FormTable`·`FormRow`)와 `wl-form-actions`
  - 짧은 링크·이력 목록은 새 `ul.wl-rows`(2px 윗선·행 구분선)
- 마크업을 바꾸기 어려운 화면(홈, 결재함, 조직도 트리, 오늘 출퇴근 줄)은 클래스는 그대로 두고 새 CSS 파일 `design/screens.css`(층 없음)에서 모양을 바꾼다.
- legacy 정리는 "필요한 클래스가 소스 어디에도 없어 절대 일치할 수 없는 규칙"만 지운다.
  - 지우기 전후의 합성 화면 192장이 픽셀 단위로 같아야 한다.
  - 같지 않으면 그 파일을 되돌린다.

**Tech Stack:** React 19.3.1, TypeScript 6.0.3, Vite 8.3.5, Vitest 5.0.5 + Testing Library(jsdom 26.1.0). 검증 도구는 git 밖의 Python `http.server` 미리보기, `puppeteer-core`, `compare.mjs`(1단계 픽셀 비교), Python 3 분석 스크립트다.

**Spec:** [설계](../specs/2026-11-10-worklog-design-system-design.md) 9절 5단계("남은 T1 목록, T5 홈, 쓰지 않는 legacy 규칙·별칭 삭제, 문서 | legacy 잔여량 기록, 전체 테스트·빌드, 결과 문서"), 10절(`mvnw.cmd test`), 12절(README). 1~4단계 넘김 항목은 [검증 결과](../../quality/worklog-design-system-20261110.md)에 있다.

---

## 작업 위치와 공통 규칙

- **작업트리:** `C:\Users\ihpark\dev\vibe-attendance\target\worklog-design-20261110`, 브랜치 `claude/worklog-design-system-20261110`. 4단계 커밋은 Push했다(`ae723df`).
- **명령 실행:** Git Bash에서 아래 두 줄을 먼저 실행한다.
  ```bash
  cd /c/Users/ihpark/dev/vibe-attendance/target/worklog-design-20261110
  TOOLS=/c/Users/ihpark/dev/vibe-attendance/target/hallmark-tools/worklog-design-20261110
  ```
- **행 번호:** 계획에 적은 행 번호는 작성 시점(`ae723df`) 기준이다. 앞 작업에서 밀리므로 함수 이름과 인용한 코드로 위치를 찾는다.
- **테스트·빌드:** `npm --prefix frontend test [-- <경로>]`, `npm --prefix frontend run build`.
- **커밋:** 작업마다 커밋하고 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`를 붙인다.
- **Push·배포:** 5단계를 마친 뒤 Push 여부를 묻는다. 공개 데모 배포는 하지 않는다.
- **Java:** Java 코드는 바꾸지 않는다. 10절대로 `mvnw.cmd test`는 한 번 실행해 결과를 기록한다.
- **지켜야 하는 테스트 계약:** 아래 다섯 가지는 바꾸지 않는다.
  - 폼 `action`·필드 이름·라벨 글자
  - `Check`의 `_이름` hidden
  - 결재함 목록 이름 "결재 신청 목록"과 페이지 nav "결재 페이지"
  - 결재선 배정 편집 group "결재선 배정 편집"과 버튼 이름(입력·저장·배정 종료·취소)
  - 홈 위젯 설정(모달, group "표시할 위젯")

## 설계 보정 (설계 문서와 다르게 구현하는 결정)

1. **짧은 목록은 시트로 바꾸지 않는다.** 템플릿·기초 묶음·허용망·정책 버전·주간 참고량은 몇 줄짜리 링크·이력 목록이다. 정렬·필터가 필요 없어 `ul.wl-rows`로 둔다.
2. **결재함·홈·조직도 트리·오늘 출퇴근 줄은 마크업을 유지한다.** 클래스 이름을 기준으로 하는 기존 화면 계약이 있어, 새 CSS(`design/screens.css`)로만 모양을 바꾼다.
3. **legacy 정리 범위:**
   - **지우는 규칙:** 선택자에 필요한 클래스가 React 소스 어디에도 없는 규칙만 지운다.
     - `:not()`·`:is()`·`:where()`·`:has()`·`:nth-*()` 안의 클래스는 필요 조건으로 보지 않는다.
     - AG Grid가 만드는 `ag-*` 클래스는 있는 것으로 본다.
     - 조립한 클래스 접두사(`t-state-${…}`, `'t-tone-' + …` 등)도 있는 것으로 본다.
   - **지우지 않는 파일:**
     - `app.css`: Thymeleaf 화면과 함께 쓰고, 동적으로 만든 클래스를 정적으로 확인할 수 없다.
     - 범위 밖인 로그인·소개 화면 CSS(`DemoAccess.css`, `DemoLanding.css`)
     - legacy 토큰(`tokens.css`, `base.css`)
   - **지우지 않는 규칙:** 클래스는 있지만 새 층에 가려진 규칙은 정적으로 증명할 수 없다. 지우지 않고 남은 양으로 기록한다.
4. **호환 별칭:** 정리 뒤 사용처(`var(--이름)`)가 0인 별칭만 `design/tokens.css`에서 지운다.
   - 작성 시점에 별칭은 40개이고, `--radius-card` 1개가 이미 사용처 0이다.
   - 가장 적게 쓰는 것은 1곳이다: `--font-display`, `--t-nav-*` 일부.
   - legacy `tokens.css`의 같은 이름 정의는 범위 밖이라 그대로 둔다.
5. **하지 않는 것:** 다음 화면은 바꾸지 않고 기록만 한다.
   - 도달하지 않는 `WorkflowPages`의 옛 `Mine`(서버가 `request/mine`에 항상 `annual`을 보낸다)
   - Thymeleaf 조각 `request/rows`
   - `EmployeesPage`·`OrganizationsPage`, `today`
   - 로그인·운영자 화면

## 파일 구조

| 파일 | 책임 | 작업 |
| --- | --- | --- |
| `frontend/src/pages/AdminPages.tsx` | 근무 템플릿 목록, 허용 네트워크 | 수정 (1) |
| `frontend/src/pages/WorkflowPages.tsx` | 기초 잔여, 원장 조정, 근로시간 하단, 계산 정책 | 수정 (2) |
| `frontend/src/pages/ApprovalRoutePages.tsx` | 결재선 배정 폼 | 수정 (3) |
| `frontend/src/pages/OrgSheetPages.tsx`, `PersonnelPage.tsx` | 조직도 트리 구역, 재직 상태 이력 구역 | 수정 (4, 6) |
| `frontend/src/design/ui.css` | `wl-rows`, `wl-inline-form` | 수정 (1) |
| `frontend/src/design/screens.css` | 결재함, 조직도 트리, 오늘 출퇴근 줄, 홈 | 생성 (4), 수정 (5) |
| `frontend/src/App.tsx`, `cssLayers.test.js` | `screens.css` 불러오기, 층 계약 | 수정 (4) |
| `frontend/src/design/workspace.css`, `components/worklog-sheet.css` | 신청 표 칸 글자, 필터 창 입력 모서리 | 수정 (6) |
| legacy CSS 12개 | 일치할 수 없는 규칙 삭제 | 수정 (7) |
| `README.md`, `design.md`, spec, 결과 문서, 이 계획 | 마무리 | 수정 (8) |

---

### Task 0: 남은 화면 합성 자료와 정리 도구 (커밋 없음)

**Files (git 밖):**
- Create: `$TOOLS/rest_fixtures.py`
- Create: `$TOOLS/dead_css.py`
- Modify: `$TOOLS/preview.py`, `$TOOLS/capture.mjs`

- [ ] **Step 1: 기준 브랜치 확인**

```bash
git fetch origin
git log --oneline HEAD..origin/codex/demo-entry-public-20261109
```

Expected: 출력 없음. 커밋이 있으면 멈추고 사용자에게 보고한다.

- [ ] **Step 2: `rest_fixtures.py` 작성**

```python
"""5단계 남은 목록·홈 합성 화면. 합성 자료만 쓰며 실제 회사·사람 자료가 아니다."""


def fixtures(base):
    envelope, command = base.envelope, base.COMMAND
    employee = {'id': 'e-1', 'employeeNo': '24001', 'displayName': '김하늘'}
    days = [{'weekday': i + 1, 'workday': i < 5, 'startTime': '09:00' if i < 5 else '', 'endTime': '18:00' if i < 5 else '',
             'breaks': '12:00-13:00' if i < 5 else ''} for i in range(7)]
    result = {'status': 'REVIEW_REQUIRED', 'actualWorkMillis': None, 'confirmedBreakMillis': None, 'over8hMillis': None, 'leaveDays': 0,
              'inputRevision': 2, 'reviewRevision': 1, 'reasons': ['ACTUAL_WORK_UNCONFIRMED']}
    approval = {**base.REQUEST, 'version': 4, 'approverName': '박서준', 'reason': '가족 행사', 'originalRequestId': None}
    organizations = [{**org, 'headEmployeeNo': '22001' if org['id'] == 'o-1' else None, 'headEmployeeName': '박서준' if org['id'] == 'o-1' else None,
                      'headEligible': org['id'] == 'o-1', 'headApproverEligible': org['id'] == 'o-1'} for org in base.ORGS]
    return {
        '/admin/schedules': envelope('schedule/list', {
            'templates': [{'id': 's-1', 'code': 'FIXED_9_18', 'name': '기본 09-18', 'active': True, 'version': 4}],
            'form': {**command, 'code': '', 'name': '', 'validFrom': base.TODAY, 'days': days}, 'today': base.TODAY}),
        '/admin/networks': envelope('clock/networks', {
            'pageCommand': 'synthetic-close', 'form': {**command, 'network': '', 'label': '', 'wideConfirmed': False},
            'networks': [{'id': 'n-1', 'label': '본사', 'network': '192.0.2.0/24', 'validFrom': '2026-01-01T00:00:00Z', 'validTo': None, 'version': 1},
                         {'id': 'n-2', 'label': '이전 사무실', 'network': '198.51.100.0/24', 'validFrom': '2025-01-01T00:00:00Z',
                          'validTo': '2025-12-31T15:00:00Z', 'version': 2}]}),
        '/admin/leave-balances': envelope('request/balances', {
            'employee': employee, 'employees': [employee], 'buckets': [base.BUCKET],
            'form': {'employeeId': 'e-1', 'commandId': 'synthetic-opening', 'baseDate': '2026-01-01', 'usableFrom': '2026-01-01',
                     'usableTo': '2026-12-31', 'quantityDays': 15, 'sourceReference': '', 'reason': ''}}),
        '/admin/leave-balances/buckets/bucket-1': envelope('request/ledger', {
            'bucketId': 'bucket-1',
            'ledger': [{'id': 'entry-1', 'entryKind': 'OPENING', 'signedQuantityDays': 15, 'effectiveOn': '2026-01-01',
                        'sourceReference': '2026년 부여', 'reason': '연초 등록'}],
            'adjustment': {'expectedVersion': 6, 'commandId': 'synthetic-adjust', 'quantityDays': None, 'effectiveOn': base.TODAY,
                           'sourceReference': '', 'reason': ''}}),
        '/me/worktime': envelope('worktime/month', {
            'admin': True, 'employeeId': 'e-1', 'employees': [employee], 'from': '2026-11-01', 'to': '2026-11-30',
            'calculateCommand': 'synthetic-calc', 'exportCommand': 'synthetic-export', 'totalTime': '8:00:00.000', 'actualTimes': {},
            'overview': {'month': '2026-11',
                         'days': [{'id': 'result-1', 'workDate': '2026-11-03', 'current': True, 'result': result, 'ruleVersion': 'CALC-1', 'resultRevision': 1}],
                         'weeks': [{'from': '2026-11-02', 'to': '2026-11-09', 'current': True,
                                    'result': {'confirmedWorkMillis': 28800000, 'unconfirmedDays': 4, 'over40hMillis': None}}],
                         'summary': {'current': True, 'result': {'confirmedWorkMillis': 28800000, 'unconfirmedDays': 19, 'reasons': ['UNCONFIRMED']}}}}),
        '/admin/work-policies': envelope('worktime/policies', {
            'employees': [employee], 'employeeId': 'e-1',
            'policies': [{'id': 'p-1', 'revision': 1, 'validFrom': '2026-01-01', 'validTo': None, 'mondayWeekReviewed': True, 'basis': '취업규칙 주 기산점 조항'}],
            'assessments': [{'id': 'a-1', 'revision': 1, 'validFrom': '2026-01-01', 'validTo': None, 'generalRuleReviewed': True, 'basis': '통상 근무 검토'}],
            'policyForm': {'commandId': 'synthetic-policy', 'from': '2027-01-01', 'to': '2027-12-31', 'mondayWeekReviewed': False, 'basis': '', 'reason': ''},
            'assessmentForm': {'employeeId': 'e-1', 'commandId': 'synthetic-assessment', 'from': '2027-01-01', 'to': '2027-12-31',
                               'generalRuleReviewed': False, 'basis': '', 'reason': ''}}),
        '/admin/approval-route-assignments': envelope('org/approval-route-assignments', {'approvalRouteAssignments': {
            'today': base.TODAY, 'scopeKind': 'EMPLOYEE', 'employeeId': 'e-1', 'organizationId': None,
            'employees': [{**employee, 'active': True, 'version': 5}],
            'organizations': [{'id': 'o-1', 'code': 'PRODUCT', 'name': '제품개발팀', 'active': True, 'version': 3}],
            'versions': [{'id': 'v-2', 'routeId': 'route-1', 'code': 'PRODUCT_HEAD', 'name': '제품개발팀 조직장', 'active': True, 'revision': 2,
                          'approverKind': 'ORGANIZATION_HEAD', 'employeeId': None, 'employeeName': None}],
            'history': [], 'form': {'scopeKind': 'EMPLOYEE', 'employeeId': 'e-1', 'organizationId': None, 'routeVersionId': None,
                                    'validFrom': base.TODAY, 'expectedVersion': 5, 'reason': '', 'commandId': 'synthetic-assign'}}}),
        '/approvals': envelope('request/list', {'title': '결재 대기함', 'requests': [approval], 'completed': False, 'page': 0, 'hasNext': False}),
        '/company/organizations': envelope('company/organizations', {'companyOrganizations': {
            'asOf': base.AS_OF, 'today': base.TODAY, 'organizationId': 'o-1', 'q': '', 'page': 0, 'hasNext': False, 'organizations': organizations,
            'selectedOrganization': organizations[0],
            'employees': [{'id': 'e-1', 'employeeNo': '24001', 'employeeName': '김하늘', 'organizationName': '제품개발팀'}]}}),
    }
```

- [ ] **Step 3: `dead_css.py` 작성**

```python
"""Report (and with --delete, remove) legacy CSS rules whose selectors can never match current React markup.

A selector is dead when a class it requires (outside :not/:is/:where/:has/:nth-*) appears in no React source file.
AG Grid classes (ag-*) and built-class prefixes (`t-state-${...}`, 't-tone-' + x) count as present. A rule is dead
when every selector in its list is dead. Usage: python -I dead_css.py <repo> [--delete file ...] [--keep class ...]
"""
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
front = root / 'frontend' / 'src'
args = sys.argv[2:]


def option(flag):
    rest = args[args.index(flag) + 1:] if flag in args else []
    return rest[:next((i for i, arg in enumerate(rest) if arg.startswith('--')), len(rest))]


delete, keep = option('--delete'), set(option('--keep'))
REPORT = ['WorkspacePolish.css', 'ThemeSwitcher.css', 'components/DemoAccountSwitcher.css', 'components/EmployeeWorkspace.css',
          'components/WorkspaceNavigation.css', 'pages/ApprovalWorkspace.css', 'pages/DemoAccess.css', 'pages/DemoLanding.css',
          'pages/PersonalDetails.css', 'pages/RecordDetails.css', 'pages/RequestDetail.css', 'pages/RequestWorkspace.css',
          'pages/ScheduleCalendar.css', 'pages/WorkspaceHome.css', 'base.css', 'tokens.css']

words, prefixes = set(), {'ag-'}
for path in front.rglob('*'):
    if path.suffix in {'.tsx', '.ts'} and '.test.' not in path.name:
        text = path.read_text(encoding='utf-8')
        words.update(re.findall(r'[A-Za-z_][\w-]*', text))
        prefixes.update(re.findall(r'([A-Za-z_][\w-]*-)(?:\$\{|[\'"]\s*\+)', text))
words |= keep


def rules(text):
    """Yield (selector list, start, end) for innermost style rules outside @keyframes.
    start is just after the previous { } or top-level ;, so a deletion also drops the rule's leading comment."""
    blank = re.sub(r'/\*.*?\*/', lambda m: ' ' * len(m.group()), text, flags=re.S)
    stack, start = [], 0
    for index, char in enumerate(blank):
        if char == '{':
            stack.append((blank[start:index].strip(), start))
            start = index + 1
        elif char == '}':
            prelude, begin = stack.pop()
            body = blank[start:index]
            if '{' not in body and not prelude.startswith('@') and not any(p.startswith('@keyframes') for p, _ in stack):
                yield prelude, begin, index + 1
            start = index + 1
        elif char == ';' and not stack:
            start = index + 1


def required(selector):
    depth, outside = 0, ''
    for char in selector:
        depth += char == '('
        depth -= char == ')'
        if depth == 0 and char != ')':
            outside += char
    return re.findall(r'\.([A-Za-z_][\w-]*)', outside)


def dead(prelude):
    selectors = [part.strip() for part in prelude.split(',') if part.strip()]
    return bool(selectors) and all(any(name not in words and not any(name.startswith(p) for p in prefixes) for name in required(s)) for s in selectors)


total = [0, 0, 0, 0]
for name in REPORT:
    path = front / name
    text = path.read_text(encoding='utf-8')
    found = [(begin, end) for prelude, begin, end in rules(text) if dead(prelude)]
    count = sum(1 for _ in rules(text))
    gone = sum(end - begin for begin, end in found)
    total = [total[0] + count, total[1] + len(found), total[2] + gone, total[3] + len(text.encode('utf-8'))]
    print(f'{name}: rules {count}, dead {len(found)}, dead chars {gone}, size {len(text.encode("utf-8"))}')
    if name in delete and found:
        for begin, end in reversed(found):
            text = text[:begin] + text[end:]
        while True:
            pruned = re.sub(r'@(?:media|supports)[^{}]*\{\s*\}', '', text)
            if pruned == text:
                break
            text = pruned
        path.write_text(text, encoding='utf-8')
        print(f'  deleted {len(found)} rules')
print(f'TOTAL rules {total[0]}, dead {total[1]}, dead chars {total[2]}, size {total[3]}')
```

- [ ] **Step 4: 미리보기·캡처 연결**

`preview.py`의 `FIXTURES.update(workspace.fixtures(base, SCHEDULE))` 줄 아래에 다음을 더한다.

```python
rest_spec = importlib.util.spec_from_file_location('rest_fixtures', HERE / 'rest_fixtures.py')
rest = importlib.util.module_from_spec(rest_spec)
rest_spec.loader.exec_module(rest)
FIXTURES.update(rest.fixtures(base))
```

`capture.mjs`의 `sets`에서 `workspace` 묶음 뒤에 다음을 더한다.

```js
  rest: [
    ['schedule-list', '/admin/schedules'], ['networks', '/admin/networks'], ['balances', '/admin/leave-balances'],
    ['ledger', '/admin/leave-balances/buckets/bucket-1'], ['month', '/me/worktime'], ['policies', '/admin/work-policies'],
    ['route-assignments', '/admin/approval-route-assignments'], ['approvals', '/approvals'], ['org-chart', '/company/organizations'],
    ['clock-history', '/me/attendance'], ['home', '/me/home'],
  ],
```

- [ ] **Step 5: 서버 재시작과 기준 캡처**

켜져 있는 미리보기 서버를 멈추고 `python "$TOOLS/preview.py"`를 백그라운드로 다시 실행한다.

```bash
python -I "$TOOLS/dead_css.py" .
npm --prefix frontend run build
node "$TOOLS/capture.mjs" "$TOOLS/shots/stage5-before" http://127.0.0.1:18131 rest
```

Expected:
- 분석 마지막 줄이 `TOTAL rules …, dead …`를 출력한다. 이 값을 결과 문서의 "정리 전" 값으로 기록한다.
- 캡처는 `44 captures, 0 with JS errors`다. JS 오류 화면이 있으면 해당 테스트 파일의 자료 모양에 맞춰 합성 자료를 고친다.

---

### Task 1: 근무 템플릿 목록·허용 네트워크

**Files:**
- Modify: `frontend/src/pages/AdminPages.tsx` (`ScheduleListPage`, `NetworksPage`)
- Modify: `frontend/src/design/ui.css` (끝에 추가)

- [ ] **Step 1: 기준 테스트 실행**

Run: `npm --prefix frontend test -- src/pages/AdminPages.test.tsx`
Expected: PASS

- [ ] **Step 2: `ScheduleListPage` 구현**

`return (…)`을 아래로 바꾼다.

```tsx
  return (
    <div className="t-legacy-page admin-shell">
      <PageHeader title="고정 근무 템플릿" help="여러 템플릿을 만들고 사원별 적용일로 배정합니다. 과거 배정은 당시 불변 버전을 사용합니다." />
      <ErrorNotice message={m.error} />
      <section className="wl-section">
        <h2>등록된 템플릿</h2>
        {m.templates.length === 0 && <p>등록된 템플릿이 없습니다.</p>}
        <ul className="wl-rows">
          {m.templates.map((item) => (
            <li key={item.id}>
              <a href={`/admin/schedules/${item.id}`}>
                {item.code} · {item.name}
              </a>
            </li>
          ))}
        </ul>
      </section>
      <section className="wl-section">
        <h2>새 템플릿</h2>
        <Form action="/admin/schedules" method="post" className="wl-form">
          <ScheduleFields form={m.form} today={m.today} />
        </Form>
      </section>
    </div>
  );
```

- [ ] **Step 3: `NetworksPage` 구현**

`return (…)`을 아래로 바꾼다. 필드·hidden·문구는 그대로다.

```tsx
  return (
    <div className="t-legacy-page admin-shell">
      <PageHeader title="허용 IP 네트워크" help={<>
        <p>시각 기준: Asia/Seoul (서울)</p>
        <p>IP 일치는 현장 근로나 본인 존재를 보증하지 않습니다. 실제 접속 주소만 검증하며 전달 헤더로 우회할 수 없습니다.</p>
      </>} actions={<a className="t-btn" href="/admin/calendar">휴일 캘린더</a>} />
      <ErrorNotice message={m.error} />
      <section className="wl-section">
        <h2>허용망 이력</h2>
        {m.networks.length === 0 && <p>등록된 허용망이 없습니다.</p>}
        <ul className="wl-rows">
          {m.networks.map((network) => (
            <li key={network.id}>
              <div className="wl-rows-main">
                <h3>{network.label} · {network.network}</h3>
                <p>시작 {fmtDateTime(network.validFrom)} · {network.validTo ? `종료 ${fmtDateTime(network.validTo)}` : '현재 유효'}</p>
              </div>
              {!network.validTo && (
                <Form action={`/admin/networks/${network.id}/close`} method="post" className="wl-form wl-inline-form">
                  <Hidden name="commandId" value={m.pageCommand} />
                  <Hidden name="expectedVersion" value={network.version} />
                  <Field id={`close-${network.id}`} label="종료 사유" name="reason" maxLength={500} required />
                  <button type="submit" className="t-btn">허용 종료</button>
                </Form>
              )}
            </li>
          ))}
        </ul>
      </section>
      <section className="wl-section">
        <h2>허용망 추가</h2>
        <Form action="/admin/networks/changes" method="post" className="wl-form">
          <Hidden name="commandId" value={m.form.commandId} />
          <FormTable>
            <Field id="network" label="IPv4/IPv6 CIDR" name="network" value={m.form.network} placeholder="192.0.2.0/24" required maxLength={80} />
            <Field id="network-label" label="명칭" name="label" value={m.form.label} maxLength={120} required />
            <FormRow label="위험 검토">
              <Check name="wideConfirmed" label="광범위 허용 위험을 검토했습니다" checked={m.form.wideConfirmed} />
            </FormRow>
            <Reason id="network-reason" form={m.form} label="등록 사유" />
          </FormTable>
          <div className="wl-form-actions">
            <button type="submit" className="t-btn primary">허용망 저장</button>
          </div>
        </Form>
      </section>
    </div>
  );
```

- [ ] **Step 4: `ui.css`에 행 목록·한 줄 폼 추가**

파일 끝에 다음을 더한다.

```css
/* Short link/history lists: 2px top line, 1px row lines, main text left and an optional inline form right. */
.wl-rows { margin: 0 0 16px; padding: 0; border-top: 2px solid var(--g800); list-style: none; }
.wl-rows:empty { display: none; }
.wl-rows > li { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 6px 16px; min-height: var(--h-row); padding: 8px 12px; border-bottom: 1px solid var(--g200); font-size: 13px; }
.wl-rows > li > a { color: var(--link); text-underline-offset: 3px; }
.wl-rows-main { min-width: 0; }
.wl-rows-main :is(h3, p), .wl-rows > li > p { margin: 0; }
.wl-rows-main h3 { color: var(--g900); font: 600 13px/1.5 var(--font-ui); }
.wl-rows-main p, .wl-rows > li > p { color: var(--g600); font-size: 12.5px; }
.wl-inline-form { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.wl-inline-form > label { margin: 0; color: var(--g700); font: 500 12.5px/1.4 var(--font-ui); }
.wl-inline-form > .t-input { width: 220px; }
```

- [ ] **Step 5: 통과 확인과 커밋**

Run: `npm --prefix frontend test -- src/pages/AdminPages.test.tsx && npm --prefix frontend run build`
Expected: PASS, 빌드 성공

```bash
git add frontend/src/pages/AdminPages.tsx frontend/src/design/ui.css
git commit -m "feat: move the schedule template list and allowed networks to the document frame" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 기초 잔여·원장 조정·근로시간·계산 정책

**Files:**
- Modify: `frontend/src/pages/WorkflowPages.tsx` (`Balances`, `Ledger`, `Month`, `Policies`)

- [ ] **Step 1: 기준 테스트 실행**

Run: `npm --prefix frontend test -- src/pages/WorkflowPages.test.tsx`
Expected: PASS

- [ ] **Step 2: `Balances`**

`return (…)`을 아래로 바꾼다.

```tsx
  return (
    <>
      <PageHeader title="기초 잔여 관리" actions={<>
        <a className="t-btn" href="/admin/requests">고객사 신청·예외 현황</a>
        <a className="t-btn" href={`/admin/requests/exceptions/new?employeeId=${m.employee.id}`}>예외 정정 신청</a>
      </>} />
      <Form action="/admin/leave-balances" method="get" className="wl-querybar">
        <div>
          <SelectField label="사원" name="employeeId" value={m.employee.id}>
            <EmployeeOptions employees={m.employees} />
          </SelectField>
        </div>
        <div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div>
      </Form>
      <section className="wl-section">
        <h2>{m.employee.displayName} · 기초 묶음</h2>
        <ul className="wl-rows">
          {m.buckets.map((b) => (
            <li key={b.id}>
              <a href={`/admin/leave-balances/buckets/${b.id}`}>
                {bucketPeriod(b)} / 잔여 {b.balanceDays} <abbr title="일">DAY</abbr> / 대기 {b.pendingDays} <abbr title="일">DAY</abbr>
              </a>
            </li>
          ))}
        </ul>
      </section>
      <section className="wl-section">
        <h2>근거 있는 기초 등록</h2>
        <p className="hint">
          자동 발생이 아닙니다. 본인의 기초 등록·증감은 다른 담당자가 처리합니다. 소수 단위는
          지원하지 않습니다.
        </p>
        <Form action="/admin/leave-balances/entries" method="post" className="wl-form">
          {hidden('employeeId', f.employeeId)}
          {hidden('commandId', f.commandId)}
          <FormTable>
            <Field label="기초 기준일" name="baseDate" type="date" value={f.baseDate} required />
            <Field label="사용 가능 시작일" name="usableFrom" type="date" value={f.usableFrom} required />
            <Field label="사용 가능 종료일 (포함)" name="usableTo" type="date" value={f.usableTo ?? ''} />
            <Field label="기초 일수 (정수 DAY)" name="quantityDays" type="number" min="0" step="1" value={f.quantityDays ?? ''} required />
            <Field label="발생 근거 참조" name="sourceReference" maxLength={500} value={f.sourceReference} required />
            <Reason label="등록 사유" value={f.reason} />
          </FormTable>
          <div className="wl-form-actions"><button className="t-btn primary">기초 원장 등록</button></div>
        </Form>
      </section>
    </>
  );
```

- [ ] **Step 3: `Ledger` 조정 구역**

`<section className="panel">`부터 그 `</section>`까지(증감 조정)를 아래로 바꾼다.

```tsx
      <section className="wl-section">
        <h2>증감 조정</h2>
        <Form
          action={`/admin/leave-balances/buckets/${m.bucketId}/adjustments`}
          method="post"
          className="wl-form"
        >
          {hidden('expectedVersion', f.expectedVersion)}
          {hidden('commandId', f.commandId)}
          <FormTable>
            <Field label="조정 DAY (감액은 음수)" name="quantityDays" type="number" step="1" value={f.quantityDays ?? ''} required />
            <Field label="효력 기준일" name="effectiveOn" type="date" value={f.effectiveOn} required />
            <Field label="근거 참조" name="sourceReference" value={f.sourceReference} maxLength={500} required />
            <Reason label="조정 사유" value={f.reason} />
          </FormTable>
          <div className="wl-form-actions"><button className="t-btn primary">조정 원장 추가</button></div>
        </Form>
      </section>
```

- [ ] **Step 4: `Month` 하단 구역**

`Month`에서 아래 세 가지를 바꾼다.
- `<section className="panel">` 두 곳(월 요약, 주간 참고량)을 `<section className="wl-section">`으로 바꾼다.
- 주간 참고량의 `<ul>`을 `<ul className="wl-rows">`로 바꾼다.
- `{m.admin && (…)}` 구역을 아래로 바꾼다.

```tsx
      {m.admin && (
        <section className="wl-section">
          <h2>수동 재집계·검토용 Excel</h2>
          <p>
            <a href={`/admin/work-policies?employeeId=${m.employeeId}`}>주 기산점·적용 대상 검토</a>{' '}
            · <a href="/admin/audit">감사 이력</a>
          </p>
          <Form action="/admin/calculations" method="post" className="wl-form">
            {periodFields}
            {hidden('commandId', m.calculateCommand)}
            <FormTable><Field label="재집계 사유" name="reason" maxLength={500} required /></FormTable>
            <div className="wl-form-actions"><button className="t-btn">월과 경계 주 재집계</button></div>
          </Form>
          <Form action="/admin/reports/exports" method="post" className="wl-form">
            {periodFields}
            {hidden('commandId', m.exportCommand)}
            <FormTable>
              <Field label="검토용 출력 사유" name="reason" maxLength={500} required />
              <p>
                결과 ID·입력/규칙 버전·필터를 고정한 검토 스냅샷입니다. 자유 사유·이메일·IP는 포함하지
                않습니다.
              </p>
            </FormTable>
            <div className="wl-form-actions"><button className="t-btn">검토용 XLSX 다운로드</button></div>
          </Form>
        </section>
      )}
```

- [ ] **Step 5: `Policies`**

`return (…)`을 아래로 바꾼다.

```tsx
  return (
    <>
      <PageHeader title="적용 대상·주 기산점 검토" help="법률 적합성을 자동 인증하지 않습니다. 근거를 확인한 적용 대상과 기간만 명시하며 민감한 보호 사유는 수집하지 않습니다. 변경은 새 불변 버전입니다." />
      <Form action="/admin/work-policies" method="get" className="wl-querybar">
        <div>
          <SelectField label="대상 사원" name="employeeId" value={m.employeeId}>
            <EmployeeOptions employees={m.employees} />
          </SelectField>
        </div>
        <div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div>
      </Form>
      <section className="wl-section">
        <h2>사업장 주 기산점</h2>
        <ul className="wl-rows">
          {m.policies.map((r) => (
            <li key={r.id}>
              <div className="wl-rows-main">
                <h3>v{r.revision} · {fmtDate(r.validFrom)} ~ {r.validTo == null ? '종료 없음' : fmtDate(r.validTo)}</h3>
                <p>월요일 일치검토 {String(r.mondayWeekReviewed)} · {r.basis}</p>
              </div>
            </li>
          ))}
        </ul>
        <Form action="/admin/work-policies" method="post" className="wl-form">
          {hidden('commandId', p.commandId)}
          <FormTable>
            <Field label="적용 시작일" name="from" type="date" value={p.from} required />
            <Field label="종료일 (포함)" name="to" type="date" value={p.to} required />
            <FormRow label="월요일 확인">
              <Check label="실제 회사의 주 기산점이 월요일인 것을 확인했습니다" name="mondayWeekReviewed" checked={p.mondayWeekReviewed} />
            </FormRow>
            <TextAreaField label="검토 근거" name="basis" value={p.basis} maxLength={500} required />
            <Field label="등록 사유" name="reason" value={p.reason} maxLength={500} required />
          </FormTable>
          <div className="wl-form-actions"><button className="t-btn primary">기산점 검토 버전 저장</button></div>
        </Form>
      </section>
      <section className="wl-section">
        <h2>사원별 일반 규정 적용 검토</h2>
        <ul className="wl-rows">
          {m.assessments.map((r) => (
            <li key={r.id}>
              <div className="wl-rows-main">
                <h3>v{r.revision} · {fmtDate(r.validFrom)} ~ {r.validTo == null ? '종료 없음' : fmtDate(r.validTo)}</h3>
                <p>일반규정 검토 {String(r.generalRuleReviewed)} · {r.basis}</p>
              </div>
            </li>
          ))}
        </ul>
        <Form action="/admin/work-assessments" method="post" className="wl-form">
          {hidden('employeeId', a.employeeId)}
          {hidden('commandId', a.commandId)}
          <FormTable>
            <Field label="적용 시작일" name="from" type="date" value={a.from} required />
            <Field label="종료일 (포함)" name="to" type="date" value={a.to} required />
            <FormRow label="일반 규정 검토">
              <Check label="성인 통상 고정 근무의 일반 규정 적용을 검토했습니다" name="generalRuleReviewed" checked={a.generalRuleReviewed} />
            </FormRow>
            <p>
              단시간·보호 대상·특례·적용 제외 등 별도 검토가 필요하거나 미확인이라면 체크하지 마세요.
              구체적인 민감정보를 적지 않습니다.
            </p>
            <TextAreaField label="최소 검토 근거 참조" name="basis" value={a.basis} maxLength={500} required />
            <Field label="등록 사유" name="reason" value={a.reason} maxLength={500} required />
          </FormTable>
          <div className="wl-form-actions"><button className="t-btn primary">적용 검토 버전 저장</button></div>
        </Form>
      </section>
    </>
  );
```

- [ ] **Step 6: 통과 확인과 커밋**

Run: `npm --prefix frontend test -- src/pages/WorkflowPages.test.tsx src/pages/RecordDetails.test.tsx && npm --prefix frontend run build`
Expected: PASS, 빌드 성공

```bash
git add frontend/src/pages/WorkflowPages.tsx
git commit -m "feat: move leave balance, ledger adjustment, work time and policy forms to document tables" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 결재선 배정

**Files:**
- Modify: `frontend/src/pages/ApprovalRoutePages.tsx` (`RouteAssignments`)

- [ ] **Step 1: 기준 테스트 실행**

Run: `npm --prefix frontend test -- src/pages/ApprovalRoutePages.test.tsx`
Expected: PASS

- [ ] **Step 2: 구현**

`../ui` import에 `FormRow`, `FormTable`을 더한다. `RouteAssignments`에서 아래를 차례로 바꾼다. 컨트롤 속성은 그대로다.

1. 두 `<section className="t-card">`(선택한 대상 구역, 대상 미선택 안내)를 `<section className="wl-section">`으로 바꾼다.
2. `className="admin-form t-route-assignment-form"`을 `className="wl-form t-route-assignment-form"`으로 바꾼다.
3. `<label htmlFor="route-version">적용할 결재선 버전</label>`을 아래 두 줄로 바꾼다.

```tsx
          <FormTable>
          <FormRow label="적용할 결재선 버전" htmlFor="route-version" required>
```

4. `</select>`와 이어지는 `<label htmlFor="route-valid-from">적용일 (시작 포함·종료 제외)</label>`을 아래로 바꾼다.

```tsx
          </select>
          </FormRow>
          <FormRow label="적용일 (시작 포함·종료 제외)" htmlFor="route-valid-from" required>
```

5. 적용일 입력의 `onChange={event => update('validFrom', event.target.value)} />`와 이어지는 `<label htmlFor="route-assignment-reason">배정·해제 사유</label>`을 아래로 바꾼다.

```tsx
            onChange={event => update('validFrom', event.target.value)} />
          </FormRow>
          <FormRow label="배정·해제 사유" htmlFor="route-assignment-reason" required>
```

6. 사유 textarea의 `onChange={event => update('reason', event.target.value)} />` 다음에 아래 두 줄을 넣는다.

```tsx
          </FormRow>
          </FormTable>
```

7. `<div className="t-sheet-toolbar" role="group" aria-label="결재선 배정 편집">`을 `<div className="wl-form-actions" role="group" aria-label="결재선 배정 편집">`으로 바꾼다.
8. 그 아래 `<p className="t-subtle">오늘 이후에 적용하며`를 `<p className="hint">오늘 이후에 적용하며`로 바꾼다.

- [ ] **Step 3: 통과 확인과 커밋**

Run: `npm --prefix frontend test -- src/pages/ApprovalRoutePages.test.tsx src/App.modal.test.tsx && npm --prefix frontend run build`
Expected: PASS, 빌드 성공

```bash
git add frontend/src/pages/ApprovalRoutePages.tsx
git commit -m "feat: move the approval route assignment form to a document table" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 결재함·조직도 트리·오늘 출퇴근 줄

**Files:**
- Modify: `frontend/src/pages/OrgSheetPages.tsx` (`CompanyOrganizationsPage`)
- Create: `frontend/src/design/screens.css`
- Modify: `frontend/src/App.tsx`, `frontend/src/cssLayers.test.js`

- [ ] **Step 1: 층 계약 테스트 먼저**

`cssLayers.test.js`의 디자인 층 `it.each` 목록에 `'src/design/screens.css'`를 더한다.

Run: `npm --prefix frontend test -- src/cssLayers.test.js`
Expected: FAIL. 파일이 없다.

- [ ] **Step 2: 조직도 트리 구역**

`CompanyOrganizationsPage`의 `<section className="t-card"><h2>조직 계층</h2><p className="t-subtle">`을 `<section className="wl-section t-org-tree-panel"><h2>조직 계층</h2><p className="hint">`로 바꾼다.

- [ ] **Step 3: `design/screens.css` 작성과 연결**

`App.tsx`의 `import './design/workspace.css';` 아래에 `import './design/screens.css';`를 더한다. 파일 내용은 다음과 같다.

```css
/* Remaining T1 screens and T5 home (spec 8). Unlayered; markup keeps its classes, so these rules restyle the
   approval inbox, organization tree, today's clock strip and the home dashboard over legacy CSS. */

/* Approval inbox: page head, underline tabs, flat rows under a 2px line. */
.t-approval-workspace { color: var(--g900); }
.t-approval-pagehead { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin: 0 0 12px; padding: 0; border: 0; background: transparent; box-shadow: none; }
.t-approval-heading { display: flex; align-items: center; gap: 8px; }
.t-approval-heading h1 { margin: 0; color: var(--g900); font: 700 22px/1.35 var(--font-ui); letter-spacing: -.025em; }
.t-approval-pagehead .t-approval-icon, .t-approval-empty .t-approval-icon { display: none; }
.t-approval-btn { display: inline-flex; align-items: center; justify-content: center; min-height: var(--h-ctl); padding: 0 12px; border: 1px solid var(--g300); border-radius: var(--r-ctl); background: var(--g0); color: var(--g800); box-shadow: none; font: 600 13px/1.2 var(--font-ui); text-decoration: none; white-space: nowrap; }
.t-approval-btn:hover { border-color: var(--g400); background: var(--g50); }
.t-approval-btn.primary { border-color: var(--brand); background: var(--brand); color: var(--g0); }
.t-approval-nav { display: flex; gap: 0; margin: 0 0 16px; padding: 0; border: 0; border-bottom: 1px solid var(--g200); background: transparent; }
.t-approval-nav a { margin: 0 0 -1px; padding: 10px 14px; border: 0; border-bottom: 2px solid transparent; border-radius: 0; background: transparent; color: var(--g600); font: 600 13px/1.2 var(--font-ui); text-decoration: none; }
.t-approval-nav a[aria-current="page"] { border-bottom-color: var(--brand); color: var(--g900); }
.t-approval-intro { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 6px 12px; margin: 0 0 10px; padding: 0; border: 0; background: transparent; }
.t-approval-intro h2 { margin: 0; color: var(--g900); font: 700 15px/1.4 var(--font-ui); }
.t-approval-intro p { margin: 2px 0 0; color: var(--g600); font-size: 12.5px; }
.t-approval-count { color: var(--g600); font-size: 13px; }
.t-approval-list { display: grid; gap: 0; margin: 0; padding: 0; border-top: 2px solid var(--g800); list-style: none; }
.t-approval-card { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 20px; margin: 0; padding: 12px; border: 0; border-bottom: 1px solid var(--g200); border-radius: 0; background: var(--g0); box-shadow: none; }
.t-approval-card-top { display: flex; align-items: center; gap: 8px; min-width: 140px; }
.t-approval-kind { color: var(--g900); font: 700 13px/1.4 var(--font-ui); }
.t-approval-status { display: inline-flex; align-items: center; min-height: 22px; padding: 0 7px; border-radius: var(--r-ctl); background: var(--g100); color: var(--g700); font: 600 12px/1 var(--font-ui); }
.t-approval-status[data-status="PENDING"] { background: var(--warn-bg); color: var(--warn); }
.t-approval-status[data-status="APPROVED"] { background: var(--ok-bg); color: var(--ok); }
.t-approval-status[data-status="REJECTED"] { background: var(--danger-bg); color: var(--danger); }
.t-approval-person { display: flex; align-items: center; gap: 8px; min-width: 140px; }
.t-approval-avatar { display: inline-grid; place-items: center; width: 28px; height: 28px; border-radius: 50%; background: var(--g800); color: var(--g0); font-size: 12px; font-weight: 700; }
.t-approval-person h2 { margin: 0; color: var(--g900); font: 700 13px/1.4 var(--font-ui); }
.t-approval-person p { margin: 0; color: var(--g600); font-size: 12px; }
.t-approval-facts { display: flex; flex-wrap: wrap; gap: 4px 16px; margin: 0; }
.t-approval-facts dt { color: var(--g600); font-size: 12px; }
.t-approval-facts dd { margin: 0; color: var(--g900); font: 600 13px/1.4 var(--font-ui); font-variant-numeric: tabular-nums; }
.t-approval-reason { flex: 1 1 200px; min-width: 0; margin: 0; overflow: hidden; color: var(--g700); font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.t-approval-meta { display: flex; flex-wrap: wrap; gap: 4px 12px; color: var(--g600); font-size: 12.5px; }
.t-approval-meta strong { color: var(--g900); font-weight: 600; }
.t-approval-card > .t-approval-btn { margin-left: auto; }
.t-approval-empty { margin: 0; padding: 32px 12px; border: 0; border-top: 2px solid var(--g800); border-radius: 0; background: transparent; color: var(--g600); text-align: center; }
.t-approval-empty h2 { margin: 0 0 4px; color: var(--g900); font: 700 15px/1.4 var(--font-ui); }
.t-approval-empty p { margin: 0; font-size: 13px; }
.t-approval-pagination { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin: 12px 0 0; color: var(--g600); font-size: 12.5px; }
.t-approval-pagination div { display: flex; gap: 6px; }

/* Organization tree: a plain indented list with guide lines; the selected organization uses brand-tint. */
.t-org-tree-panel { margin: 0; }
.t-org-tree ul { margin: 0; padding: 0 0 0 14px; border-left: 1px solid var(--g150); list-style: none; }
.t-org-tree > ul { padding-left: 0; border-left: 0; }
.t-org-tree li { margin: 0; }
.t-org-tree summary { cursor: pointer; }
.t-org-tree a { display: inline-block; padding: 5px 8px; border-radius: var(--r-ctl); color: var(--g800); font-size: 13px; text-decoration: none; }
.t-org-tree a:hover { background: var(--g50); }
.t-org-tree a[aria-current="true"] { background: var(--brand-tint); color: var(--brand-ink); font-weight: 700; }

/* Today's clock strip on 출퇴근 기록. */
.t-clock-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px 16px; margin: 0 0 16px; padding: 12px 16px; border: 1px solid var(--g200); border-radius: var(--r-pop); background: var(--g0); box-shadow: none; }
.t-clock-actions strong { color: var(--g900); font: 700 14px/1.4 var(--font-ui); }
.t-clock-actions .t-muted { margin: 2px 0 0; color: var(--g600); font-size: 12.5px; }
.t-clock-buttons { display: flex; gap: 6px; }
```

- [ ] **Step 4: 통과 확인과 커밋**

Run: `npm --prefix frontend test -- src/cssLayers.test.js src/pages/OrgSheetPages.test.tsx src/pages/WorkflowPages.test.tsx && npm --prefix frontend run build`
Expected: PASS, 빌드 성공

```bash
git add frontend/src/pages/OrgSheetPages.tsx frontend/src/design/screens.css frontend/src/App.tsx frontend/src/cssLayers.test.js
git commit -m "feat: restyle the approval inbox, organization tree and today's clock strip" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 홈 (T5)

**Files:**
- Modify: `frontend/src/design/screens.css` (끝에 추가)

- [ ] **Step 1: 홈 CSS 추가**

위젯 설정과 마크업은 그대로 두고 모양만 바꾼다.

```css
/* Home (T5): greeting, a metrics strip and flat bordered panels; widget settings unchanged. */
.t-work-home { color: var(--g900); }
.t-work-home-welcome { display: flex; flex-wrap: wrap; align-items: flex-end; justify-content: space-between; gap: 12px 16px; margin: 0 0 20px; padding: 0; border: 0; background: transparent; box-shadow: none; }
.t-work-home-date { display: flex; align-items: center; gap: 6px; margin: 0; color: var(--g600); font-size: 13px; font-weight: 600; }
.t-work-home-date svg { width: 15px; height: 15px; }
.t-work-home-intro h1 { margin: 4px 0 0; color: var(--g900); font: 700 22px/1.35 var(--font-ui); letter-spacing: -.025em; }
.t-work-home-intro > p:not(.t-work-home-date) { margin: 2px 0 0; color: var(--g600); font-size: 13px; }
.t-work-home-actions { display: flex; flex-wrap: wrap; gap: 6px; }
.t-work-home-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin: 0 0 10px; }
.t-work-home-toolbar h2 { margin: 0; color: var(--g900); font: 700 15px/1.4 var(--font-ui); letter-spacing: -.02em; }
.t-btn.t-work-home-settings-trigger { display: inline-grid; place-items: center; width: var(--h-ctl); min-width: var(--h-ctl); height: var(--h-ctl); min-height: var(--h-ctl); padding: 0; border: 1px solid var(--g300); border-radius: var(--r-ctl); background: var(--g0); color: var(--g700); box-shadow: none; }
.t-btn.t-work-home-settings-trigger:hover:not(:disabled) { border-color: var(--g400); background: var(--g50); }
.t-work-home-local-note { margin: 0 0 10px; color: var(--g600); font-size: 12.5px; }
.t-work-home-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 0; margin: 0 0 16px; overflow: hidden; border: 1px solid var(--g200); border-radius: var(--r-pop); }
.t-work-home-metric { margin: 0; padding: 14px 16px; border: 0; border-left: 1px solid var(--g200); border-radius: 0; background: var(--g0); box-shadow: none; }
.t-work-home-metric:first-child { border-left: 0; }
.t-work-home-metric dt { display: flex; align-items: center; gap: 6px; color: var(--g600); font: 600 12.5px/1.4 var(--font-ui); }
.t-work-home-metric dd { margin: 4px 0 0; }
.t-work-home-metric strong { display: block; color: var(--g900); font: 700 22px/1.25 var(--font-ui); letter-spacing: -.02em; font-variant-numeric: tabular-nums; }
.t-work-home-metric small { color: var(--g600); font-size: 12px; }
.t-work-home-widget-icon { display: inline-grid; place-items: center; width: 18px; height: 18px; padding: 0; border: 0; border-radius: 0; background: transparent; color: var(--g600); }
.t-work-home-widget-icon svg { width: 15px; height: 15px; }
.t-work-home-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin: 0 0 16px; }
.t-work-home-panel { margin: 0; padding: 16px; border: 1px solid var(--g200); border-radius: var(--r-pop); background: var(--g0); box-shadow: none; }
.t-work-home-columns + .t-work-home-panel { margin-top: 0; }
.t-work-home-panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin: 0 0 12px; }
.t-work-home-panel-heading h2 { display: flex; align-items: center; gap: 6px; margin: 0; color: var(--g900); font: 700 15px/1.4 var(--font-ui); }
.t-work-home-panel-heading a { color: var(--link); font-size: 12.5px; font-weight: 600; text-decoration: none; }
.t-work-home-clock { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px 20px; margin: 0 0 10px; }
.t-work-home-clock dt { color: var(--g600); font-size: 12px; }
.t-work-home-clock dd { margin: 2px 0 0; color: var(--g900); font: 700 18px/1.3 var(--font-ui); font-variant-numeric: tabular-nums; }
.t-work-home-confirmation, .t-work-home-empty { margin: 0; color: var(--g600); font-size: 12.5px; }
.t-work-home-timeline, .t-work-home-recent { margin: 8px 0 0; padding: 0; border-top: 1px solid var(--g150); list-style: none; }
.t-work-home-timeline li, .t-work-home-recent li { padding: 8px 0; border-bottom: 1px solid var(--g150); font-size: 13px; }
.t-work-home-request-icon { display: none; }
.t-work-home-recent strong { font-size: 13px; font-weight: 600; }
.t-work-home-recent small { display: block; color: var(--g600); font-size: 12px; font-variant-numeric: tabular-nums; }
.t-work-home-badge { display: inline-flex; align-items: center; min-height: 22px; padding: 0 7px; border-radius: var(--r-ctl); background: var(--g100); color: var(--g700); font: 600 12px/1 var(--font-ui); }
.t-work-home-badge[data-tone="amber"] { background: var(--warn-bg); color: var(--warn); }
.t-work-home-badge[data-tone="green"], .t-work-home-badge[data-tone="recorded"] { background: var(--ok-bg); color: var(--ok); }
.t-work-home-week { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 0; border-top: 2px solid var(--g800); }
.t-work-home-week > * { min-width: 0; padding: 10px; border: 0; border-right: 1px solid var(--g150); border-bottom: 1px solid var(--g150); border-radius: 0; background: var(--g0); box-shadow: none; }
.t-work-home-week > :last-child { border-right: 0; }
.t-work-home-week-date { display: block; color: var(--g600); font-size: 12px; font-variant-numeric: tabular-nums; }
.t-work-home-week-shift strong { display: block; margin-top: 4px; color: var(--g900); font: 600 13px/1.4 var(--font-ui); font-variant-numeric: tabular-nums; }
@media (max-width: 900px) {
  .t-work-home-columns { grid-template-columns: minmax(0, 1fr); }
  .t-work-home-week { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 600px) {
  .t-work-home-metric { border-top: 1px solid var(--g200); border-left: 0; }
  .t-work-home-metric:first-child { border-top: 0; }
}
```

- [ ] **Step 2: 통과 확인과 커밋**

Run: `npm --prefix frontend test -- src/pages/WorkspaceHome.test.tsx src/App.test.tsx src/cssLayers.test.js && npm --prefix frontend run build`
Expected: PASS, 빌드 성공

```bash
git add frontend/src/design/screens.css
git commit -m "feat: restyle the home dashboard with a metrics strip and flat panels" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 앞 단계에서 넘긴 작은 항목

**Files:**
- Modify: `frontend/src/pages/PersonnelPage.tsx` (재직 상태 이력 구역)
- Modify: `frontend/src/design/workspace.css` (신청 표 칸 글자)
- Modify: `frontend/src/components/worklog-sheet.css` (필터 창 입력)

- [ ] **Step 1: 재직 상태 이력 구역**

`PersonnelPage.tsx`에서 아래 두 가지를 바꾼다.
- `className="t-card t-personnel-history"`를 `className="wl-section t-personnel-history"`로 바꾼다.
- 이어지는 `<p className="t-muted">같은 적용일의`를 `<p className="hint">같은 적용일의`로 바꾼다.

- [ ] **Step 2: 신청 표 칸 글자 (4단계 넘김)**

`workspace.css`에는 `@media (min-width: 601px)` 블록이 두 개 있다. 그중 `:is(.t-personal-table, .t-request-board, …) table`로 시작하는 표 규칙 블록(두 번째)의 안쪽 끝에 다음을 더한다.

```css
  .t-request-board td :is(span, small, p, a) { font-size: 13px; line-height: 1.5; }
  .t-request-board td small, .t-request-board .t-request-reason { color: var(--g600); font-size: 12.5px; }
  .t-request-board .t-request-title { font-weight: 600; }
```

- [ ] **Step 3: 필터 창 입력 모서리 (2단계 넘김)**

`worklog-sheet.css`의 `.w-sheet-grid :is(input, select, textarea, button, a):focus-visible …` 줄 아래에 다음을 더한다.

```css
.w-sheet-grid :is(.ag-filter, .ag-menu) :is(.ag-input-field-input, .ag-picker-field-wrapper) { border-color: var(--field-line); border-radius: var(--r-ctl); box-shadow: none; }
.w-sheet-grid :is(.ag-filter, .ag-menu) :is(.ag-input-field-input, .ag-picker-field-wrapper):focus-within { border-color: var(--brand); outline: 2px solid var(--brand); outline-offset: 0; }
```

- [ ] **Step 4: 통과 확인과 커밋**

Run: `npm --prefix frontend test -- src/pages/PersonnelPage.test.tsx src/sheetAssets.test.ts src/cssLayers.test.js && npm --prefix frontend run build`
Expected: PASS, 빌드 성공

```bash
git add frontend/src/pages/PersonnelPage.tsx frontend/src/design/workspace.css frontend/src/components/worklog-sheet.css
git commit -m "fix: flatten the personnel status history and tidy request table text and filter inputs" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 쓰지 않는 legacy 규칙 삭제

**Files:**
- Modify (지울 대상 12개):
  - `frontend/src/WorkspacePolish.css`, `frontend/src/ThemeSwitcher.css`
  - `components/EmployeeWorkspace.css`, `components/WorkspaceNavigation.css`, `components/DemoAccountSwitcher.css`
  - `pages/ApprovalWorkspace.css`, `pages/PersonalDetails.css`, `pages/RecordDetails.css`
  - `pages/RequestDetail.css`, `pages/RequestWorkspace.css`, `pages/ScheduleCalendar.css`, `pages/WorkspaceHome.css`
- Modify (사용처 0인 별칭만): `frontend/src/design/tokens.css`

- [ ] **Step 1: 정리 전 전체 캡처**

```bash
npm --prefix frontend run build
for set in stage1 sheets modals workspace rest; do node "$TOOLS/capture.mjs" "$TOOLS/shots/stage5-pre/$set" http://127.0.0.1:18131 $set; done
python -I "$TOOLS/dead_css.py" . | tail -1
grep -o '#tna-app' src/main/resources/static/app.css frontend/src/*.css frontend/src/pages/*.css frontend/src/components/*.css | wc -l
```

Expected: 다섯 묶음 모두 JS 오류 0이다(24+28+68+28+44 = 192장). 분석 마지막 줄과 `#tna-app` 수를 "정리 직전" 값으로 기록한다. Step 5에서 같은 명령으로 다시 잰다.

- [ ] **Step 2: 삭제**

```bash
python -I "$TOOLS/dead_css.py" . --delete WorkspacePolish.css ThemeSwitcher.css components/EmployeeWorkspace.css components/WorkspaceNavigation.css components/DemoAccountSwitcher.css pages/ApprovalWorkspace.css pages/PersonalDetails.css pages/RecordDetails.css pages/RequestDetail.css pages/RequestWorkspace.css pages/ScheduleCalendar.css pages/WorkspaceHome.css
git diff --stat
```

Expected: 파일마다 `deleted N rules`를 출력하고, 삭제만 있는 diff가 나온다.

- [ ] **Step 3: 테스트·빌드**

Run: `npm --prefix frontend test && npm --prefix frontend run build`
Expected: 전체 통과(`cssLayers.test.js`가 파일이 계속 `@layer legacy`로 감싸져 있는지 확인), 빌드 성공

- [ ] **Step 4: 픽셀 비교**

```bash
for set in stage1 sheets modals workspace rest; do node "$TOOLS/capture.mjs" "$TOOLS/shots/stage5-post/$set" http://127.0.0.1:18131 $set; node "$TOOLS/compare.mjs" "$TOOLS/shots/stage5-pre/$set" "$TOOLS/shots/stage5-post/$set"; done
```

Expected: 다섯 묶음 모두 `N compared, 0 different`다.

차이가 있으면 아래 순서로 처리한다.
1. 해당 화면이 쓰는 파일만 `git checkout -- <파일>`로 되돌린다.
2. 원인 클래스를 찾는다(예: 동적으로 만든 클래스).
3. `--keep <클래스>`를 더해 다시 지운다.
4. 비교가 0이 될 때까지 Step 2~4를 반복한다.

- [ ] **Step 5: 별칭 정리와 남은 양 기록**

별칭은 `design/tokens.css` 22~34행에 한 줄에 여러 개씩 있다. 첫 명령은 사용처가 0인 별칭만 출력한다.

```bash
for alias in $(grep -oE -- '--(t-[a-z0-9-]+|font-body|font-display|radius-[a-z]+)\s*:' frontend/src/design/tokens.css | tr -d ' :' | sort -u); do printf '%s %s\n' "$alias" "$(grep -rhoE --include='*.css' --include='*.tsx' -e "var\($alias[,)]" frontend/src src/main/resources/static | wc -l)"; done | awk '$2 == 0'
python -I "$TOOLS/dead_css.py" . | tail -1
grep -o '#tna-app' src/main/resources/static/app.css frontend/src/*.css frontend/src/pages/*.css frontend/src/components/*.css | wc -l
```

- 출력된 별칭이 있으면 `design/tokens.css`의 해당 줄에서 그 `--이름: 값;` 부분만 지우고 Step 3·4를 다시 실행한다.
- 출력값(남은 규칙 수, 크기, `#tna-app` 수, 별칭 사용 수)은 결과 문서에 기록한다.

- [ ] **Step 6: 커밋**

```bash
git add frontend/src
git commit -m "refactor: delete legacy CSS rules no current markup can match" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 마무리 검증과 문서

- [ ] **Step 1: 전체 자동 검증**

```bash
npm --prefix frontend test
npm --prefix frontend run build
node "$TOOLS/workspace-check.mjs" "$TOOLS/shots/stage5-check"
node "$TOOLS/modal-check.mjs" "$TOOLS/shots/stage5-check"
node "$TOOLS/sheet-check.mjs" "$TOOLS/shots/stage5-check"
```

Expected: 전체 통과, 빌드 성공, 세 검사 PASS

- [ ] **Step 2: `mvnw.cmd test` (설계 10절)**

PowerShell에서 저장소 루트로 이동해 `.\mvnw.cmd -q test`를 백그라운드로 실행한다(시간이 오래 걸린다). 통과·실패·실행 불가(환경 사유)를 결과 문서에 그대로 적는다.

- [ ] **Step 3: 눈으로 확인과 비교 이미지**

`$TOOLS/shots/stage5-post/rest`를 1440·390px, 차콜·블루로 확인한다. 고칠 것이 있으면 작업 단위로 고치고 `fix:` 커밋하며, 픽셀 비교는 다시 하지 않는다(의도한 변경).

`pair.mjs`로 `stage5-before`와 `stage5-post/rest`의 아래 화면을 나란히 붙여 보낸다.
- `home-charcoal-1440`
- `approvals-charcoal-1440`
- `policies-blue-1440`
- `networks-charcoal-1440`

- [ ] **Step 4: 문서**

- **README:** `- **Project Status:**` 다음 줄에 아래 항목을 넣는다(값은 실제 결과로 채운다).

```markdown
- 공통 디자인 시스템 (2026-11-10~11, 개발본 `claude/worklog-design-system-20261110`). Pretendard·토큰·legacy 층 위에 공통 시트, 공통 모달·문서형 화면, 내 업무 캘린더와 사원 맥락 줄을 적용하고 쓰지 않는 legacy CSS 규칙을 지웠습니다. 프런트엔드 테스트·타입·빌드와 차콜·블루 1440·390px 합성 화면 검증을 통과했습니다. 공개 배포는 별도입니다. [설계](docs/superpowers/specs/2026-11-10-worklog-design-system-design.md)·[검증 결과](docs/quality/worklog-design-system-20261110.md)
```

- **설계 문서:** 머리의 `- 상태: 사용자 방향 승인(시안 v1과 아래 결정). 구현 전.`을 `- 상태: 1~5단계 구현 완료(2026-11-11, 개발본). 공개 배포 전.`으로 바꾼다. 9절 5단계 행 아래에 설계 보정 3·4번(정리 범위, 별칭)을 한 줄로 더한다.
- **`design.md`:** `## CSS 층` 절에 "legacy 정리는 소스에 없는 클래스를 요구하는 규칙만 지운다. 새 층에 가려진 규칙과 `app.css`는 남기며 남은 양은 결과 문서에 기록한다."를 더한다.
- **결과 문서:** `## 5단계 정리 (날짜)` 절에 아래를 적는다.
  - 테스트·빌드, 캡처·픽셀 비교, 검사 결과
  - `mvnw.cmd test` 결과
  - 정리 전후 legacy 양(규칙·크기·`#tna-app`·별칭)
  - 커밋, 계획과 달라진 점, 남은 일, 실행하지 않은 검증
  - 끝에 `## 전체 요약` 절을 두고 1~5단계 커밋 범위와 최종 수치를 정리한다.
- **이 계획:** 끝에 실행 기록을 더한다.

- [ ] **Step 5: 커밋과 Push 여부 확인**

```bash
git add README.md design.md docs/superpowers/specs/2026-11-10-worklog-design-system-design.md docs/quality/worklog-design-system-20261110.md docs/superpowers/plans/2026-11-11-worklog-design-stage5-cleanup.md
git commit -m "docs: record the WorkLog design system cleanup and close the project" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

사용자에게 5단계 커밋을 Push할지 묻는다. 공개 데모 배포와 PR 생성은 하지 않고, 필요하면 별도로 묻는다.

## 실행 기록 (2026-11-11)

- 0번: 기준 브랜치에 새 커밋이 없었다. 남은 화면 44장을 찍었고 JS 오류 0이었다.
- 1–3번: 계획대로 구현했다(`2f75ad0`, `c69b1db`, `4a50c74`). 화면 확인 뒤 폼 바로 아래 안내 글자를 고쳤다(`fb33da4`).
- 4–5번: 계획의 CSS에 화면 확인 결과를 더해 커밋했다(`a65111c`, `5219538`).
  - 결재함 목록 테두리·아바타 폭·버튼 줄바꿈
  - 조직도 트리 조직장 이름 줄바꿈
  - 홈 주간 칸·최근 신청 행·휴대폰 배치
- 6번: 계획대로 구현했다(`c213f94`).
- 7번: 계획대로 실행했다(`c3a5aea`).
  - 규칙 164개와 별칭 1개를 지웠다.
  - 픽셀 비교에서 192장 중 7장이 1픽셀·1단계 차이였고, 같은 빌드 재촬영에서도 나오는 렌더링 잡음으로 판단했다.
- 8번: 다음을 확인하고 기록했다.
  - 전체 516개 테스트 통과, 빌드 성공, 검사 3종 PASS
  - `mvnw.cmd test`는 JDK 25가 없어 실행하지 못했다.
  - 화면 확인 뒤 항목명 필수 점을 고쳤다(`d97d914`).
  - `App.requestModal` 간헐 실패가 5단계 이전부터 있었음을 확인했다.
  - 문서를 반영했다.
