# 재직자 일괄 등록·사용자 관리 구현 계획 (개통 준비 계획 2/3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 담당자가 기존 재직자를 엑셀에서 붙여넣어 한 번에 등록한다(최대 500명). 등록 시 인사정보 생년월일·직급·직책, 오늘자 소속 배정, 최초 접속 대기 계정이 함께 만들어진다. 사용자 관리 화면에서는 사원별 계정 상태를 보고, 여러 명을 골라 계정 생성·비밀번호 초기화를 한 번에 한다.

**Architecture:**
- 붙여넣은 셀은 문자열 그대로 서버로 보낸다. 서버의 순수 검증기(`EmployeeImportRows`)가 행별 오류를 만들고, 서비스가 DB 조건(사번 중복, 조직 코드, 마감 월)을 더한다. 미리보기와 저장은 같은 검증을 쓴다.
- 저장은 `OrgCommands.execute` 한 번(감사 1건, action `EMPLOYEE_IMPORT`)이다. 500행이라 행별 감사를 남기는 `executeBatch`(50행 제한)는 쓰지 않는다. 결과는 감사 `after_state`에서 다시 읽어 재시도에도 같은 응답을 준다.
- 사용자 관리 목록의 상태는 SQL `CASE`로 계산해 필터·페이지가 맞게 한다. 계산 순서는 `AccountStatus.of`와 같고 통합 테스트로 둘을 대조한다.
- 일괄 계정 동작은 계획 1의 `AccountProvisioning.provision`/`reopen`을 그대로 쓰고 명령 한 번으로 묶는다. 한 명이라도 대상이 아니면 아무것도 바꾸지 않는다.

**Tech Stack:** Java 25, Spring Boot 4.1 / Spring Security, MyBatis 애너테이션 매퍼, PostgreSQL 18(RLS), Jackson 3(`tools.jackson`), React 19 + AG Grid(`WorklogSheet`) + Vitest, JUnit 5 통합 테스트(`DatabaseFixture`)

**설계 문서:** [2026-11-23-opening-prep-design.md](../specs/2026-11-23-opening-prep-design.md) §1.1, §1.3, §2.2, §3, §4 · **선행:** [계획 1](2026-11-24-account-provisioning.md)(커밋 `c1bbf9f..15d3610`)

**이 계획의 범위 밖:** 공휴일 달력(계획 3, `V41`), 일괄 등록에서 근무조·연차·권한·직무 처리, 파일 업로드.

**새 마이그레이션 없음.** 필요한 열과 권한은 모두 기존 스키마와 V40에 있다(`employee`, `employee_assignment`, `employee_personnel` INSERT는 `tna_app`에 이미 허용).

---

## 실행 환경 메모 (이 PC)

- Maven은 JDK 25가 필요하다. 시스템 `JAVA_HOME`은 JDK 8이다. 테스트 명령마다 `C:\Users\ihpark\.jdks\jdk-25.0.5.1+2`를 지정한다.
- 프로젝트 테스트 DB 이미지가 빌드되지 않는 동안에는 전용 컨테이너(`127.0.0.1:25432`, DB `tna_phase4_test`)를 `TNA_TEST_JDBC_URL`·`TNA_TEST_DB_USER`·`TNA_TEST_DB_PASSWORD`로 연결한다. 결과 문서 [account-provisioning-20261124](../../quality/account-provisioning-20261124.md)와 같다. 이미지가 고쳐지면 환경변수 없이 실행한다.
- 아래 명령의 `.\mvnw.cmd`는 위 설정을 마친 PowerShell에서 실행한다.

## 파일 구조

| 파일 | 책임 |
| --- | --- |
| Create `src/main/java/kr/tna/org/EmployeeImportRows.java` | 셀 문자열 → 값 변환과 행 단위 검증(순수) |
| Create `src/main/java/kr/tna/org/EmployeeImportMapper.java` | 활성 조직 목록, 명령 결과(`after_state`) 조회 |
| Create `src/main/java/kr/tna/org/EmployeeImportService.java` | 화면 모델, 미리보기, 저장(사원·인사정보·소속·계정) |
| Create `src/main/java/kr/tna/org/EmployeeImportController.java` | `GET /admin/employees/import`, `GET …/template` |
| Create `src/main/java/kr/tna/org/EmployeeImportApi.java` | `POST …/preview`, `POST …/save`(JSON) |
| Create `src/main/java/kr/tna/org/AccountDirectoryMapper.java` | 사용자 관리 목록(상태 `CASE`, 검색, 페이지) |
| Create `src/main/java/kr/tna/org/AccountDirectoryService.java` | 목록 모델과 일괄 계정 생성·초기화 |
| Create `src/main/java/kr/tna/org/AccountDirectoryController.java` | `GET /admin/accounts` |
| Create `src/main/java/kr/tna/org/AccountBulkApi.java` | `POST /admin/accounts/create`, `/reset`(JSON) |
| Modify `src/main/java/kr/tna/org/AccountService.java:124` | `requireEmployee`를 패키지 공개로(일괄 동작이 재사용) |
| Modify `src/main/java/kr/tna/common/config/SecurityConfiguration.java` | `/admin/accounts/**` 담당자 권한, 공개 데모 저장 차단 |
| Modify `src/main/java/kr/tna/common/web/ReactPageViewResolver.java` | `org/employee-import`, `org/accounts` 화면 등록 |
| Create `frontend/src/pages/clipboardTable.ts` | 엑셀 붙여넣기(TSV·따옴표 셀) 해석 |
| Create `frontend/src/pages/EmployeeImportPage.tsx` | 재직자 일괄 등록 화면 |
| Create `frontend/src/pages/AccountsPage.tsx` | 사용자 관리 화면 |
| Modify `frontend/src/navigation.ts`, `frontend/src/App.tsx`, `frontend/src/pages/IdentityPages.tsx` | 메뉴·제목·화면 연결 |
| Tests | `src/test/java/kr/tna/org/EmployeeImportRowsTest.java`, `src/test/java/kr/tna/EmployeeImportWebIT.java`, `src/test/java/kr/tna/AccountDirectoryWebIT.java`, `frontend/src/pages/clipboardTable.test.ts`, `frontend/src/pages/EmployeeImportPage.test.tsx`, `frontend/src/pages/AccountsPage.test.tsx` |

---

### Task 1: 붙여넣은 행의 값 변환과 검증

**Files:**
- Create: `src/main/java/kr/tna/org/EmployeeImportRows.java`
- Test: `src/test/java/kr/tna/org/EmployeeImportRowsTest.java`

- [ ] **Step 1: 실패하는 단위 테스트 작성**

```java
package kr.tna.org;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EmployeeImportRowsTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    private static EmployeeImportRows.Row row(String no, String name, String joined, String birth, String org, String grade, String position) {
        return new EmployeeImportRows.Row("r1", no, name, joined, birth, org, grade, position);
    }

    private static String error(EmployeeImportRows.Parsed parsed, String field) {
        return parsed.errors().stream().filter(e -> e.field().equals(field)).map(EmployeeImportRows.FieldError::message)
                .findFirst().orElse(null);
    }

    @Test void acceptsFourDateFormatsAndTrimsCells() {
        for (String joined : new String[] {"2019-03-04", "2019.03.04", "2019/03/04", "20190304", " 2019-03-04 "}) {
            var parsed = EmployeeImportRows.check(row(" 19001 ", " 홍길동 ", joined, "1990-05-12", " D10 ", "", " 팀장 "), TODAY);
            assertEquals(java.util.List.of(), parsed.errors(), joined);
            assertEquals(LocalDate.of(2019, 3, 4), parsed.joinedOn());
            assertEquals("19001", parsed.employeeNo());
            assertEquals("홍길동", parsed.displayName());
            assertEquals("D10", parsed.organizationCode());
            assertNull(parsed.gradeName());
            assertEquals("팀장", parsed.positionName());
        }
    }

    @Test void reportsEachInvalidCellOnItsOwnField() {
        var parsed = EmployeeImportRows.check(row("a@b", "", "45231", "2019-02-30", "", "x".repeat(121), ""), TODAY);
        assertEquals("사번은 1~40자이며 @를 쓸 수 없습니다.", error(parsed, "employeeNo"));
        assertNotNull(error(parsed, "displayName"));
        assertEquals("날짜 형식으로 표시한 뒤 복사하세요.", error(parsed, "joinedOn"));
        assertEquals("올바른 날짜가 아닙니다.", error(parsed, "birthDate"));
        assertEquals("조직코드를 입력해 주세요.", error(parsed, "organizationCode"));
        assertNotNull(error(parsed, "gradeName"));
    }

    @Test void joiningDateMustNotBeInTheFutureAndBirthDateMustPrecedeIt() {
        assertEquals("입사일이 오늘 이후면 입사 예정 관리에서 등록해 주세요.",
                error(EmployeeImportRows.check(row("", "가", "2026-11-25", "1990-01-01", "D10", "", ""), TODAY), "joinedOn"));
        assertEquals("생년월일은 입사일 이전이어야 합니다.",
                error(EmployeeImportRows.check(row("", "가", "2019-03-04", "2019-03-04", "D10", "", ""), TODAY), "birthDate"));
        assertEquals("생년월일은 1900-01-01 이후여야 합니다.",
                error(EmployeeImportRows.check(row("", "가", "2019-03-04", "1899-12-31", "D10", "", ""), TODAY), "birthDate"));
        assertNull(EmployeeImportRows.check(row("", "가", "2026-11-24", "1990-01-01", "D10", "", ""), TODAY).employeeNo());
    }

    @Test void rowCountIsOneToFiveHundred() {
        assertThrows(OrgException.class, () -> EmployeeImportRows.checkCount(0));
        assertThrows(OrgException.class, () -> EmployeeImportRows.checkCount(501));
        assertDoesNotThrow(() -> EmployeeImportRows.checkCount(500));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dtest=EmployeeImportRowsTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL(컴파일 오류, `EmployeeImportRows` 없음)

- [ ] **Step 3: 구현**

```java
package kr.tna.org;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 엑셀에서 붙여넣은 재직자 행을 값으로 바꾸고 행 안의 규칙만 검사한다. DB 조건은 서비스가 더한다. */
public final class EmployeeImportRows {
    public static final int MAX_ROWS = 500;
    private static final LocalDate EARLIEST_BIRTH = LocalDate.of(1900, 1, 1);
    private static final Pattern SERIAL = Pattern.compile("\\d{5}");
    private static final Pattern DATE = Pattern.compile("(\\d{4})([-./]?)(\\d{2})\\2(\\d{2})");

    public record Row(String clientRowId, String employeeNo, String displayName, String joinedOn,
                      String birthDate, String organizationCode, String gradeName, String positionName) { }
    public record FieldError(String field, String message) { }
    public record Parsed(String clientRowId, String employeeNo, String displayName, LocalDate joinedOn,
                         LocalDate birthDate, String organizationCode, String gradeName, String positionName,
                         List<FieldError> errors) { }

    private EmployeeImportRows() { }

    public static void checkCount(int rows) {
        if (rows < 1 || rows > MAX_ROWS) throw OrgException.invalid("1~500행을 붙여넣어 주세요.");
    }

    public static Parsed check(Row row, LocalDate today) {
        var errors = new ArrayList<FieldError>();
        String number = blankToNull(row.employeeNo());
        if (number != null && (number.length() > 40 || number.contains("@") || hasControl(number))) {
            errors.add(new FieldError("employeeNo", "사번은 1~40자이며 @를 쓸 수 없습니다."));
        }
        String name = text(row.displayName(), 120, "이름", "displayName", errors, "이름을 입력해 주세요.");
        LocalDate joined = date(row.joinedOn(), "입사일", "joinedOn", errors);
        if (joined != null && joined.isAfter(today)) {
            errors.add(new FieldError("joinedOn", "입사일이 오늘 이후면 입사 예정 관리에서 등록해 주세요."));
        }
        LocalDate birth = date(row.birthDate(), "생년월일", "birthDate", errors);
        if (birth != null && birth.isBefore(EARLIEST_BIRTH)) {
            errors.add(new FieldError("birthDate", "생년월일은 1900-01-01 이후여야 합니다."));
        } else if (birth != null && joined != null && !birth.isBefore(joined)) {
            errors.add(new FieldError("birthDate", "생년월일은 입사일 이전이어야 합니다."));
        }
        String organization = text(row.organizationCode(), 40, "조직코드", "organizationCode", errors, "조직코드를 입력해 주세요.");
        String grade = text(row.gradeName(), 120, "직급", "gradeName", errors, null);
        String position = text(row.positionName(), 120, "직책", "positionName", errors, null);
        return new Parsed(row.clientRowId(), number, name, joined, birth, organization, grade, position, List.copyOf(errors));
    }

    /** requiredMessage가 null이면 선택 항목이다. */
    private static String text(String raw, int maximum, String label, String field, List<FieldError> errors, String requiredMessage) {
        String value = blankToNull(raw);
        if (value == null) {
            if (requiredMessage != null) errors.add(new FieldError(field, requiredMessage));
            return null;
        }
        try {
            return OrgCommands.requiredText(value, maximum, label);
        } catch (OrgException error) {
            errors.add(new FieldError(field, error.getMessage()));
            return null;
        }
    }

    private static LocalDate date(String raw, String label, String field, List<FieldError> errors) {
        String value = blankToNull(raw);
        if (value == null) {
            errors.add(new FieldError(field, label + "을 입력해 주세요."));
            return null;
        }
        if (SERIAL.matcher(value).matches()) {
            errors.add(new FieldError(field, "날짜 형식으로 표시한 뒤 복사하세요."));
            return null;
        }
        var match = DATE.matcher(value);
        if (!match.matches()) {
            errors.add(new FieldError(field, "YYYY-MM-DD 형식으로 입력해 주세요."));
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4)));
        } catch (DateTimeException error) {
            errors.add(new FieldError(field, "올바른 날짜가 아닙니다."));
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: 같은 명령. Expected: PASS(4개)

- [ ] **Step 5: 커밋**

```powershell
git add src/main/java/kr/tna/org/EmployeeImportRows.java src/test/java/kr/tna/org/EmployeeImportRowsTest.java
git commit -m "feat: 재직자 일괄 등록 행의 날짜 형식과 셀 규칙 검사"
```

---

### Task 2: 미리보기 — DB 조건과 사번 발급

**Files:**
- Create: `src/main/java/kr/tna/org/EmployeeImportMapper.java`
- Create: `src/main/java/kr/tna/org/EmployeeImportService.java`(미리보기와 화면 모델까지)
- Test: `src/test/java/kr/tna/EmployeeImportWebIT.java`(서비스 직접 호출 테스트부터)

- [ ] **Step 1: 실패하는 통합 테스트 작성**

테스트 클래스 뼈대는 `OrgBulkWebIT`의 방식 B를 따른다(새 고객사, 합성 담당자 `TnaPrincipal`, `organization(code, parent)` 헬퍼). 해당 헬퍼 코드(`OrgBulkWebIT.java:650-720`의 `authenticated`, `organization`, `member`)를 이 클래스에 같은 이름으로 옮겨 쓴다.

```java
package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.org.EmployeeImportRows.Row;
import kr.tna.org.EmployeeImportService;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class EmployeeImportWebIT {
    @Autowired MockMvc mvc;
    @Autowired EmployeeImportService imports;
    JdbcTemplate db;
    UUID tenant;
    kr.tna.common.auth.TnaPrincipal manager;
    UUID team;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f = DatabaseFixture.start();
        p.add("spring.datasource.url", f::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", f::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void seed() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 일괄 등록')",
                tenant, "IMPORT-" + tenant);
        manager = member("ADMIN", java.util.Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        team = organization("D10", null);
    }

    static Row row(String id, String no, String name, String joined, String birth, String org) {
        return new Row(id, no, name, joined, birth, org, "사원", "");
    }

    @Test void previewIssuesNumbersAcrossTheBatchAndFlagsDatabaseConflicts() {
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'19001','기존',DATE '2019-01-01')",
                tenant, UUID.randomUUID());
        var preview = imports.preview(manager, List.of(
                row("a", "", "가", "2019-03-04", "1990-01-01", "D10"),
                row("b", "", "나", "2019-05-01", "1991-01-01", "D10"),
                row("c", "19001", "다", "2019-06-01", "1992-01-01", "D10"),
                row("d", "77", "라", "2019-06-01", "1992-01-01", "D10"),
                row("e", "77", "마", "2019-06-01", "1992-01-01", "D10"),
                row("f", "", "바", "2019-06-01", "1992-01-01", "NOPE")));
        assertFalse(preview.valid());
        assertEquals("19002", preview.rows().get(0).employeeNo());
        assertEquals("19003", preview.rows().get(1).employeeNo());
        assertEquals("이미 등록된 사번입니다.", preview.rows().get(2).errors().getFirst().message());
        assertEquals("붙여넣은 행 안에서 사번이 겹칩니다.", preview.rows().get(3).errors().getFirst().message());
        assertEquals("활성 조직 코드가 아닙니다.", preview.rows().get(5).errors().getFirst().message());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.employee WHERE tenant_id=?", Integer.class, tenant) - 1);
    }

    @Test void previewRejectsEmptyAndOversizedBatches() {
        assertEquals(422, assertThrows(OrgException.class, () -> imports.preview(manager, List.of())).status());
    }
}
```

`member(...)`가 만드는 담당자 사원은 사번 `ADMIN`이므로 `19xxx` 발급에 영향을 주지 않는다. 마지막 단언의 `- 1`은 그 담당자 사원을 뺀 것이다.

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=EmployeeImportWebIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: FAIL(컴파일 오류, `EmployeeImportService` 없음)

- [ ] **Step 3: 매퍼 작성**

```java
package kr.tna.org;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EmployeeImportMapper {
    record Organization(UUID id, String code, String name) { }

    @Select("SELECT id, code, name FROM tna.organization WHERE tenant_id=#{tenantId} AND active ORDER BY code, id")
    List<Organization> activeOrganizations(@Param("tenantId") UUID tenantId);

    @Select("SELECT after_state::text FROM tna.org_audit_event WHERE tenant_id=#{tenantId} AND command_id=#{commandId}")
    String result(@Param("tenantId") UUID tenantId, @Param("commandId") UUID commandId);
}
```

- [ ] **Step 4: 서비스(미리보기·화면 모델) 작성**

```java
package kr.tna.org;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.EmployeeImportRows.FieldError;
import kr.tna.org.EmployeeImportRows.Parsed;
import kr.tna.org.EmployeeImportRows.Row;
import org.springframework.stereotype.Service;

@Service
public class EmployeeImportService {
    public record PageModel(List<EmployeeImportMapper.Organization> organizations, int maxRows) { }
    public record RowResult(String clientRowId, String employeeNo, List<FieldError> errors) { }
    public record Preview(boolean valid, List<RowResult> rows) { }
    record Checked(Parsed parsed, String employeeNo, UUID organizationId, List<FieldError> errors) { }

    private final OrgCommands commands;
    private final EmployeeAdminMapper employees;
    private final EmployeeImportMapper mapper;

    public EmployeeImportService(OrgCommands commands, EmployeeAdminMapper employees, EmployeeImportMapper mapper) {
        this.commands = commands;
        this.employees = employees;
        this.mapper = mapper;
    }

    public PageModel page(TnaPrincipal actor) {
        return commands.read(actor, () -> new PageModel(mapper.activeOrganizations(actor.tenantId()), EmployeeImportRows.MAX_ROWS));
    }

    public Preview preview(TnaPrincipal actor, List<Row> rows) {
        List<Row> input = rows == null ? List.of() : rows;
        EmployeeImportRows.checkCount(input.size());
        return commands.readShared(actor, () -> summarize(check(actor.tenantId(), input)));
    }

    List<Checked> check(UUID tenant, List<Row> rows) {
        LocalDate today = commands.today();
        var organizations = new HashMap<String, UUID>();
        for (var organization : mapper.activeOrganizations(tenant)) organizations.put(organization.code(), organization.id());
        var existing = new HashSet<>(employees.employeeNumbers(tenant));
        var parsed = rows.stream().map(row -> EmployeeImportRows.check(row, today)).toList();
        var manual = new HashMap<String, Integer>();
        for (var row : parsed) if (row.employeeNo() != null) manual.merge(row.employeeNo(), 1, Integer::sum);
        var issued = new HashSet<>(existing);
        issued.addAll(manual.keySet());
        var clientIds = new HashSet<String>();
        var checked = new ArrayList<Checked>();
        for (var row : parsed) {
            var errors = new ArrayList<>(row.errors());
            if (row.clientRowId() == null || row.clientRowId().isBlank() || !clientIds.add(row.clientRowId())) {
                errors.add(new FieldError("clientRowId", "행 식별자가 없거나 겹칩니다."));
            }
            String number = row.employeeNo();
            if (number != null && existing.contains(number)) {
                errors.add(new FieldError("employeeNo", "이미 등록된 사번입니다."));
            } else if (number != null && manual.get(number) > 1) {
                errors.add(new FieldError("employeeNo", "붙여넣은 행 안에서 사번이 겹칩니다."));
            } else if (number == null && row.joinedOn() != null) {
                try {
                    number = EmployeeNumbers.next(row.joinedOn(), issued);
                    issued.add(number);
                } catch (OrgException error) {
                    errors.add(new FieldError("employeeNo", error.getMessage()));
                }
            }
            UUID organization = row.organizationCode() == null ? null : organizations.get(row.organizationCode());
            if (row.organizationCode() != null && organization == null) {
                errors.add(new FieldError("organizationCode", "활성 조직 코드가 아닙니다."));
            }
            if (row.joinedOn() != null && errors.stream().noneMatch(error -> error.field().equals("joinedOn"))) {
                try {
                    employees.requireOpen(tenant, row.joinedOn(), null);
                } catch (OrgException error) {
                    errors.add(new FieldError("joinedOn", error.getMessage()));
                }
            }
            checked.add(new Checked(row, number, organization, List.copyOf(errors)));
        }
        return checked;
    }

    static Preview summarize(List<Checked> checked) {
        var rows = checked.stream().map(c -> new RowResult(c.parsed().clientRowId(), c.employeeNo(), c.errors())).toList();
        return new Preview(rows.stream().allMatch(row -> row.errors().isEmpty()), rows);
    }
}
```

`employees.requireOpen(tenant, joined, left)`은 `EmployeeService.create`가 쓰는 마감 월 확인이다(`EmployeeService.java:129`). 시그니처가 다르면 `EmployeeService.requireOpenEmployment`처럼 맞춘다.

- [ ] **Step 5: 통과 확인**

Run: Step 2 명령. Expected: PASS(2개)

- [ ] **Step 6: 커밋**

```powershell
git add src/main/java/kr/tna/org/EmployeeImportMapper.java src/main/java/kr/tna/org/EmployeeImportService.java src/test/java/kr/tna/EmployeeImportWebIT.java
git commit -m "feat: 재직자 일괄 등록 미리보기에서 사번 발급과 중복·조직 코드 확인"
```

---

### Task 3: 저장 — 사원·인사정보·소속·계정을 한 명령으로

**Files:**
- Modify: `src/main/java/kr/tna/org/EmployeeImportService.java`
- Test: `src/test/java/kr/tna/EmployeeImportWebIT.java`

- [ ] **Step 1: 실패하는 통합 테스트 추가**

```java
    @Test void saveCreatesEverythingOnceAndReplaysTheSameResult() {
        UUID command = UUID.randomUUID();
        var form = new EmployeeImportService.Form(command, "기존 재직자 이관", List.of(
                row("a", "", "가", "2019-03-04", "1990-01-01", "D10"),
                row("b", "20100", "나", "2020-01-02", "1991-02-03", "D10")));
        var first = imports.save(manager, form);
        var again = imports.save(manager, form);
        assertEquals(first, again);
        assertEquals(List.of("19001", "20100"), first.employeeNos());
        assertEquals(2, first.accounts());
        var seoulToday = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        for (String number : first.employeeNos()) {
            UUID employee = db.queryForObject("SELECT id FROM tna.employee WHERE tenant_id=? AND employee_no=?", UUID.class, tenant, number);
            assertEquals(team, db.queryForObject("""
                    SELECT organization_id FROM tna.employee_assignment WHERE tenant_id=? AND employee_id=? AND valid_from=?
                    """, UUID.class, tenant, employee, seoulToday));
            assertEquals(1, db.queryForObject("""
                    SELECT count(*) FROM tna.employee_personnel WHERE tenant_id=? AND employee_id=? AND birth_date IS NOT NULL AND grade_name='사원'
                    """, Integer.class, tenant, employee));
            assertEquals(1, db.queryForObject("""
                    SELECT count(*) FROM tna.tenant_account WHERE tenant_id=? AND employee_id=? AND NOT active AND email IS NULL
                      AND activation_expires_at > now()
                    """, Integer.class, tenant, employee));
        }
        String audit = db.queryForObject("""
                SELECT string_agg(before_state::text||after_state::text,'') FROM tna.org_audit_event
                WHERE tenant_id=? AND action='EMPLOYEE_IMPORT'
                """, String.class, tenant);
        for (String secret : new String[] {"1990-01-01", "1991-02-03", "19900101", "19910203"}) assertFalse(audit.contains(secret), secret);
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='EMPLOYEE_IMPORT'", Integer.class, tenant));
    }

    @Test void anyInvalidRowCancelsTheWholeBatch() {
        var form = new EmployeeImportService.Form(UUID.randomUUID(), "이관", List.of(
                row("a", "", "가", "2019-03-04", "1990-01-01", "D10"),
                row("b", "", "나", "2019-03-04", "2019-03-04", "D10")));
        var error = assertThrows(EmployeeImportService.ImportProblem.class, () -> imports.save(manager, form));
        assertEquals(422, error.status());
        assertEquals("birthDate", error.preview().rows().get(1).errors().getFirst().field());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.employee WHERE tenant_id=?", Integer.class, tenant));
    }
```

`assertEquals(1, …employee…)`는 담당자 사원 한 명만 남았다는 뜻이다.

- [ ] **Step 2: 실패 확인**

Run: Task 2 Step 2 명령. Expected: FAIL(컴파일 오류, `Form`·`save` 없음)

- [ ] **Step 3: 서비스에 저장 추가**

생성자에 `EmployeePersonnelMapper personnel`, `AccountProvisioning provisioning`, `kr.tna.common.auth.LocalDemoLogin localDemo`를 더한다. 다음을 클래스에 추가한다.

```java
    static final String ACTION = "EMPLOYEE_IMPORT";
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    public record Form(UUID commandId, String reason, List<Row> rows) { }
    public record Result(UUID batchId, int employees, int accounts, List<String> employeeNos) { }

    public static final class ImportProblem extends OrgException {
        private final Preview preview;
        ImportProblem(Preview preview) {
            super(422, "행별 오류를 고친 뒤 다시 저장해 주세요.");
            this.preview = preview;
        }
        public Preview preview() { return preview; }
    }

    public Result save(TnaPrincipal actor, Form form) {
        if (localDemo.publicDemo()) throw new OrgException(403, "공개 데모에서는 저장할 수 없습니다.");
        if (form == null || form.commandId() == null) throw OrgException.invalid("명령 식별자가 필요합니다.");
        String reason = OrgCommands.requiredText(form.reason(), 500, "등록 사유");
        List<Row> rows = form.rows() == null ? List.of() : form.rows();
        EmployeeImportRows.checkCount(rows.size());
        // 생년월일은 날짜 SHA-256이 쉽게 역산되므로 입력 해시에서 뺀다(ADR 0008).
        String hash = OrgCommands.fingerprint(ACTION, reason, rows.stream().map(row -> String.join("\u001f",
                String.valueOf(row.clientRowId()), String.valueOf(row.employeeNo()), String.valueOf(row.displayName()),
                String.valueOf(row.joinedOn()), String.valueOf(row.organizationCode()), String.valueOf(row.gradeName()),
                String.valueOf(row.positionName()))).toList().toString());
        UUID batch = UUID.randomUUID();
        commands.execute(actor, form.commandId(), ACTION, "EMPLOYEE_BATCH", hash, reason, () -> {
            UUID tenant = actor.tenantId();
            var checked = check(tenant, rows);
            var preview = summarize(checked);
            if (!preview.valid()) throw new ImportProblem(preview);
            LocalDate today = commands.today();
            var numbers = new ArrayList<String>();
            for (var row : checked) {
                var values = row.parsed();
                var employee = new OrgData.Employee(UUID.randomUUID(), row.employeeNo(), values.displayName(),
                        values.joinedOn(), null, true, 0);
                employees.insert(tenant, employee, actor.accountId());
                employees.insertAssignment(tenant, UUID.randomUUID(), employee.id(), row.organizationId(), today, reason, actor.accountId());
                personnel.save(tenant, employee.id(), new EmployeePersonnelData.Stored(values.birthDate(), values.gradeName(),
                        values.positionName(), null, null, null, 1), 0, actor.accountId());
                provisioning.provision(tenant, employee.id(), values.joinedOn());
                numbers.add(row.employeeNo());
            }
            return new OrgCommands.Change(batch, java.util.Map.of("employees", 0),
                    new Result(batch, numbers.size(), numbers.size(), List.copyOf(numbers)));
        });
        return commands.read(actor, () -> JSON.readValue(mapper.result(actor.tenantId(), form.commandId()), Result.class));
    }
```

- 재시도: `execute`가 같은 `commandId`·같은 해시를 찾으면 변경 없이 돌아오고, 마지막 줄이 처음 감사의 `after_state`를 읽어 같은 `Result`를 돌려준다.
- `OrgCommands.execute`가 `ImportProblem`을 다른 예외로 감싸면, `OrgException`을 그대로 다시 던지는지 확인하고 테스트로 고정한다(`anyInvalidRowCancelsTheWholeBatch`).

- [ ] **Step 4: 통과 확인**

Run: Task 2 Step 2 명령. Expected: PASS(4개)

- [ ] **Step 5: 커밋**

```powershell
git add src/main/java/kr/tna/org/EmployeeImportService.java src/test/java/kr/tna/EmployeeImportWebIT.java
git commit -m "feat: 재직자 일괄 등록 저장으로 사원·인사정보·소속·대기 계정을 함께 생성"
```

---

### Task 4: 일괄 등록 HTTP — 화면 모델, 양식, JSON 미리보기·저장

**Files:**
- Create: `src/main/java/kr/tna/org/EmployeeImportController.java`, `src/main/java/kr/tna/org/EmployeeImportApi.java`
- Modify: `src/main/java/kr/tna/common/web/ReactPageViewResolver.java`(PAGES 목록)
- Modify: `src/main/java/kr/tna/common/config/SecurityConfiguration.java`(공개 데모 차단)
- Test: `src/test/java/kr/tna/EmployeeImportWebIT.java`, `src/test/java/kr/tna/PublicDemoSecurityIT.java`

- [ ] **Step 1: 실패하는 웹 테스트 추가**

```java
    @Test void pageTemplateAndJsonEndpointsFollowTheContract() throws Exception {
        var page = mvc.perform(authenticated(get("/admin/employees/import"), manager))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.page()).andReturn();
        assertEquals(500, ((EmployeeImportService.PageModel) page.getModelAndView().getModel().get("employeeImport")).maxRows());
        var template = mvc.perform(authenticated(get("/admin/employees/import/template"), manager))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", "attachment; filename=employee-import.csv"))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, java.util.Arrays.copyOf(template, 3));
        assertTrue(new String(template, java.nio.charset.StandardCharsets.UTF_8).contains("사번,이름,입사일,생년월일,조직코드,직급,직책"));

        String body = """
                {"rows":[{"clientRowId":"a","employeeNo":"","displayName":"가","joinedOn":"2019-03-04","birthDate":"2019-03-04","organizationCode":"D10","gradeName":"","positionName":""}]}
                """;
        mvc.perform(authenticated(post("/admin/employees/import/preview"), manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.rows[0].errors[0].field").value("birthDate"));
        String save = body.replace("{\"rows\"", "{\"commandId\":\"" + UUID.randomUUID() + "\",\"reason\":\"이관\",\"rows\"");
        mvc.perform(authenticated(post("/admin/employees/import/save"), manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(save))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.rows[0].errors[0].field").value("birthDate"));
        mvc.perform(authenticated(post("/admin/employees/import/save"), manager)
                        .contentType(MediaType.APPLICATION_JSON).content(save))
                .andExpect(status().isForbidden());
        mvc.perform(authenticated(post("/admin/employees/import/preview"), manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[]}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.message").value("1~500행을 붙여넣어 주세요."));
    }
```

정적 import로 MockMvc의 `get`, `post`, `status`, `header`, `jsonPath`, `csrf`, `MediaType`을 추가한다. `authenticated(...)`는 Task 2에서 옮긴 헬퍼다.

`PublicDemoSecurityIT`의 403 확인 목록(계획 1에서 `/first-login`을 넣은 반복문)에 `"/admin/employees/import/save"`를 추가한다.

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=EmployeeImportWebIT,PublicDemoSecurityIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: FAIL(404 또는 view 없음)

- [ ] **Step 3: 화면·양식 컨트롤러**

```java
package kr.tna.org;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
class EmployeeImportController {
    private static final String HEADER = "사번,이름,입사일,생년월일,조직코드,직급,직책\r\n";
    private final EmployeeImportService imports;

    EmployeeImportController(EmployeeImportService imports) { this.imports = imports; }

    @GetMapping("/admin/employees/import")
    ModelAndView page(@AuthenticationPrincipal TnaPrincipal actor) {
        return new ModelAndView("org/employee-import").addObject("employeeImport", imports.page(actor));
    }

    @GetMapping("/admin/employees/import/template")
    ResponseEntity<byte[]> template() {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        bytes.writeBytes(HEADER.getBytes(StandardCharsets.UTF_8));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=employee-import.csv")
                .cacheControl(CacheControl.noStore()).contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes.toByteArray());
    }
}
```

- [ ] **Step 4: JSON 컨트롤러**

```java
package kr.tna.org;

import java.util.List;
import java.util.Map;
import kr.tna.common.auth.TnaPrincipal;
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
@RequestMapping(value = "/admin/employees/import", produces = MediaType.APPLICATION_JSON_VALUE)
class EmployeeImportApi {
    record PreviewForm(List<EmployeeImportRows.Row> rows) { }

    private final EmployeeImportService imports;

    EmployeeImportApi(EmployeeImportService imports) { this.imports = imports; }

    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EmployeeImportService.Preview> preview(@AuthenticationPrincipal TnaPrincipal actor, @RequestBody PreviewForm form) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(imports.preview(actor, form.rows()));
    }

    @PostMapping(value = "/save", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EmployeeImportService.Result> save(@AuthenticationPrincipal TnaPrincipal actor,
                                                      @RequestBody EmployeeImportService.Form form) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(imports.save(actor, form));
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<?> rejected(OrgException error) {
        Object body = error instanceof EmployeeImportService.ImportProblem problem ? problem.preview() : Map.of("message", error.getMessage());
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable() {
        return ResponseEntity.badRequest().body(Map.of("message", "요청 형식을 확인해 주세요."));
    }
}
```

- [ ] **Step 5: 화면 등록과 공개 데모 차단**

- `ReactPageViewResolver.PAGES`에 `page("org/employee-import", "employeeImport"),`를 `page("org/onboarding", "onboarding")` 옆에 추가한다.
- `SecurityConfiguration`의 공개 데모 `POST … denyAll()` 목록(현재 `"/admin/employees/onboarding"`으로 시작하는 줄)에 `"/admin/employees/import/save"`를 추가한다. `/admin/employees/**`는 이미 담당자 전용이므로 권한 줄은 바꾸지 않는다.

- [ ] **Step 6: 통과 확인**

Run: Step 2 명령. Expected: PASS

- [ ] **Step 7: 커밋**

```powershell
git add src/main/java/kr/tna/org/EmployeeImportController.java src/main/java/kr/tna/org/EmployeeImportApi.java src/main/java/kr/tna/common/web/ReactPageViewResolver.java src/main/java/kr/tna/common/config/SecurityConfiguration.java src/test/java/kr/tna/EmployeeImportWebIT.java src/test/java/kr/tna/PublicDemoSecurityIT.java
git commit -m "feat: 재직자 일괄 등록 화면 모델·양식 내려받기·JSON 미리보기와 저장 경로"
```

---

### Task 5: 엑셀 붙여넣기 해석(프런트엔드)

**Files:**
- Create: `frontend/src/pages/clipboardTable.ts`
- Test: `frontend/src/pages/clipboardTable.test.ts`

- [ ] **Step 1: 실패하는 테스트 작성**

```ts
import { describe, expect, it } from 'vitest';
import { parseClipboardTable, toImportDrafts } from './clipboardTable';

describe('parseClipboardTable', () => {
  it('splits tabs and CRLF rows and drops trailing blank lines', () => {
    expect(parseClipboardTable('a\tb\r\nc\td\r\n\r\n')).toEqual([['a', 'b'], ['c', 'd']]);
  });
  it('keeps tabs, newlines and doubled quotes inside quoted cells', () => {
    expect(parseClipboardTable('"x\ty"\t"line1\nline2"\t"say ""hi"""\n')).toEqual([['x\ty', 'line1\nline2', 'say "hi"']]);
  });
  it('keeps empty middle cells', () => {
    expect(parseClipboardTable('1\t\t3')).toEqual([['1', '', '3']]);
  });
});

describe('toImportDrafts', () => {
  it('maps seven columns, pads missing ones and gives each row an id', () => {
    const drafts = toImportDrafts([['19001', '홍길동', '2019-03-04', '1990-05-12', 'D10'], ['', '김', '2020.01.02', '19910203', 'D10', '대리', '팀장', '남는 열']]);
    expect(drafts.map(({ clientRowId, ...rest }) => rest)).toEqual([
      { employeeNo: '19001', displayName: '홍길동', joinedOn: '2019-03-04', birthDate: '1990-05-12', organizationCode: 'D10', gradeName: '', positionName: '' },
      { employeeNo: '', displayName: '김', joinedOn: '2020.01.02', birthDate: '19910203', organizationCode: 'D10', gradeName: '대리', positionName: '팀장' },
    ]);
    expect(new Set(drafts.map(d => d.clientRowId)).size).toBe(2);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- --run src/pages/clipboardTable.test.ts`
Expected: FAIL(모듈 없음)

- [ ] **Step 3: 구현**

```ts
export interface ImportDraft {
  clientRowId: string;
  employeeNo: string;
  displayName: string;
  joinedOn: string;
  birthDate: string;
  organizationCode: string;
  gradeName: string;
  positionName: string;
}

export const IMPORT_FIELDS = ['employeeNo', 'displayName', 'joinedOn', 'birthDate', 'organizationCode', 'gradeName', 'positionName'] as const;

/** 엑셀이 클립보드에 넣는 TSV를 행·열로 나눈다. 따옴표 셀 안의 탭·줄바꿈과 "" 이스케이프를 지킨다. */
export function parseClipboardTable(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c !== '"') cell += c;
      else if (text[i + 1] === '"') { cell += '"'; i++; }
      else quoted = false;
    } else if (c === '"' && cell === '') {
      quoted = true;
    } else if (c === '\t') {
      row.push(cell); cell = '';
    } else if (c === '\r' || c === '\n') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(cell); rows.push(row); row = []; cell = '';
    } else {
      cell += c;
    }
  }
  if (cell !== '' || row.length > 0) { row.push(cell); rows.push(row); }
  return rows.filter(cells => cells.some(value => value.trim() !== ''));
}

export function toImportDrafts(table: string[][]): ImportDraft[] {
  return table.map(cells => {
    const draft = { clientRowId: crypto.randomUUID() } as ImportDraft;
    IMPORT_FIELDS.forEach((field, index) => { draft[field] = cells[index] ?? ''; });
    return draft;
  });
}
```

- [ ] **Step 4: 통과 확인**

Run: Step 2 명령. Expected: PASS(4개)

- [ ] **Step 5: 커밋**

```powershell
git add frontend/src/pages/clipboardTable.ts frontend/src/pages/clipboardTable.test.ts
git commit -m "feat: 엑셀 붙여넣기 TSV와 따옴표 셀 해석"
```

---

### Task 6: 재직자 일괄 등록 화면

**Files:**
- Create: `frontend/src/pages/EmployeeImportPage.tsx`
- Modify: `frontend/src/App.tsx`(`titles`, `isTaskUrl` 제외 목록, `Content`), `frontend/src/navigation.ts`(인사관리 > 사원)
- Test: `frontend/src/pages/EmployeeImportPage.test.tsx`

- [ ] **Step 1: 실패하는 화면 테스트 작성**

```tsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EmployeeImportPage } from './EmployeeImportPage';

const csrf = { token: 't', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const model = { organizations: [{ id: 'o1', code: 'D10', name: '개발팀' }], maxRows: 500 };

function paste(text: string) {
  fireEvent.paste(screen.getByLabelText('엑셀에서 복사한 행 붙여넣기'), { clipboardData: { getData: () => text } });
}

afterEach(() => vi.restoreAllMocks());

describe('EmployeeImportPage', () => {
  it('turns pasted rows into sheet rows and shows server errors per cell', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      valid: false, rows: [{ clientRowId: 'x', employeeNo: '19001', errors: [{ field: 'birthDate', message: '생년월일은 입사일 이전이어야 합니다.' }] }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    render(<EmployeeImportPage model={model} csrf={csrf} />);
    paste('\t홍길동\t2019-03-04\t2019-03-04\tD10\t\t\r\n');
    expect(screen.getByText('1행')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '검증' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/admin/employees/import/preview', expect.objectContaining({ method: 'POST' })));
    expect(await screen.findByText('오류 1행')).toBeTruthy();
    expect((screen.getByRole('button', { name: '저장' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('enables save only after a clean preview and a reason, then shows next steps', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify({ valid: true, rows: [{ clientRowId: 'x', employeeNo: '19001', errors: [] }] }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ batchId: 'b', employees: 1, accounts: 1, employeeNos: ['19001'] }), { status: 200 }));
    render(<EmployeeImportPage model={model} csrf={csrf} />);
    paste('\t홍길동\t2019-03-04\t1990-05-12\tD10\t\t\r\n');
    fireEvent.click(screen.getByRole('button', { name: '검증' }));
    await screen.findByText('모든 행 정상');
    fireEvent.change(screen.getByLabelText('등록 사유'), { target: { value: '기존 재직자 이관' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(await screen.findByText('등록 1명 · 계정 1개')).toBeTruthy();
    expect(screen.getByRole('link', { name: '사용자 관리' }).getAttribute('href')).toBe('/admin/accounts');
    expect(screen.getByRole('link', { name: '근무 설정' }).getAttribute('href')).toBe('/admin/schedules');
    expect(screen.getByRole('link', { name: '연차 관리' }).getAttribute('href')).toBe('/admin/leave-balances');
  });
});
```

`fireEvent.paste`의 `clipboardData` 모의 방식이 jsdom에서 다르게 동작하면 `createEvent.paste` + `Object.defineProperty(event, 'clipboardData', …)`로 바꾼다.

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- --run src/pages/EmployeeImportPage.test.tsx`
Expected: FAIL(모듈 없음)

- [ ] **Step 3: 화면 구현**

```tsx
import { useMemo, useState } from 'react';
import type { ColDef } from 'ag-grid-community';
import { PageHeader } from '../components/PageHeader';
import { WorklogSheet, sheetEditorColumn } from '../components/WorklogSheet';
import type { SheetFieldError } from '../components/WorklogSheet';
import type { CsrfToken } from '../types';
import { IMPORT_FIELDS, parseClipboardTable, toImportDrafts } from './clipboardTable';
import type { ImportDraft } from './clipboardTable';

export interface EmployeeImportModel { organizations: { id: string; code: string; name: string }[]; maxRows: number }
interface FieldError { field: string; message: string }
interface Preview { valid: boolean; rows: { clientRowId: string; employeeNo: string | null; errors: FieldError[] }[] }
interface Result { batchId: string; employees: number; accounts: number; employeeNos: string[] }

const CAPTIONS: Record<(typeof IMPORT_FIELDS)[number], string> = {
  employeeNo: '사번', displayName: '이름', joinedOn: '입사일', birthDate: '생년월일',
  organizationCode: '조직코드', gradeName: '직급', positionName: '직책',
};

async function postJson<T>(url: string, csrf: CsrfToken, body: object): Promise<{ status: number; data: T }> {
  const response = await fetch(url, {
    method: 'POST', credentials: 'same-origin',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify(body),
  });
  return { status: response.status, data: await response.json() as T };
}

export function EmployeeImportPage({ model, csrf }: { model: EmployeeImportModel; csrf: CsrfToken }) {
  const [drafts, setDrafts] = useState<ImportDraft[]>([]);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [reviewed, setReviewed] = useState('');
  const [reason, setReason] = useState('');
  const [commandId, setCommandId] = useState(() => crypto.randomUUID());
  const [result, setResult] = useState<Result | null>(null);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const current = JSON.stringify(drafts);
  const fresh = preview !== null && reviewed === current;
  const errors: SheetFieldError[] = fresh
    ? preview.rows.flatMap(row => row.errors.map(error => ({ clientRowId: row.clientRowId, field: error.field, message: error.message })))
    : [];
  const invalidRows = fresh ? preview.rows.filter(row => row.errors.length > 0).length : 0;

  function load(text: string) {
    const next = toImportDrafts(parseClipboardTable(text));
    if (next.length > model.maxRows) { setMessage(`한 번에 ${model.maxRows}행까지 등록할 수 있습니다.`); return; }
    setDrafts(next); setPreview(null); setResult(null); setMessage('');
  }

  function update(rowId: string, field: (typeof IMPORT_FIELDS)[number], value: string) {
    setDrafts(rows => rows.map(row => row.clientRowId === rowId ? { ...row, [field]: value } : row));
  }

  const columns = useMemo<ColDef<ImportDraft>[]>(() => IMPORT_FIELDS.map(field => sheetEditorColumn<ImportDraft>(
    { field, headerName: CAPTIONS[field], minWidth: field === 'displayName' ? 140 : 110 },
    (row, index) => ({ label: `${CAPTIONS[field]} ${index + 1}`, value: row[field], onChange: value => update(row.clientRowId, field, value) }),
  )), []);

  async function validate() {
    setBusy(true); setMessage('');
    try {
      const { status, data } = await postJson<Preview & { message?: string }>('/admin/employees/import/preview', csrf, { rows: drafts });
      if (status !== 200) { setMessage(data.message ?? '검증하지 못했습니다.'); return; }
      setPreview(data); setReviewed(current);
    } catch { setMessage('검증하지 못했습니다. 잠시 후 다시 시도해 주세요.'); }
    finally { setBusy(false); }
  }

  async function save() {
    setBusy(true); setMessage('');
    try {
      const { status, data } = await postJson<Result & Preview & { message?: string }>('/admin/employees/import/save', csrf, { commandId, reason, rows: drafts });
      if (status === 200) { setResult(data); setDrafts([]); setPreview(null); setCommandId(crypto.randomUUID()); return; }
      if (status === 422 && Array.isArray(data.rows)) { setPreview(data); setReviewed(current); setCommandId(crypto.randomUUID()); return; }
      setMessage(data.message ?? '저장하지 못했습니다.');
    } catch {
      // 응답을 받지 못했으면 같은 명령 식별자로 다시 저장해 중복 등록을 막는다.
      setMessage('저장 결과를 확인하지 못했습니다. 같은 내용으로 다시 저장하면 한 번만 반영됩니다.');
    } finally { setBusy(false); }
  }

  return (
    <section className="w-page">
      <PageHeader title="재직자 일괄 등록" help="엑셀에서 사번·이름·입사일·생년월일·조직코드·직급·직책 7개 열을 머리글 없이 복사해 붙여넣습니다. 사번을 비우면 입사 연도로 자동 발급합니다." />
      <div className="actions">
        <a href="/admin/employees/import/template" download>양식 내려받기</a>
        <span>조직코드: {model.organizations.map(o => `${o.code} ${o.name}`).join(', ') || '등록된 조직 없음'}</span>
      </div>
      <textarea aria-label="엑셀에서 복사한 행 붙여넣기" rows={3} placeholder="엑셀에서 복사한 행을 여기에 붙여넣으세요."
        onPaste={event => { event.preventDefault(); load(event.clipboardData.getData('text/plain')); }} />
      <WorklogSheet<ImportDraft> label="붙여넣은 재직자" count={`${drafts.length}행`} rows={drafts} columns={columns}
        getRowId={row => row.clientRowId} errors={errors} emptyMessage="붙여넣은 행이 없습니다." />
      {fresh && <p role="status">{preview.valid ? '모든 행 정상' : `오류 ${invalidRows}행`}</p>}
      <label>등록 사유<textarea value={reason} maxLength={500} onChange={event => setReason(event.target.value)} /></label>
      <div className="actions">
        <button type="button" onClick={validate} disabled={busy || drafts.length === 0}>검증</button>
        <button type="button" onClick={save} disabled={busy || !fresh || !preview.valid || reason.trim() === ''}>저장</button>
      </div>
      {message && <p role="alert">{message}</p>}
      {result && (
        <div role="status">
          <p>{`등록 ${result.employees}명 · 계정 ${result.accounts}개`}</p>
          <p>다음 단계: <a href="/admin/schedules">근무 설정</a> · <a href="/admin/leave-balances">연차 관리</a> · <a href="/admin/accounts">사용자 관리</a></p>
        </div>
      )}
    </section>
  );
}
```

- 시트의 `count`가 `"1행"`을 보여 주는지 `WorklogSheet` 표시 방식을 확인하고, 다르면 테스트의 `getByText('1행')`을 실제 표시에 맞춘다.
- 스타일은 기존 일괄 화면(`LeaveBalanceBatch.css`)의 클래스를 재사용한다. 새 여백·큰 카드는 만들지 않는다(조밀한 업무 화면).

- [ ] **Step 4: 화면 연결**

- `App.tsx`
  - `titles`에 `'org/employee-import': '재직자 일괄 등록',`
  - `isTaskUrl`의 `/admin/employees/(onboarding|bulk-edit)` 제외 정규식에 `import`를 추가해 `(onboarding|bulk-edit|import)`로 바꾼다.
  - `Content`에 `{props.page === 'org/employee-import' && <EmployeeImportPage model={props.data.employeeImport as EmployeeImportModel} csrf={props.csrf} />}`를 `org/onboarding` 줄 다음에 둔다.
- `navigation.ts` 인사관리 > 사원 목록의 '입사 예정 관리' 다음 줄:
  `item('employee-import', '재직자 일괄 등록', '/admin/employees/import', admin, 'org/employee-import'),`

- [ ] **Step 5: 통과 확인**

Run: `npm --prefix frontend test -- --run src/pages/EmployeeImportPage.test.tsx src/pages/clipboardTable.test.ts` 그리고 `npm --prefix frontend run build`
Expected: PASS, 빌드 성공. 메뉴 개수를 단언하는 기존 테스트(`navigation.test.ts` 등)가 실패하면 새 항목을 반영한다.

- [ ] **Step 6: 커밋**

```powershell
git add frontend/src/pages/EmployeeImportPage.tsx frontend/src/pages/EmployeeImportPage.test.tsx frontend/src/App.tsx frontend/src/navigation.ts
git commit -m "feat: 재직자 일괄 등록 화면(붙여넣기·셀 수정·검증·저장·다음 단계)"
```

---

### Task 7: 사용자 관리 목록

**Files:**
- Create: `src/main/java/kr/tna/org/AccountDirectoryMapper.java`, `src/main/java/kr/tna/org/AccountDirectoryService.java`(목록까지), `src/main/java/kr/tna/org/AccountDirectoryController.java`
- Modify: `SecurityConfiguration.java`(담당자 권한), `ReactPageViewResolver.java`
- Test: `src/test/java/kr/tna/AccountDirectoryWebIT.java`

- [ ] **Step 1: 실패하는 통합 테스트 작성**

클래스 뼈대(`@DynamicPropertySource`, 새 고객사, `member`, `authenticated`)는 Task 2의 `EmployeeImportWebIT`과 같게 만든다.

```java
    UUID employee(String number, String name) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,DATE '2020-01-01')",
                tenant, id, number, name);
        return id;
    }

    void account(UUID employee, boolean active, String expires, int failures) {
        db.update("""
                INSERT INTO tna.tenant_account(tenant_id,id,employee_id,active,password_hash,activation_expires_at,activation_failures)
                VALUES (?,?,?,?,?,CAST(? AS timestamptz),?)
                """, tenant, UUID.randomUUID(), employee, active, active ? "{noop}x-synthetic-hash" : null, expires, failures);
    }

    void birth(UUID employee) {
        db.update("INSERT INTO tna.employee_personnel(tenant_id,employee_id,birth_date,version,updated_by) VALUES (?,?,DATE '1990-01-01',1,?)",
                tenant, employee, manager.accountId());
    }

    @Test void listsEveryStateTheSameWayAccountStatusDoes() throws Exception {
        UUID none = employee("A1", "계정없음");
        UUID active = employee("A2", "사용중"); account(active, true, null, 0);
        UUID noBirth = employee("A3", "생년월일없음"); account(noBirth, false, "2999-01-01", 0);
        UUID locked = employee("A4", "잠김"); account(locked, false, "2999-01-01", 5); birth(locked);
        UUID expired = employee("A5", "만료"); account(expired, false, "2000-01-01", 0); birth(expired);
        UUID pending = employee("A6", "대기"); account(pending, false, "2999-01-01", 1); birth(pending);
        var expected = java.util.Map.of(none, "NONE", active, "ACTIVE", noBirth, "BIRTH_DATE_REQUIRED",
                locked, "LOCKED", expired, "EXPIRED", pending, "PENDING");
        var result = mvc.perform(authenticated(get("/admin/accounts").param("q", "A"), manager))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.page()).andReturn();
        var directory = (kr.tna.org.AccountDirectoryService.Directory) result.getModelAndView().getModel().get("accountDirectory");
        for (var item : directory.items()) {
            if (!expected.containsKey(item.employeeId())) continue;
            assertEquals(expected.get(item.employeeId()), item.state(), item.displayName());
            assertEquals(kr.tna.org.AccountStatus.valueOf(item.state()).label(), item.label());
        }
        var filtered = (kr.tna.org.AccountDirectoryService.Directory) mvc.perform(authenticated(get("/admin/accounts")
                        .param("status", "LOCKED"), manager)).andReturn().getModelAndView().getModel().get("accountDirectory");
        assertEquals(java.util.List.of(locked), filtered.items().stream().map(i -> i.employeeId()).toList());
        mvc.perform(authenticated(get("/admin/accounts").param("status", "WRONG"), manager)).andExpect(status().isBadRequest());
        String page = result.getResponse().getContentAsString();
        assertFalse(page.contains("1990-01-01"));
    }
```

`active` 계정의 비밀번호 열은 `account_active_requires_password` 제약 때문에 값이 필요하다. 로그인에 쓰지 않으므로 합성 문자열이면 된다.

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=AccountDirectoryWebIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: FAIL(컴파일 오류 또는 403/404)

- [ ] **Step 3: 매퍼 작성**

```java
package kr.tna.org;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccountDirectoryMapper {
    record Row(UUID employeeId, String employeeNo, String displayName, String organizationName, String state) { }

    /** AccountStatus.of와 같은 순서. 바꾸면 AccountDirectoryWebIT의 대조 테스트도 함께 바꾼다. */
    @Select("""
            SELECT employee_id, employee_no, display_name, organization_name, state FROM (
              SELECT e.id AS employee_id, e.employee_no, e.display_name, o.name AS organization_name,
                CASE WHEN a.id IS NULL THEN 'NONE'
                     WHEN a.activation_expires_at IS NULL THEN CASE WHEN a.active THEN 'ACTIVE' ELSE 'PREVIOUS_INVITATION' END
                     WHEN NOT EXISTS (SELECT 1 FROM tna.employee_personnel p WHERE p.tenant_id=e.tenant_id
                                        AND p.employee_id=e.id AND p.birth_date IS NOT NULL) THEN 'BIRTH_DATE_REQUIRED'
                     WHEN a.activation_failures >= 5 THEN 'LOCKED'
                     WHEN a.activation_expires_at <= #{now} THEN 'EXPIRED'
                     ELSE 'PENDING' END AS state
              FROM tna.employee e
              LEFT JOIN tna.tenant_account a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
              LEFT JOIN tna.employee_assignment s ON s.tenant_id=e.tenant_id AND s.employee_id=e.id
                   AND s.valid_from <= #{today} AND (s.valid_to IS NULL OR s.valid_to > #{today})
              LEFT JOIN tna.organization o ON o.tenant_id=s.tenant_id AND o.id=s.organization_id
              WHERE e.tenant_id=#{tenantId} AND e.active AND (e.left_on IS NULL OR e.left_on >= #{today})
                AND (e.employee_no ILIKE #{pattern} OR e.display_name ILIKE #{pattern})
            ) listed
            WHERE #{status}='all' OR state=#{status}
            ORDER BY employee_no, employee_id
            LIMIT 51 OFFSET #{offset}
            """)
    List<Row> list(@Param("tenantId") UUID tenantId, @Param("pattern") String pattern, @Param("status") String status,
                   @Param("today") LocalDate today, @Param("now") Instant now, @Param("offset") int offset);
}
```

- [ ] **Step 4: 서비스(목록) 작성**

```java
package kr.tna.org;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.stereotype.Service;

@Service
public class AccountDirectoryService {
    static final List<String> STATES = List.of("all", "NONE", "PENDING", "BIRTH_DATE_REQUIRED", "LOCKED", "EXPIRED", "ACTIVE", "PREVIOUS_INVITATION");

    public record Item(UUID employeeId, String employeeNo, String displayName, String organizationName,
                       String state, String label, boolean self) { }
    public record Option(String value, String label) { }
    public record Directory(List<Item> items, String q, String status, int page, boolean hasNext, List<Option> statuses) { }

    private final OrgCommands commands;
    private final AccountDirectoryMapper mapper;
    private final Clock clock;

    public AccountDirectoryService(OrgCommands commands, AccountDirectoryMapper mapper, Clock clock) {
        this.commands = commands;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Directory directory(TnaPrincipal actor, String q, String status, int page) {
        String query = q == null ? "" : q.strip();
        if (query.length() > 80) throw OrgException.invalid("검색어는 80자 이하로 입력해 주세요.");
        if (!STATES.contains(status)) throw new OrgException(400, "상태 값을 확인해 주세요.");
        int offset = OrgCommands.offset(page);
        String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return commands.readShared(actor, () -> {
            var rows = mapper.list(actor.tenantId(), pattern, status, commands.today(), clock.instant(), offset);
            var items = rows.stream().limit(50).map(row -> new Item(row.employeeId(), row.employeeNo(), row.displayName(),
                    row.organizationName(), row.state(), AccountStatus.valueOf(row.state()).label(),
                    row.employeeId().equals(actor.employeeId()))).toList();
            var statuses = STATES.stream().map(value -> new Option(value,
                    value.equals("all") ? "전체" : AccountStatus.valueOf(value).label())).toList();
            return new Directory(items, query, status, page, rows.size() > 50, statuses);
        });
    }
}
```

`AccountStatus.NONE.label()`과 `PREVIOUS_INVITATION.label()`이 화면에 맞는 문구("계정 없음", "이전 초대 대기")인지 확인한다. 비어 있거나 다르면 `AccountStatus`의 라벨을 고치고 `AccountStatusTest`에 단언을 더한다.

- [ ] **Step 5: 컨트롤러, 권한, 화면 등록**

```java
package kr.tna.org;

import kr.tna.common.auth.TnaPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

@Controller
class AccountDirectoryController {
    private final AccountDirectoryService accounts;

    AccountDirectoryController(AccountDirectoryService accounts) { this.accounts = accounts; }

    @GetMapping("/admin/accounts")
    ModelAndView list(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(defaultValue = "") String q,
                      @RequestParam(defaultValue = "all") String status, @RequestParam(defaultValue = "0") int page) {
        return new ModelAndView("org/accounts").addObject("accountDirectory", accounts.directory(actor, q, status, page));
    }
}
```

- 400을 화면 오류로 보이려면 `OrgErrorAdvice`의 `assignableTypes`에 `AccountDirectoryController.class`를 추가한다(기존 화면 컨트롤러와 같은 처리).
- `SecurityConfiguration`의 담당자 권한 목록(`"/admin/organizations", … "/admin/access", …`)에 `"/admin/accounts", "/admin/accounts/**"`를 추가한다. 추가하지 않으면 마지막 `anyRequest().denyAll()`이 막는다.
- `ReactPageViewResolver.PAGES`에 `page("org/accounts", "accountDirectory"),`를 추가한다.

- [ ] **Step 6: 통과 확인**

Run: Step 2 명령. Expected: PASS

- [ ] **Step 7: 커밋**

```powershell
git add src/main/java/kr/tna/org/AccountDirectoryMapper.java src/main/java/kr/tna/org/AccountDirectoryService.java src/main/java/kr/tna/org/AccountDirectoryController.java src/main/java/kr/tna/org/OrgErrorAdvice.java src/main/java/kr/tna/common/config/SecurityConfiguration.java src/main/java/kr/tna/common/web/ReactPageViewResolver.java src/test/java/kr/tna/AccountDirectoryWebIT.java
git commit -m "feat: 사용자 관리 목록(계정 상태 필터·검색·페이지)"
```

---

### Task 8: 여러 사원의 계정 생성·비밀번호 초기화

**Files:**
- Modify: `src/main/java/kr/tna/org/AccountDirectoryService.java`(일괄 동작), `src/main/java/kr/tna/org/AccountService.java:124`(`requireEmployee` 패키지 공개)
- Create: `src/main/java/kr/tna/org/AccountBulkApi.java`
- Modify: `SecurityConfiguration.java`(공개 데모 차단)
- Test: `src/test/java/kr/tna/AccountDirectoryWebIT.java`, `PublicDemoSecurityIT.java`

- [ ] **Step 1: 실패하는 통합 테스트 추가**

```java
    String bulk(String action, String reason, UUID... employees) throws Exception {
        String ids = java.util.Arrays.stream(employees).map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(","));
        return mvc.perform(authenticated(post("/admin/accounts/" + action), manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"" + UUID.randomUUID() + "\",\"reason\":\"" + reason + "\",\"employeeIds\":[" + ids + "]}"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test void createsAccountsForEveryoneOrNobody() throws Exception {
        UUID first = employee("B1", "첫째");
        UUID second = employee("B2", "둘째");
        UUID has = employee("B3", "이미있음"); account(has, true, null, 0);
        String rejected = bulk("create", "개통", first, has);
        assertTrue(rejected.contains("이미 계정이 있습니다."), rejected);
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.tenant_account WHERE tenant_id=? AND employee_id=?", Integer.class, tenant, first));
        assertTrue(bulk("create", "개통", first, second).contains("\"count\":2"));
        assertEquals(2, db.queryForObject("""
                SELECT count(*) FROM tna.tenant_account WHERE tenant_id=? AND employee_id IN (?,?) AND activation_expires_at IS NOT NULL
                """, Integer.class, tenant, first, second));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='ACCOUNT_BULK_PROVISIONED'", Integer.class, tenant));
    }

    @Test void resetsAccountsButNeverTheActorOrSomeoneWithoutAnAccount() throws Exception {
        UUID worker = employee("C1", "직원"); account(worker, true, null, 0);
        UUID none = employee("C2", "계정없음");
        assertTrue(bulk("reset", "초기화", worker, none).contains("계정이 없습니다. 계정 생성을 사용하세요."));
        assertTrue(bulk("reset", "초기화", worker, manager.employeeId()).contains("본인 계정은 초기화할 수 없습니다."));
        long before = db.queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND employee_id=?", Long.class, tenant, worker);
        assertTrue(bulk("reset", "초기화", worker).contains("\"count\":1"));
        assertEquals(before + 1, db.queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND employee_id=?", Long.class, tenant, worker));
        assertTrue(bulk("reset", " ", worker).contains("초기화 사유"));
    }
```

`PublicDemoSecurityIT`의 403 목록에 `"/admin/accounts/create"`, `"/admin/accounts/reset"`을 추가한다.

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=AccountDirectoryWebIT,PublicDemoSecurityIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: FAIL(404)

- [ ] **Step 3: `requireEmployee` 재사용 준비**

`AccountService.java:124`의 `private OrgData.Employee requireEmployee(UUID tenantId, UUID employeeId, boolean current)`에서 `private`을 지운다(패키지 공개). 동작은 바꾸지 않는다.

- [ ] **Step 4: 서비스에 일괄 동작 추가**

생성자에 `AccountService accountService`, `AccountAdminMapper accountAdmin`, `AccountProvisioning provisioning`, `PublicDemoIdentityGuard identityGuard`, `kr.tna.common.auth.LocalDemoLogin localDemo`를 더한다. `identityGuard`의 실제 클래스 이름은 `AccountService` 생성자에서 확인한다.

```java
    public record BulkForm(UUID commandId, String reason, List<UUID> employeeIds) { }
    public record BulkResult(int count) { }
    public record EmployeeError(UUID employeeId, String message) { }

    public static final class BulkProblem extends OrgException {
        private final List<EmployeeError> errors;
        BulkProblem(List<EmployeeError> errors) {
            super(422, "선택한 사원 중 처리할 수 없는 사원이 있습니다.");
            this.errors = errors;
        }
        public List<EmployeeError> errors() { return errors; }
    }

    public BulkResult create(TnaPrincipal actor, BulkForm form) { return bulk(actor, form, true); }

    public BulkResult reset(TnaPrincipal actor, BulkForm form) { return bulk(actor, form, false); }

    private BulkResult bulk(TnaPrincipal actor, BulkForm form, boolean create) {
        if (localDemo.publicDemo()) throw new OrgException(403, "공개 데모에서는 계정을 바꿀 수 없습니다.");
        if (form == null || form.commandId() == null) throw OrgException.invalid("명령 식별자가 필요합니다.");
        String reason = OrgCommands.requiredText(form.reason(), 500, create ? "생성 사유" : "초기화 사유");
        var ids = form.employeeIds() == null ? List.<UUID>of() : form.employeeIds();
        if (ids.isEmpty() || ids.size() > 100 || ids.contains(null) || Set.copyOf(ids).size() != ids.size()) {
            throw OrgException.invalid("1~100명을 중복 없이 선택해 주세요.");
        }
        for (UUID id : ids) identityGuard.requireMutable(actor, id);
        var sorted = ids.stream().sorted().toList();
        String action = create ? "ACCOUNT_BULK_PROVISIONED" : "ACCOUNT_BULK_RESET";
        UUID batch = UUID.randomUUID();
        commands.execute(actor, form.commandId(), action, "EMPLOYEE_BATCH",
                OrgCommands.fingerprint(action, reason, sorted.toString()), reason, () -> {
            UUID tenant = actor.tenantId();
            var errors = new java.util.ArrayList<EmployeeError>();
            var work = new java.util.ArrayList<Runnable>();
            for (UUID id : sorted) {
                try {
                    var employee = accountService.requireEmployee(tenant, id, true);
                    var account = accountAdmin.account(tenant, id);
                    if (create && account != null) errors.add(new EmployeeError(id, "이미 계정이 있습니다."));
                    else if (!create && account == null) errors.add(new EmployeeError(id, "계정이 없습니다. 계정 생성을 사용하세요."));
                    else if (!create && id.equals(actor.employeeId())) errors.add(new EmployeeError(id, "본인 계정은 초기화할 수 없습니다."));
                    else if (create) work.add(() -> provisioning.provision(tenant, id, employee.joinedOn()));
                    else work.add(() -> provisioning.reopen(tenant, account.id()));
                } catch (OrgException error) {
                    errors.add(new EmployeeError(id, error.getMessage()));
                }
            }
            if (!errors.isEmpty()) throw new BulkProblem(List.copyOf(errors));
            work.forEach(Runnable::run);
            return new OrgCommands.Change(batch, java.util.Map.of("employeeIds", sorted, "count", 0),
                    java.util.Map.of("employeeIds", sorted, "count", sorted.size()));
        });
        return new BulkResult(sorted.size());
    }
```

- 감사는 명령 1건이다. 상태에는 사원 ID와 건수만 남긴다(생년월일·비밀번호 없음).
- `reopen`은 `auth_version`을 올려 기존 세션과 이전 비밀번호를 끊는다(계획 1).

- [ ] **Step 5: JSON 컨트롤러와 공개 데모 차단**

```java
package kr.tna.org;

import java.util.Map;
import kr.tna.common.auth.TnaPrincipal;
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
@RequestMapping(value = "/admin/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
class AccountBulkApi {
    private final AccountDirectoryService accounts;

    AccountBulkApi(AccountDirectoryService accounts) { this.accounts = accounts; }

    @PostMapping(value = "/create", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccountDirectoryService.BulkResult> create(@AuthenticationPrincipal TnaPrincipal actor,
                                                              @RequestBody AccountDirectoryService.BulkForm form) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.create(actor, form));
    }

    @PostMapping(value = "/reset", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccountDirectoryService.BulkResult> reset(@AuthenticationPrincipal TnaPrincipal actor,
                                                             @RequestBody AccountDirectoryService.BulkForm form) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.reset(actor, form));
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<Map<String, Object>> rejected(OrgException error) {
        Map<String, Object> body = error instanceof AccountDirectoryService.BulkProblem problem
                ? Map.of("message", error.getMessage(), "errors", problem.errors()) : Map.of("message", error.getMessage());
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable() {
        return ResponseEntity.badRequest().body(Map.of("message", "요청 형식을 확인해 주세요."));
    }
}
```

`SecurityConfiguration`의 공개 데모 `POST … denyAll()` 목록에 `"/admin/accounts/create", "/admin/accounts/reset"`을 추가한다.

- [ ] **Step 6: 통과 확인**

Run: Step 2 명령, 이어서 `AccountServiceIT,AccountAccessWebIT`. Expected: PASS

- [ ] **Step 7: 커밋**

```powershell
git add src/main/java/kr/tna/org/AccountDirectoryService.java src/main/java/kr/tna/org/AccountService.java src/main/java/kr/tna/org/AccountBulkApi.java src/main/java/kr/tna/common/config/SecurityConfiguration.java src/test/java/kr/tna/AccountDirectoryWebIT.java src/test/java/kr/tna/PublicDemoSecurityIT.java
git commit -m "feat: 사용자 관리에서 여러 사원의 계정 생성·비밀번호 초기화"
```

---

### Task 9: 사용자 관리 화면

**Files:**
- Create: `frontend/src/pages/AccountsPage.tsx`
- Modify: `frontend/src/pages/IdentityPages.tsx`(`case 'org/accounts'`), `frontend/src/App.tsx`(`titles`), `frontend/src/navigation.ts`(시스템 설정 > 계정·공통코드)
- Test: `frontend/src/pages/AccountsPage.test.tsx`

- [ ] **Step 1: 실패하는 화면 테스트 작성**

```tsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AccountsPage } from './AccountsPage';
import type { AccountDirectory } from './AccountsPage';

const csrf = { token: 't', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' };
const model: AccountDirectory = {
  q: '', status: 'all', page: 0, hasNext: false,
  statuses: [{ value: 'all', label: '전체' }, { value: 'NONE', label: '계정 없음' }],
  items: [
    { employeeId: 'e1', employeeNo: '19001', displayName: '가', organizationName: '개발팀', state: 'NONE', label: '계정 없음', self: false },
    { employeeId: 'e2', employeeNo: '19002', displayName: '나', organizationName: '개발팀', state: 'LOCKED', label: '잠김(5회 실패)', self: false },
  ],
};

afterEach(() => vi.restoreAllMocks());

describe('AccountsPage', () => {
  it('enables only the action that fits every selected employee', () => {
    render(<AccountsPage model={model} csrf={csrf} reload={() => undefined} />);
    fireEvent.click(screen.getByLabelText('19001 가 선택'));
    expect((screen.getByRole('button', { name: '계정 생성' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('처리 사유'), { target: { value: '개통' } });
    expect((screen.getByRole('button', { name: '계정 생성' }) as HTMLButtonElement).disabled).toBe(false);
    expect((screen.getByRole('button', { name: '비밀번호 초기화' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByLabelText('19002 나 선택'));
    expect((screen.getByRole('button', { name: '계정 생성' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('posts the selected ids and reloads, or shows each employee error', async () => {
    const reload = vi.fn();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify({ message: '선택한 사원 중 처리할 수 없는 사원이 있습니다.', errors: [{ employeeId: 'e2', message: '현재 재직 중인 활성 사원만 관리할 수 있습니다.' }] }), { status: 422 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ count: 1 }), { status: 200 }));
    render(<AccountsPage model={model} csrf={csrf} reload={reload} />);
    fireEvent.click(screen.getByLabelText('19002 나 선택'));
    fireEvent.change(screen.getByLabelText('처리 사유'), { target: { value: '잠금 해제' } });
    fireEvent.click(screen.getByRole('button', { name: '비밀번호 초기화' }));
    expect(await screen.findByText('19002 나: 현재 재직 중인 활성 사원만 관리할 수 있습니다.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '비밀번호 초기화' }));
    await waitFor(() => expect(reload).toHaveBeenCalled());
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- --run src/pages/AccountsPage.test.tsx`
Expected: FAIL(모듈 없음)

- [ ] **Step 3: 화면 구현**

```tsx
import { useState } from 'react';
import { PageHeader } from '../components/PageHeader';
import type { CsrfToken } from '../types';

export interface AccountItem { employeeId: string; employeeNo: string; displayName: string; organizationName: string | null; state: string; label: string; self: boolean }
export interface AccountDirectory { items: AccountItem[]; q: string; status: string; page: number; hasNext: boolean; statuses: { value: string; label: string }[] }

const CONFIRM = {
  create: '선택한 사원의 계정을 만듭니다. 직원은 7일 안에 사번으로 첫 로그인해 생년월일을 확인하고 비밀번호를 정합니다.',
  reset: '선택한 사원의 비밀번호를 초기화합니다. 직원은 7일 안에 사번으로 다시 첫 로그인해야 합니다.',
};

export function AccountsPage({ model, csrf, reload }: { model: AccountDirectory; csrf: CsrfToken; reload: () => void }) {
  const [selected, setSelected] = useState<string[]>([]);
  const [reason, setReason] = useState('');
  const [message, setMessage] = useState('');
  const [errors, setErrors] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [commandId, setCommandId] = useState(() => crypto.randomUUID());
  const chosen = model.items.filter(item => selected.includes(item.employeeId));
  const ready = chosen.length > 0 && chosen.length <= 100 && reason.trim() !== '' && !busy;
  const canCreate = ready && chosen.every(item => item.state === 'NONE');
  const canReset = ready && chosen.every(item => item.state !== 'NONE' && !item.self);

  function toggle(id: string) {
    setSelected(ids => ids.includes(id) ? ids.filter(value => value !== id) : [...ids, id]);
    setCommandId(crypto.randomUUID());
  }

  async function run(action: 'create' | 'reset') {
    if (!window.confirm(CONFIRM[action])) return;
    setBusy(true); setMessage(''); setErrors([]);
    try {
      const response = await fetch('/admin/accounts/' + action, {
        method: 'POST', credentials: 'same-origin',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({ commandId, reason, employeeIds: selected }),
      });
      const data = await response.json() as { count?: number; message?: string; errors?: { employeeId: string; message: string }[] };
      if (response.ok) { setSelected([]); setReason(''); setCommandId(crypto.randomUUID()); reload(); return; }
      setCommandId(crypto.randomUUID());
      setMessage(data.message ?? '처리하지 못했습니다.');
      setErrors((data.errors ?? []).map(error => {
        const item = model.items.find(candidate => candidate.employeeId === error.employeeId);
        return `${item ? `${item.employeeNo} ${item.displayName}` : error.employeeId}: ${error.message}`;
      }));
    } catch {
      setMessage('처리 결과를 확인하지 못했습니다. 같은 선택으로 다시 실행하면 한 번만 반영됩니다.');
    } finally { setBusy(false); }
  }

  return (
    <section className="w-page">
      <PageHeader title="사용자 관리" help="사원별 로그인 계정 상태를 보고, 여러 사원의 계정을 만들거나 비밀번호를 초기화합니다. 한 번에 100명까지 처리합니다." />
      <form className="actions" action="/admin/accounts" method="get">
        <label>검색<input name="q" defaultValue={model.q} maxLength={80} placeholder="사번·이름" /></label>
        <label>상태<select name="status" defaultValue={model.status}>
          {model.statuses.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select></label>
        <button type="submit">조회</button>
      </form>
      <table className="w-table">
        <thead><tr><th scope="col">선택</th><th scope="col">사번</th><th scope="col">이름</th><th scope="col">현재 소속</th><th scope="col">계정 상태</th></tr></thead>
        <tbody>
          {model.items.map(item => (
            <tr key={item.employeeId}>
              <td><input type="checkbox" aria-label={`${item.employeeNo} ${item.displayName} 선택`}
                checked={selected.includes(item.employeeId)} onChange={() => toggle(item.employeeId)} /></td>
              <td>{item.employeeNo}</td><td>{item.displayName}</td><td>{item.organizationName ?? '—'}</td>
              <td>{item.label}{item.self ? ' (본인)' : ''}</td>
            </tr>
          ))}
          {model.items.length === 0 && <tr><td colSpan={5}>조건에 맞는 사원이 없습니다.</td></tr>}
        </tbody>
      </table>
      <nav className="actions pagination">
        {model.page > 0 && <a href={`/admin/accounts?q=${encodeURIComponent(model.q)}&status=${model.status}&page=${model.page - 1}`}>이전</a>}
        {model.hasNext && <a href={`/admin/accounts?q=${encodeURIComponent(model.q)}&status=${model.status}&page=${model.page + 1}`}>다음</a>}
      </nav>
      <label>처리 사유<textarea value={reason} maxLength={500} onChange={event => setReason(event.target.value)} /></label>
      <div className="actions">
        <span>{`선택 ${chosen.length}명`}</span>
        <button type="button" disabled={!canCreate} onClick={() => run('create')}>계정 생성</button>
        <button type="button" disabled={!canReset} onClick={() => run('reset')}>비밀번호 초기화</button>
      </div>
      {message && <div role="alert"><p>{message}</p>{errors.map(error => <p key={error}>{error}</p>)}</div>}
    </section>
  );
}
```

- 목록은 페이지당 50명이라 `WorklogSheet` 대신 기존 목록 화면(`RoleManagementPage`)과 같은 표를 쓴다. 표·버튼 클래스는 그 화면의 클래스로 맞춘다.
- 생년월일은 모델에 없으므로 화면에도 나오지 않는다.

- [ ] **Step 4: 화면 연결**

- `IdentityPages.tsx`의 `case 'org/access-list'` 앞에:
  ```tsx
  case 'org/accounts':
    return <AccountsPage model={data.accountDirectory as AccountDirectory} csrf={csrf} reload={reload} />;
  ```
- `App.tsx` `titles`에 `'org/accounts': '사용자 관리',`
- `navigation.ts` 시스템 설정 > 계정·공통코드의 '권한 관리' 다음 줄: `item('accounts', '사용자 관리', '/admin/accounts', admin, 'org/accounts'),`

- [ ] **Step 5: 통과 확인**

Run: `npm --prefix frontend test -- --run src/pages/AccountsPage.test.tsx` 그리고 전체 `npm --prefix frontend test -- --run`, `npm --prefix frontend run build`
Expected: PASS, 빌드 성공(메뉴 개수 단언 테스트가 있으면 함께 고친다)

- [ ] **Step 6: 커밋**

```powershell
git add frontend/src/pages/AccountsPage.tsx frontend/src/pages/AccountsPage.test.tsx frontend/src/pages/IdentityPages.tsx frontend/src/App.tsx frontend/src/navigation.ts
git commit -m "feat: 사용자 관리 화면(상태 필터·선택 계정 생성·초기화)"
```

---

### Task 10: 문서 갱신

**Files:**
- Modify: `docs/design/account-access.md`(사용자 관리·일괄 계정 동작, 경로 표)
- Modify: `docs/superpowers/specs/2026-11-23-opening-prep-design.md`(§1.1·§1.3 끝에 '2026-11-24 구현 반영' 한 줄씩)
- Modify: `docs/adr/0008-employee-account-provisioning.md`(결정에 재직자 일괄 등록·사용자 관리의 계정 생성 한 줄)
- Modify: `docs/development/local.md`(개통 순서: 조직 등록 → 재직자 일괄 등록 → 근무 배정 → 사용자 관리 확인)
- Modify: `frontend/src/pages/CompanySetupGuide.tsx`(기존 재직자는 '재직자 일괄 등록'으로 넣는다는 단계)

- [ ] **Step 1: 문서 반영**

설계 문서에 추가할 문장(§1.1 끝):
"2026-11-24 구현 반영: 저장은 명령 1건(`EMPLOYEE_IMPORT`)과 감사 1건이다. 생년월일은 입력 해시·감사에서 뺀다. 소속 배정 적용일은 서울 기준 오늘이다."

설계 문서에 추가할 문장(§1.3 끝):
"2026-11-24 구현 반영: 일괄 생성은 `ACCOUNT_BULK_PROVISIONED`, 일괄 초기화는 `ACCOUNT_BULK_RESET` 감사 1건으로 남긴다. 본인 계정은 초기화할 수 없고, 한 명이라도 대상이 아니면 아무것도 바꾸지 않는다."

- [ ] **Step 2: 화면 안내 테스트**

`CompanySetupGuide`에 텍스트 단언 테스트가 있으면 새 문구로 고친다. Run: `npm --prefix frontend test -- --run`

- [ ] **Step 3: 커밋**

```powershell
git add docs/design/account-access.md docs/superpowers/specs/2026-11-23-opening-prep-design.md docs/adr/0008-employee-account-provisioning.md docs/development/local.md frontend/src/pages/CompanySetupGuide.tsx
git commit -m "docs: 재직자 일괄 등록과 사용자 관리 반영"
```

---

### Task 11: 전체 회귀와 브라우저 확인

**Files:**
- Modify: `scripts/verify-customer-opening-browser.cjs`(일괄 등록 단계 추가)
- Create: `docs/quality/employee-import-accounts-20261124.md`

- [ ] **Step 1: 개통 브라우저 시나리오 보강**

`scripts/verify-customer-opening-browser.cjs`의 PC·모바일 흐름에서 직원 한 명을 입사 확정 대신 재직자 일괄 등록으로 넣는다. 순서: 붙여넣기 칸에 TSV 입력 → 검증 → 사유 → 저장 → 결과 "등록 1명 · 계정 1개" → 사번/사번 로그인 → 최초 접속 → 새 비밀번호 로그인 → 출근. 사용자 관리에서 그 사원의 상태가 "사용 중"인지 본다. `node --check`로 문법을 확인한다.

- [ ] **Step 2: 전체 회귀(새 테스트 DB)**

Run: `.\mvnw.cmd verify`
Expected: 이번 변경 관련 실패 0. 환경 때문에 실행되지 않는 테스트(고정 이미지 빌드, 날짜 의존 `LeaveWorkflowIT`)는 결과 문서에 이름과 원인을 적는다.

Run: `npm --prefix frontend test -- --run`, `npm --prefix frontend run build`
Expected: PASS

- [ ] **Step 3: 브라우저 확인(PC 1366px, 모바일 390px)**

로컬 앱을 테스트 DB에 붙여 다음을 확인하고 화면을 캡처한다.
- 엑셀 형식 TSV 3행(사번 1개 비움, 날짜 형식 3가지) 붙여넣기 → 검증 오류 셀 강조 → 셀 수정 → 검증 정상 → 저장 → 결과·다음 단계 링크
- 같은 저장을 다시 눌러도 사원이 늘지 않음(새 명령 식별자 발급 확인)
- 등록한 직원이 모바일에서 최초 접속 → 출근
- 사용자 관리: 상태 필터, 2명 선택 계정 생성, 잠긴 직원 초기화, 본인 행 초기화 버튼 비활성

- [ ] **Step 4: 결과 기록과 커밋**

`docs/quality/employee-import-accounts-20261124.md`에 실행 명령, 테스트 수, 화면 확인 항목, 실행하지 못한 검증을 실제 결과로만 적는다.

```powershell
git add scripts/verify-customer-opening-browser.cjs docs/quality/employee-import-accounts-20261124.md
git commit -m "test: 재직자 일괄 등록·사용자 관리 회귀와 PC·모바일 확인 결과 기록"
```

---

## 자체 점검 결과

- **설계 대비:** §1.1(붙여넣기·셀 수정·검증·전체 취소·결과·다음 단계·양식·공개 데모 403) → Task 4~6. §1.3(목록 열·검색·필터·생성·초기화·100명·사유) → Task 7~9. §2.2(명령 1건·재시도·사번 배치 발급·행 규칙·날짜 형식·엑셀 일련번호 안내) → Task 1~3. §3(생년월일 미노출, 경쟁 재검증, 전체 취소 422) → Task 3, 7. §4 경로 6개 → Task 4, 7, 8.
- **설계와 다르게 정한 점(보고 대상):**
  - 설계 §2.2의 "감사 1건(행 수, 사번 목록)"은 그대로 따르되, 감사 `after_state`에 결과(`Result`)를 넣어 재시도 응답에 쓴다.
  - 사용자 관리 목록은 행이 50명 단위라 `WorklogSheet` 대신 일반 표를 쓴다. 설계에 시트 지정은 없다.
  - 일괄 계정 동작은 사원 버전을 올리지 않는다. 사원 상세의 단건 초기화는 계속 버전을 확인한다.
- **이름 일관성:** `EmployeeImportRows.Row/Parsed/FieldError`, `EmployeeImportService.Preview/RowResult/Form/Result/ImportProblem`, `AccountDirectoryService.Directory/Item/Option/BulkForm/BulkResult/EmployeeError/BulkProblem`, 화면 키 `employeeImport`·`accountDirectory`, 뷰 이름 `org/employee-import`·`org/accounts`를 모든 Task에서 같게 썼다.
- **확인이 필요한 기존 API(실행 시 맞춤):** `EmployeeAdminMapper.requireOpen` 시그니처, `AccountService`의 공개 데모 사원 보호 클래스 이름, `AccountStatus.NONE/PREVIOUS_INVITATION` 라벨, `WorklogSheet`의 `count` 표시 형식. 각 Task에 확인 지점을 적었다.
