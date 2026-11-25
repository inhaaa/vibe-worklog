# 일일 근태 자동화 (B 묶음) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 퇴근하면 예정 휴게를 자동 적용해 근무시간을 바로 집계하고, 근무시간코드 기준 지각·조퇴와 날이 지난 무기록 근로일의 결근을 판정·저장·표시하며, 매일 새벽 일괄 집계한다.

**Architecture:** 순수 계산기 `WorktimeCalculator`에 `AttendanceContext`(날이 지났는지, 반차 구분)를 받는 `day(input, context)`를 추가해 `DayResult.attendance`(지각·조퇴·결근)를 만든다. `WorktimeService`가 이를 `day_result`의 새 열(V42)에 저장한다. 휴게 자동 확인은 `ClockService.autoConfirm`이 기존 `work_confirmation`에 `method='AUTO_SCHEDULE'`로 기록한다. 퇴근 직후·결재 처리 직후·새벽 스케줄러(`DailyAttendanceBatch`)가 계산을 부른다.

**Tech Stack:** Spring Boot 4.1 / Java 25, MyBatis annotation mapper, PostgreSQL 18 + Flyway, React 19 + Vitest, JUnit 5 + 외부 테스트 DB(`DatabaseFixture`).

설계: [docs/superpowers/specs/2026-11-25-daily-attendance-automation-design.md](../specs/2026-11-25-daily-attendance-automation-design.md)

---

## 공통 실행 방법

- 작업 디렉터리: `C:\Users\ihpark\dev\vibe-attendance\target\claude-opening-prep-20261123` (브랜치 `claude/opening-prep-20261123`). 브랜치를 바꾸지 않는다. Push 금지.
- Maven은 JDK 25와 외부 테스트 DB 환경변수가 필요하다. 반드시 스크래치 스크립트로 실행한다:
  `powershell -File C:\Users\ihpark\AppData\Local\Temp\claude\c6d09512e35b8bb6\ff996283-e18f-4a51-b946-d1e31871cf61\scratchpad\mvn-test.ps1 <maven args>`
  예: `... mvn-test.ps1 -q test -Dtest=AttendanceJudgementTest`, 통합: `... mvn-test.ps1 -q verify -Dit.test=DailyAttendanceBatchIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`
  (스크립트가 없으면 컨트롤러 세션에 알린다. 직접 만들지 않는다.)
  백엔드만 확인할 때는 `-Dfrontend.skip=true`(npm ci 생략), jar가 잠겨 있으면 `-Dspring-boot.repackage.skip=true`를 붙인다. 여러 테스트 클래스는 쉼표로 구분한다.
- 프런트엔드: `cd frontend; npx vitest run <파일>`; 빌드 `npm run build`.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`를 넣는다.
- 비밀번호·실제 생년월일·개인정보를 로그·감사·지문·페이지 모델에 넣지 않는다. 회사(테넌트) 격리, RLS, 권한을 약화하지 않는다.

## 파일 구조

| 파일 | 책임 |
| --- | --- |
| `src/main/java/kr/tna/worktime/WorktimeCalculator.java` (수정) | `AttendanceContext`, `Attendance`, `day(input, context)`, 지각·조퇴·결근 판정 |
| `src/main/java/kr/tna/worktime/AttendanceLabel.java` (생성) | 판정값 → 화면 문구("정상", "지각 7분" …) |
| `src/main/resources/db/migration/V42__daily_attendance_automation.sql` (생성) | 결과 열 3개, 확인 방법, 시스템 실행 허용 |
| `src/main/java/kr/tna/worktime/WorktimeMapper.java` (수정) | 결과 저장·조회 열, 반차 구분, 시스템 실행, 일괄 후보 조회 |
| `src/main/java/kr/tna/worktime/WorktimeService.java` (수정) | 문맥 전달, `CALC-2`, 시스템 계산 `calculateDatesAsSystem`, 결재 후 재계산 |
| `src/main/java/kr/tna/clock/ClockService.java`, `ClockMapper.java` (수정) | `autoConfirm`, 퇴근 시 자동 확인 |
| `src/main/java/kr/tna/clock/AttendanceController.java` (수정) | 퇴근 뒤 즉시 계산 |
| `src/main/java/kr/tna/request/RequestController.java` (수정) | 결재 처리 뒤 재계산 |
| `src/main/java/kr/tna/worktime/DailyAttendanceBatch.java` (생성) | 새벽 일괄 작업 |
| `src/main/java/kr/tna/TnaApplication.java` (수정) | `@EnableScheduling` |
| `src/main/java/kr/tna/clock/AttendanceHistory.java`, `workspace/WorkspaceData.java`, `workspace/WorkspaceQueries.java` (수정) | 직원 화면의 근태 문구 |
| `src/main/java/kr/tna/workspace/AttendanceExceptionQueries.java` (생성), `ProfileCompanyController.java` (수정) | 담당자 '지각·조퇴·결근' 탭 |
| `frontend/src/pages/AttendancePeriodPage.tsx`, `CorePages.tsx`, `WorkflowPages.tsx`, `CompanyAttendanceNavigation.tsx`, `AttendanceExceptionsPage.tsx`(생성), `App.tsx` | 화면 |
| `docs/rules/calculation.md`, `docs/design/worktime-results.md`, `docs/quality/daily-attendance-20261125.md` | 문서 |

---

### Task 1: 계산기 — 지각·조퇴·결근 판정

**Files:**
- Modify: `src/main/java/kr/tna/worktime/WorktimeCalculator.java`
- Create: `src/test/java/kr/tna/worktime/AttendanceJudgementTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.tna.worktime;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.List;
import java.util.UUID;
import kr.tna.worktime.WorktimeCalculator.*;
import org.junit.jupiter.api.Test;

class AttendanceJudgementTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate DATE = LocalDate.of(2026, 11, 23); // 월요일
    private static final long MIN = 60_000L;
    private final WorktimeCalculator calculator = new WorktimeCalculator();

    private static Instant at(String hhmm) { return DATE.atTime(LocalTime.parse(hhmm)).atZone(SEOUL).toInstant(); }
    private static Schedule fixed(String start, String end, String... breaks) {
        var list = new java.util.ArrayList<Interval>();
        for (int i = 0; i < breaks.length; i += 2) list.add(new Interval(at(breaks[i]), at(breaks[i + 1])));
        return new Schedule(true, at(start), at(end), List.copyOf(list));
    }
    private static List<ClockEvent> events(String in, String out) {
        var list = new java.util.ArrayList<ClockEvent>();
        if (in != null) list.add(new ClockEvent(UUID.randomUUID(), EventKind.IN, at(in)));
        if (out != null) list.add(new ClockEvent(UUID.randomUUID(), EventKind.OUT, at(out)));
        return List.copyOf(list);
    }
    private static Confirmation breaks(String... b) {
        var list = new java.util.ArrayList<Interval>();
        for (int i = 0; i < b.length; i += 2) list.add(new Interval(at(b[i]), at(b[i + 1])));
        return new Confirmation(0, 0, true, List.copyOf(list));
    }
    private DayResult run(List<ClockEvent> events, Schedule schedule, Confirmation c, double leave, String part, boolean ended, boolean pending) {
        return calculator.day(new DayInput(DATE, events, List.of(schedule), DayKind.WORKDAY, true, 0, 0, c, pending, leave, leave),
                new AttendanceContext(ended, part));
    }

    @Test void onTimeDayIsNormal() {
        var r = run(events("08:00", "17:00"), fixed("08:00", "17:00", "12:00", "13:00"), breaks("12:00", "13:00"), 0, null, true, false);
        assertEquals(0L, r.attendance().lateMillis());
        assertEquals(0L, r.attendance().earlyLeaveMillis());
        assertNull(r.attendance().absence());
        assertEquals("정상", AttendanceLabel.of(r.attendance()));
    }
    @Test void oneSecondAfterStartIsLateAndRoundsUpToOneMinute() {
        var r = run(List.of(new ClockEvent(UUID.randomUUID(), EventKind.IN, at("08:00").plusSeconds(1)),
                new ClockEvent(UUID.randomUUID(), EventKind.OUT, at("17:00"))),
                fixed("08:00", "17:00", "12:00", "13:00"), breaks("12:00", "13:00"), 0, null, true, false);
        assertEquals(1000L, r.attendance().lateMillis());
        assertTrue(r.reasons().contains("LATE"));
        assertEquals(WorktimeCalculator.Status.CALCULATED, r.status());
        assertEquals("지각 1분", AttendanceLabel.of(r.attendance()));
    }
    @Test void lateAndEarlyLeaveAreBothShown() {
        var r = run(events("08:07", "16:30"), fixed("08:00", "17:00", "12:00", "13:00"), breaks("12:00", "13:00"), 0, null, true, false);
        assertEquals(7 * MIN, r.attendance().lateMillis());
        assertEquals(30 * MIN, r.attendance().earlyLeaveMillis());
        assertEquals("지각 7분 · 조퇴 30분", AttendanceLabel.of(r.attendance()));
    }
    @Test void morningHalfDayUsesFirstBreakEndAsStart() {
        var r = run(events("13:00", "17:00"), fixed("08:00", "17:00", "12:00", "13:00"), breaks(), 0.5, "AM", true, false);
        assertEquals(0L, r.attendance().lateMillis());
        var late = run(events("13:10", "17:00"), fixed("08:00", "17:00", "12:00", "13:00"), breaks(), 0.5, "AM", true, false);
        assertEquals(10 * MIN, late.attendance().lateMillis());
    }
    @Test void afternoonHalfDayUsesFirstBreakStartAsEnd() {
        var r = run(events("08:00", "12:00"), fixed("08:00", "17:00", "12:00", "13:00"), breaks(), 0.5, "PM", true, false);
        assertEquals(0L, r.attendance().earlyLeaveMillis());
    }
    @Test void codeWithoutBreakUsesMidpointForHalfDay() {
        var r = run(events("08:00", "12:00"), fixed("08:00", "16:00"), breaks(), 0.5, "PM", true, false);
        assertEquals(0L, r.attendance().earlyLeaveMillis());
        var am = run(events("12:30", "16:00"), fixed("08:00", "16:00"), breaks(), 0.5, "AM", true, false);
        assertEquals(30 * MIN, am.attendance().lateMillis());
    }
    @Test void endedWorkdayWithoutRecordsIsAbsentWithZeroWork() {
        var r = run(List.of(), fixed("08:00", "17:00", "12:00", "13:00"), null, 0, null, true, false);
        assertEquals("FULL", r.attendance().absence());
        assertEquals(0L, r.actualWorkMillis());
        assertTrue(r.reasons().contains("ABSENT"));
        assertFalse(r.reasons().contains("MISSING_CLOCK_IN"));
        assertEquals(WorktimeCalculator.Status.CALCULATED, r.status());
        assertEquals("결근", AttendanceLabel.of(r.attendance()));
    }
    @Test void halfDayLeaveWithoutRecordsIsHalfAbsent() {
        var r = run(List.of(), fixed("08:00", "17:00", "12:00", "13:00"), null, 0.5, "AM", true, false);
        assertEquals("HALF", r.attendance().absence());
        assertEquals("결근(반일)", AttendanceLabel.of(r.attendance()));
    }
    @Test void todayWithoutRecordsIsNotAbsentYet() {
        var r = run(List.of(), fixed("08:00", "17:00", "12:00", "13:00"), null, 0, null, false, false);
        assertNull(r.attendance().absence());
        assertTrue(r.reasons().contains("MISSING_CLOCK_IN"));
    }
    @Test void pendingCorrectionKeepsReviewInsteadOfAbsence() {
        var r = run(List.of(), fixed("08:00", "17:00", "12:00", "13:00"), null, 0, null, true, true);
        assertNull(r.attendance().absence());
        assertTrue(r.reasons().contains("PENDING_CORRECTION"));
    }
    @Test void notJudgedOnHolidayFullLeaveAndSelective() {
        var holiday = calculator.day(new DayInput(DATE, List.of(), List.of(fixed("08:00", "17:00")), DayKind.HOLIDAY, true, 0, 0, null, false, 0, 0),
                new AttendanceContext(true, null));
        assertEquals(Attendance.NONE, holiday.attendance());
        var leave = run(List.of(), fixed("08:00", "17:00"), null, 1, "FULL", true, false);
        assertEquals(Attendance.NONE, leave.attendance());
        var selective = new Schedule(true, at("07:00"), at("22:00"), List.of(), true, 480, null);
        var sel = calculator.day(new DayInput(DATE, events("10:00", "19:00"), List.of(selective), DayKind.WORKDAY, true, 0, 0, breaks(), false, 0, 0),
                new AttendanceContext(true, null));
        assertEquals(Attendance.NONE, sel.attendance());
        assertNull(AttendanceLabel.of(Attendance.NONE));
    }
    @Test void onlyClockInJudgesLatenessAndKeepsReview() {
        var r = run(events("08:05", null), fixed("08:00", "17:00"), null, 0, null, true, false);
        assertEquals(5 * MIN, r.attendance().lateMillis());
        assertNull(r.attendance().earlyLeaveMillis());
        assertTrue(r.reasons().contains("MISSING_CLOCK_OUT"));
        assertEquals(WorktimeCalculator.Status.REVIEW_REQUIRED, r.status());
    }
    @Test void legacyDayOverloadStillWorks() {
        var r = calculator.day(new DayInput(DATE, events("08:00", "17:00"), List.of(fixed("08:00", "17:00", "12:00", "13:00")),
                DayKind.WORKDAY, true, 0, 0, breaks("12:00", "13:00"), false, 0, 0));
        assertEquals(8 * 3_600_000L, r.actualWorkMillis());
        assertNotNull(r.attendance());
    }
}
```

- [ ] **Step 2: 실패 확인** — `mvn-test.ps1 -q test -Dtest=AttendanceJudgementTest` → 컴파일 실패(`AttendanceContext`, `AttendanceLabel` 없음).

- [ ] **Step 3: 계산기 구현**

`WorktimeCalculator.java`에서:

1. 상수와 레코드 추가(`DayResult` 위):
```java
    public record AttendanceContext(boolean dayEnded, String leavePart) {
        public static final AttendanceContext NONE = new AttendanceContext(false, null);
    }
    /** 지각·조퇴는 판정하지 않으면 null, 판정했으면 0 이상. absence는 FULL/HALF/null. */
    public record Attendance(Long lateMillis, Long earlyLeaveMillis, String absence) {
        public static final Attendance NONE = new Attendance(null, null, null);
    }
```
2. `INFORMATIONAL`에 `"ABSENT", "LATE", "EARLY_LEAVE"`를 추가한다.
3. `DayResult`에 마지막 구성요소 `Attendance attendance`를 추가하고, 기존 16개 인자 생성자를 유지한다:
```java
    public record DayResult(LocalDate workDate, Status status, Long scheduledMillis, Long presenceMillis,
                            Long actualWorkMillis, Long confirmedBreakMillis, Long outsideScheduleMillis,
                            Long over8hMillis, Long nightWorkMillis, Long holidayWorkMillis, Long legalOvertimeMillis,
                            double leaveDays, Long paidLeaveMillis, long inputRevision, long reviewRevision, Set<String> reasons,
                            Attendance attendance) {
        public DayResult {
            if (attendance == null) attendance = Attendance.NONE;
        }
        public DayResult(LocalDate workDate, Status status, Long scheduledMillis, Long presenceMillis,
                         Long actualWorkMillis, Long confirmedBreakMillis, Long outsideScheduleMillis,
                         Long over8hMillis, Long nightWorkMillis, Long holidayWorkMillis, Long legalOvertimeMillis,
                         double leaveDays, Long paidLeaveMillis, long inputRevision, long reviewRevision, Set<String> reasons) {
            this(workDate, status, scheduledMillis, presenceMillis, actualWorkMillis, confirmedBreakMillis, outsideScheduleMillis,
                    over8hMillis, nightWorkMillis, holidayWorkMillis, legalOvertimeMillis, leaveDays, paidLeaveMillis,
                    inputRevision, reviewRevision, reasons, Attendance.NONE);
        }
    }
```
4. 기존 `public DayResult day(DayInput input)`를 `public DayResult day(DayInput input, AttendanceContext context)`로 바꾸고, 아래 오버로드를 추가한다:
```java
    public DayResult day(DayInput input) { return day(input, AttendanceContext.NONE); }
```
5. 새 `day` 안의 무기록(`noEvents`) 분기를 아래로 바꾼다:
```java
        boolean judged = scheduledWork != null && !selective && schedule.workday() && scheduled != null && scheduled > 0
                && input.dayKind() == DayKind.WORKDAY && input.approvedLeaveDays() < 1;
        String absence = null;
        if (noEvents) {
            if (scheduled!=null && input.dayKind()!=DayKind.UNCONFIRMED && !input.pendingCorrection()
                    && (nonWorkday || input.approvedLeaveDays()==1)) {
                actual=0L; confirmedBreak=0L; outside=0L; night=0L; holiday=0L;
                reasons.add(input.approvedLeaveDays()==1?"APPROVED_LEAVE_NO_ATTENDANCE":"NO_CLOCK_ON_CONFIRMED_NON_WORKDAY");
            } else if (judged && context.dayEnded() && !input.pendingCorrection()) {
                actual=0L; confirmedBreak=0L; outside=0L; night=0L; holiday=0L;
                absence = input.approvedLeaveDays() == 0.5 ? "HALF" : "FULL";
                reasons.add("ABSENT");
            } else if (!input.pendingCorrection()) {
                reasons.add("MISSING_CLOCK_IN"); reasons.add("MISSING_CLOCK_OUT");
            }
        }
```
(기존 `else { ... events ... }` 분기는 그대로 `else`로 이어 붙인다. `judged`는 `noEvents` 계산 바로 뒤, `nonWorkday` 다음 줄에 둔다.)
6. 반환 직전, 지각·조퇴를 계산하고 상태 판정 뒤 `Attendance`를 넣는다:
```java
        Long late = null, early = null;
        if (judged && !reasons.contains("DUPLICATE_CLOCK_EVENT") && !reasons.contains("INVALID_CLOCK_ORDER")
                && !reasons.contains("CROSS_MIDNIGHT_UNSUPPORTED")) {
            Instant expectedStart = schedule.start(), expectedEnd = schedule.end();
            Interval first = schedule.plannedBreaks().stream().min(Comparator.comparing(Interval::start)).orElse(null);
            Instant middle = schedule.start().plus(Duration.between(schedule.start(), schedule.end()).dividedBy(2));
            if ("AM".equals(context.leavePart())) expectedStart = first == null ? middle : first.end();
            if ("PM".equals(context.leavePart())) expectedEnd = first == null ? middle : first.start();
            if (ins.size() == 1 && sameDay(ins.getFirst().occurredAt(), ins.getFirst().occurredAt(), input.workDate())) {
                late = Math.max(0L, Duration.between(expectedStart, ins.getFirst().occurredAt()).toMillis());
                if (late > 0) reasons.add("LATE");
            }
            if (outs.size() == 1 && sameDay(outs.getFirst().occurredAt(), outs.getFirst().occurredAt(), input.workDate())) {
                early = Math.max(0L, Duration.between(outs.getFirst().occurredAt(), expectedEnd).toMillis());
                if (early > 0) reasons.add("EARLY_LEAVE");
            }
        }
        Attendance attendance = absence != null ? new Attendance(null, null, absence)
                : late == null && early == null ? Attendance.NONE : new Attendance(late, early, null);
```
`ins`/`outs`가 `noEvents` 분기 밖에서 이미 선언돼 있는지 확인한다(현재 `noEvents` 계산 전에 선언됨). `sameDay(a,b,date)`는 기존 private 메서드다. 이 블록은 `boolean review=...` 줄 **앞**에 둬서 `LATE`/`EARLY_LEAVE`가 이유 집합에 포함되게 한다(정보성이므로 상태를 바꾸지 않는다). 마지막 `return new DayResult(...)`에 `, attendance`를 덧붙인다.
7. 클래스 주석의 `CALC-1`을 `CALC-2`로 바꾼다.

`src/main/java/kr/tna/worktime/AttendanceLabel.java` 생성:
```java
package kr.tna.worktime;

import kr.tna.worktime.WorktimeCalculator.Attendance;

/** 판정값을 화면 문구로 바꾼다. 분은 올림(08:00:01 출근 = 지각 1분). 판정하지 않은 날은 null. */
public final class AttendanceLabel {
    private AttendanceLabel() { }

    public static String of(Attendance a) {
        if (a == null || a.equals(Attendance.NONE)) return null;
        if ("FULL".equals(a.absence())) return "결근";
        if ("HALF".equals(a.absence())) return "결근(반일)";
        var parts = new java.util.ArrayList<String>();
        if (a.lateMillis() != null && a.lateMillis() > 0) parts.add("지각 " + minutes(a.lateMillis()) + "분");
        if (a.earlyLeaveMillis() != null && a.earlyLeaveMillis() > 0) parts.add("조퇴 " + minutes(a.earlyLeaveMillis()) + "분");
        return parts.isEmpty() ? "정상" : String.join(" · ", parts);
    }

    public static String of(Long lateMillis, Long earlyLeaveMillis, String absence) {
        return of(new Attendance(lateMillis, earlyLeaveMillis, absence));
    }

    static long minutes(long millis) { return (millis + 59_999L) / 60_000L; }
}
```

- [ ] **Step 4: 통과 확인** — `mvn-test.ps1 -q test -Dtest=AttendanceJudgementTest,WorktimeCalculatorTest,SelectiveWorktimeTest` → 모두 PASS. 기존 테스트가 깨지면 계산기 변경이 기존 사유·상태를 바꾼 것이므로 고친다(테스트를 고치지 않는다).

- [ ] **Step 5: 커밋**
```bash
git add src/main/java/kr/tna/worktime/WorktimeCalculator.java src/main/java/kr/tna/worktime/AttendanceLabel.java src/test/java/kr/tna/worktime/AttendanceJudgementTest.java
git commit -m "feat: judge lateness, early leave and absence in the calculator"
```

---

### Task 2: V42 마이그레이션

**Files:**
- Create: `src/main/resources/db/migration/V42__daily_attendance_automation.sql`
- Create: `src/test/java/kr/tna/DailyAttendanceSchemaIT.java`
- Modify: `src/test/java/kr/tna/MigrationRunnerIT.java` (마이그레이션 수 41 → 42; 파일에서 `41`을 검색해 버전 수 단언만 바꾼다)

- [ ] **Step 1: 실패하는 테스트**

```java
package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class DailyAttendanceSchemaIT {
    JdbcTemplate db;
    UUID tenant, employee, account;

    @BeforeEach void seed() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID(); employee = UUID.randomUUID(); account = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 일일근태')", tenant, "DAILY-" + tenant);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'D1','합성 사원',DATE '2026-01-01')", tenant, employee);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-hash')", tenant, account, employee, account + "@example.test");
        db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision,recalculation_required) VALUES (?,?,DATE '2026-11-23',1,0,true)", tenant, employee);
    }

    private int confirm(String method, UUID by) {
        return db.update("""
            INSERT INTO tna.work_confirmation(tenant_id,id,employee_id,work_date,confirmed_input_revision,review_revision,
              source_fingerprint,presence_as_work,breaks_match,confirmed_by,break_snapshot,client_command_id,method)
            VALUES (?,?,?,DATE '2026-11-23',1,1,?,true,true,?,'[]'::jsonb,?,?)
            """, tenant, UUID.randomUUID(), employee, "a".repeat(64), by, UUID.randomUUID(), method);
    }

    @Test void autoConfirmationMayHaveNoAccountButEmployeeConfirmationMust() {
        assertEquals(1, confirm("AUTO_SCHEDULE", null));
        db.update("UPDATE tna.day_input_revision SET review_revision=1 WHERE tenant_id=?", tenant);
        assertThrows(DataIntegrityViolationException.class, () -> confirm("EMPLOYEE", null));
        assertThrows(DataIntegrityViolationException.class, () -> confirm("GUESS", account));
    }

    @Test void existingConfirmationsDefaultToEmployeeAndResultColumnsExist() {
        assertEquals("EMPLOYEE", db.queryForObject(
            "SELECT column_default FROM information_schema.columns WHERE table_schema='tna' AND table_name='work_confirmation' AND column_name='method'",
            String.class).replace("::character varying", "").replace("'", ""));
        assertEquals(3, db.queryForObject("""
            SELECT count(*) FROM information_schema.columns WHERE table_schema='tna' AND table_name='day_result'
              AND column_name IN ('late_millis','early_leave_millis','absence')""", Integer.class));
        assertEquals("YES", db.queryForObject("""
            SELECT is_nullable FROM information_schema.columns WHERE table_schema='tna' AND table_name='calculation_run' AND column_name='requested_by'""", String.class));
    }

    @Test void resultAttendanceValuesAreChecked() {
        UUID run = UUID.randomUUID();
        db.update("INSERT INTO tna.calculation_run(tenant_id,id,employee_id,requested_by,from_date,to_date,expanded_from,expanded_to,engine_version) VALUES (?,?,?,NULL,DATE '2026-11-23',DATE '2026-11-23',DATE '2026-11-23',DATE '2026-11-23','CALC-2')",
                tenant, run, employee);
        String insert = """
            INSERT INTO tna.day_result(tenant_id,id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
              source_fingerprint,rule_version,status,leave_days,reason_codes,input_refs,late_millis,early_leave_millis,absence)
            VALUES (?,?,?,?,DATE '2026-11-23',?,1,0,?,'CALC-2','CALCULATED',0,'[]'::jsonb,'[]'::jsonb,?,?,?)""";
        assertEquals(1, db.update(insert, tenant, UUID.randomUUID(), run, employee, 1, "b".repeat(64), 0L, 0L, null));
        assertThrows(DataIntegrityViolationException.class, () -> db.update(insert, tenant, UUID.randomUUID(), run, employee, 2, "b".repeat(64), -1L, 0L, null));
        assertThrows(DataIntegrityViolationException.class, () -> db.update(insert, tenant, UUID.randomUUID(), run, employee, 3, "b".repeat(64), null, null, "SOME"));
    }
}
```

- [ ] **Step 2: 실패 확인** — `mvn-test.ps1 -q verify -Dit.test=DailyAttendanceSchemaIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false` → `method` 열 없음으로 FAIL. (`day_result`의 다른 NOT NULL 열 때문에 INSERT가 실패하면 V7 정의를 읽어 테스트 INSERT의 열을 보완한다.)

- [ ] **Step 3: 마이그레이션 작성**

```sql
-- B 묶음: 휴게 자동 적용, 지각·조퇴·결근, 시스템(새벽 일괄) 계산.
ALTER TABLE tna.day_result
    ADD COLUMN late_millis bigint,
    ADD COLUMN early_leave_millis bigint,
    ADD COLUMN absence varchar(10),
    ADD CONSTRAINT day_result_late_valid CHECK (late_millis IS NULL OR late_millis >= 0),
    ADD CONSTRAINT day_result_early_leave_valid CHECK (early_leave_millis IS NULL OR early_leave_millis >= 0),
    ADD CONSTRAINT day_result_absence_valid CHECK (absence IS NULL OR absence IN ('FULL','HALF'));

ALTER TABLE tna.work_confirmation
    ADD COLUMN method varchar(20) NOT NULL DEFAULT 'EMPLOYEE',
    ALTER COLUMN confirmed_by DROP NOT NULL,
    ADD CONSTRAINT work_confirmation_method_valid CHECK (method IN ('EMPLOYEE','AUTO_SCHEDULE')),
    ADD CONSTRAINT work_confirmation_employee_has_actor CHECK (method <> 'EMPLOYEE' OR confirmed_by IS NOT NULL);

-- NULL = 시스템(새벽 일괄) 실행.
ALTER TABLE tna.calculation_run ALTER COLUMN requested_by DROP NOT NULL;
```
기존 `tna_app`의 `work_confirmation`·`day_result`·`calculation_run` 권한이 열 단위 GRANT라면(`grep -n "GRANT" src/main/resources/db/migration/V5__attendance_recording.sql V7__worktime_results.sql`로 확인), 새 열에 대한 같은 권한(INSERT/SELECT)을 이 파일에 추가한다. 테이블 단위 GRANT면 추가하지 않는다.

- [ ] **Step 4: 통과 확인** — 같은 명령 + `-Dit.test=DailyAttendanceSchemaIT,MigrationRunnerIT` → PASS.

- [ ] **Step 5: 커밋**
```bash
git add src/main/resources/db/migration/V42__daily_attendance_automation.sql src/test/java/kr/tna/DailyAttendanceSchemaIT.java src/test/java/kr/tna/MigrationRunnerIT.java
git commit -m "feat: add attendance judgement columns and automatic confirmation method"
```

---

### Task 3: 판정 저장·조회와 `CALC-2`

**Files:**
- Modify: `src/main/java/kr/tna/worktime/WorktimeMapper.java`
- Modify: `src/main/java/kr/tna/worktime/WorktimeService.java`
- Modify: `src/main/java/kr/tna/worktime/AuditSummary.java` (`ruleVersion`/`engineVersion` 허용값에 `CALC-2` 추가: `names("CALC-1 CALC-2")`)
- Create: `src/test/java/kr/tna/DailyAttendanceResultIT.java`

- [ ] **Step 1: 실패하는 통합 테스트**

`HolidayWebIT`의 시드 방식(테넌트·사원·계정·역할 직접 INSERT, `@SpringBootTest` + `DatabaseFixture` 프로퍼티)을 그대로 따른다. 근무일·일정·휴일 반영은 기존 `CalendarSeed`와 일정 시드 헬퍼를 사용한다 — `grep -rln "CalendarSeed\." src/test/java | head`로 사용 예(예: `WorktimeServiceIT` 또는 `AttendanceWebIT`)를 찾아 같은 방식으로 2026-11-23(월) 08:00~17:00, 휴게 12:00~13:00 고정 일정과 근로일 달력을 만든다. 시계는 `@MockitoBean Clock`이 아니라 기존 테스트가 쓰는 방식(있으면 `MutableClock`/`TestClock` 빈)을 따른다; 없으면 2026-11-25 기준 실제 날짜 대신 **과거 날짜(2026-11-23)** 를 쓰고 '날이 지났다'는 오늘(`LocalDate.now(Asia/Seoul)`) 기준으로 충분하다.

```java
@Test void recalculationStoresLatenessAndAbsence() {
    // 11-23: 08:07 출근, 17:00 퇴근, 휴게 확인(직원 확인 1건) → 지각 7분
    // 11-24: 기록 없음 → 결근
    // 담당자 재집계: worktime.recalculate(manager, employee, 11-23, 11-24, UUID.randomUUID(), "합성 재집계")
    var rows = db.queryForList("""
        SELECT DISTINCT ON(work_date) work_date, late_millis, early_leave_millis, absence, rule_version, actual_work_millis
        FROM tna.day_result WHERE tenant_id=? AND employee_id=? AND work_date IN (DATE '2026-11-23', DATE '2026-11-24')
        ORDER BY work_date, result_revision DESC""", tenant, employee);
    assertEquals(420000L, rows.get(0).get("late_millis"));
    assertEquals(0L, rows.get(0).get("early_leave_millis"));
    assertEquals("CALC-2", rows.get(0).get("rule_version"));
    assertEquals("FULL", rows.get(1).get("absence"));
    assertEquals(0L, rows.get(1).get("actual_work_millis"));
    // 조회 경로도 판정값을 돌려준다
    var days = worktime.days(manager, employee, LocalDate.of(2026,11,23), LocalDate.of(2026,11,24));
    assertEquals(420000L, days.get(0).result().attendance().lateMillis());
    assertEquals("FULL", days.get(1).result().attendance().absence());
}
@Test void halfDayLeavePartReachesTheCalculator() {
    // 11-23 오전 반차 승인 효력(leave_request_day.leave_part='AM', quantity_days=0.5)과 13:05 출근, 17:00 퇴근
    // → late_millis = 300000 (기준 13:00)
}
```
반차 효력 시드는 `grep -rn "leave_part" src/test/java | head`로 찾은 기존 IT의 INSERT를 재사용한다.

- [ ] **Step 2: 실패 확인** — `-Dit.test=DailyAttendanceResultIT` → `late_millis` NULL 또는 `CALC-1`로 FAIL.

- [ ] **Step 3: 구현**

`WorktimeMapper.java`:
1. `leaveEffects` SELECT에 `d.leave_part`를 추가하고 레코드를 `record LeaveEffect(String id, double quantityDays, String recognitionRule, String payKind, String leavePart)`로 바꾼다. 정적 메서드 추가:
```java
        /** FULL 또는 합계 1일 이상이면 FULL, 반차 하나면 AM/PM, 없으면 null. */
        public static String part(List<LeaveEffect> effects) {
            if (effects.isEmpty()) return null;
            if (totalDays(effects) >= 1 || effects.stream().anyMatch(e -> e.leavePart() == null || "FULL".equals(e.leavePart()))) return "FULL";
            return effects.getFirst().leavePart();
        }
```
`new LeaveEffect(` 호출처가 있으면(`grep -rn "new WorktimeMapper.LeaveEffect\|new LeaveEffect(" src`) 마지막 인자 `null`을 추가한다.
2. `insertRun`의 `'CALC-1'` → `'CALC-2'`.
3. `insertResult`의 열 목록에 `,late_millis,early_leave_millis,absence`를, VALUES에 `,#{r.attendance.lateMillis},#{r.attendance.earlyLeaveMillis},#{r.attendance.absence}`를 추가하고 `'CALC-1'` → `'CALC-2'`.
4. `latest`와 `result` SELECT에 `late_millis,early_leave_millis,absence,`를 `leave_days,` 앞에 추가하고, `ResultRow`의 `legalOvertimeMillis` 다음에 `Long lateMillis,Long earlyLeaveMillis,String absence,`를 같은 순서로 추가한다. `ResultRow`를 생성하는 다른 SELECT가 있으면(`grep -n "ResultRow" src/main/java -r`) 같은 열을 넣는다.

`WorktimeService.java`:
1. `Input` 레코드에 `WorktimeCalculator.AttendanceContext context`를 마지막 구성요소로 추가한다. `input(...)`에서:
```java
        var context=new WorktimeCalculator.AttendanceContext(date.isBefore(LocalDate.now(clock.withZone(SEOUL))),
                WorktimeMapper.LeaveEffect.part(effects));
        return new Input(snapshot,policy,assessment,leave,pending,day,source,context);
```
2. 결과를 계산하는 모든 곳에서 `calculator.day(input.day())`를 `calculator.day(input.day(),input.context())`로 바꾼다(`grep -n "calculator.day(" WorktimeService.java`). `approvedPaidLeaveMillis(DayInput)`처럼 유급휴가만 보는 곳은 그대로 둔다. `calculate`의 `refs`에는 문맥을 넣지 않는다(지문 형식 불변).
3. `storedResult`에서 마지막 인자로 `new WorktimeCalculator.Attendance(r.lateMillis(),r.earlyLeaveMillis(),r.absence())`를 넘긴다.
4. `calculate`의 `"ruleVersion","CALC-1"` → `"CALC-2"`, 검토용 Excel의 `"규칙","CALC-1"` → `"CALC-2"`. 결과가 없는 `DayView`의 `"CALC-1"` 기본값(526·533행 부근)은 `"CALC-2"`로 바꾼다.
5. `currentResult`가 만드는 오래된 결과는 판정값을 버린다(`Attendance.NONE`, 기존 16인자 생성자 그대로라 변경 불필요).

- [ ] **Step 4: 통과 확인** — `-Dit.test=DailyAttendanceResultIT,WorktimeServiceIT,MonthEndReviewIT` (존재하는 것만) 및 `-Dtest=WorktimeCalculatorTest,AuditSummaryBatchTest` → PASS. `"CALC-1"`을 기대하던 기존 테스트가 새로 계산한 결과에 대해 실패하면 기대값을 `CALC-2`로 바꾼다(과거 저장 결과를 읽는 테스트는 그대로).

- [ ] **Step 5: 커밋** — `git commit -m "feat: store attendance judgement with CALC-2 results"`

---

### Task 4: 퇴근 시 휴게 자동 적용과 즉시 집계

**Files:**
- Modify: `src/main/java/kr/tna/clock/ClockMapper.java` (`insertConfirmation`에 `method` 인자)
- Modify: `src/main/java/kr/tna/clock/ClockService.java`
- Modify: `src/main/java/kr/tna/clock/AttendanceController.java`
- Create: `src/test/java/kr/tna/AutoBreakConfirmationIT.java`

- [ ] **Step 1: 실패하는 통합 테스트** (시드는 Task 3과 같은 방식. 퇴근은 `POST /me/attendance/events`를 MockMvc로 호출하거나 `clocks.record(...)`를 직접 호출한다. 허용 네트워크 시드는 `grep -rn "attendance_network\|addNetwork" src/test/java | head`의 기존 예를 따른다. 시계가 실제 시각이면 '오늘'에 출근·퇴근을 기록하고 오늘 일정의 출근을 현재보다 이르게 둔다.)

```java
@Test void clockOutConfirmsScheduledBreaksAndCalculatesImmediately() {
    // 출근 → 퇴근
    var c = db.queryForMap("SELECT method, confirmed_by, break_snapshot::text AS b FROM tna.work_confirmation WHERE tenant_id=? AND employee_id=?", tenant, employee);
    assertEquals("AUTO_SCHEDULE", c.get("method"));
    assertEquals(account, c.get("confirmed_by"));
    assertNotNull(db.queryForObject("SELECT actual_work_millis FROM tna.day_result WHERE tenant_id=? AND employee_id=? AND work_date=? ORDER BY result_revision DESC LIMIT 1", Long.class, tenant, employee, today));
}
@Test void breaksOutsidePresenceAreClipped() {
    // 일정 08:00~17:00, 휴게 12:00~13:00, 실제 12:30~17:00 → break_snapshot 이 12:30~13:00 한 구간
}
@Test void autoConfirmationIsSkippedWhenRecordsAreIncomplete() {
    // 출근 없이 퇴근만 기록(이전 날짜의 원천이 없도록) → work_confirmation 0건, 퇴근 기록은 저장됨
}
@Test void replayingTheSameClockOutDoesNotDuplicate() {
    // 같은 commandId로 퇴근 두 번 → work_confirmation 1건, WORKTIME_AUTO_CALCULATE 감사 1건
}
```

- [ ] **Step 2: 실패 확인** — `-Dit.test=AutoBreakConfirmationIT` → 확인 0건으로 FAIL.

- [ ] **Step 3: 구현**

`ClockMapper.insertConfirmation`: 열 목록에 `,method`, VALUES에 `,#{method}`를 추가하고 `@Param("method") String method` 인자를 끝에 추가한다. 기존 `confirm(...)` 호출은 `"EMPLOYEE"`를 넘긴다.

`ClockService`에 추가(`confirmationCheck` 옆):
```java
    /**
     * 예정 휴게 중 출퇴근 구간 안의 부분을 실제 휴게로 자동 확인한다. 조건을 못 갖추면 아무것도 하지 않고 false.
     * 호출하는 쪽의 트랜잭션·회사 컨텍스트 안에서 실행된다. confirmedBy가 null이면 시스템이다.
     */
    public boolean autoConfirm(UUID tenantId, UUID employeeId, LocalDate date, UUID confirmedBy) {
        DaySnapshot day = snapshot(tenantId, employeeId, date);
        Instant now = clock.instant();
        if (day.confirmation() != null || date.isAfter(now.atZone(SEOUL).toLocalDate())) return false;
        var breaks = autoBreaks(day, now);
        if (breaks == null) return false;
        if (mapper.advanceReview(tenantId, employeeId, date, day.inputRevision(), day.reviewRevision()) != 1) return false;
        UUID command = UUID.nameUUIDFromBytes(("AUTO_SCHEDULE:" + tenantId + ":" + employeeId + ":" + date + ":" + day.inputRevision())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        changed(mapper.insertConfirmation(tenantId, UUID.randomUUID(), employeeId, date, day.inputRevision(), day.reviewRevision() + 1,
                day.sourceFingerprint(), now.truncatedTo(ChronoUnit.MILLIS), confirmedBy, json.writeValueAsString(breaks), command, "AUTO_SCHEDULE"));
        return true;
    }
    /** 직원 확인과 같은 출퇴근 조건. 휴게는 고정·선택 모두 출퇴근 구간으로 자른다. 조건 미달이면 null. */
    private static List<Interval> autoBreaks(DaySnapshot day, Instant now) {
        var ins = day.effectiveEvents().stream().filter(e -> e.kind().equals("IN")).toList();
        var outs = day.effectiveEvents().stream().filter(e -> e.kind().equals("OUT")).toList();
        if (ins.size() != 1 || outs.size() != 1 || day.schedule() == null || day.calendarVersionId() == null) return null;
        Instant start = ins.getFirst().occurredAt(), end = outs.getFirst().occurredAt();
        if (!start.isBefore(end) || !start.atZone(SEOUL).toLocalDate().equals(day.workDate())
                || !end.atZone(SEOUL).toLocalDate().equals(day.workDate()) || end.isAfter(now)) return null;
        return day.schedule().breaks().stream().map(b -> new Interval(day.workDate().atTime(b.startTime()).atZone(SEOUL).toInstant(),
                        day.workDate().atTime(b.endTime()).atZone(SEOUL).toInstant()))
                .map(b -> new Interval(b.startAt().isBefore(start) ? start : b.startAt(), b.endAt().isAfter(end) ? end : b.endAt()))
                .filter(b -> b.startAt().isBefore(b.endAt())).toList();
    }
```
(`advanceReview`가 0을 반환하는 경쟁 상황은 false로 넘긴다. `changed`·`json`·`clock`·`SEOUL`은 클래스에 이미 있다.)

`ClockService.record`의 변경 람다에서 `changed(mapper.advanceInput(...));` 다음 줄에:
```java
            if (kind.equals("OUT")) autoConfirm(actor.tenantId(), actor.employeeId(), date, actor.accountId());
```

`AttendanceController.event`: 기록 성공 뒤 퇴근이면 계산한다. 두 분기 모두 다음 도우미를 쓴다:
```java
    private void afterRecord(TnaPrincipal actor, String eventKind, UUID commandId) {
        if (!"OUT".equals(eventKind)) return;
        try { worktime.calculateConfirmedDay(actor, commands.today(), commandId); }
        catch (RuntimeException failed) { LOG.warn("event=worktime_auto_calculate outcome=failed status={} cause={}", failed instanceof OrgException e ? e.status() : 500, failed.getClass().getSimpleName()); }
    }
```
`clocks.record(...)` 호출 바로 뒤에 `afterRecord(actor,eventKind,commandId);`를 넣는다(예외가 난 기록에는 호출하지 않는다). `calculateConfirmedDay`의 감사 사유 문구를 `"퇴근·근무 확인 후 자동 계산"`으로 바꾼다.

- [ ] **Step 4: 통과 확인** — `-Dit.test=AutoBreakConfirmationIT,AttendanceWebIT,ClockServiceIT`(존재하는 것만) → PASS. 기존 테스트가 "퇴근 후 확인 전" 상태를 기대하면, 그 테스트가 직원 확인 흐름을 검증하려는 것인지 확인하고 퇴근 전에 확인하도록 옮기지 말고 **기대값을 자동 확인 상태로** 바꾼다. 변경한 테스트 목록을 보고서에 적는다.

- [ ] **Step 5: 커밋** — `git commit -m "feat: apply scheduled breaks on clock-out and calculate immediately"`

---

### Task 5: 결재 처리 직후 재계산

**Files:**
- Modify: `src/main/java/kr/tna/worktime/WorktimeMapper.java`, `WorktimeService.java`
- Modify: `src/main/java/kr/tna/request/RequestController.java`
- Create: `src/test/java/kr/tna/ApprovalRecalculationIT.java`

- [ ] **Step 1: 실패하는 통합 테스트** — 11-24 무기록(결근 결과 저장) 상태에서 그날 종일 연차를 신청·승인(`POST /requests/{id}/approve`, 기존 `LeaveWorkflowIT`의 신청·승인 시드 재사용)하면, 응답 직후 최신 결과의 `absence`가 NULL이고 `reason_codes`에 `APPROVED_LEAVE_NO_ATTENDANCE`가 있다. 반려(`reject`)·철회(`withdraw`) 뒤에도 예외 없이 재계산된다(결과 행 수 증가).

- [ ] **Step 2: 실패 확인** — 승인 직후 결과가 여전히 `FULL`이라 FAIL.

- [ ] **Step 3: 구현**

`WorktimeMapper`:
```java
    @Select("SELECT target_employee_id FROM tna.approval_request WHERE tenant_id=#{tenantId} AND id=#{id}")
    UUID requestTarget(@Param("tenantId") UUID tenantId,@Param("id") UUID id);
    @Select("""
        SELECT work_date FROM tna.day_input_revision
        WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} AND recalculation_required AND work_date<=#{today}
        ORDER BY work_date
        """)
    List<LocalDate> recalculationDates(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("today") LocalDate today);
```

`WorktimeService` — 시스템 계산 경로(Task 6도 사용):
```java
    /**
     * 사람 감사 없이 지정 날짜만 계산한다(주 확장 없음, 마감 월 제외). 호출하는 쪽이 회사 트랜잭션을 연다.
     * requestedBy가 null이면 시스템 실행. 이미 현재이고 그날이 끝난 뒤 계산된 결과는 건너뛴다.
     */
    public int calculateDatesAsSystem(UUID tenant,UUID requestedBy,UUID employeeId,java.util.Collection<LocalDate> dates) {
        mapper.lock(tenant);
        Employee employee=employee(tenant,employeeId);
        LocalDate today=LocalDate.now(clock.withZone(SEOUL));
        var targets=new java.util.TreeSet<LocalDate>();
        for(LocalDate d:dates) if(d!=null&&!d.isAfter(today)&&employed(employee,d)) targets.add(d);
        if(targets.isEmpty())return 0;
        var closed=Set.copyOf(mapper.closedMonths(tenant,targets.first(),targets.last()));
        targets.removeIf(d->closed.contains(d.withDayOfMonth(1)));
        var stored=latestDays(tenant,employee,targets.isEmpty()?today:targets.first(),targets.isEmpty()?today:targets.last());
        var todo=new ArrayList<LocalDate>();
        for(LocalDate d:targets) {
            var in=input(tenant,employee,d);var saved=stored.get(d);
            Instant dayEnd=d.plusDays(1).atStartOfDay(SEOUL).toInstant();
            // CALC-1 결과나 날이 끝나기 전에 계산된 결과는 결근 판정이 빠져 있으므로 다시 계산한다.
            boolean fresh=saved!=null&&"CALC-2".equals(saved.ruleVersion())
                    &&current(in.clock(),in.source(),saved.inputRevision(),saved.reviewRevision(),saved.sourceFingerprint())
                    &&(d.equals(today)||!saved.calculatedAt().isBefore(dayEnd));
            if(!fresh)todo.add(d);
        }
        if(todo.isEmpty())return 0;
        UUID runId=UUID.randomUUID();
        changed(mapper.insertRun(tenant,runId,employee.id(),requestedBy,todo.getFirst(),todo.getLast(),todo.getFirst(),todo.getLast()));
        for(LocalDate d:todo) storeResult(tenant,runId,employee,d);
        changed(mapper.completeRun(tenant,runId));
        return todo.size();
    }
```
`latestDays`가 반환하는 값의 타입·필드명(`inputRevision()`, `reviewRevision()`, `sourceFingerprint()`, `calculatedAt()`)을 확인하고 맞춘다(`ResultRow`면 그대로 맞음). 기존 `calculate(...)`의 for 루프 본문(결과 INSERT, `mapper.calculated`, 퇴근 보완 정리)을 `private void storeResult(UUID tenant,UUID runId,Employee employee,LocalDate date)`로 추출하고 `calculate`도 이를 호출하게 바꾼다(동작 불변, `actor.tenantId()` → `tenant`).

결재 후 재계산:
```java
    /** 결재 처리 직후 대상 사원의 재집계 필요 날짜를 계산한다. 실패해도 결재는 유지된다. */
    public void recalculateAfterDecision(TnaPrincipal actor,UUID requestId) {
        if(actor==null||requestId==null)return;
        try {
            transactions.execute(actor.tenantId(),()->{
                UUID target=mapper.requestTarget(actor.tenantId(),requestId);
                if(target==null)return 0;
                return calculateDatesAsSystem(actor.tenantId(),actor.accountId(),target,
                        mapper.recalculationDates(actor.tenantId(),target,LocalDate.now(clock.withZone(SEOUL))));
            });
        } catch(RuntimeException failed) {
            LOG.warn("event=worktime_decision_recalculate outcome=failed cause={}",failed.getClass().getSimpleName());
        }
    }
```
`WorktimeService`에 `TenantTransactions transactions`(빈이 있는지 `grep -rn "TenantTransactions(" src/main/java/kr/tna/common/config` 확인)와 `Logger LOG`를 생성자 주입으로 추가한다. 생성자를 직접 호출하는 테스트가 있으면 인자를 추가한다.

`RequestController`: `submitDecision`을 호출하는 approve/reject/withdraw와 `cancellation`, `exceptionalCancellation`에서 서비스 호출이 성공한 뒤 `worktime.recalculateAfterDecision(actor,id)`를 부른다. 예: `() -> { var result = flow.decide(actor, id, decision); worktime.recalculateAfterDecision(actor, id); return result; }`. 취소 신청은 원신청 id(`id`)를 넘긴다. 정정 신청 제출(`/requests/corrections` 류, `flow.submitCorrection`)에도 제출된 신청 id로 호출한다(대기 정정이 생기면 결근이 '정정 대기'로 바뀌어야 함). `RequestController` 생성자에 `WorktimeService worktime`을 추가한다.

- [ ] **Step 4: 통과 확인** — `-Dit.test=ApprovalRecalculationIT,LeaveWorkflowIT,CorrectionWorkflowIT`(존재하는 것만) → PASS(이미 알려진 날짜 의존 실패 `monthlyLeaveMustCoverEachUsageDateAtSubmissionAndAtDelayedApproval`는 예외).

- [ ] **Step 5: 커밋** — `git commit -m "feat: recalculate affected days right after approval decisions"`

---

### Task 6: 새벽 일괄 작업

**Files:**
- Create: `src/main/java/kr/tna/worktime/DailyAttendanceBatch.java`
- Modify: `src/main/java/kr/tna/worktime/WorktimeMapper.java`
- Modify: `src/main/java/kr/tna/TnaApplication.java` (`@EnableScheduling`)
- Modify: `src/main/resources/application.yml`(또는 properties) — `tna.daily-batch.enabled: true`; 테스트 설정(`src/test/resources/application*.yml`이 있으면)에 `false`
- Create: `src/test/java/kr/tna/DailyAttendanceBatchIT.java`

- [ ] **Step 1: 실패하는 통합 테스트**

```java
@Test void batchMarksYesterdayAbsenceAndAutoConfirmsLeftoverDays() {
    // 회사 A: 사원1 어제 무기록, 사원2 어제 출퇴근 있음·확인 없음(직접 INSERT로 원천만 넣음)
    // 회사 B: 사원3 어제 무기록
    batch.runOnce();
    // 사원1: absence FULL, calculation_run.requested_by IS NULL
    // 사원2: work_confirmation method AUTO_SCHEDULE, confirmed_by NULL, actual_work_millis NOT NULL
    // 사원3: absence FULL (회사 B도 처리, 서로의 자료를 섞지 않음: 각 결과의 tenant_id 확인)
}
@Test void runningTwiceWritesNothingNew() {
    batch.runOnce(); long rows = countResults();
    batch.runOnce(); assertEquals(rows, countResults());
}
@Test void closedMonthIsSkipped() {
    // 지난달을 마감(기존 월 마감 시드 재사용)하고 그 달 날짜를 recalculation_required=true로 둔 뒤 runOnce → 그 날짜 결과 없음
}
@Test void concurrentRunsExecuteOnce() {
    // 두 스레드에서 runOnce 동시 호출 → 한쪽은 false(잠금 실패) 반환
}
```

- [ ] **Step 2: 실패 확인** — `DailyAttendanceBatch` 없음으로 컴파일 FAIL.

- [ ] **Step 3: 구현**

`WorktimeMapper` 추가:
```java
    @Select("""
        SELECT e.id FROM tna.employee e
        WHERE e.tenant_id=#{tenantId} AND e.joined_on<=#{to} AND (e.left_on IS NULL OR e.left_on>=#{from})
        ORDER BY e.id
        """)
    List<UUID> employedBetween(@Param("tenantId") UUID tenantId,@Param("from") LocalDate from,@Param("to") LocalDate to);
```
(`tna.employee`에 `active` 열이 있고 비활성 사원을 제외해야 하면 조건을 추가한다 — `BusinessCommands.requireCurrent`는 `e.active`를 쓴다.)

회사 목록은 트랜잭션 밖에서 `authDataSource`(`tna_auth`, `tenant_registry`에 회사 조회 정책 `USING (true)` 보유)로 `id WHERE active`만 읽는다. `tna_app`은 RLS 때문에 현재 회사 행만 보이며, 이를 넓히는 GRANT·정책은 추가하지 않는다.

`DailyAttendanceBatch.java`:
```java
package kr.tna.worktime;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.clock.ClockService;
import kr.tna.common.tenant.TenantTransactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 00:10 서울: 지난 7일과 재집계 필요 날짜를 회사별로 자동 확인·계산한다. 여러 서버에서도 세션 잠금으로 한 번만 실행된다. */
@Component
@ConditionalOnProperty(name = "tna.daily-batch.enabled", havingValue = "true", matchIfMissing = true)
public class DailyAttendanceBatch {
    private static final Logger LOG = LoggerFactory.getLogger(DailyAttendanceBatch.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long LOCK_KEY = 0x544E4144_41494C59L; // "TNADAILY"
    // ponytail: 지난 7일만 다시 본다. 서버가 7일 넘게 멈췄던 구간은 담당자 수동 재집계로 복구한다.
    private static final int LOOKBACK_DAYS = 7;

    private final DataSource dataSource;
    private final TenantTransactions transactions;
    private final WorktimeMapper mapper;
    private final WorktimeService worktime;
    private final ClockService clocks;
    private final Clock clock;

    public DailyAttendanceBatch(@Qualifier("dataSource") DataSource dataSource, TenantTransactions transactions,
            WorktimeMapper mapper, WorktimeService worktime, ClockService clocks, Clock clock) {
        this.dataSource = dataSource; this.transactions = transactions; this.mapper = mapper;
        this.worktime = worktime; this.clocks = clocks; this.clock = clock;
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Seoul")
    public void nightly() { runOnce(); }

    /** @return 잠금을 얻어 실행했으면 true, 다른 실행이 진행 중이면 false */
    public boolean runOnce() {
        try (var connection = dataSource.getConnection()) {
            var lock = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            if (!Boolean.TRUE.equals(lock.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY))) return false;
            try {
                LocalDate today = LocalDate.now(clock.withZone(SEOUL));
                int failed = 0;
                for (UUID tenant : tenants()) {
                    try { runTenant(tenant, today); }
                    catch (RuntimeException e) { failed++; LOG.warn("event=daily_attendance_batch outcome=tenant_failed cause={}", e.getClass().getSimpleName()); }
                }
                LOG.info("event=daily_attendance_batch outcome=completed failedTenants={}", failed);
                return true;
            } finally {
                lock.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
            }
        } catch (java.sql.SQLException e) {
            LOG.warn("event=daily_attendance_batch outcome=storage_unavailable");
            return false;
        }
    }

    private List<UUID> tenants() {
        return new JdbcTemplate(dataSource).queryForList("SELECT id FROM platform.tenant_registry WHERE active ORDER BY id", UUID.class);
    }

    private void runTenant(UUID tenant, LocalDate today) {
        LocalDate from = today.minusDays(LOOKBACK_DAYS), yesterday = today.minusDays(1);
        List<UUID> employees = transactions.execute(tenant, () -> mapper.employedBetween(tenant, from, yesterday));
        for (UUID employee : employees) {
            // 사원마다 짧은 트랜잭션: 회사 잠금을 오래 잡지 않는다.
            transactions.execute(tenant, () -> {
                var dates = new ArrayList<LocalDate>();
                for (LocalDate d = from; !d.isAfter(yesterday); d = d.plusDays(1)) dates.add(d);
                mapper.lockForWrite(tenant); // 자동 확인(공유 잠금)보다 먼저 쓰기 잠금을 잡아 잠금 승격을 피한다
                dates.addAll(mapper.recalculationDates(tenant, employee, today.minusDays(62), yesterday));
                for (LocalDate d : dates) clocks.autoConfirm(tenant, employee, d, null);
                return worktime.calculateDatesAsSystem(tenant, null, employee, dates);
            });
        }
    }
}
```
주의: `autoConfirm`은 마감 월을 막지 않는다. `ClockMapper`/`WorktimeMapper.closedMonths`로 마감 월이면 건너뛰도록 `runTenant`에서 `dates`를 걸러 낸 뒤 자동 확인한다:
```java
                var closed = java.util.Set.copyOf(mapper.closedMonths(tenant, dates.stream().min(LocalDate::compareTo).orElse(yesterday), yesterday));
                dates.removeIf(d -> closed.contains(d.withDayOfMonth(1)));
```
(`closedMonths`의 반환 타입을 확인하고 맞춘다.) 재직 기간 밖 날짜의 자동 확인은 원천이 없으므로 아무것도 하지 않는다.

`TnaApplication`에 `@org.springframework.scheduling.annotation.EnableScheduling`을 붙인다. 테스트 프로필에서 스케줄러가 실제로 돌지 않도록 `src/test/resources`의 Spring 설정에 `tna.daily-batch.enabled: false`를 넣고, `DailyAttendanceBatchIT`만 `@SpringBootTest(properties = "tna.daily-batch.enabled=true")`로 빈을 받아 `runOnce()`를 직접 호출한다(크론은 00:10이라 테스트 중 자동 실행되지 않는다).

- [ ] **Step 4: 통과 확인** — `-Dit.test=DailyAttendanceBatchIT` → PASS.

- [ ] **Step 5: 커밋** — `git commit -m "feat: run nightly automatic confirmation and attendance calculation"`

---

### Task 7: 직원 화면 — 근태 표시와 확인 버튼 제거

**Files:**
- Modify: `src/main/java/kr/tna/clock/AttendanceHistory.java`, `src/main/java/kr/tna/worktime/WorktimeService.java` (`attendanceHistory`)
- Modify: `src/main/java/kr/tna/workspace/WorkspaceData.java`, `WorkspaceQueries.java`
- Modify: `frontend/src/pages/AttendancePeriodPage.tsx`, `frontend/src/pages/CorePages.tsx`, `frontend/src/pages/WorkflowPages.tsx`, `frontend/src/pages/WorkflowTypes.ts`와 해당 `*.test.tsx`

- [ ] **Step 1: 실패하는 테스트**
  - `AttendanceNavigationTest`(또는 `AttendanceHistory` 단위 테스트)에 추가: 현재 결과의 `Attendance(420000,0,null)` → 행의 `attendanceLabel` = `"지각 7분"`; 오늘·근로일·출근 없음·예정 출근 지남 → `"미출근"`; 오늘·예정 출근 전 → `null`.
  - `AttendancePeriodPage.test.tsx`: 행 데이터에 `attendanceLabel: '지각 7분'`이 있으면 '근태' 열에 그 문구가 보인다.
  - `CorePages.test.tsx`(홈): 주간 일정 항목에 `attendanceLabel: '결근'`이 있으면 표시된다.
  - `WorkflowPages` 테스트: 출퇴근 기록 상세에 '근무 확인' 버튼이 없고, 확인이 있는 날 "예정 휴게가 자동 적용되었습니다. 다르면 정정을 신청해 주세요." 문구가 보인다.

- [ ] **Step 2: 실패 확인** — `mvn-test.ps1 -q test -Dtest=AttendanceNavigationTest`, `npx vitest run src/pages/AttendancePeriodPage.test.tsx src/pages/CorePages.test.tsx src/pages/WorkflowPages*.test.tsx`.

- [ ] **Step 3: 구현**
  - `AttendanceHistory.Row`에 마지막 구성요소 `String attendanceLabel`을 추가한다. `row(...)`에 인자 `WorktimeCalculator.Attendance attendance, Instant now`를 추가하고:
```java
        String label = AttendanceLabel.of(attendance);
        if (label == null && date.equals(today) && scheduleKind == ScheduleKind.WORKDAY && in == null && schedule != null
                && !"SELECTIVE".equals(schedule.workMode()) && schedule.startTime() != null
                && now.isAfter(date.atTime(schedule.startTime()).atZone(SEOUL).toInstant())) label = "미출근";
```
    `WorktimeService.attendanceHistory`는 `view.current()`이면 `view.result().attendance()`, 아니면 `Attendance.NONE`과 `clock.instant()`를 넘긴다. `Row`/`row(`를 쓰는 테스트 호출을 함께 고친다.
  - `WorkspaceData.DayView`에 `String attendanceLabel` 구성요소를 추가하고, `WorkspaceQueries`에서 주간 `DayView`를 만들 때(93행) 저장된 최신 결과를 읽어 넣는다. 새 매퍼 메서드(WorktimeMapper 또는 `PersonalScheduleMapper`):
```java
    @Select("""
        SELECT DISTINCT ON(work_date) work_date, late_millis, early_leave_millis, absence FROM tna.day_result
        WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} AND work_date BETWEEN #{from} AND #{to}
        ORDER BY work_date, result_revision DESC
        """)
    List<AttendanceMark> attendanceMarks(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("from") LocalDate from,@Param("to") LocalDate to);
    record AttendanceMark(LocalDate workDate, Long lateMillis, Long earlyLeaveMillis, String absence) { }
```
    // ponytail: 홈은 최신 저장 결과를 그대로 쓴다(최신성 검사 생략). 새벽 일괄·즉시 계산이 결과를 갱신한다.
    오늘은 위 '미출근' 규칙을 같은 방식으로 적용한다(오늘 결과가 없을 때).
  - `AttendancePeriodPage.tsx`: 표에 '근태' 열을 추가해 `row.attendanceLabel ?? '-'`를 보인다. '지각'/'결근'이 들어간 문구는 경고 색(기존 상태 배지 클래스 재사용)으로 표시한다.
  - `CorePages.tsx`(홈 이번 주 일정, 502행 부근): `entry.attendanceLabel`이 있으면 기존 `Bar` 옆에 `<Bar kind={entry.attendanceLabel === '정상' ? 'ok' : 'warn'}>{entry.attendanceLabel}</Bar>`(`Bar`의 실제 `kind` 값을 확인해 맞춘다).
  - `WorkflowPages.tsx` 1221~1280행: '근무 확인' `Form` 블록을 삭제한다. `d.confirmation != null`이면 `<p>예정 휴게가 자동 적용되었습니다. 다르면 정정을 신청해 주세요.</p>`를 보인다. `WorkflowTypes.ts`의 `canConfirm`/`confirmationBreaks`는 다른 사용처가 없으면 제거한다. 서버의 `/me/work-confirmations` 엔드포인트는 남긴다(이전 화면·스크립트 호환).

- [ ] **Step 4: 통과 확인** — 위 테스트 PASS, `npm run build` 성공.

- [ ] **Step 5: 커밋** — `git commit -m "feat: show attendance status to employees and drop the daily confirm button"`

---

### Task 8: 담당자 '지각·조퇴·결근' 탭

**Files:**
- Create: `src/main/java/kr/tna/workspace/AttendanceExceptionQueries.java`
- Modify: `src/main/java/kr/tna/workspace/ProfileCompanyController.java`, `src/main/java/kr/tna/common/web/ReactPageViewResolver.java`(페이지 데이터 키 `attendanceExceptions` 허용)
- Modify: `frontend/src/pages/CompanyAttendanceNavigation.tsx`, `frontend/src/App.tsx`(company/attendance 라우팅에서 `attendanceExceptions`가 있으면 새 페이지)
- Create: `frontend/src/pages/AttendanceExceptionsPage.tsx`, `AttendanceExceptionsPage.test.tsx`
- Create: `src/test/java/kr/tna/AttendanceExceptionsWebIT.java`

- [ ] **Step 1: 실패하는 테스트**
  - WebIT: 담당자가 `GET /company/attendance?view=exceptions&month=2026-11&kind=ALL` → 200, 페이지 데이터 `/attendanceExceptions/counts/late`=1, `/absent`=1, 행 2개(사번·이름·날짜·문구). `kind=ABSENT`면 1행. 일반 직원 403. 잘못된 month → 422. 다른 회사 결과는 보이지 않는다.
  - Vitest: 요약(지각 n · 조퇴 n · 결근 n), 구분 선택(전체/지각/조퇴/결근), 행의 날짜를 누르면 `/company/attendance?employeeId=…&month=…`(월별 근무현황)로 간다.

- [ ] **Step 2: 실패 확인**

- [ ] **Step 3: 구현**

`AttendanceExceptionQueries.java`(권한·회사 트랜잭션은 같은 패키지의 `CheckoutOperationsQueries`가 쓰는 방식을 그대로 따른다 — 생성자 주입 객체와 `read` 호출 패턴을 복사):
```java
    static final List<String> KINDS = List.of("ALL", "LATE", "EARLY_LEAVE", "ABSENT");
    public record Row(UUID employeeId, String employeeNo, String employeeName, LocalDate workDate, String label) { }
    public record Counts(int late, int earlyLeave, int absent) { }
    public record View(String month, String kind, Counts counts, List<Row> rows, String error) { }
```
SQL(새 매퍼 인터페이스 `AttendanceExceptionMapper` 또는 기존 `ProfileCompanyMapper`에 추가):
```sql
SELECT r.employee_id, e.employee_no, e.display_name AS employee_name, r.work_date, r.late_millis, r.early_leave_millis, r.absence
FROM (SELECT DISTINCT ON (employee_id, work_date) employee_id, work_date, late_millis, early_leave_millis, absence
      FROM tna.day_result WHERE tenant_id=#{tenantId} AND work_date >= #{from} AND work_date < #{to}
      ORDER BY employee_id, work_date, result_revision DESC) r
JOIN tna.employee e ON e.tenant_id=#{tenantId} AND e.id=r.employee_id
WHERE r.late_millis > 0 OR r.early_leave_millis > 0 OR r.absence IS NOT NULL
ORDER BY r.work_date DESC, e.employee_no
LIMIT 1000
```
// ponytail: 한 달 최대 1000행, 페이지 없음. 넘으면 페이지를 추가한다.
월 형식 오류는 `error`에 "조회 월을 확인해 주세요."를 넣고 컨트롤러가 422로 응답한다(월말 확인 탭과 같은 방식). 구분 필터와 건수는 Java에서 계산하고, 문구는 `AttendanceLabel.of(...)`를 쓴다.

컨트롤러:
```java
    @GetMapping(value = "/company/attendance", params = "view=exceptions")
    ModelAndView exceptions(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "ALL") String kind) {
        var result = exceptions.read(actor, month, kind);
        var view = new ModelAndView("company/attendance").addObject("canViewCheckout", true).addObject("attendanceExceptions", result);
        if (result.error() != null) view.setStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        return view;
    }
```
기존 기본 매핑의 `params`에 `"view!=exceptions"`를 추가한다. `ReactPageViewResolver`의 `company/attendance` 허용 키 목록에 `"attendanceExceptions"`를 추가한다.

`CompanyAttendanceViews`에 탭 `<a href={path + '?view=exceptions'}>지각·조퇴·결근</a>`과 `active` 값 `'exceptions'`를 추가한다. `attendanceListReturn`은 이번 범위에서 바꾸지 않는다.

`AttendanceExceptionsPage.tsx`는 `MonthEndReviewPage.tsx`의 구조(월 선택 폼, `WorklogSheet` 또는 표, 상단 탭)를 따라 작게 만든다: 월 입력, 구분 select, 요약 한 줄, 표(사번·이름·근무일·근태).

- [ ] **Step 4: 통과 확인** — WebIT·Vitest PASS, 빌드 성공.

- [ ] **Step 5: 커밋** — `git commit -m "feat: add lateness, early leave and absence view for attendance admins"`

---

### Task 9: 검토용 Excel 열

**Files:**
- Modify: `src/main/java/kr/tna/worktime/WorktimeService.java` (`prepareExport`)
- Test: 기존 검토용 Excel 테스트(`grep -rln "유급휴가 인정(ms)" src/test`)에 단언 추가

- [ ] **Step 1: 실패하는 테스트** — 내보낸 일 결과 머리글 마지막 세 칸이 `"지각(분)","조퇴(분)","결근"`이고, 지각 7분인 행의 해당 칸이 `7`, 결근 행이 `"결근"`, 판정 없는 행은 빈 칸이 아니라 `"미확정"`(기존 null 표기 규칙을 확인해 같은 문자열)이다.

- [ ] **Step 2: 실패 확인**

- [ ] **Step 3: 구현** — 일 결과 머리글 `row(...)`의 마지막 `"유급휴가 인정(ms)"` 뒤에 `"지각(분)","조퇴(분)","결근"`을 추가하고, 각 행의 마지막 `r.paidLeaveMillis()` 뒤에:
```java
                        attendanceMinutes(day.current()?r.attendance().lateMillis():null),
                        attendanceMinutes(day.current()?r.attendance().earlyLeaveMillis():null),
                        day.current()&&r.attendance().absence()!=null?("FULL".equals(r.attendance().absence())?"결근":"결근(반일)"):null
```
```java
    private static Long attendanceMinutes(Long millis) {return millis==null?null:(millis+59_999L)/60_000L;}
```
(null은 기존 `ReviewWorkbook`이 미확정 문자열로 쓴다.)

- [ ] **Step 4: 통과 확인**

- [ ] **Step 5: 커밋** — `git commit -m "feat: export attendance judgement in the review workbook"`

---

### Task 10: 문서·전체 회귀·브라우저 확인

**Files:**
- Modify: `docs/rules/calculation.md` (§3.1 실제 근로 확인 행, §4.1 76·124·128행 부근 문장, §4.2 136·138행, C02·C04 사례), `docs/design/worktime-results.md` (자동 계산 문단: 퇴근·결재 직후·새벽 일괄)
- Create: `docs/quality/daily-attendance-20261125.md`
- Modify: `README.md`의 현재 상태 목록에 한 줄(있으면)

- [ ] **Step 1: 문서 반영** — 계산 규칙에 다음을 반영한다.
  - 퇴근 시 예정 휴게 중 출퇴근 구간 안의 부분을 자동 확인한다(`AUTO_SCHEDULE`). 실제와 다르면 정정한다.
  - 지각·조퇴는 근무시간코드 기준으로 유예 없이 판정한다. 반차일은 첫 휴게, 휴게가 없으면 가운데 시각이 기준이다.
  - 날이 지난 무기록 근로일은 결근(실근로 0)이다. 대기 정정이 있으면 예외다.
  - C02는 지각 7분, C04는 자동 확인 후 실근로 8시간으로 바꾼다.
  - 새 사례 C40~C44(결근, 반일 결근, 오늘 무기록, 오전 반차 기준, 선택근무 미판정)를 추가한다.
  - 규칙 버전은 `CALC-2`다.
- [ ] **Step 2: 전체 회귀** — 테스트 DB 컨테이너를 새로 띄운 상태(컨트롤러가 준비)에서 `mvn-test.ps1 verify`, `cd frontend; npx vitest run; npm run build`. 기대: 기존 알려진 오류 13건 외 실패 0건.
- [ ] **Step 3: 브라우저 확인**(컨트롤러 세션이 수행) — `run-app.ps1`로 앱을 띄운다.
  - PC
    - 직원 출근·퇴근 → 출퇴근 기록에서 근무시간 즉시 표시, '근무 확인' 버튼 없음
    - 근무시간코드보다 늦은 출근 → '지각 n분'
    - 무기록 지난 근로일 → '결근'(일괄 `runOnce`를 테스트용으로 부르는 수단이 없으므로, 앱 기동 뒤 담당자 수동 재집계로 대체하고 그 사실을 기록)
    - 담당자 '지각·조퇴·결근' 탭의 건수·필터
  - 모바일(390×844): 같은 화면이 깨지지 않는지 확인
- [ ] **Step 4: 결과 문서** — `docs/quality/daily-attendance-20261125.md`에 회귀 수치, 브라우저 확인 표, 실행하지 못한 검증(스케줄러 실제 00:10 실행, 고정 이미지 테스트, 다중 서버 잠금의 실제 환경)을 적는다.
- [ ] **Step 5: 커밋** — `git commit -m "docs: record daily attendance automation rules and verification"`

---

## 자기 점검

- 설계 §2(자동 적용): Task 4, 6. §3(판정): Task 1, 3. §4(즉시·결재 후·새벽): Task 4, 5, 6. §5(V42·화면·Excel): Task 2, 7, 8, 9. §6 경계(휴일 변경·마감 월·정정 후 해소): Task 5·6 테스트. §7 검증: 각 작업 테스트와 Task 10.
- 타입 이름: `AttendanceContext(dayEnded, leavePart)`, `Attendance(lateMillis, earlyLeaveMillis, absence)`, `AttendanceLabel.of`, `calculateDatesAsSystem(tenant, requestedBy, employeeId, dates)`, `recalculateAfterDecision(actor, requestId)`, `ClockService.autoConfirm(tenant, employee, date, confirmedBy)`, `DailyAttendanceBatch.runOnce()`가 작업 사이에서 같다.
