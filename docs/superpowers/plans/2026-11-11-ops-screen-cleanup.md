# 운영 화면 정리·초 단위 시각·근무 확인 자동 계산 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 직원 화면 6개에서 검토용 문구·열을 걷어내고 모든 시각을 초 단위로 보이며, 직원이 근무 확인을 저장하면 그날 실근로를 바로 계산한다.

**Architecture:** 표시 형식은 프런트 `ui.tsx`의 세 함수와 서버 `SeoulTimeDisplay`·`WorktimeController.duration`만 바꿔 전 화면에 퍼지게 한다. 자동 계산은 `AttendanceController.confirm`이 확인 저장(트랜잭션 1) 뒤 `WorktimeService.calculateConfirmedDay`(트랜잭션 2)를 부르는 방식이다. 이 메서드는 수동 재집계와 같은 저장 메서드를 공유한다. '확인 전'은 `WorktimeCalculator.pending`이 날짜별로 판단하고, `DayView.pending`·`PeriodView.pendingDays`로 화면에 내려간다.

**Tech Stack:** Java 25 · Spring Boot · MyBatis · PostgreSQL(Testcontainers 통합 테스트) / React 19 · TypeScript · AG Grid · Vitest

**Spec:** [2026-11-11-ops-screen-cleanup-design.md](../specs/2026-11-11-ops-screen-cleanup-design.md)

---

## 작업 위치와 공통 명령

- 저장소: `C:\Users\ihpark\dev\vibe-attendance\target\ops-screen-cleanup-20261111` (브랜치 `claude/ops-screen-cleanup-20261111`, upstream 없음). 다른 worktree는 건드리지 않는다.
- 아래 명령은 PowerShell 기준이며 저장소 루트에서 실행한다. 프런트 명령은 `frontend`에서 실행한다.

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.cache\vibe-attendance-tools\jdk-25.0.4.1+1"
```

| 용도 | 명령 |
| --- | --- |
| 백엔드 단위 테스트 1개 | `.\mvnw.cmd -B -q "-Dtest=<클래스명>" "-Dsurefire.failIfNoSpecifiedTests=false" test` |
| 백엔드 통합 테스트 1개 (Docker 필요) | `.\mvnw.cmd -B "-Dtest=None" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=<클래스명>" verify` |
| 백엔드 전체 | `.\mvnw.cmd -B verify` (Testcontainers 새 DB, 오래 걸리므로 백그라운드 실행) |
| 프런트 파일 1개 | `npm test -- <src 기준 경로>` |
| 프런트 전체·빌드 | `npm test`, `npm run build` |

통합 테스트는 Testcontainers로 새 PostgreSQL을 띄운다. 이미 돌고 있는 로컬 앱 DB(`127.0.0.1:15432`)나 컨테이너는 쓰지 않는다.

## 파일 구조

| 파일 | 책임 | 작업 |
| --- | --- | --- |
| `frontend/src/ui.tsx` | 공통 표시 함수 | `fmtDateTime`·`fmtTime`·`duration` 초 단위, `secondsInput` 추가 |
| `src/main/java/kr/tna/common/web/SeoulTimeDisplay.java` | 서버 서울 시각 표시 | 초 단위 |
| `src/main/java/kr/tna/worktime/WorktimeController.java` | 근로시간 화면 모델 | `duration` 초 단위·`-`, 월 화면의 문자열 맵 제거 |
| `src/main/java/kr/tna/worktime/WorktimeCalculator.java` | 계산 규칙 | `pending` 추가 |
| `src/main/java/kr/tna/worktime/WorktimeData.java` | 근로시간 DTO | `DayView.pending`·`approvedLeaveDays`, `PeriodView.pendingDays` |
| `src/main/java/kr/tna/worktime/WorktimeService.java` | 조회·계산 | 날짜별 확인 전 판단, 공유 저장 메서드, `calculateConfirmedDay` |
| `src/main/java/kr/tna/clock/AttendanceController.java` | 출퇴근 화면·확인 | 확인 뒤 자동 계산, 화면 모델에 그날 실근로 |
| `src/main/java/kr/tna/common/web/ReactPageViewResolver.java` | 화면별 허용 키 | `clock/history`에 `actualWorkMillis` 추가, `worktime/month`에서 문자열 맵 키 삭제 |
| `src/main/java/kr/tna/request/LeaveWorkflowService.java` | 신청 흐름 | `currentCorrectionRequestId` |
| `src/main/java/kr/tna/request/RequestController.java` | 정정 화면 | 현재 승인 정정 ID 자동 입력 |
| `frontend/src/pages/IdentityPages.tsx`, `PersonalDetails.tsx` | 내 정보·회사 근무현황 | 섹션·문구 삭제, 열 머리글 |
| `frontend/src/pages/WorkflowPages.tsx`, `WorkflowTypes.ts` | 출퇴근·근로시간·신청 상세·정정 | 화면 정리, 새 필드 타입 |
| `frontend/src/pages/CorePages.tsx` | 근무 일정 | 안내문 삭제, 링크 문구 |
| `frontend/src/pages/AnnualRequestPage.tsx`, `components/RequestBreakEditor.tsx` | 내 근태현황·신청 모달 | 문구·단위·입력 정리 |
| `frontend/src/design/*.css`, `frontend/src/pages/*.css`, `frontend/src/WorkspacePolish.css` | 스타일 | 지운 마크업의 규칙 삭제 |
| `docs/design/worktime-results.md`, `docs/design/screens.md`, `README.md` | 문서 | 자동 계산·표시 형식 갱신 |

---

### Task 0: 작업 환경 준비

**Files:** 없음

- [ ] **Step 1: 브랜치 확인**

Run: `git status -sb`
Expected: 첫 줄 `## claude/ops-screen-cleanup-20261111`, 변경 없음.

- [ ] **Step 2: 프런트 의존성 설치**

Run: `cd frontend; npm ci`
Expected: `added ... packages`, 종료 코드 0.

- [ ] **Step 3: 프런트 기준선**

Run: `npm test`
Expected: 모두 PASS. 통과 개수를 기록한다.

- [ ] **Step 4: 백엔드 단위 기준선**

Run: `.\mvnw.cmd -B -q test`
Expected: `BUILD SUCCESS`. 첫 실행은 Wrapper·의존성을 내려받는다.

---

### Task 1: 프런트 공통 시각 형식과 회사 근무현황 열 머리글

**Files:**
- Modify: `frontend/src/ui.tsx:175-203`
- Modify: `frontend/src/pages/IdentityPages.tsx:307,314`
- Test: `frontend/src/ui.test.tsx:27-34`

- [ ] **Step 1: 실패하는 테스트로 바꾸기**

`frontend/src/ui.test.tsx`의 27-34행 테스트를 아래로 바꾼다.

```tsx
  it('업무 날짜는 시간대 변환 없이 표시하고 시각은 서울 기준 초까지만 보인다', () => {
    expect(fmtDate('2026-01-01')).toBe('2026-01-01');
    expect(fmtDateTime('2026-11-11T08:09:58.315Z')).toBe('2026-11-11 17:09:58');
    expect(fmtDateTime('2026-12-31T15:00:00.999Z')).toBe('2027-01-01 00:00:00');
    expect(fmtTime('09:00:00.000')).toBe('09:00');
    expect(fmtTime('09:00')).toBe('09:00');
    expect(fmtTime('09:00:30.125')).toBe('09:00:30');
    expect(fmtTime('09:00:00.500')).toBe('09:00:00');
    expect(fmtTime(null)).toBe('미확인');
    expect(duration(29_398_999)).toBe('8:09:58');
    expect(duration(0)).toBe('0:00:00');
    expect(duration(null)).toBe('-');
    expect(secondsInput('2026-11-04T18:00:30.125')).toBe('2026-11-04T18:00:30');
    expect(secondsInput('2026-11-04T12:00/2026-11-04T13:00:00.000')).toBe('2026-11-04T12:00/2026-11-04T13:00:00');
    expect(secondsInput(null)).toBe('');
  });
```

3행 import에 `secondsInput`을 더한다.

```tsx
import { Check, CsrfContext, Field, Form, fmtDate, fmtTime, fmtDateTime, duration, secondsInput } from './ui';
```

- [ ] **Step 2: 실패 확인**

Run: `npm test -- src/ui.test.tsx`
Expected: FAIL. `secondsInput` export가 없고 `fmtDateTime` 결과에 `.315`가 붙는다.

- [ ] **Step 3: 구현**

`frontend/src/ui.tsx`의 `fmtTime`·`fmtDateTime`·`duration`(176-203행)을 아래로 바꾸고 `secondsInput`을 더한다.

```tsx
export function fmtTime(value?: string | null) {
  if (!value) return '미확인';
  const [clock, fraction = ''] = value.split('.');
  const [hour, minute, second = '00'] = clock.split(':');
  return second === '00' && !/[1-9]/.test(fraction) ? `${hour}:${minute}` : `${hour}:${minute}:${second}`;
}
export function fmtDateTime(value?: string | null) {
  if (!value) return '미지정';
  const instant = new Date(value);
  if (!Number.isFinite(instant.getTime())) return '시각 미확인';
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(instant);
}
export function duration(milliseconds?: number | null) {
  if (milliseconds == null || !Number.isFinite(milliseconds)) return '-';
  const seconds = Math.floor(milliseconds / 1000);
  return `${Math.floor(seconds / 3600)}:${String(Math.floor(seconds / 60) % 60).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
}
/** datetime-local 입력값을 1초 단위 입력칸에 맞춘다. 서버 값의 밀리초만 지운다. */
export const secondsInput = (value?: string | null) => (value ?? '').replace(/(\d{2}:\d{2}:\d{2})\.\d+/g, '$1');
```

- [ ] **Step 4: 통과 확인**

Run: `npm test -- src/ui.test.tsx`
Expected: PASS

- [ ] **Step 5: 회사 근무현황 열 머리글**

`frontend/src/pages/IdentityPages.tsx` 307행의 `headerName: '예정 근무 (시:분:초.밀리초)'`를 `headerName: '예정 근무'`로, 314행의 `headerName: '실근로 (시:분:초.밀리초)'`를 `headerName: '실근로'`로 바꾼다.

- [ ] **Step 6: 전체 프런트 테스트와 기존 기대값 갱신**

Run: `npm test`
Expected: 표시 형식 변경으로 일부 테스트가 실패한다. 아래 [부록 A](#부록-a-형식-변경으로-바뀌는-기존-기대값)의 Task 1 항목만 새 기대값으로 고친 뒤 다시 실행해 모두 PASS를 확인한다. 화면 정리 Task(7~12)에서 다시 쓰는 테스트는 그 Task에서 고친다.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/ui.tsx frontend/src/ui.test.tsx frontend/src/pages/IdentityPages.tsx frontend/src/pages/IdentityPages.test.tsx frontend/src/pages/WorkflowPages.test.tsx
git commit -m "feat: show WorkLog times to the second without milliseconds"
```

---

### Task 2: 서버 공통 시각 형식

**Files:**
- Modify: `src/main/java/kr/tna/common/web/SeoulTimeDisplay.java:16-18`
- Modify: `src/main/java/kr/tna/worktime/WorktimeController.java:49`
- Test: `src/test/java/kr/tna/SeoulTimeDisplayTest.java`
- Create: `src/test/java/kr/tna/worktime/WorktimeDurationTest.java`

- [ ] **Step 1: 실패하는 테스트**

`SeoulTimeDisplayTest.java`의 기대값을 초 단위로 바꾸고 메서드 이름을 고친다.

```java
    @Test void instantUsesSeoulAcrossMidnightAndYearBoundariesToTheSecond() {
        assertEquals("2027-01-01 00:00:00", display.instant(Instant.parse("2026-12-31T15:00:00.123Z")));
        assertEquals("2026-12-31 23:59:59", display.instant(Instant.parse("2026-12-31T14:59:59.999999999Z")));
        assertEquals("2026-01-01 09:00:00", display.instant(Instant.parse("2026-01-01T00:00:00Z")));
    }

    @Test void dateAndTimeHaveStableIsoAndSecondDisplays() {
        assertEquals("2026-10-24", display.date(LocalDate.of(2026, 10, 24)));
        assertEquals("09:05:00", display.time(LocalTime.of(9, 5, 0, 7_000_000)));
        assertEquals("00:00:00", display.time(LocalTime.MIDNIGHT));
    }
```

같은 파일 `displayDoesNotDependOnHostTimezoneOrLocale`의 `"2027-01-01 00:00:00.123"`은 `"2027-01-01 00:00:00"`, `"09:05:00.007"`은 `"09:05:00"`으로 바꾼다. `intervalsUseFullSeoulDatesAndWhitelistOnlyStartAndEndTimes`의 기대값은 아래로 바꾼다.

```java
        assertEquals("2026-12-31 23:30:00 ~ 2027-01-01 00:00:00; 2027-01-01 01:00:00 ~ 2027-01-01 01:15:00", display.intervals(data));
```

새 파일 `src/test/java/kr/tna/worktime/WorktimeDurationTest.java`:

```java
package kr.tna.worktime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class WorktimeDurationTest {
    @Test void durationShowsHoursMinutesSecondsAndDashWhenUnknown() {
        assertEquals("8:09:58", WorktimeController.duration(29_398_999L));
        assertEquals("0:00:00", WorktimeController.duration(0L));
        assertEquals("27:00:00", WorktimeController.duration(97_200_000L));
        assertEquals("-", WorktimeController.duration(null));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -B -q "-Dtest=SeoulTimeDisplayTest,WorktimeDurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL. 예상 `2027-01-01 00:00:00`과 실제 `2027-01-01 00:00:00.123`이 다르다.

- [ ] **Step 3: 구현**

`SeoulTimeDisplay.java` 16-18행:

```java
    private static final DateTimeFormatter INSTANT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneId.of("Asia/Seoul"));
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
```

`WorktimeController.java` 49행:

```java
    public static String duration(Long ms){if(ms==null)return "-";long seconds=ms/1000;return String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60);}
```

- [ ] **Step 4: 통과 확인**

Run: Step 2와 같은 명령
Expected: PASS

- [ ] **Step 5: 영향받는 통합 테스트 갱신**

`src/test/java/kr/tna/WorkspaceWebIT.java` 149-150행의 eventTimes 기대값 `"09:17:00.000"`·`"18:41:00.000"`을 `"09:17:00"`·`"18:41:00"`으로 고친다([부록 A](#부록-a-형식-변경으로-바뀌는-기존-기대값) Task 2).

Run: `.\mvnw.cmd -B "-Dtest=None" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=WorkspaceWebIT" verify`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/kr/tna/common/web/SeoulTimeDisplay.java src/main/java/kr/tna/worktime/WorktimeController.java src/test/java/kr/tna/SeoulTimeDisplayTest.java src/test/java/kr/tna/worktime/WorktimeDurationTest.java src/test/java/kr/tna/WorkspaceWebIT.java
git commit -m "feat: format server-side Seoul times and durations to the second"
```

---

### Task 3: '확인 전' 날짜 판단과 화면 DTO

**Files:**
- Modify: `src/main/java/kr/tna/worktime/WorktimeCalculator.java` (`current` 메서드 바로 아래에 추가)
- Modify: `src/main/java/kr/tna/worktime/WorktimeData.java:22-24,32`
- Modify: `src/main/java/kr/tna/worktime/WorktimeService.java` (`detail`, `month`, `readDays`, `storedDay`, `weeklyPeriods`)
- Test: `src/test/java/kr/tna/WorktimeCalculatorTest.java`

- [ ] **Step 1: 실패하는 단위 테스트**

`WorktimeCalculatorTest.java`의 마지막 `@Test` 다음(헬퍼 메서드 위)에 추가한다. 기존 헬퍼 `normal()`, `nonWorkday(kind, leave, pending)`, `withEvents(...)`, `event(...)`를 그대로 쓴다.

```java
    @Test void pendingCountsOnlyPastDaysWhoseWorkIsNotShown() {
        LocalDate today = DATE.plusDays(1);
        assertTrue(calculator.pending(normal(), today, false, null), "기록이 있는데 계산 전이면 확인 전입니다.");
        assertFalse(calculator.pending(normal(), today, true, 8*HOUR), "현재 계산 결과가 있으면 확인 전이 아닙니다.");
        assertTrue(calculator.pending(normal(), today, false, 8*HOUR), "오래된 결과는 값이 보이지 않으므로 확인 전입니다.");
        assertFalse(calculator.pending(normal(), DATE.minusDays(1), false, null), "미래 날짜는 세지 않습니다.");
        assertTrue(calculator.pending(normal(), DATE, false, null), "오늘은 퇴근 기록이 있으면 셉니다.");
        assertFalse(calculator.pending(withEvents(normal(), List.of(event(EventKind.IN, "09:00"))), DATE, false, null), "오늘 퇴근 전은 세지 않습니다.");
    }
    @Test void pendingSkipsOnlyRestDaysTheCalculatorResolvesWithoutRecords() {
        LocalDate today = DATE.plusDays(1);
        assertFalse(calculator.pending(nonWorkday(DayKind.NON_WORKDAY, 0, false), today, false, null), "기록 없는 확정 비근무일");
        assertFalse(calculator.pending(nonWorkday(DayKind.HOLIDAY, 0, false), today, false, null), "기록 없는 확정 휴일");
        assertFalse(calculator.pending(nonWorkday(DayKind.WORKDAY, 1, false), today, false, null), "기록 없는 승인 휴가일");
        assertTrue(calculator.pending(withEvents(normal(), List.of()), today, false, null), "기록 없는 근무일");
        assertTrue(calculator.pending(nonWorkday(DayKind.UNCONFIRMED, 0, false), today, false, null), "캘린더 미확인");
        assertTrue(calculator.pending(nonWorkday(DayKind.HOLIDAY, 0, true), today, false, null), "대기 정정이 있는 휴일");
        var missingSchedule = new DayInput(DATE, List.of(), List.of(), DayKind.WORKDAY, true, 7, 4, null, false, 0);
        assertTrue(calculator.pending(missingSchedule, today, false, null), "일정 미배정");
    }
```

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -B -q "-Dtest=WorktimeCalculatorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: 컴파일 FAIL, `cannot find symbol: method pending`.

- [ ] **Step 3: 계산기 구현**

`WorktimeCalculator.java`의 `public static boolean current(...)` 메서드 바로 아래에 추가한다.

```java
    /**
     * 확인 전: 지난 날(오늘은 퇴근 기록 뒤)인데 실근로가 표시되지 않는 날.
     * 기록 없이 0시간으로 확정되는 확정 비근무일·승인 휴가일만 제외하며, 그 판단은 {@link #day} 규칙을 그대로 쓴다.
     */
    public boolean pending(DayInput input, LocalDate today, boolean current, Long actualWorkMillis) {
        boolean out = input.events().stream().anyMatch(event -> event.kind()==EventKind.OUT);
        if (input.workDate().isAfter(today) || input.workDate().equals(today) && !out) return false;
        if (current && actualWorkMillis!=null) return false;
        if (!input.events().isEmpty()) return true;
        try { return day(input).actualWorkMillis()==null; }
        catch (IllegalArgumentException invalid) { return true; }
    }
```

- [ ] **Step 4: 통과 확인**

Run: Step 2와 같은 명령
Expected: PASS

- [ ] **Step 5: DTO 필드 추가**

`WorktimeData.java`의 `DayView`와 `PeriodView`를 바꾼다.

```java
    public record DayView(UUID id,UUID runId,UUID employeeId,String employeeNo,String employeeName,
            LocalDate workDate,long resultRevision,String ruleVersion,Instant calculatedAt,String sourceFingerprint,
            boolean current,WorktimeCalculator.DayResult result,boolean pending,int approvedLeaveDays) { }
```

```java
    public record PeriodView(LocalDate from,LocalDate to,boolean current,WorktimeCalculator.PeriodResult result,List<UUID> dayResultIds,int pendingDays) { }
```

- [ ] **Step 6: 서비스에서 채우기**

`WorktimeService.java`를 아래처럼 바꾼다.

`readDays`(261-276행) 전체:

```java
    private List<DayView> readDays(UUID tenant,Employee employee,LocalDate from,LocalDate to) {
        var stored=new HashMap<LocalDate,WorktimeMapper.ResultRow>();for(var r:mapper.latest(tenant,employee.id(),from,to))stored.put(r.workDate(),r);
        LocalDate today=LocalDate.now(clock.withZone(SEOUL));
        var days=new ArrayList<DayView>();
        for(LocalDate date=from;!date.isAfter(to);date=date.plusDays(1)) {
            Input current=input(tenant,employee,date);var r=stored.get(date);int leave=current.day().approvedLeaveDays();
            if(r==null) {
                var missing=new DayResult(date,Status.REVIEW_REQUIRED,null,null,null,null,null,null,null,null,null,leave,
                        current.clock().inputRevision(),current.clock().reviewRevision(),Set.of("NOT_CALCULATED","LEGAL_OVERTIME_UNDETERMINED"));
                days.add(new DayView(null,null,employee.id(),employee.employeeNo(),employee.displayName(),date,0,"CALC-1",null,current.source(),false,missing,
                        calculator.pending(current.day(),today,false,null),leave));
            }else {
                var result=storedResult(r);
                boolean matches=WorktimeCalculator.current(result,current.clock().inputRevision(),current.clock().reviewRevision())&&r.sourceFingerprint().equals(current.source());
                days.add(storedDay(employee,r,result,matches,calculator.pending(current.day(),today,matches,result.actualWorkMillis()),leave));
            }
        }return List.copyOf(days);
    }
```

`storedDay`(282-285행):

```java
    private static DayView storedDay(Employee employee,WorktimeMapper.ResultRow r,DayResult result,boolean current,boolean pending,int approvedLeaveDays) {
        return new DayView(r.id(),r.runId(),employee.id(),employee.employeeNo(),employee.displayName(),r.workDate(),r.resultRevision(),r.ruleVersion(),
                r.calculatedAt(),r.sourceFingerprint(),current,result,pending,approvedLeaveDays);
    }
```

`detail`(69행)의 `storedDay(person,stored,result,currentSource)`를 아래로 바꾼다.

```java
                return new ResultDetail(storedDay(person,stored,result,currentSource,
                        calculator.pending(current.day(),LocalDate.now(clock.withZone(SEOUL)),currentSource,result.actualWorkMillis()),current.day().approvedLeaveDays()),
                        stored.id().equals(latest.id()),latest.id(),run,
```

(그다음 줄 `frozen.clock(),...`부터는 그대로 둔다.)

`month`(99-100행)의 `PeriodView` 생성:

```java
            return new MonthView(month,List.copyOf(selected),List.copyOf(weeks),new PeriodView(from,to.plusDays(1),
                    selected.stream().allMatch(DayView::current),result,ids(selected),pendingDays(selected)));
```

`weeklyPeriods`(357행):

```java
            weeks.add(new PeriodView(begin,end,week.stream().allMatch(DayView::current),result,ids(week),pendingDays(week)));
```

`ids` 헬퍼(376행) 아래에 추가:

```java
    private static int pendingDays(List<DayView> days) {return (int)days.stream().filter(DayView::pending).count();}
```

- [ ] **Step 7: 컴파일·단위 테스트**

Run: `.\mvnw.cmd -B -q test`
Expected: `BUILD SUCCESS`. `DayView`·`PeriodView`를 직접 만드는 다른 코드가 컴파일되지 않으면 같은 방식으로 새 인자를 채운다. 현재 생성 위치는 `WorktimeService` 안뿐이다.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/kr/tna/worktime/WorktimeCalculator.java src/main/java/kr/tna/worktime/WorktimeData.java src/main/java/kr/tna/worktime/WorktimeService.java src/test/java/kr/tna/WorktimeCalculatorTest.java
git commit -m "feat: mark days whose actual work is not yet shown as pending"
```

---

### Task 4: 근무 확인 뒤 하루 자동 계산 서비스

**Files:**
- Modify: `src/main/java/kr/tna/worktime/WorktimeService.java:103-124` (`recalculate`) + 새 메서드 2개
- Test: `src/test/java/kr/tna/WorktimeServiceIT.java`

- [ ] **Step 1: 실패하는 통합 테스트**

`WorktimeServiceIT.java`의 `monthUsesFullBoundaryWeeksAndAuditViewingIsItselfAudited` 다음에 추가한다. `@BeforeEach`가 어제(`date`)의 09:00~18:00 기록과 근무 확인을 이미 만든다.

```java
    @Test void confirmedDayIsCalculatedOnceAsASingleDayRun() {
        UUID confirmation=UUID.randomUUID();
        UUID run=reports.calculateConfirmedDay(employee,date,confirmation);
        assertEquals(run,reports.calculateConfirmedDay(employee,date,confirmation),"같은 확인을 다시 보내도 계산은 한 번이어야 합니다.");
        var day=reports.days(employee,employee.employeeId(),date,date).getFirst();
        assertTrue(day.current());assertEquals(8*3_600_000L,day.result().actualWorkMillis());assertFalse(day.pending());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM tna.day_result WHERE tenant_id=? AND employee_id=? AND work_date=?",Integer.class,tenant,employee.employeeId(),date));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM tna.calculation_run WHERE tenant_id=? AND id=? AND requested_by=? AND from_date=? AND to_date=? AND expanded_from=? AND expanded_to=? AND status='COMPLETED'",
                Integer.class,tenant,run,employee.accountId(),date,date,date,date));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='WORKTIME_AUTO_CALCULATE' AND target_id=?",Integer.class,tenant,run));
    }
    @Test void autoCalculationOnlyTouchesTheCallersOwnDay() {
        reports.calculateConfirmedDay(manager,date,UUID.randomUUID());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM tna.day_result WHERE tenant_id=? AND employee_id=?",Integer.class,tenant,employee.employeeId()));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM tna.day_result WHERE tenant_id=? AND employee_id=?",Integer.class,tenant,manager.employeeId()));
    }
    @Test void pendingDaysFollowCalculationAndSkipTheFuture() {
        assertTrue(reports.days(employee,employee.employeeId(),date,date).getFirst().pending(),"확인했지만 계산 전인 어제는 확인 전입니다.");
        assertFalse(reports.days(employee,employee.employeeId(),date.plusDays(2),date.plusDays(2)).getFirst().pending(),"미래 날짜는 세지 않습니다.");
        assertTrue(reports.days(employee,employee.employeeId(),date.minusDays(1),date.minusDays(1)).getFirst().pending(),"기록 없는 지난 근무일은 확인 전입니다.");
        reports.calculateConfirmedDay(employee,date,UUID.randomUUID());
        var month=reports.month(employee,employee.employeeId(),java.time.YearMonth.from(date));
        assertEquals(month.days().stream().filter(DayView::pending).count(),month.summary().pendingDays());
        assertFalse(month.days().stream().filter(d->d.workDate().equals(date)).findFirst().orElseThrow().pending());
    }
```

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -B "-Dtest=None" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=WorktimeServiceIT" verify`
Expected: 컴파일 FAIL, `cannot find symbol: method calculateConfirmedDay`.

- [ ] **Step 3: 공유 저장 메서드로 재집계 정리**

`WorktimeService.recalculate`(103-124행)를 아래로 바꾸고, 바로 아래에 `calculateConfirmedDay`와 `calculate`를 추가한다. 파일 상단 import에 `java.nio.charset.StandardCharsets`를 더한다.

```java
    public UUID recalculate(TnaPrincipal actor,UUID employeeId,LocalDate from,LocalDate to,UUID commandId,String reason) {
        range(from,to);UUID target=target(actor,employeeId);String checked=requiredText(reason,500,"재집계 사유"),action="WORKTIME_RECALCULATE";
        return administration.execute(actor,commandId,action,"CALCULATION_RUN",fingerprint(action,target,from,to,checked),checked,()->
                calculate(actor,employee(actor.tenantId(),target),from,to,WorktimeCalculator.weekStart(from),WorktimeCalculator.weekStart(to).plusDays(6)));
    }
    /** 본인 근무 확인 직후 그날 하루만 계산한다. 같은 확인 명령을 다시 보내도 계산은 한 번이다. */
    public UUID calculateConfirmedDay(TnaPrincipal actor,LocalDate workDate,UUID confirmationCommandId) {
        if(actor==null)throw OrgException.forbidden();
        if(workDate==null||confirmationCommandId==null)throw OrgException.invalid("계산할 근무일을 확인해 주세요.");
        String action="WORKTIME_AUTO_CALCULATE";
        UUID commandId=UUID.nameUUIDFromBytes((action+":"+confirmationCommandId).getBytes(StandardCharsets.UTF_8));
        return commands.execute(actor,commandId,action,"CALCULATION_RUN",fingerprint(action,actor.employeeId(),workDate),"근무 확인 후 자동 계산","EMPLOYEE",
                ()->calculate(actor,employee(actor.tenantId(),actor.employeeId()),workDate,workDate,workDate,workDate));
    }
    private Change calculate(TnaPrincipal actor,Employee employee,LocalDate from,LocalDate to,LocalDate start,LocalDate end) {
        UUID target=employee.id(),runId=UUID.randomUUID();
        changed(mapper.insertRun(actor.tenantId(),runId,target,actor.accountId(),from,to,start,end));int count=0;
        for(LocalDate date=start;!date.isAfter(end);date=date.plusDays(1)) {
            Input input=input(actor.tenantId(),employee,date);DayResult result=calculator.day(input.day());
            UUID resultId=UUID.randomUUID();long revision=mapper.nextResult(actor.tenantId(),target,date);
            var refs=new LinkedHashMap<String,Object>();refs.put("clock",input.clock());refs.put("policy",input.policy());
            refs.put("assessment",input.assessment());refs.put("leaveEffects",input.leave());refs.put("pendingCorrections",input.pending());
            var employment=new LinkedHashMap<String,Object>();employment.put("joinedOn",employee.joinedOn().toString());
            employment.put("leftOn",employee.leftOn()==null?null:employee.leftOn().toString());refs.put("employment",employment);
            changed(mapper.insertResult(actor.tenantId(),resultId,runId,target,revision,result,input.source(),
                    input.policy()==null?null:input.policy().id(),input.assessment()==null?null:input.assessment().id(),
                    json.writeValueAsString(result.reasons().stream().sorted().toList()),json.writeValueAsString(List.of(refs))));
            changed(mapper.calculated(actor.tenantId(),target,date,result.inputRevision(),result.reviewRevision()));count++;
        }
        changed(mapper.completeRun(actor.tenantId(),runId));
        return new Change(runId,null,Map.of("employeeId",target,"from",from,"to",to,"expandedFrom",start,"expandedTo",end,"dayCount",count,"ruleVersion","CALC-1"));
    }
```

- [ ] **Step 4: 통과 확인**

Run: Step 2와 같은 명령
Expected: PASS. 기존 재집계 테스트도 함께 통과해야 한다.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/kr/tna/worktime/WorktimeService.java src/test/java/kr/tna/WorktimeServiceIT.java
git commit -m "feat: calculate a confirmed work day for the employee who confirmed it"
```

---

### Task 5: 출퇴근 화면에서 자동 계산 연결과 그날 실근로 표시

**Files:**
- Modify: `src/main/java/kr/tna/clock/AttendanceController.java`
- Modify: `src/main/java/kr/tna/common/web/ReactPageViewResolver.java` (`clock/history` 허용 키)
- Test: `src/test/java/kr/tna/clock/AttendanceNavigationTest.java`

- [ ] **Step 1: 실패하는 단위 테스트**

`AttendanceNavigationTest.java`의 필드와 생성자를 바꾸고 테스트 2개를 추가한다.

```java
    private final kr.tna.worktime.WorktimeService worktime = mock(kr.tna.worktime.WorktimeService.class);
    private final AttendanceController controller = new AttendanceController(null, clocks, commands, worktime);
```

```java
    @Test void confirmationCalculatesTheDayAndStillReturnsWhenCalculationFails() {
        LocalDate day = today.minusDays(1);UUID command = UUID.randomUUID();
        var form = new ClockData.ConfirmationForm(day, 4L, 2L, true, true, "snapshot-fingerprint", command);
        var binding = new org.springframework.validation.BeanPropertyBindingResult(form, "confirmationForm");
        var redirect = (RedirectView) controller.confirm(actor, form, binding).getView();
        assertEquals("/me/attendance?date=" + day, redirect.getUrl());
        verify(worktime).calculateConfirmedDay(actor, day, command);
        when(worktime.calculateConfirmedDay(actor, day, command)).thenThrow(new kr.tna.org.OrgException(503, "저장소 오류"));
        assertEquals("/me/attendance?date=" + day, ((RedirectView) controller.confirm(actor, form, binding).getView()).getUrl(),
                "계산이 실패해도 저장한 확인은 유지하고 화면을 정상 이동해야 합니다.");
    }
    @Test void dayPageCarriesOnlyTheCurrentActualWork() {
        when(commands.today()).thenReturn(today);
        when(clocks.day(actor, today)).thenReturn(day(today));
        when(worktime.days(actor, actor.employeeId(), today, today)).thenReturn(List.of(view(today, true, 8 * 3_600_000L)));
        assertEquals(8 * 3_600_000L, controller.history(actor, today).getModel().get("actualWorkMillis"));
        when(worktime.days(actor, actor.employeeId(), today, today)).thenReturn(List.of(view(today, false, 8 * 3_600_000L)));
        assertNull(controller.history(actor, today).getModel().get("actualWorkMillis"), "오래된 결과의 값은 보이지 않아야 합니다.");
    }
```

헬퍼를 `day(...)` 아래에 추가한다.

```java
    private kr.tna.worktime.WorktimeData.DayView view(LocalDate date, boolean current, Long actual) {
        var result = new kr.tna.worktime.WorktimeCalculator.DayResult(date, kr.tna.worktime.WorktimeCalculator.Status.CALCULATED,
                null, null, actual, null, null, null, null, null, null, 0, 4, 2, Set.of());
        return new kr.tna.worktime.WorktimeData.DayView(UUID.randomUUID(), UUID.randomUUID(), actor.employeeId(), "E001", "직원",
                date, 1, "CALC-1", Instant.EPOCH, "a".repeat(64), current, result, false, 0);
    }
```

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -B -q "-Dtest=AttendanceNavigationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: 컴파일 FAIL, 생성자 인자 수가 맞지 않는다.

- [ ] **Step 3: 구현**

`AttendanceController.java`를 바꾼다. import에 `kr.tna.worktime.WorktimeService`, `org.slf4j.Logger`, `org.slf4j.LoggerFactory`를 더한다.

```java
    private static final Logger LOG = LoggerFactory.getLogger(AttendanceController.class);
    private static final SeoulTimeDisplay TIME_DISPLAY = new SeoulTimeDisplay();
    private final AttendanceSettingsService settings;private final ClockService clocks;private final OrgCommands commands;private final WorktimeService worktime;
    public AttendanceController(AttendanceSettingsService settings,ClockService clocks,OrgCommands commands,WorktimeService worktime){this.settings=settings;this.clocks=clocks;this.commands=commands;this.worktime=worktime;}
```

`confirm` 핸들러:

```java
    @PostMapping("/me/work-confirmations") ModelAndView confirm(@AuthenticationPrincipal TnaPrincipal actor,@ModelAttribute("confirmationForm") ConfirmationForm form,BindingResult b){check(b);return submit(()->{UUID id=clocks.confirm(actor,form);calculate(actor,form);return id;},"/me/attendance?date="+form.workDate(),()->dayPage(actor,form.workDate(),form));}
```

`dayPage`의 `var page=...` 뒤에 그날 실근로를 넣고, 새 private 메서드 `calculate`를 `dayPage` 아래에 추가한다. import에 `kr.tna.worktime.WorktimeData.DayView`를 더한다. 현재 결과가 아니거나 값이 없으면 null이다.

```java
        page.addObject("actualWorkMillis",worktime.days(actor,actor.employeeId(),d.workDate(),d.workDate()).stream()
                .filter(DayView::current).map(view->view.result().actualWorkMillis()).findFirst().orElse(null));
```

```java
    /** 확인은 이미 저장됐다. 계산 실패는 그날을 확인 전으로 남기고 담당자 재집계로 복구한다. */
    private void calculate(TnaPrincipal actor,ConfirmationForm form){
        try{worktime.calculateConfirmedDay(actor,form.workDate(),form.commandId());}
        catch(RuntimeException failed){LOG.warn("event=worktime_auto_calculate outcome=failed status={}",failed instanceof OrgException e?e.status():500);}
    }
```

`ModelAndView.addObject`는 null 값을 받는다. 기존 두 테스트처럼 `worktime.days`를 stub하지 않으면 Mockito가 빈 목록을 돌려주므로 null이 들어가고 테스트는 그대로 통과한다.

React 페이지 직렬화기는 화면별 허용 키만 내보낸다. `ReactPageViewResolver.java`의 `clock/history` 항목에 `"actualWorkMillis"`를 추가한다.

```java
            page("clock/history", "day", "eventTimes", "confirmationForm", "today", "isToday", "inCommand", "outCommand", "actualWorkMillis"),
```

- [ ] **Step 4: 통과 확인**

Run: Step 2와 같은 명령
Expected: PASS

- [ ] **Step 5: 스프링 연결 확인**

Run: `.\mvnw.cmd -B "-Dtest=None" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=AttendanceWebIT,WorktimeWebIT" verify`
Expected: PASS. 생성자 주입과 `/me/attendance`·`/me/work-confirmations`가 실제 컨텍스트에서 동작한다.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/kr/tna/clock/AttendanceController.java src/main/java/kr/tna/common/web/ReactPageViewResolver.java src/test/java/kr/tna/clock/AttendanceNavigationTest.java
git commit -m "feat: calculate actual work right after the employee confirms a day"
```

---

### Task 6: 정정 화면의 현재 승인 정정 ID 자동 입력

**Files:**
- Modify: `src/main/java/kr/tna/request/LeaveWorkflowService.java` (새 public 메서드)
- Modify: `src/main/java/kr/tna/request/RequestController.java:69` (`correctionPage`)
- Test: `src/test/java/kr/tna/LeaveWorkflowIT.java`
- Create: `src/test/java/kr/tna/request/CorrectionPageTest.java`

- [ ] **Step 1: 실패하는 테스트**

`LeaveWorkflowIT.java`의 `approvedCorrectionKeepsPriorHistoryAndBindsNewEvidenceToTheNewFactRevision` 다음에 추가한다.

```java
    @Test void correctionFormReadsTheCurrentApprovedCorrectionId() {
        assertNull(workflow.currentCorrectionRequestId(employee,employee.employeeId(),correctionDate));
        UUID first=correction(null);approve(first);
        assertEquals(first,workflow.currentCorrectionRequestId(employee,employee.employeeId(),correctionDate));
    }
```

새 파일 `src/test/java/kr/tna/request/CorrectionPageTest.java`:

```java
package kr.tna.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockData;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.request.RequestData.CorrectionForm;
import org.junit.jupiter.api.Test;

class CorrectionPageTest {
    private final LeaveWorkflowService flow = mock(LeaveWorkflowService.class);
    private final ClockService clocks = mock(ClockService.class);
    private final RequestController controller = new RequestController(flow, clocks, null, null, null, null, null);
    private final TnaPrincipal actor = new TnaPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, Set.of("EMPLOYEE"), Instant.EPOCH);

    @Test void correctionPageFillsTheCurrentApprovedCorrectionInsteadOfAskingForAnId() {
        LocalDate date = LocalDate.of(2026, 11, 4);UUID current = UUID.randomUUID();
        when(clocks.day(actor, date)).thenReturn(new ClockData.DaySnapshot(actor.employeeId(), date, List.of(), null, null, null, "UNCONFIRMED", 3, 1, null, "a".repeat(64), List.of()));
        when(flow.currentCorrectionRequestId(actor, actor.employeeId(), date)).thenReturn(current);
        var form = (CorrectionForm) controller.correction(actor, date).getModel().get("form");
        assertEquals(current, form.replacesRequestId());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -B -q "-Dtest=CorrectionPageTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: 컴파일 FAIL, `cannot find symbol: method currentCorrectionRequestId`.

- [ ] **Step 3: 구현**

`LeaveWorkflowService.java`의 public 메서드들 사이(예: `submitCorrection` 위)에 추가한다. `Supplier`는 이미 import돼 있다.

```java
    /** 그 날짜의 현재 승인 정정 신청 ID. 정정 화면이 ID를 입력받지 않고 hidden으로 보내도록 서버가 채운다. */
    public UUID currentCorrectionRequestId(TnaPrincipal actor,UUID employeeId,LocalDate date) {
        if(actor==null)throw OrgException.forbidden();
        Supplier<UUID> read=()->{var effect=mapper.currentCorrection(actor.tenantId(),employeeId,date);return effect==null?null:effect.requestId();};
        return actor.employeeId().equals(employeeId)?commands.read(actor,read):administration.read(actor,read);
    }
```

`RequestController.correctionPage`(69행)의 새 폼 생성에서 `replacesRequestId` 자리(`actualBreaks` 다음의 첫 `null`)를 채운다.

```java
...addObject("form",f!=null?f:new CorrectionForm(exceptional?employee:null,date,day.inputRevision(),day.sourceFingerprint(),"KEEP","","KEEP","",false,false,"",flow.currentCorrectionRequestId(a,employee,date),null,exceptional,"",UUID.randomUUID()));}
```

- [ ] **Step 4: 통과 확인**

Run: Step 2와 같은 명령, 이어서 `.\mvnw.cmd -B "-Dtest=None" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=LeaveWorkflowIT,LeaveWorkflowWebIT" verify`
Expected: 모두 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/kr/tna/request/LeaveWorkflowService.java src/main/java/kr/tna/request/RequestController.java src/test/java/kr/tna/LeaveWorkflowIT.java src/test/java/kr/tna/request/CorrectionPageTest.java
git commit -m "feat: fill the current approved correction on the correction page"
```

---

### Task 7: 내 정보 정리

**Files:**
- Modify: `frontend/src/pages/IdentityPages.tsx:167-208,377-379`
- Modify: `frontend/src/pages/PersonalDetails.tsx:85,101,117`
- Test: `frontend/src/pages/IdentityPages.test.tsx:40-86`

- [ ] **Step 1: 테스트를 새 기준으로 바꾸기**

`IdentityPages.test.tsx` 44-45행(주민등록번호 안내가 보인다는 기대)을 아래로 바꾼다.

```tsx
  expect(view.getByRole('tabpanel').textContent).not.toContain('주민등록번호');
  expect(view.container.textContent).not.toContain('개인 정보와 입사 정보를 한눈에 확인하세요.');
```

56-86행의 내 정보 테스트 4개(`본인의 현재 조직장과…`, `내 정보에 본인 계정·현재 배정…`, `미배정 정보와 부적격 결재 담당…`, `결재 담당이 없으면 미배정…`)를 지우고 아래 1개로 바꾼다.

```tsx
  it('내 정보는 인사 탭만 보이고 계정·배정·역할 정보는 보이지 않는다', async () => {
    const view = await show('workspace/profile', { profile: { ...profile, organizationHeadName: '김조직장' },
      personalDetails: { employeeId: 'e-1', version: 0, form: {}, entries: [], appointments: [] } });
    const main = view.container.querySelector('main')!;
    expect(view.getByRole('heading', { name: '내 정보' })).toBeTruthy();
    for (const text of ['계정과 업무 배정', 'employee@example.test', '김조직장', '박승인', '조회 기준', '공통 · 관리자'])
      expect(main.textContent).not.toContain(text);
    expect(view.getAllByRole('tab')).toHaveLength(7);
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npm test -- src/pages/IdentityPages.test.tsx`
Expected: FAIL. '계정과 업무 배정'과 주민등록번호 안내가 아직 보인다.

- [ ] **Step 3: 구현**

`IdentityPages.tsx`의 `ProfilePage`(167-208행) 전체를 아래로 바꾼다.

```tsx
function ProfilePage({ details, identity }: {
  details?: PersonalDetailsModel;
  identity: { employeeName: string; joinedOn: string; birthDate?: string | null };
}) {
  return (
    <>
      <PageHeader title="내 정보" />
      {details && <PersonalDetailsTabs model={details} identity={identity} />}
    </>
  );
}
```

`IdentityPages`의 `workspace/profile` 분기(377-379행):

```tsx
    case 'workspace/profile': {
      const p = data.profile as Profile;
      return <ProfilePage details={data.personalDetails as PersonalDetailsModel | undefined} identity={{ employeeName: p.employeeName,
        joinedOn: p.joinedOn, birthDate: (data.employeeHeader as { birthDate?: string | null } | undefined)?.birthDate }} />;
    }
```

`PersonalDetails.tsx`에서 탭 설명 배열 `descriptions`(85행)를 지우고, 101행 제목 묶음에서 `<p>{descriptions[tab]}</p>`를 지운다.

```tsx
      <div className="t-personal-panel-heading"><div><h2>{tabs[tab]}</h2></div>
```

117행의 `<p className="t-personal-privacy">…주민등록번호…</p>` 요소 전체를 지운다.

- [ ] **Step 4: 남은 미사용 코드 정리**

Run: `npx tsc --noEmit -p .` (frontend에서)
Expected: 오류 없음. 이 변경으로 쓰이지 않게 된 import(`Profile` 필드를 읽던 헬퍼 등)가 있으면 그 import만 지운다. 지운 마크업의 CSS는 Task 13에서 정리한다.

- [ ] **Step 5: 통과 확인**

Run: `npm test -- src/pages/IdentityPages.test.tsx src/pages/PersonalDetails.test.tsx src/pages/ApprovalRoutePages.test.tsx`
Expected: `ApprovalRoutePages.test.tsx` 275-280행의 내 정보 결재 경로 테스트가 실패하면 그 테스트를 지운다([부록 A](#부록-a-형식-변경으로-바뀌는-기존-기대값) Task 7). 이후 모두 PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/IdentityPages.tsx frontend/src/pages/IdentityPages.test.tsx frontend/src/pages/PersonalDetails.tsx frontend/src/pages/ApprovalRoutePages.test.tsx
git commit -m "feat: drop account and assignment details from my profile"
```

---

### Task 8: 출퇴근 기록 정리

**Files:**
- Modify: `frontend/src/pages/WorkflowTypes.ts:157-165` (`ClockModel`)
- Modify: `frontend/src/pages/WorkflowPages.tsx:1063-1168` (`AttendanceRecord`), `1223-1250` (`clock/history`)
- Test: `frontend/src/pages/WorkflowPages.test.tsx:626-656`

- [ ] **Step 1: 실패하는 테스트**

`WorkflowPages.test.tsx` 626-645행 테스트(`출퇴근 확인은 사실/검토 개정과…`)를 아래 3개로 바꾼다. 파일 위쪽 `day` 상수 아래에 확인 가능한 날 픽스처를 추가한다.

```tsx
const confirmable = {
  ...day,
  dayKind: 'WORKDAY',
  calendarVersionId: 'calendar-1',
  schedule: { workday: true, startTime: '09:00:00.000', endTime: '18:00:00.000', breaks: [{ startTime: '12:00:00.000', endTime: '13:00:00.000' }] },
  events: [{ id: 'in-1', kind: 'IN', occurredAt: '2026-10-26T00:00:00.123Z' }, { id: 'out-1', kind: 'OUT', occurredAt: '2026-10-26T09:03:40.000Z' }],
  effectiveEvents: [{ id: 'in-1', kind: 'IN', occurredAt: '2026-10-26T00:00:00.123Z' }, { id: 'out-1', kind: 'OUT', occurredAt: '2026-10-26T09:03:40.000Z' }],
};
const confirmationForm = { workDate: day.workDate, baseInputRevision: 2, expectedReviewRevision: 3, sourceFingerprint: 'clock-fingerprint', commandId: 'confirm-command' };
```

```tsx
  it('출퇴근 기록은 구분·시각만 보이고 버전·캘린더 코드·안내문을 보이지 않는다', async () => {
    const view = await render('clock/history', { day: confirmable, eventTimes: { 'in-1': '2026-10-26 09:00:00' }, confirmationForm });
    const text = view.container.textContent ?? '';
    for (const hidden of ['출근 기록·실제 근로 확인', '사실 v', '검토 v', '캘린더:', 'Asia/Seoul', '체류시간을 실제 근로로', '원천'])
      expect(text).not.toContain(hidden);
    expect(text).toContain('예정 09:00~18:00 · 휴게 12:00~13:00');
    const grid = view.getByRole('grid', { name: '출퇴근 기록' });
    expect(Array.from(grid.querySelectorAll('.ag-header-cell[col-id]')).map(cell => cell.getAttribute('col-id'))).toEqual(['kind', 'occurredAt']);
    expect(within(grid).getByRole('columnheader', { name: /구분/ })).toBeTruthy();
    expect(within(grid).getByRole('columnheader', { name: /시각/ })).toBeTruthy();
    expect(grid.querySelector('[row-id="in-1"]')?.textContent).toContain('2026-10-26 09:00:00');
  });
  it('근무 확인은 출근·퇴근이 하나씩 있고 미확인일 때만 보이며 개정·지문·명령을 제출한다', async () => {
    const view = await render('clock/history', { day: confirmable, eventTimes: {}, confirmationForm });
    const posted = body(view, '/me/work-confirmations');
    expect(posted.get('expectedReviewRevision')).toBe('3');
    expect(posted.get('sourceFingerprint')).toBe('clock-fingerprint');
    expect(posted.get('commandId')).toBe('confirm-command');
    expect(view.getByRole('button', { name: '근무 확인' })).toBeTruthy();
    expect(view.container.textContent).not.toContain('원천 한 쌍');
    for (const blocked of [{ ...confirmable, schedule: null }, { ...day, schedule: confirmable.schedule, calendarVersionId: 'calendar-1' },
      { ...confirmable, confirmation: { id: 'c-1' } }])
      expect((await render('clock/history', { day: blocked, eventTimes: {}, confirmationForm })).container.querySelector('form[action="/me/work-confirmations"]')).toBeNull();
  });
  it('근무 확인 뒤에는 계산된 실근로를 초 단위로 보인다', async () => {
    const confirmed = { ...confirmable, confirmation: { id: 'c-1' } };
    expect((await render('clock/history', { day: confirmed, eventTimes: {}, confirmationForm, actualWorkMillis: 29_486_000 })).container.textContent)
      .toContain('근무 확인 완료 · 실근로 8:11:26');
    expect((await render('clock/history', { day: confirmed, eventTimes: {}, confirmationForm, actualWorkMillis: null })).container.textContent)
      .toContain('근무 확인 완료 · 실근로는 담당자 재집계 후 표시됩니다.');
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npm test -- src/pages/WorkflowPages.test.tsx`
Expected: FAIL. '출근 기록·실제 근로 확인'이 보이고 '출퇴근 기록' 그리드가 없다.

- [ ] **Step 3: 타입**

`WorkflowTypes.ts`의 `ClockModel`에 필드를 추가한다.

```ts
  actualWorkMillis?: number | null;
```

- [ ] **Step 4: 구현**

`WorkflowPages.tsx` 상단 import 목록에 `duration`을 더한다. `AttendanceRecord`(1063-1168행) 전체를 아래로 바꾼다.

```tsx
const eventName = (kind: string) => (kind === 'IN' ? '출근' : kind === 'OUT' ? '퇴근' : kind);
export function scheduleLine(day: DaySnapshot) {
  if (day.schedule == null) return null;
  if (!day.schedule.workday) return '비근무일';
  return [`예정 ${fmtTime(day.schedule.startTime)}~${fmtTime(day.schedule.endTime)}`,
    ...day.schedule.breaks.map(b => `휴게 ${fmtTime(b.startTime)}~${fmtTime(b.endTime)}`)].join(' · ');
}
export function AttendanceRecord({ data, showSchedule = true }: { data: Record<string, unknown>; showSchedule?: boolean }): React.ReactNode {
  const m = data as unknown as ClockModel,
    d = m.day,
    f = m.confirmationForm;
  const time = (event: ClockEvent) => m.eventTimes?.[event.id] ?? fmtDateTime(event.occurredAt);
  const columns = [
    sheetColumn<ClockEvent>({ field: 'kind', headerName: '구분', minWidth: 120, pinned: 'left', valueGetter: ({ data }) => data ? eventName(data.kind) : '' }, event => eventName(event.kind)),
    sheetColumn<ClockEvent>({ field: 'occurredAt', headerName: '시각', minWidth: 200, flex: 1, valueGetter: ({ data }) => data ? time(data) : '' }, time),
  ];
  const effective = d.effectiveEvents ?? d.events;
  const changedEvents =
    d.effectiveEvents != null &&
    (d.events.length !== d.effectiveEvents.length ||
      d.events.some((e, i) => {
        const after = d.effectiveEvents?.[i];
        return e.id !== after?.id || e.kind !== after?.kind || e.occurredAt !== after?.occurredAt;
      }));
  const canConfirm = d.schedule != null && d.calendarVersionId != null && d.confirmation == null
    && effective.filter(e => e.kind === 'IN').length === 1 && effective.filter(e => e.kind === 'OUT').length === 1;
  return (
    <section className="wl-section">
      {d.schedule == null ? <p className="notice">근무 일정 미배정</p> : showSchedule && <p className="t-muted">{scheduleLine(d)}</p>}
      <WorklogSheet title="출퇴근 기록" label="출퇴근 기록" rows={d.events} columns={columns} getRowId={event => event.id} scope="all" />
      {changedEvents && (
        <div className="notice">
          <h3>정정 반영 기록</h3>
          <ul>
            {d.effectiveEvents?.map(e => <li key={e.id}>{eventName(e.kind)} · {time(e)}</li>)}
          </ul>
        </div>
      )}
      {d.confirmation && (
        <p className="notice">
          {m.actualWorkMillis != null ? `근무 확인 완료 · 실근로 ${duration(m.actualWorkMillis)}` : '근무 확인 완료 · 실근로는 담당자 재집계 후 표시됩니다.'}
        </p>
      )}
      {canConfirm && (
        <Form action="/me/work-confirmations" method="post" className="wl-form">
          {hidden('workDate', f.workDate)}
          {hidden('baseInputRevision', f.baseInputRevision)}
          {hidden('expectedReviewRevision', f.expectedReviewRevision)}
          {hidden('sourceFingerprint', f.sourceFingerprint)}
          {hidden('commandId', f.commandId)}
          <h3>근무 확인</h3>
          <FormTable>
            <FormRow label="근무 확인" required>
              <label className="checkbox-label">
                <input type="checkbox" name="presenceAsWork" value="true" defaultChecked={f.presenceAsWork} required />
                휴게를 뺀 실제 근로가 맞습니다
              </label>
              <label className="checkbox-label">
                <input type="checkbox" name="breaksMatch" value="true" defaultChecked={f.breaksMatch} required />
                휴게가 예정과 같습니다
              </label>
            </FormRow>
          </FormTable>
          <div className="wl-form-actions">
            <button type="submit" className="t-btn primary">근무 확인</button>
          </div>
        </Form>
      )}
    </section>
  );
}
```

`clock/history` 분기(1223-1250행)에서 오늘의 근무 카드 문구와 `AttendanceRecord` 호출을 바꾼다.

```tsx
    case 'clock/history': {
      const clock = data as unknown as ClockModel;
      const todayCard = clock.isToday && clock.day.workDate === data.today;
      content = (
        <>
          <PageHeader title="출퇴근 기록" help="출퇴근 시각을 기록하고 근무를 확인하세요." />
          {todayCard && <section className="t-clock-actions t-card" aria-label="오늘 출퇴근">
            <div><span className="t-clock-indicator" aria-hidden="true" /><strong>오늘의 근무</strong>{scheduleLine(clock.day) && <p className="t-muted">{scheduleLine(clock.day)}</p>}</div>
            <div className="t-clock-buttons">{(['IN', 'OUT'] as const).map(kind => <Form key={kind} action="/me/attendance/events">
              {hidden('eventKind', kind)}{hidden('commandId', kind === 'IN' ? data.inCommand as string : data.outCommand as string)}
              <button className={`t-btn${kind === 'IN' ? ' primary' : ''}`} disabled={!(kind === 'IN' ? data.inCommand : data.outCommand)}>{kind === 'IN' ? '출근 기록' : '퇴근 기록'}</button>
            </Form>)}</div>
          </section>}
          <Form action="/me/attendance" method="get" className="wl-querybar">
            <Field label="기준일" name="date" type="date" value={clock.day.workDate} required />
            <div className="wl-querybar-actions">
              <a className="t-btn wl-query-reset" href="/me/attendance">오늘</a>
              <button className="t-btn wl-query-submit" type="submit">조회</button>
            </div>
          </Form>
          <AttendanceRecord data={data} showSchedule={!todayCard} />
        </>
      );
      break;
    }
```

- [ ] **Step 5: 통과 확인**

Run: `npm test -- src/pages/WorkflowPages.test.tsx`
Expected: 새 테스트 PASS. 같은 파일의 근로시간·신청 상세 관련 실패는 Task 9·12에서 고친다. `npm test -- src/pages/CorePages.test.tsx src/App.test.tsx`도 실행해, `today` 화면에서 출퇴근 기록을 함께 그리는 기존 테스트가 통과하는지 확인한다.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/WorkflowTypes.ts frontend/src/pages/WorkflowPages.tsx frontend/src/pages/WorkflowPages.test.tsx
git commit -m "feat: simplify the attendance record and show worked time after confirming"
```

---

### Task 9: 근로시간 화면 정리

**Files:**
- Modify: `frontend/src/pages/WorkflowTypes.ts:237-271` (`DayView`, `PeriodView`, `MonthModel`)
- Modify: `frontend/src/pages/WorkflowPages.tsx:76,532-631` (`rawMillis`, `Month`)
- Modify: `src/main/java/kr/tna/worktime/WorktimeController.java:45` (`monthPage`)
- Modify: `src/main/java/kr/tna/common/web/ReactPageViewResolver.java` (`worktime/month` 허용 키)
- Test: `frontend/src/pages/WorkflowPages.test.tsx:458-505,555-572`

- [ ] **Step 1: 실패하는 테스트**

`WorkflowPages.test.tsx`의 두 근로시간 테스트(458-505행 `월 결과는 미확정과…`, 555-572행 `일 결과는 미확정·실제0시간을…`)를 아래 2개로 바꾼다.

```tsx
  it('근로시간은 실근로·확인 전·휴가 지표와 다섯 열만 보이고 담당자 명령을 제한한다', async () => {
    const data = {
      admin: true, employeeId: employee.id, employees: [employee], from: '2026-10-01', to: '2026-10-31',
      calculateCommand: 'calc-command', exportCommand: 'export-command',
      overview: {
        month: '2026-10',
        days: [
          { id: 'result-1', workDate: '2026-10-26', current: true, pending: false, approvedLeaveDays: 0,
            result: { ...result, actualWorkMillis: 29_110_000, confirmedBreakMillis: 3_600_000, over8hMillis: 310_000 } },
          { id: null, workDate: '2026-10-27', current: false, pending: true, approvedLeaveDays: 0, result },
          { id: 'result-3', workDate: '2026-10-28', current: false, pending: true, approvedLeaveDays: 1,
            result: { ...result, actualWorkMillis: 28_800_000 } },
        ],
        weeks: [{ from: '2026-10-26', to: '2026-11-02', current: false, pendingDays: 2,
          result: { confirmedWorkMillis: 29_110_000, unconfirmedDays: 6, over40hMillis: null, reasons: [] } }],
        summary: { from: '2026-10-01', to: '2026-11-01', current: false, pendingDays: 2,
          result: { confirmedWorkMillis: 29_110_000, unconfirmedDays: 30, over40hMillis: null, reasons: ['PARTIAL_TOTAL'] } },
      },
    };
    const view = await render('worktime/month', data);
    const text = view.container.textContent ?? '';
    expect(view.getByRole('heading', { name: '근로시간' })).toBeTruthy();
    for (const hidden of ['일·주·월 근로시간 검토', '상태·최신성', '실근로 ms', '휴게 ms', 'PARTIAL_TOTAL', 'REVIEW_REQUIRED', 'CALC-1', '미확정', '(서울)', '결과 ID·입력/규칙 버전'])
      expect(text).not.toContain(hidden);
    const summary = view.getByLabelText('월 요약');
    expect(summary.textContent).toContain('실근로 합계8:05:10');
    expect(summary.textContent).toContain('확인 전2일');
    expect(summary.textContent).toContain('휴가1일');
    const grid = view.getByRole('grid', { name: '일별 근로시간' });
    expect(Array.from(grid.querySelectorAll('.ag-header-cell[col-id]')).map(cell => cell.getAttribute('col-id')))
      .toEqual(['workDate', 'actualWorkMillis', 'confirmedBreakMillis', 'over8hMillis', 'approvedLeaveDays']);
    expect(within(grid).getByRole('columnheader', { name: /8시간 초과/ })).toBeTruthy();
    expect(grid.querySelector('[row-id="2026-10-26"] [col-id="actualWorkMillis"]')?.textContent).toBe('8:05:10');
    expect(grid.querySelector('[row-id="2026-10-28"] [col-id="actualWorkMillis"]')?.textContent).toBe('-');
    expect(grid.querySelector('[row-id="2026-10-28"] [col-id="approvedLeaveDays"]')?.textContent).toBe('1');
    expect(within(grid).getByRole('link', { name: '2026-10-26' }).getAttribute('href')).toBe('/worktime/results/result-1');
    const weeks = view.getByRole('grid', { name: '주간 근로시간' });
    expect(weeks.textContent).toContain('2026-10-26 ~ 2026-11-01');
    expect(weeks.querySelector('[col-id="pendingDays"]:not(.ag-header-cell)')?.textContent).toBe('2일');
    expect(view.container.querySelector('form[action="/admin/calculations"]')).toBeTruthy();
    expect(view.container.querySelector('form[action="/admin/reports/exports"]')).toBeTruthy();
    expect((await render('worktime/month', { ...data, admin: false })).container.querySelector('form[action="/admin/calculations"]')).toBeNull();
  });
  it('일별 실근로는 표시 문자열이 아닌 밀리초 값으로 정렬한다', async () => {
    const days = [null, 0, 10_000, 2_000].map((actualWorkMillis, index) => ({
      id: null, workDate: `2026-10-${20 + index}`, current: true, pending: false, approvedLeaveDays: 0,
      result: { ...result, actualWorkMillis },
    }));
    const period = { current: true, pendingDays: 0, result: { confirmedWorkMillis: 12_000, unconfirmedDays: 1, over40hMillis: null, reasons: [] } };
    const view = await render('worktime/month', { admin: false, employeeId: employee.id, employees: [],
      overview: { month: '2026-10', days, weeks: [], summary: period } });
    const grid = view.getByRole('grid', { name: '일별 근로시간' });
    expect(grid.querySelector('[row-id="2026-10-20"] [col-id="actualWorkMillis"]')?.textContent).toBe('-');
    expect(grid.querySelector('[row-id="2026-10-21"] [col-id="actualWorkMillis"]')?.textContent).toBe('0:00:00');
    fireEvent.click(within(grid).getByRole('columnheader', { name: /실근로/ }).querySelector('.ag-header-cell-label')!);
    await waitFor(() => expect(grid.querySelector('[row-id="2026-10-23"]')?.getAttribute('row-index')).toBe('2'));
    expect(grid.querySelector('[row-id="2026-10-22"]')?.getAttribute('row-index')).toBe('3');
  });
```

- [ ] **Step 2: 실패 확인**

Run: `npm test -- src/pages/WorkflowPages.test.tsx`
Expected: FAIL. 제목이 '일·주·월 근로시간 검토'이고 '일별 근로시간' 그리드가 없다.

- [ ] **Step 3: 타입**

`WorkflowTypes.ts`를 바꾼다. `DayView`에 두 필드, `PeriodView`에 한 필드를 더하고 `MonthModel`에서 `actualTimes`·`totalTime`을 지운다.

```ts
export interface DayView {
  id: string | null;
  employeeId: string;
  employeeNo: string;
  employeeName: string;
  workDate: string;
  resultRevision: number;
  ruleVersion: string;
  calculatedAt: string;
  current: boolean;
  pending: boolean;
  approvedLeaveDays: number;
  result: DayResult;
}
```

```ts
export interface PeriodView {
  from: string;
  to: string;
  current: boolean;
  pendingDays: number;
  result: PeriodResult;
}
export interface MonthModel {
  overview: { month: string; days: DayView[]; weeks: PeriodView[]; summary: PeriodView };
  employeeId: string;
  employees: EmployeeChoice[];
  admin: boolean;
  from: string;
  to: string;
  calculateCommand: string;
  exportCommand: string;
}
```

- [ ] **Step 4: 구현**

`WorkflowPages.tsx`의 `Month`(532-631행) 전체를 아래로 바꾼다. import 목록의 `PeriodView` 타입을 `./WorkflowTypes`에서 가져오도록 더한다.

```tsx
function Month({ model: m }: { model: MonthModel }) {
  const view = m.overview,
    summary = view.summary;
  const shown = (day: DayView, value: number | null) => (day.current ? value : null);
  const timeColumn = (colId: string, headerName: string, pick: (day: DayView) => number | null) =>
    sheetColumn<DayView>({ colId, headerName, minWidth: 120, cellClass: 'w-sheet-number', filter: 'agNumberColumnFilter',
      valueGetter: ({ data }) => (data ? shown(data, pick(data)) : null),
      getQuickFilterText: ({ data }) => (data ? duration(shown(data, pick(data))) : '') },
      day => duration(shown(day, pick(day))));
  const columns = [
    sheetColumn<DayView>({ field: 'workDate', headerName: '근무일', minWidth: 130, pinned: 'left' },
      day => (day.id ? <a href={`/worktime/results/${day.id}`}>{fmtDate(day.workDate)}</a> : fmtDate(day.workDate))),
    timeColumn('actualWorkMillis', '실근로', day => day.result.actualWorkMillis),
    timeColumn('confirmedBreakMillis', '휴게', day => day.result.confirmedBreakMillis),
    timeColumn('over8hMillis', '8시간 초과', day => day.result.over8hMillis),
    sheetColumn<DayView>({ field: 'approvedLeaveDays', headerName: '휴가', minWidth: 90, cellClass: 'w-sheet-number', filter: 'agNumberColumnFilter' },
      day => (day.approvedLeaveDays ? day.approvedLeaveDays : '-')),
  ];
  const weekColumns = [
    sheetColumn<PeriodView>({ colId: 'week', headerName: '주간', minWidth: 210, pinned: 'left',
      valueGetter: ({ data }) => (data ? `${data.from} ~ ${previousDay(data.to)}` : '') }, week => `${week.from} ~ ${previousDay(week.to)}`),
    sheetColumn<PeriodView>({ colId: 'confirmedWorkMillis', field: 'result.confirmedWorkMillis', headerName: '실근로', minWidth: 120,
      cellClass: 'w-sheet-number', filter: 'agNumberColumnFilter' }, week => duration(week.result.confirmedWorkMillis)),
    sheetColumn<PeriodView>({ field: 'pendingDays', headerName: '확인 전', minWidth: 100, cellClass: 'w-sheet-number',
      filter: 'agNumberColumnFilter' }, week => `${week.pendingDays}일`),
    sheetColumn<PeriodView>({ colId: 'over40hMillis', field: 'result.over40hMillis', headerName: '40시간 초과', minWidth: 130,
      cellClass: 'w-sheet-number', filter: 'agNumberColumnFilter' }, week => duration(week.result.over40hMillis)),
  ];
  const leaveDays = view.days.reduce((sum, day) => sum + day.approvedLeaveDays, 0);
  const periodFields = (
    <>
      {hidden('employeeId', m.employeeId)}
      {hidden('from', m.from)}
      {hidden('to', m.to)}
    </>
  );
  return (
    <>
      <PageHeader title="근로시간" />
      <Form action={m.admin ? '/admin/attendance' : '/me/worktime'} method="get" className="wl-querybar">
        <Field label="기준 월" name="month" type="month" value={view.month} required />
        {m.admin && (
          <SelectField label="대상 사원" name="employeeId" value={m.employeeId}>
            <EmployeeOptions employees={m.employees} />
          </SelectField>
        )}
        <div className="wl-querybar-actions"><button className="t-btn wl-query-submit" type="submit">조회</button></div>
      </Form>
      <dl className="wl-summary" aria-label="월 요약">
        <div><dt>실근로 합계</dt><dd>{duration(summary.result.confirmedWorkMillis)}</dd></div>
        <div data-tone={summary.pendingDays ? 'warn' : undefined}><dt>확인 전</dt><dd>{summary.pendingDays}일</dd></div>
        <div><dt>휴가</dt><dd>{leaveDays}일</dd></div>
      </dl>
      <section className="wl-section">
        <WorklogSheet label="일별 근로시간" title="일별" rows={view.days} columns={columns} getRowId={day => day.workDate} scope="all" />
      </section>
      <section className="wl-section">
        <WorklogSheet label="주간 근로시간" title="주간" rows={view.weeks} columns={weekColumns} getRowId={week => week.from} scope="all" />
      </section>
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
            <FormTable><Field label="검토용 출력 사유" name="reason" maxLength={500} required /></FormTable>
            <div className="wl-form-actions"><button className="t-btn">검토용 XLSX 다운로드</button></div>
          </Form>
        </section>
      )}
    </>
  );
}
```

`rawMillis`(76행)와 `Reasons`(65행)가 더 이상 쓰이지 않으면 지운다(`npx tsc --noEmit -p .`로 확인). 다른 컴포넌트가 쓰면 그대로 둔다.

`WorktimeController.java` 45행 `monthPage`에서 `actualTimes`·`totalTime`과 그 지도 생성을 지운다.

```java
    private ModelAndView monthPage(TnaPrincipal a,UUID employeeId,YearMonth month,boolean admin){var view=worktime.month(a,employeeId,month);return new ModelAndView("worktime/month").addObject("overview",view).addObject("employeeId",employeeId).addObject("admin",admin).addObject("employees",admin?worktime.employees(a):List.of()).addObject("from",month.atDay(1)).addObject("to",month.atEndOfMonth()).addObject("calculateCommand",UUID.randomUUID()).addObject("exportCommand",UUID.randomUUID());}
```

쓰지 않게 된 Java import(`HashMap` 등)가 생기면 지운다. `ReactPageViewResolver.java`의 `worktime/month` 허용 키에서 `"actualTimes", "totalTime"`을 지운다.

```java
            page("worktime/month", "overview", "employeeId", "admin", "employees", "from", "to", "calculateCommand", "exportCommand"),
```

- [ ] **Step 5: 통과 확인**

Run: `npm test -- src/pages/WorkflowPages.test.tsx`, `.\mvnw.cmd -B -q test`
Expected: 근로시간 테스트 PASS, 백엔드 `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/WorkflowTypes.ts frontend/src/pages/WorkflowPages.tsx frontend/src/pages/WorkflowPages.test.tsx src/main/java/kr/tna/worktime/WorktimeController.java src/main/java/kr/tna/common/web/ReactPageViewResolver.java
git commit -m "feat: show monthly work time as plain hours with pending days"
```

---

### Task 10: 근무 일정 안내문 정리

**Files:**
- Modify: `frontend/src/pages/CorePages.tsx:519-522,598-601,603`
- Test: `frontend/src/pages/CorePages.test.tsx`

- [ ] **Step 1: 실패하는 테스트**

`CorePages.test.tsx`의 `요약 줄·막대·오른쪽 패널은 이번 달 자료로…` 테스트(110행, `render`로 `view`를 만든다) 마지막 줄(142행) 뒤에 추가한다.

```tsx
    expect(view.container.textContent).not.toContain('일정은 예정 정보입니다');
    expect(view.container.textContent).not.toContain('예정 근무와 휴게를 실제 시간으로 확정하지 않습니다');
    expect(view.getByRole('link', { name: '출퇴근 기록' }).getAttribute('href')).toBe('/me/attendance?date=2026-11-10');
```

- [ ] **Step 2: 실패 확인**

Run: `npm test -- src/pages/CorePages.test.tsx`
Expected: FAIL. 안내문이 보이고 '출퇴근 기록' 링크가 없다.

- [ ] **Step 3: 구현**

`CorePages.tsx`에서 `<p className="t-calendar-note">일정은 예정 정보입니다…</p>`(519-522행)와 `<p className="t-subtle">예정 근무와 휴게를 실제 시간으로 확정하지 않습니다…</p>`(598-601행) 요소를 지운다. 603행 링크 문구 `원천·정정 반영 내역`을 `출퇴근 기록`으로 바꾼다. `t-calendar-note` CSS는 Task 13에서 정리한다.

- [ ] **Step 4: 통과 확인**

Run: `npm test -- src/pages/CorePages.test.tsx`
Expected: PASS. 지운 두 안내문을 기대하는 기존 테스트는 조사 결과 없다.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/CorePages.tsx frontend/src/pages/CorePages.test.tsx
git commit -m "feat: remove review notes from the work schedule"
```

---

### Task 11: 내 근태현황·신청 정리

**Files:**
- Modify: `frontend/src/pages/AnnualRequestPage.tsx:33,203,297-298,312-362` 및 `AnnualRequestPage` 진입부
- Test: `frontend/src/pages/AnnualRequestPage.test.tsx`

- [ ] **Step 1: 실패하는 테스트**

`AnnualRequestPage.test.tsx`(기존 `envelope()` 헬퍼와 `App` 렌더링을 쓴다)를 아래처럼 바꾼다.

1. `연도별 조회를 먼저 제공하고 기본 화면에 즉시 제출 폼을 두지 않는다` 테스트의 마지막 줄 `expect(view.getByText(/조회 시점 오늘/)).toBeTruthy();`를 아래로 바꾼다.

```tsx
  const text=view.container.textContent??'';
  for(const hidden of['기준 시각','목록 필터와 무관한','선택 연도 · 오늘까지 유효한 승인','조회 시점 오늘','대기는 예약·차감하지 않음',
    '본인 대상의 원신청 기간·일수입니다','귀속연도 잔액은 현재 원장 잔액입니다','신청 건수와 휴가 DAY는',' DAY'])
    expect(text).not.toContain(hidden);
```

2. `빈 자료와 확인된 부분합을 연간 전체 0으로 단정하지 않는다` 테스트 본문을 아래로 바꾼다.

```tsx
  const view=render(<App initial={envelope({coverage:'NO_REGISTERED_DATA',requests:[],summaries:[]})}/>);
  const used=view.getByRole('heading',{name:'사용'}).closest('section')!;
  expect(used.querySelector('strong')?.textContent).toBe('-');
  expect(view.queryByText(/선택 연도의 등록 자료가 없습니다/)).toBeNull();
```

3. `목록 필터와 별개 연간 요약·본인 대상과 대리 작성 이력을 구분한다` 테스트에서 `expect(view.getByText(/목록 필터와 무관한 연도 전체/)).toBeTruthy();` 줄을 지운다.

4. 같은 `describe` 안에 새 테스트를 추가한다.

```tsx
 it('대리 작성 이력은 담당자이거나 작성 이력이 있을 때만 보인다',()=>{
  const employeeOnly=render(<App initial={envelope({authoredRequests:[]})}/>);
  expect(employeeOnly.queryByRole('heading',{name:'내가 대리 작성한 이력'})).toBeNull();
  cleanup();
  const base=envelope({authoredRequests:[]});
  const admin=render(<App initial={{...base,data:{...base.data,workspacePrincipal:{roles:['EMPLOYEE','ATTENDANCE_ADMIN']}}}}/>);
  expect(admin.getByRole('heading',{name:'내가 대리 작성한 이력'})).toBeTruthy();
 });
```

- [ ] **Step 2: 실패 확인**

Run: `npm test -- src/pages/AnnualRequestPage.test.tsx`
Expected: FAIL. '기준 시각'과 ' DAY'가 보인다.

- [ ] **Step 3: 구현**

`AnnualRequestPage.tsx`:

1. 33행 `` ` · 원신청 ${row.quantityDays} DAY` ``를 `` ` · ${row.quantityDays}일` ``로 바꾼다.
2. 203행 `… DAY</dd>`를 `…일</dd>`로 바꾼다.
3. 297-298행 미리보기의 `DAY` 세 곳을 `일`로 바꾼다(`예상 차감 {…}일`, `선택 묶음 사용 가능 {…}일`, `조회 시점 오늘 사용 가능 {…}일`).
4. `Annual`의 `metrics`(319-326행)와 그 렌더링(328-332행)을 아래로 바꾼다.

```tsx
  const partial = a.attributionPartial ? ' (일부)' : '';
  const days = (value: number | null | undefined) => (value == null ? (a.unknownBucketCount ? '귀속 미확인' : '-') : `${value}일${partial}`);
  const metrics: [string, ReactNode][] = [
    ['사용', missing ? '-' : `${a.usedDays}일`],
    ['사용 예정', missing ? '-' : `${a.plannedDays}일`],
    ['결재 대기', missing ? '-' : `${a.pendingDays}일`],
    ['현재 사용 가능', `${a.currentAvailableDays}일`],
    ['확인된 등록 기초·조정', days(a.registeredDays)],
    ['귀속연도 원장 잔액', days(a.attributedBalanceDays)],
  ];
```

```tsx
    <div className="t-annual-metrics">{[metrics[3],...metrics.slice(0,3)].map(([label,value])=><section className={`t-card${label==='현재 사용 가능'?' t-request-available':''}`} key={label}><h2>{label}</h2><strong>{value}</strong></section>)}</div>
    <dl className="t-request-balance-basis">{metrics.slice(4).map(([label,value])=><div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
```

5. 328행 `<p className="t-muted">기준 시각 …</p>`와 329행 `{missing&&<p className="notice">선택 연도의 등록 자료가 없습니다…</p>}`를 지운다.
6. 335·336·337·349행 섹션의 설명 `<p className="t-muted">…</p>`를 지운다.
7. 336행 대리 작성 섹션을 조건부로 감싼다. `Annual`(312행)에 `admin` prop을 더하고, `AnnualRequestPage`(368행)에서 `data.workspacePrincipal`의 역할로 넘긴다.

```tsx
function Annual({ model: m, submitting, admin }: { model: AnnualModel; submitting: boolean; admin: boolean }) {
```

```tsx
    {(admin || a.authoredRequests.length > 0) && <section className="wl-section"><h2>내가 대리 작성한 이력</h2><RequestTable rows={a.authoredRequests}/><Pages annual={a} authored/></section>}
```

```tsx
  return page==='request/mine'&&data.annual?<Annual model={data as unknown as AnnualModel} submitting={Boolean(busy)}
    admin={((data.workspacePrincipal as { roles?: string[] } | undefined)?.roles ?? []).includes('ATTENDANCE_ADMIN')}/>:null;
```

8. 343-344행 `{bucket.balanceDays} DAY`·`{bucket.availableDays} DAY`를 `일`로 바꾸고, 351행 머리글 `휴가 DAY`와 359행 `data-label="휴가 DAY"`를 `휴가(일)`로 바꾼다.

- [ ] **Step 4: 통과 확인**

Run: `npm test -- src/pages/AnnualRequestPage.test.tsx src/pages/AnnualRequestPage.responsive.test.tsx src/App.requestModal.test.tsx`
Expected: [부록 A](#부록-a-형식-변경으로-바뀌는-기존-기대값) Task 11의 `DAY` 기대값(`AnnualRequestPage.test.tsx` 204·230·231·281·386행, `App.requestModal.test.tsx` 125행)을 `일`로 고친 뒤 모두 PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/AnnualRequestPage.tsx frontend/src/pages/AnnualRequestPage.test.tsx frontend/src/App.requestModal.test.tsx
git commit -m "feat: trim captions and DAY units from my attendance overview"
```

---

### Task 12: 신청 상세·정정 화면·신청 모달 정리

**Files:**
- Modify: `frontend/src/pages/WorkflowPages.tsx:174-441` (`RequestDetailPage`, `Correction`)
- Modify: `frontend/src/pages/AnnualRequestPage.tsx:243-266` (정정 입력)
- Modify: `frontend/src/components/RequestBreakEditor.tsx:33-34`
- Test: `frontend/src/pages/WorkflowPages.test.tsx`, `frontend/src/pages/CorrectionWorkspace.test.tsx`, `frontend/src/components/RequestBreakEditor.test.tsx`

- [ ] **Step 1: 실패하는 테스트**

`CorrectionWorkspace.test.tsx`의 정정 화면 테스트 끝에 추가한다.

```tsx
  const text = view.container.textContent ?? '';
  for (const hidden of ['원천', '(서울)', 'Asia/Seoul', '변경할 현재 승인 정정 ID', '민감정보를 적지 마세요', '작성자·대상자와 다른 승인자가'])
    expect(text).not.toContain(hidden);
  expect(view.container.querySelector('input[type="hidden"][name="replacesRequestId"]')?.getAttribute('value')).toBe('original');
  expect(view.container.querySelector('input[name="requestedOutAt"]')?.getAttribute('step')).toBe('1');
  expect(view.getByLabelText('퇴근 시각')).toBeTruthy();
```

`WorkflowPages.test.tsx`의 신청 상세를 렌더링하는 테스트(부록 A Task 12 참고) 끝에 추가한다.

```tsx
    const text = view.container.textContent ?? '';
    for (const hidden of ['결재선 버전', '해석 시각', '제출 당시 근태정책', '신청 버전', '원천 기록은 보존하며', 'Asia/Seoul', ' DAY'])
      expect(text).not.toContain(hidden);
```

`RequestBreakEditor.test.tsx`에 추가한다.

```tsx
it('휴게 입력은 초 단위이고 라벨에 서울 표기를 붙이지 않는다', () => {
  const view = render(<RequestBreakEditor value="2026-11-04T12:00:00.000/2026-11-04T13:00" onChange={() => {}} />);
  const start = view.getByLabelText('휴게 1 시작') as HTMLInputElement;
  expect(start.getAttribute('step')).toBe('1');
  expect(start.value).toBe('2026-11-04T12:00:00');
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm test -- src/pages/CorrectionWorkspace.test.tsx src/components/RequestBreakEditor.test.tsx src/pages/WorkflowPages.test.tsx`
Expected: FAIL. '원천'·'(서울)'·'결재선 버전'이 보이고 step이 `0.001`이다.

- [ ] **Step 3: 신청 상세 구현**

`WorkflowPages.tsx`의 `RequestDetailPage`에서:

1. 196행 `help`를 `"신청 내용과 처리 이력을 확인하세요."`로 바꾼다.
2. 213행 `<dt>결재선 버전</dt>…</dd>`, 218행 `<dt>해석 시각 (서울)</dt>…</dd>`, 220행 `<p className="hint">제출 당시 해석은 보존됩니다…</p>`를 지운다.
3. 227행을 `<p className="t-request-detail-effect">현재 효력 <strong>{effectName}</strong></p>`로 바꾼다.
4. 232행 `{r.quantityDays} DAY`와 246행 `{day.quantityDays} DAY`를 `일`로 바꾼다.
5. 242행 조건을 `(d.days.length > 0 || d.correction)`로 바꾸고, 256행 `<Notice>원천 기록은 보존하며…</Notice>`와 258-269행 `{d.attendancePolicy && …}` 블록을 지운다.

- [ ] **Step 4: 정정 화면 구현**

`WorkflowPages.tsx`의 `Correction`에서:

1. 369행 `help`를 `"확인한 기록과 실제 근로 내용을 입력해 주세요."`로 바꾼다.
2. 375-386행 원천 기록 구역을 아래로 바꾼다.

```tsx
      <section className="wl-section t-correction-source" aria-label="현재 출퇴근 기록">
        <h2>현재 출퇴근 기록</h2>
        <ul className="t-correction-events">
          {m.day.events.map((e) => (
            <li key={e.id}>
              <span>{e.kind === 'IN' ? '출근' : e.kind === 'OUT' ? '퇴근' : e.kind}</span><strong>{m.eventTimes?.[e.id] ?? fmtDateTime(e.occurredAt)}</strong>
            </li>
          ))}
          {!m.day.events.length && <li>출퇴근 기록이 없습니다.</li>}
        </ul>
      </section>
```

3. hidden 묶음(395-401행) 끝에 `{hidden('replacesRequestId', f.replacesRequestId)}`를 더한다.
4. 404행, 415행, 419행, 432-434행의 `<p className="hint">…</p>`를 지운다.
5. 409·413행 시각 입력을 아래로 바꾼다. `secondsInput`을 `../ui`에서 import한다.

```tsx
            <Field label="출근 시각" name="requestedInAt" type="datetime-local" step="1" value={secondsInput(f.requestedInAt)} />
```

```tsx
            <Field label="퇴근 시각" name="requestedOutAt" type="datetime-local" step="1" value={secondsInput(f.requestedOutAt)} />
```

6. 429행 `<details className="t-correction-advanced">…</details>`를 지운다.
7. 437행 `<span className="wl-modal-note">작성자·대상자와 다른 승인자가 내용을 검토합니다.</span>`를 지운다.

- [ ] **Step 5: 신청 모달과 휴게 편집기 구현**

`AnnualRequestPage.tsx`:

1. 246행 목록 항목의 ` (서울)`을 지우고, 빈 목록 문구를 `<p>출퇴근 기록이 없습니다.</p>`로 바꾼다.
2. 248행 `<p>원천 기록을 보존하고 확인된 근거로 정정 심사를 요청합니다.</p>`를 지운다.
3. 264행 라벨을 `{label} 시각`으로, 265행 입력을 아래로 바꾼다. `secondsInput`을 `../ui`에서 import한다.

```tsx
                  <input id={`annual-${direction}-time`} className="t-input" type="datetime-local" step="1" name={timeField} value={secondsInput(correction[timeField])} onChange={event => change(timeField, event.target.value)} readOnly={correction[actionField] !== 'SET'} />
```

`RequestBreakEditor.tsx` 33-34행의 두 입력에서 `aria-label`의 ` (서울)`을 지우고 `step="0.001"`을 `step="1"`로, `value={start}`·`value={end}`를 `value={secondsInput(start)}`·`value={secondsInput(end)}`로 바꾼다. `secondsInput`을 `../ui`에서 import한다.

- [ ] **Step 6: 통과 확인**

Run: `npm test`
Expected: [부록 A](#부록-a-형식-변경으로-바뀌는-기존-기대값) Task 12 항목(`WorkflowPages.test.tsx` 267·327·330·357·389행, `CodePages.test.tsx` 191-197행, `CorrectionWorkspace.test.tsx` 15·16·24행, `RequestBreakEditor.test.tsx` 16·17·19·20·30행)을 고친 뒤 모두 PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/pages/WorkflowPages.tsx frontend/src/pages/AnnualRequestPage.tsx frontend/src/components/RequestBreakEditor.tsx frontend/src/pages/CorrectionWorkspace.test.tsx frontend/src/components/RequestBreakEditor.test.tsx frontend/src/pages/WorkflowPages.test.tsx frontend/src/pages/CodePages.test.tsx
git commit -m "feat: remove version and source notes from request detail and correction"
```

---

### Task 13: 지운 마크업이 쓰던 CSS 정리

**Files:**
- Modify: `frontend/src/design/workspace.css:209-217`
- Modify: `frontend/src/pages/PersonalDetails.css:4,128,134,139,143,148,154,164-182,244-245`
- Modify: `frontend/src/WorkspacePolish.css:95`
- Modify: `frontend/src/design/request.css:212-214`
- Modify: `frontend/src/pages/RequestWorkspace.css:182-184,273-274`

이 변경으로 마크업이 사라진 클래스는 `t-profile-details`, `t-profile-asof`, `t-profile-footnote`, `t-personal-privacy`, `t-calendar-note`, `t-correction-advanced`다. 서버 레거시 스타일시트 `src/main/resources/static/app.css`는 이번에 고치지 않는다.

- [ ] **Step 1: 단독 규칙 삭제**

- `workspace.css`: `.t-personal-privacy`(211-212행), `.t-profile-details`·`.t-profile-asof`·`.t-profile-footnote`(215-217행) 규칙을 지운다.
- `PersonalDetails.css`: `#tna-app .t-profile-details .t-person-info`(134행 규칙), `#tna-app .t-personal-privacy`·`… svg`·`… strong`(164-182행), `#tna-app .t-profile-asof`·`#tna-app .t-profile-footnote`(244-245행) 규칙을 지운다.
- `request.css`: `.t-correction-advanced` 세 규칙(212-214행)을 지운다.
- `RequestWorkspace.css`: `#tna-app .t-correction-advanced` 규칙들(182-184행, 273-274행)을 지운다.

- [ ] **Step 2: 묶인 선택자에서 한 항목만 빼기**

- `workspace.css` 209행: `.t-personal-group .wl-info-grid, .t-profile-details .wl-info-grid {` → `.t-personal-group .wl-info-grid {`
- `PersonalDetails.css` 4·128·139·143·148·154행: `:is(.t-private-personnel, .t-profile-details)` → `.t-private-personnel`
- `WorkspacePolish.css` 95행: `:is(` 목록에서 `.t-calendar-note, `만 지운다.

- [ ] **Step 3: 남은 참조 없음 확인**

Run: `git grep -n "t-profile-details\|t-profile-asof\|t-profile-footnote\|t-personal-privacy\|t-correction-advanced" -- frontend/src` 와 `git grep -n "t-calendar-note" -- frontend/src`
Expected: 출력 없음.

- [ ] **Step 4: 스타일 계약·빌드 확인**

Run: `npm test -- src/cssLayers.test.js src/design/tokens.test.js`, 이어서 `npm run build`
Expected: PASS, 빌드 성공.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/design/workspace.css frontend/src/pages/PersonalDetails.css frontend/src/WorkspacePolish.css frontend/src/design/request.css frontend/src/pages/RequestWorkspace.css
git commit -m "style: delete rules for profile, schedule and correction markup that no longer exists"
```

---

### Task 14: 문서 갱신

**Files:**
- Modify: `docs/design/worktime-results.md:22`
- Modify: `docs/design/screens.md:228`
- Modify: `README.md:50`

- [ ] **Step 1: 근로시간 결과 문서**

`docs/design/worktime-results.md` 22행의 "계산기는 별도 수동 재집계로 실행한다"를 아래로 바꾼다.

```markdown
직원이 근무 확인을 저장하면 같은 요청에서 그날 하루를 계산한다(감사 행위 `WORKTIME_AUTO_CALCULATE`, 하루짜리 계산 실행). 계산이 실패해도 확인은 유지하고 그날은 확인 전으로 남는다. 정정·휴가 승인 등 다른 사실 변경은 담당자 수동 재집계로 계산한다.
```

같은 줄의 나머지 문장(계산·다운로드 실패 표시 등)은 유지한다. 조회 GET이 재집계하지 않는다는 14행은 그대로 둔다.

- [ ] **Step 2: 화면 문서**

`docs/design/screens.md` 228행의 "Instant는 서울 `uuuu-MM-dd HH:mm:ss.SSS`"를 "Instant는 서울 `uuuu-MM-dd HH:mm:ss`(화면은 초까지만 표시, 저장·계산은 밀리초 유지)"로 바꾼다.

- [ ] **Step 3: README**

`README.md` 50행을 아래로 바꾼다.

```markdown
- CALC-1 시간 계산: 일·주·월 결과, 근무 확인 시 그날 자동 계산, 확인 전 일수 표시와 담당자 수동 재집계 연결
```

- [ ] **Step 4: 링크·일관성 확인**

Run: `git diff --stat` 와 `git diff docs README.md`
Expected: 세 파일만 바뀌고, 다른 문서의 링크를 깨지 않는다.

- [ ] **Step 5: Commit**

```bash
git add docs/design/worktime-results.md docs/design/screens.md README.md
git commit -m "docs: record confirm-time calculation and second-precision display"
```

---

### Task 15: 전체 검증

**Files:** 없음 (실패하면 해당 Task로 돌아가 고친다)

- [ ] **Step 1: 프런트 전체**

Run: `npm test`, 이어서 `npm run build`
Expected: 모두 PASS, 빌드 성공.

- [ ] **Step 2: 백엔드 전체 (백그라운드)**

Run: `.\mvnw.cmd -B verify`
Expected: `BUILD SUCCESS`, `target/surefire-reports`·`target/failsafe-reports`에 실패·오류 0.

- [ ] **Step 3: 남은 밀리초 표시 검색**

Run: `git grep -n "SSS\|밀리초\|step=\"0.001\"" -- frontend/src src/main/java ":!*.test.*"`
Expected: 검토용 Excel(`WorktimeService` 수출 형식·`humanDuration`)과 입력 검증(`ScheduleService` 입력 형식 안내)만 남는다.

- [ ] **Step 4: 브라우저 확인 (사용자 확인 후)**

로컬 앱 실행·근무 확인 저장은 로컬 DB에 쓰기 때문에 시작 전에 사용자에게 확인받는다. 승인되면 이 브랜치의 앱과 프런트를 띄워 6개 화면을 PC 폭에서 확인하고 스크린샷을 남긴다. 출근·퇴근이 있는 지난 날에서 근무 확인 → '근무 확인 완료 · 실근로 …' → 근로시간 화면의 값과 확인 전 일수 감소를 확인한다.

- [ ] **Step 5: 최종 상태**

Run: `git status --short`, `git log --oneline main..HEAD`
Expected: 작업 트리 깨끗함, Task별 커밋 목록.

---

## 부록 A: 형식 변경으로 바뀌는 기존 기대값

2026-11-11에 읽기 전용으로 모든 프런트 테스트와 관련 백엔드 테스트를 조사한 결과다. 행 번호는 작업 시작 시점 기준이며, 앞 Task에서 줄이 밀렸으면 같은 문자열로 찾는다. 표의 Task에서 고친다.

| Task | 위치 | 현재 기대값 | 새 기대값 |
| --- | --- | --- | --- |
| 1 | `frontend/src/ui.test.tsx:29,32,33` | `09:00:30.125`, `미확정`, `1:00:00.000` | Task 1 Step 1에서 테스트 전체를 바꾼다 |
| 1 | `frontend/src/pages/WorkflowPages.test.tsx:291` | `'2026-11-01 09:00:00.123'` | `'2026-11-01 09:00:00'` |
| 1 | `frontend/src/pages/WorkflowPages.test.tsx:314` | `'2026-11-01 18:00:00.123'` | `'2026-11-01 18:00:00'` |
| 1 | `frontend/src/pages/WorkflowPages.test.tsx:412` | `'12:00:00.123'` | `'2026-10-26 12:00:00 ~ 2026-10-26 13:00:00'` (실제 렌더링 문자열로 확인) |
| 1 | `frontend/src/pages/WorkflowPages.test.tsx:549` | `'2026-10-26 09:00:00.123'` | `'2026-10-26 09:00:00'` |
| 1 | `frontend/src/pages/WorkflowPages.test.tsx:552` | `'2026-10-26 09:01:00.456'` | `'2026-10-26 09:01:00'` |
| 1 | `frontend/src/pages/WorkflowPages.test.tsx:622` | `'2026-10-26 09:00:00.123'` | `'2026-10-26 09:00:00'` |
| 1 | `frontend/src/pages/WorkflowPages.test.tsx:364` (eventTimes 픽스처) | `'… .123'` | 초까지의 같은 시각 (통과하지만 서버 형식과 맞춘다) |
| 1 | `frontend/src/pages/IdentityPages.test.tsx:67` | `'2026-10-27 00:00:00.000'` | `'2026-10-27 00:00:00'` (이 테스트는 Task 7에서 지운다) |
| 1 | `frontend/src/pages/IdentityPages.test.tsx:137` | `'8:00:00.000'` | `'8:00:00'` |
| 1 | `frontend/src/pages/IdentityPages.test.tsx:185` | `/미확정/g` 3개 | 1개, 그리고 `[col-id="scheduledMillis"]`·`[col-id="actualWorkMillis"]` 셀이 `'-'` |
| 1 | `frontend/src/pages/IdentityPages.test.tsx:186` | `not.toContain('0:00:00.000')` | `not.toContain('0:00:00')` |
| 1 | `frontend/src/pages/IdentityPages.test.tsx:200,201` | `'10:00:00.000'`, `'8:00:00.000'` | `'10:00:00'`, `'8:00:00'` |
| 1 | `frontend/src/pages/IdentityPages.test.tsx:218` | `'2026-10-26 00:00:00.000'` | `'2026-10-26 00:00:00'` |
| 2 | `src/test/java/kr/tna/SeoulTimeDisplayTest.java` | 밀리초 기대값 | Task 2 Step 1에서 바꾼다 |
| 2 | `src/test/java/kr/tna/WorkspaceWebIT.java:149-150` | eventTimes `"09:17:00.000"`, `"18:41:00.000"` | `"09:17:00"`, `"18:41:00"` (Task 2 Step 5에서 `WorkspaceWebIT`만 실행) |
| 7 | `frontend/src/pages/IdentityPages.test.tsx:44-45, 56-86` | 주민등록번호 안내·계정과 업무 배정 | Task 7 Step 1에서 바꾼다 |
| 7 | `frontend/src/pages/ApprovalRoutePages.test.tsx:275-280` (내 정보의 `적용 결재 경로`·`신청 전 확인` 테스트) | `'조직별 결재선 · 팀 결재'`, `'조직장 공석으로 재지정이 필요합니다.'` | 테스트를 지운다. 내 정보에서 결재 경로를 보이지 않는다 |
| 8 | `frontend/src/pages/WorkflowPages.test.tsx:626-645` | `'원천 출퇴근 기록'` 그리드·확인 폼 | Task 8 Step 1에서 바꾼다 |
| 9 | `frontend/src/pages/WorkflowPages.test.tsx:458-505, 555-572` | `totalTime`·`실근로 ms`·`계산 근거 상세` 링크 | Task 9 Step 1에서 바꾼다 |
| 11 | `frontend/src/pages/AnnualRequestPage.test.tsx:204` | `/9 DAY/` | `/9일/` |
| 11 | `frontend/src/pages/AnnualRequestPage.test.tsx:230,231` | `'7 DAY'`, `'9 DAY'` 부재 | `'7일'`, `'9일'` 부재 |
| 11 | `frontend/src/pages/AnnualRequestPage.test.tsx:281` | `'3 DAY'` | `'3일'` |
| 11 | `frontend/src/pages/AnnualRequestPage.test.tsx:356-370` | `조회 시점 오늘`, `선택 연도의 등록 자료가 없습니다`, `목록 필터와 무관한 연도 전체` | Task 11 Step 1에서 바꾼다 |
| 11 | `frontend/src/pages/AnnualRequestPage.test.tsx:386` | `/예상 차감 2 DAY/` | `/예상 차감 2일/` |
| 11 | `frontend/src/App.requestModal.test.tsx:125` | `'4 DAY'` | `'4일'` |
| 11 | `frontend/src/pages/AnnualRequestPage.responsive.test.tsx:32` | 열 머리글과 셀 `data-label` 일치 | 머리글·`data-label`을 함께 `휴가(일)`로 바꾸면 그대로 통과 |
| 12 | `frontend/src/pages/WorkflowPages.test.tsx:267` | `'2 DAY'` | `'2일'` |
| 12 | `frontend/src/pages/WorkflowPages.test.tsx:327` | 제목 `'제출 당시 근태정책'` 존재 | `queryByRole('heading', { name: '제출 당시 근태정책' })`가 `null` |
| 12 | `frontend/src/pages/WorkflowPages.test.tsx:330` | `'ANNUAL · 연차 정책 · v2'`, `'승인 시 연차 DAY 차감'`, `'정책 근거'`, `'TEAM · 조직장 결재 · v3'` | 목록에서 지운다 |
| 12 | `frontend/src/pages/WorkflowPages.test.tsx:357` | `'FUTURE_UNIT'`, `'FUTURE_PAY'`, `'FUTURE_DEDUCTION'` | 목록에서 지운다 |
| 12 | `frontend/src/pages/WorkflowPages.test.tsx:389` | `getByLabelText('제안 출근 시각 (서울)')` | `getByLabelText('출근 시각')` |
| 12 | `frontend/src/pages/CodePages.test.tsx:191-197` | 신청 상세의 `제출 당시 근태정책`·정책 값 | 제목이 없다는 기대 하나로 바꾸고 나머지 세 기대를 지운다 |
| 12 | `frontend/src/pages/CorrectionWorkspace.test.tsx:15` | `getByRole('region',{name:'현재 원천 기록'})` | `getByRole('region',{name:'현재 출퇴근 기록'})` |
| 12 | `frontend/src/pages/CorrectionWorkspace.test.tsx:16` | `'휴게 1 종료 (서울)'` | `'휴게 1 종료'` |
| 12 | `frontend/src/pages/CorrectionWorkspace.test.tsx:22` | `replacesRequestId`가 `'original'` | 그대로 (hidden으로 계속 보낸다) |
| 12 | `frontend/src/pages/CorrectionWorkspace.test.tsx:24` | `'기존 승인 정정을 다시 변경하는 경우'` | 기대를 지운다 |
| 12 | `frontend/src/components/RequestBreakEditor.test.tsx:16,17,19,20,30` | `'휴게 N 시작/종료 (서울)'` | ` (서울)`을 뺀 라벨 |

변하지 않는 것으로 확인한 테스트: `SeoulTimeDisplayWebIT`·`AuditTrailWebIT`(ISO 원문만 확인), `MvpJourneyIT`·`WorktimeServiceIT`의 XLSX 검사·`ReviewWorkbookTest`(Excel 형식 유지), `DayFlowTimelineTest`·고정 일정 테스트(저장 정밀도), `WorktimeWebIT`·`WorktimeResultDetailIT`·`AttendanceWebIT`·`ReactPageViewTest`(형식 문자열 미검사). `ProfileCompanyWebIT:96-98`은 내 정보 응답 키를 그대로 두므로 통과한다.

참고(이번 범위 밖, 수정하지 않음): `src/main/resources/templates/worktime/month.html` 등 Thymeleaf 템플릿은 `ReactPageViewResolver`가 먼저 처리해 쓰이지 않는다.

## 실행 중 바뀐 점

- Task 2(`1b6ee24`): 서버 `SeoulTimeDisplay.time`은 계획 본문의 `HH:mm:ss`가 아니라 설계대로 `HH:mm`(초나 초 미만이 있으면 `HH:mm:ss`)를 쓴다. 계산 근거 상세 안내는 "'-'는 미확정이며…"로 바꿨다.
- Task 3(`721039d`): 확인 전 규칙의 경계 단언 3개(빈 현재 결과, 잘못된 입력, 미검토 휴무일)를 더했다.
- Task 4(`047a003`): 자동 계산 범위를 그날 하루가 아니라 확인한 날이 속한 주로 넓혔다. 주 40시간 초과 값이 나오려면 7일이 모두 현재 결과여야 하기 때문이며, 테스트도 함께 고쳤다.
- Task 5(`c7d3fda`): 계획의 `map(...).findFirst()`는 실근로가 null인 현재 결과에서 NullPointerException을 냈다. `findFirst().map(...)`로 바꾸고 확인한 날에만 읽는다. Step 5의 IT는 근무 확인을 보내지 않으며 확인부터 실근로 표시까지의 흐름은 브라우저에서 확인한다.
- Task 6(`21bfbee`): 기존 `targetRead`를 재사용하고 담당자 조회·거부 조회 테스트를 더했다.
- Task 7(`73bcc99`): 신원 연결 테스트를 더하고 `Profile` 타입을 줄였다.
- Task 8(`bb962dd`, `6b501bd`): 확인 폼 조건에 출근·퇴근 순서와 같은 서울 날짜를 더하고, 캘린더 상태를 한글(휴일·비근무일·캘린더 미확인)로 보이며, 휴게는 항상 보인다.
- Task 9(`b4953db`): 후속 테스트 커밋으로 정렬 테스트가 실제 순서 변화를 검사하게 했다.
- Task 11(`d14ffc1`): `(일부)`와 `-` 표시 테스트를 더했다.
- Task 14: 계획의 세 파일 외에 계산 규칙·인사정보·로컬 개발·이 설계와 계획 문서도 실제 동작에 맞췄고, README는 '그 주 자동 계산'으로 적었다.

사용자 결정(2026-11-16):

- 입사 전·퇴사 후 날짜는 확인 전으로 세지 않고 근로시간 표에서 빈칸으로 둔다.
- 결재 경로를 신청 모달에서만 보이는 것과 "승인 시 이미 차감", "등록 자료 기준" 보조 문구 삭제는 그대로 둔다.
