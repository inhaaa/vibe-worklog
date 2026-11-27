# 법정 연차 자동 산정 (D 묶음) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 회사가 '회계연도 기준'과 '입사일 기준' 중에서 연차 부여 기준을 고른다. 근속년차별 법정 일수(첫해 비례, 3년 이상 2년마다 1일 가산, 최대 25일)를 사원별로 계산한다. 출근율 80%와 월 개근은 일일 근태의 결근 판정으로 자동 판정한다. 담당자는 미리보기를 확인한 뒤 연간 부여와 월 연차를 일괄 반영한다. 연간 부여의 '확인 필요' 행은 담당자가 골라 법정 일수로 함께 반영할 수 있다.

**Architecture:** 일수·기념일·출근율 판정은 저장소에 접근하지 않는 `kr.tna.request.StatutoryLeave`에 모은다. 출근율 근거는 B 묶음이 쓰는 `WorktimeService.leaveAttendanceDays`(근무조·달력·최신 결과 비교)를 1년 범위로 넓혀 그대로 쓰고, 결근 일수(`absence` FULL=1, HALF=0.5)를 더한다. `AnnualLeaveCycleService`의 미리보기·반영은 지금 구조(토큰, `OrgCommands.execute`, 이월·소멸)를 유지하고, 사원별 부여 일수와 판정 근거를 행과 `annual_leave_award`(V47)에 싣는다. 입사일 기준은 같은 서비스에서 '월'을 골라 사원마다 기념일을 부여일로 쓴다. 월 연차 일괄 반영은 `EmploymentAccrualService`의 기존 사원별 저장 경로를 같이 쓴다.

**Tech Stack:** Spring Boot 4.1 / Java 25, MyBatis annotation mapper, PostgreSQL 18 + Flyway, React 19 + Vitest, JUnit 5 + 외부 테스트 DB(`DatabaseFixture`).

설계: [docs/superpowers/specs/2026-11-27-statutory-annual-leave-design.md](../specs/2026-11-27-statutory-annual-leave-design.md)

---

## 공통 실행 방법

- 작업 디렉터리: `C:\Users\ihpark\dev\vibe-attendance\target\claude-opening-prep-20261123` (브랜치 `claude/opening-prep-20261123`). 브랜치를 바꾸지 않는다. Push 금지. bare `git stash` 금지.
- Maven은 **Bash 도구에서** 스크립트로 실행한다(PowerShell 도구는 `-D` 인자를 망가뜨린다). 한 번에 하나만 실행한다.
  - 단위: `powershell -NoProfile -File "C:\Users\ihpark\AppData\Local\Temp\claude\c6d09512e35b8bb6\ff996283-e18f-4a51-b946-d1e31871cf61\scratchpad\mvn-test.ps1" -q test -Dfrontend.skip=true -Dspring-boot.repackage.skip=true -Dtest=A,B`
  - 통합: `... mvn-test.ps1 -q verify -Dfrontend.skip=true -Dspring-boot.repackage.skip=true -Dit.test=A,B -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`
  - `-q`가 아무것도 출력하지 않으면 `target/failsafe-reports`·`target/surefire-reports`에서 이번에 돌린 클래스만 읽는다(예전 보고서가 남아 있다).
- 프런트엔드: `cd frontend; npx vitest run <파일>`; 빌드 `npm run build`.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 회사 격리·RLS·권한을 약화하지 않는다. 담당자 본인 제외(`e.id<>#{self}`, `basis()`의 403)는 그대로 둔다. 개인정보를 로그·감사·페이지 모델에 기존 화면보다 더 넣지 않는다. 사용자에게 보이는 문구는 한국어다.
- 기존 도우미(`OrgCommands.execute/readShared/fingerprint/requiredText/checkVersion`, `BusinessCommands`)와 기존 코드 모양(촘촘한 한 줄 SQL, record)을 따른다. 설정값·인터페이스·추상화를 새로 만들지 않는다.

## 설계와 코드를 대조해 정한 점

설계가 정하지 않았거나 코드와 맞지 않는 곳은 가장 작은 쪽으로 정했다. 구현 중 바꾸지 않는다.

1. **비례 일수 상한 15일**: 윤년(366일)에 회계연도 시작일 입사자는 `15×366/365=15.04→15.5`가 된다. 비례 일수는 `min(…, 15)`로 둔다.
2. **입사일 기준의 이월 한도**: 입사일 기준은 연간 부여마다 현재 규칙(`currentRule`)을 쓴다. `annual_leave_cycle.rule_version`은 그 해 첫 반영 때의 버전 기록일 뿐이다. 회계연도 기준은 지금처럼 그 연도에 고정한 규칙(`appliedRule`)을 쓴다.
3. **입사일 기준 규칙의 `fiscal_start_month`**: 열이 NOT NULL이고 월 연차의 귀속연도(`AnnualLeaveCycleData.currentYear`)가 계속 쓴다. 입사일 기준으로 저장하면 이전 값(없으면 1)을 그대로 둔다.
4. **판정 불가(`unresolved`) 재정의**: 지금은 결근으로 확정된 날도 '미확인'으로 센다. 설계 §4대로 '최신 결과가 없거나 최신이 아닌 날'만 판정 불가로 바꾼다. 사원별 월 연차 화면의 '미확인' 수도 같은 정의를 따른다.
5. **출근율 조회 범위**: `leaveAttendanceDays`의 33일 상한을 366일로 넓힌다. `WorktimeMapper.monthDays`는 42일 상한이라 31일씩 나눠 읽는다.
6. **월 연차 일괄 대상**: 월 연차 사용기한(첫 입사기념일 전날)이 오늘 이후인 사원만 보인다. 기한이 지난 미판정 월은 기존 사원별 화면에서 처리한다.
7. **월 개근 자동 판정 순서**: 결근이 있으면 판정 불가가 있어도 '미개근'이다. 결근 0일·판정 불가 0일이면 소정근로일이 0일이어도 '개근'이다.
8. **확인 필요 행의 이월·소멸**: 연간 미리보기는 확인 필요 행도 전기 잔여·이월·소멸을 계산해 보인다. 반영은 부여 대상 행 전부와 담당자가 고른 확인 필요 행(`reviewedEmployeeIds`)만 한다.
9. **회계 시작월 변경 안내**: 부여 일수 입력이 없어지므로 기존 문구의 "부여·이월 일수는"을 "이월 한도는"으로 바꾼다.
10. **월 연차 일괄 반영의 판정 근거**: `leave_monthly_review`에 결근 열을 더하지 않는다. "자동 판정: 소정 n일, 결근 m일"은 원장 근거(`source_reference`)에, 담당자 사유는 `reason`에 저장한다.
11. **미래 시점 시험**: `LeaveWorkflowIT`의 다음 회계연도 시점 시험 2개는 미래 날짜의 근태 결과를 만들 수 없어 부여 대상이 될 수 없다. 이월·대기·복원 계산은 미리보기 단계까지만 검증하도록 바꾼다.
12. **월 연차 일괄 반영 응답 유실**: 같은 명령 재시도 버튼은 두지 않는다. 다시 미리보면 반영된 월은 빠지고 토큰이 바뀌어 중복 반영되지 않는다.

13. **담당자 확인 부여의 기록**(사용자 결정 2026-11-27, 설계 §4.1·§5): `annual_leave_award.reviewed_by_admin boolean NOT NULL DEFAULT false`로 표시한다. 판정 근거 열은 계산한 값 그대로 저장한다. 그래서 소정근로일 0일 행도 담당자가 고르면 저장할 수 있게 검사 조건을 `scheduled_days > 0 OR reviewed_by_admin`으로 둔다. 원장 근거(새 `leave_bucket.source_reference`와 같은 반영의 `leave_ledger.source_reference`)에 ` · 담당자 확인(판정 사유)`를 덧붙이고, `reason`은 담당자가 입력한 반영 사유 그대로다.
14. **고른 사원 검증**: 고른 사원 목록(중복 제거·정렬)을 명령 지문에 넣는다. 다시 계산한 계획에서 확인 필요 행이 아닌 ID(이미 반영됨, 부여 대상, 다른 회사·없는 사원)가 있으면 409 "확인한 사원이 미리보기의 확인 필요 사원과 다릅니다. 미리보기를 다시 확인해 주세요."로 거절한다. 빈 값(`null`)이 섞이면 422다.
15. **반영 가능(`canPost`)**: 시작일이 지났고 행이 하나라도 있으면 참이다(확인 필요 행만 있어도 고르면 반영할 수 있다). 부여 대상도 고른 행도 없으면 반영 때 422 "부여할 사원이 없습니다. 확인 필요 사원을 고르거나 근태를 확정한 뒤 다시 미리 보세요."다.

WorkLog 도입 전 근태가 없는 기존 사원은 처음 연간 부여에서 '확인 필요'가 되고, 담당자가 확인해 골라 법정 일수로 반영한다(결정 13). 월 연차 일괄 반영(Task 7)의 확인 필요 행은 지금처럼 빼고 사원별 화면에서 처리한다.

## 파일 구조

| 파일 | 책임 |
| --- | --- |
| `src/main/resources/db/migration/V47__statutory_annual_leave.sql` (생성) | `annual_leave_rule.basis`, `annual_leave_award` 판정 근거 열 |
| `src/main/java/kr/tna/request/StatutoryLeave.java` (생성) | 근속 일수, 기념일, 회계연도 근속·비례, 출근율·월 개근 판정(순수 함수) |
| `src/main/java/kr/tna/request/AnnualLeaveCycleData.java`, `AnnualLeaveCycleMapper.java`, `AnnualLeaveCycleService.java` (수정) | 기준 저장, 사원별 일수·판정, 입사일 기준 월별 부여, 개인 원장 기간 |
| `src/main/java/kr/tna/request/AnnualLeavePostingController.java`, `AnnualLeaveCycleController.java` (수정) | 월 미리보기·반영, 월 연차 일괄 API, 페이지 모델 `month` |
| `src/main/java/kr/tna/worktime/WorktimeService.java` (수정) | `LeaveAttendanceDay.absenceDays`, 판정 불가 정의, 366일 범위 |
| `src/main/java/kr/tna/request/EmploymentAccrualData.java`, `EmploymentAccrualMapper.java`, `EmploymentAccrualService.java` (수정) | `Period.absenceDays`, 월 연차 일괄 미리보기·반영 |
| `src/main/java/kr/tna/common/web/ReactPageViewResolver.java`, `src/main/java/kr/tna/common/config/SecurityConfiguration.java` (수정) | 모델 키 `month`, 공개 데모 쓰기 차단 경로 |
| `src/test/java/kr/tna/AttendanceSeed.java` (생성) | 출근율 시험용 근무조·달력·출퇴근·계산 시드 |
| `src/test/java/kr/tna/StatutoryLeaveSchemaIT.java`, `src/test/java/kr/tna/request/StatutoryLeaveTest.java`, `src/test/java/kr/tna/StatutoryAnnualLeaveIT.java` (생성) | 새 시험 |
| `src/test/java/kr/tna/AnnualLeaveCycleIT.java`, `AnnualLeaveCycleWebIT.java`, `EmploymentAccrualIT.java`, `LeaveWorkflowIT.java`, `MigrationRunnerIT.java`, `PublicDemoSecurityIT.java` (수정) | 바뀐 규칙에 맞춘 기존 시험 |
| `frontend/src/pages/annualLeaveTypes.ts`, `AnnualLeaveCycle.tsx`, `PersonalLeaveLedger.tsx`, `employmentLeaveTypes.ts` (수정), `AnnualMonthlyBulk.tsx` (생성) | 기준 선택, 판정 열, 월 선택, 월 연차 일괄 표, 원장 기간 |
| `frontend/src/pages/AnnualLeavePages.test.tsx`, `EmploymentLeave.test.tsx` (수정), `AnnualMonthlyBulk.test.tsx` (생성) | React 시험 |

---

### Task 1: V47 스키마 (연차 기준, 부여 판정 근거)

**Files:**
- Create: `src/main/resources/db/migration/V47__statutory_annual_leave.sql`
- Create: `src/test/java/kr/tna/StatutoryLeaveSchemaIT.java`
- Modify: `src/test/java/kr/tna/MigrationRunnerIT.java:31-36` (46 → 47)

- [ ] **Step 1: 실패하는 스키마 시험**

```java
package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class StatutoryLeaveSchemaIT {
    JdbcTemplate db;
    UUID tenant, employee, account, bucket;

    @BeforeEach void seed() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID(); employee = UUID.randomUUID(); account = UUID.randomUUID(); bucket = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 법정 연차')", tenant, "STAT-" + tenant);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'S1','합성 사원',DATE '2024-07-01')", tenant, employee);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic')", tenant, account, employee, account + "@example.test");
        db.update("INSERT INTO tna.annual_leave_rule(tenant_id,id,version,fiscal_start_month,grant_days,carry_limit_days,created_by) VALUES (?,?,1,1,15,0,?)",
                tenant, UUID.randomUUID(), account);
        db.update("INSERT INTO tna.annual_leave_cycle(tenant_id,id,entitlement_year,rule_version,created_by) VALUES (?,?,2025,1,?)", tenant, UUID.randomUUID(), account);
        db.update("""
                INSERT INTO tna.leave_bucket(tenant_id,id,employee_id,base_date,usable_from,usable_to,source_reference,created_by)
                VALUES (?,?,?,DATE '2025-01-01',DATE '2025-01-01',DATE '2025-12-31','합성 부여',?)""", tenant, bucket, employee, account);
    }

    private int rule(int version, String basis) {
        return db.update("INSERT INTO tna.annual_leave_rule(tenant_id,id,version,fiscal_start_month,grant_days,carry_limit_days,basis,created_by) VALUES (?,?,?,1,15,0,?,?)",
                tenant, UUID.randomUUID(), version, basis, account);
    }

    private int award(Integer serviceYear, String start, String end, Integer scheduled, String absence, String granted) {
        return award(serviceYear, start, end, scheduled, absence, granted, false);
    }

    private int award(Integer serviceYear, String start, String end, Integer scheduled, String absence, String granted, boolean reviewed) {
        return db.update("""
                INSERT INTO tna.annual_leave_award(tenant_id,entitlement_year,employee_id,bucket_id,created_by,
                    service_year,period_start,period_end,scheduled_days,absence_days,granted_days,reviewed_by_admin)
                VALUES (?,2025,?,?,?,?,CAST(? AS date),CAST(? AS date),?,CAST(? AS numeric),CAST(? AS numeric),?)""",
                tenant, employee, bucket, account, serviceYear, start, end, scheduled, absence, granted, reviewed);
    }

    @Test void existingRulesAreFiscalAndOnlyTheTwoBasesAreAccepted() {
        assertEquals("FISCAL", db.queryForObject("SELECT basis FROM tna.annual_leave_rule WHERE tenant_id=? AND version=1", String.class, tenant));
        assertEquals(1, rule(2, "HIRE_DATE"));
        assertThrows(DataIntegrityViolationException.class, () -> rule(3, "MONTHLY"));
    }

    @Test void awardEvidenceIsAllOrNothingAndWithinStatutoryBounds() {
        assertThrows(DataIntegrityViolationException.class, () -> award(1, "2024-07-01", null, 1, "0", "8"));
        assertThrows(DataIntegrityViolationException.class, () -> award(1, "2024-07-01", "2024-12-31", 0, "0", "8"));
        assertThrows(DataIntegrityViolationException.class, () -> award(1, "2024-07-01", "2024-12-31", 10, "0.25", "8"));
        assertThrows(DataIntegrityViolationException.class, () -> award(1, "2024-07-01", "2024-12-31", 10, "0", "25.5"));
        assertThrows(DataIntegrityViolationException.class, () -> award(0, "2024-07-01", "2024-12-31", 10, "0", "8"));
        assertEquals(1, award(1, "2024-07-01", "2024-12-31", 10, "1.5", "8"));
        assertEquals(Boolean.TRUE, db.queryForObject("""
                SELECT has_column_privilege('tna_app','tna.annual_leave_award','granted_days','SELECT')
                   AND has_column_privilege('tna_app','tna.annual_leave_award','granted_days','INSERT')
                   AND NOT has_column_privilege('tna_app','tna.annual_leave_award','granted_days','UPDATE')
                   AND has_column_privilege('tna_app','tna.annual_leave_rule','basis','INSERT')""", Boolean.class));
    }

    @Test void awardsPostedBeforeV47KeepEmptyEvidenceAndAreNotAdminReviewed() {
        assertThrows(DataIntegrityViolationException.class, () -> award(null, null, null, null, null, null, true));
        assertEquals(1, award(null, null, null, null, null, null));
        assertEquals(Boolean.FALSE, db.queryForObject("SELECT reviewed_by_admin FROM tna.annual_leave_award WHERE tenant_id=?", Boolean.class, tenant));
    }

    @Test void aReviewedAwardKeepsTheComputedEvidenceEvenWithoutScheduledDays() {
        assertEquals(1, award(2, "2024-01-01", "2024-12-31", 0, "0", "15", true));
    }
}
```

- [ ] **Step 2: 실패 확인** — `-Dit.test=StatutoryLeaveSchemaIT` → `basis` 열 없음으로 FAIL.

- [ ] **Step 3: 마이그레이션**

```sql
-- 법정 연차 자동 산정(설계 2026-11-27-statutory-annual-leave-design.md §2·§6): 회사 부여 기준과 사원별 판정 근거
ALTER TABLE tna.annual_leave_rule
    ADD COLUMN basis varchar(10) NOT NULL DEFAULT 'FISCAL',
    ADD CONSTRAINT annual_leave_rule_basis_valid CHECK (basis IN ('FISCAL','HIRE_DATE'));

-- V47 이전 부여 행은 근거가 비어 있다. 새 행은 근거 열을 모두 채운다.
ALTER TABLE tna.annual_leave_award
    ADD COLUMN service_year integer,
    ADD COLUMN period_start date,
    ADD COLUMN period_end date,
    ADD COLUMN scheduled_days integer,
    ADD COLUMN absence_days numeric,
    ADD COLUMN granted_days numeric,
    -- 출근율 판정이 '확인 필요'인 사원을 담당자가 골라 법정 일수로 부여했는지(설계 §4.1). 기존 행은 거짓이다.
    ADD COLUMN reviewed_by_admin boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT annual_leave_award_evidence_valid CHECK (
        (service_year IS NULL AND period_start IS NULL AND period_end IS NULL AND scheduled_days IS NULL
            AND absence_days IS NULL AND granted_days IS NULL AND NOT reviewed_by_admin)
        OR (service_year >= 1 AND period_start IS NOT NULL AND period_end IS NOT NULL AND period_start <= period_end
            AND scheduled_days >= 0 AND (scheduled_days > 0 OR reviewed_by_admin)
            AND absence_days >= 0 AND mod(absence_days, 0.5) = 0 AND absence_days <= scheduled_days
            AND granted_days BETWEEN 0 AND 25 AND mod(granted_days, 0.5) = 0)
    );
```

RLS 정책과 권한은 V37의 테이블 단위 `GRANT SELECT, INSERT`가 새 열에도 적용되므로 바꾸지 않는다.

`MigrationRunnerIT`의 `46`과 `'46'`을 `47`, `'47'`로 바꾼다.

- [ ] **Step 4: 통과 확인** — `-Dit.test=StatutoryLeaveSchemaIT,MigrationRunnerIT,DatabaseFoundationIT,CodeMigrationUpgradeIT` → PASS. `CodeMigrationUpgradeIT`가 최신 버전의 열 목록을 정확히 비교해 실패하면 그 비교에 새 열 7개만 더한다.

- [ ] **Step 5: 커밋** — `feat: add the annual leave basis and per-award statutory evidence columns`

---

### Task 2: 순수 계산 `StatutoryLeave`

**Files:**
- Create: `src/main/java/kr/tna/request/StatutoryLeave.java`
- Create: `src/test/java/kr/tna/request/StatutoryLeaveTest.java`
- Modify: `src/main/java/kr/tna/worktime/WorktimeService.java:149-150` (`LeaveAttendanceDay`에 `double absenceDays`를 `unresolved` 뒤에 추가. 값 계산은 Task 4. 이 Task에서는 생성 위치 :596에 `0`을 넘긴다)

- [ ] **Step 1: 실패하는 단위 시험** (설계 §8 경계)

```java
package kr.tna.request;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.request.StatutoryLeave.Evidence;
import kr.tna.worktime.WorktimeService.LeaveAttendanceDay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StatutoryLeaveTest {
    private static LocalDate d(String value) { return LocalDate.parse(value); }
    private static void days(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> expected + " != " + actual); }

    @ParameterizedTest @CsvSource({"1,15", "2,15", "3,16", "4,16", "5,17", "21,25", "30,25"})
    void serviceYearDays(int year, String expected) { days(expected, StatutoryLeave.serviceDays(year)); }

    @Test void serviceYearMustBePositive() { assertThrows(IllegalArgumentException.class, () -> StatutoryLeave.serviceDays(0)); }

    @ParameterizedTest @CsvSource({
            "2025-07-01,2026-01-01,8",      // 설계 예: 184일 → 7.56 → 8
            "2025-12-31,2026-01-01,0.5",    // 월말 하루
            "2025-03-15,2026-01-01,12",     // 292일 → 정확히 12, 올리지 않는다
            "2025-01-01,2026-01-01,15",     // 회계연도 시작일 입사자의 다음 해
            "2024-01-01,2025-01-01,15",     // 윤년 366일 → 15.04 → 상한 15
            "2025-10-01,2026-04-01,7.5"})   // 4월 시작 회사, 182일 → 7.48 → 7.5
    void firstFiscalYearIsProratedUpToHalfDays(String joined, String fiscalStart, String expected) {
        days(expected, StatutoryLeave.prorated(d(joined), d(fiscalStart)));
    }

    @Test void fiscalServiceYearCountsFromTheJoiningFiscalYearStart() {
        assertEquals(LocalDate.of(2024, 1, 1), StatutoryLeave.fiscalStart(d("2024-07-01"), 1));
        assertEquals(LocalDate.of(2025, 4, 1), StatutoryLeave.fiscalStart(d("2026-03-31"), 4));
        assertEquals(1, StatutoryLeave.fiscalServiceYear(d("2024-07-01"), d("2025-01-01"), 1));
        assertEquals(2, StatutoryLeave.fiscalServiceYear(d("2024-07-01"), d("2026-01-01"), 1));
        assertEquals(3, StatutoryLeave.fiscalServiceYear(d("2024-07-01"), d("2027-01-01"), 1));
        assertEquals(5, StatutoryLeave.fiscalServiceYear(d("2024-07-01"), d("2029-01-01"), 1));
    }

    @Test void february29thAnniversariesMoveToMarch1stInCommonYears() {
        assertEquals(d("2025-03-01"), StatutoryLeave.anniversary(d("2024-02-29"), 1));
        assertEquals(d("2028-02-29"), StatutoryLeave.anniversary(d("2024-02-29"), 4));
        assertEquals(d("2026-01-31"), StatutoryLeave.anniversary(d("2025-01-31"), 1));
        assertEquals(d("2025-01-31"), StatutoryLeave.anniversary(d("2025-01-31"), 0));
    }

    @Test void annualAttendanceRateBoundaries() {
        var exact = StatutoryLeave.annual(new Evidence(10, new BigDecimal("2.0"), 0));
        assertEquals(StatutoryLeave.GRANT, exact.decision());
        days("80", exact.attendanceRate());
        assertNull(exact.reason());
        var below = StatutoryLeave.annual(new Evidence(1000, new BigDecimal("201.0"), 0));
        assertEquals(StatutoryLeave.NEEDS_REVIEW, below.decision());
        assertEquals("출근율 79.9%", below.reason());
        assertEquals("출근율 75.0%", StatutoryLeave.annual(new Evidence(10, new BigDecimal("2.5"), 0)).reason());
        assertEquals(StatutoryLeave.GRANT, StatutoryLeave.annual(new Evidence(5, new BigDecimal("0.5"), 0)).decision());
        var unresolved = StatutoryLeave.annual(new Evidence(200, BigDecimal.ZERO, 3));
        assertEquals("근태 확정 전 3일", unresolved.reason());
        assertNull(unresolved.attendanceRate());
        assertEquals("소정근로일 0일", StatutoryLeave.annual(new Evidence(0, BigDecimal.ZERO, 0)).reason());
    }

    @Test void monthlyDecisionPrefersAbsenceThenUnresolvedDays() {
        assertEquals(StatutoryLeave.FULL, StatutoryLeave.monthly(new Evidence(22, BigDecimal.ZERO, 0)));
        assertEquals(StatutoryLeave.NOT_FULL, StatutoryLeave.monthly(new Evidence(22, new BigDecimal("0.5"), 2)));
        assertEquals(StatutoryLeave.NEEDS_REVIEW, StatutoryLeave.monthly(new Evidence(22, BigDecimal.ZERO, 1)));
        assertEquals(StatutoryLeave.FULL, StatutoryLeave.monthly(new Evidence(0, BigDecimal.ZERO, 0)));
    }

    @Test void evidenceAddsFullAndHalfAbsences() {
        var day = d("2026-01-05");
        var evidence = Evidence.of(List.of(
                new LeaveAttendanceDay(day, true, false, false, false, 1, "s", UUID.randomUUID(), 1),
                new LeaveAttendanceDay(day.plusDays(1), true, false, true, false, 0.5, "s", UUID.randomUUID(), 1),
                new LeaveAttendanceDay(day.plusDays(2), true, false, false, true, 0, "s", null, 0),
                new LeaveAttendanceDay(day.plusDays(3), false, false, false, false, 0, "s", null, 0)));
        assertEquals(3, evidence.scheduledDays());
        days("1.5", evidence.absenceDays());
        assertEquals(1, evidence.unresolvedDays());
    }
}
```

- [ ] **Step 2: 실패 확인** — `-Dtest=StatutoryLeaveTest` → 컴파일 실패.

- [ ] **Step 3: 구현**

`WorktimeService.java:149-150`:

```java
    public record LeaveAttendanceDay(LocalDate date, boolean scheduled, boolean attended, boolean leave,
            boolean unresolved, double absenceDays, String source, UUID resultId, long resultRevision) { }
```

같은 파일 :596의 생성은 `unresolved, 0, source, day.id(), day.resultRevision()`로 맞춘다(실제 값은 Task 4).

`StatutoryLeave.java`:

```java
package kr.tna.request;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import kr.tna.worktime.WorktimeService.LeaveAttendanceDay;

/** 근로기준법 제60조 연차 일수와 출근율·월 개근 판정(설계 2026-11-27 §3·§4). 저장소에 접근하지 않는다. */
public final class StatutoryLeave {
    public static final String FISCAL = "FISCAL", HIRE_DATE = "HIRE_DATE";
    public static final String GRANT = "GRANT", NEEDS_REVIEW = "NEEDS_REVIEW", FULL = "FULL", NOT_FULL = "NOT_FULL";
    public static final BigDecimal BASE_DAYS = new BigDecimal("15.0");
    private static final BigDecimal TWO = BigDecimal.valueOf(2), FOUR = BigDecimal.valueOf(4), FIVE = BigDecimal.valueOf(5),
            HUNDRED = BigDecimal.valueOf(100), YEAR_DAYS = BigDecimal.valueOf(365);

    private StatutoryLeave() { }

    /** 근속 n년차 일수: 15 + floor((n-1)/2), 최대 25. */
    public static BigDecimal serviceDays(int serviceYear) {
        if (serviceYear < 1) throw new IllegalArgumentException("serviceYear");
        return BigDecimal.valueOf(Math.min(15 + (serviceYear - 1) / 2, 25)).setScale(1);
    }

    /** 입사 n주년 기념일. 2월 29일 입사자의 평년 기념일은 3월 1일이다. n=0이면 입사일이다. */
    public static LocalDate anniversary(LocalDate joinedOn, int years) {
        LocalDate date = joinedOn.plusYears(years);
        return date.getDayOfMonth() == joinedOn.getDayOfMonth() ? date : date.plusDays(1);
    }

    /** 날짜가 속한 회계연도의 시작일. */
    public static LocalDate fiscalStart(LocalDate date, int fiscalStartMonth) {
        return LocalDate.of(date.getYear() - (date.getMonthValue() < fiscalStartMonth ? 1 : 0), fiscalStartMonth, 1);
    }

    /** 회계연도 기준 근속년차: 입사한 회계연도의 시작일부터 이번 회계연도 시작일까지의 햇수. */
    public static int fiscalServiceYear(LocalDate joinedOn, LocalDate fiscalYearStart, int fiscalStartMonth) {
        return (int) ChronoUnit.YEARS.between(fiscalStart(joinedOn, fiscalStartMonth), fiscalYearStart);
    }

    /** 입사 다음 회계연도 비례 일수: 15 × (입사일~입사 회계연도 말일 일수) / 365를 0.5일 단위로 올린다. 15일을 넘지 않는다. */
    public static BigDecimal prorated(LocalDate joinedOn, LocalDate fiscalYearStart) {
        long days = ChronoUnit.DAYS.between(joinedOn, fiscalYearStart);
        BigDecimal halves = BigDecimal.valueOf(15 * 2 * days).divide(YEAR_DAYS, 0, RoundingMode.CEILING);
        return halves.divide(TWO, 1, RoundingMode.UNNECESSARY).min(BASE_DAYS);
    }

    public record Evidence(int scheduledDays, BigDecimal absenceDays, int unresolvedDays) {
        public static Evidence of(List<LeaveAttendanceDay> days) {
            int scheduled = 0, unresolved = 0;
            BigDecimal absence = BigDecimal.ZERO;
            for (var day : days) {
                if (day.scheduled()) scheduled++;
                if (day.unresolved()) unresolved++;
                absence = absence.add(BigDecimal.valueOf(day.absenceDays()));
            }
            return new Evidence(scheduled, absence.setScale(1), unresolved);
        }
    }

    public record Judgement(String decision, BigDecimal attendanceRate, String reason) { }

    /** 연간 부여 80% 판정(§4.1). 출근율은 소수 첫째 자리에서 버려 표시하고 비교는 정확히 한다. */
    public static Judgement annual(Evidence evidence) {
        if (evidence.unresolvedDays() > 0) return new Judgement(NEEDS_REVIEW, null, "근태 확정 전 " + evidence.unresolvedDays() + "일");
        if (evidence.scheduledDays() == 0) return new Judgement(NEEDS_REVIEW, null, "소정근로일 0일");
        BigDecimal scheduled = BigDecimal.valueOf(evidence.scheduledDays());
        BigDecimal attended = scheduled.subtract(evidence.absenceDays());
        BigDecimal rate = attended.multiply(HUNDRED).divide(scheduled, 1, RoundingMode.DOWN);
        if (attended.multiply(FIVE).compareTo(scheduled.multiply(FOUR)) >= 0) return new Judgement(GRANT, rate, null);
        return new Judgement(NEEDS_REVIEW, rate, "출근율 " + rate.toPlainString() + "%");
    }

    /** 월 개근 판정(§4.2): 결근이 있으면 미개근, 판정 불가가 있으면 확인 필요, 아니면 개근. */
    public static String monthly(Evidence evidence) {
        if (evidence.absenceDays().signum() > 0) return NOT_FULL;
        return evidence.unresolvedDays() > 0 ? NEEDS_REVIEW : FULL;
    }
}
```

- [ ] **Step 4: 통과 확인** — `-Dtest=StatutoryLeaveTest,EmploymentAccrualCalendarTest` → PASS. 통합: `-Dit.test=EmploymentAccrualIT` → PASS(값 0이라 동작 변화 없음).

- [ ] **Step 5: 커밋** — `feat: add statutory annual leave day, anniversary and attendance rate rules`

---

### Task 3: 연차 기준 저장 (basis, 부여 일수 입력 제거, 기준 변경 금지)

**Files:**
- Modify: `src/main/java/kr/tna/request/AnnualLeaveCycleData.java:243-245` (`Rule`, `RuleForm`)
- Modify: `src/main/java/kr/tna/request/AnnualLeaveCycleMapper.java:16-45` (`basis` 선택·저장)
- Modify: `src/main/java/kr/tna/request/AnnualLeaveCycleService.java:57-76` (`saveRule`)
- Modify: `src/test/java/kr/tna/AnnualLeaveCycleIT.java`, `AnnualLeaveCycleWebIT.java`, `EmploymentAccrualIT.java:141,155-157`, `LeaveWorkflowIT.java:165-189,214,244-281`
- Modify: `frontend/src/pages/annualLeaveTypes.ts`, `frontend/src/pages/AnnualLeaveCycle.tsx`, `frontend/src/pages/AnnualLeavePages.test.tsx`

- [ ] **Step 1: 실패하는 시험** — `AnnualLeaveCycleIT`에 추가한다.

```java
    @Test void basisIsChosenOnceRecordsExistAndNewVersionsStoreTheStatutoryBaseDays() {
        UUID first = cycles.saveRule(admin, new RuleForm(0L, "HIRE_DATE", null, new BigDecimal("3"), "입사일 기준 운영", UUID.randomUUID()));
        var saved = cycles.savedRule(admin, first);
        assertEquals("HIRE_DATE", saved.basis());
        assertEquals(1, saved.fiscalStartMonth());
        days("15", saved.grantDays());
        cycles.saveRule(admin, new RuleForm(1L, "FISCAL", 4, new BigDecimal("3"), "회계연도 기준으로 변경", UUID.randomUUID()));
        assertEquals("FISCAL", cycles.rule(admin).basis());
        assertEquals(4, cycles.rule(admin).fiscalStartMonth());
        db.update("INSERT INTO tna.annual_leave_cycle(tenant_id,id,entitlement_year,rule_version,created_by) VALUES (?,?,?,2,?)",
                tenant, UUID.randomUUID(), year, admin.accountId());
        var locked = assertThrows(OrgException.class, () -> cycles.saveRule(admin,
                new RuleForm(2L, "HIRE_DATE", null, new BigDecimal("3"), "기준 변경 시도", UUID.randomUUID())));
        assertEquals(409, locked.status());
        assertEquals("연간 부여나 월별 연차를 기록한 회사는 연차 기준을 바꿀 수 없습니다. 이월 한도는 변경할 수 있습니다.", locked.getMessage());
        cycles.saveRule(admin, new RuleForm(2L, "FISCAL", 4, new BigDecimal("5"), "이월 한도 조정", UUID.randomUUID()));
        days("5", cycles.rule(admin).carryLimitDays());
        for (String basis : java.util.Arrays.asList(null, "MONTHLY"))
            status(422, () -> cycles.saveRule(admin, new RuleForm(3L, basis, 4, BigDecimal.ONE, "잘못된 기준", UUID.randomUUID())));
        status(422, () -> cycles.saveRule(admin, new RuleForm(3L, "FISCAL", null, BigDecimal.ONE, "시작월 누락", UUID.randomUUID())));
    }
```

- [ ] **Step 2: 실패 확인** — `-Dit.test=AnnualLeaveCycleIT` → 컴파일 실패.

- [ ] **Step 3: 서버 구현**

`AnnualLeaveCycleData.java`:

```java
    public record Rule(long version, int fiscalStartMonth, BigDecimal grantDays, BigDecimal carryLimitDays, String basis) { }
    public record RuleForm(Long expectedVersion, String basis, Integer fiscalStartMonth, BigDecimal carryLimitDays,
            String reason, UUID commandId) { }
```

`AnnualLeaveCycleMapper.java`: 세 조회(`currentRule`, `savedRule`, `appliedRule`)의 선택 목록 끝에 `basis`(`appliedRule`은 `r.basis`)를 더하고 저장을 바꾼다(MyBatis는 열 순서로 record를 만든다).

```java
    @Insert("""
            INSERT INTO tna.annual_leave_rule(tenant_id, id, version, fiscal_start_month, grant_days, carry_limit_days, basis, created_by)
            VALUES (#{tenantId},#{id},#{rule.version},#{rule.fiscalStartMonth},#{rule.grantDays},#{rule.carryLimitDays},#{rule.basis},#{actor})
            """)
```

`AnnualLeaveCycleService.saveRule` 전체:

```java
    public UUID saveRule(TnaPrincipal actor, RuleForm form) {
        if (form == null || !StatutoryLeave.FISCAL.equals(form.basis()) && !StatutoryLeave.HIRE_DATE.equals(form.basis()))
            throw OrgException.invalid("연차 부여 기준을 회계연도 기준 또는 입사일 기준으로 선택해 주세요.");
        boolean fiscal = StatutoryLeave.FISCAL.equals(form.basis());
        if (fiscal && (form.fiscalStartMonth() == null || form.fiscalStartMonth() < 1 || form.fiscalStartMonth() > 12))
            throw OrgException.invalid("회계 시작월을 1~12월 중에서 선택해 주세요.");
        var cap = quantity(form.carryLimitDays());
        String reason = requiredText(form.reason(), 500, "변경 사유");
        return administration.execute(actor, form.commandId(), "ANNUAL_LEAVE_RULE_SAVE", "ANNUAL_LEAVE_RULE",
                fingerprint(form.expectedVersion(), form.basis(), form.fiscalStartMonth(), cap, reason), reason, () -> {
                    var before = mapper.currentRule(actor.tenantId());
                    long version = before == null ? 0 : before.version();
                    checkVersion(form.expectedVersion(), version);
                    String beforeBasis = before == null ? StatutoryLeave.FISCAL : before.basis();
                    int beforeMonth = before == null ? 1 : before.fiscalStartMonth();
                    // 입사일 기준도 월 연차 귀속연도가 쓰는 회계 시작월을 그대로 보존한다.
                    int month = fiscal ? form.fiscalStartMonth() : beforeMonth;
                    boolean basisChanged = !beforeBasis.equals(form.basis());
                    if ((basisChanged || beforeMonth != month) && mapper.hasCycles(actor.tenantId()))
                        throw OrgException.conflict(basisChanged
                                ? "연간 부여나 월별 연차를 기록한 회사는 연차 기준을 바꿀 수 없습니다. 이월 한도는 변경할 수 있습니다."
                                : "연간 부여나 월별 연차를 기록한 회사는 기존 회계 시작월을 유지해 주세요. 이월 한도는 변경할 수 있습니다.");
                    var rule = new Rule(version + 1, month, StatutoryLeave.BASE_DAYS, cap, form.basis());
                    UUID id = UUID.randomUUID();
                    changed(mapper.insertRule(actor.tenantId(), id, rule, actor.accountId()));
                    return new Change(id, before, rule);
                });
    }
```

`quantity()`의 오류 문구 "부여·이월 일수는"을 "이월 한도는"으로 바꾼다. `plan()`의 첫 서명에서 `rule.grantDays()`를 `rule.basis()`로 바꾼다(부여 일수는 Task 5부터 사원별로 계산한다. 이 Task에서는 행의 부여 일수가 새 규칙의 15일이다).

- [ ] **Step 4: 기존 시험을 새 규칙에 맞춘다** (Task 5 이후에도 그대로 맞는 값으로 바꾼다. 모든 대상자가 근속 2년차가 되도록 입사일을 `start.minusYears(2)`로 두면 Task 5에서도 15일이다)

`AnnualLeaveCycleIT`:
- `seed()`: `admin = member(tenant, "담당", true, start.minusYears(2)); worker = member(tenant, "직원", false, start.minusYears(2));`
- 도우미:

```java
    private static RuleForm rule(long version,int month,String cap) {
        return new RuleForm(version,"FISCAL",month,new BigDecimal(cap),"합성 회사 기준",UUID.randomUUID());
    }
    private void save(String cap) { cycles.saveRule(admin,rule(0,start.getMonthValue(),cap)); }
```

- `previewConsumesOnly…`: `member(tenant, "기존", false, start.minusYears(2))`, `save("3.5")`, 기대값 `days("19.5", ledger.availableDays())`, `assertEquals(18.5, balance(target))`, `days("18.5", annual.view(...).annual().registeredDays())`.
- `policyRevisions…`: `member(tenant, "다른담당", true, start.minusYears(2))`, `rule(0, start.getMonthValue(), "0")`, `rule(0, 1, "0")`(버전 충돌 409 그대로), `rule(1, start.getMonthValue()%12+1, "5")`(시작월 409), `rule(1, start.getMonthValue(), "4")`, `assertEquals(0, cycles.ledger(admin, year)…signum())`을 `days("15", cycles.ledger(admin, year).availableDays())`로, `rule(2, 2, "0")`.
- `retroactiveGrants…`: `save("1")`.
- `retryReturnsTheOriginallySavedRule…`: `first = rule(0,1,"2.5")`, `days("15", receipt.grantDays())`, `cycles.saveRule(other, rule(1,1,"1"))`.
- `staleSource…`: `save("1")`, `cycles.saveRule(admin, rule(1, start.getMonthValue(), "2"))`, `member(tenant, "추가", false, start.minusYears(2))`, 마지막 `days("17", cycles.ledger(worker, year).availableDays())`(15 + 이월 2).
- `duplicateConcurrentPosts…`: `save("2.5")`.
- `invalidRulesAndFutureYears…`: 수량 반복은 `status(422, () -> cycles.saveRule(admin, rule(0,1,quantity)))` 한 줄만 남기고, `rule(0,13,"0")`, `save("0")`, `rule(1,today.getMonthValue()+1,"0")`.
- `aLedgerFailure…`: `save("1")`. `negativeImported…`: `save("1")`. `annualTables…`, `movementReferences…`: `save("0")`.

`EmploymentAccrualIT`:
- :141 `new AnnualLeaveCycleData.RuleForm(0L,"FISCAL",joined.getMonthValue(),BigDecimal.ONE,"회계연도 기준",UUID.randomUUID())`, 마지막 단언을 `annual.preview(admin,joined.getYear()+1)`로 바꾼다(회계연도 시작일 입사자는 그해 대상이 아니다. 다음 해에 월 연차 묶음이 막지 않는지 본다).
- :156-157 `RuleForm(0L,"FISCAL",3,BigDecimal.ONE,…)`, `RuleForm(0L,"FISCAL",1,BigDecimal.ONE,…)`.

`AnnualLeaveCycleWebIT`:
- 세 기준 JSON(`policy`, `revision`, `rule`)의 `"grantDays", …`를 `"basis", "FISCAL"`로 바꾼다(`FAIL_ON_UNKNOWN_PROPERTIES`라 남기면 400).
- 기대값 `12.5` → `15.0`(행 부여, 직원 잔여, `registeredDays`, `currentAvailableDays`). 개정 JSON은 `"basis","FISCAL"`과 `"carryLimitDays", 2`.

`LeaveWorkflowIT`:
- :214 `new RuleForm(0L,"FISCAL",joined.getMonthValue(),BigDecimal.ONE,"합성 회계연도",UUID.randomUUID())`.
- `annualBucket`: 반영 뒤 원하는 잔여가 되도록 조정한다(부여 일수는 이제 법정 일수다).

```java
    private UUID annualBucket(String grant,String cap) {
        annualCycles.saveRule(manager,new RuleForm(0L,"FISCAL",today.getMonthValue(),new BigDecimal(cap),"합성 연차 규칙",UUID.randomUUID()));
        var preview=annualCycles.preview(manager,today.getYear());
        annualCycles.post(manager,new PostForm(preview.year(),preview.token(),"연차 확정",UUID.randomUUID()));
        var granted=annualCycles.ledger(employee,null).entries().stream().filter(e->e.entryKind().equals("GRANT")).findFirst().orElseThrow();
        long version=db.queryForObject("SELECT version FROM tna.leave_bucket WHERE tenant_id=? AND id=?",Long.class,tenant,granted.bucketId());
        workflow.adjust(manager,granted.bucketId(),new AdjustmentForm(new BigDecimal(grant).subtract(granted.signedQuantityDays()),today,version,
                "합성 시험 잔여","시험 수량 맞춤",UUID.randomUUID()));
        return granted.bucketId();
    }
```

- `postExpiryCancellationBeforeSettlementIsKeptInTheOriginalBucketForReview`: 다음 회계연도 반영을 빼고 미리보기까지만 본다(설계와 코드 대조 11). `assertStatus(409,…)` 줄부터 메서드 끝까지를 아래로 바꾼다. 반영하지 않으므로 옛 묶음 잔여는 부여 1 − 사용 0.5 + 만료 뒤 복원 0.5 = 1이다.

```java
        assertStatus(409,()->futureCycles.post(manager,new PostForm(earlier.year(),earlier.token(),"변경 전 미리보기",UUID.randomUUID())));
        var reviewed=futureCycles.preview(manager,boundary.getYear());
        var employeeRow=reviewed.rows().stream().filter(r->r.employeeId().equals(employee.employeeId())).findFirst().orElseThrow();
        assertEquals(0,new BigDecimal("0.5").compareTo(employeeRow.carryDays()));
        assertEquals(1.0,balance(oldBucket));
        var ledger=futureCycles.ledger(employee,boundary.getYear());
        assertTrue(ledger.entries().stream().anyMatch(e->e.bucketId().equals(oldBucket) && e.entryKind().equals("RESTORE")
                && e.effectiveOn().equals(boundary) && e.entitlementYear()==today.getYear()));
```

- `pendingDeductionOrCancellationDefersOnlyItsEmployeeUntilTheDecision`: 다른 사원 반영과 `canPost` 단언을 빼고 대기·이월만 본다.

```java
    @Test void pendingDeductionOrCancellationDefersOnlyItsEmployeeUntilTheDecision() {
        UUID source=annualBucket("0.5","2");
        LocalDate boundary=today.withDayOfMonth(1).plusYears(1);
        var zone=ZoneId.of("Asia/Seoul");
        var futureAdmin=new OrgCommands(tenantTransactions,appSource,Clock.fixed(boundary.atStartOfDay(zone).toInstant(),zone));
        var future=new AnnualLeaveCycleService(annualCycleMapper,workflowMapper,attributionMapper,futureAdmin,businessCommands);
        int year=boundary.getYear();
        var before=future.preview(manager,year);
        UUID pending=workflow.submitLeave(employee,new LeaveForm(source,date,date,null,"전기 미결재 반차",UUID.randomUUID(),"AM"));
        assertStatus(409,()->future.post(manager,new PostForm(year,before.token(),"결재 전 확인",UUID.randomUUID())));
        var held=future.preview(manager,year);
        assertEquals(1,held.pendingCount());
        assertTrue(held.rows().stream().noneMatch(r->r.employeeId().equals(employee.employeeId())));
        workflow.withdraw(employee,pending,new DecisionForm("WITHDRAW",0L,"대기 철회",UUID.randomUUID()));
        assertTrue(future.preview(manager,year).rows().stream().anyMatch(r->r.employeeId().equals(employee.employeeId())));
        UUID used=workflow.submitLeave(employee,new LeaveForm(source,date,date,null,"전기 사용 반차",UUID.randomUUID(),"AM"));
        approve(used);
        UUID cancel=workflow.requestCancellation(employee,used,new CancelForm(false,1L,"전기 취소 심사",UUID.randomUUID()));
        assertEquals(1,future.preview(manager,year).pendingCount());
        approve(cancel);
        var reviewed=future.preview(manager,year);
        assertEquals(0,reviewed.pendingCount());
        var row=reviewed.rows().stream().filter(r->r.employeeId().equals(employee.employeeId())).findFirst().orElseThrow();
        assertEquals(0,new BigDecimal("0.5").compareTo(row.carryDays()));
    }
```

(Task 5에서 `AnnualLeaveCycleService` 생성자에 `worktime`이 추가되면 이 두 시험의 `new AnnualLeaveCycleService(...)` 끝에 `worktime`을 넘긴다.)

- [ ] **Step 5: 화면의 기준 폼** — 부여 일수 입력을 없애고 기준 선택을 둔다.

`annualLeaveTypes.ts`:

```ts
export type AnnualLeaveBasis = 'FISCAL' | 'HIRE_DATE';
export const basisNames: Record<AnnualLeaveBasis, string> = { FISCAL: '회계연도 기준', HIRE_DATE: '입사일 기준' };

export interface AnnualLeaveRule {
  version: number;
  basis: AnnualLeaveBasis;
  fiscalStartMonth: number;
  grantDays: number;
  carryLimitDays: number;
}

export interface PolicyCommand {
  expectedVersion: number;
  basis: AnnualLeaveBasis;
  fiscalStartMonth: number | null;
  carryLimitDays: number;
  reason: string;
  commandId: string;
}
```

`AnnualLeaveCycle.tsx`:

```tsx
const draftRule = (rule: AnnualLeaveRule | null) => ({
  basis: (rule?.basis ?? 'FISCAL') as AnnualLeaveBasis, fiscalStartMonth: String(rule?.fiscalStartMonth ?? ''),
  carryLimitDays: String(rule?.carryLimitDays ?? ''),
});
const isRule = (rule: AnnualLeaveRule) => rule && Number.isSafeInteger(rule.version) && rule.version > 0
  && (rule.basis === 'FISCAL' || rule.basis === 'HIRE_DATE')
  && Number.isInteger(rule.fiscalStartMonth) && rule.fiscalStartMonth >= 1 && rule.fiscalStartMonth <= 12
  && isDays(rule.grantDays) && isDays(rule.carryLimitDays);
```

- `ruleChanged`를 바꾼다:

```tsx
  const ruleChanged = !rule || draft.basis !== rule.basis || draft.carryLimitDays === '' || Number(draft.carryLimitDays) !== rule.carryLimitDays
    || (draft.basis === 'FISCAL' && (draft.fiscalStartMonth === '' || Number(draft.fiscalStartMonth) !== rule.fiscalStartMonth));
```

- `changeRule(key: keyof typeof draft, value: string)`의 `setDraft`는 `{ ...current, [key]: value }` 그대로 둔다.
- 저장 명령: `{ expectedVersion: rule?.version ?? 0, basis: draft.basis, fiscalStartMonth: draft.basis === 'FISCAL' ? Number(draft.fiscalStartMonth) : null, carryLimitDays: Number(draft.carryLimitDays), reason: checkedPolicyReason.trimmed, commandId: crypto.randomUUID() }`.
- 폼 안내 문장을 "연차 일수는 근로기준법에 따라 사원별로 계산합니다. 1·2년차 15일, 3년 이상 근속하면 2년마다 1일을 더하고 최대 25일입니다. 이월 한도는 사원 한 명 기준 0.5일 단위로 입력합니다."로 바꾼다. 미설정 안내는 "회사의 연차 부여 기준과 이월 한도를 먼저 설정하세요."
- '연간 부여 일수' 행을 지우고 첫 행에 기준 선택을 넣는다. 회계 시작월 행은 `draft.basis === 'FISCAL'`일 때만 보인다.

```tsx
            <FormRow label="연차 부여 기준" htmlFor="annual-basis" required>
              <select id="annual-basis" className="t-select" required value={draft.basis} onChange={event => changeRule('basis', event.target.value)}>
                {(Object.keys(basisNames) as AnnualLeaveBasis[]).map(basis => <option key={basis} value={basis}>{basisNames[basis]}</option>)}
              </select>
            </FormRow>
            {draft.basis === 'FISCAL' && <FormRow label="회계연도 시작 월" htmlFor="annual-start-month" required>…기존 select 그대로…</FormRow>}
```

- `PreviewResults`의 기준 줄: `기준 버전 {result.rule.version} · {result.rule.basis === 'HIRE_DATE' ? '입사일 기준' : `매년 ${result.rule.fiscalStartMonth}월 1일 시작`} · 이월 한도 {result.rule.carryLimitDays}일`(부여 일수 표시는 뺀다).

`AnnualLeavePages.test.tsx`:
- `const policy = { version: 1, basis: 'FISCAL' as const, fiscalStartMonth: 1, grantDays: 15, carryLimitDays: 3 };`
- 첫 시험: `연간 부여 일수` 관련 줄을 지우고 `expect(screen.queryByLabelText('연간 부여 일수')).toBeNull(); expect(field('연차 부여 기준').value).toBe('FISCAL');`. 저장 본문 기대값 `{ basis: 'FISCAL', fiscalStartMonth: 1, carryLimitDays: 3, expectedVersion: 0, reason: '사내 휴가 규정 개정', commandId: expect.any(String) }`.
- `it.each` 시작월 시험: `if (!oldMonth) { edit('이월 한도 일수', '3'); }`.
- 500자 시험: `edit('연간 부여 일수', '12.5')` → `edit('이월 한도 일수', '3.5')`.
- '처리한 연도…' 시험: `show({ policy: { ...policy, version: 2, carryLimitDays: 4 } })`, `expect(field('이월 한도 일수').value).toBe('4')`, `'부여 12일'` 단언을 `toContain('매년 1월 1일 시작')`으로 바꾼다.
- 새 시험:

```tsx
it('입사일 기준을 고르면 회계 시작월 없이 저장하고 기준 변경 금지 안내를 보여준다', async () => {
  const send = fetches().mockResolvedValueOnce(json({ message: '연간 부여나 월별 연차를 기록한 회사는 연차 기준을 바꿀 수 없습니다. 이월 한도는 변경할 수 있습니다.' }, 409));
  show();
  edit('연차 부여 기준', 'HIRE_DATE');
  expect(screen.queryByLabelText('회계연도 시작 월')).toBeNull();
  edit('기준 설정 사유', '입사일 기준 전환');
  fireEvent.click(button('기준 저장'));
  expect((await screen.findByRole('alert')).textContent).toContain('연차 기준을 바꿀 수 없습니다');
  expect(JSON.parse(String(send.mock.calls[0][1]?.body))).toMatchObject({ basis: 'HIRE_DATE', fiscalStartMonth: null, carryLimitDays: 3, expectedVersion: 1 });
  expect(field('연차 부여 기준').value).toBe('HIRE_DATE');
});
```

- [ ] **Step 6: 통과 확인** — `-Dit.test=AnnualLeaveCycleIT,AnnualLeaveCycleWebIT,EmploymentAccrualIT,LeaveWorkflowIT,PublicDemoSecurityIT` → PASS. `cd frontend; npx vitest run src/pages/AnnualLeavePages.test.tsx src/pages/EmploymentLeave.test.tsx; npm run build` → PASS.

- [ ] **Step 7: 커밋** — `feat: let companies choose the annual leave basis and store statutory base days`

---

### Task 4: 출근율 근거 (결근 일수, 판정 불가, 1년 범위)

**Files:**
- Modify: `src/main/java/kr/tna/worktime/WorktimeService.java:152-160` (`leaveAttendanceDays`), `:588-597` (`readMonthDays`의 근거 계산)
- Create: `src/test/java/kr/tna/AttendanceSeed.java`
- Modify: `src/test/java/kr/tna/EmploymentAccrualIT.java` (시험 추가)

- [ ] **Step 1: 시험 시드 도우미** — B 묶음의 실제 경로(배정 근무조, 달력, 출퇴근, 자동 확인, `calculateDatesAsSystem`)로 결과를 만든다. `DailyAttendanceBatchIT`의 일정·출퇴근 SQL과 같다.

```java
package kr.tna;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.worktime.WorktimeService;
import org.springframework.jdbc.core.JdbcTemplate;

/** 연차 출근율 시험용 근태. from~through 회사 달력은 workdays만 근로일이고 나머지는 비근로일이다. 일정은 매일 09~18시(휴게 12~13시)다. 회사마다 한 번만 만든다. */
final class AttendanceSeed {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate db;
    private final TenantTransactions transactions;
    private final ClockService clocks;
    private final WorktimeService worktime;
    private final TnaPrincipal admin;
    private final LocalDate from, through;
    private final List<LocalDate> workdays;
    private final UUID schedule = UUID.randomUUID(), network = UUID.randomUUID();

    private AttendanceSeed(JdbcTemplate db, TenantTransactions transactions, ClockService clocks, WorktimeService worktime,
            TnaPrincipal admin, LocalDate from, LocalDate through, List<LocalDate> workdays) {
        this.db = db; this.transactions = transactions; this.clocks = clocks; this.worktime = worktime;
        this.admin = admin; this.from = from; this.through = through; this.workdays = workdays;
    }

    static AttendanceSeed calendar(JdbcTemplate db, TenantTransactions transactions, ClockService clocks, WorktimeService worktime,
            TnaPrincipal admin, LocalDate from, LocalDate through, LocalDate... workdays) {
        var seed = new AttendanceSeed(db, transactions, clocks, worktime, admin, from, through, List.of(workdays));
        UUID tenant = admin.tenantId(), account = admin.accountId(), template = UUID.randomUUID(), calendar = UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 출근율 일정',?,?)",
                tenant, template, template.toString(), account, account);
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'합성 출근율 일정',?)",
                tenant, seed.schedule, template, from, account);
        for (int day = 1; day <= 7; day++) {
            db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time) VALUES (?,?,?,true,TIME '09:00',TIME '18:00')",
                    tenant, seed.schedule, day);
            db.update("INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time) VALUES (?,?,?,1,TIME '12:00',TIME '13:00')",
                    tenant, seed.schedule, day);
        }
        db.update("""
                INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by)
                SELECT ?,?,COALESCE(max(revision),0)+1,?,?,'합성 출근율 달력',? FROM tna.calendar_version WHERE tenant_id=?
                """, tenant, calendar, from, through.plusDays(1), account, tenant);
        db.update("""
                INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis)
                SELECT ?,?,d::date,'NON_WORKDAY','합성 비근로일','합성 출근율 근거' FROM generate_series(CAST(? AS date),CAST(? AS date),interval '1 day') d
                """, tenant, calendar, from, through);
        for (var day : workdays)
            db.update("UPDATE tna.calendar_day SET kind='WORKDAY',name='합성 근로일' WHERE tenant_id=? AND version_id=? AND work_date=?", tenant, calendar, day);
        db.update("INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by) VALUES (?,?,CAST('127.0.0.1/32' AS cidr),'합성 출근율',?,'합성 근거',?)",
                tenant, seed.network, java.sql.Timestamp.from(from.atStartOfDay(SEOUL).toInstant()), account);
        return seed;
    }

    List<LocalDate> workdays() { return workdays; }

    /** 사원을 이 일정에 배정하고, 재직 중인 근로일 중 absent가 아닌 날에 출퇴근을 넣은 뒤 calculated 날짜만 계산한다. */
    void employee(UUID employee, Set<LocalDate> absent, Collection<LocalDate> calculated) {
        UUID tenant = admin.tenantId(), account = admin.accountId();
        LocalDate joined = db.queryForObject("SELECT joined_on FROM tna.employee WHERE tenant_id=? AND id=?", LocalDate.class, tenant, employee);
        db.update("""
                INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,valid_to,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,?,'합성 출근율 배정',?,?)""",
                tenant, UUID.randomUUID(), employee, schedule, joined.isAfter(from) ? joined : from, through.plusDays(1), account, account);
        for (var day : workdays) {
            if (day.isBefore(joined) || absent.contains(day)) continue;
            for (String kind : List.of("IN", "OUT")) {
                var at = java.sql.Timestamp.from(day.atTime(kind.equals("IN") ? 9 : 18, 0).atZone(SEOUL).toInstant());
                db.update("""
                        INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,
                            source,validated_ip,network_id,assigned_work_date) VALUES (?,?,?,?,?,?,?,'IP',CAST('127.0.0.1' AS inet),?,?)
                        """, tenant, UUID.randomUUID(), employee, kind, at, at, UUID.randomUUID(), network, day);
            }
            db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) VALUES (?,?,?,2,0)",
                    tenant, employee, day);
        }
        var dates = calculated.stream().filter(day -> !day.isBefore(joined)).toList();
        if (dates.isEmpty()) return;
        transactions.execute(tenant, () -> {
            for (var day : dates) clocks.autoConfirm(tenant, employee, day, null);
            return worktime.calculateDatesAsSystem(tenant, null, employee, dates);
        });
    }

    void attend(UUID employee) { employee(employee, Set.of(), workdays); }
}
```

- [ ] **Step 2: 실패하는 시험** — `EmploymentAccrualIT`에 추가한다(고정 시계 2026-11-22).

```java
    @Test void attendanceEvidenceCountsAbsenceAndUnconfirmedWorkdaysOverMoreThanAMonth() {
        LocalDate from = LocalDate.of(2026,1,5), through = today.minusDays(1);
        LocalDate first = LocalDate.of(2026,2,2), absent = LocalDate.of(2026,5,4), open = LocalDate.of(2026,9,7);
        var person = member("W09", false, from);
        AttendanceSeed.calendar(db, transactions, clocks, worktime, admin, from, through, first, absent, open)
                .employee(person.employeeId(), Set.of(absent), List.of(first, absent));
        var days = worktime.leaveAttendanceDays(admin, person.employeeId(), from, through);
        var evidence = StatutoryLeave.Evidence.of(days);
        assertEquals(3, evidence.scheduledDays());
        assertEquals(0, new BigDecimal("1").compareTo(evidence.absenceDays()));
        assertEquals(1, evidence.unresolvedDays());
        var absence = days.stream().filter(day -> day.date().equals(absent)).findFirst().orElseThrow();
        assertFalse(absence.unresolved(), "결근으로 확정된 날은 판정 불가가 아닙니다.");
        assertTrue(days.stream().filter(day -> day.date().equals(open)).findFirst().orElseThrow().unresolved());
        assertEquals(422, failure(() -> worktime.leaveAttendanceDays(admin, person.employeeId(), from.minusDays(60), through)));
    }
```

`import kr.tna.request.StatutoryLeave;`는 `kr.tna.request.*`로 이미 들어온다.

- [ ] **Step 3: 실패 확인** — `-Dit.test=EmploymentAccrualIT` → 33일 상한으로 422 또는 결근 0으로 FAIL.

- [ ] **Step 4: 구현** — `WorktimeService.leaveAttendanceDays`:

```java
    /** 월 연차·연간 출근율 판정: 회사 결과와 같은 배정 근무조·승인 휴가·결과 최신성을 쓴다. monthDays가 42일 상한이라 31일씩 읽는다. */
    public List<LeaveAttendanceDay> leaveAttendanceDays(TnaPrincipal actor, UUID employeeId, LocalDate from, LocalDate through) {
        if (from == null || through == null || through.isBefore(from) || ChronoUnit.DAYS.between(from, through) > 366)
            throw OrgException.invalid("연차 산정 기간을 확인해 주세요.");
        return administration.readShared(actor, () -> {
            var person = employee(actor.tenantId(), employeeId);
            var attendance = new ArrayList<LeaveAttendanceDay>();
            for (LocalDate start = from; !start.isAfter(through); start = start.plusDays(31)) {
                LocalDate end = start.plusDays(30).isAfter(through) ? through : start.plusDays(30);
                readMonthDays(actor.tenantId(), person, start, end, attendance);
            }
            return List.copyOf(attendance);
        });
    }
```

`readMonthDays`의 `if (attendance != null)` 블록:

```java
            if (attendance != null) {
                boolean known = snapshot.schedule() != null && snapshot.calendarVersionId() != null
                        && !"UNCONFIRMED".equals(snapshot.dayKind());
                boolean scheduled = known && snapshot.schedule().workday() && "WORKDAY".equals(snapshot.dayKind());
                boolean attended = scheduled && day.current() && pending.isEmpty()
                        && day.result().actualWorkMillis() != null && day.result().actualWorkMillis() > 0;
                // 판정 불가는 최신 결과가 없거나 최신이 아닌 소정근로일이다. 결근으로 확정된 날은 판정한 날이다(설계 2026-11-27 §4).
                boolean resolved = day.current() && day.result().actualWorkMillis() != null;
                boolean unresolved = !known || scheduled && (!pending.isEmpty() || day.approvedLeaveDays() < 1 && !resolved);
                String absence = scheduled && day.current() ? day.result().attendance().absence() : null;
                double absenceDays = "FULL".equals(absence) ? 1 : "HALF".equals(absence) ? 0.5 : 0;
                attendance.add(new LeaveAttendanceDay(date, scheduled, attended, scheduled && day.approvedLeaveDays() > 0,
                        unresolved, absenceDays, source, day.id(), day.resultRevision()));
            }
```

- [ ] **Step 5: 통과 확인** — `-Dit.test=EmploymentAccrualIT,LeaveExitServiceIT,DailyAttendanceResultIT` → PASS.

- [ ] **Step 6: 커밋** — `feat: count absence days and confirm only stale workdays as undetermined in leave attendance`

---

### Task 5: 회계연도 기준 연간 부여 (사원별 법정 일수와 출근율 판정)

**Files:**
- Modify: `src/main/java/kr/tna/request/AnnualLeaveCycleData.java:246-250` (`Row`, `Preview`)
- Modify: `src/main/java/kr/tna/request/AnnualLeaveCycleMapper.java:84` (`joined_on<`), `:122-127` (`insertAward`)
- Modify: `src/main/java/kr/tna/request/AnnualLeaveCycleService.java` (생성자, `post`, `plan`, `postAward`)
- Create: `src/test/java/kr/tna/StatutoryAnnualLeaveIT.java`
- Modify: `src/test/java/kr/tna/AnnualLeaveCycleIT.java`, `AnnualLeaveCycleWebIT.java`, `LeaveWorkflowIT.java` (근태 시드, 생성자 인자)

- [ ] **Step 1: 실패하는 시험** — 2026-03-10 고정 시계.

```java
package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.OrgException;
import kr.tna.request.AnnualLeaveCycleData.*;
import kr.tna.request.AnnualLeaveCycleService;
import kr.tna.request.LeaveAttributionService;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.worktime.WorktimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 법정 연차 연간 부여(설계 2026-11-27 §3·§4.1·§5). 2026-03-10 서울 기준으로 고정한다. */
@SpringBootTest
class StatutoryAnnualLeaveIT {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    @TestConfiguration(proxyBeanMethods = false)
    static class Dates {
        @Bean @Primary Clock statutoryLeaveClock() { return Clock.fixed(TODAY.atTime(10, 0).atZone(SEOUL).toInstant(), SEOUL); }
    }
    @Autowired AnnualLeaveCycleService cycles;
    @Autowired LeaveWorkflowService workflow;
    @Autowired LeaveAttributionService attributions;
    @Autowired TenantTransactions transactions;
    @Autowired ClockService clocks;
    @Autowired WorktimeService worktime;
    JdbcTemplate db;
    UUID tenant;
    TnaPrincipal admin;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var fixture = DatabaseFixture.start();
        p.add("spring.datasource.url", fixture::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", fixture::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void seed() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = company();
        admin = member(tenant, "H01", true, LocalDate.of(2020, 1, 1));
    }

    record Fiscal(TnaPrincipal a, TnaPrincipal b, TnaPrincipal c, TnaPrincipal e, AttendanceSeed seed) { }

    /** 1월 시작 회사. A 2025-07-01 입사(비례), B 근속 3년차, C 근태 없음, D 회계연도 시작일 입사, E 출근율 50%. */
    private Fiscal fiscal() {
        cycles.saveRule(admin, new RuleForm(0L, "FISCAL", 1, new BigDecimal("5"), "회계연도 기준", UUID.randomUUID()));
        var a = member(tenant, "A01", false, LocalDate.of(2025, 7, 1));
        var b = member(tenant, "B01", false, LocalDate.of(2023, 1, 1));
        var c = member(tenant, "C01", false, LocalDate.of(2020, 1, 2));
        member(tenant, "D01", false, LocalDate.of(2026, 1, 1));
        var e = member(tenant, "E01", false, LocalDate.of(2024, 3, 4));
        var seed = AttendanceSeed.calendar(db, transactions, clocks, worktime, admin, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                LocalDate.of(2025, 3, 3), LocalDate.of(2025, 9, 1));
        seed.attend(a.employeeId());
        seed.attend(b.employeeId());
        seed.employee(e.employeeId(), Set.of(LocalDate.of(2025, 3, 3)), seed.workdays());
        return new Fiscal(a, b, c, e, seed);
    }

    @Test void fiscalPreviewProratesTheFirstYearAddsSeniorityAndHoldsBackUnconfirmedOrLowAttendance() {
        fiscal();
        var preview = cycles.preview(admin, 2026);
        assertEquals(LocalDate.of(2026, 1, 1), preview.periodStart());
        assertEquals(List.of("A01", "B01", "C01", "E01"), preview.rows().stream().map(Row::employeeNo).toList());
        var a = preview.rows().get(0);
        assertEquals(1, a.serviceYear());
        days("8", a.grantDays());
        assertEquals(LocalDate.of(2025, 7, 1), a.periodStart());
        assertEquals(LocalDate.of(2025, 12, 31), a.periodEnd());
        assertEquals(1, a.scheduledDays());
        assertEquals("GRANT", a.decision());
        days("100", a.attendanceRate());
        var b = preview.rows().get(1);
        assertEquals(3, b.serviceYear());
        days("16", b.grantDays());
        assertEquals(2, b.scheduledDays());
        assertEquals("GRANT", b.decision());
        var c = preview.rows().get(2);
        assertEquals(6, c.serviceYear());
        days("17", c.grantDays());
        assertEquals("NEEDS_REVIEW", c.decision());
        assertTrue(c.reason().startsWith("근태 확정 전 "), c.reason());
        var e = preview.rows().get(3);
        assertEquals(2, e.serviceYear());
        days("15", e.grantDays());
        days("1", e.absenceDays());
        assertEquals("NEEDS_REVIEW", e.decision());
        assertEquals("출근율 50.0%", e.reason());
        assertEquals(2, preview.reviewCount());
        assertTrue(preview.canPost());
    }

    @Test void postingRecordsOnlyGrantRowsWithTheirEvidence() {
        var people = fiscal();
        cycles.post(admin, post(cycles.preview(admin, 2026)));
        assertEquals(2, count("annual_leave_award"));
        var award = db.queryForMap("""
                SELECT service_year,period_start,period_end,scheduled_days,absence_days,granted_days FROM tna.annual_leave_award
                WHERE tenant_id=? AND employee_id=?""", tenant, people.a().employeeId());
        assertEquals(1, award.get("service_year"));
        assertEquals(java.sql.Date.valueOf("2025-07-01"), award.get("period_start"));
        assertEquals(java.sql.Date.valueOf("2025-12-31"), award.get("period_end"));
        assertEquals(1, award.get("scheduled_days"));
        days("0", (BigDecimal) award.get("absence_days"));
        days("8", (BigDecimal) award.get("granted_days"));
        days("8", cycles.ledger(people.a(), 2026).availableDays());
        days("16", cycles.ledger(people.b(), 2026).availableDays());
        assertEquals(Boolean.FALSE, db.queryForObject("SELECT reviewed_by_admin FROM tna.annual_leave_award WHERE tenant_id=? AND employee_id=?",
                Boolean.class, tenant, people.a().employeeId()));
        var after = cycles.preview(admin, 2026);
        assertEquals(2, after.postedCount());
        assertEquals(List.of("C01", "E01"), after.rows().stream().map(Row::employeeNo).toList());
        assertTrue(after.canPost());
        status(422, () -> cycles.post(admin, post(after)));   // 확인 필요 행을 고르지 않으면 반영할 사원이 없다
    }

    @Test void reviewedRowsArePostedWithStatutoryDaysOnlyWhenTheAdministratorChecksThem() {
        var people = fiscal();
        cycles.post(admin, post(cycles.preview(admin, 2026), people.e().employeeId()));
        assertEquals(3, count("annual_leave_award"));
        var award = db.queryForMap("""
                SELECT reviewed_by_admin,service_year,scheduled_days,absence_days,granted_days FROM tna.annual_leave_award
                WHERE tenant_id=? AND employee_id=?""", tenant, people.e().employeeId());
        assertEquals(true, award.get("reviewed_by_admin"));
        assertEquals(2, award.get("service_year"));
        assertEquals(2, award.get("scheduled_days"));
        days("1", (BigDecimal) award.get("absence_days"));
        days("15", (BigDecimal) award.get("granted_days"));
        var ledger = cycles.ledger(people.e(), 2026);
        days("15", ledger.availableDays());
        assertTrue(ledger.entries().stream().anyMatch(e -> "GRANT".equals(e.entryKind())
                && e.sourceReference().contains("담당자 확인(출근율 50.0%)")));
        var after = cycles.preview(admin, 2026);
        assertEquals(List.of("C01"), after.rows().stream().map(Row::employeeNo).toList());
        status(409, () -> cycles.post(admin, post(after, people.a().employeeId())));   // 이미 반영한 사원
        status(409, () -> cycles.post(admin, post(after, UUID.randomUUID())));         // 미리보기에 없는 사원
        status(422, () -> cycles.post(admin, new PostForm(2026, after.token(), "빈 값", UUID.randomUUID(),
                java.util.Arrays.asList((UUID) null))));
        cycles.post(admin, post(after, people.c().employeeId()));   // 근태가 없는 사원도 담당자가 확인하면 법정 일수
        days("17", cycles.ledger(people.c(), 2026).availableDays());
        assertTrue(cycles.preview(admin, 2026).rows().isEmpty());
    }

    @Test void attendanceConfirmedAfterThePreviewRejectsThePostAndThenGrants() {
        var people = fiscal();
        var before = cycles.preview(admin, 2026);
        people.seed().attend(people.c().employeeId());
        status(409, () -> cycles.post(admin, post(before)));
        var latest = cycles.preview(admin, 2026);
        assertEquals("GRANT", latest.rows().get(2).decision());
        cycles.post(admin, post(latest));
        assertEquals(3, count("annual_leave_award"));
    }

    @Test void anotherCompanyOrAnEmployeeCannotPreviewOrPostTheseAwards() {
        var people = fiscal();
        var preview = cycles.preview(admin, 2026);
        status(403, () -> cycles.preview(people.a(), 2026));
        status(403, () -> cycles.post(people.a(), post(preview)));
        var foreign = member(company(), "X01", true, LocalDate.of(2020, 1, 1));
        cycles.saveRule(foreign, new RuleForm(0L, "FISCAL", 1, BigDecimal.ONE, "타사 기준", UUID.randomUUID()));
        var foreignPreview = cycles.preview(foreign, 2026);
        assertTrue(foreignPreview.rows().isEmpty());
        status(409, () -> cycles.post(foreign, post(preview)));
        status(409, () -> cycles.post(foreign, post(foreignPreview, people.c().employeeId())));   // 다른 회사 사원은 고를 수 없다
        assertEquals(0, count("annual_leave_award"));
    }

    private UUID company() {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 법정 연차 회사')", id, "STAT-" + id);
        return id;
    }
    private TnaPrincipal member(UUID company, String number, boolean manager, LocalDate hired) {
        UUID employee = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)", company, employee, number, "합성 " + number, hired);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic')", company, account, employee, account + "@example.test");
        var roles = manager ? Set.of("EMPLOYEE", "ATTENDANCE_ADMIN") : Set.of("EMPLOYEE");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", company, account, role);
        return new TnaPrincipal(company, account, employee, 1, roles, Instant.now());
    }
    private int count(String table) { return db.queryForObject("SELECT count(*) FROM tna." + table + " WHERE tenant_id=?", Integer.class, tenant); }
    private static void status(int expected, Runnable action) { assertEquals(expected, assertThrows(OrgException.class, action::run).status()); }
    private static void days(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> expected + " != " + actual); }
    private static PostForm post(Preview preview, UUID... reviewed) {
        return new PostForm(preview.year(), preview.token(), "합성 법정 연차 반영", UUID.randomUUID(), List.of(reviewed));
    }
}
```

- [ ] **Step 2: 실패 확인** — `-Dit.test=StatutoryAnnualLeaveIT` → 컴파일 실패(`Row.serviceYear` 없음).

- [ ] **Step 3: 데이터·매퍼**

`AnnualLeaveCycleData.java`:

```java
    public record Row(UUID employeeId, String employeeNo, String employeeName, LocalDate joinedOn, LocalDate grantOn, int serviceYear,
            LocalDate periodStart, LocalDate periodEnd, int scheduledDays, BigDecimal absenceDays, int unresolvedDays,
            BigDecimal attendanceRate, String decision, String reason,
            BigDecimal previousDays, BigDecimal grantDays, BigDecimal carryDays, BigDecimal expireDays) { }
    public record Preview(int year, String month, LocalDate periodStart, LocalDate periodEnd, Rule rule, List<Row> rows,
            int postedCount, int existingCount, int laterCount, int pendingCount, int reviewCount, String token, boolean canPost) { }
    /** reviewedEmployeeIds: 담당자가 골라 법정 일수로 함께 반영할 확인 필요 사원. 없으면 부여 대상만 반영한다. */
    public record PostForm(Integer year, String token, String reason, UUID commandId, List<UUID> reviewedEmployeeIds) {
        public PostForm(Integer year, String token, String reason, UUID commandId) { this(year, token, reason, commandId, null); }
    }
```

`AnnualLeaveCycleMapper.java`:
- `candidates`의 `e.joined_on<=#{start}`를 `e.joined_on<#{start}`로 바꾼다(회계연도 시작일 입사자는 그해 대상이 아니다).
- `insertAward`:

```java
    @Insert("""
            INSERT INTO tna.annual_leave_award (tenant_id,entitlement_year,employee_id,bucket_id,created_by,
                service_year,period_start,period_end,scheduled_days,absence_days,granted_days,reviewed_by_admin)
            VALUES (#{tenantId},#{year},#{row.employeeId},#{bucketId},#{actor},#{row.serviceYear},#{row.periodStart},
                #{row.periodEnd},#{row.scheduledDays},#{row.absenceDays},#{row.grantDays},#{reviewed})
            """)
    int insertAward(@Param("tenantId") UUID tenantId,@Param("year") int year,@Param("row") AnnualLeaveCycleData.Row row,
            @Param("bucketId") UUID bucketId,@Param("reviewed") boolean reviewed,@Param("actor") UUID actor);
```

- [ ] **Step 4: 서비스** — `AnnualLeaveCycleService`.

생성자에 `kr.tna.worktime.WorktimeService worktime`을 마지막 인자로 더하고 필드에 저장한다. import에 `kr.tna.request.AnnualLeaveCycleMapper.Candidate`, `kr.tna.worktime.WorktimeService`를 더한다.

`post()` 전체(import `java.util.Objects`, `java.util.Set`, `java.util.TreeSet`, `java.util.stream.Collectors`):

```java
    public UUID post(TnaPrincipal actor, PostForm form) {
        if (form == null || form.year() == null) throw OrgException.invalid("반영할 연도를 입력해 주세요.");
        int year = validYear(form.year());
        if (form.token() == null || !form.token().matches("[0-9a-f]{64}"))
            throw OrgException.invalid("반영할 연도의 미리보기를 확인해 주세요.");
        String reason = requiredText(form.reason(), 500, "반영 사유");
        var reviewed = reviewed(form.reviewedEmployeeIds());
        return administration.execute(actor, form.commandId(), "ANNUAL_LEAVE_POST", "ANNUAL_LEAVE_CYCLE",
                fingerprint(year, form.token(), reason, reviewed), reason, () -> {
                    var plan = plan(actor, year);
                    var preview = plan.preview();
                    if (!form.token().equals(preview.token()))
                        throw OrgException.conflict("회사 기준, 대상 사원, 근태 또는 잔여가 바뀌었습니다. 미리보기를 다시 확인해 주세요.");
                    var selected = selected(plan, reviewed);
                    if (!preview.canPost()) throw OrgException.invalid("회계연도 시작일 이후에 반영 대상자를 확인해 주세요.");
                    if (selected.isEmpty()) throw OrgException.invalid("부여할 사원이 없습니다. 확인 필요 사원을 고르거나 근태를 확정한 뒤 다시 미리 보세요.");
                    …(cycleId, type은 그대로)…
                    String source = year + "년 회사 연차 기준 " + preview.rule().version() + "차";
                    var awards = new ArrayList<UUID>();
                    for (var award : selected) awards.add(postAward(actor, preview, award, type, source, reason));
                    return new Change(cycleId, null, Map.of("year", year, "ruleVersion", preview.rule().version(),
                            "periodStart", preview.periodStart(), "periodEnd", preview.periodEnd(), "awardBuckets", awards,
                            "postedCount", awards.size(), "reviewedEmployeeIds", List.copyOf(reviewed), "previewToken", preview.token()));
                });
    }

    private static Set<UUID> reviewed(List<UUID> ids) {
        if (ids == null) return Set.of();
        if (ids.stream().anyMatch(Objects::isNull)) throw OrgException.invalid("확인한 사원을 다시 선택해 주세요.");
        return new TreeSet<>(ids);
    }

    /** 부여 대상 행 전부와 담당자가 고른 확인 필요 행(설계 §4.1·§5). 고른 사원이 확인 필요 행이 아니면 다시 미리 보게 한다. */
    private static List<Award> selected(Plan plan, Set<UUID> reviewed) {
        var review = plan.awards().stream().filter(award -> StatutoryLeave.NEEDS_REVIEW.equals(award.row().decision()))
                .map(award -> award.row().employeeId()).collect(Collectors.toSet());
        if (!review.containsAll(reviewed))
            throw OrgException.conflict("확인한 사원이 미리보기의 확인 필요 사원과 다릅니다. 미리보기를 다시 확인해 주세요.");
        return plan.awards().stream().filter(award -> StatutoryLeave.GRANT.equals(award.row().decision())
                || reviewed.contains(award.row().employeeId())).toList();
    }
```

`plan()`과 새 `award()`·`byEmployee()` (기존 :127-180을 바꾼다):

```java
    private record Settlement(Source source, BigDecimal carry, BigDecimal expire) { }
    private record Award(Row row, List<Settlement> settlements, LocalDate usableTo) { }
    private record Plan(Preview preview, List<Award> awards) { }

    private Plan plan(TnaPrincipal actor, int year) {
        UUID tenant = actor.tenantId();
        var applied = mapper.appliedRule(tenant, year);
        var rule = applied == null ? mapper.currentRule(tenant) : applied;
        if (rule == null) return new Plan(new Preview(year, null, null, null, null, List.of(), 0, 0, 0, 0, 0, null, false), List.of());
        var start = LocalDate.of(year, rule.fiscalStartMonth(), 1);
        var end = start.plusYears(1).minusDays(1);
        var sources = byEmployee(mapper.sources(tenant, actor.employeeId(), year-1, start.minusDays(1)));
        boolean started = !start.isAfter(administration.today());
        var signatures = new ArrayList<String>();
        signatures.add(fingerprint(tenant, actor.accountId(), actor.employeeId(), year, rule.version(), rule.basis(),
                rule.fiscalStartMonth(), rule.carryLimitDays(), applied != null, started));
        int posted = 0, existing = 0, later = 0, pending = 0;
        var awards = new ArrayList<Award>();
        for (var employee : mapper.candidates(tenant, actor.employeeId(), year, start)) {
            signatures.add(fingerprint(employee.employeeId(), employee.employeeNo(), employee.employeeName(),
                    employee.version(), employee.joinedOn(), employee.leftOn(), employee.posted(), employee.confirmed(), employee.later()));
            if (employee.posted()) { posted++; continue; }
            if (employee.confirmed()) { existing++; continue; }
            if (employee.later()) { later++; continue; }
            var previousBuckets = sources.getOrDefault(employee.employeeId(), List.of());
            for (var source : previousBuckets) signatures.add(fingerprint(source.bucketId(), source.version(),
                    source.revision(), source.balance(), source.closingBalance(), source.pending()));
            if (previousBuckets.stream().anyMatch(Source::pending)) { pending++; continue; }
            int serviceYear = StatutoryLeave.fiscalServiceYear(employee.joinedOn(), start, rule.fiscalStartMonth());
            var days = serviceYear == 1 ? StatutoryLeave.prorated(employee.joinedOn(), start) : StatutoryLeave.serviceDays(serviceYear);
            var periodStart = serviceYear == 1 ? employee.joinedOn() : start.minusYears(1);
            awards.add(award(actor, rule, employee, start, end, serviceYear, periodStart, days, previousBuckets, signatures));
        }
        String token = fingerprint(signatures.toArray());
        var rows = awards.stream().map(Award::row).toList();
        int review = (int) rows.stream().filter(row -> StatutoryLeave.NEEDS_REVIEW.equals(row.decision())).count();
        var preview = new Preview(year, null, start, end, rule, rows, posted, existing, later, pending, review, token,
                started && !rows.isEmpty());
        return new Plan(preview, awards);
    }

    /** 한 사원의 전기 잔여 이월·소멸과 직전 산정기간 출근율 판정을 반영 행으로 만든다(설계 §4.1). */
    private Award award(TnaPrincipal actor, Rule rule, Candidate employee, LocalDate grantOn, LocalDate usableTo, int serviceYear,
            LocalDate periodStart, BigDecimal days, List<Source> previousBuckets, List<String> signatures) {
        BigDecimal previous = ZERO, carried = ZERO;
        var settlements = new ArrayList<Settlement>();
        for (var source : previousBuckets) {
            if (source.balance().signum() < 0) throw OrgException.conflict("이월 대상 묶음에 음수 잔여가 있습니다. 해당 원장을 확인해 주세요.");
            // Credits effective after expiry stay in the original bucket for an administrator to review.
            BigDecimal eligible = source.balance().min(source.closingBalance()).max(ZERO);
            if (eligible.signum() == 0) continue;
            BigDecimal carry = eligible.min(rule.carryLimitDays().subtract(carried));
            settlements.add(new Settlement(source, carry, eligible.subtract(carry)));
            previous = previous.add(eligible);
            carried = carried.add(carry);
        }
        var periodEnd = grantOn.minusDays(1);
        var attendance = worktime.leaveAttendanceDays(actor, employee.employeeId(), periodStart, periodEnd);
        signatures.add(fingerprint(attendance.stream().map(day -> fingerprint(day)).toArray()));
        var evidence = StatutoryLeave.Evidence.of(attendance);
        var judgement = StatutoryLeave.annual(evidence);
        var row = new Row(employee.employeeId(), employee.employeeNo(), employee.employeeName(), employee.joinedOn(), grantOn,
                serviceYear, periodStart, periodEnd, evidence.scheduledDays(), evidence.absenceDays(), evidence.unresolvedDays(),
                judgement.attendanceRate(), judgement.decision(), judgement.reason(), previous, days, carried, previous.subtract(carried));
        return new Award(row, settlements, usableTo);
    }

    private static Map<UUID, List<Source>> byEmployee(List<Source> sources) {
        var grouped = new HashMap<UUID, List<Source>>();
        for (var source : sources) grouped.computeIfAbsent(source.employeeId(), key -> new ArrayList<>()).add(source);
        return grouped;
    }
```

`postAward()`는 사원별 부여일·사용기한과 근거를 쓰고, 담당자가 고른 행은 근거 문구와 `reviewed_by_admin`에 표시한다:

```java
    private UUID postAward(TnaPrincipal actor, Preview preview, Award award, UUID type, String basisSource, String reason) {
        UUID tenant = actor.tenantId(), employee = award.row().employeeId(), bucket = UUID.randomUUID();
        LocalDate start = award.row().grantOn();
        boolean reviewed = StatutoryLeave.NEEDS_REVIEW.equals(award.row().decision());
        String source = reviewed ? basisSource + " · 담당자 확인(" + award.row().reason() + ")" : basisSource;
        changed(workflow.insertBucket(tenant, bucket, new BucketForm(employee, start, start, award.usableTo(),
                award.row().grantDays(), source, reason, null), actor.accountId()));
        changed(mapper.insertAward(tenant, preview.year(), award.row(), bucket, reviewed, actor.accountId()));
        …(나머지 attribution·movement·settlement 줄은 그대로이며 모두 위의 `source`를 쓴다)…
    }
```

- [ ] **Step 5: 기존 시험에 근태 시드를 넣는다** (출근율 판정이 생겨 시드가 없으면 '확인 필요'로 반영되지 않는다)

`AnnualLeaveCycleIT`: `@Autowired ClockService clocks; @Autowired WorktimeService worktime;`와 필드 `AttendanceSeed attendance;`를 더한다.

```java
    @BeforeEach void seed() {
        …(기존 줄)…
        // 근속 2년차 직전 회계연도와 1년차(입사~입사 회계연도 말일) 산정기간에 근로일을 하루씩 둔다.
        attendance = AttendanceSeed.calendar(db, transactions, clocks, worktime, admin, start.minusYears(2), start.minusDays(1),
                monday(start.minusYears(2)), monday(start.minusYears(1)));
        attendance.attend(admin.employeeId());
        attendance.attend(worker.employeeId());
    }
    private static LocalDate monday(LocalDate date) { return date.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY)); }
```

- `policyRevisions…`: `other`를 만든 직후 `attendance.attend(other.employeeId());`.
- `retroactiveGrants…`: `lateHireRecord`를 만든 직후 `attendance.attend(lateHireRecord.employeeId());`.
- `staleSource…`: 마지막 `assertFalse(cycles.preview(admin, year).canPost())`를 `assertEquals(List.of("NEEDS_REVIEW"), cycles.preview(admin, year).rows().stream().map(Row::decision).toList())`로 바꾼다('추가' 사원은 시드가 없어 확인 필요 행으로 남고, 행이 있으면 `canPost`가 참이다).
- 다른 시험은 Task 3의 값 그대로 통과한다.

`AnnualLeaveCycleWebIT`: `@Autowired TenantTransactions transactions; @Autowired ClockService clocks; @Autowired WorktimeService worktime;`를 더하고 `company()` 끝에:

```java
        int last = today.getYear() - 1;
        var monday = LocalDate.of(last, 1, 1).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
        AttendanceSeed.calendar(db, transactions, clocks, worktime, admin, LocalDate.of(last, 1, 1), LocalDate.of(last, 12, 31), monday)
                .attend(employee.employeeId());
```

`LeaveWorkflowIT`:
- 두 시험의 `new AnnualLeaveCycleService(annualCycleMapper,workflowMapper,attributionMapper,futureAdmin,businessCommands)` 끝에 `,worktime`을 더한다.
- `annualBucket`의 `saveRule` 다음에 시드를 넣는다(입사일~회계연도 시작 전날 산정기간):

```java
        LocalDate start=today.withDayOfMonth(1);
        LocalDate joined=db.queryForObject("SELECT joined_on FROM tna.employee WHERE tenant_id=? AND id=?",LocalDate.class,tenant,employee.employeeId());
        AttendanceSeed.calendar(db,tenantTransactions,clocks,worktime,manager,joined,start.minusDays(1),
                joined.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY))).attend(employee.employeeId());
```

(`joined`는 `today.minusYears(1)` 또는 `today.withDayOfMonth(1).minusMonths(2)`라 다음 월요일이 회계연도 시작일보다 앞선다. 기존 달력 `today~today+30`과 겹치지 않는다.)

- [ ] **Step 6: 통과 확인** — `-Dit.test=StatutoryAnnualLeaveIT,AnnualLeaveCycleIT,AnnualLeaveCycleWebIT,LeaveWorkflowIT,EmploymentAccrualIT,PublicDemoSecurityIT` → PASS. `cd frontend; npx vitest run src/pages/AnnualLeavePages.test.tsx` → PASS(화면은 기존 열만 읽는다).

- [ ] **Step 7: 커밋** — `feat: grant fiscal-year annual leave by statutory service years and attendance rate`

---

### Task 6: 입사일 기준 월별 부여와 개인 원장 기간

**Files:**
- Modify: `src/main/java/kr/tna/request/AnnualLeaveCycleData.java` (`PostForm`, `Ledger`)
- Modify: `src/main/java/kr/tna/request/AnnualLeaveCycleMapper.java` (`joinedOn` 추가)
- Modify: `src/main/java/kr/tna/request/AnnualLeaveCycleService.java` (`preview`, `previewMonth`, `month`, `post`, `plan`, `ledger`)
- Modify: `src/main/java/kr/tna/request/AnnualLeavePostingController.java` (`/preview` 본문), `AnnualLeaveCycleController.java:205-207` (모델 `month`)
- Modify: `src/main/java/kr/tna/common/web/ReactPageViewResolver.java:78` (`"month"`)
- Modify: `src/test/java/kr/tna/StatutoryAnnualLeaveIT.java`, `AnnualLeaveCycleWebIT.java`

바꾸지 않는 것: 월 연차의 사용기한(`accrualOn(joined,12)-1`)과 귀속연도(`currentYear`)는 기준과 무관하게 지금 그대로다(설계 §3). 퇴사 정산(`LeaveExitService`)은 규칙·기준을 읽지 않으므로 바꾸지 않는다(설계 §9).

- [ ] **Step 1: 실패하는 시험** — `StatutoryAnnualLeaveIT`에 추가한다(`import java.time.YearMonth; import kr.tna.request.LeaveAttributionData; import kr.tna.request.RequestData.BucketForm;`).

```java
    record Hire(TnaPrincipal f, TnaPrincipal g, TnaPrincipal i, UUID opening) { }

    /** 입사일 기준, 이월 한도 3일. F 2025-03-02 입사(1주년 3-02), G 2024-02-29 입사(2주년 3-01, 전기 5일), H 3-20 기념일(미도래), I 2025-02-10 입사. */
    private Hire hire() {
        cycles.saveRule(admin, new RuleForm(0L, "HIRE_DATE", null, new BigDecimal("3"), "입사일 기준", UUID.randomUUID()));
        var f = member(tenant, "F01", false, LocalDate.of(2025, 3, 2));
        var g = member(tenant, "G01", false, LocalDate.of(2024, 2, 29));
        member(tenant, "H02", false, LocalDate.of(2025, 3, 20));
        var i = member(tenant, "I01", false, LocalDate.of(2025, 2, 10));
        var seed = AttendanceSeed.calendar(db, transactions, clocks, worktime, admin, LocalDate.of(2025, 2, 10), LocalDate.of(2026, 3, 9),
                LocalDate.of(2025, 6, 2));
        for (var person : List.of(f, g, i)) seed.attend(person.employeeId());
        UUID opening = workflow.openBucket(admin, new BucketForm(g.employeeId(), LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 1),
                LocalDate.of(2026, 2, 28), new BigDecimal("5"), "합성 1년차 연차", "이전 원장", UUID.randomUUID()));
        attributions.save(admin, opening, new LeaveAttributionData.Form(0L, "CONFIRMED", 2025, true, "확정 기초 자료", "합성 확인", UUID.randomUUID()));
        return new Hire(f, g, i, opening);
    }

    @Test void hireDateMonthlyPostingGrantsOnEachAnniversaryAndSettlesThePreviousYear() {
        var people = hire();
        var march = cycles.previewMonth(admin, YearMonth.of(2026, 3));
        assertEquals("2026-03", march.month());
        assertEquals(LocalDate.of(2026, 3, 1), march.periodStart());
        assertEquals(TODAY, march.periodEnd());
        assertEquals(List.of("F01", "G01"), march.rows().stream().map(Row::employeeNo).toList());
        var f = march.rows().get(0);
        assertEquals(LocalDate.of(2026, 3, 2), f.grantOn());
        assertEquals(1, f.serviceYear());
        days("15", f.grantDays());
        assertEquals(LocalDate.of(2025, 3, 2), f.periodStart());
        assertEquals(LocalDate.of(2026, 3, 1), f.periodEnd());
        var g = march.rows().get(1);
        assertEquals(LocalDate.of(2026, 3, 1), g.grantOn());
        assertEquals(2, g.serviceYear());
        days("5", g.previousDays());
        days("3", g.carryDays());
        days("2", g.expireDays());
        cycles.post(admin, post(march));
        var buckets = db.queryForList("""
                SELECT b.usable_from,b.usable_to,w.entitlement_year FROM tna.annual_leave_award w
                JOIN tna.leave_bucket b ON b.tenant_id=w.tenant_id AND b.id=w.bucket_id
                JOIN tna.employee e ON e.tenant_id=w.tenant_id AND e.id=w.employee_id
                WHERE w.tenant_id=? ORDER BY e.employee_no""", tenant);
        assertEquals(List.of(java.sql.Date.valueOf("2026-03-02"), java.sql.Date.valueOf("2026-03-01")), buckets.stream().map(r -> r.get("usable_from")).toList());
        assertEquals(List.of(java.sql.Date.valueOf("2027-03-01"), java.sql.Date.valueOf("2027-02-28")), buckets.stream().map(r -> r.get("usable_to")).toList());
        assertTrue(buckets.stream().allMatch(r -> Integer.valueOf(2026).equals(r.get("entitlement_year"))));
        assertEquals(-2.0, db.queryForObject("SELECT sum(signed_quantity_days) FROM tna.leave_ledger WHERE tenant_id=? AND bucket_id=? AND entry_kind='EXPIRE'",
                Double.class, tenant, people.opening()));
        days("18", cycles.ledger(people.g(), null).availableDays());
        assertEquals(2, cycles.previewMonth(admin, YearMonth.of(2026, 3)).postedCount());
        var february = cycles.previewMonth(admin, YearMonth.of(2026, 2));
        assertEquals(List.of("I01"), february.rows().stream().map(Row::employeeNo).toList());
        cycles.post(admin, post(february));
        assertEquals(3, count("annual_leave_award"));
        var april = cycles.previewMonth(admin, YearMonth.of(2026, 4));
        assertTrue(april.rows().isEmpty());
        assertFalse(april.canPost());
    }

    @Test void hireDateLedgerUsesTheAnniversaryYearAndTheWrongPeriodKindIsRejected() {
        var people = hire();
        var march = cycles.previewMonth(admin, YearMonth.of(2026, 3));
        cycles.post(admin, post(march));
        var ledger = cycles.ledger(people.f(), null);
        assertEquals(2026, ledger.year());
        assertEquals("HIRE_DATE", ledger.basis());
        assertEquals(LocalDate.of(2026, 3, 2), ledger.periodStart());
        assertEquals(LocalDate.of(2027, 3, 1), ledger.periodEnd());
        days("15", ledger.availableDays());
        assertTrue(ledger.entries().stream().anyMatch(e -> "GRANT".equals(e.entryKind())));
        var previous = cycles.ledger(people.f(), 2025);
        assertEquals(LocalDate.of(2025, 3, 2), previous.periodStart());
        assertEquals(LocalDate.of(2026, 3, 1), previous.periodEnd());
        status(422, () -> cycles.preview(admin, 2026));
        status(422, () -> cycles.post(admin, new PostForm(2026, march.token(), "연도 반영 시도", UUID.randomUUID())));
        status(422, () -> cycles.previewMonth(admin, YearMonth.of(1899, 12)));
        var fiscalAdmin = member(company(), "X02", true, LocalDate.of(2020, 1, 1));
        cycles.saveRule(fiscalAdmin, new RuleForm(0L, "FISCAL", 1, BigDecimal.ONE, "회계연도 기준", UUID.randomUUID()));
        status(422, () -> cycles.previewMonth(fiscalAdmin, YearMonth.of(2026, 3)));
        status(409, () -> cycles.saveRule(admin, new RuleForm(1L, "FISCAL", 1, new BigDecimal("3"), "기준 변경 시도", UUID.randomUUID())));
    }
```

시험 도우미 `post`가 월도 싣도록 바꾼다(회계연도 미리보기의 `month`는 `null`이라 Task 5 시험은 그대로 통과한다):

```java
    private static PostForm post(Preview preview, UUID... reviewed) {
        return new PostForm(preview.year(), preview.month(), preview.token(), "합성 법정 연차 반영", UUID.randomUUID(), List.of(reviewed));
    }
```

`hireDateMonthlyPosting…`의 2월 반영 앞에 입사일 기준도 담당자 확인이 되는지 한 줄 더한다(I는 근태가 확정돼 부여 대상이므로 고를 수 없다): `status(409, () -> cycles.post(admin, post(february, people.i().employeeId())));`

`AnnualLeaveCycleWebIT.adminPreviewsWithoutWriting…`의 첫 페이지 단언에 `.andExpect(ReactPageAssertions.data("/month", java.time.YearMonth.from(today).toString()))`를 더하고, 같은 시험 끝에 잘못된 월은 422임을 확인한다: `postJson("/preview", Map.of("month", "2026-13"), admin).andExpect(status().isUnprocessableEntity());`.

- [ ] **Step 2: 실패 확인** — `-Dit.test=StatutoryAnnualLeaveIT,AnnualLeaveCycleWebIT` → 컴파일 실패.

- [ ] **Step 3: 데이터·매퍼**

```java
    public record PostForm(Integer year, String month, String token, String reason, UUID commandId, List<UUID> reviewedEmployeeIds) {
        public PostForm(Integer year, String token, String reason, UUID commandId) { this(year, null, token, reason, commandId, null); }
    }
    public record Ledger(int year, LocalDate today, BigDecimal availableDays, List<Entry> entries, ExitReceipt exitReceipt,
            String basis, LocalDate periodStart, LocalDate periodEnd) { }
```

`AnnualLeaveCycleMapper`:

```java
    @Select("SELECT joined_on FROM tna.employee WHERE tenant_id=#{tenantId} AND id=#{employeeId}")
    LocalDate joinedOn(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);
```

- [ ] **Step 4: 서비스** — `import java.time.YearMonth; import java.time.format.DateTimeParseException; import static kr.tna.request.StatutoryLeave.HIRE_DATE;`

```java
    public Preview preview(TnaPrincipal actor, int year) {
        return administration.readShared(actor, () -> plan(actor, validYear(year), null).preview());
    }

    public Preview previewMonth(TnaPrincipal actor, YearMonth month) {
        return administration.readShared(actor, () -> plan(actor, validYear(month.getYear()), month).preview());
    }

    public static YearMonth month(String value) {
        try {
            var month = YearMonth.parse(value);
            validYear(month.getYear());
            return month;
        } catch (DateTimeParseException invalid) {
            throw OrgException.invalid("반영할 월을 2026-03 형식으로 입력해 주세요.");
        }
    }
```

`post()`의 앞부분:

```java
    public UUID post(TnaPrincipal actor, PostForm form) {
        if (form == null || form.year() == null && form.month() == null) throw OrgException.invalid("반영할 연도나 월을 입력해 주세요.");
        YearMonth month = form.month() == null ? null : month(form.month());
        int year = month == null ? validYear(form.year()) : month.getYear();
        if (form.token() == null || !form.token().matches("[0-9a-f]{64}"))
            throw OrgException.invalid("반영할 기간의 미리보기를 확인해 주세요.");
        String reason = requiredText(form.reason(), 500, "반영 사유");
        var reviewed = reviewed(form.reviewedEmployeeIds());
        return administration.execute(actor, form.commandId(), "ANNUAL_LEAVE_POST", "ANNUAL_LEAVE_CYCLE",
                fingerprint(year, form.month(), form.token(), reason, reviewed), reason, () -> {
                    var plan = plan(actor, year, month);
                    …(토큰 비교·`selected(plan, reviewed)`·canPost·빈 선택·cycle·type은 Task 5 그대로. canPost 메시지는 "부여일 이후에 반영 대상자를 확인해 주세요."로 바꾼다)…
                    boolean hire = HIRE_DATE.equals(preview.rule().basis());
                    var awards = new ArrayList<UUID>();
                    for (var award : selected) {
                        String source = hire
                                ? "%s 입사 %d년차 연차 · 회사 연차 기준 %d차".formatted(award.row().joinedOn(), award.row().serviceYear(), preview.rule().version())
                                : year + "년 회사 연차 기준 " + preview.rule().version() + "차";
                        awards.add(postAward(actor, preview, award, type, source, reason));
                    }
                    var audit = new HashMap<String, Object>(Map.of("year", year, "ruleVersion", preview.rule().version(),
                            "periodStart", preview.periodStart(), "periodEnd", preview.periodEnd(), "awardBuckets", awards,
                            "postedCount", awards.size(), "reviewedEmployeeIds", List.copyOf(reviewed), "previewToken", preview.token()));
                    if (month != null) audit.put("month", month.toString());
                    return new Change(cycleId, null, audit);
                });
    }
```

`plan(actor, year)`를 `plan(actor, year, month)`로 바꾼다. 달라지는 곳만 적는다(나머지는 Task 5 그대로):

```java
    private Plan plan(TnaPrincipal actor, int year, YearMonth month) {
        UUID tenant = actor.tenantId();
        var applied = month == null ? mapper.appliedRule(tenant, year) : null;
        var rule = applied == null ? mapper.currentRule(tenant) : applied;
        String label = month == null ? null : month.toString();
        if (rule == null) return new Plan(new Preview(year, label, null, null, null, List.of(), 0, 0, 0, 0, 0, null, false), List.of());
        boolean hire = HIRE_DATE.equals(rule.basis());
        if (hire != (month != null))
            throw OrgException.invalid(hire ? "입사일 기준 회사는 월을 선택해 미리 보세요." : "회계연도 기준 회사는 연도를 선택해 미리 보세요.");
        LocalDate today = administration.today();
        LocalDate start = hire ? month.atDay(1) : LocalDate.of(year, rule.fiscalStartMonth(), 1);
        // 입사일 기준은 그 달 1일부터 오늘(지난달이면 말일)까지 기념일이 지난 사원이 대상이다(설계 §5).
        LocalDate end = !hire ? start.plusYears(1).minusDays(1) : month.atEndOfMonth().isAfter(today) ? today : month.atEndOfMonth();
        boolean started = !start.isAfter(today);
        var sources = new HashMap<LocalDate, Map<UUID, List<Source>>>();
        var signatures = new ArrayList<String>();
        signatures.add(fingerprint(tenant, actor.accountId(), actor.employeeId(), year, label, end, rule.version(), rule.basis(),
                rule.fiscalStartMonth(), rule.carryLimitDays(), applied != null, started));
        int posted = 0, existing = 0, later = 0, pending = 0;
        var awards = new ArrayList<Award>();
        for (var employee : mapper.candidates(tenant, actor.employeeId(), year, start)) {
            LocalDate joined = employee.joinedOn();
            int serviceYear = hire ? year - joined.getYear() : StatutoryLeave.fiscalServiceYear(joined, start, rule.fiscalStartMonth());
            LocalDate grantOn = hire ? StatutoryLeave.anniversary(joined, serviceYear) : start;
            if (hire && (serviceYear < 1 || grantOn.isBefore(start) || grantOn.isAfter(end)
                    || employee.leftOn() != null && employee.leftOn().isBefore(grantOn))) continue;
            signatures.add(…Task 5 그대로…);
            if (employee.posted()) { posted++; continue; }
            if (employee.confirmed()) { existing++; continue; }
            if (employee.later()) { later++; continue; }
            var previousBuckets = sources.computeIfAbsent(grantOn,
                    day -> byEmployee(mapper.sources(tenant, actor.employeeId(), year - 1, day.minusDays(1))))
                    .getOrDefault(employee.employeeId(), List.of());
            …(전기 묶음 서명·대기 판정 Task 5 그대로)…
            var days = !hire && serviceYear == 1 ? StatutoryLeave.prorated(joined, start) : StatutoryLeave.serviceDays(serviceYear);
            var periodStart = hire ? StatutoryLeave.anniversary(joined, serviceYear - 1) : serviceYear == 1 ? joined : start.minusYears(1);
            var usableTo = hire ? StatutoryLeave.anniversary(joined, serviceYear + 1).minusDays(1) : end;
            awards.add(award(actor, rule, employee, grantOn, usableTo, serviceYear, periodStart, days, previousBuckets, signatures));
        }
        …(토큰·rows·review Task 5 그대로)…
        var preview = new Preview(year, label, start, end, rule, rows, posted, existing, later, pending, review, token,
                started && !rows.isEmpty());
        return new Plan(preview, awards);
    }
```

`ledger()`:

```java
    public Ledger ledger(TnaPrincipal actor, Integer selectedYear) {
        return employees.read(actor, () -> {
            workflow.readLock(actor.tenantId());
            LocalDate today = administration.today();
            var current = mapper.currentRule(actor.tenantId());
            boolean hire = current != null && HIRE_DATE.equals(current.basis());
            LocalDate joined = hire ? mapper.joinedOn(actor.tenantId(), actor.employeeId()) : null;
            int year = validYear(selectedYear != null ? selectedYear : hire ? anniversaryYear(joined, today) : currentYear(today, current));
            LocalDate start, end;
            if (hire) {
                // 입사일 기준 회사의 연도는 그 해 기념일부터 다음 기념일 전날까지다(설계 §5 개인 원장).
                int years = Math.max(0, year - joined.getYear());
                start = StatutoryLeave.anniversary(joined, years);
                end = StatutoryLeave.anniversary(joined, years + 1);
            } else {
                var applied = mapper.appliedRule(actor.tenantId(), year);
                var rule = applied == null ? current : applied;
                start = LocalDate.of(year, rule == null ? 1 : rule.fiscalStartMonth(), 1);
                end = start.plusYears(1);
            }
            return new Ledger(year, today, mapper.available(actor.tenantId(), actor.employeeId(), today),
                    mapper.entries(actor.tenantId(), actor.employeeId(), year, start, end),
                    mapper.exitReceipt(actor.tenantId(), actor.employeeId(), start, end),
                    hire ? HIRE_DATE : StatutoryLeave.FISCAL, start, end.minusDays(1));
        });
    }

    private static int anniversaryYear(LocalDate joined, LocalDate today) {
        int years = Math.max(0, today.getYear() - joined.getYear());
        if (years > 0 && StatutoryLeave.anniversary(joined, years).isAfter(today)) years--;
        return joined.getYear() + years;
    }
```

- [ ] **Step 5: 컨트롤러·모델**

`AnnualLeavePostingController`:

```java
    public record PreviewForm(Integer year, String month) {}

    @PostMapping("/preview")
    Preview preview(@AuthenticationPrincipal TnaPrincipal actor, InputStream body) {
        var form = form(body, PreviewForm.class);
        if (form.month() != null) return cycles.previewMonth(actor, AnnualLeaveCycleService.month(form.month()));
        if (form.year() == null) throw OrgException.invalid("미리 볼 회계연도나 월을 입력해 주세요.");
        return cycles.preview(actor, form.year());
    }
```

`AnnualLeaveCycleController.administration`의 모델에 `.addObject("month", java.time.YearMonth.from(today).toString())`를 더한다. `ReactPageViewResolver.java:78`을 `page("request/annual-cycle", "policy", "year", "yearSelected", "month", "today", "publicDemo")`로 바꾼다.

- [ ] **Step 6: 통과 확인** — `-Dit.test=StatutoryAnnualLeaveIT,AnnualLeaveCycleIT,AnnualLeaveCycleWebIT,LeaveWorkflowIT,LeaveExitServiceIT,EmploymentAccrualIT` → PASS.

- [ ] **Step 7: 커밋** — `feat: grant hire-date annual leave on each anniversary month and show the anniversary year ledger`

---

### Task 7: 월 연차 일괄 미리보기·반영

**Files:**
- Modify: `src/main/java/kr/tna/request/EmploymentAccrualData.java` (`Period.absenceDays`, `BulkRow`, `BulkPreview`, `BulkForm`)
- Modify: `src/main/java/kr/tna/request/EmploymentAccrualMapper.java` (`Review.period()`, `monthlyCandidates`)
- Modify: `src/main/java/kr/tna/request/EmploymentAccrualService.java` (`period`, `post` 정리, `bulkPreview`, `postBulk`)
- Modify: `src/main/java/kr/tna/request/AnnualLeavePostingController.java` (`/monthly-preview`, `/monthly-apply`)
- Modify: `src/main/java/kr/tna/common/config/SecurityConfiguration.java:50` (공개 데모 차단 경로)
- Modify: `src/test/java/kr/tna/EmploymentAccrualIT.java`, `AnnualLeaveCycleWebIT.java`, `PublicDemoSecurityIT.java:162`

- [ ] **Step 1: 실패하는 시험** — `EmploymentAccrualIT`에 추가한다(고정 시계 2026-11-22, `joined`=2026-08-01, 3개 근무월).

```java
    @Test void bulkMonthlyPreviewDecidesFromDailyAttendanceAndPostsOnlyDecidedRows() {
        LocalDate first = LocalDate.of(2026,8,3), second = LocalDate.of(2026,9,7), third = LocalDate.of(2026,10,5);
        var seed = AttendanceSeed.calendar(db, transactions, clocks, worktime, admin, joined, today.minusDays(1), first, second, third);
        var absent = member("W02", false, joined);
        var unknown = member("W03", false, joined);
        seed.attend(worker.employeeId());
        seed.employee(absent.employeeId(), Set.of(second), seed.workdays());
        seed.employee(unknown.employeeId(), Set.of(), List.of());
        var preview = accrual.bulkPreview(admin);
        assertEquals(List.of("FULL","FULL","FULL","FULL","NOT_FULL","FULL","NEEDS_REVIEW","NEEDS_REVIEW","NEEDS_REVIEW"),
                preview.rows().stream().map(BulkRow::decision).toList());
        assertEquals(List.of("W01","W01","W01","W02","W02","W02","W03","W03","W03"), preview.rows().stream().map(BulkRow::employeeNo).toList());
        assertEquals("자동 판정: 소정 1일, 결근 0일", preview.rows().getFirst().finding());
        assertEquals("자동 판정: 소정 1일, 결근 1일", preview.rows().get(4).finding());
        assertEquals("근태 확정 전 1일", preview.rows().get(6).finding());
        assertEquals(5, preview.fullCount());
        assertEquals(1, preview.notFullCount());
        assertEquals(3, preview.reviewCount());
        assertTrue(preview.canPost());
        var form = new BulkForm(preview.token(), "출근부 자동 판정 확인", UUID.randomUUID());
        UUID id = accrual.postBulk(admin, form);
        assertEquals(id, accrual.postBulk(admin, form));
        assertEquals(6, db.queryForObject("SELECT count(*) FROM tna.leave_monthly_review WHERE tenant_id=?", Integer.class, tenant));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='MONTHLY_LEAVE_BULK'", Integer.class, tenant));
        var ledger = annual.ledger(worker, null);
        assertEquals(3, ledger.availableDays().intValueExact());
        assertTrue(ledger.entries().stream().allMatch(e -> e.sourceReference().contains("자동 판정: 소정 1일, 결근 0일 · 개근")
                && e.reason().equals("출근부 자동 판정 확인")));
        assertEquals(1, ledger.entries().stream().map(AnnualLeaveCycleData.Entry::bucketId).distinct().count());
        var after = accrual.bulkPreview(admin);
        assertEquals(3, after.rows().size());
        assertFalse(after.canPost());
        assertEquals(422, failure(() -> accrual.postBulk(admin, new BulkForm(after.token(), "확인", UUID.randomUUID()))));
        assertEquals("FULL", accrual.preview(admin, worker.employeeId()).periods().getFirst().decision());
    }

    @Test void bulkMonthlyPostRejectsChangedAttendanceAndOtherRoles() {
        LocalDate first = LocalDate.of(2026,8,3), second = LocalDate.of(2026,9,7), third = LocalDate.of(2026,10,5);
        var seed = AttendanceSeed.calendar(db, transactions, clocks, worktime, admin, joined, today.minusDays(1), first, second, third);
        seed.employee(worker.employeeId(), Set.copyOf(seed.workdays()), List.of());   // 세 근무일 모두 기록 없음, 아직 계산 전
        var before = accrual.bulkPreview(admin);
        assertEquals(List.of("NEEDS_REVIEW","NEEDS_REVIEW","NEEDS_REVIEW"), before.rows().stream().map(BulkRow::decision).toList());
        assertEquals(403, failure(() -> accrual.bulkPreview(worker)));
        assertEquals(403, failure(() -> accrual.postBulk(worker, new BulkForm(before.token(), "직원 시도", UUID.randomUUID()))));
        assertEquals(422, failure(() -> accrual.postBulk(admin, new BulkForm(before.token(), "판정 없음", UUID.randomUUID()))));
        transactions.execute(tenant, () -> worktime.calculateDatesAsSystem(tenant, null, worker.employeeId(), List.of(first)));
        var latest = accrual.bulkPreview(admin);
        assertEquals("NOT_FULL", latest.rows().getFirst().decision());
        assertEquals(409, failure(() -> accrual.postBulk(admin, new BulkForm(before.token(), "이전 미리보기", UUID.randomUUID()))));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.leave_monthly_review WHERE tenant_id=?", Integer.class, tenant));
    }
```

`AnnualLeaveCycleWebIT.employeeCannotChoose…`에 `postJson("/monthly-preview", Map.of(), employee).andExpect(status().isForbidden()); postJson("/monthly-apply", Map.of(), employee).andExpect(status().isForbidden());`, 담당자 `/monthly-preview`가 200이고 `rows`가 빈 배열·`canPost` false임을 더한다(직원 E01은 입사 2년이 지나 월 연차 기한이 끝났다).

`PublicDemoSecurityIT:162`의 목록을 `List.of("/policy", "/apply", "/monthly-apply")`로 바꾼다.

- [ ] **Step 2: 실패 확인** — `-Dit.test=EmploymentAccrualIT,AnnualLeaveCycleWebIT,PublicDemoSecurityIT` → 컴파일 실패.

- [ ] **Step 3: 데이터·매퍼**

`EmploymentAccrualData.java`:

```java
    public record Period(int monthNumber, LocalDate periodStart, LocalDate periodEnd, LocalDate accrualOn,
            int scheduledDays, int attendedDays, int leaveDays, int unresolvedDays, String decision, String reason, int grantedDays, String token,
            UUID includedBucketId, BigDecimal absenceDays) { }
    public record BulkRow(UUID employeeId, String employeeNo, String employeeName, LocalDate joinedOn, Period period,
            String decision, String finding) { }
    public record BulkPreview(LocalDate today, List<BulkRow> rows, int fullCount, int notFullCount, int reviewCount,
            String token, boolean canPost) { }
    public record BulkForm(String token, String reason, UUID commandId) { }
```

`EmploymentAccrualMapper.Review.period()`의 생성 끝에 `,null`을 더한다(저장된 판정에는 결근 일수가 없다). 후보 조회를 더한다:

```java
    @Select("""
        SELECT e.id FROM tna.employee e
        WHERE e.tenant_id=#{tenant} AND e.id<>#{self} AND e.joined_on>#{joinedAfter} AND e.joined_on<#{today}
            AND (e.active OR e.left_on IS NOT NULL)
            AND NOT EXISTS (SELECT 1 FROM tna.leave_exit_settlement x WHERE x.tenant_id=e.tenant_id AND x.employee_id=e.id)
        ORDER BY e.employee_no,e.id
        """)
    List<UUID> monthlyCandidates(UUID tenant, UUID self, LocalDate joinedAfter, LocalDate today);
```

- [ ] **Step 4: 서비스** — `EmploymentAccrualService`.

`period()`에서 결근 일수를 더한다:

```java
        int scheduled = 0, attended = 0, leave = 0, unresolved = 0;
        BigDecimal absence = BigDecimal.ZERO;
        …
        for (var day : dates) {
            …(기존 네 줄)…
            absence = absence.add(BigDecimal.valueOf(day.absenceDays()));
            signature.add(fingerprint(day));
        }
        …
        return new Period(window.monthNumber(),window.periodStart(),window.periodEnd(),window.accrualOn(),scheduled,attended,
                leave,unresolved,null,null,0,token,null,absence.setScale(1));
```

`post()`의 저장 부분(:101-115)을 공용 메서드로 옮긴다:

```java
                    String finding = switch (form.decision()) {
                        case "FULL" -> "개근";
                        case "INCLUDED" -> "개근 · 기초에 포함";
                        default -> "미개근";
                    };
                    var saved = record(actor,basis,row,form.decision(),included,reason,finding);
                    Map<String,Object> audit = new HashMap<>(Map.of("employeeId",form.employeeId(),"accrualOn",row.accrualOn(),
                            "decision",form.decision(),"grantedDays",saved.quantity(),"source",saved.source(),"bucketId",saved.bucket()));
                    if (included != null) audit.put("includedBucketId",included.bucketId());
                    return new OrgCommands.Change(saved.id(),null,audit);
```

```java
    private record Recorded(UUID id, UUID bucket, String source, int quantity) { }

    /** 한 근무월의 판정과 MONTHLY_ACCRUAL 원장을 같은 묶음에 기록한다. 사원별 확정과 일괄 반영이 같이 쓴다. */
    private Recorded record(TnaPrincipal actor, Basis basis, Period row, String decision, Source included, String reason, String finding) {
        String source = "%d개월차 %s~%s · 소정 %d일 · 출근 확인 %d일 · 승인 휴가 %d일 · 미확인 %d일 · %s".formatted(
                row.monthNumber(),row.periodStart(),row.periodEnd(),row.scheduledDays(),row.attendedDays(),
                row.leaveDays(),row.unresolvedDays(),finding);
        UUID tenant = actor.tenantId(), employee = basis.employee().id(), id = UUID.randomUUID();
        int year = AnnualLeaveCycleData.currentYear(row.accrualOn(),basis.rule());
        UUID bucket = bucket(actor,basis,year);
        int quantity = decision.equals("FULL") ? 1 : 0;
        changed(mapper.insert(tenant,id,employee,basis.employee().joinedOn(),row,basis.usableTo(),year,
                decision,quantity,bucket,reason,actor.accountId(),included == null ? null : included.bucketId(),
                included == null ? null : included.sourceReference()));
        changed(mapper.movement(tenant,UUID.randomUUID(),bucket,employee,quantity,row.accrualOn(),id,source,reason,actor.accountId()));
        return new Recorded(id,bucket,source,quantity);
    }
```

일괄 미리보기·반영:

```java
    public BulkPreview bulkPreview(TnaPrincipal actor) {
        return commands.readShared(actor, () -> bulk(actor));
    }

    public UUID postBulk(TnaPrincipal actor, BulkForm form) {
        if (form == null || form.token() == null || !form.token().matches("[0-9a-f]{64}"))
            throw OrgException.invalid("월 연차 미리보기를 다시 확인해 주세요.");
        String reason = requiredText(form.reason(),500,"반영 사유");
        return commands.execute(actor,form.commandId(),"MONTHLY_LEAVE_BULK","LEAVE_MONTHLY_REVIEW",
                fingerprint(form.token(),reason),reason,() -> {
                    var preview = bulk(actor);
                    if (!form.token().equals(preview.token()))
                        throw OrgException.conflict("근태 결과나 사원 정보가 바뀌었습니다. 월 연차 미리보기를 다시 확인해 주세요.");
                    if (!preview.canPost()) throw OrgException.invalid("일괄 반영할 개근·미개근 판정이 없습니다.");
                    var ids = new ArrayList<UUID>();
                    for (var row : preview.rows()) {
                        if (StatutoryLeave.NEEDS_REVIEW.equals(row.decision())) continue;
                        // 같은 사원의 다음 달은 방금 기록한 판정의 묶음을 이어 쓰도록 근거를 다시 읽는다.
                        var basis = basis(actor,row.employeeId());
                        String finding = row.finding() + " · " + (StatutoryLeave.FULL.equals(row.decision()) ? "개근" : "미개근");
                        ids.add(record(actor,basis,row.period(),row.decision(),null,reason,finding).id());
                    }
                    return new OrgCommands.Change(ids.getFirst(),null,Map.of("reviewIds",ids,"postedCount",ids.size(),
                            "previewToken",preview.token()));
                });
    }

    /** 발생일이 지났지만 판정하지 않은 월(설계 §5). 월 연차 사용기한이 끝난 사원은 사원별 화면에서 처리한다. */
    private BulkPreview bulk(TnaPrincipal actor) {
        LocalDate today = commands.today();
        var rows = new ArrayList<BulkRow>();
        var signature = new ArrayList<String>();
        signature.add(fingerprint(actor.tenantId(),actor.accountId(),today));
        for (UUID id : mapper.monthlyCandidates(actor.tenantId(),actor.employeeId(),today.minusMonths(13),today)) {
            var basis = basis(actor,id);
            if (basis.settled() || !basis.consistent() || basis.usableTo().isBefore(today)) continue;
            var person = basis.employee();
            for (var window : basis.windows()) {
                if (basis.reviews().containsKey(window.monthNumber())) continue;
                var period = period(actor,basis,window);
                String decision = StatutoryLeave.monthly(new StatutoryLeave.Evidence(period.scheduledDays(),period.absenceDays(),period.unresolvedDays()));
                String finding = StatutoryLeave.NEEDS_REVIEW.equals(decision) ? "근태 확정 전 %d일".formatted(period.unresolvedDays())
                        : "자동 판정: 소정 %d일, 결근 %s일".formatted(period.scheduledDays(),period.absenceDays().stripTrailingZeros().toPlainString());
                rows.add(new BulkRow(person.id(),person.employeeNo(),person.displayName(),person.joinedOn(),period,decision,finding));
                signature.add(fingerprint(person.id(),window.accrualOn(),period.token(),decision));
            }
        }
        int full = (int) rows.stream().filter(row -> StatutoryLeave.FULL.equals(row.decision())).count();
        int notFull = (int) rows.stream().filter(row -> StatutoryLeave.NOT_FULL.equals(row.decision())).count();
        return new BulkPreview(today,List.copyOf(rows),full,notFull,rows.size()-full-notFull,fingerprint(signature.toArray()),full+notFull>0);
    }
```

- [ ] **Step 5: 컨트롤러·보안**

`AnnualLeavePostingController`: 생성자에 `EmploymentAccrualService monthly`를 더한다.

```java
    @PostMapping("/monthly-preview")
    EmploymentAccrualData.BulkPreview monthlyPreview(@AuthenticationPrincipal TnaPrincipal actor) {
        return monthly.bulkPreview(actor);
    }

    @PostMapping("/monthly-apply")
    Map<String, UUID> monthlyApply(@AuthenticationPrincipal TnaPrincipal actor, InputStream body) {
        return Map.of("id", monthly.postBulk(actor, form(body, EmploymentAccrualData.BulkForm.class)));
    }
```

`SecurityConfiguration.java:50`의 공개 데모 차단 목록에 `"/admin/leave-balances/annual-cycle/monthly-apply"`를 더한다.

`frontend/src/pages/employmentLeaveTypes.ts`의 `MonthlyPeriod`에 `absenceDays?: number | null;`을 더한다(사원별 화면은 쓰지 않는다).

- [ ] **Step 6: 통과 확인** — `-Dit.test=EmploymentAccrualIT,EmploymentLeaveWebIT,AnnualLeaveCycleWebIT,PublicDemoSecurityIT,LeaveWorkflowIT,LeaveExitServiceIT` → PASS. `cd frontend; npx vitest run src/pages/EmploymentLeave.test.tsx` → PASS.

- [ ] **Step 7: 커밋** — `feat: preview and bulk post first-year monthly leave from daily attendance`

---

### Task 8: React 화면 (판정 열, 월 선택, 월 연차 일괄 표, 원장 기간)

**Files:**
- Modify: `frontend/src/pages/annualLeaveTypes.ts`, `frontend/src/pages/AnnualLeaveCycle.tsx`, `frontend/src/pages/PersonalLeaveLedger.tsx`
- Create: `frontend/src/pages/AnnualMonthlyBulk.tsx`, `frontend/src/pages/AnnualMonthlyBulk.test.tsx`
- Modify: `frontend/src/pages/AnnualLeavePages.test.tsx`, `frontend/src/pages/EmploymentLeave.test.tsx` (원장 시험 자료)

- [ ] **Step 1: 실패하는 React 시험**

`AnnualLeavePages.test.tsx` 자료를 서버 모양으로 바꾼다:

```tsx
const model: AnnualCycleModel = { policy, year: 2026, yearSelected: false, month: '2026-01', today: '2026-01-05', publicDemo: false };
const row = (values: Partial<AnnualCycleRow>): AnnualCycleRow => ({ employeeId: 'employee-a', employeeNo: 'E101', employeeName: '김가람',
  joinedOn: '2023-01-01', grantOn: '2026-01-01', serviceYear: 3, periodStart: '2025-01-01', periodEnd: '2025-12-31',
  scheduledDays: 248, absenceDays: 0, unresolvedDays: 0, attendanceRate: 100, decision: 'GRANT', reason: null,
  previousDays: 4.5, grantDays: 16, carryDays: 3, expireDays: 1.5, ...values });
const preview: AnnualCyclePreview = { year: 2026, month: null, periodStart: '2026-01-01', periodEnd: '2026-12-31',
  rule: policy, postedCount: 1, existingCount: 2, laterCount: 1, pendingCount: 1, reviewCount: 1, token: 'checked-state', canPost: true, rows: [
    row({}),
    row({ employeeId: 'employee-b', employeeNo: 'E102', employeeName: '이소라', joinedOn: '2025-07-01', serviceYear: 1,
      periodStart: '2025-07-01', previousDays: 0, grantDays: 8, carryDays: 0, expireDays: 0 }),
    row({ employeeId: 'employee-c', employeeNo: 'E103', employeeName: '박하늘', attendanceRate: 76.4, decision: 'NEEDS_REVIEW',
      reason: '출근율 76.4%', previousDays: 2, grantDays: 15, carryDays: 2, expireDays: 0 }),
  ] };
```

- 반영 시험의 셀 기대값: `[expect.stringContaining('김가람'), '2023-01-01', '3년차', '100%', '부여 대상', '4.5일', '3일', '1.5일', '16일', '19일']`, 셋째 행 판정 셀은 `'확인 필요출근율 76.4%'`를 포함한다. `확인 필요 1명`이 보이고, 셋째 행을 고르지 않았으므로 성공 문구는 `2명의 연차를 원장에 반영했습니다.`(부여 대상만)이며 반영 본문은 `{ year: 2026, token: preview.token, reason: '연도별 연차 부여', reviewedEmployeeIds: [], commandId: expect.any(String) }`이다.
- '미리본 일수를 보면서…' 시험의 `screen.getByRole('checkbox')`는 확인 필요 행 선택칸이 생겨 둘이 되므로 `screen.getByRole('checkbox', { name: '소멸 대상과 일수를 확인했습니다.' })`로 바꾼다.
- 다른 시험의 `preview.rows[1]`은 그대로 쓴다(부여 대상 행).
- 새 시험:

```tsx
it('입사일 기준 회사는 월을 골라 미리 보고 기념일 부여일을 보여준 뒤 그 월로 반영한다', async () => {
  const hire = { ...policy, basis: 'HIRE_DATE' as const };
  const monthly = { ...preview, rule: hire, month: '2026-03', periodStart: '2026-03-01', periodEnd: '2026-03-10', reviewCount: 0,
    rows: [row({ grantOn: '2026-03-02', serviceYear: 1, grantDays: 15, previousDays: 0, carryDays: 0, expireDays: 0 })] };
  const send = fetches().mockResolvedValueOnce(json(monthly)).mockResolvedValueOnce(json({ id: 'r' })).mockResolvedValueOnce(json({ ...monthly, rows: [] }));
  show({ policy: hire, month: '2026-03', today: '2026-03-10' });
  expect(screen.queryByLabelText('대상 회계연도')).toBeNull();
  expect(field('대상 월').value).toBe('2026-03');
  edit('처리 사유', '3월 입사기념일');
  const table = await review();
  expect(within(table).getAllByRole('columnheader').map(header => header.textContent)).toContain('부여일');
  fireEvent.click(button('원장에 반영'));
  await screen.findByText('1명의 연차를 원장에 반영했습니다.');
  expect(JSON.parse(String(send.mock.calls[0][1]?.body))).toEqual({ month: '2026-03' });
  expect(JSON.parse(String(send.mock.calls[1][1]?.body))).toEqual({ month: '2026-03', token: monthly.token, reason: '3월 입사기념일', reviewedEmployeeIds: [], commandId: expect.any(String) });
});

it('확인 필요 행은 담당자가 골라야 반영되고 고른 사원 ID를 보낸다', async () => {
  const send = fetches().mockResolvedValueOnce(json({ ...preview, rows: [preview.rows[2]] })).mockResolvedValueOnce(json({ id: 'r' }))
    .mockResolvedValueOnce(json(emptyPreview));
  show();
  edit('처리 사유', '도입 전 근태 확인');
  await review();
  expect(screen.getByText(/근태를 확정한 뒤 다시 미리 보거나/)).toBeTruthy();
  expect(button('원장에 반영').disabled).toBe(true);
  fireEvent.click(screen.getByRole('checkbox', { name: '박하늘 법정 일수 부여' }));
  fireEvent.click(button('원장에 반영'));
  await screen.findByText('1명의 연차를 원장에 반영했습니다.');
  expect(JSON.parse(String(send.mock.calls[1][1]?.body))).toEqual({ year: 2026, token: preview.token, reason: '도입 전 근태 확인',
    reviewedEmployeeIds: ['employee-c'], commandId: expect.any(String) });
});

it('공개 데모에서는 확인 필요 행을 고를 수 없다', async () => {
  fetches().mockResolvedValueOnce(json(preview));
  show({ publicDemo: true });
  await review();
  expect(screen.queryByRole('checkbox', { name: '박하늘 법정 일수 부여' })).toBeNull();
});
```

- 개인 원장 시험 자료(`AnnualLeavePages.test.tsx`의 `ledger`, `EmploymentLeave.test.tsx`의 `ledger`)에 `basis: 'FISCAL', periodStart: '2026-01-01', periodEnd: '2026-12-31'`을 더하고, 입사일 기준 시험을 하나 더한다:

```tsx
it('입사일 기준 회사의 원장은 입사기념일 기준 1년 기간을 보여준다', () => {
  const ledger: PersonalLedger = { year: 2026, today: '2026-03-10', availableDays: 15, basis: 'HIRE_DATE',
    periodStart: '2026-03-02', periodEnd: '2027-03-01', entries: [] };
  render(<WorkflowPages page="request/personal-ledger" data={{ ledger }} csrf={csrf} reload={vi.fn()} />);
  expect(screen.getByText(/2026-03-02 ~ 2027-03-01/)).toBeTruthy();
  expect(screen.getByText(/입사기념일 기준 1년/)).toBeTruthy();
});
```

(`fmtDate` 표시 형식이 `2026-03-02`가 아니면 기존 원장 시험의 날짜 단언과 같은 형식으로 맞춘다.)

`AnnualMonthlyBulk.test.tsx`:

```tsx
// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { AnnualMonthlyBulk } from './AnnualMonthlyBulk';
import type { MonthlyBulkPreview } from './annualLeaveTypes';

const csrf = { headerName: 'X-TEST-CSRF', parameterName: '_csrf', token: 'synthetic-token' };
const base = '/admin/leave-balances/annual-cycle';
const period = (monthNumber: number, unresolvedDays = 0, absenceDays = 0) => ({ monthNumber, periodStart: '2026-08-01', periodEnd: '2026-08-31',
  accrualOn: '2026-09-01', scheduledDays: 21, attendedDays: 21, leaveDays: 0, unresolvedDays, absenceDays, decision: null, reason: null,
  grantedDays: 0, token: 't' + monthNumber, includedBucketId: null });
const preview: MonthlyBulkPreview = { today: '2026-11-22', token: 'bulk-token', canPost: true, fullCount: 1, notFullCount: 1, reviewCount: 1, rows: [
  { employeeId: 'a', employeeNo: 'W01', employeeName: '김가람', joinedOn: '2026-08-01', period: period(1), decision: 'FULL', finding: '자동 판정: 소정 21일, 결근 0일' },
  { employeeId: 'b', employeeNo: 'W02', employeeName: '이소라', joinedOn: '2026-08-01', period: period(1, 0, 1), decision: 'NOT_FULL', finding: '자동 판정: 소정 21일, 결근 1일' },
  { employeeId: 'c', employeeNo: 'W03', employeeName: '박하늘', joinedOn: '2026-08-01', period: period(1, 3), decision: 'NEEDS_REVIEW', finding: '근태 확정 전 3일' },
] };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it('월 연차 판정을 미리 보고 개근·미개근 행만 사유와 함께 반영한 뒤 다시 조회한다', async () => {
  const send = vi.fn<typeof fetch>().mockResolvedValueOnce(json(preview)).mockResolvedValueOnce(json({ id: 'r' }))
    .mockResolvedValueOnce(json({ ...preview, rows: [preview.rows[2]], fullCount: 0, notFullCount: 0, canPost: false }));
  vi.stubGlobal('fetch', send);
  render(<AnnualMonthlyBulk csrf={csrf} publicDemo={false} />);
  fireEvent.click(screen.getByRole('button', { name: '월 연차 미리보기' }));
  const table = await screen.findByRole('table', { name: '월 연차 자동 판정' });
  expect(within(table).getAllByRole('row')).toHaveLength(4);
  expect(within(table).getByText('근태 확정 전 3일')).toBeTruthy();
  const apply = screen.getByRole('button', { name: '개근·미개근 2건 반영' }) as HTMLButtonElement;
  expect(apply.disabled).toBe(true);
  fireEvent.change(screen.getByLabelText('월 연차 반영 사유'), { target: { value: '출근부 자동 판정 확인' } });
  fireEvent.click(apply);
  await screen.findByText('월 연차 2건을 원장에 반영했습니다.');
  expect(send.mock.calls.map(([url]) => url)).toEqual([base + '/monthly-preview', base + '/monthly-apply', base + '/monthly-preview']);
  expect(JSON.parse(String(send.mock.calls[1][1]?.body))).toEqual({ token: 'bulk-token', reason: '출근부 자동 판정 확인', commandId: expect.any(String) });
  expect(screen.queryByRole('button', { name: /건 반영/ })).toBeNull();
});

it('공개 데모는 미리보기만 한다', async () => {
  vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValueOnce(json(preview)));
  render(<AnnualMonthlyBulk csrf={csrf} publicDemo />);
  fireEvent.click(screen.getByRole('button', { name: '월 연차 미리보기' }));
  await screen.findByRole('table', { name: '월 연차 자동 판정' });
  expect(screen.queryByRole('button', { name: /건 반영/ })).toBeNull();
});

it('미리본 뒤 근태가 바뀌어 거절되면 서버 문구를 보여준다', async () => {
  vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValueOnce(json(preview))
    .mockResolvedValueOnce(json({ message: '근태 결과나 사원 정보가 바뀌었습니다. 월 연차 미리보기를 다시 확인해 주세요.' }, 409)));
  render(<AnnualMonthlyBulk csrf={csrf} publicDemo={false} />);
  fireEvent.click(screen.getByRole('button', { name: '월 연차 미리보기' }));
  await screen.findByRole('table', { name: '월 연차 자동 판정' });
  fireEvent.change(screen.getByLabelText('월 연차 반영 사유'), { target: { value: '확인' } });
  fireEvent.click(screen.getByRole('button', { name: '개근·미개근 2건 반영' }));
  expect((await screen.findByRole('alert')).textContent).toContain('미리보기를 다시 확인해 주세요');
});
```

- [ ] **Step 2: 실패 확인** — `cd frontend; npx vitest run src/pages/AnnualLeavePages.test.tsx src/pages/AnnualMonthlyBulk.test.tsx` → FAIL.

- [ ] **Step 3: 타입** — `annualLeaveTypes.ts`:

```ts
import type { AttendanceDecision, ExitReceipt, MonthlyPeriod } from './employmentLeaveTypes';

export interface AnnualCycleModel { policy: AnnualLeaveRule | null; year: number; yearSelected: boolean; month: string; today: string; publicDemo: boolean; }

export interface AnnualCycleRow {
  employeeId: string; employeeNo: string; employeeName: string; joinedOn: string; grantOn: string; serviceYear: number;
  periodStart: string; periodEnd: string; scheduledDays: number; absenceDays: number; unresolvedDays: number;
  attendanceRate: number | null; decision: 'GRANT' | 'NEEDS_REVIEW'; reason: string | null;
  previousDays: number; grantDays: number; carryDays: number; expireDays: number;
}

export interface AnnualCyclePreview {
  year: number; month: string | null; periodStart: string; periodEnd: string; rule: AnnualLeaveRule; rows: AnnualCycleRow[];
  postedCount: number; existingCount: number; laterCount: number; pendingCount: number; reviewCount: number; token: string; canPost: boolean;
}

export interface AnnualCycleCommand { year?: number; month?: string; token: string; reason: string; reviewedEmployeeIds: string[]; commandId: string; }

export type MonthlyBulkDecision = 'FULL' | 'NOT_FULL' | 'NEEDS_REVIEW';
export interface MonthlyBulkRow { employeeId: string; employeeNo: string; employeeName: string; joinedOn: string; period: MonthlyPeriod; decision: MonthlyBulkDecision; finding: string; }
export interface MonthlyBulkPreview { today: string; rows: MonthlyBulkRow[]; fullCount: number; notFullCount: number; reviewCount: number; token: string; canPost: boolean; }
```

`PersonalLedger`에 `basis: AnnualLeaveBasis; periodStart: string; periodEnd: string;`을 더한다.

- [ ] **Step 4: 연간 미리보기** — `AnnualLeaveCycle.tsx`.

```tsx
type PreviewRequest = { year: number } | { month: string };
const isRow = (row: AnnualCycleRow) => row && row.employeeId && row.employeeNo && row.employeeName
  && (row.decision === 'GRANT' || row.decision === 'NEEDS_REVIEW') && Number.isInteger(row.serviceYear)
  && [row.previousDays, row.grantDays, row.carryDays, row.expireDays].every(isDays);
function isPreview(result: AnnualCyclePreview, request: PreviewRequest) {
  return ('month' in request ? result?.month === request.month : result?.year === request.year) && isRule(result.rule)
    && typeof result.periodStart === 'string' && typeof result.periodEnd === 'string'
    && [result.postedCount, result.existingCount, result.laterCount, result.pendingCount, result.reviewCount].every(isCount)
    && typeof result.canPost === 'boolean' && (!result.canPost || (typeof result.token === 'string' && result.token.length > 0))
    && Array.isArray(result.rows) && result.rows.every(isRow);
}
```

`PreviewResults`:

```tsx
function PreviewResults({ result, today, reviewed, onReview, selectable }: {
  result: AnnualCyclePreview; today: string; reviewed: Set<string>; onReview: (employeeId: string, checked: boolean) => void; selectable: boolean;
}) {
  const hire = result.rule.basis === 'HIRE_DATE';
  const granted = result.rows.filter(row => row.decision === 'GRANT');
  const columns = ['사원', '입사일', ...(hire ? ['부여일'] : []), '근속', '출근율', '판정', '전년 잔여', '이월', '소멸', '신규 부여', '반영 후 잔여'];
  return <section className="annual-cycle-preview" aria-labelledby="annual-review-title">
    <h3 id="annual-review-title" tabIndex={-1}>미리보기 결과</h3>
    <p><strong>{fmtDate(result.periodStart)} ~ {fmtDate(result.periodEnd)}</strong></p>
    <p>기준 버전 {result.rule.version} · {hire ? '입사일 기준' : `매년 ${result.rule.fiscalStartMonth}월 1일 시작`} · 이월 한도 {result.rule.carryLimitDays}일</p>
    <ul className="annual-review-counts">
      <li>반영 대상 {granted.length}명</li>{result.reviewCount > 0 && <li>확인 필요 {result.reviewCount}명</li>}
      <li>이미 반영 {result.postedCount}명</li>
      …(기존 기초 등록·후속 연도·결재 대기 li 그대로)…
    </ul>
    …(기존 결재 대기 안내 그대로)…
    {result.reviewCount > 0 && <p className="hint">확인 필요인 사원은 근태를 확정한 뒤 다시 미리 보거나, 사정을 확인하고 '법정 일수 부여'를 골라 함께 반영하세요.
      고른 사원은 부여 대상과 같은 법정 일수를 받고 '담당자 확인'으로 기록됩니다. 출근율 80% 미만이라 법정 일수를 주지 않을 사원은 고르지 말고 개인별 연차 관리의 잔여 조정으로 처리합니다.</p>}
    {result.rows.length ? <AnnualLeaveTable label="사원별 반영 예정" columns={columns}
      rows={result.rows.map(row => ({ id: row.employeeId, cells: [
        <><strong>{row.employeeName}</strong><small>{row.employeeNo}</small></>, fmtDate(row.joinedOn), ...(hire ? [fmtDate(row.grantOn)] : []),
        row.serviceYear + '년차', row.attendanceRate == null ? '—' : row.attendanceRate + '%',
        row.decision === 'GRANT' ? '부여 대상' : <><strong className="annual-review-flag">확인 필요</strong><small>{row.reason}</small>
          {selectable && <label><input type="checkbox" aria-label={row.employeeName + ' 법정 일수 부여'} checked={reviewed.has(row.employeeId)}
            onChange={event => onReview(row.employeeId, event.target.checked)} />법정 일수 부여</label>}</>,
        row.previousDays + '일', row.carryDays + '일', row.expireDays + '일', row.grantDays + '일', <strong>{row.grantDays + row.carryDays}일</strong>,
      ] }))} /> : <p>{result.pendingCount ? '현재 바로 반영할 사원이 없습니다.' : '이 기간에 추가로 반영할 사원이 없습니다.'}</p>}
    {!result.canPost && result.periodStart > today && <p>{result.periodStart}부터 원장에 반영할 수 있습니다.</p>}
  </section>;
}
```

(`fmtDate(row.joinedOn)`의 표시가 `2023-01-01`이 아니면 시험의 셀 기대값을 실제 형식에 맞춘다. `.annual-review-flag`는 `AnnualLeave.css`에 `color: var(--danger, #b42318); margin-right: 4px;` 한 줄만 둔다.)

`AnnualLeaveCycle` 본문:
- `const [month, setMonth] = useState(model.month); const hire = rule?.basis === 'HIRE_DATE'; const hasMonth = /^\d{4}-(0[1-9]|1[0-2])$/.test(month);`
- `const canPreview = Boolean(rule && !ruleChanged && (hire ? hasMonth : hasYear) && !locked);`
- `const request = (): PreviewRequest => hire ? { month } : { year: Number(year) };`
- `readPreview(selected: PreviewRequest)`: 본문 `body: selected`, 검사 `isPreview(data, selected)`. `inspect()`는 `readPreview(request())`.
- `const [reviewed, setReviewed] = useState<Set<string>>(() => new Set());` `clearReview()`에서 `setReviewed(new Set())`도 한다. 고르기: `function review(employeeId: string, checked: boolean) { setReviewed(current => { const next = new Set(current); if (checked) next.add(employeeId); else next.delete(employeeId); return next; }); setExpiryConfirmed(false); }`
- `const selected = preview?.rows.filter(row => row.decision === 'GRANT' || reviewed.has(row.employeeId)) ?? [];` `expiryDays`는 `selected` 합계, `canApply = Boolean(preview?.canPost && !model.publicDemo)`. 반영 버튼은 `selected.length === 0`이면 비활성이다. `<PreviewResults result={preview} today={model.today} reviewed={reviewed} onReview={review} selectable={canApply && !locked} />`.
- 반영 명령: `{ kind: 'apply', count: selected.length, command: { ...(preview.month ? { month: preview.month } : { year: preview.year }), token: preview.token, reason: checkedReason.trimmed, reviewedEmployeeIds: [...reviewed], commandId: crypto.randomUUID() } }`. 반영 뒤 `readPreview(action.command.month ? { month: action.command.month } : { year: action.command.year! })`.
- 처리 영역 제목·입력: 회계연도 기준은 지금처럼 '연도별 일괄 처리'와 '대상 회계연도', 입사일 기준은 제목 '월별 일괄 처리'와 `<input id="annual-target-month" className="t-input" type="month" required value={month} onChange={event => { setMonth(event.target.value); inputChanged(); }} />`(라벨 '대상 월').
- 안내 문장: 입사일 기준은 "선택한 달 1일부터 오늘(지난달이면 말일)까지 입사기념일이 지난 사원이 대상입니다. 직전 1년 출근율이 80% 이상이고 근태가 모두 확정된 사원만 반영합니다. 담당자 본인의 연차는 다른 담당자가 처리합니다." 회계연도 기준은 기존 문장 앞에 "회계연도 시작일 전에 입사한 사원이 대상이고, 직전 회계연도 출근율로 판정합니다."를 더한다.
- 페이지 끝(`</section>` 뒤)에 `<AnnualMonthlyBulk csrf={csrf} publicDemo={model.publicDemo} />`를 둔다.

- [ ] **Step 5: 월 연차 일괄 표** — `AnnualMonthlyBulk.tsx`:

```tsx
import { useRef, useState } from 'react';
import { commandReason } from '../commandReason';
import { PageRequestError, requestJson } from '../transport';
import type { CsrfToken } from '../types';
import { FormRow, FormTable, fmtDate } from '../ui';
import { AnnualLeaveTable } from './AnnualLeaveTable';
import { employmentPath } from './employmentLeaveTypes';
import type { MonthlyBulkDecision, MonthlyBulkPreview } from './annualLeaveTypes';

const base = '/admin/leave-balances/annual-cycle';
const decisionNames: Record<MonthlyBulkDecision, string> = { FULL: '개근', NOT_FULL: '미개근', NEEDS_REVIEW: '확인 필요' };

export function AnnualMonthlyBulk({ csrf, publicDemo }: { csrf: CsrfToken; publicDemo: boolean }) {
  const [preview, setPreview] = useState<MonthlyBulkPreview>();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ text: string; error?: boolean }>();
  const running = useRef(false);
  const checked = commandReason(reason);
  const decided = preview?.rows.filter(row => row.decision !== 'NEEDS_REVIEW').length ?? 0;
  async function run(action: () => Promise<void>) {
    if (running.current) return;
    running.current = true; setBusy(true); setMessage(undefined);
    try { await action(); }
    catch (failure) {
      setMessage({ error: true, text: failure instanceof PageRequestError && failure.status < 500 ? failure.message
        : '서버의 처리 결과를 확인할 수 없습니다. 월 연차 미리보기를 다시 확인하세요.' });
    } finally { running.current = false; setBusy(false); }
  }
  async function load() {
    setPreview(undefined);
    setPreview(await requestJson<MonthlyBulkPreview>(base + '/monthly-preview', { csrf, body: {} }));
  }
  const apply = () => run(async () => {
    await requestJson<{ id: string }>(base + '/monthly-apply', { csrf, body: { token: preview!.token, reason: checked.trimmed, commandId: crypto.randomUUID() } });
    setReason('');
    await load();
    setMessage({ text: `월 연차 ${decided}건을 원장에 반영했습니다.` });
  });
  return <section className="wl-section" aria-labelledby="monthly-bulk-title">
    <h2 id="monthly-bulk-title">입사 첫해 월 연차 일괄 반영</h2>
    <p className="hint">발생일이 지났지만 판정하지 않은 월을 일일 근태로 판정합니다. 결근이 없으면 개근, 결근이 있으면 미개근이고, 근태가 확정되지 않은 달은 반영하지 않습니다.
      '기초 잔여에 이미 포함' 판정은 <a href={employmentPath}>입사·퇴사 연차 정산</a>에서 합니다.</p>
    <div className="wl-form-actions"><button type="button" className="t-btn" disabled={busy} onClick={() => void run(load)}>
      {busy && !preview ? '조회 중…' : '월 연차 미리보기'}</button></div>
    {preview && <>
      <ul className="annual-review-counts"><li>개근 {preview.fullCount}건</li><li>미개근 {preview.notFullCount}건</li>
        {preview.reviewCount > 0 && <li>확인 필요 {preview.reviewCount}건</li>}</ul>
      {preview.rows.length ? <AnnualLeaveTable label="월 연차 자동 판정"
        columns={['사원', '입사일', '근무월', '산정기간', '소정', '결근', '판정 불가', '자동 판정']}
        rows={preview.rows.map(row => ({ id: row.employeeId + row.period.monthNumber, cells: [
          <><strong>{row.employeeName}</strong><small>{row.employeeNo}</small></>, fmtDate(row.joinedOn), row.period.monthNumber + '개월차',
          `${fmtDate(row.period.periodStart)} ~ ${fmtDate(row.period.periodEnd)}`, row.period.scheduledDays + '일',
          (row.period.absenceDays ?? 0) + '일', row.period.unresolvedDays + '일',
          <><strong>{decisionNames[row.decision]}</strong><small>{row.finding}</small></>,
        ] }))} /> : <p>판정할 월 연차가 없습니다.</p>}
      {preview.canPost && !publicDemo && <form className="wl-form" aria-labelledby="monthly-bulk-title" onSubmit={event => { event.preventDefault(); void apply(); }}>
        <FormTable columns={2}><FormRow label="월 연차 반영 사유" htmlFor="monthly-bulk-reason" required>
          <input id="monthly-bulk-reason" className="t-input" type="text" required value={reason} disabled={busy}
            onChange={event => { setReason(event.target.value); setMessage(undefined); }} />
        </FormRow></FormTable>
        <div className="wl-form-actions"><button type="submit" className="t-btn primary" disabled={busy || !checked.validReason}>
          {busy ? '반영 중…' : `개근·미개근 ${decided}건 반영`}</button></div>
      </form>}
    </>}
    {message && <p className={message.error ? 'notice error' : 'notice'} role={message.error ? 'alert' : 'status'}>{message.text}</p>}
  </section>;
}
```

- [ ] **Step 6: 개인 원장** — `PersonalLeaveLedger.tsx`의 이력 안내 문장을 기준별로 바꾼다:

```tsx
      <p className="hint">{ledger.basis === 'HIRE_DATE'
        ? `${fmtDate(ledger.periodStart)} ~ ${fmtDate(ledger.periodEnd)} 입사기념일 기준 1년의 묶음과 이 기간에 효력이 생긴 변동을 표시합니다.`
        : `${ledger.year}년 귀속 묶음의 내역과 해당 회계기간(${fmtDate(ledger.periodStart)} ~ ${fmtDate(ledger.periodEnd)})에 효력이 생긴 변동을 표시합니다.`}</p>
```

- [ ] **Step 7: 통과 확인** — `cd frontend; npx vitest run src/pages/AnnualLeavePages.test.tsx src/pages/AnnualMonthlyBulk.test.tsx src/pages/EmploymentLeave.test.tsx src/pages/WorkflowPages.test.tsx; npm run build` → PASS.

- [ ] **Step 8: 커밋** — `feat: show statutory leave judgements, hire-date months and the monthly leave bulk table`

---

### Task 9: 문서·전체 회귀·브라우저 확인 (컨트롤러가 수행)

**Files:**
- Modify: `README.md:41`, `docs/rules/workflow.md:83`, `docs/prd.md:12,21`
- Modify: `src/test/java/kr/tna/AnnualLeaveCycleBrowser.java`, `scripts/verify-annual-leave-cycle.cjs:83-88` (부여 일수 입력 제거, 기준 선택, 근태 시드)
- Create: `docs/quality/statutory-annual-leave-20261127.md`

- [ ] **Step 1: 문서** — 설계의 결정만 옮긴다.
  - `README.md:41`: "회사의 연차 부여 기준(회계연도 기준·입사일 기준)과 이월 한도를 정합니다. 사원별 연차 일수는 근로기준법대로 근속년차와 직전 기간 출근율로 계산하고, 담당자가 미리 본 뒤 일괄 반영합니다. 입사 첫해 월 연차도 일일 근태로 판정해 한 번에 반영합니다." 끝에 설계·결과 링크.
  - `docs/rules/workflow.md:83`: 회계연도 전환 문장 뒤에 "입사일 기준 회사는 입사기념일마다 같은 방식으로 이월·소멸과 새 묶음을 기록한다. 부여 일수는 법정 근속 일수다. 직전 기간 출근율 80% 미만이거나 근태가 확정되지 않은 사원은 '확인 필요'로 표시하고, 담당자가 확인해 고른 사원만 같은 법정 일수로 반영하며 '담당자 확인'으로 기록한다." 와 설계 링크.
  - `docs/prd.md:12`: "회계연도별 부여 일수" → "연차 부여 기준과 법정 근속 일수·출근율 판정". `:21`의 "근속 1년 이후의 입사일 기준 발생과 회계 기준의 차이는 담당자가 대조한다."는 "입사일 기준 회사는 기념일마다 부여한다. 회계연도 기준 회사의 퇴사 시 입사일 기준과의 차이는 퇴사 정산 화면에서 대조한다."로 바꾼다.
  - 커밋: `docs: describe statutory annual leave accrual and the company basis`

- [ ] **Step 2: 브라우저 시나리오 갱신** — `verify-annual-leave-cycle.cjs`의 `'연간 부여 일수'` 입력을 지우고 `admin.select(named('combobox', '연차 부여 기준'), 'FISCAL')`을 넣는다. 저장 결과 단언을 `[rule.basis, rule.fiscalStartMonth, rule.grantDays, rule.carryLimitDays]` = `['FISCAL', 1, 15, 3]`로, 미리보기 행 기대값을 새 열(입사일·근속·출근율·판정)에 맞춘다. `AnnualLeaveCycleBrowser.seed`는 대상 사원에게 직전 회계연도 근로일 하나와 출퇴근·결과를 `AttendanceSeed`와 같은 SQL로 넣는다(앱 Bean `WorktimeService.calculateDatesAsSystem`을 `TenantTransactions.execute` 안에서 호출). 커밋: `test: update the annual leave browser scenario for statutory accrual`

- [ ] **Step 3: 전체 회귀** — 새 테스트 DB에서 `mvn-test.ps1 verify`, `cd frontend; npx vitest run; npm run build`. 기대: [이전 결과](../../quality/overtime-payroll-20261126.md)의 알려진 오류 13건 외 실패 0건.

- [ ] **Step 4: 브라우저 확인** (로컬 데모, 앱 내장 브라우저, 합성 자료만)
  - 회계연도 기준: 기준 저장(부여 일수 입력 없음) → 연도 미리보기에서 비례 첫해·근속 가산·확인 필요(사유) 행 확인 → 확인 필요 한 명을 '법정 일수 부여'로 고르고 반영 → 직원 '내 연차 원장' 잔여·GRANT 근거의 '담당자 확인'.
  - 입사일 기준(별도 합성 회사): 기준 저장 → 이번 달·지난달 미리보기 → 반영 → 직전 묶음 이월·소멸 → 직원 원장의 '입사기념일 기준 1년' 기간.
  - 기준 변경 금지 안내(반영 뒤 기준 바꾸기 시도).
  - 월 연차 일괄: 개근·미개근·확인 필요 행 → 사유 입력 → 반영 → 재조회에서 확인 필요만 남음 → 사원별 화면 판정 확인.
  - 모바일 390×844: 페이지 가로 넘침 없음, 미리보기·월 연차 표는 시트 안 가로 스크롤.

- [ ] **Step 5: 결과 문서** — `docs/quality/statutory-annual-leave-20261127.md`를 [이전 결과](../../quality/overtime-payroll-20261126.md)와 같은 절(검증 환경, 회귀 결과, 새 테스트 표, 코드 검토에서 고친 점, 브라우저 확인, 남은 점)로 쓴다. '남은 점'에 설계 §9 범위 밖 항목과 이 계획의 '남는 위험'(도입 전 근태가 없는 사원)을 적는다. 커밋: `docs: record statutory annual leave verification`
