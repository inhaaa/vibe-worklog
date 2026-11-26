# 연장근무 인정·주 52시간·급여용 근태 집계 (C 묶음) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 승인된 연장 구간 안의 실제 근로를 '인정 연장'으로 일별 계산·저장하고, 주 12시간 초과 연장 신청을 막고, 담당자에게 주 52시간 점검 탭과 월 마감 Excel의 급여용 근태 열을 제공한다.

**Architecture:** 계산기 `WorktimeCalculator`의 `DayInput`에 승인 연장 구간(연장 휴게를 뺀 순구간)을 넣고 `DayResult.approvedOvertimeMillis`를 만든다. `WorktimeService.input()`이 그날의 승인 연장을 읽어 넘기고, 결과는 `day_result.approved_overtime_millis`(V44, 규칙 `CALC-3`)에 저장된다. 연장 최종 승인·취소 승인은 그날의 검토 개정을 올려(`reviseDay`) 기존 결재 직후 재계산이 다시 계산하게 한다. 월 마감은 DB 함수가 마감 때 새 열을 스냅샷한다(V45).

**Tech Stack:** Spring Boot 4.1 / Java 25, MyBatis annotation mapper, PostgreSQL 18 + Flyway, React 19 + Vitest, JUnit 5 + 외부 테스트 DB(`DatabaseFixture`).

설계: [docs/superpowers/specs/2026-11-26-overtime-payroll-design.md](../specs/2026-11-26-overtime-payroll-design.md)

---

## 공통 실행 방법

- 작업 디렉터리: `C:\Users\ihpark\dev\vibe-attendance\target\claude-opening-prep-20261123` (브랜치 `claude/opening-prep-20261123`). 브랜치를 바꾸지 않는다. Push 금지. bare `git stash` 금지.
- Maven은 **Bash 도구에서** 스크립트로 실행한다(PowerShell 도구는 `-D` 인자를 망가뜨린다). 한 번에 하나만 실행한다.
  - 단위: `powershell -NoProfile -File "C:\Users\ihpark\AppData\Local\Temp\claude\c6d09512e35b8bb6\ff996283-e18f-4a51-b946-d1e31871cf61\scratchpad\mvn-test.ps1" -q test -Dfrontend.skip=true -Dspring-boot.repackage.skip=true -Dtest=A,B`
  - 통합: `... mvn-test.ps1 -q verify -Dfrontend.skip=true -Dspring-boot.repackage.skip=true -Dit.test=A,B -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`
  - `-q`가 아무것도 출력하지 않으면 `target/failsafe-reports`·`target/surefire-reports`에서 이번에 돌린 클래스만 읽는다(예전 보고서가 남아 있다).
- 프런트엔드: `cd frontend; npx vitest run <파일>`; 빌드 `npm run build`.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 회사 격리·RLS·권한을 약화하지 않는다. 개인정보를 로그·감사·페이지 모델에 넣지 않는다.
- 시험에서 `approval_request`를 SQL로 직접 넣을 때 연장이 아닌 종류는 `attendance_type_version_id`가 필요하다(`NotificationSchemaIT.seed()` 참고).

## 파일 구조

| 파일 | 책임 |
| --- | --- |
| `src/main/resources/db/migration/V44__overtime_recognition.sql` (생성) | `day_result.approved_overtime_millis`, CALC-3 허용, 월 마감 행 새 열 |
| `src/main/resources/db/migration/V45__payroll_month_close_values.sql` (생성) | 월 마감 집계 함수·스냅샷 트리거에 새 열 |
| `src/main/java/kr/tna/worktime/WorktimeCalculator.java` (수정) | `DayInput.approvedOvertime`, `DayResult.approvedOvertimeMillis` |
| `src/main/java/kr/tna/worktime/OvertimeIntervals.java` (생성) | 승인 연장 행 → 연장 휴게를 뺀 순구간(순수 함수) |
| `src/main/java/kr/tna/worktime/WorktimeService.java`, `WorktimeMapper.java` (수정) | 승인 연장 읽기, 저장 열, CALC-3 |
| `src/main/java/kr/tna/worktime/AuditSummary.java` (수정) | CALC-3 허용 |
| `src/main/java/kr/tna/request/LeaveWorkflowService.java`, `LeaveWorkflowMapper.java` (수정) | 승인·취소 때 날짜 표시, 주 12시간 검사 |
| `src/main/java/kr/tna/overtime/OvertimeDirectory.java` (수정) | 미리보기 12시간 검사, 신청현황 '인정 시간' |
| `src/main/java/kr/tna/worktime/MonthCloseExportMapper.java`, `MonthCloseWorkbook.java` (수정) | 급여용 열 |
| `src/main/java/kr/tna/workspace/WeeklyHoursQueries.java` (생성), `ProfileCompanyMapper.java`, `ProfileCompanyController.java` (수정) | '주 52시간' 탭 |
| `frontend/src/pages/WeeklyHoursPage.tsx` (생성), `CompanyAttendanceNavigation.tsx`, `IdentityPages.tsx`, `OvertimePage.tsx` (수정) | 화면 |

---

### Task 1: V44 스키마 (인정 연장 열, CALC-3, 월 마감 새 열)

**Files:**
- Create: `src/main/resources/db/migration/V44__overtime_recognition.sql`
- Create: `src/test/java/kr/tna/OvertimeRecognitionSchemaIT.java`
- Modify: `src/test/java/kr/tna/MigrationRunnerIT.java:31-36` (43 → 44)

- [ ] **Step 1: 실패하는 스키마 시험** (`DailyAttendanceSchemaIT` 구조를 따른다: 관리자 `JdbcTemplate`, 합성 회사·사원·계정 seed)

```java
package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class OvertimeRecognitionSchemaIT {
    JdbcTemplate db;
    UUID tenant, employee, run;

    @BeforeEach void seed() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID(); employee = UUID.randomUUID(); run = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 연장인정')", tenant, "OTREC-" + tenant);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'R1','합성 사원',DATE '2026-01-01')", tenant, employee);
        db.update("INSERT INTO tna.calculation_run(tenant_id,id,employee_id,requested_by,from_date,to_date,expanded_from,expanded_to,engine_version) VALUES (?,?,?,NULL,DATE '2026-11-23',DATE '2026-11-23',DATE '2026-11-23',DATE '2026-11-23','CALC-3')",
                tenant, run, employee);
    }

    private int result(int revision, String rule, Long approvedOvertime) {
        return db.update("""
            INSERT INTO tna.day_result(tenant_id,id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
              source_fingerprint,rule_version,status,leave_days,reason_codes,input_refs,approved_overtime_millis)
            VALUES (?,?,?,?,DATE '2026-11-23',?,1,0,?,?,'CALCULATED',0,'[]'::jsonb,'[]'::jsonb,?)""",
            tenant, UUID.randomUUID(), run, employee, revision, "c".repeat(64), rule, approvedOvertime);
    }

    @Test void calc3ResultsCarryApprovedOvertimeAndOlderRulesStayValid() {
        assertEquals(1, result(1, "CALC-3", 7_200_000L));
        assertEquals(1, result(2, "CALC-2", null));
        assertThrows(DataIntegrityViolationException.class, () -> result(3, "CALC-3", -1L));
        assertThrows(DataIntegrityViolationException.class, () -> result(4, "CALC-9", null));
    }

    @Test void monthCloseRowsHavePayrollColumns() {
        assertEquals(10, db.queryForObject("""
            SELECT count(*) FROM information_schema.columns WHERE table_schema='tna' AND table_name='attendance_month_export_row'
              AND column_name IN ('payroll_captured','approved_overtime_millis','unapproved_extra_millis','night_work_millis',
                'holiday_work_millis','late_count','late_millis','early_leave_count','early_leave_millis','absence_days')""", Integer.class));
        assertEquals("false", db.queryForObject("""
            SELECT column_default FROM information_schema.columns WHERE table_schema='tna' AND table_name='attendance_month_export_row'
              AND column_name='payroll_captured'""", String.class));
    }
}
```

`calculation_run`/`day_result`의 다른 NOT NULL 열 때문에 INSERT가 실패하면 `DailyAttendanceSchemaIT`의 INSERT 열을 그대로 맞춘다.

- [ ] **Step 2: 실패 확인** — `-Dit.test=OvertimeRecognitionSchemaIT` → `CALC-3` 검사 위반 또는 열 없음으로 FAIL.

- [ ] **Step 3: 마이그레이션**

```sql
-- 연장근무 인정(설계 2026-11-26-overtime-payroll-design.md §2·§5): 일별 인정 연장, 규칙 CALC-3, 월 마감 급여용 열
ALTER TABLE tna.day_result
    ADD COLUMN approved_overtime_millis bigint,
    ADD CONSTRAINT day_result_approved_overtime_nonnegative CHECK (approved_overtime_millis IS NULL OR approved_overtime_millis >= 0);

ALTER TABLE tna.calculation_run DROP CONSTRAINT calculation_run_engine_status_valid;
ALTER TABLE tna.day_result DROP CONSTRAINT day_result_fingerprint_rule_valid;
```

그다음 V42(`src/main/resources/db/migration/V42__daily_attendance_automation.sql:32-37`)의 두 제약을 **같은 이름·같은 식**으로 다시 만들되 허용 목록에 `'CALC-3'`을 더한다(`status IN (...)` 부분은 V42 그대로 복사).

```sql
ALTER TABLE tna.attendance_month_export_row
    ADD COLUMN payroll_captured boolean NOT NULL DEFAULT false,
    ADD COLUMN approved_overtime_millis bigint CHECK (approved_overtime_millis >= 0),
    ADD COLUMN unapproved_extra_millis bigint CHECK (unapproved_extra_millis >= 0),
    ADD COLUMN night_work_millis bigint CHECK (night_work_millis >= 0),
    ADD COLUMN holiday_work_millis bigint CHECK (holiday_work_millis >= 0),
    ADD COLUMN late_count integer CHECK (late_count >= 0),
    ADD COLUMN late_millis bigint CHECK (late_millis >= 0),
    ADD COLUMN early_leave_count integer CHECK (early_leave_count >= 0),
    ADD COLUMN early_leave_millis bigint CHECK (early_leave_millis >= 0),
    ADD COLUMN absence_days numeric CHECK (absence_days >= 0);
```

`tna_app`의 `day_result` 열 권한이 열 단위로 주어져 있으면(`grep -n "ON tna.day_result TO tna_app" src/main/resources/db/migration/*.sql`) `approved_overtime_millis`에 같은 SELECT·INSERT 권한을 준다. `attendance_month_export_row`는 테이블 단위 SELECT·INSERT라 추가 권한이 없다.

`MigrationRunnerIT`의 `43`과 `'43'`을 `44`, `'44'`로 바꾼다.

- [ ] **Step 4: 통과 확인** — `-Dit.test=OvertimeRecognitionSchemaIT,DailyAttendanceSchemaIT,MigrationRunnerIT,DatabaseFoundationIT` → PASS.

- [ ] **Step 5: 커밋** — `feat: add approved overtime result column and payroll month close columns`

---

### Task 2: 계산기의 인정 연장과 순구간 도우미

**Files:**
- Modify: `src/main/java/kr/tna/worktime/WorktimeCalculator.java` (`DayInput` :41-50, `DayResult` :58-74, `day()` 본문)
- Create: `src/main/java/kr/tna/worktime/OvertimeIntervals.java`
- Create: `src/test/java/kr/tna/worktime/ApprovedOvertimeTest.java`

- [ ] **Step 1: 실패하는 단위 시험**

```java
package kr.tna.worktime;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kr.tna.worktime.WorktimeCalculator.*;
import org.junit.jupiter.api.Test;

class ApprovedOvertimeTest {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final LocalDate DAY = LocalDate.of(2026, 11, 23); // 월요일
    static final long MIN = 60_000L;
    final WorktimeCalculator calculator = new WorktimeCalculator();

    static java.time.Instant at(int h, int m) { return DAY.atTime(h, m).atZone(SEOUL).toInstant(); }
    static Interval iv(int h1, int m1, int h2, int m2) { return new Interval(at(h1, m1), at(h2, m2)); }

    DayResult day(int outH, int outM, List<Interval> approved) {
        var events = List.of(new ClockEvent(UUID.randomUUID(), EventKind.IN, at(9, 0)),
                new ClockEvent(UUID.randomUUID(), EventKind.OUT, at(outH, outM)));
        var schedule = new Schedule(true, at(9, 0), at(18, 0), List.of(iv(12, 0, 13, 0)));
        var confirmation = new Confirmation(1, 0, true, List.of(iv(12, 0, 13, 0)));
        return calculator.day(new DayInput(DAY, events, List.of(schedule), DayKind.WORKDAY, true, 1, 0, confirmation,
                false, 0, 0, approved), AttendanceContext.NONE);
    }

    @Test void recognizesOnlyApprovedTimeActuallyWorked() {
        // 18:00~23:00 승인(연장 휴게 20:00~20:15 제외) · 22:40 퇴근 → 4시간 25분
        var approved = OvertimeIntervals.net(DAY, List.of(new OvertimeIntervals.Approved(LocalTime.of(18, 0), LocalTime.of(23, 0),
                List.of(new OvertimeIntervals.Pause(LocalTime.of(20, 0), LocalTime.of(20, 15))))));
        assertEquals(265 * MIN, day(22, 40, approved).approvedOvertimeMillis());
    }

    @Test void noApprovalMeansZeroAndUnapprovedWorkStaysOutsideSchedule() {
        var result = day(21, 0, List.of());
        assertEquals(0L, result.approvedOvertimeMillis());
        assertEquals(180 * MIN, result.outsideScheduleMillis());
    }

    @Test void leavingBeforeTheApprovedStartRecognizesNothing() {
        var approved = OvertimeIntervals.net(DAY, List.of(new OvertimeIntervals.Approved(LocalTime.of(19, 0), LocalTime.of(21, 0), List.of())));
        assertEquals(0L, day(18, 30, approved).approvedOvertimeMillis());
    }

    @Test void overlappingApprovalsAreMergedOnce() {
        var approved = OvertimeIntervals.net(DAY, List.of(
                new OvertimeIntervals.Approved(LocalTime.of(18, 0), LocalTime.of(20, 0), List.of()),
                new OvertimeIntervals.Approved(LocalTime.of(19, 0), LocalTime.of(21, 0), List.of())));
        assertEquals(List.of(iv(18, 0, 21, 0)), approved);
        assertEquals(180 * MIN, day(21, 0, approved).approvedOvertimeMillis());
    }

    @Test void unconfirmedWorkLeavesRecognitionUnknown() {
        var events = List.of(new ClockEvent(UUID.randomUUID(), EventKind.IN, at(9, 0)), new ClockEvent(UUID.randomUUID(), EventKind.OUT, at(21, 0)));
        var schedule = new Schedule(true, at(9, 0), at(18, 0), List.of(iv(12, 0, 13, 0)));
        var result = calculator.day(new DayInput(DAY, events, List.of(schedule), DayKind.WORKDAY, true, 1, 0, null, false, 0, 0,
                List.of(iv(18, 0, 21, 0))), AttendanceContext.NONE);
        assertNull(result.actualWorkMillis());
        assertNull(result.approvedOvertimeMillis());
    }
}
```

`WorktimeCalculator`의 생성자·`AttendanceContext` 사용법이 다르면 `src/test/java/kr/tna/worktime/AttendanceJudgementTest.java`의 방식으로 맞춘다.

- [ ] **Step 2: 실패 확인** — `-Dtest=ApprovedOvertimeTest` → 컴파일 실패.

- [ ] **Step 3: 순구간 도우미**

```java
package kr.tna.worktime;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import kr.tna.worktime.WorktimeCalculator.Interval;

/** 승인된 연장 구간에서 그 근무시간코드의 연장 휴게를 빼고, 겹치는 구간을 합친다(설계 §2). 자정을 넘는 연장은 없다. */
public final class OvertimeIntervals {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public record Pause(LocalTime start, LocalTime end) { }
    public record Approved(LocalTime start, LocalTime end, List<Pause> pauses) { }
    private OvertimeIntervals() { }

    public static List<Interval> net(LocalDate date, List<Approved> approved) {
        var pieces = new ArrayList<Interval>();
        for (var a : approved) {
            var remaining = new ArrayList<Interval>(List.of(interval(date, a.start(), a.end())));
            for (var p : a.pauses()) {
                var cut = interval(date, p.start(), p.end());
                var next = new ArrayList<Interval>();
                for (var r : remaining) {
                    if (!cut.start().isBefore(r.end()) || !cut.end().isAfter(r.start())) { next.add(r); continue; }
                    if (r.start().isBefore(cut.start())) next.add(new Interval(r.start(), cut.start()));
                    if (cut.end().isBefore(r.end())) next.add(new Interval(cut.end(), r.end()));
                }
                remaining = next;
            }
            pieces.addAll(remaining);
        }
        pieces.sort(Comparator.comparing(Interval::start));
        var merged = new ArrayList<Interval>();
        for (var p : pieces) {
            if (!merged.isEmpty() && !p.start().isAfter(merged.getLast().end())) {
                var last = merged.removeLast();
                merged.add(new Interval(last.start(), p.end().isAfter(last.end()) ? p.end() : last.end()));
            } else merged.add(p);
        }
        return List.copyOf(merged);
    }

    private static Interval interval(LocalDate date, LocalTime start, LocalTime end) {
        return new Interval(date.atTime(start).atZone(SEOUL).toInstant(), date.atTime(end).atZone(SEOUL).toInstant());
    }
}
```

- [ ] **Step 4: 계산기 변경**

1. `DayInput`에 마지막 구성요소 `List<Interval> approvedOvertime`을 추가한다. compact 생성자에서 `if (approvedOvertime == null) approvedOvertime = List.of();`. 기존 11인자 생성자는 `List.of()`를 넘기고, 10인자 생성자는 기존처럼 11인자를 부른다.
2. `DayResult`에 마지막 구성요소 `Long approvedOvertimeMillis`를 추가한다. 기존 17인자 생성자(attendance 포함)를 새로 만들어 `null`을 넘기고, 16인자 생성자는 17인자를 부르게 한다. `day()`의 마지막 `return new DayResult(...)`는 18인자로 바꿔 `approvedOvertime` 지역 변수를 넘긴다.
3. `day()`에서 `Long ... holiday=null;` 선언 줄에 `approvedOvertime=null`을 더한다.
   - `noEvents`에서 `actual=0L`로 정하는 두 분기에 `approvedOvertime=0L;`을 더한다.
   - 실제 근로를 계산하는 분기(`var work=subtract(bounds,breaks); ...`) 안, `night=0L;` 바로 앞에:
     ```java
     approvedOvertime=0L;
     for (var real:work) for (var approved:input.approvedOvertime()) approvedOvertime+=intersection(real,approved);
     ```
   `input.approvedOvertime()`은 `OvertimeIntervals.net`이 합친 서로 겹치지 않는 구간이라 중복으로 세지 않는다.
4. `DayResult`를 만드는 다른 곳(`WorktimeService.java` 약 :616, :632, :742)은 컴파일 오류가 나는 곳만 고친다. 저장값을 읽는 곳은 Task 3에서 새 열을 넘긴다. 그 전에는 `null`을 넘긴다.

- [ ] **Step 5: 통과 확인** — `-Dtest=ApprovedOvertimeTest,AttendanceJudgementTest,WorktimeCalculatorTest,SelectiveWorktimeTest` → PASS(기존 시험은 바뀌지 않아야 한다).

- [ ] **Step 6: 커밋** — `feat: recognize approved overtime inside actual work in the calculator`

---

### Task 3: 승인 연장 읽기·저장, CALC-3, 승인·취소 때 재계산

**Files:**
- Modify: `src/main/java/kr/tna/worktime/WorktimeMapper.java` (`insertRun` :197, `insertResult` :205-219, `latest` :220-230, `result` :231-242, `ResultRow` :289-292, 새 조회 `approvedOvertime`)
- Modify: `src/main/java/kr/tna/worktime/WorktimeService.java` (`input()` 약 :689, `storeResult` :316-335, `calculateDatesAsSystem` 건너뛰기 :357-359, CALC-2 문자열 :314, :441, :611, :618, `storedResult` :631)
- Modify: `src/main/java/kr/tna/worktime/AuditSummary.java:39-40`
- Modify: `src/main/java/kr/tna/request/LeaveWorkflowService.java` (`approveOvertime` :440-448, `approveOvertimeCancellation` :450-459)
- Modify: 기존 시험의 `CALC-2` 기대값(`DailyAttendanceResultIT.java:92,96,104`, `worktime/AuditSummaryBatchTest.java:24-26`, `frontend/src/pages/WorkflowPages.test.tsx:677-679`는 서버 응답 고정값이면 그대로 두고, 서버가 새로 만드는 값을 기대하는 곳만 바꾼다)
- Create: `src/test/java/kr/tna/OvertimeRecognitionIT.java`

- [ ] **Step 1: 실패하는 통합 시험**

`ApprovalRouteWorkflowIT.overtimeAndItsCancellationOnlyTakeEffectOnTheFinalDecision`(:136)의 연장 신청·결재 셋업을 복사한다(근무조·근무시간코드에 AFTER 허용 구간과 연장 휴게가 있는 셋업, 결재선 연결). 시나리오:

1. 사원이 **오늘보다 앞선 평일이 아닌, 오늘** 근무일에 18:00~23:00 연장을 신청한다(사전신청이라 시작 전이어야 한다. 오늘 18:00이 이미 지났으면 신청 셋업이 쓰는 날짜 방식을 따른다. 기존 시험이 미래 날짜로 신청한 뒤 SQL로 날짜를 옮긴다면 같은 방식으로 `overtime_request_detail.work_date`·`approval_request.from_date/to_date`를 지난 근무일로 옮긴다).
2. 그 근무일에 출근 09:00·퇴근 22:40 기록과 휴게 확인(12:00~13:00)을 SQL 또는 `ClockService`로 넣고 계산한다 → `approved_overtime_millis = 0`(아직 승인 전).
3. 최종 승인(`POST /requests/{id}/approve`) → 같은 요청 안에서 다시 계산되어 최신 결과의 `approved_overtime_millis`가 `(22:40-18:00) - 연장휴게` 분, `rule_version = 'CALC-3'`.
4. 취소 신청 → 취소 최종 승인 → 최신 결과 `approved_overtime_millis = 0`.

```java
    private Map<String, Object> latest(LocalDate date) {
        return db.queryForMap("""
            SELECT approved_overtime_millis, rule_version FROM tna.day_result
            WHERE tenant_id=? AND employee_id=? AND work_date=? ORDER BY result_revision DESC LIMIT 1""", tenant, employee.employeeId(), date);
    }
```

- [ ] **Step 2: 실패 확인** — `-Dit.test=OvertimeRecognitionIT` → FAIL.

- [ ] **Step 3: 승인 연장 조회** (`WorktimeMapper`)

```java
    record ApprovedOvertimeRow(java.time.LocalTime startTime, java.time.LocalTime endTime, UUID codeVersionId) { }

    @Select("""
        SELECT d.start_time, d.end_time, d.code_version_id FROM tna.approval_request r
        JOIN tna.overtime_request_detail d ON d.tenant_id=r.tenant_id AND d.request_id=r.id
        JOIN tna.request_effect f ON f.tenant_id=r.tenant_id AND f.request_id=r.id AND f.effect_kind='OVERTIME'
        WHERE r.tenant_id=#{tenantId} AND r.target_employee_id=#{employeeId} AND r.kind='OVERTIME' AND r.status='APPROVED'
          AND f.effect_status='EFFECTIVE' AND d.work_date=#{workDate}
        ORDER BY d.start_time""")
    List<ApprovedOvertimeRow> approvedOvertime(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
            @Param("workDate") LocalDate workDate);

    @Select("SELECT start_time, end_time FROM tna.work_time_code_break WHERE tenant_id=#{tenantId} AND version_id=#{versionId} AND kind<>'REGULAR'")
    List<OvertimeIntervals.Pause> overtimePauses(@Param("tenantId") UUID tenantId, @Param("versionId") UUID versionId);
```

`work_time_code_break`를 `tna_app`이 읽을 수 있는지 확인한다(`PersonalScheduleMapper.breaks`가 이미 읽는다).

- [ ] **Step 4: 입력에 넣기** — `WorktimeService.input()`에서 `DayInput`을 만들 때 마지막 인자로:

```java
OvertimeIntervals.net(date, mapper.approvedOvertime(tenantId, employeeId, date).stream()
        .map(o -> new OvertimeIntervals.Approved(o.startTime(), o.endTime(), mapper.overtimePauses(tenantId, o.codeVersionId())))
        .toList())
```

(지역 변수 이름은 `input()`의 실제 이름에 맞춘다.) 다른 세 경로(`openSettlementDay`, `readMonthDays`, `MonthEndReviewQueries.assess`)는 바꾸지 않는다. 승인·취소는 검토 개정을 올리므로 최신 여부 판정은 개정으로 충분하다.

- [ ] **Step 5: 저장·읽기·CALC-3**
  - `insertResult`의 열 목록 끝에 `approved_overtime_millis`, 값에 `#{r.approvedOvertimeMillis}`를 더한다. `'CALC-2'` → `'CALC-3'`(`insertRun`, `insertResult`).
  - `latest`·`result` SELECT와 `ResultRow`, `storedResult`에 `approved_overtime_millis`를 더해 `DayResult`의 18번째 값으로 넘긴다.
  - `WorktimeService`의 `"CALC-2"` 문자열(:314 감사, :441 검토 Excel 머리글, :611·:618 자리표시)을 `"CALC-3"`으로, `calculateDatesAsSystem`의 건너뛰기 조건 `"CALC-2".equals(saved.ruleVersion())`를 `"CALC-3".equals(...)`로 바꾼다. 상수 `static final String RULE_VERSION = "CALC-3";`을 `WorktimeService`에 두고 Java 쪽은 이 상수를 쓴다(SQL 리터럴은 그대로 둔다).
  - `AuditSummary`의 허용 목록 `names("CALC-1 CALC-2")` → `names("CALC-1 CALC-2 CALC-3")`.

- [ ] **Step 6: 승인·취소 때 날짜 표시** — `LeaveWorkflowService.approveOvertime`과 `approveOvertimeCancellation`의 효력 처리 뒤에:

```java
        // 인정 연장이 바뀌므로 그날을 재계산 필요로 표시한다(결재 직후 재계산이 처리, 설계 §3).
        var detail=mapper.overtime(actor.tenantId(),overtimeRequestId);
        changed(mapper.reviseDay(actor.tenantId(),request.targetEmployeeId(),detail.workDate(),0));
```

취소 신청은 원신청의 상세를 쓴다(`overtime_request_detail`은 원신청 id에 있다. `approveOvertimeCancellation`이 원효력을 찾는 방식과 같은 원신청 id를 쓴다). `mapper.overtime(...)`의 반환 레코드에서 근무일 필드 이름을 확인한다. `reviseDay`가 1이 아닌 값을 돌려주면 `changed` 대신 그대로 호출한다(upsert).

- [ ] **Step 7: 기존 기대값 갱신** — `CALC-2`를 기대하는 시험 중 서버가 **새로 계산한** 결과를 보는 곳만 `CALC-3`으로 바꾼다. 시험이 직접 넣는 `'CALC-2'` seed는 그대로 둔다(V44가 허용).

- [ ] **Step 8: 통과 확인** — `-Dit.test=OvertimeRecognitionIT,ApprovalRouteWorkflowIT,ApprovalRecalculationIT,DailyAttendanceResultIT,DailyAttendanceBatchIT,AutoBreakConfirmationIT` 와 `-Dtest=AuditSummaryBatchTest,ApprovedOvertimeTest` → PASS.

- [ ] **Step 9: 커밋** — `feat: store approved overtime per day and recalculate after overtime decisions`

---

### Task 4: 주 12시간 초과 연장 신청 차단

**Files:**
- Modify: `src/main/java/kr/tna/request/LeaveWorkflowMapper.java` (새 조회 `weeklyOvertimeMinutes`)
- Modify: `src/main/java/kr/tna/request/LeaveWorkflowService.java` (`submitOvertime` :156-177, 공용 검사 메서드)
- Modify: `src/main/java/kr/tna/overtime/OvertimeDirectory.java` (미리보기 :79-87)
- Create: `src/test/java/kr/tna/WeeklyOvertimeLimitIT.java`

- [ ] **Step 1: 실패하는 통합 시험** (Task 3의 연장 셋업 재사용)
  - 같은 주에 대기 6시간 + 승인 5시간이 있을 때 2시간 신청 → 422, 메시지에 "주 12시간" 포함. 1시간 신청 → 성공(정확히 12시간 허용).
  - 반려·철회된 건, 취소 승인된 건은 합계에서 빠진다.
  - 다른 주의 신청은 합계에 넣지 않는다.
  - 미리보기(`POST /me/overtime/preview`)도 같은 422와 메시지를 돌려준다.
  - 기존 셋업으로 하루에 넣을 수 있는 연장 구간이 짧으면, 같은 주의 여러 근무일에 나눠 신청하거나 SQL로 `requested_minutes`를 직접 넣은 대기·승인 건을 만든다(연장 detail 행은 기존 신청에서 복사).

- [ ] **Step 2: 실패 확인** — `-Dit.test=WeeklyOvertimeLimitIT` → FAIL.

- [ ] **Step 3: 합계 조회**

```java
    @Select("""
        SELECT COALESCE(sum(d.requested_minutes),0)::int FROM tna.approval_request r
        JOIN tna.overtime_request_detail d ON d.tenant_id=r.tenant_id AND d.request_id=r.id
        LEFT JOIN tna.request_effect f ON f.tenant_id=r.tenant_id AND f.request_id=r.id AND f.effect_kind='OVERTIME'
        WHERE r.tenant_id=#{tenantId} AND r.target_employee_id=#{employeeId} AND r.kind='OVERTIME'
          AND d.work_date BETWEEN #{weekStart} AND #{weekEnd}
          AND (r.status='PENDING' OR (r.status='APPROVED' AND f.effect_status='EFFECTIVE'))""")
    int weeklyOvertimeMinutes(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
            @Param("weekStart") LocalDate weekStart, @Param("weekEnd") LocalDate weekEnd);
```

- [ ] **Step 4: 공용 검사** — `LeaveWorkflowService`:

```java
    static final int WEEKLY_OVERTIME_LIMIT_MINUTES=720;
    /** 주(월~일) 연장 신청·승인 합계 + 이번 신청이 12시간을 넘으면 거부한다(설계 §4). 회사 잠금 안에서 부른다. */
    public void requireWeeklyOvertimeLimit(UUID tenant,UUID employee,LocalDate workDate,int minutes) {
        LocalDate start=workDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        int existing=mapper.weeklyOvertimeMinutes(tenant,employee,start,start.plusDays(6));
        if(existing+minutes>WEEKLY_OVERTIME_LIMIT_MINUTES)
            throw OrgException.invalid("이번 주 연장 신청·승인 합계 "+duration(existing)+"에 이번 신청 "+duration(minutes)+"을 더하면 주 12시간을 넘습니다.");
    }
    private static String duration(int minutes) {
        int h=minutes/60,m=minutes%60;
        return h==0?m+"분":m==0?h+"시간":h+"시간 "+m+"분";
    }
```

- `submitOvertime`: `overtime.evaluate(..., true)`로 신청 분을 얻은 뒤, `createRequest` 전에 `requireWeeklyOvertimeLimit(actor.tenantId(), actor.employeeId(), workDate, requestedMinutes)`. 변수 이름은 실제 코드에 맞춘다. `commands.execute`가 회사 잠금을 잡으므로 동시 제출도 직렬화된다.
- `OvertimeDirectory.preview`: `policy.evaluate` 결과로 같은 메서드를 부른다(읽기 잠금 안). `OvertimeController`의 예외 처리기가 `{"message"}`로 돌려주고, `OvertimePage.tsx`가 이미 `role="alert"`로 보인다.

- [ ] **Step 5: 통과 확인** — `-Dit.test=WeeklyOvertimeLimitIT,ApprovalRouteWorkflowIT,LeaveOperationsWebIT` 와 `cd frontend; npx vitest run src/pages/OvertimePage.test.tsx` → PASS.

- [ ] **Step 6: 커밋** — `feat: block overtime requests beyond 12 hours a week`

---

### Task 5: 월 마감 급여용 근태 집계 (V45 + Excel)

**Files:**
- Create: `src/main/resources/db/migration/V45__payroll_month_close_values.sql`
- Modify: `src/main/java/kr/tna/worktime/MonthCloseExportMapper.java` (`Row` :15-16, `rows` SQL :34-52)
- Modify: `src/main/java/kr/tna/worktime/MonthCloseWorkbook.java` (머리글 :79-80, :92-93, 행 :94-97, `Totals` :112-137)
- Modify: `src/test/java/kr/tna/MigrationRunnerIT.java` (44 → 45)
- Modify: `src/test/java/kr/tna/worktime/MonthCloseWorkbookTest.java`, `src/test/java/kr/tna/MonthCloseExportIT.java`

- [ ] **Step 1: 실패하는 시험**
  - `MonthCloseExportIT`에 새 시험: 한 사원의 마감 월에 CALC-3 결과(인정 연장 2시간, 바깥 3시간, 야간 1시간, 지각 7분 1회, 조퇴 30분 1회, 결근 FULL 1일·HALF 1일)를 SQL로 넣고 마감 → 행의 새 열 값(`payroll_captured=true`, 승인 없는 초과 1시간, 결근 1.5일 등)과 다운로드 Excel의 새 열을 확인한다. `result()` seed 도우미는 `AttendanceExceptionsWebIT.java:174-188` 방식을 따른다.
  - 같은 달에 `approved_overtime_millis`가 NULL인 CALC-2 결과(실근로 확인됨)가 하나라도 있으면 인정 연장·승인 없는 초과 합계는 NULL(빈칸).
  - 기존 `earlierCloseKeepsItsOriginalColumnsUntilReopenedAndClosedAgain` 방식으로, V45 이전 마감 행(`payroll_captured=false`)은 새 열이 빈칸임을 확인한다(SQL로 `payroll_captured=false` 행을 만든다).
  - `MonthCloseWorkbookTest`: 새 머리글 순서와 합계(하나라도 NULL이면 합계 빈칸, `payroll_captured=false` 행은 새 열 빈칸).

- [ ] **Step 2: 실패 확인** — `-Dit.test=MonthCloseExportIT` · `-Dtest=MonthCloseWorkbookTest` → FAIL.

- [ ] **Step 3: V45** — `V31__attendance_month_close_export.sql:30-68`의 `tna.month_close_export_values`를 읽고, **반환 열을 끝에 덧붙인** 새 정의로 바꾼다(`DROP FUNCTION` 후 `CREATE`, V31의 `REVOKE`·`GRANT`·`SECURITY`·`SET search_path` 등 속성을 그대로 다시 적용). 날짜별 최신 결과 집계에 다음을 추가한다(기존 `unknown_work`와 같은 사원·조직 묶음):

```sql
  CASE WHEN bool_or(d.actual_work_millis IS NOT NULL AND d.approved_overtime_millis IS NULL) OR bool_or(d.actual_work_millis IS NULL AND d.id IS NOT NULL)
       THEN NULL ELSE COALESCE(sum(d.approved_overtime_millis),0) END AS approved_overtime_millis,
  CASE WHEN <위와 같은 조건> THEN NULL
       ELSE COALESCE(sum(GREATEST(COALESCE(d.outside_schedule_millis,0)-COALESCE(d.approved_overtime_millis,0),0)),0) END AS unapproved_extra_millis,
  CASE WHEN bool_or(d.actual_work_millis IS NULL AND d.id IS NOT NULL) THEN NULL ELSE COALESCE(sum(d.night_work_millis),0) END AS night_work_millis,
  CASE WHEN bool_or(d.actual_work_millis IS NULL AND d.id IS NOT NULL) THEN NULL ELSE COALESCE(sum(d.holiday_work_millis),0) END AS holiday_work_millis,
  count(*) FILTER (WHERE d.late_millis > 0)::int AS late_count,
  COALESCE(sum(d.late_millis),0) AS late_millis,
  count(*) FILTER (WHERE d.early_leave_millis > 0)::int AS early_leave_count,
  COALESCE(sum(d.early_leave_millis),0) AS early_leave_millis,
  COALESCE(sum(CASE d.absence WHEN 'FULL' THEN 1 WHEN 'HALF' THEN 0.5 END),0) AS absence_days
```

V31의 실근로 미확정 판정식(`unknown_work`)을 그대로 재사용해 위 `<조건>`을 같은 의미로 쓴다(별칭·조인 이름은 V31 함수 본문에 맞춘다). 지각·조퇴·결근은 판정하지 않은 날이 `NULL`이므로 0으로 센다.

스냅샷 함수 `tna.capture_attendance_month_close()`(최신 정의 `V33__selective_month_close_membership.sql:55-63`)를 다시 만들어 **열 목록을 명시**하고 `payroll_captured`에 `true`를 넣는다:

```sql
INSERT INTO tna.attendance_month_export_row(tenant_id,closure_id,employee_id,employee_no,employee_name,organization_id,
  organization_code,organization_name,employed_days,worked_days,actual_millis,leave_days,payroll_captured,
  approved_overtime_millis,unapproved_extra_millis,night_work_millis,holiday_work_millis,late_count,late_millis,
  early_leave_count,early_leave_millis,absence_days)
SELECT NEW.tenant_id, NEW.id, v.employee_id, v.employee_no, v.employee_name, v.organization_id, v.organization_code,
  v.organization_name, v.employed_days, v.worked_days, v.actual_millis, v.leave_days, true,
  v.approved_overtime_millis, v.unapproved_extra_millis, v.night_work_millis, v.holiday_work_millis, v.late_count,
  v.late_millis, v.early_leave_count, v.early_leave_millis, v.absence_days
FROM tna.month_close_export_values(<V33과 같은 인자>) v;
```

V33의 나머지(선택근무 구성원 INSERT 등)는 그대로 둔다. `MigrationRunnerIT`를 45로 올린다.

- [ ] **Step 4: 매퍼·Excel**
  - `MonthCloseExportMapper.Row`에 `boolean payrollCaptured, Long approvedOvertimeMillis, Long unapprovedExtraMillis, Long nightWorkMillis, Long holidayWorkMillis, Integer lateCount, Long lateMillis, Integer earlyLeaveCount, Long earlyLeaveMillis, java.math.BigDecimal absenceDays`를 끝에 덧붙이고 `rows` SQL에 열을 더한다.
  - `MonthCloseWorkbook`: 요약·사원 머리글 끝(유급휴가 열 뒤)에 `인정 연장(시간)`, `승인 없는 초과(시간)`, `야간근로(시간)`, `휴일근로(시간)`, `지각(회)`, `지각(분)`, `조퇴(회)`, `조퇴(분)`, `결근(일)`을 붙인다. 시간은 기존 `hours(Long)`, 분은 `millis/60_000`(올림 없이 정수 분), 결근은 소수 그대로. `payrollCaptured=false` 행은 새 9칸을 빈칸으로 둔다. 범위·부서 합계는 기존 `Totals`처럼 하나라도 NULL이거나 `payrollCaptured=false`면 그 열 합계를 빈칸으로 둔다.

- [ ] **Step 5: 통과 확인** — `-Dit.test=MonthCloseExportIT,AttendanceMonthCloseIT,MigrationRunnerIT,CodeMigrationUpgradeIT` · `-Dtest=MonthCloseWorkbookTest,AttendanceMonthCloseServiceTest` · `cd frontend; npx vitest run src/pages/MonthCloseExport.test.tsx` → PASS. `CodeMigrationUpgradeIT.verifyMonthCloseExportUpgrade`(:380)가 반환 열 수를 고정해 두었으면 새 열을 반영한다.

- [ ] **Step 6: 커밋** — `feat: add payroll attendance totals to the month close workbook`

---

### Task 6: 담당자 '주 52시간' 탭

**Files:**
- Create: `src/main/java/kr/tna/workspace/WeeklyHoursQueries.java`
- Modify: `src/main/java/kr/tna/workspace/ProfileCompanyMapper.java` (새 조회), `ProfileCompanyController.java` (:43 기본 매핑 제외 목록, 새 매핑), `src/main/java/kr/tna/common/web/ReactPageViewResolver.java:43` (모델 키 `weeklyHours`)
- Create: `frontend/src/pages/WeeklyHoursPage.tsx`, `frontend/src/pages/WeeklyHoursPage.test.tsx`
- Modify: `frontend/src/pages/CompanyAttendanceNavigation.tsx:67-74`, `frontend/src/pages/IdentityPages.tsx:321-336`
- Create: `src/test/java/kr/tna/WeeklyHoursWebIT.java`

- [ ] **Step 1: 실패하는 웹 시험** (`AttendanceExceptionsWebIT` 셋업·`result()` 도우미 복사, `CALC-3`·`approved_overtime_millis` 열 추가)
  - 2026-10월 조회: 사원 A 주(10-05~10-11) 실근로 합계 53시간(인정 연장 9시간) → '초과', 사원 B 49시간 → '임박', 사원 C 47시간 → 행 없음.
  - 같은 주에 실근로 NULL 결과가 있으면 `unknownDays` 1.
  - 월 경계: 9-29~10-05 주도 포함된다(조회 월과 겹치는 주).
  - 다른 회사 결과는 보이지 않는다. `ATTENDANCE_ADMIN`이 아니면 403, 잘못된 월은 422(지각·조퇴·결근 탭과 같다).
  - 페이지 JSON: `/weeklyHours/rows/0/status` = `"OVER"`, `/weeklyHours/rows/0/weekStart` = `"2026-10-05"`, `actualMillis`, `approvedOvertimeMillis`, `unknownDays`, `employeeNo`, `employeeName`, `truncated`.

- [ ] **Step 2: 실패 확인** — `-Dit.test=WeeklyHoursWebIT` → FAIL.

- [ ] **Step 3: 조회** — `ProfileCompanyMapper`에 `CURRENT_EXCEPTIONS`(:145-156)와 같은 "날짜별 최신·현재 결과" CTE를 쓰는 조회를 추가한다(범위: 조회 월 1일이 속한 주의 월요일 ~ 말일이 속한 주의 일요일):

```sql
SELECT employee_id, employee_no, employee_name, date_trunc('week', work_date)::date AS week_start,
  COALESCE(sum(actual_work_millis),0) AS actual_millis, COALESCE(sum(approved_overtime_millis),0) AS approved_overtime_millis,
  count(*) FILTER (WHERE actual_work_millis IS NULL)::int AS unknown_days
FROM latest
GROUP BY employee_id, employee_no, employee_name, date_trunc('week', work_date)
HAVING COALESCE(sum(actual_work_millis),0) >= 172800000
ORDER BY sum(actual_work_millis) DESC, employee_no, week_start
LIMIT 1001
```

(`latest` CTE의 열 이름은 `CURRENT_EXCEPTIONS`에 맞추고, 필요한 열이 없으면 CTE를 복사해 `actual_work_millis`, `approved_overtime_millis`를 더한다.) 172,800,000 = 48시간, 187,200,000 = 52시간.

- [ ] **Step 4: 서비스·컨트롤러** — `AttendanceExceptionQueries`(권한·월 검사·1000건 상한)를 본뜬 `WeeklyHoursQueries`:

```java
public record Row(UUID employeeId, String employeeNo, String employeeName, LocalDate weekStart, LocalDate weekEnd,
        long actualMillis, long approvedOvertimeMillis, int unknownDays, String status) { }   // status: OVER(>52h) | NEAR(48~52h)
public record View(String month, List<Row> rows, boolean truncated, String error) { }
```

`status = actualMillis > 187_200_000L ? "OVER" : "NEAR"`, `weekEnd = weekStart.plusDays(6)`. 컨트롤러는 `params="view=weekly-hours"` 매핑을 추가하고 기본 매핑의 제외 목록에 `"view!=weekly-hours"`를 더한다. 모델 키 `weeklyHours`, 오류면 422.

- [ ] **Step 5: 화면** — `AttendanceExceptionsPage.tsx`를 본뜬 `WeeklyHoursPage.tsx`:
  - 머리 `회사 근무현황`, `CompanyAttendanceViews active="weekly-hours"`, 조회 월 입력(숨은 `view=weekly-hours`).
  - 안내 문장: "실근로 합계가 48시간 이상인 주를 보여 줍니다. 확인 전 일수가 있으면 확인된 날만의 합계입니다."
  - 시트 열: 사번 · 이름 · 주간(`MM-dd~MM-dd`, 링크 `/company/attendance?employeeId=…&month=…`) · 실근로 합계(시간, 소수 1자리) · 인정 연장(시간) · 상태(`OVER` → 빨강 태그 '52시간 초과', `NEAR` → 노랑 태그 '임박') · 확인 전(일)
  - 빈 목록: "48시간 이상 근무한 주가 없습니다." 1000건 초과 시 "1000건까지 표시".
  - `CompanyAttendanceViews`에 `'weekly-hours'` 탭 '주 52시간'을 지각·조퇴·결근 뒤에 추가한다. `IdentityPages.tsx`의 `company/attendance` 분기에서 `data.weeklyHours`가 있으면 `WeeklyHoursPage`를 렌더한다.
  - `WeeklyHoursPage.test.tsx`: 행·태그·링크·빈 목록·상한 안내.

- [ ] **Step 6: 통과 확인** — `-Dit.test=WeeklyHoursWebIT,AttendanceExceptionsWebIT` · `npx vitest run src/pages/WeeklyHoursPage.test.tsx src/pages/AttendanceExceptionsPage.test.tsx` · `npm run build` → PASS.

- [ ] **Step 7: 커밋** — `feat: add the weekly 52-hour check tab for administrators`

---

### Task 7: 연장근무 신청현황 '인정 시간' 열

**Files:**
- Modify: `src/main/java/kr/tna/overtime/OvertimeDirectory.java` (`DirectoryMapper.rows` :37-55, `Row` :25-27)
- Modify: `frontend/src/pages/OvertimePage.tsx` (`Results` :98-124), `frontend/src/pages/OvertimePage.test.tsx`
- Modify: `src/test/java/kr/tna/LeaveOperationsWebIT.java` (`overtimeDirectoryIsSeparateAndUsesTheEmployeesOwnPolicy` :95) 또는 Task 3의 `OvertimeRecognitionIT`

- [ ] **Step 1: 실패하는 시험** — `OvertimeRecognitionIT`에 승인 뒤 담당자 `GET /admin/overtime`(페이지 JSON)의 그 행 `recognizedMillis`가 인정 연장과 같은지, 결과가 없으면 `null`인지 확인을 더한다. `OvertimePage.test.tsx`: 담당자 결과표에 '인정 시간' 열, 값이 있으면 `4시간 25분`, `null`이면 `계산 전`.

- [ ] **Step 2: 실패 확인**.

- [ ] **Step 3: 구현**
  - `rows` SQL에 근무일 최신 결과를 붙인다:
    ```sql
    LEFT JOIN LATERAL (SELECT dr.approved_overtime_millis FROM tna.day_result dr
      WHERE dr.tenant_id=r.tenant_id AND dr.employee_id=r.target_employee_id AND dr.work_date=d.work_date
      ORDER BY dr.result_revision DESC LIMIT 1) res ON true
    ```
    선택 목록에 `res.approved_overtime_millis AS recognized_millis`, `Row` 끝에 `Long recognizedMillis`. (조인 별칭 `r`, `d`는 실제 SQL에 맞춘다. 취소 신청 행은 원신청 detail을 쓰므로 같은 근무일이다.)
  - `OvertimePage.tsx` 담당자 결과표의 '유효 승인' 뒤에 '인정 시간' 열을 추가한다(담당자만). 분 표시는 페이지에 이미 있는 시간 포맷 함수를 쓰고, 없으면 `h시간 m분`.

- [ ] **Step 4: 통과 확인** — 관련 IT·vitest·`npm run build` → PASS.

- [ ] **Step 5: 커밋** — `feat: show recognized overtime in the overtime directory`

---

### Task 8: 문서

**Files:**
- Modify: `docs/rules/calculation.md` (3.2 표에 `approved_overtime_millis` 행, CALC-3, 인정 연장 정의, 주 12시간 신청 검사)
- Modify: `docs/superpowers/specs/2026-11-18-overtime-requests.md` (담당자 대조 절의 "급여 가산… 수정하지 않는다" 뒤에 인정 연장·주 12시간 검사 1~2줄과 새 설계 링크)
- Modify: `README.md` (현재 기능에 1줄)

- [ ] **Step 1: 설계 문서의 결정만 옮긴다.**
- [ ] **Step 2: 커밋** — `docs: describe overtime recognition, weekly limits and payroll totals`

---

### Task 9: 전체 회귀와 브라우저 확인 (컨트롤러가 수행)

- [ ] 새 테스트 DB에서 `mvn-test.ps1 verify`, `npx vitest run`, `npm run build`. 기대: 알려진 오류 13건 외 실패 0건.
- [ ] 로컬 데모: 담당자가 근무시간코드에 AFTER 허용 구간·연장 휴게를 둔 근무조를 배정 → 직원 연장 신청 → 결재자 승인 → 출퇴근(퇴근이 승인 종료 전) → 인정 연장, 신청현황 '인정 시간', 12시간 초과 신청 차단 안내, '주 52시간' 탭, 월 마감 Excel 새 열 → 모바일.
- [ ] 결과 문서 `docs/quality/overtime-payroll-20261126.md` 작성·커밋.
