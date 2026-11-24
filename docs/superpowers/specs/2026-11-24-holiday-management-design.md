# 휴일 관리 — 연도별 휴일 테이블과 근태 자동 반영

- 작성: 2026-11-24 · 상태: 설계 승인(사용자, 2026-11-24) · 구현 전
- 작업 브랜치: `claude/opening-prep-20261123` · 개통 준비 계획 3/3
- 바꾸는 문서: [개통 준비 설계](2026-11-23-opening-prep-design.md) §1.4·§2.3(달력 날짜별 개정 화면). §2.4 내장 공휴일 표와 법적 근거는 그대로 쓴다.
- 관련: [휴일·허용 네트워크·출퇴근](../../design/attendance-recording.md)

## 배경

지금은 `근태 운영 > 근무 기준 > 근무일·휴일`(`/admin/calendar`)에서 기간 단위 '달력 버전'(`calendar_version` + `calendar_day`)을 등록한다.

- 예외 날짜를 `날짜|종류|이름|근거` 텍스트로 입력한다.
- 한 번 등록한 기간은 수정·삭제할 수 없고, 겹치는 기간도 다시 등록할 수 없다.
- 출퇴근 근무 확인, 휴가 차감, 연장근무 신청, 일 근무시간 계산, 월말 점검이 이 데이터를 읽는다. 등록되지 않은 날은 '캘린더 미확인'으로 막힌다.
- 휴가 신청일(`leave_request_day`)과 연장근무 신청(`overtime_request_detail`)은 당시 달력 버전을 FK로 저장한다. 일 결과와 근무 확인은 달력 버전을 입력 지문에 넣어, 버전이 바뀌면 '재계산 필요'가 된다.

## 사용자 결정 (2026-11-24)

| 주제 | 결정 |
| --- | --- |
| 방향 | 별도 달력 화면 대신 **휴일 관리** 메뉴와 **휴일 테이블**을 새로 둔다. 담당자는 여기서 **연도별**로 관리하고, 저장하면 근태에 자동 반영된다. |
| 반영 방식 | 근태 계산은 기존 달력 구조를 유지한다. 휴일 저장 시 **바뀐 날짜만** 근태 달력에 새 반영분을 만든다. 마감 월과 바뀌지 않은 날짜의 결과는 보존한다. |
| 구분 | 공휴일(법정·대체·임시), 회사 휴일, 비근로일 3가지 |

## 1. 화면 — `근태 운영 > 근무 기준 > 휴일 관리` (`/admin/holidays`)

- 기존 `근무일·휴일` 메뉴 자리를 바꾼다. `/admin/calendar`로 들어오면 `/admin/holidays`로 보낸다. 기간 등록 폼(파이프 텍스트)은 없앤다.
- **연도 선택**(이전·다음 연도). 그 연도의 근태 반영 상태를 보여 준다: "근태 반영됨 · 마지막 반영 MM-DD HH:mm · 담당자" 또는 "아직 근태 미반영".
- **휴일 시트**(`WorklogSheet`): 날짜, 요일(표시), 휴일명, 구분, 근거, 상태.
  - 상태: 반영됨 / 저장 전 변경 / 마감 월(변경 불가)
  - 행 추가·행 삭제. 셀에서 바로 고친다.
- **'YYYY년 공휴일 불러오기'**: 내장 목록 가운데 시트에 없는 날짜만 시트에 추가한다(저장 전 변경 상태). 미리보기에 4인 이하 사업장 안내를 붙인다(개통 준비 설계 §2.4).
- **저장**: 사유 필수. 휴일 테이블 저장과 근태 반영을 한 번에 한다. 결과에 추가·수정·삭제 건수와 근태 반영 날짜 수를 보여 준다.
- 내장 목록이 없는 연도는 "YYYY년 공휴일은 아직 내장되지 않았습니다. 날짜를 직접 추가하세요."로 안내한다.

## 2. 데이터 — `V41__holiday_management.sql`

### 2.1 휴일 테이블

`tna.holiday`

| 열 | 규칙 |
| --- | --- |
| `tenant_id`, `id` | PK `(tenant_id,id)` |
| `holiday_date` | `UNIQUE (tenant_id, holiday_date)` |
| `name` | 1~120자, 공백만 불가 |
| `category` | `PUBLIC`(공휴일) · `COMPANY`(회사 휴일) · `NON_WORKDAY`(비근로일) |
| `basis` | 0~500자, NULL 허용 |
| `created_by/at`, `updated_by/at` | 계정 FK |

`tna.holiday_year` — 연도별 버전과 마지막 반영 정보

| 열 | 규칙 |
| --- | --- |
| `tenant_id`, `year` | PK, `year BETWEEN 2000 AND 2100` |
| `version` | 저장마다 +1(동시 저장 확인) |
| `applied_at`, `applied_by` | 마지막 근태 반영 |

- 두 테이블 모두 FORCE RLS(`tenant_isolation`). `tna_app`에 `SELECT, INSERT, UPDATE, DELETE`(holiday), `SELECT, INSERT, UPDATE`(holiday_year).

### 2.2 근태 달력 반영

- `calendar_version`에 `scope varchar(10) NOT NULL DEFAULT 'PERIOD' CHECK (scope IN ('PERIOD','DAY'))`를 더한다. 기간 겹침 제약은 `WHERE (scope='PERIOD')` 부분 제약으로 다시 만든다.
- **DAY 반영분**: 날짜 하나만 담는다(`valid_from=날짜`, `valid_to=날짜+1`, `calendar_day` 1행).
- **적용 규칙**: 한 날짜를 포함하는 버전 가운데 `revision`이 가장 큰 버전을 쓴다. `security_invoker` 뷰 `tna.calendar_effective_day(tenant_id, work_date, version_id, kind, name, basis)`로 한곳에 정의한다.
  - 날짜 한 건 조회(`ClockMapper`)와 월 조회(`MonthEndReviewMapper.DAY_EVIDENCE_JOINS`, 이를 재사용하는 `WorktimeMapper`·`CheckoutFollowUpMapper`)를 이 뷰로 바꾼다.
  - 휴가·연장 신청의 `calendar_version_id` FK와 입력 지문 구조는 바꾸지 않는다.
- **연도 Y 저장 시 반영**(한 명령, 고객사 잠금 안에서):
  1. 원하는 종류: 휴일 행이 있으면 `PUBLIC`·`COMPANY` → `HOLIDAY`, `NON_WORKDAY` → `NON_WORKDAY`. 없으면 `WORKDAY`.
  2. 근태 달력이 없는 구간(연속 구간마다): 그 구간 `PERIOD` 버전을 만든다.
  3. 근태 달력이 있는 날: 종류나 이름이 다르면 `DAY` 반영분을 만든다. 같으면 아무것도 하지 않는다.
  4. 같은 것으로 보는 경우: 휴일 행이 없는 **토·일**의 기존 `NON_WORKDAY`는 `WORKDAY`와 같다고 본다(근무 요일은 근무조가 정하므로 결과가 같다).
  5. 마감 월(`ClosedAttendanceMonths`) 날짜는 반영하지 않는다. 그 날짜의 휴일 행을 추가·수정·삭제하면 행 오류로 막는다.
- 새 반영분이 생긴 날만 입력 지문이 바뀌어 일 결과가 '재계산 필요', 근무 확인이 '확인 필요'가 된다. 별도 재집계 표시는 만들지 않는다.

### 2.3 기존 데이터 이전

- 마이그레이션이 각 고객사의 **현재 적용 중인** 달력에서 `HOLIDAY` 날과 **평일**의 `NON_WORKDAY` 날을 `tna.holiday`로 옮긴다(`HOLIDAY` → `COMPANY`, 이름·근거 유지; `NON_WORKDAY` → `NON_WORKDAY`). 주말 `NON_WORKDAY`는 옮기지 않는다(§2.2-4).
  - 공휴일과 회사 휴일을 기존 자료로 구분할 수 없으므로 `COMPANY`로 옮긴다. 화면에서 바꿀 수 있다.
- 달력이 있는 연도마다 `holiday_year`를 version 1로 만든다(`applied_at`=마이그레이션 시각, `applied_by` NULL 허용).

### 2.4 내장 공휴일 — `KoreanPublicHolidays`

개통 준비 설계 §2.4의 2026년(22일)·2027년(24일) 표와 근거를 그대로 쓴다. 모두 `PUBLIC`으로 불러온다. 매년 월력요항 발표 뒤 다음 해 목록을 추가하는 절차를 개발 안내에 적는다.

## 3. 보안과 오류

- 담당자 전용(`/admin/holidays/**`), POST는 CSRF. 공개 데모에서는 저장을 막는다(403).
- 저장은 `OrgCommands.execute` 한 번, 감사 1건(action `HOLIDAY_SAVE`): 연도, 추가·수정·삭제 건수, 근태 반영 날짜 수. 같은 `commandId` 재요청은 처음 결과를 돌려준다.
- 동시 저장: 화면이 본 `holiday_year.version`(`expectedVersion`)과 다르면 409 "최신 내용을 확인한 뒤 다시 저장하세요."
- 행 오류는 모두 모아 한 번에 보여 주고 아무것도 저장하지 않는다(422): 날짜 중복, 선택 연도가 아닌 날짜, 마감 월 날짜, 휴일명 누락·120자 초과, 구분 값 오류, 근거 500자 초과.
- 휴일 행은 사람 정보가 아니므로 감사 상태에 날짜·이름을 넣어도 된다. 단, 감사 크기를 위해 건수와 날짜 목록만 남긴다.

## 4. HTTP 계약

| 메서드·경로 | 입력 | 결과 |
| --- | --- | --- |
| GET `/admin/holidays` | `year`(기본: 서울 기준 올해) | 화면 모델(연도, 버전, 반영 정보, 행, 마감 월 목록, 내장 목록 유무) |
| POST `/admin/holidays/public-preview` | JSON `{year}` | 내장 공휴일 목록(날짜·이름·근거) |
| POST `/admin/holidays/save` | JSON `{commandId, reason, year, expectedVersion, rows:[{clientRowId,id?,date,name,category,basis}]}` | 결과 · 409 · 422(행별 오류) |
| GET `/admin/calendar` | — | 303 `/admin/holidays` |

- 제거: `POST /admin/calendar/versions`(기간 등록). 이를 쓰던 테스트 도우미는 SQL 시드 또는 휴일 저장으로 바꾼다.

## 5. 테스트와 검증

- **Java 단위**: `KoreanPublicHolidays`가 §2.4 표와 날짜·이름까지 일치, 반영 계산(바뀐 날만, 빈 구간 PERIOD, 주말 비근로일 동일 처리, 마감 월 제외)
- **PostgreSQL 통합**: 저장 후 출퇴근·월말 점검·휴가·연장이 새 휴일을 읽음, 바뀐 날만 `STALE_RESULT`, 마감 월 거부, 409, RLS, 마이그레이션 이전 결과, 같은 `commandId` 재시도
- **화면(Vitest)**: 연도 이동, 불러오기 병합, 행 오류 표시, 저장 결과
- **브라우저(PC·모바일)**: 공휴일 불러오기 → 회사 휴일 추가 → 저장 → 직원 홈 '확인된 휴일', 휴일 출근 시 휴일근로 검토 표시
- **전체 회귀**: `mvnw verify`, `npm --prefix frontend test`, 빌드. 달력을 시드하는 기존 테스트(약 30개 파일)가 그대로 통과해야 한다.

## 6. 함께 갱신할 문서

- [attendance-recording](../../design/attendance-recording.md): 휴일 관리와 반영 규칙
- 개통 준비 설계 §1.4·§2.3: 이 문서로 대체됐다는 표시
- [개발 안내](../../development/local.md): 공휴일 연례 추가, 개통 순서의 휴일 단계
- `CompanySetupGuide.tsx`: '휴일 캘린더' 단계를 '휴일 관리'로

## 2026-11-24 구현 반영

구현하며 설계와 다르게 정한 점이다(계획 '자체 점검 결과'와 같다).

1. 휴일명 비교는 휴일 행이 있는 날(HOLIDAY·NON_WORKDAY)만 한다. 근로일끼리는 종류만 비교한다. 이름까지 비교하면 기존 기간 등록의 근로일 이름과 달라 첫 저장 때 그 해 모든 평일이 '재계산 필요'가 된다.
2. 근거만 바뀐 날에는 근태 달력에 새 반영분을 만들지 않는다. 근태 달력 행의 근거는 처음 반영 때 값으로 남는다.
3. 기간(PERIOD) 범위 안인데 날짜 행이 없는 날(SQL 시드에서만 생김)은 DAY 반영분으로 채운다.
4. `calendar_day.name`·`basis`는 비울 수 없다. 휴일 행이 없는 날은 이름 '근로일', 근거가 빈 날은 '휴일 관리 반영'으로 넣는다.
5. 저장 행은 `id`를 받지 않고 날짜로 비교한다(`UNIQUE (tenant_id, holiday_date)`). 날짜를 바꾸면 삭제+추가로 센다.
6. 마감 월의 삭제 오류는 `clientRowId: null`로 돌려주고 화면의 오류 목록에만 보인다.
7. 선택근무 정산의 '회사 달력 확인'은 '휴일 관리 확인'(`/admin/holidays?year=YYYY`)이 됐고 복귀 왕복은 없앴다.
8. 2000~2100년 밖의 달력 날짜는 `holiday`·`holiday_year`로 옮기지 않는다.
9. 공개 데모 시드에 `holiday_year` 1행을 넣고 검증 기대값을 맞춘다. 데모 휴일 행은 시드하지 않는다.
10. 기간 등록 동작 테스트는 지우고 같은 규칙을 `HolidayServiceIT`·`HolidayWebIT`가 맡는다. 시드 용도 호출은 `CalendarSeed`(SQL)로 옮겼다.
