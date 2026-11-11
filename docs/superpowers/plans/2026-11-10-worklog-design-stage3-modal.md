# WorkLog 디자인 시스템 3단계(공통 모달·문서형 화면) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공통 모달을 크기·머리·하단·모바일 시트 규칙으로 다시 꾸민다. 문서형 부품(문서형 표·정보 격자·결재란·안내)을 만들어 신청 작성, 신청 상세(결재 처리), 정정 신청과 관리·기록 문서형 화면(T3)에 적용한다.

**Architecture:**

- `components/Modal.css`를 legacy 층에서 꺼내 층 없이 다시 쓴다.
  - 크기는 `s`·`m`·`l`·`xl`이고 기본은 `l`이다.
  - `footer={false}`를 주면 하단을 그리지 않는다.
- `ui.tsx`에 `FormTable`·`FormRow`를 더한다.
  - `FormTable` 안의 `Field`·`SelectField`·`TextAreaField`는 항목명·값 칸 한 행으로 그린다. 표 밖에서는 지금과 같다.
  - 읽기 전용 `dt`·`dd` 목록은 `dl.wl-form-table`로 같은 모양을 쓴다.
- 결재란 `ApprovalStamps`와 안내 `Notice`를 새 부품으로 둔다. 정보 격자는 `.wl-info-grid` CSS만 둔다.
- 신청 작성은 단계·미리보기·초점 동작을 그대로 둔다.
  - 결재선 표는 결재란으로 바꾼다.
  - 동작 줄은 폼 안에 둔 채 스크롤 영역 아래에 붙인다(`position: sticky`).
  - 업무 모달로 열 때는 공통 하단을 그리지 않는다.
- 관리·기록 화면은 legacy `admin-form`·`panel`·`actions` 대신 `wl-form`·`wl-section`·`wl-form-actions`를 쓴다. legacy 규칙이 닿지 않게 하기 위해서다. 쓰지 않게 된 legacy 규칙은 5단계에서 지운다.

**Tech Stack:** React 19.3.1, TypeScript 6.0.3, Vite 8.3.5, Vitest 5.0.5 + Testing Library(jsdom 26.1.0), AG Grid Community 36.2.0(legacy CSS 테마). 검증 전용 도구는 Python 3 `http.server`와 `puppeteer-core`(로컬 Chrome)이며 git 밖에 둔다.

**Spec:** [설계](../specs/2026-11-10-worklog-design-system-design.md) 9절 3단계, 7.7·7.8절, 8절 T3 행. 2단계 결과는 [검증 결과](../../quality/worklog-design-system-20261110.md)의 2단계 절에 있다.

---

## 작업 위치와 공통 규칙

- **작업트리:** `C:\Users\ihpark\dev\vibe-attendance\target\worklog-design-20261110`, 브랜치 `claude/worklog-design-system-20261110`. 2단계 커밋은 2026-11-10 사용자 요청으로 `origin`에 Push했다(`3b81d7a`).
- **명령 실행:** 모든 명령은 Git Bash에서 실행한다. 아래 두 줄을 먼저 실행한다.
  ```bash
  cd /c/Users/ihpark/dev/vibe-attendance/target/worklog-design-20261110
  TOOLS=/c/Users/ihpark/dev/vibe-attendance/target/hallmark-tools/worklog-design-20261110
  ```
- **검증 도구:** `$TOOLS`는 git 밖이며 커밋하지 않는다. `preview.py`(127.0.0.1:18131), `capture.mjs`, `sheet-check.mjs`를 이어서 쓴다.
- **행 번호:** 계획에 적은 행 번호는 작성 시점(`3b81d7a`) 기준이다. 앞 작업에서 같은 파일을 고치면 번호가 밀리므로, 함수 이름과 인용한 코드로 위치를 찾는다.
- **테스트:** 전체는 `npm --prefix frontend test`, 파일 하나는 `npm --prefix frontend test -- <frontend 기준 경로>`이다.
- **빌드:** `npm --prefix frontend run build`(tsc 검사 후 vite 빌드).
- **커밋:** 작업마다 커밋하고, 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`를 붙인다.
- **Push·배포:** 3단계를 마친 뒤 Push 여부를 사용자에게 묻는다. 공개 데모 배포는 하지 않는다.
- **Java:** 이 단계는 Java 코드를 바꾸지 않는다.
- **jsdom 제약:**
  - jsdom 26에는 `HTMLDialogElement.showModal`·`close`가 없다. 모달을 여는 테스트 파일은 기존 파일처럼 `beforeEach`에서 두 메서드를 정의한다.
  - Vitest는 CSS를 읽지 않는다. 그래서 CSS 변경은 11번 작업의 합성 화면 캡처로 확인한다.
- **지켜야 하는 테스트 계약:** 아래는 바꾸지 않는다.
  - **공통 모달:** `dialog.t-modal`, `aria-labelledby`가 가리키는 `h2.t-modal-title`(tabIndex -1), X 버튼 `.t-modal-close`(이름 "닫기", 첫 초점 대상), 처리 중 잠금, 배경 클릭 무시, 초점 복귀 순서.
  - **신청 작성:** 다음 이름과 위치를 유지한다.
    - region "결재선"·"신청자"·"신청 내용"(CONTENT 단계)·"신청 미리보기"·"현재 정정 근거"
    - complementary "신청 요약": 폼 안에 두고, 단계가 바뀌어도 같은 노드다.
    - navigation "신청 작성 단계"(`aria-current="step"`)
    - form "신청서", group "신청 기간"·"사용할 연차"
    - 순서: 결재선 → 신청자 → 신청 내용, 신청 요약 → 미리보기
    - `미리보기`·`신청 제출`은 폼 안에 둔다.
    - 자체 dialog의 X는 "신청 창 닫기"이고 [닫기]는 하나다.
    - 업무 모달에 넣은 화면에는 dialog·"근태 신청" 제목·[닫기]가 없다.
    - role=alert는 한 번에 하나만 둔다.
  - **신청 상세:** 다음을 유지한다.
    - 클래스 `.t-request-detail-page`, `.t-request-detail-timeline`
    - 처리 이력마다 `<time>` 하나
    - `aria-label="현재 상태"`, 글자 "현재 결재 담당 {이름}"·"{코드} · {이름} · v{개정}"
    - form "승인 검토"·"반려 검토", h2 "결재 검토", h3 "승인"·"반려"
    - 정정·취소 신청에는 " DAY" 글자를 두지 않는다.
  - **관리 화면:** 다음을 유지한다.
    - 라벨 글자의 정확 일치 조회(`getByLabelText('이름')` 등)
    - 역할 hidden 입력이 체크 상자보다 앞에 오는 순서
    - 근무 템플릿 상세의 `table` 1개
    - 인사정보 화면의 첫 `form`
    - 감사 상세에는 `svg`가 없어야 한다.
    - 계산 근거의 `dt` 바로 뒤에 `dd`가 온다.

## 설계 보정 (설계 문서와 다르게 구현하는 결정)

12번 작업에서 설계 문서에 반영한다.

1. **결재선 표 → 결재란.** `AnnualRequestPage.test.tsx`의 "결재 정보" 표 단언 두 곳(71–73행, 419행)을 결재란 목록 단언으로 바꾼다. 결재 경로 이름, 결재 담당 이름, 상태 문구는 결재란 안에 그대로 남는다.
2. **신청 작성의 동작 버튼은 모달 하단으로 옮기지 않는다.**
   - 옮기지 않는 이유: `신청 제출`은 폼 안에 있어야 하고(`App.requestModal.test.tsx` 100행), `미리보기`는 신청 요약 뒤·신청 내용 안에 있어야 한다(`AnnualRequestPage.test.tsx` 90행).
   - 대신 동작 줄을 폼 안에 두고 스크롤 영역 아래에 붙인다.
   - 업무 모달로 열면 공통 [닫기] 하단을 그리지 않는다(`footer={false}`).
   - 자체 dialog(주소로 바로 연 경우)는 [닫기] 줄을 남긴다. 테스트가 그 [닫기] 하나를 요구한다.
3. **필수 표시는 항목명 뒤 점(CSS)만 둔다.** 설계 7.8의 "스크린리더용 '필수'" 글자는 넣지 않는다. 이유는 두 가지다.
   - 컨트롤의 `required`가 이미 필수로 읽힌다.
   - 항목명에 글자를 더하면 `getByLabelText('이름')` 같은 정확 일치 조회가 깨진다.
4. **모바일 모달:** 아래에 붙는 시트로 바꾸고, 높이는 내용만큼(최대 화면 높이 − 16px)으로 한다. 문서형 L 모달은 내용이 길어 사실상 전체 높이가 되고, S 확인 창은 짧은 시트로 남는다.
5. **신청 상세의 결재란:** 새 구역 "결재 진행"에 현재 결재 담당과 현재 상태를 표시한다. "제출 당시 결재선" 구역은 제출 당시 해석을 그대로 보여 준다. 재할당 뒤에도 결재란이 현재 담당을 맞게 보여 주기 위해서다.
6. **묶음·체크 행의 항목명:** 아래 짧은 항목명을 새로 둔다. 컨트롤 자체의 이름은 바꾸지 않는다.

   | 화면 | 항목명 | 행에 담는 컨트롤 |
   | --- | --- | --- |
   | 사원 | 재직 기간 | 입사일·마지막 재직일 |
   | 사원·조직 | 상태 | 활성 사원·활성 조직 |
   | 계정 | 역할 | 공통·승인자·근태 담당자·대표 |
   | 근무 템플릿 | 요일별 근무 | 요일별 표 |
   | 귀속 | 근거 확인 | 단일 귀속연도 확인 |
   | 출퇴근 확인 | 본인 확인 | 근로·휴게 확인 |
   | 정정 | 실제 확인, 휴게 구간 | 확인 체크, 휴게 편집 |
7. **이력 시트 제목:** 바로 위에 구역 제목이 있는 이력 시트는 지금처럼 건수만 표시한다(소속 이력, 버전별 근무, 배정 이력, 조직장 이력, 당시 원천·유효 출퇴근). 바로 위에 제목이 없는 두 곳(승인자 배정 이력, 원천 출퇴근 기록)에만 `title`을 준다.
8. **1단계에서 생긴 표시 오류를 고친다.**
   - 기본 버튼 규칙(층 없음)이 기간 달력의 날짜 버튼(`class="t-btn"`)에도 적용되어, legacy의 선택·오늘 표시가 지워졌다.
   - 4번 작업에서 날짜 버튼의 선택·오늘 상태를 새 층에 다시 정의한다.
9. **화면 머리:** legacy 제목·링크 묶음(`<a>…목록으로</a><h1>`)은 `PageHeader`로 바꾸고, 설명 문장은 도움말로 옮긴다.
   - 감사 상세에는 도움말을 두지 않는다. `svg`가 없어야 한다는 테스트 때문이다.
   - 모달 안의 화면 제목은 18px로 줄인다. 모달 제목보다 커 보이지 않게 하기 위해서다.
10. **하지 않는 것:**
    - `schedule/form`·`clock/day`는 서버가 독립 화면으로 내보내지 않는다(`ReactPageViewResolver` 미등록). 실제로 쓰는 `ScheduleFields`·`AttendanceRecord`만 고친다.
    - 신청 작성 단계(유형 → 내용 → 확인)와 미리보기 요청은 바꾸지 않는다. 시안의 "잔여 연차 9일 → 신청 후 7일"은 미리보기 전 계산이 필요해 넣지 않는다.
    - 다음은 모양을 바꾸지 않는다.
      - 귀속 이력 표, 인사정보의 재직 상태 이력 표, 사원사진 올리기
      - 연차 묶음 선택의 열 구성, 휴게 편집기
    - `EmployeesPage`·`OrganizationsPage`(도달하지 않는 화면)는 손대지 않는다. 다만 공용 필드 helper가 바뀌므로 두 화면의 필드도 문서형 표로 보인다.

## 파일 구조

| 파일 | 책임 | 작업 |
| --- | --- | --- |
| `frontend/src/components/Modal.tsx`, `Modal.css`, `Modal.test.tsx` | 크기 기본값 `l`, `footer={false}`, 머리·본문·하단·모바일 시트(층 없음) | 수정·다시 씀 (1) |
| `frontend/src/components/DemoAccountSwitcher.tsx`, `pages/WorkspaceHome.tsx` | 모달 크기 `s`·`m`, 하단 버튼의 옛 변형 클래스 제거 | 수정 (1) |
| `frontend/src/cssLayers.test.js` | `Modal.css`·`design/request.css` 층 계약 | 수정 (1, 4) |
| `frontend/src/ui.tsx`, `ui.formTable.test.tsx` | `FormTable`·`FormRow`, 표 안 필드의 행 렌더링 | 수정·생성 (2) |
| `frontend/src/design/ui.css` | 문서형 표, `wl-form`·`wl-section`·`wl-form-actions`, 결재란·안내·정보 격자 | 수정 (2, 3) |
| `frontend/src/components/ApprovalStamps.tsx`, `.test.tsx` | 결재란 | 생성 (3) |
| `frontend/src/components/Notice.tsx`, `.test.tsx` | 안내(아이콘 + 한 문장) | 생성 (3) |
| `frontend/src/design/request.css` | 신청 작성·신청 상세·정정 신청 배치 | 생성 (4), 수정 (5, 6) |
| `frontend/src/App.tsx`, `App.requestModal.test.tsx` | 신청 작성 모달 하단 없음, `request.css` 불러오기, 상세 모달 회귀 | 수정 (4, 5) |
| `frontend/src/pages/AnnualRequestPage.tsx`, `.test.tsx` | 신청 작성(4), 귀속 관리(8) | 수정 |
| `frontend/src/pages/WorkflowPages.tsx`, `.test.tsx` | 신청 상세(5), 정정 신청(6), 기록 상세(10) | 수정 |
| `frontend/src/pages/AdminPages.tsx` | 사원·조직·계정(7), 근무 템플릿·배정(8) | 수정 |
| `frontend/src/pages/OrgSheetPages.tsx` | 조직장 배정 | 수정 (8) |
| `frontend/src/pages/PersonnelPage.tsx`, `PersonalDetails.tsx` | 인사정보 입력 | 수정 (9) |
| `design.md`, spec, `docs/quality/worklog-design-system-20261110.md`, 이 계획 | 기준·결과 문서 | 수정 (12) |

---

### Task 0: 모달 합성 화면과 기준 캡처 (커밋 없음)

미리보기 서버(127.0.0.1:18131)는 2단계부터 켜져 있다. 모달 화면용 합성 자료를 더해 서버를 다시 띄우고, 바꾸기 전 화면을 기준으로 캡처한다. 업무 모달은 배경 화면을 연 뒤 링크를 눌러 연다. 그래야 실제처럼 공통 모달 안에 화면이 들어간다.

**Files (git 밖):**
- Create: `$TOOLS/modal_fixtures.py`
- Modify: `$TOOLS/preview.py`
- Modify: `$TOOLS/capture.mjs`
- Create: `$TOOLS/modal-check.mjs`

- [ ] **Step 1: 기준 브랜치 후속 변경 확인**

```bash
git fetch origin
git log --oneline HEAD..origin/codex/demo-entry-public-20261109
```

Expected: 출력이 없다. 커밋이 보이면 진행을 멈추고 목록을 사용자에게 보고한다. 병합은 사용자 확인 후에만 한다(설계 11절).

- [ ] **Step 2: `modal_fixtures.py` 작성**

```python
"""3단계 공통 모달·문서형 화면 합성 자료. 합성 자료만 쓰며 실제 회사·사람 자료가 아니다."""


def fixtures(base):
    envelope = base.envelope
    command = base.COMMAND
    events = [{'id': 'in-1', 'kind': 'IN', 'occurredAt': '2026-11-03T00:02:11.120Z'},
              {'id': 'out-1', 'kind': 'OUT', 'occurredAt': '2026-11-03T09:05:40.500Z'}]
    clock_day = {'employeeId': 'e-1', 'workDate': '2026-11-03', 'events': events, 'effectiveEvents': events,
                 'inputRevision': 2, 'reviewRevision': 1, 'dayKind': 'WORKDAY', 'scheduleVersionId': 'v-1',
                 'calendarVersionId': 'cal-1', 'confirmation': None,
                 'schedule': {'workday': True, 'startTime': '09:00:00', 'endTime': '18:00:00',
                              'breaks': [{'startTime': '12:00:00', 'endTime': '13:00:00'}]}}

    request = {**base.REQUEST, 'version': 4, 'status': 'PENDING', 'effectStatus': 'NONE', 'approverName': '박서준',
               'reason': '가족 행사', 'originalRequestId': None}
    detail = envelope('request/detail', {
        'request': request, 'canDecide': True, 'ownRequest': False, 'pageCommand': 'synthetic-decision',
        'detail': {
            'days': [{'leaveDate': '2026-11-09', 'quantityDays': 1, 'zeroReason': None},
                     {'leaveDate': '2026-11-10', 'quantityDays': 1, 'zeroReason': None}],
            'history': [{'createdAt': base.AS_OF, 'eventKind': 'SUBMITTED', 'fromStatus': None, 'toStatus': 'PENDING', 'reason': '가족 행사'}],
            'correction': None,
            'attendancePolicy': {'code': 'ANNUAL_LEAVE', 'name': '연차', 'revision': 1, 'effectiveFrom': '2026-01-01', 'unit': 'DAY',
                                 'payKind': 'PAID', 'deductionRule': 'LEAVE_DAY', 'basis': '취업규칙 연차 조항'},
            'routeSnapshot': {'routeCode': 'PRODUCT_HEAD', 'routeName': '제품개발팀 조직장', 'revision': 2, 'scopeKind': 'ORGANIZATION',
                              'approverKind': 'ORGANIZATION_HEAD', 'organizationName': '제품개발팀', 'resolvedApproverName': '박서준',
                              'resolvedAt': base.AS_OF}}})

    compose_correction = envelope('request/mine', {
        'employeeHeader': base.HEADER, 'annual': base.ANNUAL, 'buckets': [base.BUCKET],
        'compose': {**base.COMPOSE, 'kind': 'ATTENDANCE_CORRECTION', 'correctionForm': {**base.CORRECTION, 'workDate': '2026-11-03'},
                    'day': {**base.DAY, 'workDate': '2026-11-03', 'events': events, 'effectiveEvents': events}}})

    correction_page = envelope('request/correction', {
        'day': clock_day, 'exceptional': True, 'actions': ['KEEP', 'SET', 'CLEAR'],
        'eventTimes': {'in-1': '2026-11-03 09:02:11.120', 'out-1': '2026-11-03 18:05:40.500'},
        'form': {'targetEmployeeId': 'e-1', 'workDate': '2026-11-03', 'baseInputRevision': 2, 'sourceFingerprint': 'a' * 64,
                 'commandId': 'synthetic-correction', 'inAction': 'KEEP', 'requestedInAt': '', 'outAction': 'SET',
                 'requestedOutAt': '2026-11-03T18:30:00.000', 'presenceAsWork': True, 'breaksConfirmed': True,
                 'actualBreaks': '', 'replacesRequestId': None, 'reason': '', 'predecessorRequestId': None}})

    employee_page = envelope('org/employee', {
        'employee': base.EMPLOYEE, 'form': {**base.EMPLOYEE, **command}, 'options': base.ORGS,
        'assignmentForm': {**command, 'commandId': 'synthetic-assignment', 'organizationId': 'o-1', 'validFrom': '2026-11-10'},
        'assignments': [{'id': 'a-1', 'organizationName': '제품개발팀', 'validFrom': '2024-03-04', 'validTo': None, 'reason': '입사 배치'}]})
    organization_page = envelope('org/organization', {'organization': base.ORG, 'form': {**base.ORG, **command}, 'options': base.ORGS})

    personnel = envelope('org/personnel', {
        'personnel': {'employeeId': 'e-1', 'version': 3, 'today': base.TODAY, 'statusHistory': [],
                      'form': {'birthDate': '1994-05-12', 'gradeName': '선임', 'positionName': '팀원', 'jobName': '제품 개발',
                               'employmentStatus': 'WORKING', 'effectiveFrom': base.TODAY, 'expectedVersion': 3, 'reason': '',
                               'commandId': 'synthetic-personnel', 'photoAction': 'KEEP', 'photoData': ''}},
        'personalDetails': {'employeeId': 'e-1', 'version': 0, 'form': {'expectedVersion': 0, 'commandId': 'synthetic-details'},
                            'entries': [], 'appointments': [],
                            'entryForm': {'kind': 'FAMILY', 'title': '', 'detail': '', 'effectiveFrom': base.TODAY,
                                          'expectedVersion': 0, 'reason': '', 'commandId': 'synthetic-entry'}}})

    days = [{'weekday': i + 1, 'workday': i < 5, 'startTime': '09:00' if i < 5 else '', 'endTime': '18:00' if i < 5 else '',
             'breaks': '12:00-13:00' if i < 5 else ''} for i in range(7)]
    version_days = [{'weekday': i + 1, 'workday': i < 5, 'startTime': '09:00:00' if i < 5 else None,
                     'endTime': '18:00:00' if i < 5 else None,
                     'breaks': [{'startTime': '12:00:00', 'endTime': '13:00:00'}] if i < 5 else []} for i in range(7)]
    template = {'id': 's-1', 'code': 'FIXED_9_18', 'name': '기본 09-18', 'active': True, 'version': 4}
    version = {'id': 'v-1', 'templateId': 's-1', 'revision': 2, 'validFrom': '2026-01-01', 'reason': '연간 기본 근무'}
    schedule_form = {**command, 'code': 'FIXED_9_18', 'name': '기본 09-18', 'validFrom': base.TODAY, 'days': days}
    schedule_detail = envelope('schedule/detail', {'template': template, 'versions': [version], 'versionDays': {'v-1': version_days},
                                                   'form': schedule_form, 'today': base.TODAY})
    schedule_assignment = envelope('schedule/assignment', {
        'employee': base.EMPLOYEE, 'versions': [version], 'templateNames': {'s-1': '기본 09-18'}, 'today': base.TODAY,
        'assignments': [{'id': 'sa-1', 'templateName': '기본 09-18', 'revision': 2, 'validFrom': '2026-01-01', 'validTo': None,
                         'reason': '연간 기본 근무'}],
        'form': {**command, 'scheduleVersionId': 'v-1', 'validFrom': '2026-11-10'}})

    attributions = envelope('request/attributions', {'leaveAttributions': {
        'bucket': {**base.BUCKET, 'employeeName': '김하늘', 'baseDate': '2026-01-01', 'sourceReference': 'synthetic'},
        'history': [{'id': 'at-1', 'bucketId': 'bucket-1', 'revision': 1, 'attendanceTypeId': 'type-1', 'classificationStatus': 'CONFIRMED',
                     'entitlementYear': 2026, 'basis': '2026년 부여분', 'reason': '연초 확인', 'createdBy': 'account-2',
                     'createdByName': '박서준', 'createdAt': base.AS_OF}],
        'form': {'expectedRevision': 1, 'classificationStatus': 'CONFIRMED', 'entitlementYear': 2026, 'singleYearConfirmed': True,
                 'basis': '', 'reason': '', 'commandId': 'synthetic-attribution'}}})

    result = envelope('worktime/result', {
        'month': '2026-11', 'admin': True, 'times': {'확인 실근로': '8:00:00.000', '확인 휴게': '1:00:00.000'},
        'detail': {'day': {'id': 'result-1', 'employeeId': 'e-1', 'employeeNo': '24001', 'employeeName': '김하늘',
                           'workDate': '2026-11-03', 'resultRevision': 1, 'ruleVersion': 'CALC-1', 'calculatedAt': base.AS_OF,
                           'current': True, 'result': {'status': 'CONFIRMED', 'inputRevision': 2, 'reviewRevision': 1,
                                                       'leaveDays': 0, 'reasons': []}},
                   'completeEvidence': True, 'latestVersion': True, 'latestResultId': None, 'clock': clock_day, 'run': None,
                   'policy': None, 'assessment': None, 'employment': None, 'leaveEffects': [], 'pendingCorrections': []}})
    audit = envelope('worktime/audit-detail', {'audit': {
        'id': 'audit-1', 'action': 'APPROVE', 'targetKind': 'REQUEST', 'targetId': 'request-1', 'actorAccountId': 'account-2',
        'commandId': 'command-9', 'requestTraceId': 'trace-9', 'at': base.AS_OF, 'reason': '일정 확인 후 승인',
        'beforeSummary': {'상태': 'PENDING'}, 'afterSummary': {'상태': 'APPROVED'}}})
    history = envelope('clock/history', {
        'day': {**clock_day, 'workDate': base.TODAY}, 'today': base.TODAY, 'isToday': True, 'inCommand': 'synthetic-in',
        'outCommand': 'synthetic-out', 'eventTimes': {'in-1': '2026-11-04 09:02:11.120', 'out-1': '2026-11-04 18:05:40.500'},
        'confirmationForm': {'workDate': base.TODAY, 'baseInputRevision': 2, 'expectedReviewRevision': 1,
                             'sourceFingerprint': 'a' * 64, 'commandId': 'synthetic-confirm'}})

    return {'/requests/request-1': detail, '/me/requests/correction': compose_correction,
            '/admin/requests/exceptions/new': correction_page, '/admin/employees/e-1': employee_page,
            '/admin/organizations/o-1': organization_page, '/admin/employees/e-1/access': base.ACCESS,
            '/admin/employees/e-1/personnel': personnel, '/admin/schedules/s-1': schedule_detail,
            '/admin/employees/e-1/schedule': schedule_assignment, '/admin/organization-heads': base.HEADS,
            '/admin/leave-balances/buckets/bucket-1/attributions': attributions, '/worktime/results/result-1': result,
            '/admin/audit/audit-1': audit, '/me/attendance': history}
```

- [ ] **Step 3: `preview.py`에 모달 자료 연결**

2단계에서 넣은 `FIXTURES.update(sheets.fixtures(base))` 줄 바로 아래에 다음을 추가한다.

```python
modal_spec = importlib.util.spec_from_file_location('modal_fixtures', HERE / 'modal_fixtures.py')
modals = importlib.util.module_from_spec(modal_spec)
modal_spec.loader.exec_module(modals)
FIXTURES.update(modals.fixtures(base))
```

- [ ] **Step 4: `capture.mjs`에 모달 묶음 추가**

2번 줄 주석을 `// 화면 묶음(stage1·sheets·modals) × 차콜·블루 × 1440·390px. 셋째 값이 있으면 배경 화면에서 그 주소의 링크를 눌러 업무 모달로 연다.`로 바꾼다. `sets`의 `sheets` 묶음 뒤에 다음을 더한다.

```js
  modals: [
    ['compose', '/me/requests', '/me/requests/new?year=2026'],
    ['compose-correction', '/me/requests', '/me/requests/correction'],
    ['compose-own-dialog', '/me/requests/new'],
    ['detail-decide', '/me/requests', '/requests/request-1'],
    ['correction-exception', '/admin/access', '/admin/requests/exceptions/new'],
    ['employee', '/admin/access', '/admin/employees/e-1'],
    ['organization', '/admin/organizations', '/admin/organizations/o-1'],
    ['access', '/admin/access', '/admin/employees/e-1/access'],
    ['personnel', '/admin/access', '/admin/employees/e-1/personnel'],
    ['schedule-detail', '/admin/access', '/admin/schedules/s-1'],
    ['schedule-assignment', '/admin/access', '/admin/employees/e-1/schedule'],
    ['organization-heads', '/admin/organizations', '/admin/organization-heads?organizationId=o-1'],
    ['attributions', '/me/requests', '/admin/leave-balances/buckets/bucket-1/attributions'],
    ['result', '/me/requests', '/worktime/results/result-1'],
    ['audit-detail', '/admin/audit/audit-1'],
    ['clock-history', '/me/attendance'],
    ['save-dialog', '/admin/organizations-reason'],
  ],
```

반복문의 `for (const [name, route] of routes)`를 `for (const [name, route, open] of routes)`로 바꾼다. `await page.waitForSelector('#workspace-content, dialog[open]', ...)` 줄 다음에 아래를 넣는다.

```js
    if (open) {
      await page.evaluate(href => {
        const link = document.createElement('a');
        link.href = href; link.id = 'synthetic-trigger'; link.textContent = '열기';
        document.querySelector('#workspace-content').append(link);
        link.focus(); link.click();
      }, open);
      await page.waitForSelector('dialog.t-modal[open] .t-modal-body', { timeout: 10000 });
      await new Promise(done => setTimeout(done, 300));
    }
```

스크린샷 줄의 `fullPage` 조건을 `!open && !route.endsWith('/new') && !route.endsWith('-reason')`로 바꾼다.

- [ ] **Step 5: `modal-check.mjs` 작성 (11번 작업에서 실행)**

```js
// Usage: node modal-check.mjs <outDir> [baseUrl]
// 업무 모달의 초점·가두기·Esc 복귀, 신청 작성의 하단 없음·동작 줄 고정, 기간 달력 선택 표시, 390px 아래 시트를 확인한다.
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
async function openTask(width, start, href) {
  const page = await browser.newPage();
  page.on('pageerror', error => errors.push(String(error)));
  await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }]);
  await page.setViewport({ width, height: width === 390 ? 844 : 900, deviceScaleFactor: 1 });
  await page.goto(base + start, { waitUntil: 'networkidle0' });
  await page.evaluate(() => document.fonts.ready);
  await page.evaluate(target => {
    const link = document.createElement('a');
    link.href = target; link.id = 'synthetic-trigger'; link.textContent = '열기';
    document.querySelector('#workspace-content').append(link);
    link.focus(); link.click();
  }, href);
  await page.waitForSelector('dialog.t-modal[open] .t-modal-body');
  await new Promise(done => setTimeout(done, 300));
  return page;
}
try {
  let page = await openTask(1440, '/me/requests', '/me/requests/new?year=2026');
  result.composeTitleFocused = await page.evaluate(() => document.activeElement?.classList.contains('t-modal-title') === true);
  result.composeFooter = await page.evaluate(() => Boolean(document.querySelector('dialog[open] .t-modal-footer')));
  result.composeActionsVisible = await page.evaluate(() => {
    const body = document.querySelector('dialog[open] .t-modal-body').getBoundingClientRect();
    const bar = document.querySelector('dialog[open] .wl-compose-actions').getBoundingClientRect();
    return bar.top >= body.top && bar.bottom <= body.bottom + 1;
  });
  await page.click('dialog[open] .t-request-period-trigger');
  await page.waitForSelector('dialog[open] .t-request-calendar');
  result.calendarSelected = await page.$eval('dialog[open] .t-request-calendar-days [aria-pressed="true"]',
    button => getComputedStyle(button).backgroundColor !== 'rgb(255, 255, 255)');
  await page.screenshot({ path: path.join(out, 'compose-calendar-charcoal-1440.png') });
  await page.keyboard.press('Escape');
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => !document.querySelector('dialog[open]'));
  result.composeFocusReturned = await page.evaluate(() => document.activeElement?.id === 'synthetic-trigger');
  await page.close();

  page = await openTask(1440, '/me/requests', '/requests/request-1');
  result.detailTitleFocused = await page.evaluate(() => document.activeElement?.classList.contains('t-modal-title') === true);
  result.detailStamps = await page.$$eval('dialog[open] .wl-stamp', items => items.length);
  for (let i = 0; i < 12; i += 1) await page.keyboard.press('Tab');
  result.detailFocusTrapped = await page.evaluate(() => Boolean(document.activeElement?.closest('dialog[open]')));
  await page.screenshot({ path: path.join(out, 'detail-decide-charcoal-1440.png') });
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => !document.querySelector('dialog[open]'));
  result.detailFocusReturned = await page.evaluate(() => document.activeElement?.id === 'synthetic-trigger');
  await page.close();

  page = await openTask(390, '/me/requests', '/me/requests/new?year=2026');
  result.sheet390 = await page.evaluate(() => {
    const box = document.querySelector('dialog[open]').getBoundingClientRect();
    return Math.round(box.left) === 0 && Math.round(box.right) === innerWidth && Math.round(box.bottom) === innerHeight;
  });
  result.overflow390 = await page.evaluate(() => document.documentElement.scrollWidth - innerWidth);
  await page.screenshot({ path: path.join(out, 'compose-sheet-charcoal-390.png') });
  await page.close();
  result.errors = errors.length;
} finally {
  await browser.close();
}
const expected = { composeTitleFocused: true, composeFooter: false, composeActionsVisible: true, calendarSelected: true,
  composeFocusReturned: true, detailTitleFocused: true, detailStamps: 2, detailFocusTrapped: true, detailFocusReturned: true,
  sheet390: true, overflow390: 0, errors: 0 };
const failed = Object.keys(expected).filter(key => result[key] !== expected[key]);
console.log(JSON.stringify(result), failed.length ? `FAIL ${failed.join(',')}` : 'PASS');
process.exit(failed.length ? 1 : 0);
```

- [ ] **Step 6: 미리보기 서버를 다시 띄우고 자료 확인**

켜져 있는 미리보기 서버를 멈춘 뒤 `python "$TOOLS/preview.py"`를 백그라운드로 다시 실행한다. 이어서 아래를 실행한다.

```bash
for p in /requests/request-1 /me/requests/correction /admin/requests/exceptions/new /admin/employees/e-1 /admin/organizations/o-1 /admin/employees/e-1/access /admin/employees/e-1/personnel /admin/schedules/s-1 /admin/employees/e-1/schedule "/admin/organization-heads?organizationId=o-1" /admin/leave-balances/buckets/bucket-1/attributions /worktime/results/result-1 /admin/audit/audit-1 /me/attendance; do printf "%s %s\n" "$p" "$(curl -s -H 'Accept: application/vnd.worklog.page+json' "http://127.0.0.1:18131$p" | python -c 'import json,sys; print(json.load(sys.stdin)["page"])')"; done
```

Expected: 각 줄에 해당 page 키가 나온다(예: `/requests/request-1 request/detail`, `/admin/organization-heads?organizationId=o-1 org/organization-heads`). 다른 키가 나오면 `preview.py`가 쿼리를 떼고 경로만 찾는지 확인한다.

- [ ] **Step 7: 바꾸기 전 기준 캡처**

```bash
npm --prefix frontend run build
node "$TOOLS/capture.mjs" "$TOOLS/shots/stage3-before" http://127.0.0.1:18131 modals
```

Expected: `68 captures, 0 with JS errors, …`가 출력된다(17화면 × 2테마 × 2폭). JS 오류가 있는 화면이 있으면, 그 화면의 합성 자료를 해당 테스트 파일의 자료 모양에 맞춘 뒤 다시 캡처한다. 이 시점의 넘침 수는 기준값으로 기록한다.

---

### Task 1: 공통 모달

**Files:**
- Modify: `frontend/src/components/Modal.tsx`
- Rewrite: `frontend/src/components/Modal.css`
- Modify: `frontend/src/components/Modal.test.tsx:165-171`
- Modify: `frontend/src/design/ui.css:110-111`
- Modify: `frontend/src/cssLayers.test.js`
- Modify: `frontend/src/components/DemoAccountSwitcher.tsx:59`
- Modify: `frontend/src/pages/WorkspaceHome.tsx:152-154`

- [ ] **Step 1: 실패하는 테스트로 바꾸기**

`Modal.test.tsx`의 `'크기를 지정하면 data-size로 노출하고 지정하지 않으면 기존 너비를 쓴다'` 테스트(165–171행)를 아래 두 테스트로 바꾼다. `within`은 이미 import되어 있다.

```tsx
  it('크기를 지정하면 data-size로 노출하고 지정하지 않으면 l을 쓴다', () => {
    const { unmount } = render(<Modal title="저장 확인" size="s" onClose={() => {}}>본문</Modal>);
    expect(screen.getByRole('dialog', { name: '저장 확인' }).getAttribute('data-size')).toBe('s');
    unmount();
    render(<Modal title="상세" onClose={() => {}}>본문</Modal>);
    expect(screen.getByRole('dialog', { name: '상세' }).getAttribute('data-size')).toBe('l');
  });

  it('footer={false}면 하단을 그리지 않고 X 닫기만 둔다', () => {
    render(<Modal title="근태 신청" footer={false} onClose={() => {}}>본문</Modal>);
    const dialog = screen.getByRole('dialog', { name: '근태 신청' });
    expect(dialog.querySelector('footer')).toBeNull();
    expect(within(dialog).getAllByRole('button', { name: '닫기' })).toHaveLength(1);
  });
```

`cssLayers.test.js`의 `legacy` 배열에서 `'src/components/Modal.css', `를 지운다. `'keeps the shared sheet in the design layer above legacy rules'` 테스트 바로 아래에 다음을 더한다.

```js
  it.each(['src/components/Modal.css'])('keeps %s in the design layer above legacy rules', file => {
    const css = code(file);
    expect(css).not.toMatch(/^@layer/);
    expect(css).not.toContain('#tna-app');
    expect(css).not.toContain('!important');
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/components/Modal.test.tsx src/cssLayers.test.js`
Expected: FAIL. `data-size`가 `null`이고, `footer`가 있으며, `Modal.css`가 `@layer legacy`로 시작한다.

- [ ] **Step 3: `Modal.tsx` 구현**

`ModalProps`의 `footer`·`size` 두 줄과 주석을 아래로 바꾼다.

```tsx
  /** 하단 동작. false면 하단을 그리지 않는다(동작 줄을 본문 폼 안에 두는 신청 작성). */
  footer?: ReactNode;
  /** s 440, m 720, l 920, xl 1200(px). 기본 l. */
  size?: 's' | 'm' | 'l' | 'xl';
```

함수 머리의 `size }`를 `size = 'l' }`로 바꾼다. `<footer>` 세 줄을 아래로 바꾼다. 기본 [닫기]에서 옛 변형 클래스 `t-modal-dismiss`를 빼서 기본 버튼 모양을 쓰게 한다.

```tsx
      {footer !== false && <footer className="t-modal-footer">
        {footer ?? <button type="button" className="t-btn" disabled={busy} onClick={onClose}>닫기</button>}
      </footer>}
```

- [ ] **Step 4: `Modal.css` 다시 쓰기**

파일 전체를 아래로 바꾼다.

```css
/* WorkLog shared modal (spec 7.7). Unlayered: wins over legacy without #tna-app.
   Sizes s 440 · m 720 · l 920 (default) · xl 1200. Phones get a bottom sheet. */
.t-modal {
  width: min(920px, calc(100% - 48px)); max-width: none; max-height: calc(100dvh - 48px);
  margin: auto; padding: 0; overflow: hidden; box-sizing: border-box;
  border: 0; border-radius: var(--r-modal); background: var(--g0); color: var(--g900);
  box-shadow: var(--shadow-modal); font-family: var(--font-ui);
}
.t-modal[data-size="s"] { width: min(440px, calc(100% - 48px)); }
.t-modal[data-size="m"] { width: min(720px, calc(100% - 48px)); }
.t-modal[data-size="xl"] { width: min(1200px, calc(100% - 48px)); }
.t-modal[open] { display: grid; grid-template-rows: auto minmax(0, 1fr) auto; }
.t-modal::backdrop { background: rgba(18, 24, 36, .44); }

.t-modal-header {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  min-height: 56px; padding: 0 12px 0 24px; border-bottom: 1px solid var(--g200); background: var(--g0);
}
.t-modal-title { min-width: 0; margin: 0; color: var(--g900); font: 700 18px/1.4 var(--font-ui); letter-spacing: -.025em; overflow-wrap: anywhere; }
.t-modal-title:focus { outline: none; }
.t-modal-close {
  display: grid; place-items: center; flex: none; width: 32px; min-width: 32px; height: 32px; min-height: 32px; padding: 0;
  border: 0; border-radius: var(--r-ctl); background: transparent; color: var(--g600); box-shadow: none; cursor: pointer;
}
.t-modal-close:hover:not(:disabled) { background: var(--g100); color: var(--g900); }
.t-modal-close:disabled { color: var(--g400); cursor: wait; }
.t-modal-close svg { width: 18px; height: 18px; }
.t-modal-body { min-width: 0; min-height: 0; overflow: auto; overscroll-behavior: contain; padding: 24px; background: var(--g0); }
.t-modal-footer {
  display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 6px;
  padding: 12px 16px 12px 24px; border-top: 1px solid var(--g200); background: var(--g0);
}
/* Left side of a footer or action row: required mark and other notes. */
.wl-modal-note { display: inline-flex; align-items: center; gap: 6px; margin-right: auto; color: var(--g600); font-size: 12px; }
.wl-modal-note .wl-required::after { margin-left: 0; }
.t-modal :focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }

/* Content inside a modal: cards and legacy forms become flat sections; page titles stay below the modal title. */
.t-modal-body :is(.panel, .t-card) { padding: 20px 0; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
.t-modal-body :is(.panel, .t-card) + :is(.panel, .t-card) { border-top: 1px solid var(--g200); }
.t-modal-body .admin-form { padding: 20px 0; border-top: 1px solid var(--g200); background: transparent; }
.t-modal-body .admin-form .actions { margin-bottom: 0; padding-top: 16px; border-top: 1px solid var(--g200); }
.t-modal-body .wl-pagehead { margin-bottom: 12px; }
.t-modal-body .wl-pagehead h1 { font-size: 18px; }

@media (max-width: 600px), (pointer: coarse) {
  .t-modal-close { width: 44px; min-width: 44px; height: 44px; min-height: 44px; }
}
@media (max-width: 600px) {
  .t-modal, .t-modal[data-size] { width: 100%; max-width: 100%; max-height: calc(100dvh - 16px); margin: auto 0 0; border-radius: 12px 12px 0 0; }
  .t-modal-header { padding: 0 4px 0 16px; }
  .t-modal-body { padding: 16px; }
  .t-modal-footer { padding: 10px 16px max(10px, env(safe-area-inset-bottom)); }
  .t-modal-footer > .t-btn { flex: 1 0 auto; }
}
```

- [ ] **Step 5: 2단계 임시 크기 규칙 지우기와 모달 사용처**

`design/ui.css`의 아래 두 줄을 지운다(크기는 이제 `Modal.css`가 정한다).

```css
/* Modal size: stage 2 styles only s (save confirmation). Other sizes, header and footer move in stage 3. */
.t-modal[data-size="s"] { width: min(440px, calc(100% - 48px)); }
```

`DemoAccountSwitcher.tsx` 59행의 `<Modal title="사번 변경" onClose={onClose} busy={busy} className="t-demo-account-modal">`에 `size="s"`를 더한다.

```tsx
  return <Modal title="사번 변경" size="s" onClose={onClose} busy={busy} className="t-demo-account-modal">
```

`WorkspaceHome.tsx` 152–154행을 아래로 바꾼다. `size="m"`을 주고, 하단 버튼에서 옛 변형 클래스를 빼 기본 버튼 모양을 쓴다.

```tsx
      {draft && <Modal title="위젯 설정" size="m" className="t-work-home-settings" onClose={closeEditor} footer={<>
        <button type="button" className="t-btn" onClick={closeEditor}>취소</button>
        <button type="button" className="t-btn primary" onClick={saveWidgets}>구성 저장</button>
```

- [ ] **Step 6: 통과 확인**

Run: `npm --prefix frontend test -- src/components/Modal.test.tsx src/cssLayers.test.js src/components/SheetSaveDialog.test.tsx src/pages/WorkspaceHome.test.tsx src/App.modal.test.tsx src/App.requestModal.test.tsx`
Expected: PASS

Run: `npm --prefix frontend test && npm --prefix frontend run build`
Expected: 전체 통과, 빌드 성공

- [ ] **Step 7: 커밋**

```bash
git add frontend/src/components/Modal.tsx frontend/src/components/Modal.css frontend/src/components/Modal.test.tsx frontend/src/design/ui.css frontend/src/cssLayers.test.js frontend/src/components/DemoAccountSwitcher.tsx frontend/src/pages/WorkspaceHome.tsx
git commit -m "feat: size the shared modal and give it a compact header, footer and phone sheet" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 문서형 표

**Files:**
- Modify: `frontend/src/ui.tsx:1-117`
- Create: `frontend/src/ui.formTable.test.tsx`
- Modify: `frontend/src/design/ui.css` (끝에 추가)

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/src/ui.formTable.test.tsx`:

```tsx
// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { Check, Field, FormRow, FormTable, SelectField, TextAreaField } from './ui';

afterEach(cleanup);

describe('문서형 표', () => {
  it('표 안의 필드는 항목명·값 칸 한 행으로 그리고 라벨 연결과 필수 점을 유지한다', () => {
    render(<FormTable>
      <Field label="이름" name="displayName" required />
      <SelectField label="상위 조직" name="parentId"><option value="">없음</option></SelectField>
      <TextAreaField label="변경 사유" name="reason" required />
    </FormTable>);
    const name = screen.getByLabelText('이름');
    const row = name.closest('.wl-form-row')!;
    expect(row.parentElement?.classList.contains('wl-form-table')).toBe(true);
    expect(row.querySelector('label.wl-form-label')?.className).toContain('wl-required');
    expect(name.parentElement?.classList.contains('wl-form-value')).toBe(true);
    expect(screen.getByLabelText('상위 조직').closest('.wl-form-row')?.querySelector('.wl-required')).toBeNull();
    expect(screen.getByLabelText('변경 사유').tagName).toBe('TEXTAREA');
    expect(document.querySelectorAll('.wl-form-row')).toHaveLength(3);
  });

  it('묶음 행은 항목명으로 이름 붙인 group이고 안의 필드는 칸 안에 그대로 둔다', () => {
    render(<FormTable>
      <FormRow label="재직 기간" required>
        <Field label="입사일" name="joinedOn" type="date" />
        <Field label="마지막 재직일 (선택)" name="leftOn" type="date" />
      </FormRow>
      <FormRow label="상태"><Check name="active" label="활성 사원" checked /></FormRow>
    </FormTable>);
    const period = screen.getByRole('group', { name: '재직 기간' });
    expect(within(period).getByLabelText('입사일').closest('.wl-form-row')).toBe(period);
    expect(period.querySelectorAll('.wl-form-row')).toHaveLength(0);
    expect((screen.getByRole('checkbox', { name: '활성 사원' }) as HTMLInputElement).checked).toBe(true);
    expect(screen.getByRole('group', { name: '상태' })).toBeTruthy();
  });

  it('연결 라벨 행과 2열 표를 지원한다', () => {
    render(<FormTable columns={2}>
      <FormRow label="분류 상태" htmlFor="status" required><select id="status"><option>미분류</option></select></FormRow>
    </FormTable>);
    expect(screen.getByLabelText('분류 상태').tagName).toBe('SELECT');
    expect(document.querySelector('.wl-form-table')?.getAttribute('data-columns')).toBe('2');
    expect(screen.queryByRole('group', { name: '분류 상태' })).toBeNull();
  });

  it('표 밖의 필드는 지금처럼 라벨과 컨트롤만 그린다', () => {
    const { container } = render(<form><Field label="이름" name="displayName" /></form>);
    expect(container.querySelector('form')?.children[0].tagName).toBe('LABEL');
    expect(container.querySelector('.wl-form-row')).toBeNull();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/ui.formTable.test.tsx`
Expected: FAIL. `FormRow`·`FormTable`이 export되지 않는다.

- [ ] **Step 3: `ui.tsx` 구현**

type import 묶음에 `ReactNode`를 더한다.

```tsx
import type {
  FormHTMLAttributes,
  InputHTMLAttributes,
  ReactNode,
  SelectHTMLAttributes,
  TextareaHTMLAttributes,
} from 'react';
```

`Form` 함수 다음, `interface FieldProps` 앞에 다음을 넣는다.

```tsx
/** 문서형 표(설계 7.8). 안의 Field·SelectField·TextAreaField는 항목명·값 칸 한 행으로 그린다. */
const FormTableContext = createContext(false);
export function FormTable({ columns = 1, children }: { columns?: 1 | 2; children: ReactNode }) {
  return (
    <FormTableContext value={true}>
      <div className="wl-form-table" data-columns={columns === 2 ? 2 : undefined}>{children}</div>
    </FormTableContext>
  );
}

/** 문서형 표의 한 행. htmlFor가 없으면 값 칸을 항목명으로 이름 붙인 group으로 둔다(묶음·체크 항목). */
export function FormRow({
  label,
  htmlFor,
  required = false,
  children,
}: {
  label: string;
  htmlFor?: string;
  required?: boolean;
  children: ReactNode;
}) {
  const labelId = useId();
  const className = `wl-form-label${required ? ' wl-required' : ''}`;
  return (
    <div className="wl-form-row" role={htmlFor ? undefined : 'group'} aria-labelledby={htmlFor ? undefined : labelId}>
      {htmlFor ? <label className={className} htmlFor={htmlFor}>{label}</label> : <span id={labelId} className={className}>{label}</span>}
      <div className="wl-form-value"><FormTableContext value={false}>{children}</FormTableContext></div>
    </div>
  );
}
```

`Field` 본문을 아래로 바꾼다.

```tsx
export function Field({
  label,
  value,
  id,
  type = 'text',
  className = 't-input',
  ...props
}: FieldProps) {
  const generatedId = useId();
  const inTable = useContext(FormTableContext);
  const fieldId = id ?? generatedId;
  const input = (
    <input
      {...props}
      id={fieldId}
      type={type}
      className={className}
      defaultValue={type === 'password' ? undefined : (value ?? undefined)}
    />
  );
  return inTable ? (
    <FormRow label={label} htmlFor={fieldId} required={props.required}>{input}</FormRow>
  ) : (
    <>
      <label htmlFor={fieldId}>{label}</label>
      {input}
    </>
  );
}
```

`SelectField`와 `TextAreaField`도 같은 방식으로 바꾼다.

```tsx
export function SelectField({
  label,
  value,
  children,
  id,
  className = 't-select',
  ...props
}: SelectProps) {
  const generatedId = useId();
  const inTable = useContext(FormTableContext);
  const fieldId = id ?? generatedId;
  const select = (
    <select {...props} id={fieldId} className={className} defaultValue={value ?? ''}>
      {children}
    </select>
  );
  return inTable ? (
    <FormRow label={label} htmlFor={fieldId} required={props.required}>{select}</FormRow>
  ) : (
    <>
      <label htmlFor={fieldId}>{label}</label>
      {select}
    </>
  );
}
```

```tsx
export function TextAreaField({ label, value, id, ...props }: TextAreaProps) {
  const generatedId = useId();
  const inTable = useContext(FormTableContext);
  const fieldId = id ?? generatedId;
  const textarea = <textarea {...props} id={fieldId} defaultValue={value ?? ''} />;
  return inTable ? (
    <FormRow label={label} htmlFor={fieldId} required={props.required}>{textarea}</FormRow>
  ) : (
    <>
      <label htmlFor={fieldId}>{label}</label>
      {textarea}
    </>
  );
}
```

`Check`는 바꾸지 않는다. 표 안에서는 `FormRow`로 감싼다.

- [ ] **Step 4: `ui.css`에 문서형 표 규칙 추가**

파일 끝에 다음을 더한다.

```css
/* Forms and sections that left the legacy admin-form/panel/actions classes. */
.wl-form { display: block; max-width: none; margin: 0; padding: 0; border: 0; background: none; }
.wl-section > h2 { margin: 0 0 10px; color: var(--g900); font: 700 15px/1.4 var(--font-ui); letter-spacing: -.02em; }
.wl-section h3 { margin: 16px 0 8px; color: var(--g900); font: 700 14px/1.4 var(--font-ui); }
.wl-section > .hint { margin: 0 0 10px; color: var(--g600); font-size: 12.5px; line-height: 1.6; }
.wl-form-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 6px; margin: 12px 0 0; }

/* Document table (spec 7.8): 2px g800 top line, 116px g50 label cell, 1px g200 row lines.
   A required item gets a dot after its label (.wl-required). A <p>/.hint right after a row reads as part of that row. */
.wl-form-table { min-width: 0; margin: 0 0 16px; border-top: 2px solid var(--g800); }
.wl-form-row { display: grid; grid-template-columns: 116px minmax(0, 1fr); min-width: 0; border-bottom: 1px solid var(--g200); }
.wl-form-label {
  display: flex; align-items: center; min-width: 0; margin: 0; padding: 8px 14px;
  background: var(--g50); color: var(--g700); font: 600 13px/1.4 var(--font-ui); overflow-wrap: anywhere;
}
.wl-form-row:has(textarea, .table-scroll, .t-break-editor, .t-request-bucket-sheet) > .wl-form-label { align-items: flex-start; padding-top: 14px; }
.wl-form-value { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px; min-width: 0; padding: 6px 12px; }
.wl-form-value > :is(.t-input, .t-select) { flex: 0 1 280px; width: auto; min-width: 0; margin: 0; }
.wl-form-value > textarea {
  flex: 1 1 100%; width: 100%; min-height: 76px; margin: 0; padding: 8px 10px; box-sizing: border-box;
  border: 1px solid var(--field-line); border-radius: var(--r-ctl); background: var(--g0); color: var(--g900); box-shadow: none;
  font: 400 13px/1.55 var(--font-ui); resize: vertical;
}
.wl-form-value > textarea:focus { border-color: var(--brand); outline: 2px solid var(--brand); outline-offset: 1px; }
.wl-form-value > label:not(.checkbox-label) { margin: 0; color: var(--g700); font: 500 12.5px/1.4 var(--font-ui); white-space: nowrap; }
.wl-form-value .checkbox-label { display: inline-flex; align-items: center; gap: 6px; min-height: 28px; margin: 0; color: var(--g900); font: 400 13px/1.4 var(--font-ui); }
.wl-form-value :is(input[type="checkbox"], input[type="radio"]) { flex: none; width: 16px; height: 16px; min-height: 0; margin: 0; accent-color: var(--brand); }
.wl-form-value > p { margin: 0; color: var(--g700); font-size: 13px; }
.wl-form-value > .table-scroll { flex: 1 1 100%; }
.wl-form-table > :is(p, .hint) {
  margin: 0; padding: 0 12px 8px 128px; border-bottom: 1px solid var(--g200);
  background: linear-gradient(90deg, var(--g50) 116px, var(--g0) 116px); color: var(--g600); font: 400 12px/1.5 var(--font-ui);
}
.wl-form-row:has(+ :is(p, .hint)), .wl-form-table > :is(p, .hint):has(+ :is(p, .hint)) { border-bottom-color: transparent; }

/* Two-column document table (th td th td). A textarea row and anything that is not a row span both columns. */
.wl-form-table[data-columns="2"] { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); border-bottom: 1px solid var(--g200); }
.wl-form-table[data-columns="2"] > .wl-form-row { border-top: 1px solid var(--g200); border-bottom: 0; }
.wl-form-table[data-columns="2"] > .wl-form-row:nth-child(-n + 2) { border-top: 0; }
.wl-form-table[data-columns="2"] > .wl-form-row:nth-child(even):not(:has(textarea)) { border-left: 1px solid var(--g200); }
.wl-form-table[data-columns="2"] > :is(.wl-form-row:has(textarea), :not(.wl-form-row)) { grid-column: 1 / -1; }

/* Read-only document table for flat dt/dd lists. The label column grows to 200px before wrapping. */
dl.wl-form-table { display: grid; grid-template-columns: fit-content(200px) minmax(0, 1fr); }
dl.wl-form-table dt { min-width: 116px; padding: 9px 14px; border-bottom: 1px solid var(--g200); background: var(--g50); color: var(--g700); font: 600 13px/1.45 var(--font-ui); }
dl.wl-form-table dd { min-width: 0; margin: 0; padding: 9px 12px; border-bottom: 1px solid var(--g200); color: var(--g900); font: 400 13px/1.55 var(--font-ui); overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }

/* Weekday table inside a value cell (schedule template). */
.wl-form-value .table-scroll table { width: 100%; border-collapse: collapse; font-size: 13px; }
.wl-form-value .table-scroll th { height: 32px; padding: 0 8px; border-bottom: 1px solid var(--g200); background: var(--g50); color: var(--g700); font-weight: 600; text-align: left; }
.wl-form-value .table-scroll td { padding: 4px 8px; border-bottom: 1px solid var(--g150); }
.wl-form-value .table-scroll td input[type="text"] {
  width: 100%; min-width: 72px; height: 30px; min-height: 0; padding: 0 8px; box-sizing: border-box;
  border: 1px solid var(--field-line); border-radius: var(--r-ctl); background: var(--g0); color: var(--g900); font: 400 13px/1.4 var(--font-ui);
}

@media (max-width: 600px) {
  .wl-form-row { grid-template-columns: minmax(0, 1fr); }
  .wl-form-label, .wl-form-row:has(textarea, .table-scroll, .t-break-editor, .t-request-bucket-sheet) > .wl-form-label { padding: 7px 12px; }
  .wl-form-value { padding: 8px 12px 10px; }
  .wl-form-value > :is(.t-input, .t-select) { flex: 1 1 100%; }
  .wl-form-table > :is(p, .hint) { padding-left: 12px; background: var(--g0); }
  .wl-form-table[data-columns="2"] { display: block; border-bottom: 0; }
  .wl-form-table[data-columns="2"] > .wl-form-row { border-top: 0; border-left: 0; border-bottom: 1px solid var(--g200); }
  dl.wl-form-table { grid-template-columns: minmax(0, 1fr); }
  dl.wl-form-table dt { padding: 7px 12px; border-bottom: 0; }
  dl.wl-form-table dd { padding: 8px 12px 10px; }
  .wl-form-actions > .t-btn { flex: 1 0 auto; }
}
```

- [ ] **Step 5: 통과 확인**

Run: `npm --prefix frontend test -- src/ui.formTable.test.tsx src/ui.test.tsx`
Expected: PASS

Run: `npm --prefix frontend test`
Expected: 전체 통과(표를 쓰는 화면이 아직 없으므로 다른 화면 출력은 같다)

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/ui.tsx frontend/src/ui.formTable.test.tsx frontend/src/design/ui.css
git commit -m "feat: add the document table for forms and read-only lists" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 결재란·안내·정보 격자

**Files:**
- Create: `frontend/src/components/ApprovalStamps.tsx`, `ApprovalStamps.test.tsx`
- Create: `frontend/src/components/Notice.tsx`, `Notice.test.tsx`
- Modify: `frontend/src/design/ui.css` (끝에 추가)

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/src/components/ApprovalStamps.test.tsx`:

```tsx
// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ApprovalStamps } from './ApprovalStamps';

afterEach(cleanup);

describe('결재란', () => {
  it('단계마다 역할·이름·소속·상태를 한 칸에 두고 상태는 색과 문구로 함께 표시한다', () => {
    render(<ApprovalStamps steps={[
      { role: '기안', name: '김하늘', detail: '제품개발팀', state: '작성 중' },
      { role: '결재', name: '박서준', detail: null, state: '결재 대기', tone: 'warn' },
    ]} />);
    const items = within(screen.getByRole('list', { name: '결재란' })).getAllByRole('listitem');
    expect(items.map(item => item.textContent)).toEqual(['기안김하늘제품개발팀작성 중', '결재박서준결재 대기']);
    expect(within(items[0]).getByText('작성 중').className).toBe('t-tag');
    expect(within(items[1]).getByText('결재 대기').className).toBe('t-tag amber');
  });
});
```

`frontend/src/components/Notice.test.tsx`:

```tsx
// @vitest-environment jsdom
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { Notice } from './Notice';

afterEach(cleanup);

describe('안내', () => {
  it('아이콘은 읽지 않고 문장만 읽는다', () => {
    const { container } = render(<Notice>승인되면 잔여 연차에서 차감됩니다.</Notice>);
    expect(container.querySelector('p.wl-notice svg')?.getAttribute('aria-hidden')).toBe('true');
    expect(container.textContent).toBe('승인되면 잔여 연차에서 차감됩니다.');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/components/ApprovalStamps.test.tsx src/components/Notice.test.tsx`
Expected: FAIL. 두 모듈이 없다.

- [ ] **Step 3: 부품 구현**

`frontend/src/components/ApprovalStamps.tsx`:

```tsx
export interface ApprovalStep {
  role: string;
  name: string;
  detail?: string | null;
  state: string;
  tone?: 'ok' | 'info' | 'warn' | 'danger';
}

const tones = { ok: 'green', info: 'blue', warn: 'amber', danger: 'red' } as const;

/** 결재란(설계 7.8): 단계마다 128px 칸에 역할·이름·소속·상태를 둔다. 모바일에서는 세로 목록이다. */
export function ApprovalStamps({ steps }: { steps: ApprovalStep[] }) {
  return (
    <ol className="wl-stamps" aria-label="결재란">
      {steps.map(step => (
        <li className="wl-stamp" key={step.role}>
          <span className="wl-stamp-role">{step.role}</span>
          <span className="wl-stamp-who"><strong>{step.name}</strong>{step.detail && <small>{step.detail}</small>}</span>
          <span className="wl-stamp-state"><span className={step.tone ? `t-tag ${tones[step.tone]}` : 't-tag'}>{step.state}</span></span>
        </li>
      ))}
    </ol>
  );
}
```

`frontend/src/components/Notice.tsx`:

```tsx
import type { ReactNode } from 'react';

/** 안내(설계 7.8): g50 바탕에 정보 아이콘과 한 문장. */
export function Notice({ children }: { children: ReactNode }) {
  return (
    <p className="wl-notice">
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><circle cx="12" cy="12" r="8.5" /><path d="M12 11v5.5M12 7.6h.01" /></svg>
      <span>{children}</span>
    </p>
  );
}
```

- [ ] **Step 4: `ui.css`에 결재란·안내·정보 격자 추가**

파일 끝에 다음을 더한다.

```css
/* Approval stamps (spec 7.8): one 128px cell per step (role, name and detail, state). A vertical list on phones. */
.wl-stamps { display: inline-grid; grid-auto-flow: column; grid-auto-columns: 128px; margin: 0; padding: 0; border: 1px solid var(--g300); border-radius: var(--r-ctl); list-style: none; overflow: hidden; }
.wl-stamp { display: grid; grid-template-rows: 26px auto 30px; min-width: 0; border-left: 1px solid var(--g300); text-align: center; }
.wl-stamp:first-child { border-left: 0; }
.wl-stamp-role { display: grid; place-items: center; border-bottom: 1px solid var(--g300); background: var(--g50); color: var(--g700); font: 600 12px/1 var(--font-ui); }
.wl-stamp-who { display: grid; align-content: center; gap: 2px; min-width: 0; padding: 9px 6px 6px; }
.wl-stamp-who strong { color: var(--g900); font: 700 14px/1.35 var(--font-ui); overflow-wrap: anywhere; }
.wl-stamp-who small { color: var(--g600); font-size: 12px; line-height: 1.4; overflow-wrap: anywhere; }
.wl-stamp-state { display: grid; place-items: center; border-top: 1px dashed var(--g200); }
.wl-approval { display: flex; flex-wrap: wrap; align-items: center; gap: 12px 16px; }
.wl-approval-note { flex: 1 1 220px; margin: 0; color: var(--g600); font-size: 12.5px; line-height: 1.6; }

/* Notice: one sentence with an info icon on g50. */
.wl-notice { display: flex; align-items: flex-start; gap: 8px; margin: 14px 0 0; padding: 10px 12px; border-radius: var(--r-pop); background: var(--g50); color: var(--g700); font: 400 12.5px/1.6 var(--font-ui); }
.wl-notice svg { flex: none; width: 16px; height: 16px; margin-top: 2px; fill: none; stroke: var(--g500); stroke-width: 1.7; stroke-linecap: round; }

/* Info grid: read-only facts, label above value, 4 columns (2 on phones). */
.wl-info-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px 20px; margin: 0; }
.wl-info-grid > div { min-width: 0; }
.wl-info-grid dt { color: var(--g600); font: 500 12px/1.5 var(--font-ui); }
.wl-info-grid dd { margin: 2px 0 0; color: var(--g900); font: 600 14px/1.5 var(--font-ui); overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }

@media (max-width: 600px) {
  .wl-stamps { display: grid; grid-auto-flow: row; grid-auto-columns: auto; width: 100%; }
  .wl-stamp { grid-template-rows: none; grid-template-columns: 56px minmax(0, 1fr) auto; align-items: center; border-top: 1px solid var(--g300); border-left: 0; text-align: left; }
  .wl-stamp:first-child { border-top: 0; }
  .wl-stamp-role { align-self: stretch; border-right: 1px solid var(--g300); border-bottom: 0; }
  .wl-stamp-who { padding: 8px 12px; }
  .wl-stamp-state { padding: 0 12px; border-top: 0; }
  .wl-info-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
```

- [ ] **Step 5: 통과 확인**

Run: `npm --prefix frontend test -- src/components/ApprovalStamps.test.tsx src/components/Notice.test.tsx`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/components/ApprovalStamps.tsx frontend/src/components/ApprovalStamps.test.tsx frontend/src/components/Notice.tsx frontend/src/components/Notice.test.tsx frontend/src/design/ui.css
git commit -m "feat: add approval stamps, notice and info grid parts" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 신청 작성

시안 2번 화면의 L 모달을 따른다. 단계·미리보기·초점 동작과 폼 필드는 그대로 둔다.

**Files:**
- Modify: `frontend/src/pages/AnnualRequestPage.tsx:1-312`
- Modify: `frontend/src/pages/AnnualRequestPage.test.tsx:71-73, 419`
- Modify: `frontend/src/App.tsx:24-27, 654`
- Modify: `frontend/src/App.requestModal.test.tsx:53`
- Create: `frontend/src/design/request.css`
- Modify: `frontend/src/cssLayers.test.js`

- [ ] **Step 1: 테스트를 새 모양으로 바꾸기**

`AnnualRequestPage.test.tsx` 71–73행을 아래로 바꾼다.

```tsx
  const stamps=within(routing).getByRole('list',{name:'결재란'});
  const steps=within(stamps).getAllByRole('listitem');
  expect(steps.map(step=>step.querySelector('.wl-stamp-role')?.textContent)).toEqual(['기안','결재']);
  expect(within(steps[1]).getByText('합성 조직장')).toBeTruthy();
```

419행 `expect(view.getByRole('table',{name:'결재 정보'})).toBeTruthy();`를 아래로 바꾼다.

```tsx
  expect(view.getByRole('list',{name:'결재란'})).toBeTruthy();
```

`App.requestModal.test.tsx`의 첫 테스트에서 53행(`getByRole('form',{name:'신청서'})`) 다음에 아래를 넣는다.

```tsx
    expect(dialog.querySelector('.t-modal-footer')).toBeNull();
    expect(within(dialog).getAllByRole('button',{name:'닫기'})).toHaveLength(1);
    expect(within(dialog).getByRole('button',{name:'미리보기'}).closest('.wl-compose-actions')?.closest('form')).toBeTruthy();
```

`cssLayers.test.js`의 `it.each(['src/components/Modal.css'])`를 `it.each(['src/components/Modal.css', 'src/design/request.css'])`로 바꾼다.

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/pages/AnnualRequestPage.test.tsx src/App.requestModal.test.tsx src/cssLayers.test.js`
Expected: FAIL. "결재란" 목록이 없고, 모달 하단이 있으며, `design/request.css`가 없다.

- [ ] **Step 3: 결재선·신청자·항목 행**

`AnnualRequestPage.tsx` import 끝에 두 줄을 더한다.

```tsx
import { ApprovalStamps } from '../components/ApprovalStamps';
import { Notice } from '../components/Notice';
```

`ApprovalLine`, `ApplicantFacts`, `RequestField`를 아래로 바꾼다.

```tsx
function ApprovalLine({ approval, checked, applicant }: { approval: ApprovalPreview | undefined; checked: boolean; applicant: { name: string; organization: string | null } }) {
  const status = approval?.eligible ? (checked ? '미리보기 확인' : '결재 가능') : '확인 필요';
  return <section className="wl-compose-block" aria-labelledby="annual-approval-line">
    <h3 id="annual-approval-line">결재선</h3>
    {approval ? <div className="wl-approval">
      <ApprovalStamps steps={[
        { role: '기안', name: applicant.name, detail: applicant.organization, state: '작성 중' },
        { role: '결재', name: approval.approverName ?? '미지정', detail: approval.routeName ?? (approval.source === 'LEGACY' ? '사원별 지정 승인자' : '미지정'), state: status, tone: approval.eligible ? 'ok' : 'warn' },
      ]} />
      {approval.message && approval.message !== status && <p className="wl-approval-note">{approval.message}</p>}
    </div> : <p className="wl-approval-note" role="status">유형·일자 조회 후 확인</p>}
  </section>;
}
function ApplicantFacts({ model }: { model: AnnualModel }) {
  const employee = model.employeeHeader;
  return <section className="wl-compose-block" aria-labelledby="annual-applicant">
    <h3 id="annual-applicant">신청자</h3>
    <dl className="wl-form-table wl-compose-applicant">
      <div><dt>이름</dt><dd>{employee?.employeeName ?? model.workspaceEmployee?.employeeName ?? '확인 필요'}</dd></div>
      <div><dt>소속</dt><dd>{employee?.organizationName ?? (employee ? '소속 미배정' : model.workspaceEmployee?.companyName ?? '확인 필요')}</dd></div>
      {employee?.employeeNo && <div><dt>사번</dt><dd>{employee.employeeNo}</dd></div>}
      <div><dt>작성 기준일</dt><dd>{fmtDate(model.annual.today)}</dd></div>
    </dl>
  </section>;
}
```

```tsx
function RequestField({ label, required = false, children }: { label: string; required?: boolean; children: ReactNode }) {
  return <div className="wl-form-row">
    <span className={`wl-form-label${required ? ' wl-required' : ''}`} aria-hidden="true">{label}</span>
    <div className="wl-form-value">{children}</div>
  </div>;
}
```

`RequestComposer`의 `const approval = …` 줄 바로 아래에 신청자 정보를 둔다.

```tsx
  const applicant = { name: model.employeeHeader?.employeeName ?? model.workspaceEmployee?.employeeName ?? '확인 필요', organization: model.employeeHeader?.organizationName ?? null };
```

- [ ] **Step 4: 요약과 본문 구조**

`const summary = …`(199–208행)를 아래로 바꾼다.

```tsx
  const summary = <aside className="wl-compose-summary" aria-label="신청 요약">
    <h3>신청 요약</h3>
    <dl className="wl-info-grid">
      <div><dt>신청 유형</dt><dd>{kindName(kind)}</dd></div>
      <div><dt>{step==='TYPE'||needsContext?'기준 날짜':'대상 기간'}</dt><dd>{step==='TYPE'||needsContext?fmtDate(date):isLeave?`${fmtDate(leave.from)} ~ ${fmtDate(leave.to)}`:fmtDate(correction.workDate)}</dd></div>
      <div><dt>오늘 사용 가능</dt><dd>{step==='REVIEW'&&compose.preview?compose.preview.availableDays:model.annual.currentAvailableDays} DAY</dd></div>
      {!embedded&&<div><dt>예정 결재 담당</dt><dd>{needsContext?'유형·일자 조회 후 확인':approval?.approverName??'미지정'}</dd></div>}
    </dl>
    <Notice>현재 잔여는 조회 시점 기준입니다. 신청 대기 중에는 예약·차감하지 않으며 제출·승인 시 다시 확인합니다.</Notice>
  </aside>;
```

`const content = <>` 안의 아래 줄들을 차례로 바꾼다. 나머지 줄은 그대로 둔다.

1. 자체 dialog 머리(210행)를 아래로 바꾼다. 설명 문장은 화면에서 숨기고, dialog의 `aria-describedby` 대상으로만 남긴다.

```tsx
    {!embedded && <header className="wl-compose-head"><div><h2 id="annual-compose-title">근태 신청</h2><p className="wl-compose-year">{model.annual.year}년 신청</p></div><p id="annual-compose-description" className="t-sr-only">필요한 내용을 차례로 작성하고, 제출 전에 한 번 더 확인하세요.</p><button type="button" className="t-btn t-request-close" aria-label="신청 창 닫기" onClick={close} disabled={locked}><RequestIcon kind="CLOSE"/></button></header>}
```

2. 단계(211행)를 아래로 바꾼다. 클래스 이름만 바뀐다.

```tsx
    <nav className="wl-steps" aria-label="신청 작성 단계"><ol>{(['TYPE','CONTENT','REVIEW'] as const).map((value,index)=><li key={value} data-complete={String(index < ['TYPE','CONTENT','REVIEW'].indexOf(step))}><span className="wl-step-number" aria-hidden="true">{index+1}</span><span aria-current={step===value?'step':undefined}>{['신청 유형','내용 작성','제출 전 확인'][index]}</span></li>)}</ol></nav>
```

3. 결재선·신청자 묶음(212행)과 신청 내용 구역 시작(213행)을 아래로 바꾼다.

```tsx
    <div className="wl-compose-context"><ApprovalLine approval={approval} checked={step==='REVIEW'} applicant={applicant} /><ApplicantFacts model={model} /></div>
    <section className="wl-compose-main" aria-label={step==='CONTENT'?'신청 내용':undefined}>
```

4. 신청서 머리(215–218행)를 아래로 바꾼다. [유형 변경]은 옛 변형 클래스를 빼고 기본 버튼을 쓴다.

```tsx
    {step==='CONTENT' && <header className="wl-compose-doc-head">
      <h3 id="annual-application-title" ref={contentHeading} tabIndex={-1} data-error-focus={!preserveOpeningFocus&&!error?'true':undefined}>신청서</h3>
      <div className="wl-compose-kind"><span>{kindName(compose.kind)}</span><button type="button" className="t-btn" disabled={locked} onClick={()=>setStep('TYPE')}>유형 변경</button></div>
    </header>}
```

5. 날짜 줄(225–231행)을 문서형 표 한 행으로 바꾼다.

```tsx
      <div className="wl-form-table wl-compose-date"><div className="wl-form-row">
        <label className="wl-form-label wl-required" htmlFor="annual-date">{kind === 'LEAVE_DAY' ? '신청 날짜' : '정정할 날짜'}</label>
        <div className="wl-form-value">
          <input id="annual-date" className="t-input" type="date" disabled={locked} name="date" value={date} onChange={event => {setDate(event.target.value);lookupDates.current[kind]=event.target.value;}} max={kind === 'ATTENDANCE_CORRECTION' ? model.annual.today : undefined} required />
          <button type={step==='TYPE'&&!needsContext?'button':'submit'} className={`t-btn ${step==='TYPE'?'primary':''}`} disabled={locked} onClick={event=>{if(step==='TYPE'&&!needsContext){event.preventDefault();if(event.currentTarget.form?.reportValidity())setStep('CONTENT');}}}>{step==='TYPE'?'내용 작성으로':'유형·일자 조회'}</button>
        </div>
      </div></div>
```

6. 내용 칸(238행)과 연차 두 행(241–242행)을 아래로 바꾼다.

```tsx
        <div className="wl-form-table wl-compose-fields" hidden={step!=='CONTENT'}>
```

```tsx
          {step==='CONTENT' && <RequestField label="신청 기간" required><RequestPeriodSheet {...period} onSelect={change} /></RequestField>}
          <RequestField label="사용할 연차" required><LeaveBucketSheet buckets={model.buckets} selected={leave.bucketId} onSelect={id => change('bucketId', id)} /></RequestField>
```

7. 신청 사유 행(277–283행)을 아래로 바꾼다.

```tsx
        <div className="wl-form-row">
          <label className="wl-form-label wl-required" htmlFor="annual-reason">신청 사유</label>
          <div className="wl-form-value">
            <textarea id="annual-reason" name="reason" maxLength={500} rows={3} value={current.reason ?? ''} onChange={event => change('reason', event.target.value)} required />
            <p className="wl-compose-count"><span>민감정보를 적지 마세요.</span><span aria-hidden="true">{(current.reason??'').length}/500</span></p>
          </div>
        </div>
```

8. 미리보기의 항목 목록(289행)을 정보 격자로 바꾼다.

```tsx
          <dl className="wl-info-grid">
```

9. 동작 줄(304행)을 아래로 바꾼다. 버튼과 처리는 그대로이고 클래스와 필수 표시만 더한다.

```tsx
        {step!=='TYPE' && <div className="wl-compose-actions"><span className="wl-modal-note"><span className="wl-required" aria-hidden="true" />필수 입력</span>{step==='CONTENT'?<><button type="button" className="t-btn" disabled={locked} onClick={()=>setStep('TYPE')}>이전</button><button type="button" className="t-btn primary" onClick={() => void preview()} disabled={locked || compose.conflict || noLeaveBuckets}>{busy?'확인 중…':'미리보기'}</button></>:<><button type="button" className="t-btn" disabled={locked} onClick={()=>{setReady(false);setStep('CONTENT');}}>내용 수정</button><button className="t-btn primary" disabled={!ready || locked || compose.conflict || noLeaveBuckets}>{submitting?'제출 중…':'신청 제출'}</button></>}</div>}
```

10. 자체 dialog 아래 줄(309행)을 아래로 바꾼다.

```tsx
    {!embedded&&<footer className="wl-compose-dismiss"><span>제출 전까지 신청 내용은 저장되지 않습니다.</span><button type="button" className="t-btn" onClick={close} disabled={locked}>닫기</button></footer>}
```

- [ ] **Step 5: App에서 하단을 빼고 신청 CSS 불러오기**

`App.tsx` 27행(`import './design/shell.css';`) 아래에 `import './design/request.css';`를 더한다. 654행의 `<Modal …>` 여는 태그를 아래로 바꾼다.

```tsx
        {background && <Modal title={Boolean(envelope.data.compose) ? '근태 신청' : titles[envelope.page] ?? '업무 상세'} busy={busy || modalWorking} onClose={closeModal}
          footer={envelope.data.compose ? false : undefined}>
```

- [ ] **Step 6: `design/request.css` 작성**

```css
/* Request compose (spec 7.7–7.8, design board frame 2). Unlayered: wins over legacy RequestWorkspace.css,
   whose rules for the renamed classes no longer match. Step flow, preview and focus handling are unchanged. */

/* Own dialog (page opened directly): the same chrome as the shared modal, header and actions stay in view. */
.t-request-dialog {
  width: min(920px, calc(100% - 48px)); max-width: none; max-height: calc(100dvh - 48px); margin: auto; padding: 0 24px;
  overflow: auto; overscroll-behavior: contain; box-sizing: border-box;
  border: 0; border-radius: var(--r-modal); background: var(--g0); color: var(--g900); box-shadow: var(--shadow-modal);
}
.t-request-dialog::backdrop { background: rgba(18, 24, 36, .44); }
.wl-compose-head {
  position: sticky; top: 0; z-index: 2; display: flex; align-items: center; justify-content: space-between; gap: 12px;
  min-height: 56px; margin: 0 -24px; padding: 0 12px 0 24px; border-bottom: 1px solid var(--g200); background: var(--g0);
}
.wl-compose-head > div { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
.wl-compose-head h2 { margin: 0; color: var(--g900); font: 700 18px/1.4 var(--font-ui); letter-spacing: -.025em; }
.wl-compose-year { margin: 0; color: var(--g600); font-size: 12.5px; font-weight: 600; }
.t-btn.t-request-close {
  display: grid; place-items: center; flex: none; width: 32px; min-width: 32px; height: 32px; min-height: 32px; padding: 0;
  border: 0; border-radius: var(--r-ctl); background: transparent; color: var(--g600); box-shadow: none; cursor: pointer;
}
.t-btn.t-request-close:hover:not(:disabled) { background: var(--g100); color: var(--g900); }
.t-request-close svg { width: 18px; height: 18px; }
.wl-compose-dismiss {
  display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px;
  margin: 20px -24px 0; padding: 12px 16px 12px 24px; border-top: 1px solid var(--g200); color: var(--g600); font-size: 12px;
}

/* Steps: one quiet line. */
.wl-steps ol { display: flex; flex-wrap: wrap; gap: 4px 20px; margin: 16px 0 0; padding: 0; list-style: none; }
.wl-steps li { display: flex; align-items: center; gap: 6px; color: var(--g600); font-size: 13px; font-weight: 600; }
.wl-step-number { display: inline-grid; place-items: center; width: 20px; height: 20px; border-radius: 50%; background: var(--g100); color: var(--g700); font-size: 12px; font-variant-numeric: tabular-nums; }
.wl-steps li[data-complete="true"] .wl-step-number { background: var(--brand-tint-2); color: var(--brand-ink); }
.wl-steps li:has([aria-current="step"]) { color: var(--brand-ink); }
.wl-steps li:has([aria-current="step"]) .wl-step-number { background: var(--brand); color: var(--g0); }

/* Blocks: 결재선 · 신청자 · 신청 내용 (board .msec). Inside the shared modal the body padding already spaces the steps. */
.t-request-embedded > .wl-steps ol { margin-top: 0; }
.wl-compose-block { margin: 20px 0 0; }
/* 신청자: one document-table row of label/value pairs (board frame 2). The div wrappers step out of the grid.
   dl.* matches the specificity of dl.wl-form-table; this file loads after ui.css, so these rules win. */
dl.wl-compose-applicant { grid-template-columns: repeat(4, max-content minmax(0, 1fr)); margin: 0; }
.wl-compose-applicant > div { display: contents; }
.wl-compose-applicant > div > dt { min-width: 0; }
:is(.wl-compose-block, .wl-compose-summary, .t-request-preview) > h3, .wl-compose-doc-head h3 { margin: 0 0 8px; color: var(--g900); font: 700 14.5px/1.4 var(--font-ui); letter-spacing: -.02em; }
.wl-compose-main { margin: 24px 0 0; }
.wl-compose-doc-head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; margin: 0 0 8px; }
.wl-compose-doc-head h3 { margin: 0; }
.wl-compose-kind { display: flex; align-items: center; gap: 8px; color: var(--g700); font-size: 13px; font-weight: 600; }
.wl-compose-kind .t-btn { min-height: 28px; padding: 0 10px; }
.wl-compose-date, .wl-compose-fields { margin: 0; }
.t-annual-context:not([hidden]) ~ .t-annual-compose-form .wl-compose-fields { border-top-width: 0; }
.wl-compose-count { display: flex; justify-content: space-between; gap: 12px; width: 100%; margin: 0; color: var(--g600); font-size: 12px; }
.wl-compose-count > span:last-child { font-variant-numeric: tabular-nums; }

/* Type picker (TYPE step): two compact options. */
.t-request-type-picker { min-width: 0; margin: 16px 0 12px; padding: 0; border: 0; }
.t-request-type-picker legend { margin: 0 0 10px; padding: 0; color: var(--g900); font: 700 14.5px/1.4 var(--font-ui); letter-spacing: -.02em; }
.t-request-type-cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.t-request-type-card {
  position: relative; display: grid; grid-template-columns: 24px minmax(0, 1fr); align-items: center; gap: 2px 10px; min-height: 0;
  margin: 0; padding: 12px 40px 12px 14px; border: 1px solid var(--g300); border-radius: var(--r-pop); background: var(--g0); box-shadow: none; cursor: pointer;
}
.t-request-type-card[data-selected="true"] { border-color: var(--brand); background: var(--brand-tint); box-shadow: none; }
.t-request-type-card input { position: absolute; top: 50%; right: 14px; width: 16px; max-width: 16px; height: 16px; min-height: 0; margin: -8px 0 0; accent-color: var(--brand); }
.t-request-type-icon { grid-row: span 2; display: grid; place-items: center; width: 24px; height: 24px; color: var(--g600); background: none; }
.t-request-type-icon svg { width: 20px; height: 20px; }
.t-request-type-card strong { color: var(--g900); font: 700 14px/1.4 var(--font-ui); }
.t-request-type-card small { margin: 0; color: var(--g600); font-size: 12.5px; line-height: 1.5; }
.t-request-type-picker > .t-muted { margin: 8px 0 0; color: var(--g600); font-size: 12px; }

/* Widgets inside value cells. The row label names the group, so the legends stay visually hidden. */
.wl-form-value :is(.t-request-period-picker, .t-request-bucket-picker) { flex: 1 1 100%; min-width: 0; margin: 0; padding: 0; border: 0; }
.wl-form-value :is(.t-request-period-picker, .t-request-bucket-picker) > legend { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.t-request-period-sheet { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 0; border: 0; border-radius: 0; background: none; overflow: visible; }
button.t-request-period-trigger {
  display: inline-flex; align-items: center; gap: 8px; min-width: 200px; min-height: var(--h-ctl); height: auto; padding: 0 10px;
  border: 1px solid var(--field-line); border-radius: var(--r-ctl); background: var(--g0); color: var(--g900); box-shadow: none;
  font: 400 13px/1.3 var(--font-ui); text-align: left; cursor: pointer;
}
.t-request-period-trigger > span { color: var(--g600); font-size: 12px; font-weight: 600; }
.t-request-period-trigger > strong { flex: 1; font-weight: 600; font-variant-numeric: tabular-nums; }
.t-request-period-trigger > svg { width: 16px; height: 16px; color: var(--g600); }
.t-request-period-trigger[aria-expanded="true"] { border-color: var(--brand); box-shadow: inset 0 0 0 1px var(--brand); background: var(--g0); }
.t-request-period-trigger[aria-invalid="true"] { border-color: var(--danger); box-shadow: none; }
.t-request-period-trigger:hover:not(:disabled, [aria-expanded="true"]) { background: var(--g50); }
.t-request-period-error { width: 100%; margin: 4px 0 0; color: var(--danger); font-size: 12px; font-weight: 600; }
.t-request-calendar { flex: 1 1 100%; max-width: 320px; padding: 8px; border: 1px solid var(--g200); border-radius: var(--r-pop); background: var(--g0); box-shadow: var(--shadow-pop); }
.t-request-calendar-head h4 { margin: 0; color: var(--g900); font: 700 13px/1.4 var(--font-ui); }
.t-request-calendar-days > .t-btn { min-width: 0; padding: 0; font-variant-numeric: tabular-nums; }
/* Stage 1 base buttons erased these legacy states: restore today and the selected day here. */
.t-request-calendar-days > .t-btn[aria-current="date"]:not([aria-pressed="true"]) { border-color: var(--brand); color: var(--brand-ink); }
.t-request-calendar-days > .t-btn[aria-pressed="true"] { border-color: var(--brand); background: var(--brand); color: var(--g0); font-weight: 700; }
.t-request-bucket-sheet { border: 1px solid var(--g200); border-radius: var(--r-ctl); background: var(--g0); }
:is(.t-request-bucket-head, .t-request-bucket-row) { align-items: center; padding: 0 12px; font-size: 13px; }
.t-request-bucket-head { min-height: 32px; background: var(--g50); color: var(--g700); font-weight: 600; }
.t-request-bucket-row { min-height: var(--h-row); border-top: 1px solid var(--g150); }
.t-request-bucket-row:has(input:checked) { background: var(--brand-tint); box-shadow: inset 3px 0 var(--brand); }
.t-request-bucket-row strong { color: var(--brand-ink); }
.t-request-bucket-choice input { accent-color: var(--brand); }
.t-request-correction-source { flex: 1 1 100%; display: grid; gap: 4px; margin: 0; padding: 10px 12px; border: 0; border-radius: var(--r-pop); background: var(--g50); font-size: 13px; }
.t-request-correction-source > div { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 4px 12px; }
.t-request-correction-source ul { margin: 0; padding-left: 18px; }
.t-request-correction-source p { margin: 0; color: var(--g600); font-size: 12.5px; }
.t-request-correction-inputs { display: flex; flex-wrap: wrap; gap: 8px 16px; flex: 1 1 100%; }
.wl-form-value .t-request-field { display: flex; align-items: center; gap: 8px; margin: 0; }
.wl-form-value .t-request-field > label { margin: 0; color: var(--g700); font: 500 12.5px/1.4 var(--font-ui); white-space: nowrap; }
.wl-form-value .t-break-editor { flex: 1 1 100%; }
.t-btn.t-break-remove {
  display: inline-flex; align-items: center; justify-content: center; min-height: 28px; padding: 0 8px;
  border: 1px solid var(--g300); border-radius: var(--r-ctl); background: var(--g0); color: var(--g700); box-shadow: none;
  font: 600 12.5px/1.2 var(--font-ui); cursor: pointer;
}
.t-btn.t-break-remove:hover:not(:disabled) { border-color: var(--g400); background: var(--g50); }

/* Preview, summary and the action row that stays at the bottom of the scroll area. */
.t-request-preview { margin: 20px 0 0; padding: 16px; border: 1px solid var(--brand-tint-2); border-radius: var(--r-pop); background: var(--brand-tint); }
.t-request-review-kind { margin: 0 0 10px; color: var(--g700); font-size: 13px; font-weight: 600; }
.t-request-review-totals { display: flex; flex-wrap: wrap; gap: 4px 16px; margin: 12px 0 0; font-size: 13px; }
.t-request-review-totals p { margin: 0; }
.t-request-review-quantity { color: var(--brand-ink); font-weight: 700; }
.t-request-review-quantity small { margin-left: 6px; color: var(--g600); font-weight: 500; }
.t-request-preview > p { margin: 6px 0 0; color: var(--g700); font-size: 13px; }
.wl-compose-summary { margin: 20px 0 0; padding: 14px 16px; border: 1px solid var(--g200); border-radius: var(--r-pop); background: var(--g0); }
.wl-compose-actions {
  position: sticky; bottom: 0; z-index: 1; display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 6px;
  margin: 16px 0 0; padding: 12px 0; border-top: 1px solid var(--g200); background: var(--g0);
}
.t-request-page-link { display: inline-block; margin: 12px 0 0; color: var(--link); font-size: 12.5px; }

@media (max-width: 600px), (pointer: coarse) {
  .t-btn.t-request-close { width: 44px; min-width: 44px; height: 44px; min-height: 44px; }
}
@media (max-width: 600px) {
  .t-request-dialog { width: 100%; max-width: 100%; max-height: calc(100dvh - 16px); margin: auto 0 0; padding: 0 16px; border-radius: 12px 12px 0 0; }
  .wl-compose-head { margin: 0 -16px; padding: 0 4px 0 16px; }
  .wl-compose-dismiss { margin: 20px -16px 0; padding: 10px 16px max(10px, env(safe-area-inset-bottom)); }
  dl.wl-compose-applicant { grid-template-columns: repeat(2, max-content minmax(0, 1fr)); }
  .wl-compose-applicant > div > dt { padding: 9px 12px; border-bottom: 1px solid var(--g200); }
  .t-request-type-cards { grid-template-columns: minmax(0, 1fr); }
  button.t-request-period-trigger { flex: 1 1 100%; }
  .wl-compose-actions > .t-btn { flex: 1 0 auto; }
}
```

- [ ] **Step 7: 통과 확인**

Run: `npm --prefix frontend test -- src/pages/AnnualRequestPage.test.tsx src/pages/AnnualRequestPage.responsive.test.tsx src/App.requestModal.test.tsx src/cssLayers.test.js src/sheetAssets.test.ts`
Expected: PASS

Run: `npm --prefix frontend test && npm --prefix frontend run build`
Expected: 전체 통과, 빌드 성공

- [ ] **Step 8: 커밋**

```bash
git add frontend/src/pages/AnnualRequestPage.tsx frontend/src/pages/AnnualRequestPage.test.tsx frontend/src/App.tsx frontend/src/App.requestModal.test.tsx frontend/src/design/request.css frontend/src/cssLayers.test.js
git commit -m "feat: lay out the request composer as an approval document" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 신청 상세·결재 처리

**Files:**
- Modify: `frontend/src/pages/WorkflowPages.tsx:1-20, 170-343`
- Modify: `frontend/src/pages/WorkflowPages.test.tsx` (테스트 추가)
- Modify: `frontend/src/App.requestModal.test.tsx` (테스트 추가)
- Modify: `frontend/src/design/request.css` (끝에 추가)

- [ ] **Step 1: 실패하는 테스트 작성**

`WorkflowPages.test.tsx`의 `'신청 상세는 내 신청으로 돌아가는 링크와 한글 상태·정보 카드를 표시한다'` 테스트 바로 위에 다음을 더한다.

```tsx
  it('결재 진행은 신청자·현재 결재 담당과 현재 상태를 결재란으로 보여 준다', async () => {
    const view = await render('request/detail', { request, detail: { days: [], history: [], correction: null } });
    const approval = view.getByRole('region', { name: '결재 진행' });
    const steps = within(within(approval).getByRole('list', { name: '결재란' })).getAllByRole('listitem');
    expect(steps.map(step => step.querySelector('strong')?.textContent)).toEqual([request.targetEmployeeName, '승인자']);
    expect(within(steps[1]).getByText('결재 대기').className).toBe('t-tag amber');
  });
```

`App.requestModal.test.tsx`의 마지막 테스트 뒤(닫는 `});` 앞)에 다음을 더한다.

```tsx
  it('결재 처리 상세를 목록 위 모달로 열고 문서형 결재 폼을 보여 준 뒤 초점을 되돌린다', async () => {
    const row = { id:'request-9', targetEmployeeName:'합성 직원', kind:'LEAVE_DAY', fromDate:'2026-11-05', toDate:'2026-11-06', quantityDays:2, status:'PENDING', effectStatus:'NONE', version:4, reason:'합성 휴가', approverName:'합성 조직장', originalRequestId:null };
    const detail: PageEnvelope = { page:'request/detail', csrf, data:{ ...shared, request:row, detail:{ days:[], history:[], correction:null }, canDecide:true, pageCommand:'decision-command' } };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response(detail,'/requests/request-9'));
    vi.stubGlobal('fetch', fetcher);
    render(<App initial={envelope(null,{requests:[row]})} />);
    const trigger = screen.getByRole('link',{name:'종일 연차 상세'});
    trigger.focus(); fireEvent.click(trigger);
    const dialog = await screen.findByRole('dialog',{name:'신청 상세'});
    expect(document.activeElement).toBe(within(dialog).getByRole('heading',{name:'신청 상세',level:2}));
    const steps = within(within(dialog).getByRole('list',{name:'결재란'})).getAllByRole('listitem');
    expect(steps.map(step => step.querySelector('strong')?.textContent)).toEqual(['합성 직원','합성 조직장']);
    for (const name of ['승인 검토','반려 검토']) {
      const form = within(dialog).getByRole('form',{name});
      expect(form.querySelector('textarea[name="reason"]')?.closest('.wl-form-row')).toBeTruthy();
    }
    expect(within(dialog).getAllByRole('button',{name:'닫기'})).toHaveLength(2);
    fireEvent(dialog,new Event('cancel',{cancelable:true}));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(document.activeElement).toBe(trigger);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/pages/WorkflowPages.test.tsx src/App.requestModal.test.tsx`
Expected: FAIL. "결재 진행" region과 결재란이 없고, 사유 칸이 문서형 표 행 안에 없다.

- [ ] **Step 3: 상세 화면 구현**

`WorkflowPages.tsx` 위쪽 import에 다음을 더한다. `../ui` import 묶음에는 `FormTable`을 더한다.

```tsx
import { ApprovalStamps } from '../components/ApprovalStamps';
import { Notice } from '../components/Notice';
```

`RequestDetailPage`의 `const versionFields = (…);` 다음 줄에 결재란 색을 정한다.

```tsx
  const approvalTone = r.status === 'APPROVED' ? 'ok' : r.status === 'REJECTED' ? 'danger' : r.status === 'PENDING' ? 'warn' : undefined;
```

`<div className="t-request-detail-document">` 바로 다음 줄에 결재 진행 구역을 넣는다.

```tsx
        <section className="t-card wl-detail-approval" aria-labelledby="request-record-approval-title">
          <h2 id="request-record-approval-title">결재 진행</h2>
          <ApprovalStamps steps={[
            { role: '기안', name: r.targetEmployeeName, detail: d.routeSnapshot?.organizationName, state: '제출' },
            { role: '결재', name: r.approverName || '미지정', detail: d.routeSnapshot?.routeName, state: statusName(r.status), tone: approvalTone },
          ]} />
        </section>
```

`<dl className="t-request-detail-values">` 세 곳(제출 당시 결재선, 요청한 정정, 제출 당시 근태정책)을 모두 `<dl className="wl-form-table">`로 바꾼다. 안내 문장 두 곳은 `Notice`로 바꾼다.

```tsx
          {r.kind === 'CANCEL_LEAVE' && <Notice>취소 심사 중에는 원승인·차감이 유지됩니다.</Notice>}
```

```tsx
            <Notice>원천 기록은 보존하며 승인된 최신 정정 효과만 적용합니다.</Notice>
```

결정·변경 폼 다섯 개(276–337행)를 아래로 바꾼다. 폼 이름, 주소, hidden 필드, 버튼 이름은 그대로이고, 사유·선택 칸만 문서형 표에 넣는다.

```tsx
            {m.canDecide && (
              <>
                <Form action={`/requests/${r.id}/approve`} method="post" className="wl-form wl-decision-form" aria-label="승인 검토">
                  <h3>승인</h3>
                  <p>{r.kind === 'LEAVE_DAY' ? '승인하면 신청 상태와 실제 연차 차감이 함께 반영됩니다.' : '승인하면 신청한 내용의 효력이 반영됩니다.'}</p>
                  {versionFields}
                  <FormTable><Reason label="승인 검토 사유" /></FormTable>
                  <div className="wl-form-actions"><button className="t-btn primary">승인하기</button></div>
                </Form>
                <Form action={`/requests/${r.id}/reject`} method="post" className="wl-form wl-decision-form" aria-label="반려 검토">
                  <h3>반려</h3>
                  <p>보완이 필요한 내용을 사유로 남겨 주세요.</p>
                  {versionFields}
                  <FormTable><Reason label="반려 사유" /></FormTable>
                  <div className="wl-form-actions"><button className="t-btn secondary">반려하기</button></div>
                </Form>
              </>
            )}
            {m.canWithdraw && (
              <Form action={`/requests/${r.id}/withdraw`} method="post" className="wl-form wl-decision-form">
                {versionFields}
                <FormTable><Reason label="철회 사유" /></FormTable>
                <div className="wl-form-actions"><button className="t-btn secondary">대기 신청 철회</button></div>
              </Form>
            )}
            {m.canCancel && (
              <Form action={`/requests/${r.id}/cancellations`} method="post" className="wl-form wl-decision-form">
                {versionFields}
                <FormTable>
                  <Reason label="취소 요청 사유" />
                  <p>
                    예정 시작 전 본인 요청만 일반 취소를 지원합니다. 지난 날짜는 담당자 예외 경로가
                    필요합니다.
                  </p>
                </FormTable>
                <div className="wl-form-actions"><button className="t-btn secondary">취소 심사 요청</button></div>
              </Form>
            )}
            {m.admin && r.status === 'PENDING' && (
              <Form action={`/admin/requests/${r.id}/reassign`} method="post" className="wl-form wl-decision-form">
                <h3>대기 건 명시 재할당</h3>
                {versionFields}
                <FormTable>
                  <SelectField label="새 승인자" name="approverEmployeeId" required>
                    {(m.candidates ?? []).map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.displayName}
                      </option>
                    ))}
                  </SelectField>
                  <Reason label="재할당 사유" />
                </FormTable>
                <div className="wl-form-actions"><button className="t-btn">재할당 저장</button></div>
              </Form>
            )}
            {m.canCancelException && (
              <Form
                action={`/admin/requests/${r.id}/cancellation-exceptions`}
                method="post"
                className="wl-form wl-decision-form"
              >
                <h3>과거·퇴사자 예외 취소</h3>
                {versionFields}
                <FormTable>
                  <Reason label="검토 근거와 예외 취소 사유" />
                  <p>다른 적격 승인자의 독립 승인이 필요합니다.</p>
                </FormTable>
                <div className="wl-form-actions"><button className="t-btn">예외 취소 심사 요청</button></div>
              </Form>
            )}
```

- [ ] **Step 4: 상세 CSS 추가**

`design/request.css` 끝에 다음을 더한다.

```css
/* Request detail and approval processing. One column in the L modal; legacy two-column grids and cards are switched off. */
.t-request-detail-page { padding: 0; border: 0; border-radius: 0; background: transparent; color: var(--g900); }
.t-request-detail-page :is(.t-request-detail-document, .t-request-detail-columns, .t-request-detail-main, .t-request-detail-side) { display: block; margin: 0; }
.t-request-detail-page .t-card { margin: 0; padding: 20px 0; border: 0; border-top: 1px solid var(--g200); border-radius: 0; background: transparent; box-shadow: none; }
.t-request-detail-page .t-request-detail-document > .t-card:first-child { padding-top: 4px; border-top: 0; }
.t-request-detail-page h2 { margin: 0 0 10px; color: var(--g900); font: 700 15px/1.4 var(--font-ui); letter-spacing: -.02em; }
.t-request-detail-page h3 { margin: 0 0 4px; color: var(--g900); font: 700 14px/1.4 var(--font-ui); }
.t-request-detail-page p { line-height: 1.6; }
.t-request-detail-page :is(.hint, .t-muted) { color: var(--g600); font-size: 12.5px; line-height: 1.6; }
.t-request-detail-page .t-card > .hint { margin: 8px 0 0; }
.t-request-detail-page .wl-form-table { margin-bottom: 0; }
.t-request-detail-backlinks { display: flex; flex-wrap: wrap; gap: 6px; }
.t-btn:is(.t-request-detail-back, .t-request-detail-original) {
  display: inline-flex; align-items: center; gap: 6px; min-height: var(--h-ctl); padding: 0 12px;
  border: 1px solid var(--g300); border-radius: var(--r-ctl); background: var(--g0); color: var(--g800); box-shadow: none;
  font: 600 13px/1.2 var(--font-ui); text-decoration: none; white-space: nowrap;
}
.t-btn:is(.t-request-detail-back, .t-request-detail-original):hover { border-color: var(--g400); background: var(--g50); }
.t-request-detail-original { margin-top: 12px; }
.t-request-detail-summary-head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 16px; }
.t-request-detail-summary-head h2 { margin: 0; }
.t-request-detail-summary-head .t-request-detail-person { margin: 2px 0 0; color: var(--g700); font-size: 13px; font-weight: 600; }
.t-request-detail-status { display: inline-flex; align-items: center; min-height: 24px; padding: 0 9px; border: 0; border-radius: var(--r-ctl); background: var(--g100); color: var(--g700); font: 600 12.5px/1 var(--font-ui); }
.t-request-detail-status[data-status="PENDING"] { background: var(--warn-bg); color: var(--warn); }
.t-request-detail-status[data-status="APPROVED"] { background: var(--ok-bg); color: var(--ok); }
.t-request-detail-status[data-status="REJECTED"] { background: var(--danger-bg); color: var(--danger); }
.t-request-detail-effect { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 12px; margin: 10px 0 12px; padding: 0; color: var(--g700); font-size: 13px; }
.t-request-detail-effect strong { color: var(--g900); font-weight: 600; }
.t-request-detail-effect > span { margin-left: auto; color: var(--g600); font-size: 12px; }
.t-request-detail-overview { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px 20px; padding: 12px 0 0; border-top: 1px solid var(--g150); }
.t-request-detail-fact h3 { margin: 0 0 2px; color: var(--g600); font: 500 12px/1.5 var(--font-ui); }
.t-request-detail-fact p { margin: 0; color: var(--g900); font: 600 14px/1.55 var(--font-ui); overflow-wrap: anywhere; }
.t-request-detail-fact .t-muted { font-size: 12px; font-weight: 400; }
.t-request-detail-dates { display: flex; flex-wrap: wrap; gap: 4px 6px; font-variant-numeric: tabular-nums; }
.t-request-detail-quantity { color: var(--brand-ink); font-size: 13px; font-weight: 700; }
.t-request-detail-reason { white-space: pre-wrap; }
.t-request-detail-days { margin: 0; padding: 0; border: 0; list-style: none; }
.t-request-detail-days li { display: flex; flex-wrap: wrap; gap: 4px 12px; padding: 8px 0; border-top: 1px solid var(--g150); font-size: 13px; font-variant-numeric: tabular-nums; }
.t-request-detail-days strong { color: var(--brand-ink); font-weight: 600; }
.t-request-detail-section-head { display: flex; align-items: baseline; gap: 8px; }
.t-request-detail-section-head h2 { margin-bottom: 0; }
.t-request-detail-timeline { margin: 10px 0 0; padding: 0; list-style: none; }
.t-request-detail-timeline li { padding: 10px 0; border-top: 1px solid var(--g150); }
.t-request-detail-event { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 10px; }
.t-request-detail-event strong { font-size: 13px; font-weight: 700; }
.t-request-detail-event time { color: var(--g600); font-size: 12px; font-variant-numeric: tabular-nums; }
.t-request-detail-transition { margin: 2px 0 0; color: var(--g700); font-size: 12.5px; }
.t-request-detail-timeline .t-request-detail-reason { margin: 4px 0 0; font-size: 13px; }
.t-request-detail-empty { margin: 8px 0 0; padding: 0; background: transparent; color: var(--g600); font-size: 13px; }
.t-request-decision-guide { margin: 0 0 4px; color: var(--g600); font-size: 12.5px; }
.wl-decision-form { margin: 16px 0 0; padding: 16px 0 0; border-top: 1px solid var(--g150); }
.wl-decision-form > p { margin: 0 0 10px; color: var(--g600); font-size: 12.5px; }
@media (max-width: 600px) {
  .t-request-detail-overview { grid-template-columns: minmax(0, 1fr); }
  .t-request-detail-effect > span { width: 100%; margin-left: 0; }
}
```

- [ ] **Step 5: 통과 확인**

Run: `npm --prefix frontend test -- src/pages/WorkflowPages.test.tsx src/App.requestModal.test.tsx`
Expected: PASS

Run: `npm --prefix frontend test`
Expected: 전체 통과

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/pages/WorkflowPages.tsx frontend/src/pages/WorkflowPages.test.tsx frontend/src/App.requestModal.test.tsx frontend/src/design/request.css
git commit -m "feat: show request detail as a document with approval stamps and decision forms" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 정정 신청

**Files:**
- Modify: `frontend/src/pages/WorkflowPages.tsx:344-435`
- Modify: `frontend/src/design/request.css` (끝에 추가)

- [ ] **Step 1: 기준 테스트 실행**

Run: `npm --prefix frontend test -- src/pages/WorkflowPages.test.tsx src/pages/CorrectionWorkspace.test.tsx`
Expected: PASS. 바꾸기 전 기준이다.

- [ ] **Step 2: 화면 구현**

`../ui` import 묶음에 `FormRow`를 더한다. `Correction`의 `return (…)` 전체를 아래로 바꾼다. hidden 필드, 필드 이름·라벨, 순서(원천 → 출퇴근 변경 → 확인·휴게 → 고급 → 사유 → 제출)는 그대로다.

```tsx
  return (
    <div className="t-correction-workspace">
      <PageHeader title="출퇴근·실제 휴게 정정 신청" help="확인한 기록과 실제 근로 내용을 입력해 주세요. 시각 기준은 Asia/Seoul (서울)입니다." />
      {m.exceptional && (
        <p className="notice">
          담당자 예외 신청입니다. 작성자·대상자와 다른 적격 승인자의 독립 승인이 필요합니다.
        </p>
      )}
      <section className="wl-section t-correction-source" aria-label="현재 원천 기록">
        <h2>현재 원천 기록</h2>
        <p className="hint">{fmtDate(m.day.workDate)} · 승인 전 원천은 유지됩니다.</p>
        <ul className="t-correction-events">
          {m.day.events.map((e) => (
            <li key={e.id}>
              <span>{e.kind === 'IN' ? '출근' : e.kind === 'OUT' ? '퇴근' : e.kind}</span><strong>{m.eventTimes?.[e.id] ?? fmtDateTime(e.occurredAt)}</strong>
            </li>
          ))}
          {!m.day.events.length && <li>조회된 원천 기록이 없습니다. 확인된 실제 근거로만 정정하세요.</li>}
        </ul>
        <p className="hint">원천은 수정·삭제하지 않으며 승인된 정정 효과를 추가합니다.</p>
      </section>
      <Form
        action={m.exceptional ? '/admin/requests/exceptions' : '/me/requests/correction'}
        method="post"
        className="wl-form t-correction-form"
        onChange={() => setDirty(true)}
        data-unsaved-changes={String(dirty)}
      >
        {hidden('targetEmployeeId', f.targetEmployeeId)}
        {hidden('workDate', f.workDate)}
        {hidden('baseInputRevision', f.baseInputRevision)}
        {hidden('sourceFingerprint', f.sourceFingerprint)}
        {hidden('commandId', f.commandId)}
        {hidden('exceptional', m.exceptional)}
        {f.predecessorRequestId && hidden('predecessorRequestId', f.predecessorRequestId)}
        <section className="wl-section">
          <h2>출퇴근 변경</h2>
          <p className="hint">유지·시각 수정·누락 정정 중 필요한 항목을 선택하세요.</p>
          <FormTable columns={2}>
            <SelectField label="출근 변경" name="inAction" value={f.inAction}>
              {m.actions.map((a) => <option key={a} value={a}>{actionName(a)}</option>)}
            </SelectField>
            <Field label="제안 출근 시각 (서울)" name="requestedInAt" type="datetime-local" step="0.001" value={f.requestedInAt} />
            <SelectField label="퇴근 변경" name="outAction" value={f.outAction}>
              {m.actions.map((a) => <option key={a} value={a}>{actionName(a)}</option>)}
            </SelectField>
            <Field label="제안 퇴근 시각 (서울)" name="requestedOutAt" type="datetime-local" step="0.001" value={f.requestedOutAt} />
          </FormTable>
          <p className="hint">시각 수정일 때만 제안 시각을 입력하세요. 유지·누락으로 정정할 때는 제안 시각을 비워 주세요.</p>
        </section>
        <section className="wl-section">
          <h2>실제 근로·휴게와 사유</h2>
          <p className="hint">실제 확인한 근거를 기준으로 작성해 주세요.</p>
          <FormTable>
            <FormRow label="실제 확인">
              <Check name="presenceAsWork" label="제안 구간의 실제 근로를 확인했습니다" checked={f.presenceAsWork} />
              <Check name="breaksConfirmed" label="실제 휴게를 확인했습니다 (없으면 아래 비움)" checked={f.breaksConfirmed} />
            </FormRow>
            <FormRow label="휴게 구간">
              <RequestBreakEditor value={breaks} onChange={value => { setBreaks(value); setDirty(true); }} />
            </FormRow>
          </FormTable>
          <details className="t-correction-advanced"><summary>기존 승인 정정을 다시 변경하는 경우</summary><FormTable><Field label="변경할 현재 승인 정정 ID (있으면)" name="replacesRequestId" value={f.replacesRequestId ?? ''} /></FormTable></details>
          <FormTable>
            <Reason label="정정 사유와 사실 근거" value={f.reason} />
            <p className="hint">
              민감정보를 적지 마세요. 확인되지 않은 실제 근로는 정상0시간으로 간주하지 않습니다.
            </p>
          </FormTable>
        </section>
        <div className="wl-form-actions"><span className="wl-modal-note">작성자·대상자와 다른 승인자가 내용을 검토합니다.</span><button className="t-btn primary">정정 심사 요청</button></div>
      </Form>
    </div>
  );
```

- [ ] **Step 3: 정정 CSS 추가**

`design/request.css` 끝에 다음을 더한다.

```css
/* Correction request (request/correction). */
.t-correction-workspace { min-width: 0; color: var(--g900); }
.t-correction-events { display: grid; margin: 0 0 8px; padding: 0; border-top: 2px solid var(--g800); list-style: none; }
.t-correction-events li { display: flex; justify-content: space-between; gap: 12px; padding: 8px 12px; border-bottom: 1px solid var(--g200); font-size: 13px; }
.t-correction-events strong { font-weight: 600; font-variant-numeric: tabular-nums; }
.t-correction-advanced { margin: 0 0 16px; }
.t-correction-advanced summary { color: var(--g700); font-size: 13px; font-weight: 600; cursor: pointer; }
.t-correction-advanced[open] .wl-form-table { margin-top: 8px; }
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix frontend test -- src/pages/WorkflowPages.test.tsx src/pages/CorrectionWorkspace.test.tsx src/components/RequestBreakEditor.test.tsx`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/pages/WorkflowPages.tsx frontend/src/design/request.css
git commit -m "feat: move the correction request form to document tables" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 사원·조직·계정 화면

**Files:**
- Modify: `frontend/src/pages/AdminPages.tsx:1-146, 354-452, 454-626, 628-816`

- [ ] **Step 1: 기준 테스트 실행**

Run: `npm --prefix frontend test -- src/pages/AdminPages.test.tsx src/App.modal.test.tsx src/App.test.tsx`
Expected: PASS

- [ ] **Step 2: 공용 필드 helper**

`../ui` import 묶음에 `FormRow`, `FormTable`을 더한다. `EmployeeFields`의 `return (…)`을 아래로 바꾼다. 필드 속성은 그대로다.

```tsx
  return (
    <>
      <CommandFields form={form} />
      <FormTable>
        <Field
          id="employee-number"
          label="사번"
          name="employeeNo"
          value={form.employeeNo}
          maxLength={40}
          readOnly={editing}
          required={!editing}
          aria-describedby="employee-number-help"
        />
        <p id="employee-number-help" className="hint">
          {editing
            ? '등록한 사번은 변경할 수 없습니다.'
            : '등록 후 변경하거나 다른 사원에게 재사용할 수 없습니다.'}
        </p>
        <Field
          id="employee-name"
          label="이름"
          name="displayName"
          value={form.displayName}
          maxLength={120}
          required
        />
        <FormRow label="재직 기간" required>
          <Field
            id="employee-joined"
            label="입사일"
            name="joinedOn"
            type="date"
            value={form.joinedOn ?? ''}
            required
          />
          <Field
            id="employee-left"
            label="마지막 재직일 (선택)"
            name="leftOn"
            type="date"
            value={form.leftOn ?? ''}
            aria-describedby="employee-left-help"
          />
        </FormRow>
        <p id="employee-left-help" className="hint">
          마지막 재직일 당일까지 재직으로 처리합니다. 미정이면 비워 두세요.
        </p>
        <FormRow label="상태"><Check name="active" label="활성 사원" checked={form.active} /></FormRow>
        {editing && (
          <p className="hint">
            재직일이나 활성 상태를 변경하면 연결된 계정의 기존 로그인이 종료됩니다.
          </p>
        )}
        <Reason id="employee-reason" form={form} label={editing ? '변경 사유' : '등록 사유'} />
      </FormTable>
    </>
  );
```

`OrganizationFields`의 `return (…)`도 아래로 바꾼다. 필드·안내 문장·조건은 그대로다.

```tsx
  return (
    <>
      <CommandFields form={form} />
      <FormTable>
        <Field
          id="organization-code"
          label="조직 코드"
          name="code"
          value={form.code}
          maxLength={40}
          readOnly={Boolean(organizationId)}
          required={!organizationId}
        />
        <p className="hint">
          {organizationId
            ? '등록한 코드는 변경할 수 없습니다.'
            : '등록 후 코드는 변경할 수 없습니다.'}
        </p>
        <Field
          id="organization-name"
          label="조직 이름"
          name="name"
          value={form.name}
          maxLength={120}
          required
        />
        <SelectField
          id="organization-parent"
          label="상위 조직"
          name="parentId"
          value={form.parentId ?? ''}
        >
          <option value="">없음 (최상위 조직)</option>
          {options
            .filter((option) =>
              organizationId
                ? (option.active && option.id !== organizationId) || option.id === form.parentId
                : option.active,
            )
            .map((option) => (
              <option key={option.id} value={option.id}>
                {option.name}
                {option.active ? '' : ' (비활성)'}
              </option>
            ))}
        </SelectField>
        {organizationId && (
          <p className="hint">
            새 상위 조직은 활성 조직을 선택하세요. 비활성 조직은 기존 부모를 유지해 이름을 수정할 수
            있지만 부모가 비활성이면 재활성화할 수 없습니다. 하위 조직을 상위 조직으로 지정할 수
            없습니다.
          </p>
        )}
        <FormRow label="상태"><Check label="활성 조직" name="active" checked={form.active} /></FormRow>
        {organizationId && (
          <p className="hint">
            활성 하위 조직이나 오늘·미래의 소속이 남아 있으면 비활성화할 수 없습니다.
          </p>
        )}
        <Reason
          id="organization-reason"
          form={form}
          label={organizationId ? '변경 사유' : '등록 사유'}
        />
      </FormTable>
    </>
  );
```

- [ ] **Step 3: 사원 상세(`EmployeePage`)**

`return (…)`을 아래로 바꾼다. 링크 글자와 주소, 시트 정의는 그대로다.

```tsx
  return (
    <div className="t-legacy-page admin-shell">
      <PageHeader title={m.employee.displayName} actions={<>
        <a className="t-btn" href="/admin/employees">사원 목록으로</a>
        <a className="t-btn" href={`${employeePath}/personnel`}>인사정보·사원사진 등록</a>
        <a className="t-btn" href={`${employeePath}/access`}>계정 초대·역할·승인자 관리</a>
        <a className="t-btn" href={`${employeePath}/schedule`}>고정 근무 템플릿 배정</a>
      </>} />
      <ErrorNotice message={m.error} />
      <section className="wl-section" aria-labelledby="employee-change-title">
        <h2 id="employee-change-title">기본정보·재직 변경</h2>
        <Form action={`${employeePath}/changes`} method="post" className="wl-form">
          <EmployeeFields form={m.form} editing />
          <div className="wl-form-actions">
            <a className="t-btn" href={employeePath}>입력 취소</a>
            <button type="submit" className="t-btn primary">사원 정보 저장</button>
          </div>
        </Form>
      </section>
      <section className="wl-section" aria-labelledby="assignment-history-title">
        <h2 id="assignment-history-title">소속 이력</h2>
        <p className="hint">
          시작일은 포함하고 종료일은 포함하지 않습니다. 종료일에 새 소속이 시작됩니다.
        </p>
        {m.assignments.length === 0 ? (
          <p className="notice">등록된 소속이 없습니다. 아래에서 첫 소속을 등록해 주세요.</p>
        ) : (
          <WorklogSheet
            label="소속 이력"
            rows={m.assignments}
            getRowId={(assignment) => assignment.id}
            scope="all"
            columns={[
              { field: 'organizationName', headerName: '조직', minWidth: 170, pinned: 'left' },
              sheetColumn<EmployeeModel['assignments'][number]>(
                { colId: 'validFrom', headerName: '시작일 (포함)', valueGetter: ({ data }) => data && fmtDate(data.validFrom), minWidth: 160 },
                (assignment) => <time dateTime={assignment.validFrom}>{fmtDate(assignment.validFrom)}</time>,
              ),
              { colId: 'validTo', headerName: '종료일 (제외)', valueGetter: ({ data }) => data?.validTo ? fmtDate(data.validTo) : '종료일 없음', minWidth: 160 },
              { field: 'reason', headerName: '사유', minWidth: 240 },
            ]}
          />
        )}
      </section>
      <section className="wl-section" aria-labelledby="assignment-create-title">
        <h2 id="assignment-create-title">소속 등록·이동</h2>
        <p className="hint" id="assignment-date-help">
          적용일은 오늘부터 선택할 수 있으며 입사일부터 마지막 재직일까지여야 합니다. 기존 이력이
          있으면 마지막 시작일보다 늦은 날만 선택할 수 있습니다. 과거 이력 수정·같은 날 재배정·예약
          취소는 제공하지 않습니다.
        </p>
        <Form action={`${employeePath}/assignments`} method="post" className="wl-form">
          <CommandFields form={m.assignmentForm} />
          <FormTable>
            <SelectField
              id="assignment-organization"
              label="새 소속 조직"
              name="organizationId"
              value={m.assignmentForm.organizationId ?? ''}
              required
              aria-describedby="assignment-organization-help"
            >
              <option value="">조직을 선택하세요</option>
              {m.options
                .filter((option) => option.active)
                .map((option) => (
                  <option key={option.id} value={option.id}>
                    {option.name}
                  </option>
                ))}
            </SelectField>
            <p className="hint" id="assignment-organization-help">
              활성 조직만 선택할 수 있습니다. 조직이 없으면{' '}
              <a href="/admin/organizations">조직 관리</a>에서 등록해 주세요.
            </p>
            <Field
              id="assignment-from"
              label="적용 시작일"
              name="validFrom"
              type="date"
              value={m.assignmentForm.validFrom ?? ''}
              required
              aria-describedby="assignment-date-help"
            />
            <Reason id="assignment-reason" form={m.assignmentForm} label="소속 등록·이동 사유" />
            <p className="hint">기존 소속 이력은 보존됩니다.</p>
          </FormTable>
          <div className="wl-form-actions">
            <button type="submit" className="t-btn primary">소속 등록·이동</button>
          </div>
        </Form>
      </section>
    </div>
  );
```

- [ ] **Step 4: 조직 상세(`OrganizationPage`)**

```tsx
  return (
    <div className="t-legacy-page admin-shell">
      <PageHeader title={m.organization.name} actions={<a className="t-btn" href="/admin/organizations">조직 목록으로</a>} />
      <section className="wl-section" aria-labelledby="organization-change-title">
        <h2 id="organization-change-title">조직 정보 변경</h2>
        <ErrorNotice message={m.error} />
        <Form action={`${path}/changes`} method="post" className="wl-form">
          <OrganizationFields
            form={m.form}
            options={m.options}
            organizationId={m.organization.id}
          />
          <div className="wl-form-actions">
            <a className="t-btn" href={path}>입력 취소</a>
            <button type="submit" className="t-btn primary">조직 정보 저장</button>
          </div>
        </Form>
      </section>
    </div>
  );
```

- [ ] **Step 5: 계정·권한(`AccessPage`)**

아래 다섯 가지를 바꾼다. 안내 문장, 조건, hidden 필드 순서는 그대로다.

1. 첫 두 줄(`<a href={path}>사원 상세로</a>`, `<h1>…</h1>`)을 `<PageHeader title={m.employee.displayName} actions={<a className="t-btn" href={path}>사원 상세로</a>} />` 한 줄로 바꾼다.
2. 세 `section`의 `className="panel"`을 `className="wl-section"`으로 바꾼다.
3. 초대 폼:

```tsx
            <Form action={`${path}/invitations`} method="post" className="wl-form">
              <CommandFields form={m.invitationForm} />
              <FormTable>
                <Field
                  id="invitation-email"
                  label="초대 이메일"
                  name="email"
                  type="email"
                  maxLength={254}
                  value={m.invitationForm.email}
                  readOnly={Boolean(account)}
                  required
                />
                <Reason id="invitation-reason" form={m.invitationForm} label="초대 사유" />
              </FormTable>
              <div className="wl-form-actions">
                <button type="submit" className="t-btn primary" disabled={!m.localDelivery}>
                  초대 발급
                </button>
              </div>
            </Form>
```

4. 역할 폼. hidden `roles=EMPLOYEE`가 체크 상자보다 앞에 남는다.

```tsx
          <Form action={`${path}/roles`} method="post" className="wl-form">
            <CommandFields form={m.rolesForm} />
            <Hidden name="roles" value="EMPLOYEE" />
            <FormTable>
              <FormRow label="역할">
                <p>공통 (EMPLOYEE) · 필수</p>
                <Check
                  name="roles"
                  label="승인자 (APPROVER)"
                  value="APPROVER"
                  checked={m.rolesForm.roles?.includes('APPROVER') ?? false}
                />
                <Check
                  name="roles"
                  label="근태 담당자 (ATTENDANCE_ADMIN)"
                  value="ATTENDANCE_ADMIN"
                  checked={m.rolesForm.roles?.includes('ATTENDANCE_ADMIN') ?? false}
                />
                <Check
                  name="roles"
                  label="대표 (COMPANY_REPRESENTATIVE)"
                  value="COMPANY_REPRESENTATIVE"
                  checked={m.rolesForm.roles?.includes('COMPANY_REPRESENTATIVE') ?? false}
                />
              </FormRow>
              <p className="hint">
                마지막 유효 담당자는 다른 담당자를 연결하기 전 회수할 수 없습니다.
              </p>
              <Reason id="roles-reason" form={m.rolesForm} label="역할 변경 사유" />
            </FormTable>
            <div className="wl-form-actions">
              <button type="submit" className="t-btn primary">역할 저장</button>
            </div>
          </Form>
```

5. 승인자 폼과 이력 시트:

```tsx
          <Form action="/admin/approver-assignments" method="post" className="wl-form">
            <Hidden name="employeeId" value={m.employee.id} />
            <CommandFields form={m.approverForm} />
            <FormTable>
              <SelectField
                id="approver-id"
                label="새 승인자"
                name="approverEmployeeId"
                value={m.approverForm.approverEmployeeId ?? ''}
              >
                <option value="">승인자 없음 (명시 해제)</option>
                {m.selectedApproverMissing && (
                  <option value={m.approverForm.approverEmployeeId ?? ''}>
                    현재 선택값 (재지정 필요)
                  </option>
                )}
                {m.candidates
                  .filter((candidate) => candidate.id !== m.employee.id)
                  .map((candidate) => (
                    <option key={candidate.id} value={candidate.id}>
                      {candidate.displayName}
                    </option>
                  ))}
              </SelectField>
              <Field
                id="approver-from"
                label="적용일"
                name="validFrom"
                type="date"
                readOnly
                required
                value={m.approverForm.validFrom}
              />
              <p className="hint">
                오늘 즉시 지정·교체·해제만 지원합니다. 당일 변경 전 관계도 이력으로 보존합니다.
              </p>
              <Reason id="approver-reason" form={m.approverForm} label="승인자 변경 사유" />
            </FormTable>
            <div className="wl-form-actions">
              <a className="t-btn" href={`${path}/access`}>최신 내용 다시 조회</a>
              <button type="submit" className="t-btn primary">승인자 저장</button>
            </div>
          </Form>
```

이력 시트는 `<WorklogSheet`와 `label="승인자 배정 이력"` 사이에 `title="승인자 배정 이력"`을 더한다.

```tsx
          <WorklogSheet
            title="승인자 배정 이력"
            label="승인자 배정 이력"
```

- [ ] **Step 6: 통과 확인**

Run: `npm --prefix frontend test -- src/pages/AdminPages.test.tsx src/App.modal.test.tsx src/App.test.tsx src/ui.test.tsx`
Expected: PASS

Run: `npm --prefix frontend run build`
Expected: 성공

- [ ] **Step 7: 커밋**

```bash
git add frontend/src/pages/AdminPages.tsx
git commit -m "feat: move employee, organization and access forms to document tables" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 근무 템플릿·배정·조직장·귀속 화면

**Files:**
- Modify: `frontend/src/pages/AdminPages.tsx:820-1079`
- Modify: `frontend/src/pages/OrgSheetPages.tsx:228-251`
- Modify: `frontend/src/pages/AnnualRequestPage.tsx:376-408`

- [ ] **Step 1: 기준 테스트 실행**

Run: `npm --prefix frontend test -- src/pages/AdminPages.test.tsx src/pages/OrgSheetPages.test.tsx src/pages/AnnualRequestPage.test.tsx`
Expected: PASS

- [ ] **Step 2: 근무 템플릿 필드(`ScheduleFields`)**

`return (…)`을 아래로 바꾼다. 필드와 요일 표는 그대로이고, 요일 표를 "요일별 근무" 행에 넣고 버튼을 동작 줄로 옮긴다.

```tsx
  return (
    <>
      <CommandFields form={form} />
      <FormTable>
        <Field
          id="template-code"
          label="템플릿 코드"
          name="code"
          maxLength={40}
          value={form.code}
          readOnly={Boolean(template)}
          required
        />
        <Field
          id="template-name"
          label="이름"
          name="name"
          maxLength={120}
          value={form.name}
          readOnly={Boolean(template)}
          required
        />
        <Field
          id="valid-from"
          label="버전 사용 시작일"
          name="validFrom"
          type="date"
          value={form.validFrom}
          min={today}
          required
        />
        <p className="hint">
          당일 시작·종료하는 하루 한 구간입니다. 예정 휴게는 실제 휴게 증거가 아닙니다. 복수 휴게는
          12:00-13:00,15:00-15:10처럼 입력하세요.
        </p>
        <FormRow label="요일별 근무">
          <div className="table-scroll" role="region" aria-label="요일별 근무와 예정 휴게" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">요일</th>
                  <th scope="col">근로일</th>
                  <th scope="col">시작</th>
                  <th scope="col">종료</th>
                  <th scope="col">예정 휴게</th>
                </tr>
              </thead>
              <tbody>
                {form.days.map((day, index) => (
                  <tr key={day.weekday}>
                    <td>
                      {weekdays[day.weekday - 1]}
                      <Hidden name={`days[${index}].weekday`} value={day.weekday} />
                    </td>
                    <td>
                      <Hidden name={`_days[${index}].workday`} value="on" />
                      <input
                        type="checkbox"
                        name={`days[${index}].workday`}
                        value="true"
                        defaultChecked={day.workday}
                        aria-label={`${weekdays[day.weekday - 1]}요일 근로일`}
                      />
                    </td>
                    <td>
                      <input
                        type="text"
                        name={`days[${index}].startTime`}
                        defaultValue={day.startTime}
                        placeholder="09:00"
                        maxLength={12}
                        aria-label={`${weekdays[day.weekday - 1]}요일 시작`}
                      />
                    </td>
                    <td>
                      <input
                        type="text"
                        name={`days[${index}].endTime`}
                        defaultValue={day.endTime}
                        placeholder="18:00"
                        maxLength={12}
                        aria-label={`${weekdays[day.weekday - 1]}요일 종료`}
                      />
                    </td>
                    <td>
                      <input
                        type="text"
                        name={`days[${index}].breaks`}
                        defaultValue={day.breaks}
                        maxLength={1000}
                        aria-label={`${weekdays[day.weekday - 1]}요일 예정 휴게`}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </FormRow>
        <Reason id="schedule-reason" form={form} label="등록·변경 사유" />
        <p className="hint">
          비근로일은 시각·휴게를 모두 비우세요. 법정 휴게·근로시간 적합성을 자동 보증하지 않습니다.
        </p>
      </FormTable>
      <div className="wl-form-actions">
        <button type="submit" className="t-btn primary">불변 버전 저장</button>
      </div>
    </>
  );
```

`ScheduleListPage`의 `<Form action="/admin/schedules" method="post" className="admin-form">`는 `className="wl-form"`으로 바꾼다. 같은 `ScheduleFields`를 쓰기 때문이다.

- [ ] **Step 3: 근무 템플릿 상세·배정**

`ScheduleDetailPage`의 처음 네 줄(`ErrorNotice`, `<a>템플릿 목록</a>`, `<h1>`, `<p>근무 시간 기준…</p>`)을 아래로 바꾼다.

```tsx
      <PageHeader title={m.template.name} help="근무 시간 기준: Asia/Seoul (서울)" actions={<a className="t-btn" href="/admin/schedules">템플릿 목록</a>} />
      <ErrorNotice message={m.error} />
```

이어서 아래를 바꾼다.
- 두 `section`의 `className="panel"`을 `className="wl-section"`으로 바꾼다.
- "새 버전 추가" 구역의 `<p>기존 버전과 배정은 유지합니다. …</p>`에 `className="hint"`를 더한다.
- 폼의 `className="admin-form"`을 `className="wl-form"`으로 바꾼다.

`ScheduleAssignmentPage`의 처음 세 줄(`ErrorNotice`, `<a>사원 상세</a>`, `<h1>`)을 아래로 바꾼다.

```tsx
      <PageHeader title={`${m.employee.displayName} · 근무 배정`} actions={<a className="t-btn" href={`/admin/employees/${m.employee.id}`}>사원 상세</a>} />
      <ErrorNotice message={m.error} />
```

이어서 아래를 바꾼다.
- 두 `section`을 `wl-section`으로 바꾼다.
- "적용일 배정" 구역의 설명 `<p>`에 `className="hint"`를 더한다.
- 폼을 아래로 바꾼다.

```tsx
        <Form
          action={`/admin/employees/${m.employee.id}/schedule`}
          method="post"
          className="wl-form"
        >
          <CommandFields form={m.form} />
          <FormTable>
            <SelectField
              id="schedule-version"
              label="템플릿 버전"
              name="scheduleVersionId"
              value={m.form.scheduleVersionId ?? ''}
              required
            >
              <option value="">선택하세요</option>
              {m.versions.map((version) => (
                <option key={version.id} value={version.id}>
                  {m.templateNames[version.templateId]} / v{version.revision} /{' '}
                  {fmtDate(version.validFrom)}
                </option>
              ))}
            </SelectField>
            <Field
              id="schedule-assignment-from"
              label="적용 시작일"
              name="validFrom"
              type="date"
              min={m.today}
              value={m.form.validFrom}
              required
            />
            <Reason id="schedule-assignment-reason" form={m.form} label="배정 사유" />
          </FormTable>
          <div className="wl-form-actions">
            <button type="submit" className="t-btn primary">근무 배정 저장</button>
          </div>
        </Form>
```

- [ ] **Step 4: 조직장 배정(`OrganizationHeadsPage`)**

`OrgSheetPages.tsx`의 `../ui` import에 `FormTable`을 더한다. `return <div className="t-organization-heads">…` 안을 아래로 바꾼다. 열 정의, 조건, 필드 속성은 그대로다.

```tsx
  return <div className="t-organization-heads">
    <PageHeader title="조직장 배정" help="조직별 한 명을 지정하고 적용일별 이력을 보존합니다." actions={<a className="t-btn" href="/company/organizations">조직도</a>} />
    <form action="/admin/organization-heads" method="get" className="wl-querybar"><div><SelectField label="배정할 조직" name="organizationId" value={m.organizationId}><option value="">조직을 선택하세요</option>{m.organizations.map(organization => <option key={organization.id} value={organization.id}>{organization.code} · {organization.name}{organization.active ? '' : ' (비활성)'}</option>)}</SelectField></div><div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div></form>
    <p className="t-subtle">조회 기준 (서울): {fmtDateTime(m.asOf)}</p>
    <ErrorNotice message={error} />
    {selected ? <>
      <section className="wl-section t-head-history"><h2>{selected.name} 조직장 이력</h2><p className="hint">시작일은 포함하고 종료일은 제외합니다. 당일 교체와 종료 이력도 보존합니다.</p>
        {m.history.length === 0 ? <p className="notice">등록된 조직장이 없습니다.</p> : <WorklogSheet label="조직장 배정 이력" rows={m.history} columns={columns} getRowId={head => head.id} scope="all" />}
      </section>
      <div className="t-head-forms"><section className="wl-section"><h2>조직장 지정·교체</h2><p className="hint">조직장 배정으로 관리·결재 권한이 자동 부여되지 않습니다. 소속과 다른 조직의 사원도 겸직할 수 있습니다.</p><Form action="/admin/organization-heads" className="wl-form"><HeadCommandFields form={m.form} /><FormTable><SelectField label="새 조직장" name="employeeId" value={m.form.employeeId} required><option value="">사원을 선택하세요</option>{missing && <option value={m.form.employeeId!}>현재 선택값 (재지정 필요)</option>}{m.candidates.map(employee => <option key={employee.id} value={employee.id}>{employee.employeeNo} · {employee.employeeName}</option>)}</SelectField><Field label="적용 시작일 (포함)" name="validFrom" type="date" value={m.form.validFrom} min={m.today} required /><TextAreaField label="지정·교체 사유" name="reason" value={m.form.reason} rows={3} maxLength={500} required /><p className="hint">오늘 이후 날짜만 선택하세요. 기존 대기 신청의 결재자는 자동 변경되지 않습니다.</p></FormTable><div className="wl-form-actions"><button className="t-btn primary" disabled={!selected.active || m.candidates.length === 0} title={!selected.active ? '비활성 조직에는 배정할 수 없습니다.' : m.candidates.length === 0 ? '배정 가능한 재직 사원이 없습니다.' : undefined}>조직장 저장</button></div></Form></section>
      <section className="wl-section"><h2>조직장 배정 종료</h2><p className="hint">해당 적용일부터 조직장을 비워 둡니다. 이전 배정과 신청 이력은 보존합니다.</p><Form action="/admin/organization-heads/end" className="wl-form"><HeadCommandFields form={m.form} /><FormTable><Field label="종료 적용일 (제외)" name="validFrom" type="date" value={m.form.validFrom} min={m.today} required /><TextAreaField label="종료 사유" name="reason" value={m.form.reason} rows={3} maxLength={500} required /></FormTable><div className="wl-form-actions"><button className="t-btn" disabled={m.history.every(head => head.supersededAt || (head.validTo && head.validTo <= m.today))} title="열려 있는 배정이 있을 때 종료할 수 있습니다.">배정 종료</button></div></Form></section></div>
    </> : <p className="notice">조직을 선택해 조직장 배정과 이력을 확인하세요.</p>}
  </div>;
```

- [ ] **Step 5: 귀속 관리(`AttributionManagement`)**

`AnnualRequestPage.tsx`의 `../ui` import를 `import { Form, FormRow, FormTable, fmtDate, fmtDateTime } from '../ui';`로 바꾼다. 아래를 바꾼다.
- 두 `section className="t-card"`를 `className="wl-section"`으로 바꾼다.
- 안내 `<p className="notice">묶음 전체가 …</p>`를 `<Notice>묶음 전체가 단일 귀속연도라는 근거가 있을 때만 확정하세요. 혼합된 묶음은 미분류로 유지합니다. 분류는 기초·사용·복원 원장을 바꾸지 않습니다.</Notice>`로 바꾼다.
- 폼을 아래로 바꾼다.

```tsx
      <Form action={`/admin/leave-balances/buckets/${m.bucket.id}/attributions`} className="wl-form" onChange={change} onSubmit={event=>{if(conflict)event.preventDefault();}} data-unsaved-changes={String(dirty)}>
        {hidden('expectedRevision',expected)}{hidden('commandId',command)}
        <FormTable>
          <FormRow label="분류 상태" htmlFor="attribution-status" required><select id="attribution-status" name="classificationStatus" className="t-select" value={status} onChange={event=>setStatus(event.target.value)}><option value="UNCLASSIFIED">미분류 (확정 해제)</option><option value="CONFIRMED">귀속연도 확정</option></select></FormRow>
          {status==='CONFIRMED'?<>
            <FormRow label="귀속연도" htmlFor="entitlement-year" required><input id="entitlement-year" className="t-input" name="entitlementYear" type="number" min="1900" max="9998" defaultValue={m.form.entitlementYear??''} required/></FormRow>
            <FormRow label="근거 확인" required><label className="checkbox-label"><input type="checkbox" name="singleYearConfirmed" value="true" defaultChecked={m.form.singleYearConfirmed} required/>묶음 전체가 단일 귀속연도라는 근거를 확인했습니다</label></FormRow>
          </>:<p>귀속연도를 지정하지 않고 새 미분류 이력을 추가합니다.</p>}
          <FormRow label="분류 근거" htmlFor="attribution-basis" required><textarea id="attribution-basis" name="basis" rows={3} maxLength={500} defaultValue={m.form.basis} required/></FormRow>
          <FormRow label="변경 사유" htmlFor="attribution-reason" required><textarea id="attribution-reason" name="reason" rows={2} maxLength={500} defaultValue={m.form.reason} required/></FormRow>
        </FormTable>
        <div className="wl-form-actions"><button className="t-btn primary" disabled={conflict}>귀속 이력 저장</button></div>
      </Form>
```

- [ ] **Step 6: 통과 확인**

Run: `npm --prefix frontend test -- src/pages/AdminPages.test.tsx src/pages/OrgSheetPages.test.tsx src/pages/AnnualRequestPage.test.tsx src/App.test.tsx`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add frontend/src/pages/AdminPages.tsx frontend/src/pages/OrgSheetPages.tsx frontend/src/pages/AnnualRequestPage.tsx
git commit -m "feat: move schedule, organization head and attribution forms to document tables" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 인사정보 입력

**Files:**
- Modify: `frontend/src/pages/PersonnelPage.tsx:99-117`
- Modify: `frontend/src/pages/PersonalDetails.tsx:158-212`

- [ ] **Step 1: 기준 테스트 실행**

Run: `npm --prefix frontend test -- src/pages/PersonnelPage.test.tsx src/pages/PersonalDetails.test.tsx`
Expected: PASS

- [ ] **Step 2: `PersonnelPage.tsx`**

`../ui` import에 `FormTable`을 더한다. 네 구역 머리의 `<div className="t-section-heading"><span className="t-section-number">0N</span><div><h2>제목</h2><p>설명</p></div></div>`를 `<h2>제목</h2><p className="hint">설명</p>`로 바꾸고, 구역 클래스 `t-card t-personnel-section`을 `wl-section t-personnel-section`으로 바꾼다. 번호 배지는 지운다. 필드 묶음은 아래로 바꾼다.

```tsx
        <section className="wl-section t-personnel-section"><h2>기본 인사정보</h2><p className="hint">등록된 정보는 사원 헤더에 표시됩니다. 필수 항목 외에는 비워둘 수 있습니다.</p>
          <FormTable columns={2}>
            <Field label="생년월일" name="birthDate" type="date" value={f.birthDate} max={m.today} />
            <Field label="직급" name="gradeName" value={f.gradeName} maxLength={120} placeholder="예: 선임" />
            <Field label="직책" name="positionName" value={f.positionName} maxLength={120} placeholder="예: 팀장" />
            <Field label="직무" name="jobName" value={f.jobName} maxLength={120} placeholder="예: 제품 개발" />
          </FormTable>
        </section>
        <section className="wl-section t-personnel-section"><h2>재직 상태</h2><p className="hint">적용일부터 표시됩니다. 계정 활성 여부와 근무·결재 권한은 각각 관리합니다.</p>
          <FormTable columns={2}><SelectField label="재직 상태" name="employmentStatus" value={f.employmentStatus} required>{['UNKNOWN', 'WORKING', 'ON_LEAVE', 'SUSPENDED'].map(status => <option key={status} value={status}>{employmentStatusName(status)}</option>)}</SelectField>
          <Field label="상태 적용일" name="effectiveFrom" type="date" value={f.effectiveFrom} min={m.today} required /></FormTable>
        </section>
```

사원사진 구역은 첫 줄만 아래로 바꾸고, 이어지는 `t-photo-upload` 블록과 `ErrorNotice`는 그대로 둔다.

```tsx
        <section className="wl-section t-personnel-section"><h2>사원사진</h2><p className="hint">PNG·JPG, 최대 128KB. 사진이 없으면 이름의 첫 글자를 표시합니다.</p>
```

변경 기록 구역(한 줄)은 아래로 바꾼다.

```tsx
        <section className="wl-section t-personnel-section"><h2>변경 기록</h2><p className="hint">변경 사유와 처리 이력을 남깁니다.</p><FormTable><TextAreaField label="변경 사유" name="reason" value={f.reason} rows={3} maxLength={500} required /></FormTable></section>
```

- [ ] **Step 3: `PersonalDetails.tsx`**

`../ui` import에 `FormTable`을 더하고 아래를 바꾼다.
- 두 구역 클래스 `t-card t-personnel-section`을 `wl-section t-personnel-section`으로, 구역 설명 `p.t-muted`를 `p.hint`로 바꾼다.
- 두 `div.actions`를 `div.wl-form-actions`로 바꾼다.

상세정보 폼의 `<fieldset disabled={busy}>…</fieldset>`(164–180행)를 아래로 바꾼다. 감싸던 `<div>`만 지우고 필드는 그대로다.

```tsx
        <fieldset disabled={busy}><FormTable columns={2}>
          <SelectField label="입사구분" name="admissionType" value={f.admissionType ?? 'UNKNOWN'}><Options values={['UNKNOWN', 'NEW', 'EXPERIENCED', 'TRANSFER']} /></SelectField>
          <Field label="인정경력 (개월)" name="recognizedMonths" type="number" value={f.recognizedMonths ?? null} min={0} max={1200} />
          <Field label="그룹입사일" name="groupJoinedOn" type="date" value={f.groupJoinedOn ?? null} />
          <Field label="영문성명" name="englishName" value={f.englishName ?? null} maxLength={120} />
          <SelectField label="결혼여부" name="maritalStatus" value={f.maritalStatus ?? 'UNKNOWN'}><option value="UNKNOWN">미등록</option><option value="YES">기혼</option><option value="NO">미혼</option></SelectField>
          <Field label="결혼일자" name="marriedOn" type="date" value={f.marriedOn ?? null} max={today} />
          <SelectField label="보훈여부" name="veteranStatus" value={f.veteranStatus ?? 'UNKNOWN'}><Options values={['UNKNOWN', 'YES', 'NO']} /></SelectField>
          <SelectField label="장애여부" name="disabilityStatus" value={f.disabilityStatus ?? 'UNKNOWN'}><Options values={['UNKNOWN', 'YES', 'NO']} /></SelectField>
          <SelectField label="병역여부" name="militaryStatus" value={f.militaryStatus ?? 'UNKNOWN'}><Options values={['UNKNOWN', 'COMPLETED', 'EXEMPT', 'PENDING', 'NOT_APPLICABLE']} /></SelectField>
          <Field label="군별" name="militaryBranch" value={f.militaryBranch ?? null} maxLength={60} />
          <Field label="휴대전화" name="mobilePhone" type="tel" value={f.mobilePhone ?? null} maxLength={40} />
          <Field label="우편번호" name="postalCode" value={f.postalCode ?? null} maxLength={20} />
          <Field label="주소" name="address" value={f.address ?? null} maxLength={200} />
          <Field label="상세주소" name="addressDetail" value={f.addressDetail ?? null} maxLength={200} />
          <Field label="연락 이메일" name="contactEmail" type="email" value={f.contactEmail ?? null} maxLength={254} />
          <TextAreaField label="상세정보 변경 사유" name="reason" value={f.reason ?? ''} maxLength={500} required />
        </FormTable></fieldset>
```

이력 폼의 `<fieldset disabled={busy}>…</fieldset>`(199–205행)도 아래로 바꾼다.

```tsx
        <fieldset disabled={busy}><FormTable columns={2}>
          <SelectField label="이력 구분" name="kind" value={e?.kind ?? 'FAMILY'}>{kinds.map((kind, i) => <option key={kind} value={kind}>{tabs[i + 2]}</option>)}</SelectField>
          <Field label="항목명" name="title" value={e?.title ?? ''} maxLength={120} placeholder="관계·학교명·자격명·언어" required />
          <Field label="상세" name="detail" value={e?.detail ?? null} maxLength={200} placeholder="인원·전공/졸업·발급기관·등급/점수" />
          <Field label="적용 시작일" name="effectiveFrom" type="date" value={e?.effectiveFrom ?? today} required />
          <Field label="적용 종료일" name="effectiveTo" type="date" value={e?.effectiveTo ?? null} />
          <TextAreaField label="이력 등록 사유" name="reason" value={e?.reason ?? ''} maxLength={500} required />
        </FormTable></fieldset>
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix frontend test -- src/pages/PersonnelPage.test.tsx src/pages/PersonalDetails.test.tsx src/App.test.tsx`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/pages/PersonnelPage.tsx frontend/src/pages/PersonalDetails.tsx
git commit -m "feat: lay out personnel forms as two-column document tables" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: 기록 상세

**Files:**
- Modify: `frontend/src/pages/WorkflowPages.tsx:62-71, 751-1216`

- [ ] **Step 1: 기준 테스트 실행**

Run: `npm --prefix frontend test -- src/pages/WorkflowPages.test.tsx src/pages/RecordDetails.test.tsx`
Expected: PASS

- [ ] **Step 2: 읽기 전용 목록과 구역**

아래 다섯 가지를 바꾼다.
1. `Summary`의 `<dl>`을 `<dl className="wl-form-table">`로 바꾼다. `dt`·`dd` 구조는 그대로다.
2. `FrozenClock`부터 `AttendanceRecord`까지(사이의 `Result`·`Audit`·`AuditDetailPage` 포함)의 `section className="panel"`과 클래스 없는 `<dl>`을 한 번에 바꾼다. `Audit` 목록에는 두 패턴이 없다.

```bash
sed -i '/^function FrozenClock/,/^export function WorkflowPages/{s/<section className="panel">/<section className="wl-section">/g; s/<dl>/<dl className="wl-form-table">/g}' frontend/src/pages/WorkflowPages.tsx
grep -c 'className="wl-section"' frontend/src/pages/WorkflowPages.tsx
```

Expected: 두 번째 명령이 `14`를 출력한다. 3단계 이전 값은 0이고, 6번 작업의 정정 화면 2곳과 이번 12곳(FrozenClock 2, Result 6, AuditDetailPage 3, AttendanceRecord 1)을 더한 값이다.
3. 남은 클래스 없는 `<dl>`이 없는지 `grep -n '<dl>' frontend/src/pages/WorkflowPages.tsx`로 확인한다(출력 없음).
4. `Result`의 `<nav className="actions" …>…</nav>`, `<h1>계산 근거 상세</h1>`, 이어지는 두 `<p>`를 아래 하나로 바꾼다. 두 링크의 클래스 `t-btn secondary`는 `t-btn`으로 바꾼다.

```tsx
      <PageHeader title="계산 근거 상세" help={<>
        <p>이 결과 ID에 저장된 계산 당시의 근거입니다. 현재 입력으로 바꾸거나 재집계하지 않습니다. 법률·급여 확정 자료가 아닌 검토용 결과입니다.</p>
        <p>시각 기준: Asia/Seoul (UTC+09:00) · 시간량: 시:분:초.밀리초. 날짜와 실제 발생 시각은 구분합니다.</p>
      </>} actions={<>
        {m.workspacePrincipal?.roles.includes('EMPLOYEE') && <a className="t-btn" href={`/me/worktime?month=${m.month}`}>내 근로시간</a>}
        {m.admin && <a className="t-btn" href={`/admin/attendance?employeeId=${day.employeeId}&month=${m.month}`}>사원 월 현황</a>}
      </>} />
```

5. `AuditDetailPage`의 `<h1>감사 상세</h1>`를 `<PageHeader title="감사 상세" />`로 바꾼다. 도움말은 두지 않는다(`svg` 금지 테스트). 이어지는 설명 `<p>`에는 `className="hint"`를 더한다.

- [ ] **Step 3: 출퇴근 확인(`AttendanceRecord`)**

다섯 hidden 필드, 그 다음의 `<h3>`·`<p>`·조건부 `<ul>`은 그대로다.

폼 여는 줄을 아래로 바꾼다.

```tsx
      <Form action="/me/work-confirmations" method="post" className="wl-form">
```

두 체크 라벨부터 제출 버튼까지(`<label className="checkbox-label">`부터 `<button type="submit">현재 사실 확인 저장</button>`까지)를 아래로 바꾼다.

```tsx
        <FormTable>
          <FormRow label="본인 확인" required>
            <label className="checkbox-label">
              <input
                type="checkbox"
                name="presenceAsWork"
                value="true"
                defaultChecked={f.presenceAsWork}
                required
              />
              출퇴근 사이에서 휴게를 제외한 실제 근로가 일치합니다
            </label>
            <label className="checkbox-label">
              <input
                type="checkbox"
                name="breaksMatch"
                value="true"
                defaultChecked={f.breaksMatch}
                required
              />
              실제 휴게가 표시된 예정 휴게와 일치합니다
            </label>
          </FormRow>
        </FormTable>
        <div className="wl-form-actions">
          <button type="submit" className="t-btn primary">현재 사실 확인 저장</button>
        </div>
```

시트 줄은 아래로 바꾼다.

```tsx
      <WorklogSheet title="원천 출퇴근 기록" label="원천 출퇴근 기록" rows={d.events} columns={columns} getRowId={event => event.id} scope="all" />
```

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix frontend test -- src/pages/WorkflowPages.test.tsx src/pages/RecordDetails.test.tsx`
Expected: PASS. 계산 근거의 `dt`→`dd` 이웃 관계와 감사 상세의 `svg` 없음이 유지된다.

Run: `npm --prefix frontend test && npm --prefix frontend run build`
Expected: 전체 통과, 빌드 성공

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/pages/WorkflowPages.tsx
git commit -m "feat: show calculation, audit and attendance records as document tables" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: 합성 화면 검증 (고친 것이 있으면 작업별 커밋)

- [ ] **Step 1: 자동 검증**

```bash
npm --prefix frontend test
npm --prefix frontend run build
```

Expected: 전체 통과, 빌드 성공. 통과한 파일 수와 테스트 수를 기록한다.

- [ ] **Step 2: 캡처와 검사**

```bash
node "$TOOLS/capture.mjs" "$TOOLS/shots/stage3" http://127.0.0.1:18131 modals
node "$TOOLS/capture.mjs" "$TOOLS/shots/stage3-sheets" http://127.0.0.1:18131 sheets
node "$TOOLS/capture.mjs" "$TOOLS/shots/stage3-stage1" http://127.0.0.1:18131 stage1
node "$TOOLS/modal-check.mjs" "$TOOLS/shots/stage3-check"
node "$TOOLS/sheet-check.mjs" "$TOOLS/shots/stage3-check"
```

Expected: 세 캡처 모두 `0 with JS errors`이고 390px 넘침이 0이다. 두 검사는 `PASS`를 출력한다.

- [ ] **Step 3: 눈으로 확인**

`$TOOLS/shots/stage3`의 1440·390px 캡처를 차콜·블루로 확인한다.
- **신청 작성(업무 모달):**
  - 머리 56px, 결재란 두 칸, 신청자 정보 격자, 항목명 회색 칸과 필수 점
  - 기간 선택이 한 줄이고, 연차 묶음·사유 칸과 글자 수가 보인다.
  - 요약 상자와 안내, 아래에 붙은 동작 줄이 보이고 [닫기] 하단은 없다.
- **신청 작성(자체 dialog):** 머리가 고정되고, X는 32px이며, [닫기] 줄이 있다.
- **신청 상세:** 다음을 확인한다.
  - 결재 진행 결재란과 한 열 배치
  - 문서형 목록, 3열 요약, 처리 이력
  - 승인·반려 폼의 문서형 표와 오른쪽 버튼
  - [닫기] 하단
- **정정·관리·기록 화면:** 다음을 확인한다.
  - 모달 안 제목이 18px이다.
  - 문서형 표: 2열 표와 2열을 차지하는 사유 칸, 묶음 행, 계속되는 안내 줄
  - 오른쪽 동작 버튼, 이력 시트
- **390px:**
  - 모달이 아래에 붙은 시트이고 위 모서리가 둥글다.
  - 문서형 표는 항목명이 위, 값이 아래다.
  - 결재란은 세로 목록이고, 동작 버튼은 전체 폭이다.
- **회귀:** `stage3-stage1`·`stage3-sheets`가 2단계 결과와 같은 배치다. 모달 기본 너비가 1100px에서 920px로 바뀐 것만 다르다.

고칠 것이 있으면 작업 단위로 고치고 `fix:` 커밋한다.

- [ ] **Step 4: 사용자에게 보낼 비교 이미지**

`$TOOLS/shots/stage3-before`와 `$TOOLS/shots/stage3`에서 아래 네 화면을 나란히 붙여 보낸다. 2단계에서 쓴 `pair.mjs`를 쓴다.
- `compose-charcoal-1440`
- `detail-decide-blue-1440`
- `employee-charcoal-1440`
- `compose-charcoal-390`

---

### Task 12: 문서와 결과 기록

**Files:**
- Modify: `design.md`
- Modify: `docs/superpowers/specs/2026-11-10-worklog-design-system-design.md`
- Modify: `docs/quality/worklog-design-system-20261110.md`
- Modify: 이 계획 파일

- [ ] **Step 1: `design.md`에 모달·문서형 규칙 추가**

`## 시트·조회 조건 바·저장 확인` 절 다음에 아래 절을 넣는다.

```markdown
## 모달·문서형 화면

- **모달(`Modal`):** 크기는 s 440, m 720, l 920(기본), xl 1200이다.
  - 머리 56px(제목 18/700, 닫기 32px), 본문 여백 24px, 하단은 오른쪽에 [취소][주 동작]을 둔다.
  - 하단 왼쪽(`.wl-modal-note`)은 필수 표시 같은 보조 정보 자리다.
  - 600px 이하에서는 아래에 붙는 시트가 된다(위 모서리 12px, safe-area 여백).
  - 동작 줄을 본문 폼 안에 두는 화면은 `footer={false}`로 하단을 뺀다.
- **문서형 표(`FormTable`·`FormRow`, `dl.wl-form-table`):**
  - 2px 윗선, 116px g50 항목명 칸, 1px 행선을 쓴다.
  - 표 안의 `Field`·`SelectField`·`TextAreaField`는 한 행으로 그린다.
  - 묶음·체크 항목은 `FormRow`(group)로 감싼다. 행 바로 뒤의 안내 문장은 같은 행의 값 칸 아래로 이어진다.
  - 필수 항목은 항목명 뒤 점으로 표시하고, 오류일 때만 빨간 테두리와 문구를 쓴다.
  - 600px 이하에서는 항목명이 위, 값이 아래다.
- **정보 격자(`.wl-info-grid`):** 항목명 12px g600 위에 값 14/600을 두고, 4열(모바일 2열)로 배치한다.
- **결재란(`ApprovalStamps`):** 단계마다 128px 칸에 역할, 이름·소속, 상태 칩을 둔다. 모바일에서는 세로 목록이다.
- **안내(`Notice`):** g50 바탕에 정보 아이콘과 한 문장을 둔다.
- **폼·구역 클래스:** 옮긴 화면은 `wl-form`·`wl-section`·`wl-form-actions`를 쓰고 legacy `admin-form`·`panel`·`actions`를 쓰지 않는다.
```

- [ ] **Step 2: 설계 문서 보정**

- 7.7절 "크기" 항목 끝 문장 "2단계에서 `size`를 추가하고 … 3단계에서 한다."를 "3단계에서 네 크기와 기본값 `l`을 적용했다."로 바꾼다.
- 7.7절 "하단" 항목에 "동작 줄을 본문 폼 안에 두는 신청 작성은 `footer={false}`로 하단을 그리지 않는다."를 더한다.
- 7.7절 "모바일(≤600px)" 항목의 "전체 높이 시트"를 "아래에 붙는 시트(높이는 내용만큼, 최대 화면 높이 − 16px)"로 바꾼다.
- 7.8절 첫 항목의 "필수 항목은 항목명 뒤에 5px danger 색 점과 스크린리더용 \"필수\"를 붙인다."를 "필수 항목은 항목명 뒤에 5px danger 색 점을 붙인다. 컨트롤의 `required`가 필수를 읽어 준다."로 바꾼다.
- 7.8절에 "읽기 전용 `dt`·`dd` 목록은 `dl.wl-form-table`로 같은 모양을 쓴다."를 더한다.
- 8절 T3 행 아래에 "`schedule/form`·`clock/day`는 독립 화면으로 내보내지 않으므로 공용 부분(`ScheduleFields`·`AttendanceRecord`)만 옮긴다."를 더한다.

- [ ] **Step 3: 결과 문서에 3단계 절 추가**

`docs/quality/worklog-design-system-20261110.md`의 2단계 절 다음에 `## 3단계 공통 모달·문서형 화면` 절을 둔다. 아래 사실을 측정한 값 그대로 적는다.
- 테스트 파일 수와 테스트 수, 빌드 결과
- 모달 캡처 68장의 JS 오류·넘침
- `modal-check`·`sheet-check` 결과
- 커밋 목록
- 설계 보정 1–10번
- 1단계에서 생긴 달력 선택 표시 오류와 수정
- 실행하지 않은 검증: 실제 DB, 실기기, Java

- [ ] **Step 4: 계획 끝에 실행 기록 추가**

이 파일 끝에 `## 실행 기록 (날짜)` 절을 두고 작업별 커밋과 벗어난 점을 적는다.

- [ ] **Step 5: 커밋**

```bash
git add design.md docs/superpowers/specs/2026-11-10-worklog-design-system-design.md docs/quality/worklog-design-system-20261110.md docs/superpowers/plans/2026-11-10-worklog-design-stage3-modal.md
git commit -m "docs: record the WorkLog modal and document stage" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Push 여부 확인**

사용자에게 3단계 커밋을 Push할지 묻는다. 공개 데모 배포는 하지 않는다.

## 실행 기록 (2026-11-11)

- 0번: 기준 브랜치에 새 커밋이 없었다. 합성 자료 14경로를 확인했고, 바꾸기 전 모달 묶음은 68장, JS 오류 0이었다.
- 1–4번: 계획대로 구현했다(`298d408`, `dcd9e96`, `05bc5be`, `81c2e51`). 4번은 화면 확인 뒤 기간 버튼 글자, 항목명 위치, 모달 안 동작 줄 끝을 같은 커밋에서 고쳤다.
- 5·6번: 한 커밋으로 묶었다(`b93e201`). 모달 안 `.t-legacy-page` 좌우 여백 0도 함께 넣었다.
- 7·8번: `AdminPages.tsx` 변경은 `2086d14`, 조직장·귀속은 `095f4e4`로 나눴다.
- 9·10번: 계획대로 구현했다(`711aec7`, `d2a016f`). 10번의 `wl-section` 개수는 18개다(계획 예상 14, 2단계 4곳 포함).
- 11번: 구역 글자·간격을 고쳤다(`7ab70e7`). 최종 결과는 다음과 같다.
  - 506개 테스트 통과, 빌드 성공
  - 캡처 120장, JS 오류 0, 넘침 0
  - `modal-check`·`sheet-check` PASS
- 12번: 문서를 반영했다.
