# 계정 자동 생성·최초 접속 구현 계획 (개통 준비 계획 1/3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 담당자의 초대 메일 없이, 입사 확정 때 계정을 자동으로 만든다. 직원은 `사번/사번`으로 들어와 생년월일을 확인하고 새 비밀번호를 정한다. 담당자는 계정 화면에서 비밀번호를 초기화한다.

**Architecture:**
- 계정 테이블에 '최초 접속 대기' 상태(`activation_expires_at`, `activation_failures`)를 추가한다.
- 계정 생성과 초기화는 일반 업무 DB 역할(`tna_app`)이 맡는다. 비밀번호 저장은 지금처럼 인증 DB 역할(`tna_auth`)만 한다.
- 최초 접속은 기존 `InvitationAcceptance`와 같은 구조(인증 DataSource, 고객사 advisory lock, 감사 기록)를 따른다.
- 로그인 실패 처리기는 비밀번호가 사번과 같을 때 플래시 속성과 함께 `/first-login`으로 보낸다.

**Tech Stack:** Java 25, Spring Boot 4.1 / Spring Security, MyBatis, PostgreSQL 18(Flyway, RLS), React 19 + Vitest, JUnit 5 + Testcontainers(`DatabaseFixture`)

**설계 문서:** [2026-11-23-opening-prep-design.md](../specs/2026-11-23-opening-prep-design.md) §1.2, §2.1, §3

**이 계획의 범위 밖:**
- 재직자 일괄 등록과 사용자 관리 화면 → 계획 2
- 공휴일 달력 → 계획 3. 설계 문서의 `V40` 중 달력 부분은 계획 3에서 `V41`로 나눈다.

---

## 파일 구조

| 파일 | 책임 |
| --- | --- |
| Create `src/main/resources/db/migration/V40__account_provisioning.sql` | 계정 대기 열과 권한, 인증 역할의 생년월일 조회, 입사 예정 생년월일 열 |
| Create `src/main/java/kr/tna/org/AccountStatus.java` | 계정 상태 계산(순수 함수) |
| Create `src/main/java/kr/tna/org/AccountProvisioning.java` | 대기 계정 생성과 초기화(앱 역할, 호출자 트랜잭션 안에서) |
| Modify `src/main/java/kr/tna/org/AccountAdminMapper.java` | 대기 계정 INSERT, 초기화 UPDATE, 계정 행에 대기 열 추가 |
| Modify `src/main/java/kr/tna/org/AccountData.java` | `Account`에 대기 열 추가, `InvitationForm`을 `ResetForm`으로 교체 |
| Modify `src/main/java/kr/tna/org/AccountService.java` | 초대 발급 제거, `reset` 추가, 대기 계정의 역할 변경 허용 |
| Modify `src/main/java/kr/tna/org/AccountAdminController.java` | 초대 경로 제거, 초기화 경로 추가, 화면 모델 교체 |
| Modify `src/main/java/kr/tna/org/RoleMembershipService.java`, `RoleDirectoryMapper.java` | 권한 관리 시트에서 대기 계정 허용 |
| Modify `src/main/java/kr/tna/common/auth/AuthStore.java` | 로그인 조건 `activation_expires_at IS NULL` |
| Create `src/main/java/kr/tna/common/auth/FirstLoginService.java` | 생년월일 확인, 실패 횟수와 잠금, 비밀번호 저장(인증 역할) |
| Create `src/main/java/kr/tna/common/auth/FirstLoginController.java` | `GET/POST /first-login` |
| Modify `src/main/java/kr/tna/common/config/SecurityConfiguration.java` | 공개 경로, 실패 처리기의 최초 접속 이동 |
| Modify `src/main/java/kr/tna/common/web/ReactPageViewResolver.java` | `first-login` 화면, `activated` 플래그, `org/access` 모델 키 |
| Modify `src/main/java/kr/tna/org/EmployeeOnboardingData.java`, `EmployeeOnboardingMapper.java`, `EmployeeOnboardingService.java` | 생년월일 입력, 확정 시 인사정보와 계정 생성 |
| Modify `frontend/src/pages/AuthPages.tsx`, `frontend/src/App.tsx` | 최초 접속 화면, 로그인 안내 문구 |
| Modify `frontend/src/pages/OnboardingPage.tsx` | 생년월일 칸, 확정 후 안내 |
| Modify `frontend/src/pages/AdminPages.tsx` | 계정·권한 화면: 초대 폼 → 상태와 초기화 |
| Create `docs/adr/0008-employee-account-provisioning.md` | 결정 기록 |

---

### Task 1: V40 마이그레이션과 권한 검사

**Files:**
- Create: `src/main/resources/db/migration/V40__account_provisioning.sql`
- Create: `src/test/java/kr/tna/AccountProvisioningSchemaIT.java`
- Modify: `src/test/java/kr/tna/MigrationRunnerIT.java:31-35` (39 → 40)

- [ ] **Step 1: 실패하는 스키마 테스트 작성**

```java
package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AccountProvisioningSchemaIT {
    private final DatabaseFixture f = DatabaseFixture.start();
    private final JdbcTemplate admin = new JdbcTemplate(f.adminDataSource());
    private UUID tenant, employee;

    @BeforeEach void seed() {
        tenant = UUID.randomUUID(); employee = UUID.randomUUID();
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 계정 고객사')",
                tenant, "PROV-" + tenant);
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'26001','합성 사원',DATE '2020-01-01')",
                tenant, employee);
        admin.update("INSERT INTO tna.employee_personnel(tenant_id,employee_id,birth_date,version,updated_by) VALUES (?,?,DATE '1990-05-12',1,?)",
                tenant, employee, UUID.randomUUID());
    }

    @Test void appCreatesPendingAccountWithoutEmailOrPassword() {
        UUID account = UUID.randomUUID();
        as(f.appDataSource(), db -> db.update("""
                INSERT INTO tna.tenant_account(tenant_id,id,employee_id,active,activation_expires_at)
                VALUES (?,?,?,false,?)""", tenant, account, employee, Timestamp.from(Instant.now().plusSeconds(3600))));
        assertNull(admin.queryForObject("SELECT email FROM tna.tenant_account WHERE id=?", String.class, account));
        assertEquals(0, admin.queryForObject("SELECT activation_failures FROM tna.tenant_account WHERE id=?", Integer.class, account));
        as(f.appDataSource(), db -> assertEquals(1, db.update(
                "UPDATE tna.tenant_account SET activation_expires_at=now(),activation_failures=0 WHERE tenant_id=? AND id=?", tenant, account)));
        assertThrows(DataAccessException.class, () -> as(f.appDataSource(), db -> db.update(
                "UPDATE tna.tenant_account SET password_hash='x' WHERE tenant_id=? AND id=?", tenant, account)));
        assertThrows(DataAccessException.class, () -> as(f.appDataSource(), db -> db.update(
                "UPDATE tna.tenant_account SET active=true WHERE tenant_id=? AND id=?", tenant, account)));
    }

    @Test void authReadsBirthDateOnlyInsideTenantAndCountsFailures() {
        UUID account = UUID.randomUUID();
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,active,activation_expires_at) VALUES (?,?,?,false,now()+interval '1 day')",
                tenant, account, employee);
        as(f.authDataSource(), db -> {
            assertEquals("1990-05-12", db.queryForObject(
                    "SELECT birth_date::text FROM tna.employee_personnel WHERE tenant_id=? AND employee_id=?", String.class, tenant, employee));
            assertEquals(1, db.update("UPDATE tna.tenant_account SET activation_failures=activation_failures+1 WHERE tenant_id=? AND id=?", tenant, account));
        });
        assertEquals(0, asTenant(f.authDataSource(), UUID.randomUUID(), db -> db.queryForObject(
                "SELECT count(*) FROM tna.employee_personnel", Integer.class)));
        assertThrows(DataAccessException.class, () -> admin.update(
                "UPDATE tna.tenant_account SET activation_failures=6 WHERE id=?", account));
    }

    @Test void onboardingStoresOptionalBirthDate() {
        assertEquals(1, admin.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='tna' AND table_name='employee_onboarding' AND column_name='birth_date'""", Integer.class));
        assertTrue(admin.queryForObject("SELECT has_column_privilege('tna_app','tna.employee_onboarding','birth_date','INSERT')", Boolean.class));
        assertTrue(admin.queryForObject("SELECT has_column_privilege('tna_app','tna.employee_onboarding','birth_date','UPDATE')", Boolean.class));
    }

    private void as(DataSource source, Consumer<JdbcTemplate> work) { asTenant(source, tenant, db -> { work.accept(db); return 0; }); }

    private <T> T asTenant(DataSource source, UUID scope, java.util.function.Function<JdbcTemplate, T> work) {
        var db = new JdbcTemplate(source);
        return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status -> {
            db.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, scope.toString());
            return work.apply(db);
        });
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=AccountProvisioningSchemaIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: FAIL. `activation_expires_at` 열이 없어 `column ... does not exist`가 난다.

- [ ] **Step 3: 마이그레이션 작성**

```sql
-- 담당자 등록·입사 확정 때 만드는 계정의 최초 접속 대기 상태
ALTER TABLE tna.tenant_account
    ALTER COLUMN email DROP NOT NULL,
    ADD COLUMN activation_expires_at timestamptz(3),
    ADD COLUMN activation_failures smallint NOT NULL DEFAULT 0,
    ADD CONSTRAINT account_activation_failures_range CHECK (activation_failures BETWEEN 0 AND 5);

GRANT SELECT (activation_expires_at, activation_failures) ON tna.tenant_account TO tna_app;
GRANT INSERT (activation_expires_at) ON tna.tenant_account TO tna_app;
GRANT UPDATE (activation_expires_at, activation_failures) ON tna.tenant_account TO tna_app;
GRANT UPDATE (activation_expires_at, activation_failures) ON tna.tenant_account TO tna_auth;

-- 최초 접속의 생년월일 확인은 인증 역할이 고객사 범위 안에서만 읽는다.
GRANT SELECT (tenant_id, employee_id, birth_date) ON tna.employee_personnel TO tna_auth;
CREATE POLICY auth_birth_date_read ON tna.employee_personnel FOR SELECT TO tna_auth
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

-- 입사 예정 단계에서 받는 생년월일(기존 행 때문에 NULL 허용, 필수 여부는 명령에서 검사)
ALTER TABLE tna.employee_onboarding
    ADD COLUMN birth_date date,
    ADD CONSTRAINT onboarding_birth_date_range CHECK (birth_date IS NULL OR birth_date >= DATE '1900-01-01');
GRANT INSERT (birth_date), UPDATE (birth_date) ON tna.employee_onboarding TO tna_app;
```

- [ ] **Step 4: 마이그레이션 버전 테스트 갱신**

`MigrationRunnerIT.java`에서 `assertEquals(39, ...)`를 `assertEquals(40, ...)`로, `WHERE version = '39'`를 `WHERE version = '40'`으로 바꾼다.

- [ ] **Step 5: 통과 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=AccountProvisioningSchemaIT,MigrationRunnerIT,AccountAccessSchemaIT,OrgSchemaIT,DatabaseFoundationIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: PASS. 기존 스키마 테스트(`AccountAccessSchemaIT`, `OrgSchemaIT`)도 그대로 통과해야 한다. 앱 역할의 `password_hash`·`active` 변경은 계속 막혀 있다.

- [ ] **Step 6: 커밋**

```powershell
git add src/main/resources/db/migration/V40__account_provisioning.sql src/test/java/kr/tna/AccountProvisioningSchemaIT.java src/test/java/kr/tna/MigrationRunnerIT.java
git commit -m "feat: 계정 최초 접속 대기 상태와 생년월일 확인 권한 추가"
```

---

### Task 2: 계정 상태 계산

**Files:**
- Create: `src/main/java/kr/tna/org/AccountStatus.java`
- Test: `src/test/java/kr/tna/org/AccountStatusTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.tna.org;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccountStatusTest {
    private static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");

    @Test void ordersConditionsFromMissingAccountToPendingActivation() {
        assertEquals(AccountStatus.NONE, AccountStatus.of(false, false, null, 0, true, NOW));
        assertEquals(AccountStatus.ACTIVE, AccountStatus.of(true, true, null, 0, true, NOW));
        assertEquals(AccountStatus.PREVIOUS_INVITATION, AccountStatus.of(true, false, null, 0, true, NOW));
        assertEquals(AccountStatus.BIRTH_DATE_REQUIRED, AccountStatus.of(true, false, NOW.plusSeconds(60), 5, false, NOW));
        assertEquals(AccountStatus.LOCKED, AccountStatus.of(true, false, NOW.plusSeconds(60), 5, true, NOW));
        assertEquals(AccountStatus.EXPIRED, AccountStatus.of(true, true, NOW, 0, true, NOW));
        assertEquals(AccountStatus.PENDING, AccountStatus.of(true, true, NOW.plusSeconds(1), 4, true, NOW));
    }

    @Test void labelsAreWhatTheAdministratorReads() {
        assertEquals("최초 접속 대기", AccountStatus.PENDING.label());
        assertEquals("잠김(5회 실패)", AccountStatus.LOCKED.label());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dtest=AccountStatusTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL. `AccountStatus`가 없어 컴파일 오류가 난다.

- [ ] **Step 3: 구현**

```java
package kr.tna.org;

import java.time.Instant;

/** 사용자 관리·계정 화면이 같은 순서로 읽는 계정 상태. */
public enum AccountStatus {
    NONE("계정 없음"), ACTIVE("사용 중"), PREVIOUS_INVITATION("이전 초대 대기"),
    BIRTH_DATE_REQUIRED("생년월일 필요"), LOCKED("잠김(5회 실패)"), EXPIRED("기간 만료"), PENDING("최초 접속 대기");

    public static final int MAX_FAILURES = 5;
    private final String label;
    AccountStatus(String label) { this.label = label; }
    public String label() { return label; }

    public static AccountStatus of(boolean exists, boolean active, Instant activationExpiresAt, int failures,
                                   boolean birthDateKnown, Instant now) {
        if (!exists) return NONE;
        if (activationExpiresAt == null) return active ? ACTIVE : PREVIOUS_INVITATION;
        if (!birthDateKnown) return BIRTH_DATE_REQUIRED;
        if (failures >= MAX_FAILURES) return LOCKED;
        if (!activationExpiresAt.isAfter(now)) return EXPIRED;
        return PENDING;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dtest=AccountStatusTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS

- [ ] **Step 5: 커밋**

```powershell
git add src/main/java/kr/tna/org/AccountStatus.java src/test/java/kr/tna/org/AccountStatusTest.java
git commit -m "feat: 계정 상태를 대기·잠김·만료 순서로 계산"
```

---

### Task 3: 대기 계정 생성과 초기화

**Files:**
- Create: `src/main/java/kr/tna/org/AccountProvisioning.java`
- Modify: `src/main/java/kr/tna/org/AccountAdminMapper.java`
- Modify: `src/main/java/kr/tna/org/AccountData.java` (`Account` 열 추가)
- Modify: `src/main/java/kr/tna/org/AccountService.java:130-134` (`account()` 매핑)
- Test: `src/test/java/kr/tna/org/AccountProvisioningTest.java`

- [ ] **Step 1: 실패하는 단위 테스트 작성(만료 시각 규칙)**

```java
package kr.tna.org;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AccountProvisioningTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T03:00:00Z"), ZoneOffset.UTC);

    @Test void windowStartsAtLaterOfNowAndSeoulJoiningMidnight() {
        assertEquals(Instant.parse("2026-12-01T03:00:00Z"), AccountProvisioning.expiresAt(LocalDate.of(2019, 3, 4), clock));
        assertEquals(Instant.parse("2026-12-07T15:00:00Z"), AccountProvisioning.expiresAt(LocalDate.of(2026, 12, 1), clock));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dtest=AccountProvisioningTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL (컴파일 오류)

- [ ] **Step 3: 매퍼에 대기 계정 SQL 추가**

`AccountAdminMapper.java`에서 `account()` 조회와 `AccountRow`를 바꾸고 메서드 두 개를 더한다:

```java
    @Select("""
            SELECT id,employee_id,email,active,credential_set,auth_version,activation_expires_at,activation_failures
            FROM tna.tenant_account WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId}
            """)
    AccountRow account(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Insert("""
            INSERT INTO tna.tenant_account(tenant_id,id,employee_id,active,activation_expires_at)
            VALUES (#{tenantId},#{accountId},#{employeeId},false,#{expiresAt})
            """)
    int insertPendingAccount(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId,
                             @Param("employeeId") UUID employeeId, @Param("expiresAt") Instant expiresAt);

    @Update("""
            UPDATE tna.tenant_account
            SET activation_expires_at=#{expiresAt},activation_failures=0,auth_version=auth_version+1
            WHERE tenant_id=#{tenantId} AND id=#{accountId}
            """)
    int reopenActivation(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId,
                         @Param("expiresAt") Instant expiresAt);

    record AccountRow(UUID id, UUID employeeId, String email, boolean active, boolean credentialSet,
                      long authVersion, Instant activationExpiresAt, int activationFailures) { }
```

- [ ] **Step 4: `AccountData.Account`와 `AccountService.account()` 갱신**

`AccountData.java`:

```java
    public record Account(UUID id, UUID employeeId, String email, boolean active, boolean credentialSet,
                          long authVersion, Set<String> roles, Instant activationExpiresAt, int activationFailures) { }
```

`AccountService.java`의 `account()`:

```java
    private Account account(UUID tenantId, UUID employeeId) {
        var row = mapper.account(tenantId, employeeId);
        return row == null ? null : new Account(row.id(), row.employeeId(), row.email(), row.active(),
                row.credentialSet(), row.authVersion(), Set.copyOf(mapper.roles(tenantId, row.id())),
                row.activationExpiresAt(), row.activationFailures());
    }
```

- [ ] **Step 5: `AccountProvisioning` 구현**

```java
package kr.tna.org;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 호출자의 고객사 명령 트랜잭션 안에서 대기 계정을 만들거나 다시 연다. 비밀번호는 다루지 않는다. */
@Component
public final class AccountProvisioning {
    static final Duration WINDOW = Duration.ofDays(7);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final AccountAdminMapper mapper;
    private final Clock clock;

    public AccountProvisioning(AccountAdminMapper mapper, Clock clock) { this.mapper = mapper; this.clock = clock; }

    public UUID provision(UUID tenantId, UUID employeeId, LocalDate joinedOn) {
        if (mapper.account(tenantId, employeeId) != null) throw OrgException.conflict("이미 계정이 있는 사원입니다.");
        UUID accountId = UUID.randomUUID();
        one(mapper.insertPendingAccount(tenantId, accountId, employeeId, expiresAt(joinedOn, clock)));
        one(mapper.setRole(tenantId, accountId, "EMPLOYEE", true));
        return accountId;
    }

    public void reopen(UUID tenantId, UUID accountId) {
        one(mapper.reopenActivation(tenantId, accountId, clock.instant().plus(WINDOW).truncatedTo(ChronoUnit.MILLIS)));
    }

    static Instant expiresAt(LocalDate joinedOn, Clock clock) {
        Instant now = clock.instant(), joining = joinedOn.atStartOfDay(SEOUL).toInstant();
        return (joining.isAfter(now) ? joining : now).plus(WINDOW).truncatedTo(ChronoUnit.MILLIS);
    }

    private static void one(int count) {
        if (count != 1) throw OrgException.conflict("다른 변경이 반영되었습니다. 최신 내용을 확인해 주세요.");
    }
}
```

- [ ] **Step 6: 통과 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dtest=AccountProvisioningTest,AccountStatusTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS. 컴파일 오류가 나면 `new Account(`를 쓰는 곳을 `rg "new Account\(" src`로 찾아 두 인자를 덧붙인다.

- [ ] **Step 7: 커밋**

```powershell
git add src/main/java/kr/tna/org/AccountProvisioning.java src/main/java/kr/tna/org/AccountAdminMapper.java src/main/java/kr/tna/org/AccountData.java src/main/java/kr/tna/org/AccountService.java src/test/java/kr/tna/org/AccountProvisioningTest.java
git commit -m "feat: 입사일 기준 7일 동안 유효한 대기 계정 생성과 재개"
```

---

### Task 4: 대기 중인 계정의 로그인 차단

**Files:**
- Modify: `src/main/java/kr/tna/common/auth/AuthStore.java:47`
- Test: `src/test/java/kr/tna/FirstLoginIT.java` (Task 5와 같은 파일. 여기서는 로그인 테스트만 작성)

- [ ] **Step 1: 실패하는 통합 테스트 작성**

```java
package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.UUID;
import kr.tna.common.auth.*;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("local")
class FirstLoginIT {
    static final String OLD = "Synthetic first login old 43!", NEW = "Synthetic first login new 43!";
    @Autowired CompanyAuthenticationProvider provider;
    @Autowired FirstLoginService firstLogin;
    JdbcTemplate db; UUID tenant, employee, account; String company;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f = DatabaseFixture.start(); p.add("spring.datasource.url", f::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER); p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", f::jdbcUrl); p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER); p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void seed() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID(); employee = UUID.randomUUID(); account = UUID.randomUUID();
        company = ("FIRST-" + tenant).toUpperCase(java.util.Locale.ROOT);
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 최초 접속')", tenant, company);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'2019031','합성 사원',DATE '2019-03-04')", tenant, employee);
        db.update("INSERT INTO tna.employee_personnel(tenant_id,employee_id,birth_date,version,updated_by) VALUES (?,?,DATE '1990-05-12',1,?)", tenant, employee, UUID.randomUUID());
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,password_hash,active) VALUES (?,?,?,?,true)",
                tenant, account, employee, PasswordPolicy.passwordEncoder().encode(OLD));
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')", tenant, account);
    }

    @Test void reopenedAccountRejectsPreviousPassword() {
        assertDoesNotThrow(() -> login("2019031", OLD));
        db.update("UPDATE tna.tenant_account SET activation_expires_at=now()+interval '7 days' WHERE id=?", account);
        assertThrows(AuthenticationException.class, () -> login("2019031", OLD));
    }

    void login(String employeeNo, String password) {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setParameter("companyCode", company);
        var token = UsernamePasswordAuthenticationToken.unauthenticated(employeeNo, password);
        token.setDetails(new CompanyAuthenticationDetails(request));
        provider.authenticate(token);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=FirstLoginIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: FAIL. `FirstLoginService`가 없어 컴파일 오류가 난다. Step 3을 하기 전에 Task 5의 빈 클래스(`public final class FirstLoginService {}`에 `@Service`)를 만들어 두면, 두 번째 단언에서 FAIL이 나는 것을 확인할 수 있다.

- [ ] **Step 3: 로그인 조건 추가**

`AuthStore.java`의 로그인 쿼리 `WHERE` 줄을 다음으로 바꾼다:

```java
                    WHERE a.tenant_id = ? AND %s = ? AND a.active AND a.activation_expires_at IS NULL AND e.active
```

- [ ] **Step 4: 통과 확인**

Run: 같은 명령. Expected: PASS

- [ ] **Step 5: 커밋**

```powershell
git add src/main/java/kr/tna/common/auth/AuthStore.java src/test/java/kr/tna/FirstLoginIT.java src/main/java/kr/tna/common/auth/FirstLoginService.java
git commit -m "feat: 최초 접속·초기화 대기 계정의 기존 비밀번호 로그인 차단"
```

---

### Task 5: 최초 접속 서비스(생년월일 확인·잠금·비밀번호 저장)

**Files:**
- Modify: `src/main/java/kr/tna/common/auth/FirstLoginService.java` (Task 4의 빈 클래스 채우기)
- Test: `src/test/java/kr/tna/FirstLoginIT.java` (테스트 추가)

- [ ] **Step 1: 실패하는 테스트 추가**

`FirstLoginIT`에 다음 테스트와 도우미를 추가한다:

```java
    void pending() {
        db.update("UPDATE tna.tenant_account SET active=false,password_hash=NULL,activation_expires_at=now()+interval '7 days' WHERE id=?", account);
    }
    int failures() { return db.queryForObject("SELECT activation_failures FROM tna.tenant_account WHERE id=?", Integer.class, account); }
    void rejected(Runnable action) { assertEquals(422, assertThrows(OrgException.class, action::run).status()); }
    void activate(LocalDate birth, String password) { firstLogin.activate(company, "2019031", birth, password, "198.51.100." + failures()); }

    @Test void matchingBirthDateSetsPasswordAndEndsPendingState() {
        pending();
        activate(LocalDate.of(1990, 5, 12), NEW);
        assertDoesNotThrow(() -> login("2019031", NEW));
        assertNull(db.queryForObject("SELECT activation_expires_at FROM tna.tenant_account WHERE id=?", java.sql.Timestamp.class, account));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='ACCOUNT_ACTIVATED'", Integer.class, tenant));
        rejected(() -> activate(LocalDate.of(1990, 5, 12), NEW));
    }

    @Test void wrongBirthDateCountsAndLocksAtFive() {
        pending();
        for (int i = 1; i <= 5; i++) { rejected(() -> activate(LocalDate.of(1990, 5, 13), NEW)); assertEquals(i, failures()); }
        rejected(() -> activate(LocalDate.of(1990, 5, 12), NEW));
        assertEquals(5, failures());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='ACCOUNT_LOCKED'", Integer.class, tenant));
        String audit = db.queryForObject("SELECT string_agg(before_state::text||after_state::text||input_hash,'') FROM tna.org_audit_event WHERE tenant_id=?", String.class, tenant);
        assertFalse(audit.contains("1990-05-1"));
    }

    @Test void expiredMissingBirthDateFormerEmployeeAndEmployeeNumberPasswordAreRejected() {
        pending();
        rejected(() -> activate(LocalDate.of(1990, 5, 12), "2019031"));
        db.update("UPDATE tna.tenant_account SET activation_expires_at=now()-interval '1 second' WHERE id=?", account);
        rejected(() -> activate(LocalDate.of(1990, 5, 12), NEW));
        pending(); db.update("UPDATE tna.employee_personnel SET birth_date=NULL WHERE employee_id=?", employee);
        rejected(() -> activate(LocalDate.of(1990, 5, 12), NEW));
        db.update("UPDATE tna.employee_personnel SET birth_date=DATE '1990-05-12' WHERE employee_id=?", employee);
        db.update("UPDATE tna.employee SET left_on=DATE '2020-01-01' WHERE id=?", employee);
        rejected(() -> activate(LocalDate.of(1990, 5, 12), NEW));
        assertEquals(0, failures());
    }
```

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=FirstLoginIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: FAIL. `activate`가 없어 컴파일 오류가 난다.

- [ ] **Step 3: 구현**

```java
package kr.tna.common.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.org.AccountStatus;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** 사번·생년월일로 대기 계정의 첫 비밀번호를 정한다. 실패 이유는 응답에 드러내지 않는다. */
@Service
public final class FirstLoginService {
    private static final Logger LOG = LoggerFactory.getLogger(FirstLoginService.class);
    static final String REJECTED = "입력한 정보가 맞지 않거나 최초 접속 기간이 지났습니다. 담당자에게 문의하세요.";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final LoginRateLimiter limiter;

    public FirstLoginService(@Qualifier("authDataSource") DataSource source, PasswordEncoder encoder, Clock clock) {
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.encoder = encoder;
        this.clock = clock;
        limiter = new LoginRateLimiter(clock);
    }

    public void activate(String companyCode, String employeeNo, LocalDate birthDate, String password, String remoteAddress) {
        String company = companyCode == null ? "" : companyCode.strip().toUpperCase(Locale.ROOT);
        String number = employeeNo == null ? "" : employeeNo.strip();
        if (company.length() > 80 || number.length() > 40) throw rejected();
        if (!limiter.tryAcquire(remoteAddress == null ? "" : remoteAddress, company, number)) {
            LOG.warn("event=first_login outcome=rate_limited");
            throw new OrgException(429, "최초 접속 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (company.isBlank() || number.isBlank() || number.contains("@") || birthDate == null
                || !PasswordPolicy.isValid(password) || password.strip().equals(number)) throw rejected();
        Outcome outcome;
        try {
            String hash = encoder.encode(password);
            outcome = transaction.execute(status -> attempt(company, number, birthDate, hash));
        } catch (DataAccessException | TransactionException exception) {
            LOG.error("event=first_login outcome=storage_unavailable");
            throw new OrgException(503, "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (outcome != Outcome.ACTIVATED) throw rejected();
    }

    private Outcome attempt(String company, String number, LocalDate birthDate, String hash) {
        var tenants = jdbc.query("SELECT id FROM platform.tenant_registry WHERE company_code=? AND active",
                (row, index) -> row.getObject("id", UUID.class), company);
        if (tenants.size() != 1) return Outcome.REJECTED;
        UUID tenant = tenants.getFirst();
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))", Object.class, tenant);
        var now = clock.instant();
        var today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var rows = jdbc.query("""
                SELECT a.id,a.auth_version,a.activation_failures,p.birth_date
                FROM tna.tenant_account a
                JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
                LEFT JOIN tna.employee_personnel p ON p.tenant_id=a.tenant_id AND p.employee_id=a.employee_id
                WHERE a.tenant_id=? AND e.employee_no=? AND a.activation_expires_at>? AND e.active
                  AND e.joined_on<=? AND (e.left_on IS NULL OR e.left_on>=?)
                """, (row, index) -> new Pending(row.getObject("id", UUID.class), row.getLong("auth_version"),
                row.getInt("activation_failures"), row.getObject("birth_date", LocalDate.class)),
                tenant, number, Timestamp.from(now), today, today);
        if (rows.size() != 1) return Outcome.REJECTED;
        Pending pending = rows.getFirst();
        if (pending.birthDate() == null || pending.failures() >= AccountStatus.MAX_FAILURES) return Outcome.REJECTED;
        if (!pending.birthDate().equals(birthDate)) {
            int failures = pending.failures() + 1;
            jdbc.update("UPDATE tna.tenant_account SET activation_failures=? WHERE tenant_id=? AND id=?", failures, tenant, pending.id());
            audit(tenant, pending.id(), failures == AccountStatus.MAX_FAILURES ? "ACCOUNT_LOCKED" : "ACCOUNT_ACTIVATION_FAILED",
                    "{\"failures\":" + pending.failures() + "}", "{\"failures\":" + failures + "}");
            return Outcome.REJECTED;
        }
        int changed = jdbc.update("""
                UPDATE tna.tenant_account
                SET password_hash=?,active=true,activation_expires_at=NULL,activation_failures=0,auth_version=auth_version+1
                WHERE tenant_id=? AND id=? AND auth_version=? AND activation_expires_at IS NOT NULL
                """, hash, tenant, pending.id(), pending.authVersion());
        if (changed != 1) return Outcome.REJECTED;
        audit(tenant, pending.id(), "ACCOUNT_ACTIVATED", "{\"pending\":true}", "{\"pending\":false,\"active\":true}");
        return Outcome.ACTIVATED;
    }

    private void audit(UUID tenant, UUID account, String action, String before, String after) {
        UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tna.org_audit_event
                (tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,input_hash,
                 reason,before_state,after_state,request_trace_id)
                VALUES (?,?,?,?,?,'ACCOUNT',?,?,'최초 접속',?::jsonb,?::jsonb,?)
                """, tenant, event, event, account, action, account,
                OrgCommands.fingerprint(action, tenant, event, account), before, after, UUID.randomUUID());
    }

    private OrgException rejected() {
        LOG.warn("event=first_login outcome=rejected");
        return OrgException.invalid(REJECTED);
    }

    private enum Outcome { ACTIVATED, REJECTED }
    private record Pending(UUID id, long authVersion, int failures, LocalDate birthDate) { }
}
```

- [ ] **Step 4: 통과 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=FirstLoginIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: PASS. 생년월일이 틀리면 실패 횟수 증가만 커밋되고, 비밀번호는 바뀌지 않는다.

- [ ] **Step 5: 커밋**

```powershell
git add src/main/java/kr/tna/common/auth/FirstLoginService.java src/test/java/kr/tna/FirstLoginIT.java
git commit -m "feat: 사번·생년월일 확인으로 첫 비밀번호 설정, 5회 실패 시 잠금"
```

---

### Task 6: 최초 접속 화면과 로그인 연결

**Files:**
- Create: `src/main/java/kr/tna/common/auth/FirstLoginController.java`
- Modify: `src/main/java/kr/tna/common/config/SecurityConfiguration.java:63-64, 119-122`
- Modify: `src/main/java/kr/tna/common/web/ReactPageViewResolver.java:32, 106`
- Modify: `frontend/src/pages/AuthPages.tsx`, `frontend/src/App.tsx:59-68, 115-126`
- Test: `src/test/java/kr/tna/FirstLoginWebIT.java`, `frontend/src/pages/AuthPages.test.tsx`(없으면 생성)

- [ ] **Step 1: 실패하는 웹 테스트 작성**

```java
package kr.tna;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class FirstLoginWebIT {
    static final String PAGE = "application/vnd.worklog.page+json";
    @Autowired MockMvc mvc;
    String company;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) { FirstLoginIT.database(p); }

    @BeforeEach void seed() {
        var db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        UUID tenant = UUID.randomUUID(), employee = UUID.randomUUID();
        company = ("WEB-" + tenant).toUpperCase(java.util.Locale.ROOT);
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 화면')", tenant, company);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'2019031','합성 사원',DATE '2019-03-04')", tenant, employee);
        db.update("INSERT INTO tna.employee_personnel(tenant_id,employee_id,birth_date,version,updated_by) VALUES (?,?,DATE '1990-05-12',1,?)", tenant, employee, UUID.randomUUID());
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,active,activation_expires_at) VALUES (?,?,?,false,now()+interval '7 days')", tenant, UUID.randomUUID(), employee);
    }

    @Test void employeeNumberAsPasswordMovesToFirstLoginWithoutQueryParameters() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/login").session(session).with(csrf()).param("companyCode", company)
                        .param("email", "2019031").param("password", "2019031"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/first-login"));
        mvc.perform(get("/first-login").session(session).accept(PAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value("first-login"))
                .andExpect(jsonPath("$.data.companyCode").value(company))
                .andExpect(jsonPath("$.data.employeeNo").value("2019031"));
    }

    @Test void otherWrongPasswordsStayOnLogin() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("companyCode", company).param("email", "2019031").param("password", "wrong"))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test void completionRedirectsToLoginAndFailureKeepsSameMessage() throws Exception {
        mvc.perform(post("/first-login").with(csrf()).param("companyCode", company).param("employeeNo", "2019031")
                        .param("birthDate", "1990-05-13").param("password", "Synthetic first web 43!")
                        .param("confirmation", "Synthetic first web 43!").accept(PAGE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.error").value("입력한 정보가 맞지 않거나 최초 접속 기간이 지났습니다. 담당자에게 문의하세요."));
        mvc.perform(post("/first-login").with(csrf()).param("companyCode", company).param("employeeNo", "2019031")
                        .param("birthDate", "1990-05-12").param("password", "Synthetic first web 43!")
                        .param("confirmation", "Synthetic first web 43!"))
                .andExpect(redirectedUrl("/login?activated"));
    }
}
```

`FirstLoginIT.database`는 패키지 공개 정적 메서드이므로 다른 IT에서 재사용할 수 있다.

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=FirstLoginWebIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: FAIL. `/login` 실패가 `/login?error`로 가고, `/first-login`은 403이다.

- [ ] **Step 3: 컨트롤러 작성**

```java
package kr.tna.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import kr.tna.org.OrgException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public final class FirstLoginController {
    private final FirstLoginService service;
    public FirstLoginController(FirstLoginService service) { this.service = service; }

    @GetMapping("/first-login")
    ModelAndView form(HttpServletRequest request) {
        var flash = RequestContextUtils.getInputFlashMap(request);
        return view(flash == null ? "" : String.valueOf(flash.getOrDefault("companyCode", "")),
                flash == null ? "" : String.valueOf(flash.getOrDefault("employeeNo", "")));
    }

    @PostMapping("/first-login")
    ModelAndView complete(@RequestParam(defaultValue = "") String companyCode, @RequestParam(defaultValue = "") String employeeNo,
                          @RequestParam(defaultValue = "") String birthDate, @RequestParam(defaultValue = "") String password,
                          @RequestParam(defaultValue = "") String confirmation,
                          HttpServletRequest request, HttpServletResponse response) {
        try {
            if (!password.equals(confirmation)) throw OrgException.invalid("비밀번호와 확인값이 일치하지 않습니다.");
            service.activate(companyCode, employeeNo, date(birthDate), password, request.getRemoteAddr());
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            var redirect = new RedirectView("/login?activated", true);
            redirect.setStatusCode(HttpStatus.SEE_OTHER);
            return new ModelAndView(redirect);
        } catch (OrgException error) {
            if (error.status() == 429) response.setHeader("Retry-After", "300");
            var view = view(companyCode, employeeNo).addObject("error", error.getMessage());
            view.setStatus(HttpStatusCode.valueOf(error.status()));
            return view;
        }
    }

    private static ModelAndView view(String companyCode, String employeeNo) {
        return new ModelAndView("first-login").addObject("companyCode", companyCode).addObject("employeeNo", employeeNo);
    }

    private static LocalDate date(String value) {
        try { return LocalDate.parse(value); } catch (DateTimeParseException invalid) { return null; }
    }
}
```

- [ ] **Step 4: 보안 경로와 실패 처리기 연결**

`SecurityConfiguration.java`:

```java
                        .requestMatchers(HttpMethod.GET, "/invite", "/invite/*", "/password-reset", "/first-login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/invite/accept", "/invite/*/accept", "/password-reset/request",
                                "/password-reset/complete", "/first-login").permitAll()
```

실패 처리기의 마지막 `else` 블록 맨 앞(`ReactPageResponse.problem(... 401 ...)` 앞)에 다음을 넣는다:

```java
                                String login = request.getParameter("email"), typed = request.getParameter("password");
                                if (login != null && typed != null && !login.isBlank() && !login.contains("@")
                                        && login.strip().equals(typed)) {
                                    var flash = new org.springframework.web.servlet.FlashMap();
                                    flash.put("companyCode", String.valueOf(request.getParameter("companyCode")));
                                    flash.put("employeeNo", login.strip());
                                    flash.setTargetRequestPath(request.getContextPath() + "/first-login");
                                    new org.springframework.web.servlet.support.SessionFlashMapManager()
                                            .saveOutputFlashMap(flash, request, response);
                                    response.sendRedirect(request.getContextPath() + "/first-login");
                                    return;
                                }
```

- [ ] **Step 5: React 화면 모델 등록**

`ReactPageViewResolver.java`:

```java
            page("invite", "companyCode", "inviteByCompany"), page("password-reset"), page("first-login", "companyCode", "employeeNo"),
            page("operator/login"),
```

```java
        for (String flag : List.of("error", "logout", "invited", "reset", "requested", "saved", "activated")) {
```

- [ ] **Step 6: 웹 테스트 통과 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=FirstLoginWebIT,SecurityWebIT,PasswordResetWebIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: PASS

- [ ] **Step 7: 실패하는 화면 테스트 작성**

`frontend/src/pages/AuthPages.test.tsx`(없으면 새로 만든다):

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AuthPages } from './AuthPages';

describe('first login', () => {
  it('prefills company and employee number and asks birth date and a new password', () => {
    render(<>{AuthPages({ page: 'first-login', data: { companyCode: 'ACME', employeeNo: '2019031', flags: {} } } as never)}</>);
    expect((screen.getByLabelText('회사 코드') as HTMLInputElement).value).toBe('ACME');
    expect((screen.getByLabelText('사번') as HTMLInputElement).value).toBe('2019031');
    expect(screen.getByLabelText('생년월일').getAttribute('type')).toBe('date');
    expect(screen.getByLabelText('새 비밀번호')).toBeTruthy();
    expect(screen.getByRole('button', { name: '비밀번호 정하기' })).toBeTruthy();
  });

  it('tells the employee to sign in after activation', () => {
    render(<>{AuthPages({ page: 'login', data: { flags: { activated: true } } } as never)}</>);
    expect(screen.getByText('비밀번호를 정했습니다. 사번과 새 비밀번호로 로그인해 주세요.')).toBeTruthy();
  });
});
```

Run: `npm --prefix frontend test -- --run src/pages/AuthPages.test.tsx`
Expected: FAIL (`first-login` 화면 없음)

- [ ] **Step 8: React 화면 구현**

`AuthPages.tsx`:

1. `AuthModel`에 `employeeNo?: string;`을, `Flags`에 `activated?: boolean;`을 추가한다.
2. `Login`의 안내 문구를 다음으로 바꾼다.

   ```tsx
            : '회사 코드와 사번(또는 이메일)으로 로그인해 주세요. 처음 로그인할 때는 비밀번호에 사번을 입력하세요.'}
   ```

3. `reset` 알림 아래에 다음을 추가한다.

   ```tsx
        {m.flags?.activated && (
          <p className="notice" role="status">
            비밀번호를 정했습니다. 사번과 새 비밀번호로 로그인해 주세요.
          </p>
        )}
   ```

4. `PasswordReset` 함수 위에 화면 컴포넌트를 추가한다.

```tsx
function FirstLogin({ model: m }: { model: AuthModel }) {
  return (
    <main className="login-shell">
      <section className="panel" aria-labelledby="first-login-title">
        <h1 id="first-login-title">최초 접속</h1>
        <p className="t-auth-context"><span>WorkLog</span><span>회사 계정</span></p>
        <p>인사정보에 등록된 생년월일을 확인한 뒤 새 비밀번호를 정합니다. 최초 접속은 계정을 만든 날부터 7일 동안 할 수 있습니다.</p>
        <ErrorNotice message={m.error} />
        <Form action="/first-login" method="post">
          <Field id="first-company" label="회사 코드" name="companyCode" value={m.companyCode ?? ''} maxLength={80} autoComplete="organization" required />
          <Field id="first-employee" label="사번" name="employeeNo" value={m.employeeNo ?? ''} maxLength={40} autoComplete="username" required />
          <Field id="first-birth" label="생년월일" name="birthDate" type="date" autoComplete="bday" required />
          <Field id="first-password" label="새 비밀번호" name="password" type="password" minLength={12} maxLength={128} autoComplete="new-password" required />
          <Field id="first-confirmation" label="새 비밀번호 확인" name="confirmation" type="password" minLength={12} maxLength={128} autoComplete="new-password" required />
          <p className="hint">공백을 포함해 12~128자로 설정해 주세요. 사번과 같은 비밀번호는 쓸 수 없습니다.</p>
          <button type="submit">비밀번호 정하기</button>
        </Form>
        <p><a href="/login">로그인으로</a></p>
        <p className="hint">5회 틀리거나 기간이 지나면 담당자에게 비밀번호 초기화를 요청하세요.</p>
      </section>
    </main>
  );
}
```

5. `AuthPages` switch에 `case 'first-login': return <FirstLogin model={model} />;`를 추가한다.

`App.tsx`:
- `publicPages`에 `'first-login'`을 추가한다.
- `titles`에 `'first-login': '최초 접속',`을 추가한다.

- [ ] **Step 9: 화면 테스트 통과 확인**

Run: `npm --prefix frontend test -- --run src/pages/AuthPages.test.tsx`
Expected: PASS

- [ ] **Step 10: 커밋**

```powershell
git add src/main/java/kr/tna/common/auth/FirstLoginController.java src/main/java/kr/tna/common/config/SecurityConfiguration.java src/main/java/kr/tna/common/web/ReactPageViewResolver.java src/test/java/kr/tna/FirstLoginWebIT.java frontend/src/pages/AuthPages.tsx frontend/src/pages/AuthPages.test.tsx frontend/src/App.tsx
git commit -m "feat: 사번으로 로그인하면 최초 접속 화면으로 이동해 비밀번호 설정"
```

---

### Task 7: 입사 예정 생년월일과 확정 시 계정 생성

**Files:**
- Modify: `src/main/java/kr/tna/org/EmployeeOnboardingData.java` (`Form`, `Row`, `View`)
- Modify: `src/main/java/kr/tna/org/EmployeeOnboardingMapper.java:12, 122-139`
- Modify: `src/main/java/kr/tna/org/EmployeeOnboardingService.java:65-130, 146-152`
- Modify: `frontend/src/pages/OnboardingPage.tsx:17, 39-41, 108-110, 180-181`
- Test: `src/test/java/kr/tna/EmployeeOnboardingIT.java`, `frontend/src/pages/OnboardingPage.test.tsx`

- [ ] **Step 1: 실패하는 통합 테스트 추가**

`EmployeeOnboardingIT`에서 확정까지 가는 기존 시나리오를 찾는다(`rg "confirm\(" src/test/java/kr/tna/EmployeeOnboardingIT.java`). 그 시나리오의 `Form` 생성에 생년월일 `LocalDate.of(1995, 4, 1)`을 마지막 인자로 넣고, 확정 직후에 다음 단언을 추가한다:

```java
        assertEquals(java.time.LocalDate.of(1995, 4, 1), admin.queryForObject(
                "SELECT birth_date FROM tna.employee_personnel WHERE tenant_id=? AND employee_id=?", java.time.LocalDate.class, tenant, employeeId));
        assertNotNull(admin.queryForObject(
                "SELECT activation_expires_at FROM tna.tenant_account WHERE tenant_id=? AND employee_id=?", java.sql.Timestamp.class, tenant, employeeId));
        assertEquals(List.of("EMPLOYEE"), admin.queryForList("""
                SELECT g.role FROM tna.role_grant g JOIN tna.tenant_account a ON a.tenant_id=g.tenant_id AND a.id=g.account_id
                WHERE a.tenant_id=? AND a.employee_id=? AND g.active""", String.class, tenant, employeeId));
```

같은 파일에 누락 검사를 추가한다:

```java
    @Test void confirmationRequiresBirthDate() {
        UUID planned = createPlanned(null);  // 이 파일의 예정 등록 도우미. 생년월일 인자를 받도록 바꾼다
        var error = assertThrows(OrgException.class, () -> confirmPlanned(planned));
        assertEquals(422, error.status());
        assertEquals("생년월일을 입력한 뒤 확정해 주세요.", error.getMessage());
    }
```

`createPlanned`·`confirmPlanned`가 이 파일에 없으면, 기존 등록·확정 코드를 두 도우미로 뽑아낸 뒤 사용한다. 이름은 파일의 기존 도우미 이름을 따른다.

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=EmployeeOnboardingIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: FAIL. `Form`에 생년월일 인자가 없어 컴파일 오류가 난다.

- [ ] **Step 3: 데이터 기록 변경**

`EmployeeOnboardingData.java`:

```java
    public record Form(String displayName, @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedOn,
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate leftOn,
                       UUID organizationId, UUID scheduleVersionId, String employeeNo, Long expectedVersion,
                       Long expectedOrganizationVersion, Long expectedTemplateVersion, UUID commandId, String reason,
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate birthDate) { }
```

- `Row`의 `joinedOn` 다음에 `LocalDate birthDate`를 넣는다.
- `View`에서 `boolean inviteEligible`을 `String accountStatus`로 바꾼다.

- [ ] **Step 4: 매퍼 SQL 변경**

`EmployeeOnboardingMapper.java`:
- `ROW`의 `o.joined_on,` 뒤에 `o.birth_date,`를 넣는다. `Row` 기록의 순서와 맞춘다.
- `insert`의 열 목록에 `birth_date`를, 값 목록에 `#{form.birthDate}`를 넣는다.
- `edit`의 `SET`에 `birth_date=#{form.birthDate},`를 넣는다.

```java
    @Insert("""
            INSERT INTO tna.employee_onboarding(tenant_id,id,display_name,joined_on,left_on,organization_id,
                schedule_version_id,birth_date,created_by,updated_by)
            VALUES(#{tenantId},#{id},#{form.displayName},#{form.joinedOn},#{form.leftOn},#{form.organizationId},
                #{form.scheduleVersionId},#{form.birthDate},#{actorId},#{actorId})
            """)
```

```java
            UPDATE tna.employee_onboarding SET display_name=#{form.displayName},joined_on=#{form.joinedOn},
                left_on=#{form.leftOn},organization_id=#{form.organizationId},schedule_version_id=#{form.scheduleVersionId},
                birth_date=#{form.birthDate},
                version=version+1,updated_by=#{actorId},updated_at=CURRENT_TIMESTAMP
```

- [ ] **Step 5: 서비스 변경**

`EmployeeOnboardingService.java`:

1. 생성자에 `EmployeePersonnelMapper personnel, AccountProvisioning provisioning, AccountAdminMapper accounts`를 추가하고 필드에 저장한다.

2. `save()`에서 `validateDates(...)` 다음에 다음을 넣는다.

   ```java
                       validateBirthDate(input.birthDate(), input.joinedOn());
   ```

   `input`을 만들 때 마지막 인자로 `form.birthDate()`를 넘기고, `fingerprint(...)`에 `input.birthDate()`를 더한다.

3. `confirm()`에서 `validateDates(planned.joinedOn(), planned.leftOn());` 다음에 다음을 넣는다.

   ```java
                       if (planned.birthDate() == null) throw OrgException.invalid("생년월일을 입력한 뒤 확정해 주세요.");
   ```

4. 소속 배정(`insertAssignment`) 다음에 다음을 넣는다.

   ```java
                       changed(personnel.save(tenant, employeeId, new EmployeePersonnelData.Stored(planned.birthDate(),
                               null, null, null, null, null, 1), 0, account));
                       provisioning.provision(tenant, employeeId, planned.joinedOn());
   ```

5. 화면 모델에서 `inviteEligible` 계산을 다음으로 바꾼다.

   ```java
               var account = employee == null ? null : accounts.account(tenant, employee.id());
               String accountStatus = employee == null ? null : AccountStatus.of(account != null,
                       account != null && account.active(), account == null ? null : account.activationExpiresAt(),
                       account == null ? 0 : account.activationFailures(), true, asOf).label();
   ```

   `new View(...)`의 해당 인자도 `accountStatus`로 바꾼다.

6. `newForm(...)`의 두 `new Form(...)` 호출 끝에 생년월일 인자를 넣는다. 새 등록은 `null`, 기존 행은 `detail.birthDate()`다.

7. 메서드를 추가한다.

```java
    private static void validateBirthDate(LocalDate birthDate, LocalDate joinedOn) {
        if (birthDate == null) throw OrgException.invalid("생년월일을 입력해 주세요.");
        if (birthDate.isBefore(LocalDate.of(1900, 1, 1)) || !birthDate.isBefore(joinedOn)) {
            throw OrgException.invalid("생년월일은 1900-01-01 이후, 입사일 이전으로 입력해 주세요.");
        }
    }
```

- [ ] **Step 6: 통합 테스트 통과 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=EmployeeOnboardingIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: PASS

- [ ] **Step 7: 실패하는 화면 테스트 추가**

`OnboardingPage.test.tsx`의 등록 폼 테스트에 다음을 추가한다:

```tsx
    expect(screen.getByLabelText('생년월일').getAttribute('type')).toBe('date');
    expect(screen.getByLabelText('생년월일').hasAttribute('required')).toBe(true);
```

확정 사원 상세 테스트에는 다음을 추가한다(모델에 `accountStatus: '최초 접속 대기'`를 넣고):

```tsx
    expect(screen.getByText('계정 · 최초 접속 대기 (사번으로 로그인해 생년월일 확인)')).toBeTruthy();
    expect(screen.queryByText('계정 초대')).toBeNull();
```

Run: `npm --prefix frontend test -- --run src/pages/OnboardingPage.test.tsx`
Expected: FAIL

- [ ] **Step 8: 화면 구현**

`OnboardingPage.tsx`:
- 폼 모델 타입(17줄 근처)에 `birthDate?: string | null;`, 행 타입에 `birthDate?: string | null;`, 화면 모델에 `accountStatus?: string | null;`을 추가하고 `inviteEligible`을 없앤다.
- 39줄의 기존 값 복사에 `birthDate: row.birthDate,`를 넣는다.
- 110줄(`마지막 재직일`) 앞에 다음을 넣는다.

  ```tsx
        <Field label="생년월일" name="birthDate" type="date" value={f.birthDate ?? ''} max={joined} required />
  ```

- 180~181줄을 다음으로 바꾼다.

```tsx
      {m.accountStatus && <p>계정 · {m.accountStatus} (사번으로 로그인해 생년월일 확인)</p>}
```

- [ ] **Step 9: 통과 확인**

Run: `npm --prefix frontend test -- --run src/pages/OnboardingPage.test.tsx`
Expected: PASS

- [ ] **Step 10: 커밋**

```powershell
git add src/main/java/kr/tna/org/EmployeeOnboardingData.java src/main/java/kr/tna/org/EmployeeOnboardingMapper.java src/main/java/kr/tna/org/EmployeeOnboardingService.java src/test/java/kr/tna/EmployeeOnboardingIT.java frontend/src/pages/OnboardingPage.tsx frontend/src/pages/OnboardingPage.test.tsx
git commit -m "feat: 입사 예정에 생년월일을 받고 확정 때 인사정보와 계정 생성"
```

---

### Task 8: 계정·권한 화면 — 초대 폼을 상태·초기화로 교체

**Files:**
- Modify: `src/main/java/kr/tna/org/AccountData.java` (`InvitationForm` → `ResetForm`)
- Modify: `src/main/java/kr/tna/org/AccountService.java` (`invite` 제거, `reset` 추가, `roles` 조건)
- Modify: `src/main/java/kr/tna/org/AccountAdminController.java:55-61, 80-100`
- Modify: `src/main/java/kr/tna/common/web/ReactPageViewResolver.java:61`
- Modify: `src/main/java/kr/tna/common/config/SecurityConfiguration.java:55`
- Modify: `frontend/src/pages/AdminPages.tsx:331, 363, 632-702`
- Test: `src/test/java/kr/tna/AccountServiceIT.java`, `AccountAccessWebIT.java`, `MvpJourneyIT.java`, `PublicDemoIdentityGuardIT.java`, `PublicDemoSecurityIT.java`, `frontend/src/pages/AdminPages.test.tsx`

- [ ] **Step 1: 실패하는 서비스 테스트 작성**

`AccountServiceIT.java`에서 초대 발급 테스트(`invite(`를 부르는 테스트)를 지운다. 같은 자리에 다음을 추가한다:

```java
    @Test void resetReopensActivationAndRevokesSessions() {
        long before = authVersion();
        accounts.reset(actor, employeeId, new AccountData.ResetForm(employeeVersion(), "합성 분실 확인", UUID.randomUUID()));
        assertNotNull(admin.queryForObject("SELECT activation_expires_at FROM tna.tenant_account WHERE tenant_id=? AND employee_id=?",
                java.sql.Timestamp.class, tenant, employeeId));
        assertEquals(before + 1, authVersion());
        assertEquals(1, admin.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='ACCOUNT_ACTIVATION_RESET'",
                Integer.class, tenant));
    }

    @Test void rolesCanBeGrantedBeforeFirstLogin() {
        admin.update("UPDATE tna.tenant_account SET activation_expires_at=now()+interval '7 days' WHERE tenant_id=? AND employee_id=?", tenant, employeeId);
        accounts.roles(actor, employeeId, new AccountData.RolesForm(Set.of("EMPLOYEE", "APPROVER"), employeeVersion(), "합성 결재자", UUID.randomUUID()));
        assertTrue(admin.queryForList("""
                SELECT g.role FROM tna.role_grant g JOIN tna.tenant_account a ON a.tenant_id=g.tenant_id AND a.id=g.account_id
                WHERE a.tenant_id=? AND a.employee_id=? AND g.active""", String.class, tenant, employeeId).contains("APPROVER"));
    }
```

이 파일에 `authVersion()`·`employeeVersion()` 도우미가 없으면 다음 두 메서드를 추가한다(`admin`은 이 파일의 관리용 `JdbcTemplate`):

```java
    long authVersion() { return admin.queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND employee_id=?", Long.class, tenant, employeeId); }
    long employeeVersion() { return admin.queryForObject("SELECT version FROM tna.employee WHERE tenant_id=? AND id=?", Long.class, tenant, employeeId); }
```

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=AccountServiceIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: FAIL (`ResetForm`, `reset` 없음)

- [ ] **Step 3: 서비스 변경**

`AccountData.java`에서 `InvitationForm`을 지우고 다음을 추가한다:

```java
    public record ResetForm(Long expectedVersion, String reason, UUID commandId) { }
```

`AccountService.java`:
- `invite(...)`, `email(...)`, `tokenHash(...)`, `RANDOM`과 `InvitationDelivery` 의존성을 지운다. 이 변경으로 쓰이지 않게 된 것만 지운다.
- `import` 정리도 함께 한다.
- 생성자에 `AccountProvisioning provisioning`을 추가한다.

추가할 메서드:

```java
    public UUID reset(TnaPrincipal actor, UUID employeeId, AccountData.ResetForm form) {
        identityGuard.requireMutable(actor, employeeId);
        String reason = requiredText(form.reason(), 500, "초기화 사유");
        String action = "ACCOUNT_ACTIVATION_RESET";
        return commands.execute(actor, form.commandId(), action, "EMPLOYEE",
                fingerprint(action, employeeId, form.expectedVersion(), reason), reason, () -> {
                    var employee = requireEmployee(actor.tenantId(), employeeId, true);
                    checkVersion(form.expectedVersion(), employee.version());
                    Account before = account(actor.tenantId(), employeeId);
                    if (before == null) throw OrgException.invalid("계정이 없는 사원입니다. 사용자 관리에서 계정을 만들어 주세요.");
                    provisioning.reopen(actor.tenantId(), before.id());
                    changed(employees.advanceVersion(actor.tenantId(), employeeId, employee.version(), actor.accountId()));
                    return new Change(employeeId, Map.of("pending", before.activationExpiresAt() != null),
                            Map.of("pending", true));
                });
    }
```

`roles(...)`의 계정 조건을 다음으로 바꾼다:

```java
                    if (before == null || !(before.active() && before.credentialSet()) && before.activationExpiresAt() == null) {
                        throw OrgException.invalid("계정이 있는 재직 사원의 역할만 변경할 수 있습니다.");
                    }
```

- [ ] **Step 4: 컨트롤러와 화면 모델 변경**

`AccountAdminController.java`:
- `invite(...)` 매핑을 지운다.
- 그 자리에 다음을 추가한다.

```java
    @PostMapping("/admin/employees/{id}/account-reset")
    ModelAndView reset(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id,
            @ModelAttribute("resetForm") AccountData.ResetForm form, BindingResult binding) {
        check(binding);
        return submit(() -> accounts.reset(actor, id, form), () -> page(actor, id, form, null, null), "?reset");
    }
```

- `page(...)`의 첫 폼 인자 타입을 `AccountData.ResetForm`으로 바꾼다.
- 모델 키 `localDelivery`·`invitationForm`을 지운다.
- 대신 `resetForm`(기본값 `new AccountData.ResetForm(employee.version(), "", UUID.randomUUID())`)과 `accountStatus`(`AccountStatus.of(...).label()`, 생년월일 여부는 `employee_personnel` 조회 결과)를 넣는다.
- 생년월일 여부는 `EmployeePersonnelMapper`에서 해당 사원을 읽은 결과의 `birthDate() != null`로 정한다.
- 생성자에서 `InvitationDelivery delivery` 의존성을 지운다.

`ReactPageViewResolver.java`의 `org/access` 모델 키 목록에서 `"localDelivery"`, `"invitationForm"`을 `"resetForm"`, `"accountStatus"`로 바꾼다.

`SecurityConfiguration.java` 55줄 정규식의 `invitations`를 `account-reset`으로 바꾼다. 공개 데모 공유 사원 보호를 유지하기 위해서다.

- [ ] **Step 5: 화면 변경**

`AdminPages.tsx`:
- `AccessModel` 타입에서 `localDelivery`, `invitationForm`을 지운다.
- `resetForm: { expectedVersion: number; reason?: string; commandId: string }`와 `accountStatus: string`을 추가한다.
- 331줄과 363줄의 링크 문구 `계정 초대·역할·승인자 관리`를 `계정·역할·승인자 관리`로 바꾼다.
- `AccessPage`의 635~702줄(초대 알림부터 초대 폼 끝까지)을 다음으로 바꾼다.

```tsx
  const reset = typeof window !== 'undefined' && new URLSearchParams(window.location.search).has('reset');
  return (
    <div className="t-legacy-page admin-shell">
      <PageHeader title={m.employee.displayName} actions={<a className="t-btn" href={path}>사원 상세로</a>} />
      <ErrorNotice message={m.error} />
      {reset && (
        <p className="notice" role="status">
          비밀번호를 초기화했습니다. 직원은 7일 안에 사번으로 로그인해 생년월일을 확인하고 새 비밀번호를 정합니다.
        </p>
      )}
      {!m.eligibleEmployee && (
        <p className="notice">현재 재직 중인 활성 사원만 초기화·역할 변경·승인자 배정을 할 수 있습니다.</p>
      )}
      <section className="wl-section" aria-labelledby="account-title">
        <h2 id="account-title">로그인 계정</h2>
        <p>{m.accountStatus}</p>
        {account && m.eligibleEmployee && (
          <Form action={`${path}/account-reset`} method="post" className="wl-form">
            <CommandFields form={m.resetForm} />
            <FormTable>
              <Reason id="reset-reason" form={m.resetForm} label="초기화 사유" />
            </FormTable>
            <div className="wl-form-actions">
              <button type="submit" className="t-btn primary">비밀번호 초기화</button>
            </div>
          </Form>
        )}
        {!account && <p className="hint">계정은 입사 확정이나 사용자 관리에서 만듭니다.</p>}
```

`AccessPage`의 나머지(역할·승인자 섹션과 닫는 태그)는 그대로 둔다.

- [ ] **Step 6: 초대에 의존하던 테스트 정리**

`rg -n "invitations|\.invite\(|InvitationForm|invitationForm|localDelivery|계정 초대" src/test frontend/src`로 남은 참조를 찾는다.

- **Java 통합 테스트**: 초대 발급을 계정 준비 수단으로 쓰던 곳은 관리용 SQL로 대기 계정을 넣는다(`INSERT INTO tna.tenant_account(...,active,activation_expires_at) VALUES (...,false,now()+interval '7 days')`).
- **공개 데모 보호 테스트**(`PublicDemoIdentityGuardIT`, `PublicDemoSecurityIT`): 대상 경로를 `/account-reset`으로 바꾼다. 403 기대값은 그대로 둔다.
- **`MvpJourneyIT`**: 초대 수락 단계를 '관리용 SQL로 대기 계정 생성 → `FirstLoginService.activate`'로 바꾼다.
- **`AdminPages.test.tsx`**: 초대 폼 단언을 지우고 다음 단언을 추가한다.

```tsx
    expect(screen.getByRole('button', { name: '비밀번호 초기화' })).toBeTruthy();
    expect(screen.queryByLabelText('초대 이메일')).toBeNull();
```

- [ ] **Step 7: 통과 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=AccountServiceIT,AccountAccessWebIT,MvpJourneyIT,PublicDemoIdentityGuardIT,PublicDemoSecurityIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: PASS

Run: `npm --prefix frontend test -- --run src/pages/AdminPages.test.tsx`
Expected: PASS

- [ ] **Step 8: 커밋**

```powershell
git add -A src/main/java/kr/tna/org src/main/java/kr/tna/common src/test/java/kr/tna frontend/src/pages/AdminPages.tsx frontend/src/pages/AdminPages.test.tsx
git commit -m "feat: 담당자 초대 발급을 계정 상태 표시와 비밀번호 초기화로 교체"
```

---

### Task 9: 권한 관리 시트에서 최초 접속 전 계정 허용

**Files:**
- Modify: `src/main/java/kr/tna/org/RoleMembershipService.java:70, 90-92`
- Modify: `src/main/java/kr/tna/org/RoleDirectoryMapper.java:32, 48, 63-64`
- Test: `src/test/java/kr/tna/RoleMembershipWebIT.java`

- [ ] **Step 1: 실패하는 테스트 추가**

`RoleMembershipWebIT`의 부여 테스트와 같은 방식으로 대상 사원을 고른다. 그 사원의 계정을 관리용 SQL로 대기 상태로 바꾼다(`active=false, password_hash=NULL, activation_expires_at=now()+interval '7 days'`). 그다음 `APPROVER` 부여 요청이 200(저장 성공)인지 단언하는 테스트를 추가한다. 후보 조회 `GET /admin/access/roles/APPROVER/candidates`의 JSON에 그 사원이 포함되는지도 확인한다.

- [ ] **Step 2: 실패 확인**

Run: `.\mvnw.cmd -q "-Dfrontend.skip=true" "-Dit.test=RoleMembershipWebIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" verify`
Expected: FAIL. 메시지는 "초대를 수락한 활성 계정의 재직 사원만…"이다.

- [ ] **Step 3: 조건 변경**

`RoleDirectoryMapper.java`:
- 32줄 SELECT에 `a.activation_expires_at IS NOT NULL AS account_pending`을 추가한다.
- 48줄 조건 `AND a.active AND a.credential_set`을 `AND (a.active AND a.credential_set OR a.activation_expires_at IS NOT NULL)`로 바꾼다.
- 63~64줄 `EmployeeRow` 끝에 `boolean accountPending`을 추가한다.

`RoleMembershipService.java`:
- 92줄을 `&& account != null && (account.active() && account.credentialSet() || account.activationExpiresAt() != null);`로 바꾼다.
- 70줄 메시지를 `"계정이 있는 재직 사원만 권한을 변경할 수 있습니다."`로 바꾼다.

- [ ] **Step 4: 통과 확인**

Run: 같은 명령. Expected: PASS. `RoleDirectoryWebIT`도 함께 실행해 통과를 확인한다.

- [ ] **Step 5: 커밋**

```powershell
git add src/main/java/kr/tna/org/RoleMembershipService.java src/main/java/kr/tna/org/RoleDirectoryMapper.java src/test/java/kr/tna/RoleMembershipWebIT.java
git commit -m "feat: 최초 접속 전 계정에도 결재자 등 권한을 미리 부여"
```

---

### Task 10: 결정 기록과 문서

**Files:**
- Create: `docs/adr/0008-employee-account-provisioning.md`
- Modify: `docs/design/account-access.md:3, 17-27, 59`
- Modify: `docs/superpowers/specs/2026-11-17-personnel-onboarding-design.md` (끝에 '2026-11-24 변경' 절)
- Modify: `docs/superpowers/specs/2026-11-23-opening-prep-design.md` (§1.2에 대기 계정 역할 부여 한 줄, 감사 action 이름 `ACCOUNT_ACTIVATION_RESET`)
- Modify: `README.md` (계정 초대 서술 두 곳), `docs/development/local.md:5-7`

- [ ] **Step 1: ADR 작성**

```markdown
# ADR 0008: 담당자 등록 시 계정 자동 생성과 사번·생년월일 최초 접속

- 날짜: 2026-11-24 · 상태: 채택 · 대체: account-access의 '담당자 이메일 초대로만 계정 생성'

## 배경
고객사 오픈 때 담당자가 직원마다 이메일 초대를 보내야 했다. 사용자는 한국 HR 시스템처럼 등록·발령과 동시에 계정이 생기고, 사번으로 처음 로그인해 비밀번호를 바꾸는 방식을 결정했다(2026-11-23).

## 결정
- 입사 확정과 재직자 일괄 등록 때 비밀번호 없는 대기 계정을 만든다(`activation_expires_at`). 로그인 ID는 사번이다.
- 사번은 동료가 알 수 있으므로 사번만으로는 첫 비밀번호를 정할 수 없다. 인사정보의 생년월일을 확인한다.
- 대기 기간은 7일이고, 5회 틀리면 잠긴다. 실패 응답은 모두 같은 문구다. 최초 접속을 마쳐도 자동 로그인하지 않는다.
- 비밀번호 저장은 계속 인증 DB 역할(`tna_auth`)만 한다. 일반 업무 DB 역할은 대기 시각과 실패 횟수만 바꾼다.
- 담당자 이메일 초대 발급은 없앤다. 서비스 운영자의 첫 담당자 초대와 이메일 비밀번호 재설정은 유지한다.

## 결과와 한계
- 생년월일이 없는 사원은 최초 접속을 할 수 없다. 그래서 입사 예정 등록에서 생년월일을 받는다.
- 생년월일을 아는 동료가 대기 7일 안에 먼저 접속할 위험은 남는다. 직원이 로그인하지 못하면 담당자 초기화로 회수한다.
```

- [ ] **Step 2: 설계 문서 갱신**

- `account-access.md`
  - 3줄과 17~27줄의 '이메일 초대로 계정 생성' 서술을 'ADR 0008의 대기 계정·최초 접속'으로 바꾼다.
  - 59줄의 `POST /admin/employees/{id}/invitations` 행을 `POST /admin/employees/{id}/account-reset`, `GET/POST /first-login` 행으로 바꾼다.
- 입사 예정 명세 끝에 다음 절을 추가한다: "2026-11-24 변경: 생년월일(필수)을 받고, 확정 때 인사정보 생년월일과 대기 계정을 함께 만든다. '계정 초대' 버튼은 없앤다."
- `README.md`의 "계정 초대와 역할 배정"과 "초대 메일로 계정을 활성화" 서술을 대기 계정·최초 접속 기준으로 고친다.
- `local.md` 5~7줄의 "입사를 확정하고 직원과 결재자를 초대한다"를 "입사를 확정하면 계정이 생기고, 직원은 사번으로 최초 접속한다"로 고친다.

- [ ] **Step 3: 문서 검증**

Run: `rg -n "계정 초대|초대를 수락|invitations" README.md docs/design/account-access.md docs/development/local.md`
Expected: 운영자 첫 담당자 초대와 이력 기록 문장만 남는다.

- [ ] **Step 4: 커밋**

```powershell
git add docs/adr/0008-employee-account-provisioning.md docs/design/account-access.md docs/superpowers/specs/2026-11-17-personnel-onboarding-design.md docs/superpowers/specs/2026-11-23-opening-prep-design.md README.md docs/development/local.md
git commit -m "docs: 계정 자동 생성과 사번·생년월일 최초 접속 결정 기록"
```

---

### Task 11: 전체 회귀와 브라우저 확인

**Files:**
- Modify: `src/test/java/kr/tna/CustomerOpeningBrowser.java` (초대 메일 단계 → 최초 접속 단계)
- Create: `docs/quality/account-provisioning-20261124.md`

- [ ] **Step 1: 개통 브라우저 검증 갱신**

`CustomerOpeningBrowser.java`에서 직원·결재자 초대(Mailpit에서 코드 읽기) 단계를 찾는다(`rg -n "invit|mailpit|Mailpit" src/test/java/kr/tna/CustomerOpeningBrowser.java`). 다음 순서로 바꾼다:

1. 입사 예정 등록에 생년월일 입력
2. 확정
3. 로그인 화면에서 `사번/사번` 입력
4. 최초 접속 화면에서 생년월일과 새 비밀번호 입력
5. 새 비밀번호로 로그인
6. 출근

서비스 운영자의 첫 담당자 초대 단계는 그대로 둔다.

- [ ] **Step 2: 전체 회귀**

Run: `.\mvnw.cmd verify`
Expected: BUILD SUCCESS. 실패하면 실패한 테스트부터 원인을 고친다. 실패를 건너뛰지 않는다.

Run: `npm --prefix frontend test -- --run`
Expected: 모든 테스트 PASS

Run: `npm --prefix frontend run build`
Expected: 타입 검사와 빌드 성공

- [ ] **Step 3: 브라우저 확인(PC 1366px, 모바일 390px)**

[개발 안내의 고객사 개통 검증](../../development/local.md#자동-검증) 절차로 `kr.tna.CustomerOpeningBrowser`를 실행한다. 다음을 화면 캡처와 함께 확인한다:

- 입사 확정 후 직원 최초 접속 → 출근
- 생년월일 5회 오입력 → 잠김 → 담당자 초기화 → 다시 최초 접속

- [ ] **Step 4: 결과 기록과 커밋**

`docs/quality/account-provisioning-20261124.md`에 다음을 적는다. 수치는 실제 실행 결과만 쓴다.

- 실행 명령
- 테스트 수와 결과
- 화면 확인 항목
- 실행하지 못한 검증(예: 공개 데모 미배포)

```powershell
git add src/test/java/kr/tna/CustomerOpeningBrowser.java docs/quality/account-provisioning-20261124.md
git commit -m "test: 입사 확정부터 최초 접속·초기화까지 PC·모바일 검증"
```

---

## 자체 점검 결과

- **설계 §1.2·§2.1·§3 대응**
  - 대기 계정: Task 1, 3
  - 상태 표시: Task 2, 8
  - 최초 접속·잠금·만료·동일 문구: Task 5, 6
  - 로그인 차단: Task 4
  - 입사 확정 연결과 생년월일: Task 7
  - 초대 제거와 초기화: Task 8
  - 비밀값 비기록: Task 5의 감사 단언
- **계획 2로 넘긴 항목**: 재직자 일괄 등록(§1.1, §2.2), 사용자 관리 화면(§1.3)
- **계획 3으로 넘긴 항목**: 달력(§1.4, §2.3, §2.4)
- **이름 일관성**
  - `AccountProvisioning.provision/reopen/expiresAt`
  - `AccountStatus.of/label/MAX_FAILURES`
  - `FirstLoginService.activate`
  - `AccountData.ResetForm`
  - 감사 action: `ACCOUNT_ACTIVATED`, `ACCOUNT_ACTIVATION_FAILED`, `ACCOUNT_LOCKED`, `ACCOUNT_ACTIVATION_RESET`
