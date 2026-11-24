# 휴일 관리 구현 계획 (개통 준비 계획 3/3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 담당자가 `근태 운영 > 근무 기준 > 휴일 관리`(`/admin/holidays`)에서 연도별 휴일 표를 고치고 저장하면, 같은 명령 안에서 근태 달력에 자동 반영된다. 내장 공휴일(2026·2027)을 불러올 수 있다. 근태 계산은 기존 달력 구조(`calendar_version`+`calendar_day`)를 그대로 읽고, 바뀐 날짜만 새 반영분이 생겨 '재계산 필요'가 된다.

**Architecture:**
- `V41`이 휴일 표(`tna.holiday`)와 연도별 버전(`tna.holiday_year`)을 만들고, `calendar_version`에 `scope`(`PERIOD`/`DAY`)를 더한다. 기간끼리만 겹칠 수 없고, 날짜 하나짜리 `DAY` 반영분은 기간 위에 쌓인다.
- 한 날짜에 적용되는 달력은 `security_invoker` 뷰 `tna.calendar_effective_day`(그 날짜를 포함하는 버전 가운데 `revision`이 가장 큰 것) 한곳에서 정한다. 읽는 곳은 `ClockMapper.calendar`와 `MonthEndReviewMapper.DAY_EVIDENCE_JOINS` 둘뿐이다(후자를 `WorktimeMapper`·`CheckoutFollowUpMapper`가 재사용). 휴가·연장·정정은 `ClockService` 스냅샷을 거치므로 따로 바꾸지 않는다.
- 저장 시 반영은 순수 계산기(`HolidayReflection`)가 정한다. 휴일 행 → 원하는 종류 → 지금 달력과 비교 → 빈 구간은 `PERIOD`, 다른 날은 `DAY`. 마감 월은 건너뛴다.
- 저장은 `OrgCommands.execute` 한 번(감사 1건, action `HOLIDAY_SAVE`)이다. 결과는 감사 `after_state`에서 다시 읽어 같은 `commandId` 재시도에 같은 응답을 준다(계획 2의 `EmployeeImportService.save`와 같은 방식).
- 기존 기간 등록(`POST /admin/calendar/versions`, `AttendanceSettingsService.calendar`)은 없앤다. 이를 쓰던 테스트는 SQL 시드 도우미(`CalendarSeed`)로 옮긴다.

**Tech Stack:** Java 25, Spring Boot 4.1 / Spring Security, MyBatis 애너테이션 매퍼, PostgreSQL 18(RLS·`security_invoker` 뷰·부분 EXCLUDE), Jackson 3(`tools.jackson`), Flyway, React 19 + AG Grid(`WorklogSheet`) + Vitest, JUnit 5 통합 테스트(`DatabaseFixture`)

**설계 문서:** [2026-11-24-holiday-management-design.md](../specs/2026-11-24-holiday-management-design.md)(승인) · 내장 공휴일 표: [개통 준비 설계](../specs/2026-11-23-opening-prep-design.md) §2.4 · **선행:** [계획 2](2026-11-24-employee-import-accounts.md)(커밋 `…13301f9`)

**이 계획의 범위 밖:** 회사 규모 저장, 반차 휴일, 근무조별 휴일, 여러 연도 한 번에 저장, 외부 공휴일 API 연동, 기존 `calendar_version`/`calendar_day` 행의 수정·삭제.

---

## 실행 환경 메모 (이 PC)

- Maven은 JDK 25가 필요하다. 시스템 `JAVA_HOME`은 JDK 8이다. PowerShell에서 먼저 `$env:JAVA_HOME='C:\Users\ihpark\.jdks\jdk-25.0.5.1+2'`를 지정한다.
- 프로젝트 테스트 DB 이미지는 이 브랜치에서 빌드되지 않는다. 전용 컨테이너(`127.0.0.1:25432`, DB `tna_phase4_test`)를 `TNA_TEST_JDBC_URL`·`TNA_TEST_DB_USER`·`TNA_TEST_DB_PASSWORD`로 연결한다. 값과 기동 방법은 [account-provisioning-20261124](../../quality/account-provisioning-20261124.md)와 같다. 이미지가 고쳐지면 환경변수 없이 실행한다.
- `HolidayMigrationIT`는 `CodeMigrationUpgradeIT`처럼 클러스터에 임시 DB를 만든다. 외부 테스트 DB의 관리자 계정에 `CREATEDB`가 없으면 실행하지 못한다. 그때는 결과 문서에 '미실시'와 원인을 적는다.
- 아래 명령의 `.\mvnw.cmd`는 위 설정을 마친 PowerShell에서 실행한다. 단위 테스트는 `test`, 통합 테스트는 `-Dit.test=… -Dtest=none … verify`로 돌린다.

## 파일 구조

| 파일 | 책임 |
| --- | --- |
| Create `src/main/java/kr/tna/clock/KoreanPublicHolidays.java` | 2026·2027 내장 공휴일(날짜·이름·근거), 4인 이하 안내 문구 |
| Create `src/main/resources/db/migration/V41__holiday_management.sql` | `holiday`·`holiday_year`, `calendar_version.scope`, 부분 EXCLUDE, 뷰 `calendar_effective_day`, 기존 달력 이전 |
| Modify `src/main/java/kr/tna/clock/ClockMapper.java:64-69` | 날짜 한 건 조회를 뷰로 |
| Modify `src/main/java/kr/tna/worktime/MonthEndReviewMapper.java:61-65` | `DAY_EVIDENCE_JOINS`의 달력 조회를 뷰로 |
| Create `src/main/java/kr/tna/clock/HolidayReflection.java` | 원하는 종류 계산, 지금 달력과 비교, 빈 구간·마감 월 처리(순수) |
| Create `src/main/java/kr/tna/clock/HolidayMapper.java` | 휴일 행·연도 버전·유효 달력·기간·반영분 쓰기·명령 결과 |
| Create `src/main/java/kr/tna/clock/HolidayService.java` | 화면 모델, 내장 목록, 저장(행 검증·마감 월·409·반영) |
| Create `src/main/java/kr/tna/clock/HolidayController.java` | `GET /admin/holidays`, `GET /admin/calendar` → 303 |
| Create `src/main/java/kr/tna/clock/HolidayApi.java` | `POST /admin/holidays/public-preview`, `POST /admin/holidays/save`(JSON) |
| Modify `src/main/java/kr/tna/clock/AttendanceController.java` | `/admin/calendar` GET·`/admin/calendar/versions` POST·`calendarPage` 제거 |
| Modify `src/main/java/kr/tna/clock/AttendanceSettingsService.java`, `AttendanceSettingsMapper.java`, `AttendanceSettingsData.java` | 기간 등록 코드 제거 |
| Modify `src/main/java/kr/tna/worktime/SelectiveSettlementReturn.java` | 쓰지 않게 된 `calendar(...)` 제거 |
| Modify `src/main/java/kr/tna/common/config/SecurityConfiguration.java`, `src/main/java/kr/tna/common/web/ReactPageViewResolver.java` | 권한·공개 데모 차단·화면 등록 |
| Create `frontend/src/pages/HolidayPage.tsx` | 휴일 관리 화면 |
| Modify `frontend/src/pages/EmployeeImportPage.tsx` | `postJson`을 export(재사용) |
| Modify `frontend/src/App.tsx`, `navigation.ts`, `pages/AdminPages.tsx`, `pages/AdminTypes.ts`, `pages/selectiveNavigation.ts`, `pages/CompanySetupGuide.tsx` | 메뉴·제목·연결, 옛 달력 화면 제거 |
| Modify `deploy/worklog/sample-data.sql`, `deploy/worklog/verify-sample-data.sql` | 데모 연도의 `holiday_year` 1행 |
| Modify `scripts/verify-customer-opening-browser.cjs` | 개통 흐름의 달력 단계를 휴일 관리로 |
| Docs | `docs/design/attendance-recording.md`, `docs/development/local.md`, 두 설계 문서의 반영 표시, `docs/quality/holiday-management-20261124.md` |
| Tests(새) | `src/test/java/kr/tna/clock/KoreanPublicHolidaysTest.java`, `src/test/java/kr/tna/clock/HolidayReflectionTest.java`, `src/test/java/kr/tna/HolidaySchemaIT.java`, `src/test/java/kr/tna/HolidayMigrationIT.java`, `src/test/java/kr/tna/CalendarSeed.java`, `src/test/java/kr/tna/CalendarEffectiveDayIT.java`, `src/test/java/kr/tna/HolidayServiceIT.java`, `src/test/java/kr/tna/HolidayWebIT.java`, `frontend/src/pages/HolidayPage.test.tsx` |
| Tests(수정) | `MigrationRunnerIT`, `DatabaseFoundationIT`, 기간 등록을 부르던 14개 파일(Task 4), `AttendanceSettingsIT`, `EmployeeServiceIT`, `CheckoutOperationsWebIT`, `AttendanceWebIT`, `SelectiveSettlementReturnTest`, `PublicDemoSecurityIT`, `navigation.test.ts`, `App.test.tsx`, `AdminPages.test.tsx`, `SelectiveSettlements.test.tsx` |

---

### Task 1: 내장 공휴일 목록

**Files:**
- Create: `src/main/java/kr/tna/clock/KoreanPublicHolidays.java`
- Test: `src/test/java/kr/tna/clock/KoreanPublicHolidaysTest.java`

- [ ] **Step 1: 실패하는 단위 테스트 작성**

표는 개통 준비 설계 §2.4를 그대로 옮긴 것이다. 날짜와 이름이 한 글자라도 다르면 실패해야 한다.

```java
package kr.tna.clock;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class KoreanPublicHolidaysTest {
    private static List<String> lines(int year) {
        return KoreanPublicHolidays.of(year).stream().map(h -> h.date() + " " + h.name()).toList();
    }

    @Test void year2026MatchesTheOfficialTable() {
        assertEquals(List.of(
                "2026-01-01 1월 1일", "2026-02-16 설날 전날", "2026-02-17 설날", "2026-02-18 설날 다음 날",
                "2026-03-01 3·1절", "2026-03-02 대체공휴일(3·1절)", "2026-05-01 노동절", "2026-05-05 어린이날",
                "2026-05-24 부처님 오신 날", "2026-05-25 대체공휴일(부처님 오신 날)", "2026-06-03 제9회 전국동시지방선거",
                "2026-06-06 현충일", "2026-07-17 제헌절", "2026-08-15 광복절", "2026-08-17 대체공휴일(광복절)",
                "2026-09-24 추석 전날", "2026-09-25 추석", "2026-09-26 추석 다음 날", "2026-10-03 개천절",
                "2026-10-05 대체공휴일(개천절)", "2026-10-09 한글날", "2026-12-25 기독탄신일"), lines(2026));
    }

    @Test void year2027MatchesTheOfficialTable() {
        assertEquals(List.of(
                "2027-01-01 1월 1일", "2027-02-06 설날 전날", "2027-02-07 설날", "2027-02-08 설날 다음 날",
                "2027-02-09 대체공휴일(설날)", "2027-03-01 3·1절", "2027-05-01 노동절", "2027-05-03 대체공휴일(노동절)",
                "2027-05-05 어린이날", "2027-05-13 부처님 오신 날", "2027-06-06 현충일", "2027-07-17 제헌절",
                "2027-07-19 대체공휴일(제헌절)", "2027-08-15 광복절", "2027-08-16 대체공휴일(광복절)", "2027-09-14 추석 전날",
                "2027-09-15 추석", "2027-09-16 추석 다음 날", "2027-10-03 개천절", "2027-10-04 대체공휴일(개천절)",
                "2027-10-09 한글날", "2027-10-11 대체공휴일(한글날)", "2027-12-25 기독탄신일", "2027-12-27 대체공휴일(기독탄신일)"),
                lines(2027));
    }

    @Test void basisNamesTheKindAndTheOfficialSource() {
        var labour = KoreanPublicHolidays.of(2026).stream().filter(h -> h.name().equals("노동절")).findFirst().orElseThrow();
        assertEquals("공휴일·근로자 유급휴일 · 관공서의 공휴일에 관한 규정(대통령령 제36290호)", labour.basis());
        assertEquals("선거일 · 2026년 월력요항(우주항공청공고 제2025-0044호)", KoreanPublicHolidays.of(2026).get(10).basis());
        assertTrue(KoreanPublicHolidays.of(2027).stream().allMatch(h -> h.basis().endsWith("2027년 월력요항(우주항공청공고 제2026-0078호)")));
        assertTrue(KoreanPublicHolidays.of(2027).stream().allMatch(h -> h.basis().length() <= 500 && h.name().length() <= 120));
    }

    @Test void yearsWithoutAnOfficialListAreEmpty() {
        assertEquals(List.of(), KoreanPublicHolidays.of(2025));
        assertEquals(List.of(), KoreanPublicHolidays.of(2028));
        assertTrue(KoreanPublicHolidays.SMALL_WORKPLACE_NOTICE.startsWith("상시 4인 이하 사업장은"));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dtest=KoreanPublicHolidaysTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL(컴파일 오류, `KoreanPublicHolidays` 없음)

- [ ] **Step 3: 구현**

```java
package kr.tna.clock;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 내장 공휴일(개통 준비 설계 §2.4). 매년 한국천문연구원 월력요항이 관보에 공고되면(보통 6월 말) 다음 해 목록을 더한다.
 * 절차는 docs/development/local.md '휴일 관리와 공휴일 연례 추가'를 따른다.
 */
public final class KoreanPublicHolidays {
    public record Holiday(LocalDate date, String name, String basis) { }

    public static final String SMALL_WORKPLACE_NOTICE =
            "상시 4인 이하 사업장은 노동절 외 공휴일의 유급휴일 의무가 없습니다. 회사 기준에 맞게 날짜를 바꿀 수 있습니다.";
    private static final String Y2026 = "2026년 월력요항(우주항공청공고 제2025-0044호)";
    private static final String RULE = "관공서의 공휴일에 관한 규정(대통령령 제36290호)";
    private static final String Y2027 = "2027년 월력요항(우주항공청공고 제2026-0078호)";
    private static final String PUBLIC = "공휴일", NATIONAL = "공휴일(국경일)", SUBSTITUTE = "대체공휴일",
            LABOUR = "공휴일·근로자 유급휴일", ELECTION = "선거일";

    private static final Map<Integer, List<Holiday>> YEARS = Map.of(
            2026, List.of(
                    h("2026-01-01", "1월 1일", PUBLIC, Y2026), h("2026-02-16", "설날 전날", PUBLIC, Y2026),
                    h("2026-02-17", "설날", PUBLIC, Y2026), h("2026-02-18", "설날 다음 날", PUBLIC, Y2026),
                    h("2026-03-01", "3·1절", NATIONAL, Y2026), h("2026-03-02", "대체공휴일(3·1절)", SUBSTITUTE, Y2026),
                    h("2026-05-01", "노동절", LABOUR, RULE), h("2026-05-05", "어린이날", PUBLIC, Y2026),
                    h("2026-05-24", "부처님 오신 날", PUBLIC, Y2026), h("2026-05-25", "대체공휴일(부처님 오신 날)", SUBSTITUTE, Y2026),
                    h("2026-06-03", "제9회 전국동시지방선거", ELECTION, Y2026), h("2026-06-06", "현충일", PUBLIC, Y2026),
                    h("2026-07-17", "제헌절", NATIONAL, RULE), h("2026-08-15", "광복절", NATIONAL, Y2026),
                    h("2026-08-17", "대체공휴일(광복절)", SUBSTITUTE, Y2026), h("2026-09-24", "추석 전날", PUBLIC, Y2026),
                    h("2026-09-25", "추석", PUBLIC, Y2026), h("2026-09-26", "추석 다음 날", PUBLIC, Y2026),
                    h("2026-10-03", "개천절", NATIONAL, Y2026), h("2026-10-05", "대체공휴일(개천절)", SUBSTITUTE, Y2026),
                    h("2026-10-09", "한글날", NATIONAL, Y2026), h("2026-12-25", "기독탄신일", PUBLIC, Y2026)),
            2027, List.of(
                    h("2027-01-01", "1월 1일", PUBLIC, Y2027), h("2027-02-06", "설날 전날", PUBLIC, Y2027),
                    h("2027-02-07", "설날", PUBLIC, Y2027), h("2027-02-08", "설날 다음 날", PUBLIC, Y2027),
                    h("2027-02-09", "대체공휴일(설날)", SUBSTITUTE, Y2027), h("2027-03-01", "3·1절", NATIONAL, Y2027),
                    h("2027-05-01", "노동절", LABOUR, Y2027), h("2027-05-03", "대체공휴일(노동절)", SUBSTITUTE, Y2027),
                    h("2027-05-05", "어린이날", PUBLIC, Y2027), h("2027-05-13", "부처님 오신 날", PUBLIC, Y2027),
                    h("2027-06-06", "현충일", PUBLIC, Y2027), h("2027-07-17", "제헌절", NATIONAL, Y2027),
                    h("2027-07-19", "대체공휴일(제헌절)", SUBSTITUTE, Y2027), h("2027-08-15", "광복절", NATIONAL, Y2027),
                    h("2027-08-16", "대체공휴일(광복절)", SUBSTITUTE, Y2027), h("2027-09-14", "추석 전날", PUBLIC, Y2027),
                    h("2027-09-15", "추석", PUBLIC, Y2027), h("2027-09-16", "추석 다음 날", PUBLIC, Y2027),
                    h("2027-10-03", "개천절", NATIONAL, Y2027), h("2027-10-04", "대체공휴일(개천절)", SUBSTITUTE, Y2027),
                    h("2027-10-09", "한글날", NATIONAL, Y2027), h("2027-10-11", "대체공휴일(한글날)", SUBSTITUTE, Y2027),
                    h("2027-12-25", "기독탄신일", PUBLIC, Y2027), h("2027-12-27", "대체공휴일(기독탄신일)", SUBSTITUTE, Y2027)));

    private KoreanPublicHolidays() { }

    /** 내장되지 않은 연도는 빈 목록이다. */
    public static List<Holiday> of(int year) {
        return YEARS.getOrDefault(year, List.of());
    }

    private static Holiday h(String date, String name, String kind, String source) {
        return new Holiday(LocalDate.parse(date), name, kind + " · " + source);
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: Step 2 명령. Expected: PASS(4개)

- [ ] **Step 5: 커밋**

```powershell
git add src/main/java/kr/tna/clock/KoreanPublicHolidays.java src/test/java/kr/tna/clock/KoreanPublicHolidaysTest.java
git commit -m "feat: 2026·2027년 내장 공휴일 목록과 4인 이하 사업장 안내"
```

---

### Task 2: V41 — 휴일 표, 날짜별 반영분, 유효 달력 뷰, 기존 달력 이전

**Files:**
- Create: `src/main/resources/db/migration/V41__holiday_management.sql`
- Test: `src/test/java/kr/tna/HolidaySchemaIT.java`, `src/test/java/kr/tna/HolidayMigrationIT.java`
- Modify: `src/test/java/kr/tna/MigrationRunnerIT.java:31-35`(40 → 41), `src/test/java/kr/tna/DatabaseFoundationIT.java:34-47`(보호 테이블 목록)

- [ ] **Step 1: 실패하는 스키마 테스트 작성**

```java
package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import kr.tna.common.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class HolidaySchemaIT {
    private final DatabaseFixture f = DatabaseFixture.start();
    private final JdbcTemplate admin = new JdbcTemplate(f.adminDataSource());
    private final JdbcTemplate app = new JdbcTemplate(f.appDataSource());
    private final TenantTransactions tx = new TenantTransactions(f.appDataSource());
    private UUID tenant, account;

    @BeforeEach void seed() {
        tenant = UUID.randomUUID(); account = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 휴일 고객사')", tenant, "HOLIDAY-" + tenant);
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'H1','합성 담당자',DATE '2020-01-01')", tenant, employee);
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,'holiday@example.test','synthetic-only')",
                tenant, account, employee);
    }

    @Test void holidayTablesAreTenantScopedWithNarrowGrants() {
        for (String table : List.of("holiday", "holiday_year")) {
            assertTrue(admin.queryForObject("""
                    SELECT c.relrowsecurity AND c.relforcerowsecurity FROM pg_class c
                    JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='tna' AND c.relname=?
                    """, Boolean.class, table), table);
        }
        assertTrue(admin.queryForObject("SELECT has_table_privilege('tna_app','tna.holiday','DELETE')", Boolean.class));
        assertFalse(admin.queryForObject("SELECT has_table_privilege('tna_app','tna.holiday_year','DELETE')", Boolean.class));
        assertFalse(admin.queryForObject("SELECT has_column_privilege('tna_app','tna.holiday_year','year','UPDATE')", Boolean.class));
        assertFalse(admin.queryForObject("SELECT has_table_privilege('tna_auth','tna.holiday','SELECT')", Boolean.class));
        assertFalse(admin.queryForObject("SELECT has_column_privilege('tna_app','tna.calendar_version','scope','UPDATE')", Boolean.class));
        assertTrue(admin.queryForObject("SELECT has_table_privilege('tna_app','tna.calendar_effective_day','SELECT')", Boolean.class));

        tx.execute(tenant, () -> holiday("2027-01-01", "PUBLIC"));
        assertEquals(1, tx.execute(tenant, () -> app.queryForObject("SELECT count(*) FROM tna.holiday", Integer.class)));
        assertEquals(0, tx.execute(UUID.randomUUID(), () -> app.queryForObject("SELECT count(*) FROM tna.holiday", Integer.class)));
        assertEquals("23505", sqlState(() -> tx.execute(tenant, () -> holiday("2027-01-01", "COMPANY"))));
        assertEquals("23514", sqlState(() -> tx.execute(tenant, () -> holiday("2027-01-02", "HOLIDAY"))));
        assertEquals("23514", sqlState(() -> tx.execute(tenant, () -> app.update(
                "INSERT INTO tna.holiday_year(tenant_id,year,version) VALUES (?,1999,1)", tenant))));
    }

    @Test void dayRevisionsStackOnPeriodsAndTheViewTakesTheHighestRevision() {
        UUID period = UUID.randomUUID(), day = UUID.randomUUID();
        tx.execute(tenant, () -> {
            version(period, 1, "2026-05-01", "2026-06-01", "PERIOD");
            day(period, "2026-05-05", "WORKDAY", "근로일");
            day(period, "2026-05-06", "WORKDAY", "근로일");
            version(day, 2, "2026-05-05", "2026-05-06", "DAY");
            day(day, "2026-05-05", "HOLIDAY", "어린이날");
            return null;
        });
        assertEquals(List.of(day + ":HOLIDAY:어린이날", period + ":WORKDAY:근로일"), tx.execute(tenant, () -> app.queryForList("""
                SELECT version_id::text||':'||kind||':'||name FROM tna.calendar_effective_day ORDER BY work_date
                """, String.class)));
        assertEquals(0, tx.execute(UUID.randomUUID(), () -> app.queryForObject("SELECT count(*) FROM tna.calendar_effective_day", Integer.class)));
        assertEquals("PERIOD", admin.queryForObject("SELECT scope FROM tna.calendar_version WHERE id=?", String.class, period));
        assertEquals("23P01", sqlState(() -> tx.execute(tenant, () -> version(UUID.randomUUID(), 3, "2026-05-20", "2026-06-10", "PERIOD"))));
        assertEquals("23514", sqlState(() -> tx.execute(tenant, () -> version(UUID.randomUUID(), 3, "2026-05-07", "2026-05-09", "DAY"))));
        assertEquals("23514", sqlState(() -> tx.execute(tenant, () -> version(UUID.randomUUID(), 3, "2026-05-07", "2026-05-08", "WEEK"))));
        tx.execute(tenant, () -> version(UUID.randomUUID(), 3, "2026-05-06", "2026-05-07", "DAY"));
    }

    private int holiday(String date, String category) {
        return app.update("""
                INSERT INTO tna.holiday(tenant_id,id,holiday_date,name,category,created_by,updated_by)
                VALUES (?,?,CAST(? AS date),'합성 휴일',?,?,?)
                """, tenant, UUID.randomUUID(), date, category, account, account);
    }

    private int version(UUID id, int revision, String from, String to, String scope) {
        return app.update("""
                INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,scope,reason,created_by)
                VALUES (?,?,?,CAST(? AS date),CAST(? AS date),?,'합성 반영',?)
                """, tenant, id, revision, from, to, scope, account);
    }

    private int day(UUID version, String date, String kind, String name) {
        return app.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES (?,?,CAST(? AS date),?,?,'합성 근거')",
                tenant, version, date, kind, name);
    }

    private static String sqlState(Runnable action) {
        var error = assertThrows(DataAccessException.class, action::run);
        return ((SQLException) error.getMostSpecificCause()).getSQLState();
    }
}
```

`TenantTransactions.execute`의 반환형이 `Supplier<T>`이므로 `version(...)`처럼 `int`를 돌려주는 람다는 그대로 쓴다.

- [ ] **Step 2: 실패하는 이전(마이그레이션) 테스트 작성**

`CodeMigrationUpgradeIT.java:18-33`의 임시 DB 준비(데이터베이스 이름 검증, 권한 부여, `finally`의 `DROP DATABASE … WITH (FORCE)`)를 그대로 따른다. 2026-12-25가 금요일이므로 아래 날짜의 요일은 12-28 월, 12-29 화, 12-31 목, 2027-01-01 금, 01-02 토, 01-03 일이다.

```java
package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class HolidayMigrationIT {
    @Test void v41MovesCurrentHolidaysAndWeekdayNonWorkdaysIntoTheHolidayTable() {
        var fixture = DatabaseFixture.start();
        var cluster = new JdbcTemplate(fixture.adminDataSource());
        String database = "tna_holiday_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(database.matches("tna_holiday_upgrade_[0-9a-f]{32}"));
        String url = fixture.jdbcUrl().replace("/" + DatabaseFixture.DATABASE_NAME, "/" + database);
        assertNotEquals(url, fixture.jdbcUrl());
        var original = (DriverManagerDataSource) fixture.adminDataSource();
        var adminSource = new DriverManagerDataSource(url, original.getUsername(), original.getPassword());
        var migrationSource = new DriverManagerDataSource(url, DatabaseFixture.MIGRATION_USER, DatabaseFixture.MIGRATION_PASSWORD);
        cluster.execute("CREATE DATABASE " + database);
        try {
            var db = new JdbcTemplate(adminSource);
            cluster.execute("REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
            cluster.execute("GRANT CONNECT ON DATABASE " + database + " TO tna_migration,tna_app,tna_auth,tna_operator");
            cluster.execute("GRANT CREATE ON DATABASE " + database + " TO tna_migration");
            db.execute("GRANT USAGE,CREATE ON SCHEMA public TO tna_migration");
            assertTrue(Flyway.configure().dataSource(migrationSource).locations("classpath:db/migration").target("40").load().migrate().success);

            UUID tenant = UUID.randomUUID(), other = UUID.randomUUID(), employee = UUID.randomUUID(), account = UUID.randomUUID(), calendar = UUID.randomUUID();
            db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'V40 합성 회사')", tenant, "V40-" + tenant);
            db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'V40 달력 없는 회사')", other, "V40-" + other);
            db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'V1','V40 담당자',DATE '2020-01-01')", tenant, employee);
            db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,'v40@example.test','synthetic-only')", tenant, account, employee);
            db.update("INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by) VALUES (?,?,1,DATE '2026-12-28',DATE '2027-01-04','V40 달력',?)",
                    tenant, calendar, account);
            for (String[] day : List.of(
                    new String[] {"2026-12-28", "WORKDAY", "검토한 기본 근로일", "기간 근거"},
                    new String[] {"2026-12-29", "NON_WORKDAY", "회사 휴무", "노사 합의"},
                    new String[] {"2026-12-30", "WORKDAY", "검토한 기본 근로일", "기간 근거"},
                    new String[] {"2026-12-31", "HOLIDAY", "창립기념일", "취업규칙 제10조"},
                    new String[] {"2027-01-01", "HOLIDAY", "1월 1일", "월력요항"},
                    new String[] {"2027-01-02", "NON_WORKDAY", "주말", "기간 근거"},
                    new String[] {"2027-01-03", "NON_WORKDAY", "주말", "기간 근거"})) {
                db.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES (?,?,CAST(? AS date),?,?,?)",
                        tenant, calendar, day[0], day[1], day[2], day[3]);
            }

            var upgrade = Flyway.configure().dataSource(migrationSource).locations("classpath:db/migration").target("41").load();
            assertEquals(1, upgrade.migrate().migrationsExecuted);
            assertEquals(0, upgrade.migrate().migrationsExecuted);

            assertEquals(List.of(
                    "2026-12-29|NON_WORKDAY|회사 휴무|노사 합의",
                    "2026-12-31|COMPANY|창립기념일|취업규칙 제10조",
                    "2027-01-01|COMPANY|1월 1일|월력요항"), db.queryForList("""
                    SELECT holiday_date||'|'||category||'|'||name||'|'||basis FROM tna.holiday
                    WHERE tenant_id=? AND created_by=? AND updated_by=? ORDER BY holiday_date
                    """, String.class, tenant, account, account));
            assertEquals(List.of("2026|1", "2027|1"), db.queryForList("""
                    SELECT year||'|'||version FROM tna.holiday_year
                    WHERE tenant_id=? AND applied_at IS NOT NULL AND applied_by IS NULL ORDER BY year
                    """, String.class, tenant));
            assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.holiday_year WHERE tenant_id=?", Integer.class, other));
            assertEquals("PERIOD", db.queryForObject("SELECT scope FROM tna.calendar_version WHERE id=?", String.class, calendar));
        } finally {
            cluster.execute("DROP DATABASE " + database + " WITH (FORCE)");
        }
    }
}
```

- [ ] **Step 3: 기존 스키마 테스트 갱신**

- `MigrationRunnerIT.java:31` `assertEquals(40, …)` → `assertEquals(41, …)`, `:35` `WHERE version = '40'` → `'41'`.
- `DatabaseFoundationIT.java:44` `"employment_rule_assessment", "leave_bucket", "leave_bucket_attribution",` → `"employment_rule_assessment", "holiday", "holiday_year", "leave_bucket", "leave_bucket_attribution",`

- [ ] **Step 4: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=HolidaySchemaIT,HolidayMigrationIT,MigrationRunnerIT,DatabaseFoundationIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: FAIL(`tna.holiday` 없음, 마이그레이션 수 40)

- [ ] **Step 5: 마이그레이션 작성**

```sql
-- 휴일 관리: 연도별 휴일 표와 근태 달력 날짜별 반영 (설계 2026-11-24-holiday-management-design.md §2)
CREATE TABLE tna.holiday (
    tenant_id uuid NOT NULL REFERENCES platform.tenant_registry (id),
    id uuid NOT NULL,
    holiday_date date NOT NULL,
    name varchar(120) NOT NULL,
    category varchar(20) NOT NULL,
    basis varchar(500),
    created_by uuid NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by uuid NOT NULL,
    updated_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,holiday_date),
    FOREIGN KEY (tenant_id,created_by) REFERENCES tna.tenant_account (tenant_id,id),
    FOREIGN KEY (tenant_id,updated_by) REFERENCES tna.tenant_account (tenant_id,id),
    CONSTRAINT holiday_name_valid CHECK (btrim(name)<>''),
    CONSTRAINT holiday_category_valid CHECK (category IN ('PUBLIC','COMPANY','NON_WORKDAY')),
    CONSTRAINT holiday_basis_valid CHECK (basis IS NULL OR btrim(basis)<>'')
);

CREATE TABLE tna.holiday_year (
    tenant_id uuid NOT NULL REFERENCES platform.tenant_registry (id),
    year integer NOT NULL,
    version bigint NOT NULL,
    applied_at timestamptz(3),
    applied_by uuid,
    PRIMARY KEY (tenant_id,year),
    FOREIGN KEY (tenant_id,applied_by) REFERENCES tna.tenant_account (tenant_id,id),
    CONSTRAINT holiday_year_range CHECK (year BETWEEN 2000 AND 2100),
    CONSTRAINT holiday_year_version_positive CHECK (version>0)
);

ALTER TABLE tna.holiday ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.holiday FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.holiday
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE tna.holiday_year ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.holiday_year FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.holiday_year
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.holiday,tna.holiday_year FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE,DELETE ON tna.holiday TO tna_app;
GRANT SELECT,INSERT ON tna.holiday_year TO tna_app;
GRANT UPDATE (version,applied_at,applied_by) ON tna.holiday_year TO tna_app;

-- 날짜 하나만 고치는 반영분(DAY)은 기간(PERIOD) 위에 쌓인다. 기간끼리는 계속 겹칠 수 없다. 기존 행은 모두 PERIOD다.
ALTER TABLE tna.calendar_version
    ADD COLUMN scope varchar(10) NOT NULL DEFAULT 'PERIOD',
    ADD CONSTRAINT calendar_scope_valid CHECK (scope IN ('PERIOD','DAY')),
    ADD CONSTRAINT calendar_day_scope_one_date CHECK (scope='PERIOD' OR valid_to=valid_from+1);
ALTER TABLE tna.calendar_version DROP CONSTRAINT calendar_period_not_overlapping;
ALTER TABLE tna.calendar_version ADD CONSTRAINT calendar_period_not_overlapping EXCLUDE USING gist (
    tenant_id tna.gist_uuid_ops WITH =,
    daterange(valid_from,valid_to,'[)') WITH &&
) WHERE (scope='PERIOD');
CREATE INDEX calendar_day_by_date ON tna.calendar_day (tenant_id,work_date);

-- 한 날짜에는 그 날짜를 포함하는 버전 가운데 revision이 가장 큰 버전을 쓴다. V41 전에는 겹침이 없어 기존 결과와 같다.
CREATE VIEW tna.calendar_effective_day WITH (security_invoker=true) AS
SELECT DISTINCT ON (d.tenant_id,d.work_date)
       d.tenant_id,d.work_date,d.version_id,d.kind,d.name,d.basis
FROM tna.calendar_day d
JOIN tna.calendar_version v ON v.tenant_id=d.tenant_id AND v.id=d.version_id
    AND v.valid_from<=d.work_date AND v.valid_to>d.work_date
ORDER BY d.tenant_id,d.work_date,v.revision DESC;
REVOKE ALL ON tna.calendar_effective_day FROM PUBLIC;
GRANT SELECT ON tna.calendar_effective_day TO tna_app;

-- 기존 데이터 이전(§2.3): 휴일 → COMPANY, 평일 비근로일 → NON_WORKDAY. 주말 비근로일은 근로일과 같게 보므로 옮기지 않는다.
CREATE POLICY holiday_migration_registry_scan ON platform.tenant_registry FOR SELECT TO tna_migration USING (true);
DO $body$
DECLARE _tenant uuid;
BEGIN
    FOR _tenant IN SELECT id FROM platform.tenant_registry LOOP
        PERFORM pg_catalog.set_config('app.tenant_id',_tenant::text,true);
        INSERT INTO tna.holiday(tenant_id,id,holiday_date,name,category,basis,created_by,updated_by)
        SELECT e.tenant_id,pg_catalog.gen_random_uuid(),e.work_date,e.name,
               CASE e.kind WHEN 'HOLIDAY' THEN 'COMPANY' ELSE 'NON_WORKDAY' END,e.basis,v.created_by,v.created_by
        FROM tna.calendar_effective_day e
        JOIN tna.calendar_version v ON v.tenant_id=e.tenant_id AND v.id=e.version_id
        WHERE e.tenant_id=_tenant AND extract(year FROM e.work_date) BETWEEN 2000 AND 2100
          AND (e.kind='HOLIDAY' OR e.kind='NON_WORKDAY' AND extract(isodow FROM e.work_date)<6);
        INSERT INTO tna.holiday_year(tenant_id,year,version,applied_at)
        SELECT DISTINCT _tenant,extract(year FROM work_date)::integer,1,CURRENT_TIMESTAMP
        FROM tna.calendar_effective_day
        WHERE tenant_id=_tenant AND extract(year FROM work_date) BETWEEN 2000 AND 2100;
    END LOOP;
    PERFORM pg_catalog.set_config('app.tenant_id','',true);
END $body$;
DROP POLICY holiday_migration_registry_scan ON platform.tenant_registry;
```

- [ ] **Step 6: 통과 확인**

Run: Step 4 명령. 이어서 `-Dit.test=AttendanceSchemaIT,LeaveWorkflowSchemaIT,CodeMigrationUpgradeIT`도 실행한다(기존 PERIOD 겹침 거부 `23P01`, V40까지의 이전 검증).
Expected: PASS

- [ ] **Step 7: 커밋**

```powershell
git add src/main/resources/db/migration/V41__holiday_management.sql src/test/java/kr/tna/HolidaySchemaIT.java src/test/java/kr/tna/HolidayMigrationIT.java src/test/java/kr/tna/MigrationRunnerIT.java src/test/java/kr/tna/DatabaseFoundationIT.java
git commit -m "feat: 휴일 표·연도 버전·날짜별 달력 반영분과 유효 달력 뷰, 기존 달력 휴일 이전"
```

---

### Task 3: 달력 읽기를 유효 달력 뷰로 바꾸기

**Files:**
- Create: `src/test/java/kr/tna/CalendarSeed.java`(테스트 시드 도우미, Task 4·6에서도 사용)
- Modify: `src/main/java/kr/tna/clock/ClockMapper.java:64-69`, `src/main/java/kr/tna/worktime/MonthEndReviewMapper.java:61-65`
- Test: `src/test/java/kr/tna/CalendarEffectiveDayIT.java`

- [ ] **Step 1: 시드 도우미 작성**

제거할 기간 등록(`AttendanceSettingsService.calendar`)과 같은 행을 만든다. 예외 줄 형식(`날짜|종류|이름|근거`)도 같다.

```java
package kr.tna;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;

/** 테스트용 근태 달력 기간(PERIOD) 시드. admin 연결로 넣는다. */
final class CalendarSeed {
    private CalendarSeed() { }

    /** from~through(포함)의 모든 날을 WORKDAY로 만들고, exceptions의 `날짜|종류|이름|근거` 줄로 덮는다. 버전 id를 돌려준다. */
    static UUID period(JdbcTemplate admin, TnaPrincipal actor, LocalDate from, LocalDate through, String basis, String exceptions) {
        var overrides = new HashMap<LocalDate, String[]>();
        if (exceptions != null) {
            for (String line : exceptions.lines().filter(line -> !line.isBlank()).toList()) {
                String[] parts = line.split("\\|", -1);
                overrides.put(LocalDate.parse(parts[0].strip()), parts);
            }
        }
        UUID id = UUID.randomUUID();
        admin.update("""
                INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by)
                SELECT ?,?,COALESCE(max(revision),0)+1,?,?,'합성 달력',? FROM tna.calendar_version WHERE tenant_id=?
                """, actor.tenantId(), id, from, through.plusDays(1), actor.accountId(), actor.tenantId());
        for (LocalDate date = from; !date.isAfter(through); date = date.plusDays(1)) {
            String[] day = overrides.getOrDefault(date, new String[] {null, "WORKDAY", "검토한 기본 근로일", basis});
            admin.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES (?,?,?,?,?,?)",
                    actor.tenantId(), id, date, day[1].strip(), day[2].strip(), day[3].strip());
        }
        return id;
    }
}
```

- [ ] **Step 2: 실패하는 통합 테스트 작성**

`member(...)`와 `@DynamicPropertySource`는 `EmployeeImportWebIT.java:42-50, 97-105`를 그대로 옮긴다.

```java
package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockMapper;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.worktime.WorktimeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class CalendarEffectiveDayIT {
    @Autowired ClockMapper clock;
    @Autowired WorktimeMapper worktime;
    @Autowired TenantTransactions transactions;
    JdbcTemplate db;
    UUID tenant;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) { /* EmployeeImportWebIT와 같다 */ }

    @Test void aDayRevisionOverridesThePeriodForSingleAndMonthReaders() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 유효 달력')", tenant, "EFFECTIVE-" + tenant);
        TnaPrincipal manager = member("ADMIN", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        LocalDate holiday = LocalDate.of(2026, 5, 5);
        UUID period = CalendarSeed.period(db, manager, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), "합성 달력", "");
        UUID day = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,scope,reason,created_by)
                VALUES (?,?,2,?,?,'DAY','합성 날짜 반영',?)
                """, tenant, day, holiday, holiday.plusDays(1), manager.accountId());
        db.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES (?,?,?,'HOLIDAY','어린이날','합성')",
                tenant, day, holiday);

        assertEquals(List.of(new ClockMapper.CalendarRow(day, "HOLIDAY")), transactions.execute(tenant, () -> clock.calendar(tenant, holiday)));
        assertEquals(List.of(new ClockMapper.CalendarRow(period, "WORKDAY")),
                transactions.execute(tenant, () -> clock.calendar(tenant, holiday.plusDays(1))));
        var month = transactions.execute(tenant, () -> worktime.monthDays(tenant, manager.employeeId(), holiday.minusDays(1), holiday.plusDays(1)));
        assertEquals(List.of(period, day, period), month.stream().map(WorktimeMapper.MonthDayRow::calendarVersionId).toList());
        assertEquals(List.of("WORKDAY", "HOLIDAY", "WORKDAY"), month.stream().map(WorktimeMapper.MonthDayRow::dayKind).toList());
    }

    private TnaPrincipal member(String number, Set<String> roles) { /* EmployeeImportWebIT.member와 같다 */ }
}
```

- [ ] **Step 3: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=CalendarEffectiveDayIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: FAIL(`clock.calendar`가 두 버전을 모두 돌려줌)

- [ ] **Step 4: 읽기 쿼리 교체**

`ClockMapper.java:64-69`:

```java
    @Select("""
            SELECT version_id,kind FROM tna.calendar_effective_day
            WHERE tenant_id=#{tenantId} AND work_date=#{date}
            """)
    List<CalendarRow> calendar(@Param("tenantId") UUID tenantId,@Param("date") LocalDate date);
```

`MonthEndReviewMapper.java:61-65`의 달력 LATERAL:

```java
            LEFT JOIN LATERAL (
                SELECT e.version_id,e.kind FROM tna.calendar_effective_day e
                WHERE e.tenant_id=#{tenantId} AND e.work_date=d.work_date
            ) cal ON true
```

- `ClockService.snapshot`의 `calendars.size()==1?…:null`은 그대로 둔다(뷰는 날짜당 최대 1행).
- 입력 지문(`ClockService.sourceFingerprint`)은 `calendarVersion`·`dayKind`만 쓰므로 구조를 바꾸지 않는다. V41 전 데이터는 겹침이 없어 모든 날짜의 버전 id와 종류가 같으므로 기존 근무 확인·일 결과의 지문은 그대로다.

- [ ] **Step 5: 통과와 회귀 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=CalendarEffectiveDayIT,AttendanceServiceIT,CorrectionProjectionIT,WorktimeServiceIT,MonthEndBatchServiceIT,CheckoutOperationsWebIT,LeaveWorkflowIT,AttendanceMonthCloseIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: PASS(`CorrectionProjectionIT.changedCalendarVersionInvalidatesCorrectionEvidenceWithUnchangedFactCounter`는 기존 PERIOD를 줄이고 revision 2를 넣으므로 그대로 통과)

- [ ] **Step 6: 커밋**

```powershell
git add src/main/java/kr/tna/clock/ClockMapper.java src/main/java/kr/tna/worktime/MonthEndReviewMapper.java src/test/java/kr/tna/CalendarSeed.java src/test/java/kr/tna/CalendarEffectiveDayIT.java
git commit -m "feat: 출퇴근·월 조회가 날짜별 최신 달력 반영분을 읽도록 유효 달력 뷰로 전환"
```

---

### Task 4: 기간 등록을 부르던 테스트를 SQL 시드로 옮기기

운영 코드는 바꾸지 않는다. Task 7에서 `AttendanceSettingsService.calendar`를 지우기 전에 호출만 옮겨, 이 단계에서 회귀가 없음을 확인한다.

**Files (Modify, `src/test/java/kr/tna/`):**

| 파일:줄 | 지금 | 바꿀 코드 |
| --- | --- | --- |
| `AnnualLeaveCycleBrowser.java:97-98` | `app.getBean(AttendanceSettingsService.class).calendar(manager, new CalendarForm(TODAY, TODAY.plusDays(7), true, "가상 연차 검증 주간", "", …))` | `CalendarSeed.period(db, manager, TODAY, TODAY.plusDays(7), "가상 연차 검증 주간", "");` |
| `AnnualRequestsWebIT.java:60` | `calendar=settings.calendar(admin,new CalendarForm(today,today.plusDays(300),true,"합성 달력","",…))` | `calendar=CalendarSeed.period(db,admin,today,today.plusDays(300),"합성 달력","");` |
| `ApprovalRouteWorkflowIT.java:108` | `settings.calendar(admin,new CalendarForm(today,date.plusDays(10),true,"검토","",…))` | `CalendarSeed.period(db,admin,today,date.plusDays(10),"검토","");` |
| `ApprovalRouteWorkflowIT.java:210` | `…(today,today.plusDays(20),true,"달력 근거","",…)` | `CalendarSeed.period(db,admin,today,today.plusDays(20),"달력 근거","");` |
| `ApprovalRouteWorkflowIT.java:492` | `…(today,today.plusDays(20),true,"합성 검토 근거","",…)` | `CalendarSeed.period(db,admin,today,today.plusDays(20),"합성 검토 근거","");` |
| `AttendanceMonthCloseIT.java:612` | `settings.calendar(admin, new CalendarForm(LAST, LAST, true, "근로일 확인", "", …))` | `CalendarSeed.period(db, admin, LAST, LAST, "근로일 확인", "");` |
| `AttendanceTypeWorkflowIT.java:66` | `…(today,today.plusDays(30),true,"합성 근거","",…)` | `CalendarSeed.period(db,admin,today,today.plusDays(30),"합성 근거","");` |
| `CheckoutOperationsWebIT.java:358` | `attendanceSettings.calendar(admin, new CalendarForm(date, month.atEndOfMonth(), true, "합성 근무 달력", holidays, …))` | `CalendarSeed.period(db, admin, date, month.atEndOfMonth(), "합성 근무 달력", holidays);` |
| `EmploymentLeaveBrowser.java:116` | `app.getBean(AttendanceSettingsService.class).calendar(hr, new CalendarForm(HIRED,LAST,true,"가상 근무 달력","",…))` | `CalendarSeed.period(db, hr, HIRED, LAST, "가상 근무 달력", "");` |
| `LeaveWorkflowIT.java:98` | `…(today,today.plusDays(30),true,"합성 검토 자료","",…)` | `CalendarSeed.period(db,manager,today,today.plusDays(30),"합성 검토 자료","");` |
| `LeaveWorkflowWebIT.java:46`, `:100` | `…(today,today.plusDays(30),true,"합성 근거","",…)` | `CalendarSeed.period(db,manager,today,today.plusDays(30),"합성 근거","");` |
| `MonthEndBatchServiceIT.java:390` | `settings.calendar(manager, new CalendarForm(date, date, true, "합성 휴무일", "", …))` | `CalendarSeed.period(db, manager, date, date, "합성 휴무일", "");` |
| `MvpJourneyIT.java:144-145` | `UUID calendar = settings.calendar(manager, new CalendarForm(MONDAY, MONDAY.plusDays(6), true, "합성 일주일 검토", "", …));` | `UUID calendar = CalendarSeed.period(new JdbcTemplate(DatabaseFixture.start().adminDataSource()), manager, MONDAY, MONDAY.plusDays(6), "합성 일주일 검토", "");` |
| `WorktimeServiceIT.java:100` | `…(date.minusDays(7),date.plusDays(7),true,"합성 공휴일 검토","",…)` | `CalendarSeed.period(db,manager,date.minusDays(7),date.plusDays(7),"합성 공휴일 검토","");` |

- 각 파일에서 이 변경으로 쓰지 않게 된 `CalendarForm`·`AttendanceSettingsService` import와 `settings`/`attendanceSettings` 필드를 지운다. `MvpJourneyIT`는 `settings.addNetwork`를 계속 쓰므로 필드를 남긴다. `SeoulTimeDisplayWebIT`는 허용망만 쓰므로 바꾸지 않는다.
- 브라우저 클래스의 `db`는 `prepare(app, db, run)`/`workAndApproval(app, db)` 인자(admin 연결)다. 인자가 아니면 `new JdbcTemplate(DatabaseFixture.start().adminDataSource())`를 쓴다.
- `AttendanceSettingsIT`, `EmployeeServiceIT:124,136`, `CheckoutOperationsWebIT:949-956`은 기간 등록의 동작 자체를 검사하므로 Task 7에서 지운다.

- [ ] **Step 1: 위 표대로 바꾼다**

- [ ] **Step 2: 회귀 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=AnnualRequestsWebIT,ApprovalRouteWorkflowIT,AttendanceMonthCloseIT,AttendanceTypeWorkflowIT,CheckoutOperationsWebIT,LeaveWorkflowIT,LeaveWorkflowWebIT,MonthEndBatchServiceIT,MvpJourneyIT,WorktimeServiceIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify` 그리고 `.\mvnw.cmd -q "-Dfrontend.skip=true" -DskipTests test-compile`(브라우저 클래스 컴파일)
Expected: PASS. 날짜 의존으로 이미 불안정한 테스트(`LeaveWorkflowIT`)가 실패하면 변경 전 커밋에서도 같은지 확인하고 결과 문서에 적는다.

- [ ] **Step 3: 커밋**

```powershell
git add src/test/java/kr/tna
git commit -m "test: 기간 등록 호출을 근태 달력 SQL 시드 도우미로 이전"
```

---

### Task 5: 휴일 → 근태 달력 반영 계산(순수)

**Files:**
- Create: `src/main/java/kr/tna/clock/HolidayReflection.java`
- Test: `src/test/java/kr/tna/clock/HolidayReflectionTest.java`

- [ ] **Step 1: 실패하는 단위 테스트 작성**

```java
package kr.tna.clock;

import static kr.tna.clock.HolidayReflection.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HolidayReflectionTest {
    private static final List<Period> YEAR_2026 = List.of(new Period(d(2026, 1, 1), d(2027, 1, 1)));

    private static LocalDate d(int y, int m, int day) { return LocalDate.of(y, m, day); }

    /** 기존 기간 등록과 데모 시드처럼 평일 WORKDAY, 주말 NON_WORKDAY인 한 해. */
    private static Map<LocalDate, Effective> weekdayCalendar(int year) {
        var map = new HashMap<LocalDate, Effective>();
        for (LocalDate date = d(year, 1, 1); date.getYear() == year; date = date.plusDays(1)) {
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            map.put(date, new Effective(date, weekend ? "NON_WORKDAY" : "WORKDAY", weekend ? "주말" : "검토한 기본 근로일"));
        }
        return map;
    }

    @Test void anUncoveredYearBecomesOnePeriodWithEveryDate() {
        var plan = plan(2027, Map.of(
                d(2027, 1, 1), new Holiday("PUBLIC", "1월 1일", "공휴일 · 근거"),
                d(2027, 3, 2), new Holiday("NON_WORKDAY", "창립 휴무", null)), Map.of(), List.of(), Set.of());
        assertEquals(List.of(), plan.days());
        assertEquals(1, plan.periods().size());
        var days = plan.periods().getFirst();
        assertEquals(365, days.size());
        assertEquals(new Day(d(2027, 1, 1), "HOLIDAY", "1월 1일", "공휴일 · 근거"), days.get(0));
        assertEquals(new Day(d(2027, 1, 2), "WORKDAY", WORKDAY_NAME, DEFAULT_BASIS), days.get(1));
        assertEquals(new Day(d(2027, 3, 2), "NON_WORKDAY", "창립 휴무", DEFAULT_BASIS), days.get(60));
    }

    @Test void onlyDatesWhoseKindOrHolidayNameDiffersGetADayRevision() {
        var current = weekdayCalendar(2026);
        current.put(d(2026, 5, 5), new Effective(d(2026, 5, 5), "HOLIDAY", "어린이 날"));
        current.put(d(2026, 8, 17), new Effective(d(2026, 8, 17), "HOLIDAY", "대체공휴일(광복절)"));
        var plan = plan(2026, Map.of(
                d(2026, 5, 5), new Holiday("PUBLIC", "어린이날", null),
                d(2026, 8, 17), new Holiday("PUBLIC", "대체공휴일(광복절)", "근거만 바뀜"),
                d(2026, 9, 24), new Holiday("PUBLIC", "추석 전날", null)), current, YEAR_2026, Set.of());
        assertEquals(List.of(), plan.periods());
        assertEquals(List.of(d(2026, 5, 5), d(2026, 9, 24)), plan.dates());
    }

    @Test void datesWithoutAHolidayReturnToWorkdaysExceptExistingWeekendNonWorkdays() {
        var current = weekdayCalendar(2026);
        current.put(d(2026, 3, 1), new Effective(d(2026, 3, 1), "HOLIDAY", "3·1절"));            // 일요일
        current.put(d(2026, 3, 4), new Effective(d(2026, 3, 4), "NON_WORKDAY", "창립 휴무"));     // 수요일
        var plan = plan(2026, Map.of(), current, YEAR_2026, Set.of());
        assertEquals(List.of(
                new Day(d(2026, 3, 1), "WORKDAY", WORKDAY_NAME, DEFAULT_BASIS),
                new Day(d(2026, 3, 4), "WORKDAY", WORKDAY_NAME, DEFAULT_BASIS)), plan.days());
    }

    @Test void closedMonthsAreSkippedAndSplitTheUncoveredPeriod() {
        var plan = plan(2026, Map.of(d(2026, 1, 2), new Holiday("COMPANY", "시무식", null)), Map.of(), List.of(),
                Set.of(YearMonth.of(2026, 1), YearMonth.of(2026, 6)));
        assertEquals(2, plan.periods().size());
        assertEquals(d(2026, 2, 1), plan.periods().get(0).getFirst().date());
        assertEquals(d(2026, 5, 31), plan.periods().get(0).getLast().date());
        assertEquals(d(2026, 7, 1), plan.periods().get(1).getFirst().date());
        assertEquals(d(2026, 12, 31), plan.periods().get(1).getLast().date());
        assertEquals(365 - 31 - 30, plan.dates().size());
    }

    @Test void aPeriodDateWithoutADayRowIsFilledByADayRevisionNotAnOverlappingPeriod() {
        var current = new HashMap<LocalDate, Effective>();
        current.put(d(2026, 3, 1), new Effective(d(2026, 3, 1), "NON_WORKDAY", "주말"));
        current.put(d(2026, 3, 3), new Effective(d(2026, 3, 3), "WORKDAY", "근로일"));
        var plan = plan(2026, Map.of(), current, List.of(new Period(d(2026, 3, 1), d(2026, 3, 4))), Set.of());
        assertEquals(List.of(new Day(d(2026, 3, 2), "WORKDAY", WORKDAY_NAME, DEFAULT_BASIS)), plan.days());
        assertEquals(2, plan.periods().size());
        assertEquals(d(2026, 2, 28), plan.periods().get(0).getLast().date());
        assertEquals(d(2026, 3, 4), plan.periods().get(1).getFirst().date());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dtest=HolidayReflectionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL(컴파일 오류)

- [ ] **Step 3: 구현**

```java
package kr.tna.clock;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** 휴일 표를 날짜별 근태 달력 종류로 바꾸고, 지금 근태 달력과 다른 날만 고른다. DB를 모른다. */
public final class HolidayReflection {
    public static final String WORKDAY_NAME = "근로일";
    /** calendar_day.basis는 비울 수 없다. 휴일 행 근거가 없거나 근로일일 때 쓴다. */
    public static final String DEFAULT_BASIS = "휴일 관리 반영";

    public record Holiday(String category, String name, String basis) { }
    public record Effective(LocalDate date, String kind, String name) { }
    public record Period(LocalDate validFrom, LocalDate validTo) { }
    public record Day(LocalDate date, String kind, String name, String basis) { }
    /** periods: 근태 달력이 없는 연속 구간(PERIOD 버전 하나씩). days: 날짜 하나씩 고치는 반영분(DAY 버전 하나씩). */
    public record Plan(List<List<Day>> periods, List<Day> days) {
        public List<LocalDate> dates() {
            return Stream.concat(periods.stream().flatMap(List::stream), days.stream()).map(Day::date).sorted().toList();
        }
    }

    private HolidayReflection() { }

    public static Day desired(LocalDate date, Holiday holiday) {
        if (holiday == null) return new Day(date, "WORKDAY", WORKDAY_NAME, DEFAULT_BASIS);
        String kind = "NON_WORKDAY".equals(holiday.category()) ? "NON_WORKDAY" : "HOLIDAY";
        return new Day(date, kind, holiday.name(), holiday.basis() == null ? DEFAULT_BASIS : holiday.basis());
    }

    /**
     * 근로일끼리는 이름을 비교하지 않는다(기존 기간 등록의 "검토한 기본 근로일"). 휴일 행이 없는 주말의 기존 비근로일은
     * 근로일과 같다고 본다. 근무 요일은 근무조가 정한다(설계 §2.2-4). 근거만 바뀐 날은 반영하지 않는다.
     */
    static boolean same(Day wanted, Effective current) {
        if (wanted.kind().equals("WORKDAY")) {
            return current.kind().equals("WORKDAY") || current.kind().equals("NON_WORKDAY") && weekend(wanted.date());
        }
        return wanted.kind().equals(current.kind()) && wanted.name().equals(current.name());
    }

    public static Plan plan(int year, Map<LocalDate, Holiday> holidays, Map<LocalDate, Effective> effective,
                            List<Period> periods, Set<YearMonth> closed) {
        var runs = new ArrayList<List<Day>>();
        var days = new ArrayList<Day>();
        List<Day> run = null;
        for (LocalDate date = LocalDate.of(year, 1, 1); date.getYear() == year; date = date.plusDays(1)) {
            if (closed.contains(YearMonth.from(date))) { run = null; continue; }
            Day wanted = desired(date, holidays.get(date));
            Effective current = effective.get(date);
            if (current == null && !covered(date, periods)) {
                if (run == null) { run = new ArrayList<>(); runs.add(run); }
                run.add(wanted);
                continue;
            }
            run = null;
            // 기간 안인데 날짜 행이 없는 날은 기간을 겹쳐 만들 수 없으므로 DAY로 채운다.
            if (current == null || !same(wanted, current)) days.add(wanted);
        }
        return new Plan(runs.stream().map(List::copyOf).toList(), List.copyOf(days));
    }

    private static boolean covered(LocalDate date, List<Period> periods) {
        return periods.stream().anyMatch(period -> !date.isBefore(period.validFrom()) && date.isBefore(period.validTo()));
    }

    private static boolean weekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: Step 2 명령. Expected: PASS(5개)

- [ ] **Step 5: 커밋**

```powershell
git add src/main/java/kr/tna/clock/HolidayReflection.java src/test/java/kr/tna/clock/HolidayReflectionTest.java
git commit -m "feat: 휴일 표를 근태 달력과 비교해 빈 구간과 바뀐 날짜만 반영하는 계산"
```

---

### Task 6: 휴일 서비스 — 화면 모델, 내장 목록, 저장과 근태 반영

**Files:**
- Create: `src/main/java/kr/tna/clock/HolidayMapper.java`, `src/main/java/kr/tna/clock/HolidayService.java`
- Test: `src/test/java/kr/tna/HolidayServiceIT.java`

- [ ] **Step 1: 실패하는 통합 테스트 작성**

클래스 뼈대(`@SpringBootTest`, `@DynamicPropertySource`, 새 고객사, `member`)는 `EmployeeImportWebIT`와 같다. 담당자 사원 이름은 `member`가 넣는 `'합성 시트 사원'`이다.

```java
package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockService;
import kr.tna.clock.HolidayService;
import kr.tna.clock.HolidayService.HolidayProblem;
import kr.tna.clock.HolidayService.RowInput;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class HolidayServiceIT {
    @Autowired HolidayService holidays;
    @Autowired ClockService clocks;
    JdbcTemplate db;
    UUID tenant;
    TnaPrincipal manager;

    // @DynamicPropertySource, member(...)는 EmployeeImportWebIT와 같다.

    @BeforeEach void seed() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 휴일 관리')", tenant, "HOLIDAY-" + tenant);
        manager = member("ADMIN", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
    }

    static RowInput input(String id, String date, String name, String category, String basis) {
        return new RowInput(id, date, name, category, basis);
    }

    static HolidayService.Form form(UUID command, int year, long version, RowInput... rows) {
        return new HolidayService.Form(command, "휴일 정리", year, version, List.of(rows));
    }

    int count(String sql) { return db.queryForObject(sql, Integer.class, tenant); }

    @Test void firstSaveCoversTheYearOnceAndReplaysTheSameResult() {
        var form = form(UUID.randomUUID(), 2027, 0L,
                input("a", "2027-01-01", "1월 1일", "PUBLIC", "공휴일"),
                input("b", "2027-03-02", "창립기념일", "COMPANY", " "));
        var first = holidays.save(manager, form);
        assertEquals(first, holidays.save(manager, form));
        assertEquals(1, first.version());
        assertEquals(2, first.added());
        assertEquals(365, first.applied().size());
        assertEquals(List.of("PERIOD|2027-01-01|2028-01-01"), db.queryForList(
                "SELECT scope||'|'||valid_from||'|'||valid_to FROM tna.calendar_version WHERE tenant_id=?", String.class, tenant));
        assertEquals("HOLIDAY|창립기념일|휴일 관리 반영", db.queryForObject("""
                SELECT kind||'|'||name||'|'||basis FROM tna.calendar_effective_day WHERE tenant_id=? AND work_date=DATE '2027-03-02'
                """, String.class, tenant));
        assertNull(db.queryForObject("SELECT basis FROM tna.holiday WHERE tenant_id=? AND holiday_date=DATE '2027-03-02'", String.class, tenant));
        assertEquals(1, count("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='HOLIDAY_SAVE'"));
        var page = holidays.page(manager, 2027);
        assertEquals(1, page.version());
        assertEquals("합성 시트 사원", page.applied().by());
        assertEquals(List.of("1월 1일", "창립기념일"), page.rows().stream().map(HolidayService.Row::name).toList());
        assertTrue(page.builtIn());
        assertFalse(holidays.page(manager, 2030).builtIn());
    }

    @Test void onlyDatesWhoseKindOrNameChangedGetANewSourceFingerprint() {
        CalendarSeed.period(db, manager, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "합성 달력", "");
        var dates = List.of(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 5), LocalDate.of(2026, 5, 6));
        var before = dates.stream().map(date -> clocks.day(manager, date).sourceFingerprint()).toList();

        var saved = holidays.save(manager, form(UUID.randomUUID(), 2026, 0L, input("a", "2026-05-05", "어린이날", "PUBLIC", null)));
        assertEquals(List.of(LocalDate.of(2026, 5, 5)), saved.applied());
        var after = dates.stream().map(date -> clocks.day(manager, date).sourceFingerprint()).toList();
        assertEquals(before.get(0), after.get(0));
        assertNotEquals(before.get(1), after.get(1));
        assertEquals(before.get(2), after.get(2));
        assertEquals("HOLIDAY", clocks.day(manager, dates.get(1)).dayKind());

        var removed = holidays.save(manager, form(UUID.randomUUID(), 2026, 1L));
        assertEquals(1, removed.deleted());
        assertEquals(List.of(LocalDate.of(2026, 5, 5)), removed.applied());
        assertEquals("WORKDAY", clocks.day(manager, dates.get(1)).dayKind());
        assertEquals(1, count("SELECT count(*) FROM tna.calendar_version WHERE tenant_id=? AND scope='PERIOD'"));
        assertEquals(2, count("SELECT count(*) FROM tna.calendar_version WHERE tenant_id=? AND scope='DAY'"));
    }

    @Test void rejectsAStaleVersionAndReportsEveryRowErrorWithoutWriting() {
        var stale = assertThrows(OrgException.class, () -> holidays.save(manager, form(UUID.randomUUID(), 2027, 3L)));
        assertEquals(409, stale.status());
        assertEquals("최신 내용을 확인한 뒤 다시 저장하세요.", stale.getMessage());
        var problem = assertThrows(HolidayProblem.class, () -> holidays.save(manager, form(UUID.randomUUID(), 2027, 0L,
                input("a", "2027-05-05", "어린이날", "PUBLIC", null),
                input("b", "2027-05-05", "중복", "COMPANY", null),
                input("c", "2026-12-31", "다른 해", "COMPANY", null),
                input("d", "2027-06-01", " ", "COMPANY", null),
                input("e", "2027-06-02", "구분 오류", "HOLIDAY", null),
                input("f", "2027-06-03", "근거 초과", "COMPANY", "x".repeat(501)),
                input("g", "2027-13-01", "형식", "COMPANY", null))));
        assertEquals(422, problem.status());
        assertEquals(List.of("a:date", "b:date", "c:date", "d:name", "e:category", "f:basis", "g:date"),
                problem.errors().stream().map(error -> error.clientRowId() + ":" + error.field()).sorted().toList());
        assertEquals("2027년 날짜만 입력할 수 있습니다.", problem.errors().stream().filter(e -> "c".equals(e.clientRowId())).findFirst().orElseThrow().message());
        for (String table : List.of("holiday", "holiday_year", "calendar_version")) {
            assertEquals(0, count("SELECT count(*) FROM tna." + table + " WHERE tenant_id=?"), table);
        }
    }

    @Test void closedMonthsKeepTheirHolidaysAndCalendar() {
        holidays.save(manager, form(UUID.randomUUID(), 2026, 0L, input("a", "2026-01-02", "시무식 휴무", "COMPANY", null)));
        db.update("""
                INSERT INTO tna.attendance_month_closure(tenant_id,id,month_start,version,status,reason,actor_account_id,actor_name)
                VALUES (?,?,DATE '2026-01-01',1,'CLOSED','합성 월마감',?,'합성 담당자')
                """, tenant, UUID.randomUUID(), manager.accountId());

        var deleted = assertThrows(HolidayProblem.class, () -> holidays.save(manager, form(UUID.randomUUID(), 2026, 1L)));
        assertNull(deleted.errors().getFirst().clientRowId());
        assertEquals("2026-01-02: 2026-01 근태가 마감돼 바꿀 수 없습니다.", deleted.errors().getFirst().message());
        var added = assertThrows(HolidayProblem.class, () -> holidays.save(manager, form(UUID.randomUUID(), 2026, 1L,
                input("a", "2026-01-02", "시무식 휴무", "COMPANY", null), input("b", "2026-01-05", "추가 휴무", "COMPANY", null))));
        assertEquals(List.of("b:date"), added.errors().stream().map(e -> e.clientRowId() + ":" + e.field()).toList());

        var kept = holidays.save(manager, form(UUID.randomUUID(), 2026, 1L,
                input("a", "2026-01-02", "시무식 휴무", "COMPANY", null), input("c", "2026-03-03", "창립기념일", "COMPANY", null)));
        assertEquals(List.of(LocalDate.of(2026, 3, 3)), kept.applied());
        assertEquals(List.of("2026-01"), holidays.page(manager, 2026).closedMonths());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=HolidayServiceIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: FAIL(컴파일 오류, `HolidayService` 없음)

- [ ] **Step 3: 매퍼 작성**

```java
package kr.tna.clock;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.worktime.ClosedAttendanceMonths;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HolidayMapper extends ClosedAttendanceMonths {
    record YearRow(long version, Instant appliedAt, String appliedBy) { }

    @Select("""
            SELECT id,holiday_date AS date,name,category,basis FROM tna.holiday
            WHERE tenant_id=#{tenantId} AND holiday_date BETWEEN #{from} AND #{through} ORDER BY holiday_date
            """)
    List<HolidayService.Row> rows(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("through") LocalDate through);

    @Select("""
            SELECT y.version,y.applied_at,e.display_name AS applied_by FROM tna.holiday_year y
            LEFT JOIN tna.tenant_account a ON a.tenant_id=y.tenant_id AND a.id=y.applied_by
            LEFT JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
            WHERE y.tenant_id=#{tenantId} AND y.year=#{year}
            """)
    YearRow year(@Param("tenantId") UUID tenantId, @Param("year") int year);

    @Insert("""
            INSERT INTO tna.holiday_year(tenant_id,year,version,applied_at,applied_by)
            VALUES (#{tenantId},#{year},1,#{now},#{actorId})
            ON CONFLICT (tenant_id,year) DO UPDATE
            SET version=tna.holiday_year.version+1,applied_at=EXCLUDED.applied_at,applied_by=EXCLUDED.applied_by
            """)
    int saveYear(@Param("tenantId") UUID tenantId, @Param("year") int year, @Param("now") Instant now, @Param("actorId") UUID actorId);

    @Insert("""
            INSERT INTO tna.holiday(tenant_id,id,holiday_date,name,category,basis,created_by,updated_by)
            VALUES (#{tenantId},#{id},#{date},#{name},#{category},#{basis,jdbcType=VARCHAR},#{actorId},#{actorId})
            """)
    int insertHoliday(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("date") LocalDate date, @Param("name") String name,
            @Param("category") String category, @Param("basis") String basis, @Param("actorId") UUID actorId);

    @Update("""
            UPDATE tna.holiday SET name=#{name},category=#{category},basis=#{basis,jdbcType=VARCHAR},
                updated_by=#{actorId},updated_at=CURRENT_TIMESTAMP
            WHERE tenant_id=#{tenantId} AND holiday_date=#{date}
            """)
    int updateHoliday(@Param("tenantId") UUID tenantId, @Param("date") LocalDate date, @Param("name") String name,
            @Param("category") String category, @Param("basis") String basis, @Param("actorId") UUID actorId);

    @Delete("DELETE FROM tna.holiday WHERE tenant_id=#{tenantId} AND holiday_date=#{date}")
    int deleteHoliday(@Param("tenantId") UUID tenantId, @Param("date") LocalDate date);

    @Select("""
            SELECT work_date AS date,kind,name FROM tna.calendar_effective_day
            WHERE tenant_id=#{tenantId} AND work_date BETWEEN #{from} AND #{through}
            """)
    List<HolidayReflection.Effective> effective(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("through") LocalDate through);

    @Select("""
            SELECT valid_from,valid_to FROM tna.calendar_version
            WHERE tenant_id=#{tenantId} AND scope='PERIOD' AND valid_from<=#{through} AND valid_to>#{from}
            """)
    List<HolidayReflection.Period> periods(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("through") LocalDate through);

    @Select("SELECT COALESCE(max(revision),0)+1 FROM tna.calendar_version WHERE tenant_id=#{tenantId}")
    int nextRevision(@Param("tenantId") UUID tenantId);

    @Insert("""
            INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,scope,reason,created_by)
            VALUES (#{tenantId},#{id},#{revision},#{from},#{to},#{scope},#{reason},#{actorId})
            """)
    int insertVersion(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("revision") int revision,
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("scope") String scope,
            @Param("reason") String reason, @Param("actorId") UUID actorId);

    @Insert("""
            INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis)
            VALUES (#{tenantId},#{versionId},#{date},#{kind},#{name},#{basis})
            """)
    int insertDay(@Param("tenantId") UUID tenantId, @Param("versionId") UUID versionId, @Param("date") LocalDate date,
            @Param("kind") String kind, @Param("name") String name, @Param("basis") String basis);

    @Select("SELECT after_state::text FROM tna.org_audit_event WHERE tenant_id=#{tenantId} AND command_id=#{commandId}")
    String result(@Param("tenantId") UUID tenantId, @Param("commandId") UUID commandId);
}
```

`tna_app`이 `tenant_account`의 `id`·`employee_id`를 읽을 수 있는지는 `AccountDirectoryMapper`가 같은 조인을 쓰므로 그대로 된다. 다르면 이 조인만 `AccountDirectoryMapper`의 방식으로 맞춘다.

- [ ] **Step 4: 서비스 작성**

```java
package kr.tna.clock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** 연도별 휴일 표와 근태 달력 반영. 저장은 명령 한 번(감사 1건, HOLIDAY_SAVE)이다. */
@Service
public class HolidayService {
    static final String ACTION = "HOLIDAY_SAVE";
    static final int MAX_ROWS = 366;
    private static final Set<String> CATEGORIES = Set.of("PUBLIC", "COMPANY", "NON_WORKDAY");
    private static final String CLOSED = " 근태가 마감돼 바꿀 수 없습니다.";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Row(UUID id, LocalDate date, String name, String category, String basis) { }
    public record Applied(Instant at, String by) { }
    public record PageModel(int year, long version, Applied applied, List<Row> rows, List<String> closedMonths, boolean builtIn) { }
    public record Preview(int year, List<KoreanPublicHolidays.Holiday> holidays, String notice) { }
    public record RowInput(String clientRowId, String date, String name, String category, String basis) { }
    public record Form(UUID commandId, String reason, Integer year, Long expectedVersion, List<RowInput> rows) { }
    /** changed: 휴일 행이 바뀐 날짜, applied: 근태 달력에 새 반영분이 생긴 날짜. 감사 after_state에도 이대로 남는다. */
    public record Result(int year, long version, int added, int updated, int deleted, List<LocalDate> changed, List<LocalDate> applied) { }
    public record RowError(String clientRowId, String field, String message) { }
    record Parsed(String clientRowId, LocalDate date, String name, String category, String basis) { }

    public static final class HolidayProblem extends OrgException {
        private final List<RowError> errors;
        HolidayProblem(List<RowError> errors) {
            super(422, "행별 오류를 고친 뒤 다시 저장해 주세요.");
            this.errors = List.copyOf(errors);
        }
        public List<RowError> errors() { return errors; }
    }

    private final OrgCommands commands;
    private final HolidayMapper mapper;
    private final LocalDemoLogin localDemo;
    private final Clock clock;

    public HolidayService(OrgCommands commands, HolidayMapper mapper, LocalDemoLogin localDemo, Clock clock) {
        this.commands = commands;
        this.mapper = mapper;
        this.localDemo = localDemo;
        this.clock = clock;
    }

    public PageModel page(TnaPrincipal actor, Integer requested) {
        int year = requested == null ? commands.today().getYear() : year(requested);
        return commands.read(actor, () -> {
            UUID tenant = actor.tenantId();
            var stored = mapper.year(tenant, year);
            var closed = mapper.closedMonths(tenant, first(year), last(year)).stream()
                    .map(month -> YearMonth.from(month).toString()).toList();
            Applied applied = stored == null || stored.appliedAt() == null ? null : new Applied(stored.appliedAt(), stored.appliedBy());
            return new PageModel(year, stored == null ? 0 : stored.version(), applied,
                    mapper.rows(tenant, first(year), last(year)), closed, !KoreanPublicHolidays.of(year).isEmpty());
        });
    }

    /** 정적 목록이라 DB를 읽지 않는다. 경로 권한은 SecurityConfiguration이 담당자로 제한한다. */
    public Preview publicHolidays(Integer requested) {
        int year = year(requested);
        return new Preview(year, KoreanPublicHolidays.of(year), KoreanPublicHolidays.SMALL_WORKPLACE_NOTICE);
    }

    public Result save(TnaPrincipal actor, Form form) {
        if (localDemo.publicDemo()) throw new OrgException(403, "공개 데모에서는 저장할 수 없습니다.");
        if (form == null || form.commandId() == null) throw OrgException.invalid("명령 식별자가 필요합니다. 화면을 다시 열어 주세요.");
        int year = year(form.year());
        if (form.expectedVersion() == null || form.expectedVersion() < 0) throw OrgException.invalid("현재 버전이 필요합니다. 화면을 다시 열어 주세요.");
        String reason = OrgCommands.requiredText(form.reason(), 500, "저장 사유");
        List<RowInput> rows = form.rows() == null ? List.of() : form.rows();
        if (rows.size() > MAX_ROWS || rows.stream().anyMatch(Objects::isNull)) throw OrgException.invalid("한 해에 366행까지 저장할 수 있습니다.");
        long expected = form.expectedVersion();
        String hash = OrgCommands.fingerprint(ACTION, year, expected, reason, rows.stream()
                .map(row -> String.join("\u001f", String.valueOf(row.clientRowId()), String.valueOf(row.date()),
                        String.valueOf(row.name()), String.valueOf(row.category()), String.valueOf(row.basis())))
                .toList().toString());
        commands.execute(actor, form.commandId(), ACTION, "HOLIDAY_YEAR", hash, reason, () -> apply(actor, year, expected, reason, rows));
        // 재시도(같은 commandId)는 execute가 변경 없이 돌아오고, 처음 감사의 결과를 그대로 읽는다.
        return commands.read(actor, () -> JSON.readValue(mapper.result(actor.tenantId(), form.commandId()), Result.class));
    }

    private OrgCommands.Change apply(TnaPrincipal actor, int year, long expected, String reason, List<RowInput> rows) {
        UUID tenant = actor.tenantId();
        LocalDate from = first(year), through = last(year);
        var stored = mapper.year(tenant, year);
        if ((stored == null ? 0 : stored.version()) != expected) throw OrgException.conflict("최신 내용을 확인한 뒤 다시 저장하세요.");
        Set<YearMonth> closed = mapper.closedMonths(tenant, from, through).stream().map(YearMonth::from).collect(Collectors.toSet());
        var errors = new ArrayList<RowError>();
        Map<LocalDate, Parsed> wanted = check(year, rows, errors);
        var existing = new HashMap<LocalDate, Row>();
        for (var row : mapper.rows(tenant, from, through)) existing.put(row.date(), row);
        var added = new TreeSet<LocalDate>();
        var updated = new TreeSet<LocalDate>();
        var deleted = new TreeSet<LocalDate>();
        for (var row : wanted.values()) {
            var old = existing.get(row.date());
            if (old == null) added.add(row.date());
            else if (!old.name().equals(row.name()) || !old.category().equals(row.category()) || !Objects.equals(old.basis(), row.basis())) {
                updated.add(row.date());
            }
        }
        for (var date : existing.keySet()) if (!wanted.containsKey(date)) deleted.add(date);
        for (var date : added) closedRow(wanted.get(date), closed, errors);
        for (var date : updated) closedRow(wanted.get(date), closed, errors);
        for (var date : deleted) {
            if (closed.contains(YearMonth.from(date))) errors.add(new RowError(null, "date", date + ": " + YearMonth.from(date) + CLOSED));
        }
        if (!errors.isEmpty()) throw new HolidayProblem(errors);

        for (var date : deleted) mapper.deleteHoliday(tenant, date);
        for (var date : updated) {
            var row = wanted.get(date);
            mapper.updateHoliday(tenant, date, row.name(), row.category(), row.basis(), actor.accountId());
        }
        for (var date : added) {
            var row = wanted.get(date);
            mapper.insertHoliday(tenant, UUID.randomUUID(), date, row.name(), row.category(), row.basis(), actor.accountId());
        }

        var holidays = new HashMap<LocalDate, HolidayReflection.Holiday>();
        for (var row : wanted.values()) holidays.put(row.date(), new HolidayReflection.Holiday(row.category(), row.name(), row.basis()));
        var effective = new HashMap<LocalDate, HolidayReflection.Effective>();
        for (var day : mapper.effective(tenant, from, through)) effective.put(day.date(), day);
        var plan = HolidayReflection.plan(year, holidays, effective, mapper.periods(tenant, from, through), closed);
        int revision = mapper.nextRevision(tenant);
        for (var run : plan.periods()) write(actor, revision++, "PERIOD", run, reason);
        for (var day : plan.days()) write(actor, revision++, "DAY", List.of(day), reason);
        mapper.saveYear(tenant, year, clock.instant().truncatedTo(ChronoUnit.MILLIS), actor.accountId());

        var changed = new TreeSet<LocalDate>(added);
        changed.addAll(updated);
        changed.addAll(deleted);
        var result = new Result(year, mapper.year(tenant, year).version(), added.size(), updated.size(), deleted.size(),
                List.copyOf(changed), plan.dates());
        return new OrgCommands.Change(UUID.randomUUID(), null, result);
    }

    private void write(TnaPrincipal actor, int revision, String scope, List<HolidayReflection.Day> days, String reason) {
        UUID id = UUID.randomUUID();
        mapper.insertVersion(actor.tenantId(), id, revision, days.getFirst().date(), days.getLast().date().plusDays(1),
                scope, reason, actor.accountId());
        for (var day : days) mapper.insertDay(actor.tenantId(), id, day.date(), day.kind(), day.name(), day.basis());
    }

    private static void closedRow(Parsed row, Set<YearMonth> closed, List<RowError> errors) {
        var month = YearMonth.from(row.date());
        if (closed.contains(month)) errors.add(new RowError(row.clientRowId(), "date", month + CLOSED));
    }

    /** 행 안의 규칙과 날짜 중복을 모두 모은다. 오류가 없는 날짜만 결과에 넣는다. */
    static Map<LocalDate, Parsed> check(int year, List<RowInput> rows, List<RowError> errors) {
        var ids = new HashSet<String>();
        var counts = new HashMap<LocalDate, Integer>();
        var parsed = new ArrayList<Parsed>();
        for (var row : rows) {
            String id = row.clientRowId();
            if (id == null || id.isBlank() || !ids.add(id)) errors.add(new RowError(id, "clientRowId", "행 식별자가 없거나 겹칩니다."));
            LocalDate date = date(row, year, errors);
            String name = text(row.name(), 120, "휴일명", id, "name", errors, true);
            String category = row.category() == null ? null : row.category().strip();
            if (!CATEGORIES.contains(category)) {
                errors.add(new RowError(id, "category", "구분을 선택해 주세요."));
                category = null;
            }
            String basis = text(row.basis(), 500, "근거", id, "basis", errors, false);
            if (date != null) counts.merge(date, 1, Integer::sum);
            parsed.add(new Parsed(id, date, name, category, basis));
        }
        var result = new LinkedHashMap<LocalDate, Parsed>();
        for (var row : parsed) {
            if (row.date() == null) continue;
            if (counts.get(row.date()) > 1) errors.add(new RowError(row.clientRowId(), "date", "같은 날짜가 두 번 있습니다."));
            else if (row.name() != null && row.category() != null) result.put(row.date(), row);
        }
        return result;
    }

    private static LocalDate date(RowInput row, int year, List<RowError> errors) {
        String raw = row.date() == null ? "" : row.date().strip();
        if (raw.isEmpty()) {
            errors.add(new RowError(row.clientRowId(), "date", "날짜를 입력해 주세요."));
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(raw);
            if (date.getYear() == year) return date;
            errors.add(new RowError(row.clientRowId(), "date", year + "년 날짜만 입력할 수 있습니다."));
        } catch (DateTimeParseException invalid) {
            errors.add(new RowError(row.clientRowId(), "date", "YYYY-MM-DD 형식으로 입력해 주세요."));
        }
        return null;
    }

    private static String text(String raw, int maximum, String label, String id, String field, List<RowError> errors, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) errors.add(new RowError(id, field, label + "을 입력해 주세요."));
            return null;
        }
        try {
            return OrgCommands.requiredText(raw, maximum, label);
        } catch (OrgException invalid) {
            errors.add(new RowError(id, field, invalid.getMessage()));
            return null;
        }
    }

    private static int year(Integer year) {
        if (year == null || year < 2000 || year > 2100) throw OrgException.invalid("연도는 2000~2100 사이로 선택해 주세요.");
        return year;
    }

    private static LocalDate first(int year) { return LocalDate.of(year, 1, 1); }
    private static LocalDate last(int year) { return LocalDate.of(year, 12, 31); }
}
```

- `OrgCommands.execute`는 `OrgException`을 감싸지 않고 그대로 던진다(`OrgCommands.java:65-105`, `DataAccessException`·`JacksonException`만 503으로 바꿈). 그래서 `HolidayProblem`의 422와 409가 그대로 나간다.
- 같은 `commandId`·같은 해시의 재요청은 `execute`가 감사만 확인하고 돌아온다. 이때 `apply`가 돌지 않으므로 버전 확인(409)도 하지 않는다.

- [ ] **Step 5: 통과 확인**

Run: Step 2 명령. Expected: PASS(4개)

- [ ] **Step 6: 커밋**

```powershell
git add src/main/java/kr/tna/clock/HolidayMapper.java src/main/java/kr/tna/clock/HolidayService.java src/test/java/kr/tna/HolidayServiceIT.java
git commit -m "feat: 휴일 저장 한 번으로 휴일 표와 근태 달력 반영(행 오류·마감 월·동시 저장 확인)"
```

---

### Task 7: HTTP 경로, 옛 기간 등록 제거, 권한·공개 데모 차단

**Files:**
- Create: `src/main/java/kr/tna/clock/HolidayController.java`, `src/main/java/kr/tna/clock/HolidayApi.java`
- Modify: `AttendanceController.java`, `AttendanceSettingsService.java`, `AttendanceSettingsMapper.java`, `AttendanceSettingsData.java`(모두 `kr/tna/clock/`), `src/main/java/kr/tna/worktime/SelectiveSettlementReturn.java`, `src/main/java/kr/tna/common/config/SecurityConfiguration.java`, `src/main/java/kr/tna/common/web/ReactPageViewResolver.java:69`
- Test: Create `src/test/java/kr/tna/HolidayWebIT.java`; Modify `PublicDemoSecurityIT.java:193-194`, `AttendanceWebIT.java:22`, `AttendanceSettingsIT.java:96-129`, `EmployeeServiceIT.java:13-14,36,124,136`, `CheckoutOperationsWebIT.java:949-956`, `src/test/java/kr/tna/worktime/SelectiveSettlementReturnTest.java:32,38-40,43`

- [ ] **Step 1: 실패하는 웹 테스트 작성**

클래스 뼈대(`@SpringBootTest`, `@AutoConfigureMockMvc`, 새 고객사, `member`, `authenticated`)는 `EmployeeImportWebIT`와 같다. 정적 import도 같다.

```java
    @Test void pagePreviewSaveAndLegacyAddressFollowTheContract() throws Exception {
        mvc.perform(authenticated(get("/admin/holidays").param("year", "2027"), manager))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.page())
                .andExpect(ReactPageAssertions.data("/holidays/year", 2027))
                .andExpect(ReactPageAssertions.data("/holidays/builtIn", true));
        mvc.perform(authenticated(get("/admin/calendar").param("date", "2027-01-04"), manager))
                .andExpect(status().isSeeOther()).andExpect(header().string("Location", "/admin/holidays"));
        mvc.perform(authenticated(post("/admin/holidays/public-preview"), manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content("{\"year\":2027}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.holidays.length()").value(24))
                .andExpect(jsonPath("$.holidays[0].name").value("1월 1일"))
                .andExpect(jsonPath("$.notice").value(kr.tna.clock.KoreanPublicHolidays.SMALL_WORKPLACE_NOTICE));

        String bad = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"reason\":\"휴일 정리\",\"year\":2027,\"expectedVersion\":0,"
                + "\"rows\":[{\"clientRowId\":\"a\",\"date\":\"2027-01-01\",\"name\":\"\",\"category\":\"PUBLIC\",\"basis\":null}]}";
        mvc.perform(authenticated(post("/admin/holidays/save"), manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].clientRowId").value("a")).andExpect(jsonPath("$.errors[0].field").value("name"));
        mvc.perform(authenticated(post("/admin/holidays/save"), manager).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isForbidden());
        String good = bad.replace("\"name\":\"\"", "\"name\":\"1월 1일\"").replaceFirst("\"commandId\":\"[^\"]+\"", "\"commandId\":\"" + UUID.randomUUID() + "\"");
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(authenticated(post("/admin/holidays/save"), manager).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(good))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.applied.length()").value(365));
        }
        mvc.perform(authenticated(post("/admin/calendar/versions"), manager).with(csrf())).andExpect(status().isForbidden());
        var plain = member("PLAIN", java.util.Set.of("EMPLOYEE"));
        mvc.perform(authenticated(get("/admin/holidays"), plain)).andExpect(status().isForbidden());
        mvc.perform(authenticated(post("/admin/holidays/public-preview"), plain).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"year\":2027}")).andExpect(status().isForbidden());
    }
```

`PublicDemoSecurityIT.java:193-194`의 반복 목록에 `"/admin/holidays/save"`를 추가한다.

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=HolidayWebIT,PublicDemoSecurityIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: FAIL(`/admin/holidays` 403 또는 404)

- [ ] **Step 3: 화면 컨트롤러**

```java
package kr.tna.clock;

import java.net.URI;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

@Controller
class HolidayController {
    private final HolidayService holidays;

    HolidayController(HolidayService holidays) { this.holidays = holidays; }

    @GetMapping("/admin/holidays")
    ModelAndView page(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(required = false) Integer year) {
        return new ModelAndView("clock/holidays").addObject("holidays", holidays.page(actor, year));
    }

    /** 옛 '근무일·휴일' 주소. 쿼리(date, settlementReturn)는 쓰지 않는다. */
    @GetMapping("/admin/calendar")
    ResponseEntity<Void> calendar() {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/admin/holidays")).build();
    }
}
```

- [ ] **Step 4: JSON 컨트롤러**

```java
package kr.tna.clock;

import java.util.Map;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/admin/holidays", produces = MediaType.APPLICATION_JSON_VALUE)
class HolidayApi {
    record PreviewForm(Integer year) { }

    private final HolidayService holidays;

    HolidayApi(HolidayService holidays) { this.holidays = holidays; }

    @PostMapping(value = "/public-preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<HolidayService.Preview> preview(@RequestBody PreviewForm form) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(holidays.publicHolidays(form.year()));
    }

    @PostMapping(value = "/save", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<HolidayService.Result> save(@AuthenticationPrincipal TnaPrincipal actor, @RequestBody HolidayService.Form form) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(holidays.save(actor, form));
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<Map<String, Object>> rejected(OrgException error) {
        Map<String, Object> body = error instanceof HolidayService.HolidayProblem problem
                ? Map.of("message", error.getMessage(), "errors", problem.errors()) : Map.of("message", error.getMessage());
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                .body(Map.of("message", "일시적으로 처리할 수 없습니다. 잠시 후 다시 확인해 주세요."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable() {
        return ResponseEntity.badRequest().body(Map.of("message", "요청 형식을 확인해 주세요."));
    }
}
```

- [ ] **Step 5: 권한·화면 등록**

- `SecurityConfiguration.java:85` 담당자 경로 목록의 `"/admin/calendar", "/admin/calendar/**"`를 `"/admin/calendar", "/admin/holidays", "/admin/holidays/**"`로 바꾼다. `/admin/calendar/versions`는 어느 규칙에도 맞지 않아 마지막 `anyRequest().denyAll()`로 403이 된다.
- 같은 파일 `:48` 공개 데모 POST 차단 목록에 `"/admin/holidays/save"`를 더한다(서비스의 403과 이중 차단).
- `ReactPageViewResolver.java:69` `page("clock/calendar", "calendars", "form", "settlementReturn", "settlementWorkDate"),` → `page("clock/holidays", "holidays"),`

- [ ] **Step 6: 옛 기간 등록 코드 제거**

| 파일 | 지울 것 |
| --- | --- |
| `AttendanceController.java:43-59, 103-108` | `calendar(...)`(`GET /admin/calendar`), `calendarVersion(...)`(`POST /admin/calendar/versions`), `calendarPage(...)`. `settlements` 필드는 `attendance`·`confirm`이 계속 쓴다. |
| `AttendanceSettingsService.java` | `KINDS`, `calendars(...)`, `calendar(...)`, `exceptions(...)`, `record CalendarException`, 그로 인해 쓰지 않게 된 import(`LocalDate`, `HashMap`, `Map`, `Set`) |
| `AttendanceSettingsMapper.java:27-41` | `calendars`, `nextRevision`, `calendar`, `day`, 그리고 쓰지 않게 된 `extends ClosedAttendanceMonths`·`LocalDate` import |
| `AttendanceSettingsData.java:12-15` | `CalendarVersion`, `CalendarForm`, 쓰지 않게 된 `LocalDate`·`DateTimeFormat` import |
| `SelectiveSettlementReturn.java:35-37` | `calendar(...)` |

- [ ] **Step 7: 옛 동작을 검사하던 테스트 정리**

- `AttendanceSettingsIT.java:96-125, 129`: `reviewedCalendarMaterializesEachDateAndKeepsExceptionsWithBasis`, `calendarRequiresReviewedBoundedPeriodAndExplicitBasis`, `calendarExceptionsRejectDuplicateOutOfPeriodInvalidKindsAndMissingEvidence`, `calendarRangesAreImmutableAndAdjacentVersionsAreAllowed`와 `calendar(...)` 도우미를 지운다. 같은 규칙(구분·근거·중복·마감·재시도)은 `HolidayServiceIT`가 검사한다.
- `EmployeeServiceIT.java:124, 136`: 기간 등록의 마감 월 409·재개 후 등록 두 줄과 `attendanceSettings` 필드(`:36`), import(`:13-14`)를 지운다. 휴일 저장의 마감 월 거부는 `HolidayServiceIT.closedMonthsKeepTheirHolidaysAndCalendar`가 맡는다.
- `CheckoutOperationsWebIT.java:949-956`: `/admin/calendar`의 `settlementReturn` 왕복과 `/admin/calendar/versions` 422 두 요청을 지운다. 남은 `/admin/calculations` 왕복은 그대로 둔다. `attendanceSettings` 필드·import가 남지 않게 한다.
- `AttendanceWebIT.java:22`:

```java
    @Test void administratorCanOpenNetworkAndHolidaySettings() throws Exception {var s=login(true);for(String p:java.util.List.of("/admin/networks","/admin/holidays"))mvc.perform(get(p).session(s)).andExpect(status().isOk()).andExpect(ReactPageAssertions.csrf());
        mvc.perform(get("/admin/calendar").session(s)).andExpect(status().isSeeOther()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location","/admin/holidays"));}
```

- `SelectiveSettlementReturnTest.java`: `:32`, `:38`, `:39-40`(`staff` 지역 변수와 그 단언), `:43`의 `returns.calendar(...)` 줄을 지운다.

- [ ] **Step 8: 통과 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=HolidayWebIT,PublicDemoSecurityIT,AttendanceWebIT,AttendanceSettingsIT,EmployeeServiceIT,CheckoutOperationsWebIT" "-Dtest=SelectiveSettlementReturnTest,AttendanceHistoryTest,AttendanceNavigationTest" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: PASS. 컴파일 오류가 나면 `rg -n "CalendarForm|settings\.calendar|settlements\.calendar|clock/calendar" src`로 남은 참조를 찾는다.

- [ ] **Step 9: 커밋**

```powershell
git add src/main/java/kr/tna src/test/java/kr/tna
git commit -m "feat: 휴일 관리 화면·내장 공휴일·저장 경로를 열고 기간 등록 폼과 경로를 제거"
```

---

### Task 8: 휴일 관리 화면

**Files:**
- Create: `frontend/src/pages/HolidayPage.tsx`, `frontend/src/pages/HolidayPage.test.tsx`
- Modify: `frontend/src/pages/EmployeeImportPage.tsx:20`(`postJson` export), `frontend/src/App.tsx:100, 299`, `frontend/src/navigation.ts:69`, `frontend/src/pages/AdminPages.tsx`, `frontend/src/pages/AdminTypes.ts:208-220`, `frontend/src/pages/selectiveNavigation.ts:46`, `frontend/src/pages/CompanySetupGuide.tsx:18-19`
- Test(수정): `frontend/src/navigation.test.ts:21`, `frontend/src/App.test.tsx:173`, `frontend/src/pages/AdminPages.test.tsx:432-449`, `frontend/src/pages/SelectiveSettlements.test.tsx:203-204`

- [ ] **Step 1: 실패하는 화면 테스트 작성**

AG Grid 셀은 jsdom에서 그려지지 않을 수 있으므로 셀 내용 대신 건수(`.wl-count`), 상태 문구, 요청 본문을 본다(`EmployeeImportPage.test.tsx`와 같은 방식).

```tsx
// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { HolidayPage } from './HolidayPage';
import type { HolidayModel } from './HolidayPage';

const csrf = { token: 't', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const JSON_HEADERS = { 'Content-Type': 'application/json' };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
const base: HolidayModel = {
  year: 2027, version: 0, applied: null, closedMonths: [], builtIn: true,
  rows: [{ id: 'h1', date: '2027-03-02', name: '창립기념일', category: 'COMPANY', basis: null }],
};

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('HolidayPage', () => {
  it('moves between years and shows whether the year reached attendance', () => {
    const { container } = render(<HolidayPage model={base} csrf={csrf} />);
    expect(screen.getByText('아직 근태 미반영')).toBeTruthy();
    expect(screen.getByRole('link', { name: '이전 연도' }).getAttribute('href')).toBe('/admin/holidays?year=2026');
    expect(screen.getByRole('link', { name: '다음 연도' }).getAttribute('href')).toBe('/admin/holidays?year=2028');
    expect(container.querySelector('.wl-count')?.textContent).toBe('총 1건');
    cleanup();
    render(<HolidayPage model={{ ...base, version: 1, applied: { at: '2026-11-24T01:05:00Z', by: '합성 담당자' } }} csrf={csrf} />);
    expect(screen.getByText('근태 반영됨 · 마지막 반영 11-24 10:05 · 합성 담당자')).toBeTruthy();
  });

  it('adds only built-in dates that the sheet does not have yet', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ year: 2027, notice: '상시 4인 이하 사업장은 노동절 외 공휴일의 유급휴일 의무가 없습니다.', holidays: [
      { date: '2027-01-01', name: '1월 1일', basis: '공휴일 · 월력요항' }, { date: '2027-03-02', name: '중복', basis: '공휴일' }] }));
    const { container } = render(<HolidayPage model={base} csrf={csrf} />);
    fireEvent.click(screen.getByRole('button', { name: '2027년 공휴일 불러오기' }));
    await waitFor(() => expect(container.querySelector('.wl-count')?.textContent).toBe('총 2건'));
    expect(fetchMock).toHaveBeenCalledWith('/admin/holidays/public-preview', expect.objectContaining({ body: JSON.stringify({ year: 2027 }) }));
    expect(screen.getByText(/1일을 불러왔습니다/).textContent).toContain('상시 4인 이하');
    expect(container.querySelector('[data-unsaved-changes]')?.getAttribute('data-unsaved-changes')).toBe('true');
  });

  it('tells the manager to add dates when the year is not built in', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ year: 2030, notice: '', holidays: [] }));
    render(<HolidayPage model={{ ...base, year: 2030, rows: [], builtIn: false }} csrf={csrf} />);
    fireEvent.click(screen.getByRole('button', { name: '2030년 공휴일 불러오기' }));
    expect(await screen.findByText('2030년 공휴일은 아직 내장되지 않았습니다. 날짜를 직접 추가하세요.')).toBeTruthy();
  });

  it('lists every rejected row and saves again with a new command', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json({ message: '행별 오류를 고친 뒤 다시 저장해 주세요.', errors: [
        { clientRowId: 'h1', field: 'name', message: '휴일명을 입력해 주세요.' },
        { clientRowId: null, field: 'date', message: '2027-01-04: 2027-01 근태가 마감돼 바꿀 수 없습니다.' }] }, 422))
      .mockResolvedValueOnce(json({ year: 2027, version: 1, added: 1, updated: 0, deleted: 0, changed: ['2027-03-02'], applied: Array(365).fill('2027-01-01') }));
    render(<HolidayPage model={base} csrf={csrf} />);
    fireEvent.change(screen.getByLabelText('저장 사유'), { target: { value: '2027년 휴일 정리' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(await screen.findByText('2027-03-02: 휴일명을 입력해 주세요.')).toBeTruthy();
    expect(screen.getByText('2027-01-04: 2027-01 근태가 마감돼 바꿀 수 없습니다.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(await screen.findByText('저장했습니다. 추가 1 · 수정 0 · 삭제 0 · 근태 반영 365일')).toBeTruthy();
    const bodies = fetchMock.mock.calls.map(call => JSON.parse(String((call[1] as RequestInit).body)));
    expect(bodies[0].commandId).not.toBe(bodies[1].commandId);
    expect(bodies[0]).toMatchObject({ year: 2027, expectedVersion: 0, reason: '2027년 휴일 정리',
      rows: [{ clientRowId: 'h1', date: '2027-03-02', name: '창립기념일', category: 'COMPANY', basis: '' }] });
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- --run src/pages/HolidayPage.test.tsx`
Expected: FAIL(모듈 없음)

- [ ] **Step 3: `postJson` 재사용**

`EmployeeImportPage.tsx:20` `async function postJson<T>(…)` 앞에 `export`만 붙인다. 동작은 바꾸지 않는다(401·403·리다이렉트는 `lost`, 5xx·JSON 아님은 예외).

- [ ] **Step 4: 화면 구현**

```tsx
import { useMemo, useState } from 'react';
import type { ColDef } from 'ag-grid-community';
import { PageHeader } from '../components/PageHeader';
import { WorklogSheet, sheetColumn, sheetEditorColumn } from '../components/WorklogSheet';
import type { SheetFieldError } from '../components/WorklogSheet';
import type { CsrfToken } from '../types';
import { postJson } from './EmployeeImportPage';

type Category = 'PUBLIC' | 'COMPANY' | 'NON_WORKDAY';
export interface HolidayRow { id: string; date: string; name: string; category: Category; basis: string | null }
export interface HolidayModel {
  year: number; version: number; applied: { at: string; by: string | null } | null;
  rows: HolidayRow[]; closedMonths: string[]; builtIn: boolean;
}
interface Draft { clientRowId: string; date: string; name: string; category: string; basis: string; saved?: string }
interface RowError { clientRowId: string | null; field: string; message: string }
interface Result { year: number; version: number; added: number; updated: number; deleted: number; changed: string[]; applied: string[] }
interface Preview { year: number; holidays: { date: string; name: string; basis: string }[]; notice: string }
type Field = 'date' | 'name' | 'category' | 'basis';

const CATEGORIES = [['PUBLIC', '공휴일'], ['COMPANY', '회사 휴일'], ['NON_WORKDAY', '비근로일']] as const;
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];
const content = (row: Draft) => JSON.stringify([row.date, row.name, row.category, row.basis]);
const snapshot = (rows: Draft[]) => JSON.stringify(rows.map(content).sort());
const fromServer = (row: HolidayRow): Draft => {
  const draft = { clientRowId: row.id, date: row.date, name: row.name, category: row.category, basis: row.basis ?? '' };
  return { ...draft, saved: content(draft) };
};
const rowId = (row: Draft) => row.clientRowId;

function weekday(date: string) {
  const time = Date.parse(`${date}T00:00:00Z`);
  return /^\d{4}-\d{2}-\d{2}$/.test(date) && Number.isFinite(time) ? WEEKDAYS[new Date(time).getUTCDay()] : '';
}

function seoulTime(at: string) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date(at)).map(part => [part.type, part.value]));
  return `${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`;
}

export function HolidayPage({ model, csrf }: { model: HolidayModel; csrf: CsrfToken }) {
  const year = model.year;
  const lockedIds = useMemo(() => new Set(model.rows.filter(row => model.closedMonths.includes(row.date.slice(0, 7))).map(row => row.id)), [model]);
  const [drafts, setDrafts] = useState<Draft[]>(() => model.rows.map(fromServer));
  const [baseline, setBaseline] = useState(() => snapshot(model.rows.map(fromServer)));
  const [version, setVersion] = useState(model.version);
  const [applied, setApplied] = useState(model.applied);
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const [reason, setReason] = useState('');
  const [commandId, setCommandId] = useState(() => crypto.randomUUID());
  const [errors, setErrors] = useState<RowError[]>([]);
  const [notice, setNotice] = useState('');
  const [message, setMessage] = useState('');
  const [result, setResult] = useState<Result | null>(null);
  const [busy, setBusy] = useState(false);
  const [accessLost, setAccessLost] = useState(false);
  const dirty = snapshot(drafts) !== baseline;

  // 내용이 바뀌면 새 명령으로 저장한다(서버 입력 해시가 행 내용을 포함).
  function edit(next: (rows: Draft[]) => Draft[]) {
    setDrafts(next); setCommandId(crypto.randomUUID()); setResult(null);
  }
  const update = (id: string, field: Field, value: string) => edit(rows => rows.map(row => row.clientRowId === id ? { ...row, [field]: value } : row));

  const columns = useMemo<ColDef<Draft>[]>(() => {
    const editor = (field: Field, headerName: string, minWidth: number, extra: { type?: 'date'; required?: boolean; maxLength?: number; options?: typeof CATEGORIES }) =>
      sheetEditorColumn<Draft>({ field, headerName, minWidth }, (row, index) => ({
        label: `${headerName} ${index + 1}`, value: row[field], readOnly: lockedIds.has(row.clientRowId),
        onChange: value => update(row.clientRowId, field, value), ...extra,
      }));
    return [
      editor('date', '날짜', 130, { type: 'date', required: true }),
      sheetColumn<Draft>({ colId: 'weekday', headerName: '요일', minWidth: 60, maxWidth: 70 }, row => weekday(row.date)),
      editor('name', '휴일명', 160, { required: true, maxLength: 120 }),
      editor('category', '구분', 110, { options: CATEGORIES }),
      editor('basis', '근거', 220, { maxLength: 500 }),
      sheetColumn<Draft>({ colId: 'status', headerName: '상태', minWidth: 130 }, row =>
        lockedIds.has(row.clientRowId) ? '마감 월(변경 불가)' : row.saved === content(row) ? '반영됨' : '저장 전 변경'),
    ];
  }, [lockedIds]);

  const sheetErrors = useMemo<SheetFieldError[]>(() => errors.flatMap(error => error.clientRowId
    ? [{ clientRowId: error.clientRowId, field: error.field, message: error.message }] : []), [errors]);
  const errorLabel = (error: RowError) => {
    if (!error.clientRowId) return error.message;
    const date = drafts.find(row => row.clientRowId === error.clientRowId)?.date;
    return `${date || '날짜 없음'}: ${error.message}`;
  };

  async function importPublic() {
    setBusy(true); setMessage(''); setNotice('');
    try {
      const { status, data, lost } = await postJson<Preview & { message?: string }>('/admin/holidays/public-preview', csrf, { year });
      if (lost) { setAccessLost(true); return; }
      if (status !== 200) { setMessage(data.message ?? '공휴일을 불러오지 못했습니다.'); return; }
      if (data.holidays.length === 0) { setMessage(`${year}년 공휴일은 아직 내장되지 않았습니다. 날짜를 직접 추가하세요.`); return; }
      const have = new Set(drafts.map(row => row.date));
      const added = data.holidays.filter(holiday => !have.has(holiday.date)).map(holiday => ({
        clientRowId: crypto.randomUUID(), date: holiday.date, name: holiday.name, category: 'PUBLIC', basis: holiday.basis,
      }));
      edit(rows => [...rows, ...added].sort((a, b) => a.date.localeCompare(b.date)));
      setNotice(`${added.length}일을 불러왔습니다. 저장해야 근태에 반영됩니다. ${data.notice}`);
    } catch { setMessage('공휴일을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'); }
    finally { setBusy(false); }
  }

  async function save() {
    setBusy(true); setMessage(''); setErrors([]);
    try {
      const rows = drafts.map(({ clientRowId, date, name, category, basis }) => ({ clientRowId, date, name, category, basis }));
      const { status, data, lost } = await postJson<Result & { message?: string; errors?: RowError[] }>('/admin/holidays/save', csrf,
        { commandId, reason, year, expectedVersion: version, rows });
      if (lost) { setAccessLost(true); return; }
      if (status === 200) {
        const saved = drafts.map(row => ({ ...row, saved: content(row) }));
        setDrafts(saved); setBaseline(snapshot(saved)); setVersion(data.version); setResult(data);
        setApplied({ at: new Date().toISOString(), by: null }); setNotice(''); setCommandId(crypto.randomUUID());
        return;
      }
      setCommandId(crypto.randomUUID());
      if (status === 422 && Array.isArray(data.errors)) { setErrors(data.errors); return; }
      setMessage(data.message ?? '저장하지 못했습니다.');
    } catch {
      // 응답을 받지 못했으면 같은 명령 식별자로 다시 저장해 중복 반영을 막는다.
      setMessage('저장 결과를 확인하지 못했습니다. 같은 내용으로 다시 저장하면 한 번만 반영됩니다.');
    } finally { setBusy(false); }
  }

  if (accessLost) return <section className="w-page"><p role="alert">로그인 상태와 권한을 다시 확인하세요.</p>
    <a href="/login" className="t-btn">다시 로그인</a></section>;
  return (
    <section className="w-page" data-unsaved-changes={String(dirty)} data-modal-busy={String(busy)}>
      <PageHeader title="휴일 관리" help="연도별 휴일을 관리합니다. 저장하면 바뀐 날짜만 근태에 반영되고, 그 날짜의 근무 확인과 일 결과는 다시 확인·재계산이 필요해집니다. 마감 월은 바꿀 수 없습니다."
        actions={<>
          <a className="t-btn" href={`/admin/holidays?year=${year - 1}`}>이전 연도</a>
          <strong>{`${year}년`}</strong>
          <a className="t-btn" href={`/admin/holidays?year=${year + 1}`}>다음 연도</a>
        </>} />
      <p role="status">{applied
        ? `근태 반영됨 · 마지막 반영 ${seoulTime(applied.at)}${applied.by ? ` · ${applied.by}` : ''}`
        : '아직 근태 미반영'}</p>
      {model.closedMonths.length > 0 && <p className="t-muted">{`마감 월: ${model.closedMonths.join(', ')}`}</p>}
      <WorklogSheet<Draft> label={`${year}년 휴일`} count={drafts.length} rows={drafts} columns={columns} getRowId={rowId}
        errors={sheetErrors} scope="all" emptyMessage="등록한 휴일이 없습니다."
        selectedIds={selected} onSelectionChange={setSelected} isRowSelectable={row => !lockedIds.has(row.clientRowId)}
        actions={<>
          <button type="button" className="t-btn" disabled={busy}
            onClick={() => edit(rows => [...rows, { clientRowId: crypto.randomUUID(), date: '', name: '', category: 'COMPANY', basis: '' }])}>행 추가</button>
          <button type="button" className="t-btn" disabled={busy || selected.size === 0}
            onClick={() => { edit(rows => rows.filter(row => !selected.has(row.clientRowId))); setSelected(new Set()); }}>선택 삭제</button>
          <button type="button" className="t-btn" disabled={busy} onClick={importPublic}>{`${year}년 공휴일 불러오기`}</button>
        </>} />
      {notice && <p role="status">{notice}</p>}
      {errors.length > 0 && (
        <div className="notice" role="alert">
          <p>{`저장하지 못했습니다. 오류 ${errors.length}건`}</p>
          <ul>{errors.map((error, index) => <li key={index}>{errorLabel(error)}</li>)}</ul>
        </div>
      )}
      <div className="wl-save-reason">
        <label htmlFor="holiday-reason" className="wl-required">저장 사유</label>
        <textarea id="holiday-reason" value={reason} maxLength={500} onChange={event => setReason(event.target.value)} />
      </div>
      <div className="wl-form-actions" aria-busy={busy}>
        <button type="button" className="t-btn primary" onClick={save}
          disabled={busy || reason.trim() === '' || (!dirty && version > 0)}>저장</button>
      </div>
      {message && <p className="notice" role="alert">{message}</p>}
      {result && <p role="status">{`저장했습니다. 추가 ${result.added} · 수정 ${result.updated} · 삭제 ${result.deleted} · 근태 반영 ${result.applied.length}일`}</p>}
    </section>
  );
}
```

- 처음 저장하는 연도(`version` 0)는 휴일 행이 없어도 저장할 수 있다. 그 해 빈 날짜를 근태 달력으로 채우는 용도다.
- 저장 직후 반영 정보의 담당자 이름은 화면을 다시 열 때 보인다(응답에 이름을 넣지 않음, 감사 크기 유지).
- `sheetEditorColumn`의 `options` 형식이 `readonly (readonly [string, string])[]`이므로 `CATEGORIES`를 그대로 넘긴다. 타입 오류가 나면 `editor`의 `extra` 타입을 `Partial<SheetEditorField>`(`components/SheetCellEditor`)로 바꾼다.

- [ ] **Step 5: 화면 연결과 옛 달력 화면 제거**

- `App.tsx`
  - `titles`의 `'clock/calendar': '근무일·휴일',` → `'clock/holidays': '휴일 관리',`
  - import: `import { HolidayPage } from './pages/HolidayPage';`, `import type { HolidayModel } from './pages/HolidayPage';`
  - `Content`의 `org/employee-import` 줄 다음: `{props.page === 'clock/holidays' && <HolidayPage model={props.data.holidays as HolidayModel} csrf={props.csrf} />}`
- `navigation.ts:69` → `item('holidays', '휴일 관리', '/admin/holidays', admin, 'clock/holidays'),`
- `AdminPages.tsx`
  - `CalendarPage` 함수(`:1134-1185`)와 `case 'clock/calendar':` 두 줄(`:1230-1231`), `CalendarModel` import(`:23`)를 지운다. 이로써 쓰지 않게 된 import(`SettlementBackLink` 등)는 빌드 경고를 보고 지운다.
  - `NetworksPage`의 `<a className="t-btn" href="/admin/calendar">휴일 캘린더</a>`(`:1089`) → `<a className="t-btn" href="/admin/holidays">휴일 관리</a>`
- `AdminTypes.ts:208-220` `CalendarModel`을 지운다.
- `selectiveNavigation.ts:46` → `if (action === 'CALENDAR') return link('휴일 관리 확인', '/admin/holidays', { year: date.slice(0, 4) });`(`settlementReturn`은 붙지만 휴일 화면은 쓰지 않는다. 모든 링크에 같은 값이 붙는다는 기존 테스트 조건을 유지한다.)
- `CompanySetupGuide.tsx:18-19`의 둘째 줄 → `{' '}<a href="/admin/holidays">휴일 관리</a>에서 올해 공휴일을 불러오고 회사 휴일을 더해 저장합니다. 저장하면 그 해 근태 달력이 채워집니다.</li>`
- 테스트
  - `navigation.test.ts:21` `'/admin/calendar'` → `'/admin/holidays'`(항목 수 38은 그대로)
  - `App.test.tsx:173` `'/admin/calendar'` → `'/admin/holidays'`
  - `AdminPages.test.tsx:432-449` '휴일 캘린더는 명시 검토와…' 테스트를 지운다.
  - `SelectiveSettlements.test.tsx:203-204` →
    ```tsx
    expect(href(1, '휴일 관리 확인').pathname).toBe('/admin/holidays');
    expect(href(1, '휴일 관리 확인').searchParams.get('year')).toBe('2026');
    ```

- [ ] **Step 6: 통과 확인**

Run: `npm --prefix frontend test -- --run` 그리고 `npm --prefix frontend run build`
Expected: PASS, 빌드 성공

- [ ] **Step 7: 커밋**

```powershell
git add frontend/src
git commit -m "feat: 휴일 관리 화면(연도 이동·시트 편집·공휴일 불러오기·저장)과 메뉴 교체"
```

---

### Task 9: 문서와 공개 데모 시드

**Files:**
- Modify: `docs/design/attendance-recording.md:7, 23`, `docs/development/local.md:5`(+새 절), `docs/superpowers/specs/2026-11-23-opening-prep-design.md`(§1.4·§2.3 끝), `docs/superpowers/specs/2026-11-24-holiday-management-design.md`(끝), `deploy/worklog/sample-data.sql:121-127`, `deploy/worklog/verify-sample-data.sql:56-57`

- [ ] **Step 1: 설계·개발 문서 반영**

`attendance-recording.md:7`을 다음으로 바꾼다.

"- 휴일은 연도별 휴일 표(`tna.holiday`: 날짜·휴일명·구분 PUBLIC/COMPANY/NON_WORKDAY·근거)로 관리한다(`/admin/holidays`). 저장하면 같은 명령 안에서 근태 달력(`calendar_version`+`calendar_day`)에 반영한다. 근태 달력이 없는 구간은 기간(PERIOD) 버전, 이미 있는 날 가운데 종류나 휴일명이 달라진 날은 날짜(DAY) 반영분을 만든다. 휴일 행이 없는 날은 근로일이며, 주말의 기존 비근로일은 근로일과 같게 본다. 기간끼리는 겹칠 수 없고, 한 날짜에는 revision이 가장 큰 버전을 쓴다(뷰 `tna.calendar_effective_day`). 마감 월은 반영하지 않는다. 근태 달력 행은 바꾸거나 지우지 않는다. 미등록 날짜는 계속 미확인이다."

`:23`의 `/admin/calendar`를 "`/admin/holidays`(옛 `/admin/calendar`는 303으로 이동)"로 바꾼다.

`local.md:5`의 "조직·근무시간코드·근무조·근무일·허용망"을 "조직·근무시간코드·근무조·휴일 관리·허용망"으로 바꾸고, '새 회사에서 첫 출근을 검증하는 방법' 절 끝에 다음 절을 더한다.

```markdown
### 휴일 관리와 공휴일 연례 추가

- 담당자: 근태 운영 > 근무 기준 > **휴일 관리**에서 연도를 고르고 'YYYY년 공휴일 불러오기' → 회사 휴일 추가 → 저장 사유 → 저장. 처음 저장하면 그 해의 빈 근태 달력이 채워지고, 이후 저장은 바뀐 날짜만 반영돼 그 날의 근무 확인·일 결과가 '재계산 필요'가 된다. 마감 월은 바꿀 수 없다.
- 개발자(매년 6월 말 월력요항 관보 공고 뒤): `KoreanPublicHolidays`에 다음 해 목록을 더한다 → `KoreanPublicHolidaysTest`에 공고 표 그대로 날짜·이름을 적는다 → 근거 문자열에 공고 번호를 넣는다 → 그 사이 개정된 「관공서의 공휴일에 관한 규정」·임시공휴일을 확인한다 → 단위 테스트를 돌린다.
- 연중에 임시공휴일이 지정되면 코드를 바꾸지 않고 담당자가 휴일 관리에서 '공휴일'로 추가한다.
```

`2026-11-23-opening-prep-design.md` §1.4(`:85` 절 끝)와 §2.3(`:168` 절 끝)에 각각 한 줄:
"> 2026-11-24: 이 절은 [휴일 관리 설계](2026-11-24-holiday-management-design.md)로 대체됐다. §2.4 내장 공휴일 표는 그대로 쓴다."

`2026-11-24-holiday-management-design.md` 끝에 '2026-11-24 구현 반영' 단락을 더하고, 이 계획 '자체 점검 결과'의 '설계와 다르게 정한 점'을 한 줄씩 옮긴다.

- [ ] **Step 2: 공개 데모 시드**

`V41`은 기존 달력이 있는 연도마다 `holiday_year`를 만든다. 이미 운영 중인 데모 DB(업그레이드)와 새로 준비하는 데모 DB의 행 수를 맞추려고 시드에도 같은 행을 넣는다. 데모 달력에는 평일 비근로일·휴일이 없으므로 `tna.holiday`는 0행이다.

`sample-data.sql`의 `calendar_day` INSERT(`:123-127`) 바로 뒤:

```sql
    INSERT INTO tna.holiday_year (tenant_id, year, version, applied_at, applied_by)
    VALUES (tenant, extract(year FROM year_from)::integer, 1, CURRENT_TIMESTAMP, admin_account);
```

`verify-sample-data.sql:56-57` 기대값 목록에 `('holiday_year', 1),`를 더한다(`holiday`는 목록에 없으므로 0을 기대).

- [ ] **Step 3: 확인**

Run: `npm --prefix frontend test -- --run` (안내 문구 단언이 있으면 함께 고친다)
`sample-data.sql`은 `worklog_demo` 전용 가드가 있어 로컬 테스트로 실행하지 않는다. 문법만 눈으로 확인하고 결과 문서에 '미실행(데모 DB 전용)'으로 적는다.

- [ ] **Step 4: 커밋**

```powershell
git add docs/design/attendance-recording.md docs/development/local.md docs/superpowers/specs/2026-11-23-opening-prep-design.md docs/superpowers/specs/2026-11-24-holiday-management-design.md deploy/worklog/sample-data.sql deploy/worklog/verify-sample-data.sql
git commit -m "docs: 휴일 관리 반영 규칙·공휴일 연례 추가 절차와 데모 시드 연도 버전"
```

---

### Task 10: 전체 회귀, 브라우저 확인, 결과 기록

**Files:**
- Modify: `scripts/verify-customer-opening-browser.cjs:396-400`
- Create: `docs/quality/holiday-management-20261124.md`

- [ ] **Step 1: 개통 브라우저 시나리오 갱신**

`'근무일 검토와 출퇴근 허용망 저장'` 단계(`:396-400`)의 달력 다섯 줄을 다음으로 바꾸고, 단계 이름을 `'휴일 관리 저장과 출퇴근 허용망 저장'`으로 고친다. 내장 목록이 없는 연도에 실행해도 처음 저장(빈 표)은 가능하므로 불러오기 결과는 단언하지 않는다.

```js
      await setupLink('/admin/holidays');
      await click(adminPage, 'button', date.slice(0, 4) + '년 공휴일 불러오기');
      await fill(adminPage, '#holiday-reason', why);
      await click(adminPage, '.wl-form-actions button', '저장');
      await visible(adminPage, '[role=status]', '근태 반영');
```

Run: `node --check scripts/verify-customer-opening-browser.cjs`

- [ ] **Step 2: 전체 회귀**

Run: `.\mvnw.cmd verify`
Expected: 이번 변경 관련 실패 0. 달력을 시드하는 기존 테스트(약 30개 파일)가 모두 통과해야 한다. 환경 때문에 실행되지 않는 테스트(고정 이미지 빌드, `HolidayMigrationIT`의 DB 생성 권한, 날짜 의존 `LeaveWorkflowIT`)는 결과 문서에 이름과 원인을 적는다.

Run: `npm --prefix frontend test -- --run`, `npm --prefix frontend run build`
Expected: PASS

- [ ] **Step 3: 브라우저 확인(PC 1366px, 모바일 390px)**

로컬 앱을 테스트 DB에 붙여 다음을 확인하고 화면을 캡처한다.
- `/admin/calendar` 북마크 → `/admin/holidays`로 이동
- 2026년: 공휴일 불러오기(22일, 4인 이하 안내) → 회사 휴일 1행 추가 → 사유 → 저장 → 결과 건수·'근태 반영됨'
- 같은 화면에서 이름만 고쳐 저장 → 근태 반영 1일, 다른 날짜의 일 결과는 그대로
- 행 오류(빈 휴일명, 다른 해 날짜) 표시와 저장 거부
- 마감 월 행이 '마감 월(변경 불가)'로 잠기고 선택 삭제가 되지 않음
- 직원 홈·근무 일정에서 그 날짜가 '확인된 휴일', 휴일 출근 기록 시 월 근로시간에 휴일근로 검토(`HOLIDAY_WORK_REVIEW`) 표시
- 2028년으로 이동 → 불러오기 시 '아직 내장되지 않았습니다' 안내
- 선택근무 정산의 '휴일 관리 확인' 링크가 해당 연도로 연다
- 모바일에서 시트 가로 스크롤과 저장 버튼 접근

- [ ] **Step 4: 결과 기록과 커밋**

`docs/quality/holiday-management-20261124.md`에 실행 명령, 테스트 수, 화면 확인 항목, 실행하지 못한 검증을 실제 결과로만 적는다.

```powershell
git add scripts/verify-customer-opening-browser.cjs docs/quality/holiday-management-20261124.md
git commit -m "test: 휴일 관리 회귀와 PC·모바일 확인 결과 기록, 개통 브라우저 단계 교체"
```

---

## 자체 점검 결과

- **설계 대비:** §1 화면(연도 이동·반영 상태·시트·상태 3종·행 추가/삭제·불러오기 병합·4인 이하 안내·미내장 연도 안내·저장 결과) → Task 8. §2.1 표·RLS·권한 → Task 2. §2.2 `scope`·부분 EXCLUDE·뷰·읽기 전환 → Task 2·3, 반영 규칙 1~5 → Task 5·6. §2.3 이전 → Task 2. §2.4 내장 목록 → Task 1. §3 보안·감사·재시도·409·422 → Task 6·7. §4 경로 4개와 제거 → Task 7. §5 테스트 → 각 Task와 Task 10. §6 문서 → Task 8(`CompanySetupGuide`)·9.
- **조사로 확인한 사실(설계 가정 검증):**
  - 달력을 직접 읽는 곳은 `ClockMapper.calendar`와 `MonthEndReviewMapper.DAY_EVIDENCE_JOINS` 둘뿐이다. `LeaveWorkflowMapper`는 달력을 읽지 않고 `ClockService` 스냅샷의 `calendarVersionId`를 저장한다. 뷰로 바꿀 수 없는 읽기는 없다.
  - V41 전에는 기간 겹침이 금지라 뷰 결과가 기존과 같다. 기존 근무 확인·일 결과의 입력 지문과 `leave_request_day`·`overtime_request_detail`의 FK는 바뀌지 않는다(옛 버전 행은 지우지 않음).
- **설계와 다르게 정한 점(보고 대상):**
  1. **휴일명 비교 범위:** 설계 §2.2-3 "종류나 이름이 다르면"을 그대로 쓰면 기존 기간 등록의 근로일 이름("검토한 기본 근로일")과 새 이름("근로일")이 달라 첫 저장 때 그 해 모든 평일이 '재계산 필요'가 된다. 이름은 휴일 행이 있는 날(HOLIDAY·NON_WORKDAY)만 비교하고, 근로일끼리는 종류만 비교한다.
  2. **근거만 바뀐 날:** 근태 달력에 새 반영분을 만들지 않는다(설계 문구대로 종류·이름만). 그래서 근태 달력 행의 근거는 처음 반영 때 값으로 남는다.
  3. **달력 '구멍':** 기간(PERIOD) 범위 안인데 날짜 행이 없는 날(SQL 시드에서만 생김)은 기간을 겹쳐 만들 수 없으므로 DAY 반영분으로 채운다. 설계는 '근태 달력이 없는 구간 → PERIOD'만 적었다.
  4. **기본값:** `calendar_day.name`·`basis`는 비울 수 없다. 휴일 행이 없는 날은 이름 "근로일", 근거가 빈 휴일과 근로일의 근거는 "휴일 관리 반영"으로 넣는다.
  5. **저장 계약:** 행의 `id?`를 받지 않고 날짜로 비교한다(`UNIQUE (tenant_id, holiday_date)`). 날짜를 바꾸면 삭제+추가로 센다. 화면은 저장된 행의 `id`를 `clientRowId`로 쓴다.
  6. **마감 월의 삭제 오류:** 삭제된 행은 보낸 행이 없으므로 `clientRowId: null`로 돌려주고 화면의 오류 목록에만 보인다.
  7. **선택근무 정산 연결:** '회사 달력 확인'(`/admin/calendar?date=…&settlementReturn=…`, 한 날짜 기간 등록 후 정산으로 복귀)을 '휴일 관리 확인'(`/admin/holidays?year=YYYY`)으로 바꾸고 복귀 왕복은 없앤다. 첫 저장이 그 해 빈 날짜를 모두 채우므로 '캘린더 미확인' 해소는 가능하다. `SelectiveSettlementReturn.calendar`를 지운다.
  8. **이전 범위:** 2000~2100년 밖의 달력 날짜는 `holiday`·`holiday_year`로 옮기지 않는다(`holiday_year` CHECK). 이전 행의 `created_by`·`updated_by`는 원래 달력 버전의 작성자다.
  9. **공개 데모 시드:** 업그레이드된 데모 DB에는 V41이 `holiday_year` 1행을 만든다. 새 데모 DB와 맞추려고 `sample-data.sql`도 1행을 넣고 `verify-sample-data.sql` 기대값을 1로 둔다. 데모 휴일 행(공휴일)은 시드하지 않는다.
  10. **기간 등록 동작 테스트 삭제:** `AttendanceSettingsIT`의 달력 테스트 4개, `EmployeeServiceIT`의 달력 마감 2줄, `CheckoutOperationsWebIT`의 달력 왕복을 지우고 같은 규칙을 `HolidayServiceIT`·`HolidayWebIT`가 맡는다. 시드 용도의 호출 14곳은 `CalendarSeed`(SQL)로 옮긴다.
- **위험(사용자 확인 권장):**
  - 대기 중인 휴가·연장 신청의 날짜가 바뀌면 승인 시 기존 검사(`LeaveWorkflowService:444-445, 467`)가 "철회 후 다시 신청"을 요구한다. 바뀐 날짜에만 생기며 기존 동작이다.
  - 토요일 근무조가 있는 회사에서 기존 주말 NON_WORKDAY는 설계 §2.2-4에 따라 '같음'으로 남아 계속 비근로일이다. 새로 채우는 빈 구간의 주말은 WORKDAY(근무조가 결정)라 회사 안에서 연도별로 다르게 보일 수 있다.
  - `HolidayMigrationIT`는 테스트 DB에 임시 DB를 만들 권한이 필요하다.
- **이름 일관성:** `KoreanPublicHolidays.Holiday/of/SMALL_WORKPLACE_NOTICE`, `HolidayReflection.Holiday/Effective/Period/Day/Plan/plan/desired/WORKDAY_NAME/DEFAULT_BASIS`, `HolidayService.Row/Applied/PageModel/Preview/RowInput/Form/Result/RowError/HolidayProblem/page/publicHolidays/save`, `HolidayMapper.YearRow/rows/year/saveYear/insertHoliday/updateHoliday/deleteHoliday/effective/periods/nextRevision/insertVersion/insertDay/result`, 뷰 이름 `clock/holidays`, 모델 키 `holidays`, 감사 action `HOLIDAY_SAVE`·kind `HOLIDAY_YEAR`, 테스트 도우미 `CalendarSeed.period(JdbcTemplate, TnaPrincipal, LocalDate, LocalDate, String, String)`를 모든 Task에서 같게 썼다.
- **확인이 필요한 기존 API(실행 시 맞춤):** MyBatis가 `HolidayService.Row`·`HolidayReflection.Effective/Period`·`HolidayMapper.YearRow` 레코드를 열 별칭·순서로 매핑하는지(기존 `CalendarVersion` 레코드와 같은 방식), `ReactPageAssertions.data`가 `int`/`boolean` 값을 비교하는 방식, `WorklogSheet`의 `actions`·`isRowSelectable` 표시, jsdom의 `Intl` 서울 시간대. 각 Task에 확인 지점을 적었다.
