# 결재 알림 (E 묶음) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 결재 제출·다음 단계·최종 승인·반려·결재자 변경 때 받는 사람에게 화면 알림(상단 종, 알림 화면)을 남기고, 계정 이메일이 있으면 1분 주기 작업으로 메일을 보낸다.

**Architecture:** 모든 결재 이력은 `LeaveWorkflowService.history()` 한 곳에서 `approval_event`에 쓰인다. 여기서 같은 트랜잭션으로 `tna.notification`(V43) 행을 만든다. 받는 사람·구분은 순수 함수 `NotificationRules`가 정한다. 화면은 `NotificationService`가 읽고, 메일은 `NotificationMailer`(세션 잠금 1분 주기)가 `NotificationMail`(SMTP·메모리·꺼짐)로 보낸다.

**Tech Stack:** Spring Boot 4.1 / Java 25, MyBatis annotation mapper, PostgreSQL 18 + Flyway, Spring Mail(이미 의존성 있음), React 19 + Vitest, JUnit 5 + 외부 테스트 DB(`DatabaseFixture`).

설계: [docs/superpowers/specs/2026-11-26-approval-notifications-design.md](../specs/2026-11-26-approval-notifications-design.md)

---

## 공통 실행 방법

- 작업 디렉터리: `C:\Users\ihpark\dev\vibe-attendance\target\claude-opening-prep-20261123` (브랜치 `claude/opening-prep-20261123`). 브랜치를 바꾸지 않는다. Push 금지. bare `git stash` 금지.
- Maven은 반드시 스크립트로 실행한다(JDK 25 + 테스트 DB 환경변수):
  `powershell -NoProfile -File C:\Users\ihpark\AppData\Local\Temp\claude\c6d09512e35b8bb6\ff996283-e18f-4a51-b946-d1e31871cf61\scratchpad\mvn-test.ps1 <maven args>`
  - 단위: `... mvn-test.ps1 -q test -Dfrontend.skip=true -Dspring-boot.repackage.skip=true -Dtest=NotificationRulesTest`
  - 통합: `... mvn-test.ps1 -q verify -Dfrontend.skip=true -Dspring-boot.repackage.skip=true -Dit.test=NotificationSchemaIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`
  - 여러 클래스는 쉼표로 구분한다(`+` 불가). PowerShell에서 `-D` 인자는 그대로 둔다(스크립트가 전달).
  - 스크립트가 없거나 DB 연결이 안 되면 컨트롤러에 알린다. 직접 만들지 않는다.
- 프런트엔드: `cd frontend; npx vitest run <파일>`; 빌드 `npm run build`.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 메일 주소·메일 본문·사유를 로그에 남기지 않는다. 회사 격리·RLS·권한을 약화하지 않는다. 비밀번호·생년월일을 메일·로그·페이지 모델에 넣지 않는다.

## 파일 구조

| 파일 | 책임 |
| --- | --- |
| `src/main/resources/db/migration/V43__approval_notifications.sql` (생성) | `tna.notification` 테이블·RLS·권한 |
| `src/main/java/kr/tna/notification/NotificationRules.java` (생성) | 결재 이력 → (받는 사람, 알림 구분) 순수 규칙 |
| `src/main/java/kr/tna/notification/NotificationText.java` (생성) | 신청 내용 문구·메일 제목·본문 |
| `src/main/java/kr/tna/notification/NotificationMapper.java` (생성) | 알림 조회·읽음·발송 대기·발송 결과 |
| `src/main/java/kr/tna/notification/NotificationService.java` (생성) | 로그인 사원 기준 목록·건수·읽음 |
| `src/main/java/kr/tna/notification/NotificationController.java` (생성) | `GET /me/notifications`, `POST /me/notifications/read-all` |
| `src/main/java/kr/tna/notification/NotificationMail.java` (생성) | 메일 전달 방식(SMTP·메모리·꺼짐) |
| `src/main/java/kr/tna/notification/NotificationMailer.java` (생성) | 1분 주기 발송 작업 |
| `src/main/java/kr/tna/request/LeaveWorkflowMapper.java` (수정) | `insertNotification` |
| `src/main/java/kr/tna/request/LeaveWorkflowService.java` (수정) | `history()`에서 알림 생성 |
| `src/main/java/kr/tna/request/RequestController.java` (수정) | 결재 상세를 열면 읽음 |
| `src/main/java/kr/tna/common/config/WorkspaceWebConfiguration.java` (수정) | 공통 데이터 `workspaceNotifications` |
| `src/main/java/kr/tna/common/web/ReactPageViewResolver.java` (수정) | 페이지 `notification/list`, COMMON 키 |
| `src/main/java/kr/tna/common/config/SecurityConfiguration.java` (수정) | `/me/notifications` 허용 |
| `src/test/resources/config/application.yml` (수정) | 시험 중 발송 작업 끄기 |
| `frontend/src/pages/NotificationsPage.tsx` (생성) | 알림 화면 |
| `frontend/src/App.tsx`, `frontend/src/navigation.ts`, CSS (수정) | 종 아이콘, 메뉴, 페이지 연결 |

---

### Task 1: V43 알림 테이블

**Files:**
- Create: `src/main/resources/db/migration/V43__approval_notifications.sql`
- Create: `src/test/java/kr/tna/NotificationSchemaIT.java`
- Modify: `src/test/java/kr/tna/DatabaseFoundationIT.java` (FORCE RLS 테이블 목록에 `"notification"`을 알파벳 순서 자리—`"leave_request_day"` 뒤, `"org_audit_event"` 앞 근처의 정렬 위치—에 추가)

- [ ] **Step 1: 실패하는 스키마 시험 작성**

`HolidaySchemaIT`의 구조를 따른다. 결재 이력 1건을 관리자 연결로 만든 뒤 알림을 넣는다.

```java
package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.SQLException;
import java.util.UUID;
import kr.tna.common.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class NotificationSchemaIT {
    private final DatabaseFixture f = DatabaseFixture.start();
    private final JdbcTemplate admin = new JdbcTemplate(f.adminDataSource());
    private final JdbcTemplate app = new JdbcTemplate(f.appDataSource());
    private final TenantTransactions tx = new TenantTransactions(f.appDataSource());
    private UUID tenant, employee, approver, account, request, event;

    @BeforeEach void seed() {
        tenant = UUID.randomUUID(); employee = UUID.randomUUID(); approver = UUID.randomUUID();
        account = UUID.randomUUID(); request = UUID.randomUUID(); event = UUID.randomUUID();
        UUID assignment = UUID.randomUUID();
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 알림 고객사')", tenant, "NOTIFY-" + tenant);
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'N1','합성 직원',DATE '2026-01-01')", tenant, employee);
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'N2','합성 승인자',DATE '2026-01-01')", tenant, approver);
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-hash')", tenant, account, employee, account + "@example.test");
        admin.update("""
            INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by)
            VALUES (?,?,?,?,DATE '2026-01-01','합성',?,?)""", tenant, assignment, employee, approver, account, account);
        admin.update("""
            INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,current_approver_id,
              approver_assignment_id,approver_assignment_version,reason,from_date,to_date,client_command_id)
            VALUES (?,?,?,?,'ATTENDANCE_CORRECTION',?,?,0,'합성',DATE '2026-11-26',DATE '2026-11-26',?)""",
            tenant, request, employee, account, approver, assignment, UUID.randomUUID());
        admin.update("""
            INSERT INTO tna.approval_event(tenant_id,id,request_id,event_kind,actor_account_id,to_status,reason,new_approver_id)
            VALUES (?,?,?,'SUBMITTED',?,'PENDING','합성',?)""", tenant, event, request, account, approver);
    }

    private int insert(UUID tenantId, UUID eventId, String kind) {
        return app.update("INSERT INTO tna.notification(tenant_id,id,recipient_employee_id,event_id,request_id,kind) VALUES (?,?,?,?,?,?)",
                tenantId, UUID.randomUUID(), approver, eventId, request, kind);
    }

    @Test void notificationIsTenantScopedWithNarrowGrants() {
        assertTrue(admin.queryForObject("""
            SELECT c.relrowsecurity AND c.relforcerowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='tna' AND c.relname='notification'""", Boolean.class));
        assertFalse(admin.queryForObject("SELECT has_table_privilege('tna_app','tna.notification','DELETE')", Boolean.class));
        for (String column : new String[] {"read_at", "mail_state", "mail_attempts", "mail_next_at"})
            assertTrue(admin.queryForObject("SELECT has_column_privilege('tna_app','tna.notification',?,'UPDATE')", Boolean.class, column), column);
        for (String column : new String[] {"recipient_employee_id", "event_id", "request_id", "kind", "created_at"})
            assertFalse(admin.queryForObject("SELECT has_column_privilege('tna_app','tna.notification',?,'UPDATE')", Boolean.class, column), column);
        assertFalse(admin.queryForObject("SELECT has_table_privilege('tna_auth','tna.notification','SELECT')", Boolean.class));

        assertEquals(1, tx.execute(tenant, () -> insert(tenant, event, "APPROVAL_REQUESTED")));
        assertEquals(1, tx.execute(tenant, () -> app.queryForObject("SELECT count(*) FROM tna.notification", Integer.class)));
        assertEquals(0, tx.execute(UUID.randomUUID(), () -> app.queryForObject("SELECT count(*) FROM tna.notification", Integer.class)));
        assertEquals("PENDING", tx.execute(tenant, () -> app.queryForObject("SELECT mail_state FROM tna.notification", String.class)));
        assertNotNull(tx.execute(tenant, () -> app.queryForObject("SELECT mail_next_at FROM tna.notification", java.sql.Timestamp.class)));
        assertEquals("42501", sqlState(() -> tx.execute(UUID.randomUUID(), () -> insert(tenant, event, "APPROVED"))));
    }

    @Test void oneNotificationPerEventAndValuesAreChecked() {
        tx.execute(tenant, () -> insert(tenant, event, "APPROVAL_REQUESTED"));
        assertEquals("23505", sqlState(() -> tx.execute(tenant, () -> insert(tenant, event, "APPROVED"))));
        UUID other = UUID.randomUUID();
        admin.update("INSERT INTO tna.approval_event(tenant_id,id,request_id,event_kind,actor_account_id,reason) VALUES (?,?,?,'REJECTED',?,'합성')",
                tenant, other, request, account);
        assertEquals("23514", sqlState(() -> tx.execute(tenant, () -> insert(tenant, other, "WITHDRAWN"))));
        assertEquals("23514", sqlState(() -> tx.execute(tenant, () -> app.update(
                "UPDATE tna.notification SET mail_state='SENT' WHERE event_id=?", event))), "보낸 알림은 다음 시도 시각이 없어야 한다");
        assertEquals(1, tx.execute(tenant, () -> app.update(
                "UPDATE tna.notification SET mail_state='SENT',mail_next_at=NULL WHERE event_id=?", event)));
        assertEquals("23514", sqlState(() -> tx.execute(tenant, () -> app.update(
                "UPDATE tna.notification SET mail_attempts=4 WHERE event_id=?", event))));
    }

    private static String sqlState(Runnable action) {
        try { action.run(); } catch (DataAccessException e) {
            for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof SQLException s) return s.getSQLState();
            throw e;
        }
        fail("SQL 오류가 나야 한다");
        return null;
    }
}
```

`approver_assignment` 열 이름이 다르면 V3/V5의 `CREATE TABLE tna.approver_assignment`를 읽어 맞춘다(테이블 정의를 바꾸지 않는다).

- [ ] **Step 2: 실패 확인** — `mvn-test.ps1 -q verify -Dfrontend.skip=true -Dspring-boot.repackage.skip=true -Dit.test=NotificationSchemaIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false` → `relation "tna.notification" does not exist`로 FAIL.

- [ ] **Step 3: 마이그레이션 작성**

```sql
-- 결재 알림: 결재 이력 1건당 받는 사람 1명의 화면 알림과 메일 발송 상태 (설계 2026-11-26-approval-notifications-design.md §3)
CREATE TABLE tna.notification (
    tenant_id uuid NOT NULL REFERENCES platform.tenant_registry (id),
    id uuid NOT NULL,
    recipient_employee_id uuid NOT NULL,
    event_id uuid NOT NULL,
    request_id uuid NOT NULL,
    kind varchar(30) NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at timestamptz(3),
    mail_state varchar(10) NOT NULL DEFAULT 'PENDING',
    mail_attempts smallint NOT NULL DEFAULT 0,
    mail_next_at timestamptz(3) DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,event_id),
    FOREIGN KEY (tenant_id,recipient_employee_id) REFERENCES tna.employee (tenant_id,id),
    FOREIGN KEY (tenant_id,event_id) REFERENCES tna.approval_event (tenant_id,id),
    FOREIGN KEY (tenant_id,request_id) REFERENCES tna.approval_request (tenant_id,id),
    CONSTRAINT notification_kind_valid CHECK (kind IN ('APPROVAL_REQUESTED','APPROVED','REJECTED')),
    CONSTRAINT notification_mail_state_valid CHECK (mail_state IN ('PENDING','SENT','SKIPPED','FAILED')),
    CONSTRAINT notification_mail_attempts_range CHECK (mail_attempts BETWEEN 0 AND 3),
    CONSTRAINT notification_mail_next_only_pending CHECK ((mail_state='PENDING')=(mail_next_at IS NOT NULL))
);
CREATE INDEX notification_recipient_recent ON tna.notification (tenant_id,recipient_employee_id,created_at DESC,id DESC);
CREATE INDEX notification_recipient_unread ON tna.notification (tenant_id,recipient_employee_id,request_id) WHERE read_at IS NULL;
CREATE INDEX notification_mail_pending ON tna.notification (tenant_id,mail_next_at) WHERE mail_state='PENDING';

ALTER TABLE tna.notification ENABLE ROW LEVEL SECURITY;
ALTER TABLE tna.notification FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tna.notification
    TO tna_migration,tna_app
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON tna.notification FROM PUBLIC;
GRANT SELECT,INSERT ON tna.notification TO tna_app;
GRANT UPDATE (read_at,mail_state,mail_attempts,mail_next_at) ON tna.notification TO tna_app;
```

`DatabaseFoundationIT`의 보호 테이블 목록에 `"notification"`을 정렬 위치에 추가한다.

- [ ] **Step 4: 통과 확인** — `-Dit.test=NotificationSchemaIT,DatabaseFoundationIT` → PASS.

- [ ] **Step 5: 커밋** — `git add src/main/resources/db/migration/V43__approval_notifications.sql src/test/java/kr/tna/NotificationSchemaIT.java src/test/java/kr/tna/DatabaseFoundationIT.java` → `git commit -m "feat: add approval notification table"`

---

### Task 2: 알림 규칙·문구와 결재 이력 연결

**Files:**
- Create: `src/main/java/kr/tna/notification/NotificationRules.java`
- Create: `src/main/java/kr/tna/notification/NotificationText.java`
- Create: `src/test/java/kr/tna/notification/NotificationRulesTest.java`
- Create: `src/test/java/kr/tna/notification/NotificationTextTest.java`
- Modify: `src/main/java/kr/tna/request/LeaveWorkflowMapper.java` (insertHistory 옆에 insertNotification)
- Modify: `src/main/java/kr/tna/request/LeaveWorkflowService.java:681-683` (`history()`)
- Create: `src/test/java/kr/tna/ApprovalNotificationIT.java`

- [ ] **Step 1: 규칙·문구 단위 시험 작성**

```java
package kr.tna.notification;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationRulesTest {
    final UUID actor = UUID.randomUUID(), next = UUID.randomUUID(), target = UUID.randomUUID();

    @Test void requestsGoToTheNextApproverAndDecisionsToTheRequester() {
        assertEquals(new NotificationRules.Target(next, "APPROVAL_REQUESTED"), NotificationRules.target("SUBMITTED", actor, next, target).orElseThrow());
        assertEquals(new NotificationRules.Target(next, "APPROVAL_REQUESTED"), NotificationRules.target("STEP_APPROVED", actor, next, target).orElseThrow());
        assertEquals(new NotificationRules.Target(next, "APPROVAL_REQUESTED"), NotificationRules.target("REASSIGNED", actor, next, target).orElseThrow());
        assertEquals(new NotificationRules.Target(target, "APPROVED"), NotificationRules.target("APPROVED", actor, null, target).orElseThrow());
        assertEquals(new NotificationRules.Target(target, "REJECTED"), NotificationRules.target("REJECTED", actor, null, target).orElseThrow());
    }

    @Test void withdrawalsUnknownKindsMissingRecipientsAndSelfActionsAreSilent() {
        assertTrue(NotificationRules.target("WITHDRAWN", actor, null, target).isEmpty());
        assertTrue(NotificationRules.target("SOMETHING", actor, next, target).isEmpty());
        assertTrue(NotificationRules.target("SUBMITTED", actor, null, target).isEmpty());
        assertTrue(NotificationRules.target("APPROVED", target, null, target).isEmpty(), "본인이 처리한 결과는 알리지 않는다");
        assertTrue(NotificationRules.target("REASSIGNED", next, next, target).isEmpty());
    }
}
```

```java
package kr.tna.notification;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class NotificationTextTest {
    final LocalDate d1 = LocalDate.of(2026, 12, 1), d2 = LocalDate.of(2026, 12, 2);

    @Test void requestSummaryUsesTheTypeNameAndShortPeriod() {
        assertEquals("연차 12-01~12-02", NotificationText.summary("LEAVE_DAY", "연차", d1, d2));
        assertEquals("휴가 12-01", NotificationText.summary("LEAVE_DAY", null, d1, d1));
        assertEquals("연차 취소 12-01", NotificationText.summary("CANCEL_LEAVE", "연차", d1, d1));
        assertEquals("출퇴근 정정 12-01", NotificationText.summary("ATTENDANCE_CORRECTION", null, d1, d1));
        assertEquals("연장근무 12-01", NotificationText.summary("OVERTIME", null, d1, d1));
        assertEquals("연장근무 취소 12-01", NotificationText.summary("CANCEL_OVERTIME", null, d1, d1));
        assertEquals("결재 요청", NotificationText.kindLabel("APPROVAL_REQUESTED"));
        assertEquals("승인", NotificationText.kindLabel("APPROVED"));
        assertEquals("반려", NotificationText.kindLabel("REJECTED"));
    }

    @Test void mailSubjectNamesTheRequesterOnlyForRequestsAndBodyCarriesRejectionReason() {
        var request = new NotificationText.MailFacts("APPROVAL_REQUESTED", "합성 회사", "홍길동", "연차 12-01~12-02", null,
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
        var mail = NotificationText.mail("person@example.test", request, java.net.URI.create("http://127.0.0.1:18080"));
        assertEquals("[WorkLog] 결재 요청 · 홍길동 연차 12-01~12-02", mail.subject());
        assertTrue(mail.body().contains("http://127.0.0.1:18080/requests/00000000-0000-0000-0000-000000000001"));
        assertFalse(mail.body().contains("반려 사유"));
        var rejected = new NotificationText.MailFacts("REJECTED", "합성 회사", "홍길동", "연차 12-01", "일정 중복",
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"));
        var rejection = NotificationText.mail("person@example.test", rejected, java.net.URI.create("https://worklog.example"));
        assertEquals("[WorkLog] 반려 · 연차 12-01", rejection.subject());
        assertTrue(rejection.body().contains("반려 사유: 일정 중복"));
        assertTrue(rejection.body().contains("합성 회사"));
        assertEquals("NotificationMail.Message[redacted]", rejection.toString());
    }
}
```

- [ ] **Step 2: 실패 확인** — `mvn-test.ps1 -q test -Dfrontend.skip=true -Dspring-boot.repackage.skip=true -Dtest=NotificationRulesTest,NotificationTextTest` → 컴파일 실패.

- [ ] **Step 3: 규칙·문구 구현**

```java
package kr.tna.notification;

import java.util.Optional;
import java.util.UUID;

/** 결재 이력 1건 → 알림 받는 사람과 구분 (설계 §2). 받는 사람이 처리한 본인이면 알리지 않는다. */
public final class NotificationRules {
    public record Target(UUID recipient, String kind) { }
    private NotificationRules() { }

    public static Optional<Target> target(String eventKind, UUID actorEmployee, UUID nextApprover, UUID requestTarget) {
        Target target = switch (eventKind) {
            case "SUBMITTED", "STEP_APPROVED", "REASSIGNED" -> nextApprover == null ? null : new Target(nextApprover, "APPROVAL_REQUESTED");
            case "APPROVED", "REJECTED" -> new Target(requestTarget, eventKind);
            default -> null;
        };
        return Optional.ofNullable(target).filter(t -> t.recipient() != null && !t.recipient().equals(actorEmployee));
    }
}
```

```java
package kr.tna.notification;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** 알림 화면과 메일의 문구. 인사 개인정보(생년월일·주소 등)는 넣지 않는다. */
public final class NotificationText {
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM-dd");
    /** 메일 한 통에 필요한 사실. reason은 반려일 때만 쓴다. */
    public record MailFacts(String kind, String companyName, String requesterName, String summary, String reason, UUID requestId) { }
    private NotificationText() { }

    public static String kindLabel(String kind) {
        return switch (kind) { case "APPROVED" -> "승인"; case "REJECTED" -> "반려"; default -> "결재 요청"; };
    }

    public static String summary(String requestKind, String typeName, LocalDate from, LocalDate to) {
        String leave = typeName == null ? "휴가" : typeName;
        String label = switch (requestKind) {
            case "LEAVE_DAY" -> leave;
            case "CANCEL_LEAVE" -> leave + " 취소";
            case "ATTENDANCE_CORRECTION" -> "출퇴근 정정";
            case "OVERTIME" -> "연장근무";
            case "CANCEL_OVERTIME" -> "연장근무 취소";
            default -> "신청";
        };
        String period = from.equals(to) ? SHORT.format(from) : SHORT.format(from) + "~" + SHORT.format(to);
        return label + " " + period;
    }

    public static NotificationMail.Message mail(String to, MailFacts f, URI origin) {
        boolean request = "APPROVAL_REQUESTED".equals(f.kind());
        String subject = "[WorkLog] " + kindLabel(f.kind()) + " · " + (request ? f.requesterName() + " " : "") + f.summary();
        String headline = switch (f.kind()) {
            case "APPROVED" -> "신청이 최종 승인되었습니다.";
            case "REJECTED" -> "신청이 반려되었습니다.";
            default -> "결재할 신청이 도착했습니다.";
        };
        var body = new StringBuilder(headline).append("\n\n")
                .append("회사: ").append(f.companyName()).append('\n')
                .append("신청자: ").append(f.requesterName()).append('\n')
                .append("신청 내용: ").append(f.summary()).append('\n');
        if ("REJECTED".equals(f.kind()) && f.reason() != null) body.append("반려 사유: ").append(f.reason()).append('\n');
        body.append("\n결재 상세: ").append(origin.resolve("/requests/" + f.requestId())).append('\n');
        return new NotificationMail.Message(to, subject, body.toString());
    }
}
```

`NotificationMail.Message`는 Task 4에서 만든다. 이 Task에서는 먼저 아래 최소 형태로 `NotificationMail.java`를 만들어 두고 Task 4에서 나머지를 채운다.

```java
package kr.tna.notification;

/** 결재 알림 메일 전달 방식. Task 4에서 전달 기능을 채운다. */
public class NotificationMail {
    public record Message(String to, String subject, String body) {
        @Override public String toString() { return "NotificationMail.Message[redacted]"; }
    }
}
```

- [ ] **Step 4: 단위 시험 통과 확인** — 같은 명령 → PASS.

- [ ] **Step 5: 결재 연결 통합 시험 작성** (`ApprovalRecalculationIT`의 `database()`, `member()`, `as()`, `decide()` 패턴을 그대로 쓴다)

```java
package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.ApproverService;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.BucketForm;
import kr.tna.request.RequestData.LeaveForm;
import kr.tna.request.RequestData.ReassignForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 결재 이력마다 받는 사람에게 알림이 1건씩 생기는지(설계 §2) 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class ApprovalNotificationIT {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    @Autowired MockMvc mvc;
    @Autowired LeaveWorkflowService flow;
    @Autowired ApproverService approvers;
    @Autowired Clock clock;
    private JdbcTemplate db;
    private UUID tenant, bucket;
    private LocalDate today;
    private TnaPrincipal manager, employee, approver, substitute;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f = DatabaseFixture.start();
        p.add("spring.datasource.url", f::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 5);
        p.add("tna.auth.url", f::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void company() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        today = LocalDate.now(clock.withZone(SEOUL));
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 알림 고객사')", tenant, "NOTIFY-" + tenant);
        manager = member("합성 담당자", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        employee = member("합성 직원", Set.of("EMPLOYEE"));
        approver = member("합성 승인자", Set.of("EMPLOYEE", "APPROVER"));
        substitute = member("합성 대결자", Set.of("EMPLOYEE", "APPROVER"));
        approvers.set(manager, new ApproverForm(employee.employeeId(), approver.employeeId(), today, 0L, "합성 승인 관계", UUID.randomUUID()));
        CalendarSeed.period(db, manager, today, today.plusDays(30), "합성 근로일 검토", "");
        bucket = flow.openBucket(manager, new BucketForm(employee.employeeId(), today, today, today.plusDays(30), 5,
                "합성 기초 근거", "합성 기초", UUID.randomUUID()));
    }

    @Test void submissionNotifiesTheApproverAndApprovalNotifiesTheRequester() throws Exception {
        UUID request = leave(2);
        assertEquals(List.of(Map.of("recipient", approver.employeeId(), "kind", "APPROVAL_REQUESTED")), notifications(request));
        decide(approver, request, "approve");
        assertEquals(List.of(Map.of("recipient", approver.employeeId(), "kind", "APPROVAL_REQUESTED"),
                Map.of("recipient", employee.employeeId(), "kind", "APPROVED")), notifications(request));
    }

    @Test void rejectionNotifiesTheRequesterAndWithdrawalNotifiesNobody() throws Exception {
        UUID rejected = leave(2);
        decide(approver, rejected, "reject");
        assertEquals("REJECTED", notifications(rejected).get(1).get("kind"));
        UUID withdrawn = leave(3);
        decide(employee, withdrawn, "withdraw");
        assertEquals(1, notifications(withdrawn).size(), "회수는 알리지 않는다");
    }

    @Test void reassignmentNotifiesTheNewApprover() throws Exception {
        UUID request = leave(2);
        long version = db.queryForObject("SELECT version FROM tna.approval_request WHERE tenant_id=? AND id=?", Long.class, tenant, request);
        Integer step = db.queryForObject("SELECT current_step FROM tna.approval_request WHERE tenant_id=? AND id=?", Integer.class, tenant, request);
        flow.reassign(manager, request, new ReassignForm(substitute.employeeId(), version, "합성 대결", UUID.randomUUID(), step));
        assertEquals(Map.of("recipient", substitute.employeeId(), "kind", "APPROVAL_REQUESTED"), notifications(request).get(1));
    }

    private UUID leave(int days) {
        LocalDate date = today.plusDays(days);
        return flow.submitLeave(employee, new LeaveForm(bucket, date, date, null, "합성 연차", UUID.randomUUID()));
    }

    private List<Map<String, Object>> notifications(UUID request) {
        return db.queryForList("""
            SELECT n.recipient_employee_id AS recipient, n.kind FROM tna.notification n
            JOIN tna.approval_event e ON e.tenant_id=n.tenant_id AND e.id=n.event_id
            WHERE n.tenant_id=? AND n.request_id=? ORDER BY e.created_at, e.id""", tenant, request);
    }

    private void decide(TnaPrincipal actor, UUID request, String action) throws Exception {
        long version = db.queryForObject("SELECT version FROM tna.approval_request WHERE tenant_id=? AND id=?", Long.class, tenant, request);
        mvc.perform(post("/requests/{id}/" + action, request).with(as(actor)).with(csrf())
                        .param("expectedVersion", Long.toString(version)).param("reason", "합성 결재 처리")
                        .param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isSeeOther());
    }

    private TnaPrincipal member(String name, Set<String> roles) {
        UUID employeeId = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",
                tenant, employeeId, "E-" + employeeId, name, today.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?, 'synthetic-unused-password-hash')",
                tenant, account, employeeId, account + "@example.test");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new TnaPrincipal(tenant, account, employeeId, 1, roles, Instant.now());
    }

    private static RequestPostProcessor as(TnaPrincipal actor) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
    }
}
```

결재 대기 신청은 `ReassignForm.expectedStep`이 null이어도 되는 옛 신청일 수 있다. `current_step`을 그대로 넘기면 두 경우 모두 맞는다. 휴가 신청이 근무 일정 부족으로 거절되면 `ApprovalRecalculationIT.absentPastWorkday()`처럼 일정·배정을 넣는다.

- [ ] **Step 6: 실패 확인** — `-Dit.test=ApprovalNotificationIT` → 알림 0건으로 FAIL.

- [ ] **Step 7: 연결 구현**

`LeaveWorkflowMapper`의 `insertHistory` 바로 아래:

```java
    @Insert("INSERT INTO tna.notification(tenant_id,id,recipient_employee_id,event_id,request_id,kind) VALUES(#{tenantId},#{id},#{recipient},#{eventId},#{requestId},#{kind})")
    int insertNotification(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("recipient") UUID recipient,
        @Param("eventId") UUID eventId,@Param("requestId") UUID requestId,@Param("kind") String kind);
```

`LeaveWorkflowService.history()`를 바꾼다(생성자는 바꾸지 않는다):

```java
    private void history(TnaPrincipal actor,RequestView r,String kind,String from,String to,String reason,UUID previous,UUID next) {
        UUID event=UUID.randomUUID();
        changed(mapper.insertHistory(actor.tenantId(),event,r.id(),kind,actor.accountId(),from,to,reason,previous,next,r.legacyForm()?null:r.currentStep()));
        // 결재와 같은 트랜잭션: 결재가 롤백되면 알림도 남지 않는다(설계 §2).
        kr.tna.notification.NotificationRules.target(kind,actor.employeeId(),next,r.targetEmployeeId()).ifPresent(t->
            changed(mapper.insertNotification(actor.tenantId(),UUID.randomUUID(),t.recipient(),event,r.id(),t.kind())));
    }
```

- [ ] **Step 8: 통과 확인** — `-Dit.test=ApprovalNotificationIT,ApprovalRecalculationIT,LeaveWorkflowIT` → PASS.

- [ ] **Step 9: 커밋** — `git add src/main/java/kr/tna/notification src/test/java/kr/tna/notification src/main/java/kr/tna/request/LeaveWorkflowMapper.java src/main/java/kr/tna/request/LeaveWorkflowService.java src/test/java/kr/tna/ApprovalNotificationIT.java` → `git commit -m "feat: notify approvers and requesters on approval events"`

---

### Task 3: 알림 조회·읽음과 화면 데이터

**Files:**
- Create: `src/main/java/kr/tna/notification/NotificationMapper.java`
- Create: `src/main/java/kr/tna/notification/NotificationService.java`
- Create: `src/main/java/kr/tna/notification/NotificationController.java`
- Modify: `src/main/java/kr/tna/request/RequestController.java` (생성자에 `NotificationService` 추가, `detail()`에서 읽음)
- Modify: `src/main/java/kr/tna/common/config/WorkspaceWebConfiguration.java`
- Modify: `src/main/java/kr/tna/common/web/ReactPageViewResolver.java`
- Modify: `src/main/java/kr/tna/common/config/SecurityConfiguration.java`
- Create: `src/test/java/kr/tna/NotificationWebIT.java`

- [ ] **Step 1: 실패하는 웹 시험 작성**

`ApprovalNotificationIT`의 셋업(`database`, `company`, `member`, `as`, `leave`)을 복사하고 아래 시험을 둔다. 페이지 JSON은 `accept("application/vnd.worklog.page+json")`과 `ReactPageAssertions.data(...)`를 쓴다(`AttendanceExceptionsWebIT` 참고).

```java
    private static final String PAGE_JSON = "application/vnd.worklog.page+json";

    @Test void bellCountListAndReadingTheRequestDetail() throws Exception {
        UUID first = leave(2), second = leave(3);
        mvc.perform(get("/me/notifications").with(as(approver)).accept(PAGE_JSON))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/workspaceNotifications", 2))
                .andExpect(jsonPath("$.data.notifications.length()").value(2))
                .andExpect(ReactPageAssertions.data("/notifications/0/requestId", second))
                .andExpect(ReactPageAssertions.data("/notifications/0/kind", "APPROVAL_REQUESTED"))
                .andExpect(ReactPageAssertions.data("/notifications/0/requesterName", "합성 직원"))
                .andExpect(ReactPageAssertions.data("/notifications/0/read", false));
        // 결재 상세를 열면 그 신청의 본인 알림만 읽음
        mvc.perform(get("/requests/{id}", first).with(as(approver)).accept(PAGE_JSON)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/workspaceNotifications", 1));
        mvc.perform(post("/me/notifications/read-all").with(as(approver)).with(csrf())).andExpect(status().isSeeOther());
        mvc.perform(get("/me/home").with(as(approver)).accept(PAGE_JSON))
                .andExpect(ReactPageAssertions.data("/workspaceNotifications", 0));
    }

    @Test void othersCannotSeeOrReadYourNotifications() throws Exception {
        UUID request = leave(2);
        mvc.perform(get("/me/notifications").with(as(employee)).accept(PAGE_JSON))
                .andExpect(jsonPath("$.data.notifications.length()").value(0))
                .andExpect(ReactPageAssertions.data("/workspaceNotifications", 0));
        mvc.perform(post("/me/notifications/read-all").with(as(employee)).with(csrf())).andExpect(status().isSeeOther());
        mvc.perform(get("/requests/{id}", request).with(as(employee)).accept(PAGE_JSON)).andExpect(status().isOk());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.notification WHERE tenant_id=? AND read_at IS NULL", Integer.class, tenant));
        // 다른 회사 사원은 같은 신청 알림을 볼 수 없다(RLS + 사원 필터)
        mvc.perform(post("/me/notifications/read-all").with(csrf())).andExpect(status().is3xxRedirection()); // 비로그인 → 로그인으로
    }

    @Test void bellCountStopsAtOneHundred() throws Exception {
        for (int i = 0; i < 101; i++) {
            UUID request = leave(2 + (i % 20));
            decide(employee, request, "withdraw"); // 대기 신청 중복을 피하려 회수(회수는 알림 없음)
        }
        mvc.perform(get("/me/home").with(as(approver)).accept(PAGE_JSON))
                .andExpect(ReactPageAssertions.data("/workspaceNotifications", 100));
        mvc.perform(get("/me/notifications").with(as(approver)).accept(PAGE_JSON))
                .andExpect(jsonPath("$.data.notifications.length()").value(100));
    }
```

`bellCountStopsAtOneHundred`에서 휴가 잔여가 모자라면 `BucketForm`의 일수를 200으로 늘린다. 비로그인 응답 코드가 302/401 등 프로젝트 규칙과 다르면 실제 동작(`SecurityConfiguration`)에 맞춰 기대값만 고친다.

- [ ] **Step 2: 실패 확인** — `-Dit.test=NotificationWebIT` → 404/403으로 FAIL.

- [ ] **Step 3: 매퍼 작성**

```java
package kr.tna.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NotificationMapper {
    record Stored(UUID id, UUID requestId, String kind, String requestKind, String typeName, String requesterName,
            LocalDate fromDate, LocalDate toDate, Instant createdAt, boolean read) { }
    record Pending(UUID id, String kind, int mailAttempts, String email, UUID requestId, String requestKind, String typeName,
            LocalDate fromDate, LocalDate toDate, String requesterName, String companyName, String eventReason) { }

    String STORED = """
            SELECT n.id, n.request_id, n.kind, r.kind AS request_kind, atv.name AS type_name, e.display_name AS requester_name,
              r.from_date, r.to_date, n.created_at, (n.read_at IS NOT NULL) AS read
            FROM tna.notification n
            JOIN tna.approval_request r ON r.tenant_id=n.tenant_id AND r.id=n.request_id
            JOIN tna.employee e ON e.tenant_id=r.tenant_id AND e.id=r.target_employee_id
            LEFT JOIN tna.attendance_type_version atv ON atv.tenant_id=r.tenant_id AND atv.id=r.attendance_type_version_id
            """;

    @Select(STORED + " WHERE n.tenant_id=#{tenantId} AND n.recipient_employee_id=#{employeeId} ORDER BY n.created_at DESC, n.id DESC LIMIT 100")
    List<Stored> recent(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Select("""
            SELECT count(*)::int FROM (SELECT 1 FROM tna.notification
              WHERE tenant_id=#{tenantId} AND recipient_employee_id=#{employeeId} AND read_at IS NULL LIMIT 100) unread""")
    int unreadCount(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Update("""
            UPDATE tna.notification SET read_at=CURRENT_TIMESTAMP
            WHERE tenant_id=#{tenantId} AND recipient_employee_id=#{employeeId} AND request_id=#{requestId} AND read_at IS NULL""")
    int markRequestRead(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId, @Param("requestId") UUID requestId);

    @Update("UPDATE tna.notification SET read_at=CURRENT_TIMESTAMP WHERE tenant_id=#{tenantId} AND recipient_employee_id=#{employeeId} AND read_at IS NULL")
    int markAllRead(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    // 메일 받는 주소: 재직(활성) 사원의 활성 계정 이메일만. 없으면 NULL → 건너뜀.
    @Select("""
            SELECT n.id, n.kind, n.mail_attempts, CASE WHEN rec.active AND a.active THEN a.email END AS email,
              r.id AS request_id, r.kind AS request_kind, atv.name AS type_name, r.from_date, r.to_date,
              req.display_name AS requester_name, t.display_name AS company_name, ev.reason AS event_reason
            FROM tna.notification n
            JOIN tna.approval_request r ON r.tenant_id=n.tenant_id AND r.id=n.request_id
            JOIN tna.employee req ON req.tenant_id=r.tenant_id AND req.id=r.target_employee_id
            JOIN tna.employee rec ON rec.tenant_id=n.tenant_id AND rec.id=n.recipient_employee_id
            JOIN tna.approval_event ev ON ev.tenant_id=n.tenant_id AND ev.id=n.event_id
            JOIN platform.tenant_registry t ON t.id=n.tenant_id
            LEFT JOIN tna.tenant_account a ON a.tenant_id=n.tenant_id AND a.employee_id=n.recipient_employee_id
            LEFT JOIN tna.attendance_type_version atv ON atv.tenant_id=r.tenant_id AND atv.id=r.attendance_type_version_id
            WHERE n.tenant_id=#{tenantId} AND n.mail_state='PENDING' AND n.mail_next_at<=CURRENT_TIMESTAMP
            ORDER BY n.mail_next_at, n.id LIMIT #{limit}""")
    List<Pending> pendingMail(@Param("tenantId") UUID tenantId, @Param("limit") int limit);

    @Update("UPDATE tna.notification SET mail_state=#{state}, mail_next_at=NULL WHERE tenant_id=#{tenantId} AND id=#{id} AND mail_state='PENDING'")
    int finishMail(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("state") String state);

    @Update("""
            UPDATE tna.notification SET mail_attempts=#{attempts},
              mail_state=CASE WHEN #{attempts}>=3 THEN 'FAILED' ELSE 'PENDING' END,
              mail_next_at=CASE WHEN #{attempts}>=3 THEN NULL
                ELSE CURRENT_TIMESTAMP + make_interval(mins => CASE WHEN #{attempts}=1 THEN 1 ELSE 5 END) END
            WHERE tenant_id=#{tenantId} AND id=#{id} AND mail_state='PENDING'""")
    int retryMail(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("attempts") int attempts);
}
```

`platform.tenant_registry`를 `tna_app`이 읽는 방식은 `EmployeeMapper.findSelf`와 같다. 열 별칭은 `map-underscore-to-camel-case`가 레코드 생성자로 맞춘다. MyBatis가 레코드 매핑에 실패하면 프로젝트의 다른 레코드 매퍼(`ProfileCompanyMapper`)와 같은 방식(별칭을 생성자 순서대로)으로 맞춘다.

- [ ] **Step 4: 서비스·컨트롤러 작성**

```java
package kr.tna.notification;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import org.springframework.stereotype.Service;

/** 로그인한 사원 본인의 알림만 읽고 바꾼다(설계 §4). */
@Service
public class NotificationService {
    private static final DateTimeFormatter SEOUL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));
    public record Row(UUID id, UUID requestId, String kind, String requesterName, String summary, String createdAt, boolean read) { }

    private final BusinessCommands business;
    private final NotificationMapper mapper;

    public NotificationService(BusinessCommands business, NotificationMapper mapper) {
        this.business = business; this.mapper = mapper;
    }

    public List<Row> list(TnaPrincipal actor) {
        return business.read(actor, () -> mapper.recent(actor.tenantId(), actor.employeeId()).stream()
                .map(n -> new Row(n.id(), n.requestId(), n.kind(), n.requesterName(),
                        NotificationText.summary(n.requestKind(), n.typeName(), n.fromDate(), n.toDate()), SEOUL.format(n.createdAt()), n.read()))
                .toList());
    }

    /** 상단 종 배지: 100건에서 멈춘다(화면은 99+). 같은 요청에서 계정 확인을 이미 마친 공통 데이터용이다. */
    public int unreadCount(UUID tenantId, UUID employeeId) { return mapper.unreadCount(tenantId, employeeId); }

    public void markRequestRead(TnaPrincipal actor, UUID requestId) {
        business.read(actor, () -> mapper.markRequestRead(actor.tenantId(), actor.employeeId(), requestId));
    }

    public void markAllRead(TnaPrincipal actor) {
        business.read(actor, () -> mapper.markAllRead(actor.tenantId(), actor.employeeId()));
    }
}
```

```java
package kr.tna.notification;

import java.net.URI;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
class NotificationController {
    private final NotificationService notifications;

    NotificationController(NotificationService notifications) { this.notifications = notifications; }

    @GetMapping("/me/notifications")
    ModelAndView page(@AuthenticationPrincipal TnaPrincipal actor) {
        return new ModelAndView("notification/list").addObject("notifications", notifications.list(actor));
    }

    @PostMapping("/me/notifications/read-all")
    ResponseEntity<Void> readAll(@AuthenticationPrincipal TnaPrincipal actor) {
        notifications.markAllRead(actor);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/me/notifications")).build();
    }
}
```

- [ ] **Step 5: 연결**

1. `RequestController`: 생성자에 `NotificationService notifications`를 추가하고 필드에 둔다. `detail()`에서 `var detail = detailPage(actor, id);` 바로 다음 줄에 `notifications.markRequestRead(actor, id);`를 넣는다(`detailPage`가 권한을 확인한 뒤에만 읽음 처리). 시험 코드가 `new RequestController(`로 직접 만드는 곳이 있으면 함께 고친다.
2. `WorkspaceWebConfiguration`: 생성자에 `NotificationService notifications`를 추가한다. `view.addObject("workspaceDemo", demo.enabled());` 다음에:
   ```java
   view.addObject("workspaceNotifications", tenants.execute(principal.tenantId(),
           () -> notifications.unreadCount(principal.tenantId(), principal.employeeId())));
   ```
   인터셉터 경로에 `/me/**`가 이미 있으므로 `/me/notifications`도 포함된다.
3. `ReactPageViewResolver`: `COMMON`에 `"workspaceNotifications"`를 추가하고, 페이지 목록에 `page("notification/list", "notifications"),`를 추가한다.
4. `SecurityConfiguration`: `/me/today` 등이 있는 `.authenticated()` 줄 근처(`anyRequest().denyAll()`보다 앞)에 `.requestMatchers("/me/notifications", "/me/notifications/read-all").authenticated()`를 추가한다.

- [ ] **Step 6: 통과 확인** — `-Dit.test=NotificationWebIT,ApprovalNotificationIT,AttendanceExceptionsWebIT` → PASS. 라우트 목록·보안 경로를 전수 검사하는 시험(예: `grep -rl "denyAll\|requestMatchers" src/test`)이 실패하면 새 경로를 그 목록에 추가한다.

- [ ] **Step 7: 커밋** — `git add src/main/java/kr/tna/notification src/main/java/kr/tna/request/RequestController.java src/main/java/kr/tna/common src/test/java/kr/tna/NotificationWebIT.java` (+ 고친 시험) → `git commit -m "feat: list notifications and mark them read"`

---

### Task 4: 메일 전달 방식과 1분 주기 발송 작업

**Files:**
- Modify: `src/main/java/kr/tna/notification/NotificationMail.java` (Task 2의 최소 형태 확장)
- Create: `src/main/java/kr/tna/notification/NotificationMailer.java`
- Modify: `src/test/resources/config/application.yml`
- Modify: `src/main/resources/application.yml` (`tna.notification-mail.enabled: true` 기본값 명시)
- Create: `src/test/java/kr/tna/notification/NotificationMailerIT.java`

- [ ] **Step 1: 실패하는 발송 시험 작성**

`@SpringBootTest`로 매퍼·데이터소스 빈을 주입받되, `NotificationMailer`는 시험 설정에서 빈이 꺼져 있으므로 `new`로 만든다(가짜 메일 방식을 넣기 위해). 시험용 알림은 `NotificationSchemaIT.seed()`처럼 관리자 연결로 요청·이력·알림을 넣는다.

```java
package kr.tna.notification;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import kr.tna.DatabaseFixture;
import kr.tna.common.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class NotificationMailerIT {
    @Autowired NotificationMapper mapper;
    @Autowired TenantTransactions transactions;
    @Autowired @Qualifier("dataSource") DataSource dataSource;
    @Autowired @Qualifier("authDataSource") DataSource authDataSource;
    private final DatabaseFixture f = DatabaseFixture.start();
    private final JdbcTemplate admin = new JdbcTemplate(f.adminDataSource());
    private UUID tenant, employee, approver, account, approverAccount, request;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f = DatabaseFixture.start();
        p.add("spring.datasource.url", f::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", f::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    // 시험 전용: 실패 횟수를 정할 수 있는 가짜 SMTP
    static final class FakeSender extends JavaMailSenderImpl {
        final List<SimpleMailMessage> sent = new java.util.ArrayList<>();
        final AtomicInteger failures = new AtomicInteger();
        @Override public void send(SimpleMailMessage message) {
            if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) throw new MailSendException("synthetic relay failure");
            sent.add(message);
        }
    }

    private NotificationMailer mailer(NotificationMail mail) {
        return new NotificationMailer(dataSource, authDataSource, transactions, mapper, mail);
    }
    // @BeforeEach seed(): NotificationSchemaIT.seed()와 같은 SQL + approverAccount(approver@example.test)
}
```

시험 메서드(셋업은 `NotificationSchemaIT.seed()`와 같고, 승인자 계정 `approverAccount`에 `approver@example.test` 이메일을 넣는다. 알림은 `insertNotification(kind, eventKind, reason)` 도우미로 이력+알림을 함께 넣는다):

```java
    @Test void sendsPendingMailOnceAndMarksItSent() {
        UUID id = insertNotification("APPROVAL_REQUESTED", "SUBMITTED", "합성 신청");
        var sender = new FakeSender();
        var mail = new NotificationMail(NotificationMail.Mode.SMTP, sender, "worklog@example.test", URI.create("https://worklog.example"));
        assertTrue(mailer(mail).runOnce(List.of(tenant)));
        assertEquals(1, sender.sent.size());
        assertEquals("approver@example.test", sender.sent.get(0).getTo()[0]);
        assertEquals("worklog@example.test", sender.sent.get(0).getFrom());
        assertTrue(sender.sent.get(0).getSubject().startsWith("[WorkLog] 결재 요청 · 합성 직원"));
        assertTrue(sender.sent.get(0).getText().contains("https://worklog.example/requests/" + request));
        assertFalse(sender.sent.get(0).getText().contains("합성 신청"), "결재 요청 메일에 이력 사유를 넣지 않는다");
        assertEquals("SENT", state(id));
        mailer(mail).runOnce(List.of(tenant));
        assertEquals(1, sender.sent.size(), "보낸 알림은 다시 보내지 않는다");
    }

    @Test void rejectionMailCarriesTheReason() {
        UUID id = insertNotificationTo(employee, "REJECTED", "REJECTED", "일정 중복");
        admin.update("UPDATE tna.tenant_account SET email='worker@example.test' WHERE tenant_id=? AND id=?", tenant, account);
        var sender = new FakeSender();
        mailer(new NotificationMail(NotificationMail.Mode.SMTP, sender, "worklog@example.test", URI.create("https://worklog.example"))).runOnce(List.of(tenant));
        assertTrue(sender.sent.get(0).getText().contains("반려 사유: 일정 중복"));
        assertEquals("SENT", state(id));
    }

    @Test void missingEmailInactiveAccountOrDisabledMailIsSkipped() {
        admin.update("UPDATE tna.tenant_account SET active=false WHERE tenant_id=? AND id=?", tenant, approverAccount);
        UUID inactive = insertNotification("APPROVAL_REQUESTED", "SUBMITTED", "합성");
        var sender = new FakeSender();
        mailer(new NotificationMail(NotificationMail.Mode.SMTP, sender, "worklog@example.test", URI.create("https://worklog.example"))).runOnce(List.of(tenant));
        assertEquals("SKIPPED", state(inactive));
        assertTrue(sender.sent.isEmpty());
        admin.update("UPDATE tna.tenant_account SET active=true WHERE tenant_id=? AND id=?", tenant, approverAccount);
        UUID off = insertNotification("APPROVAL_REQUESTED", "SUBMITTED", "합성");
        mailer(new NotificationMail(NotificationMail.Mode.OFF, null, null, URI.create("https://worklog.example"))).runOnce(List.of(tenant));
        assertEquals("SKIPPED", state(off));
    }

    @Test void failuresRetryThenStopAtThree() {
        UUID id = insertNotification("APPROVAL_REQUESTED", "SUBMITTED", "합성");
        var sender = new FakeSender();
        sender.failures.set(5);
        var mailer = mailer(new NotificationMail(NotificationMail.Mode.SMTP, sender, "worklog@example.test", URI.create("https://worklog.example")));
        mailer.runOnce(List.of(tenant));
        assertEquals("PENDING", state(id));
        assertEquals(1, admin.queryForObject("SELECT mail_attempts FROM tna.notification WHERE tenant_id=? AND id=?", Integer.class, tenant, id));
        assertTrue(admin.queryForObject("SELECT mail_next_at > CURRENT_TIMESTAMP + interval '50 seconds' FROM tna.notification WHERE tenant_id=? AND id=?", Boolean.class, tenant, id));
        mailer.runOnce(List.of(tenant));
        assertEquals(1, admin.queryForObject("SELECT mail_attempts FROM tna.notification WHERE tenant_id=? AND id=?", Integer.class, tenant, id), "다음 시도 시각 전에는 다시 보내지 않는다");
        for (int i = 0; i < 2; i++) {
            admin.update("UPDATE tna.notification SET mail_next_at=CURRENT_TIMESTAMP - interval '1 second' WHERE tenant_id=? AND id=?", tenant, id);
            mailer.runOnce(List.of(tenant));
        }
        assertEquals("FAILED", state(id));
        assertEquals(3, admin.queryForObject("SELECT mail_attempts FROM tna.notification WHERE tenant_id=? AND id=?", Integer.class, tenant, id));
        assertTrue(sender.sent.isEmpty());
    }

    @Test void onlyOneRunHoldsTheLock() throws Exception {
        insertNotification("APPROVAL_REQUESTED", "SUBMITTED", "합성");
        try (var holder = f.appDataSource().getConnection(); var st = holder.createStatement()) {
            st.execute("SELECT pg_advisory_lock(" + NotificationMailer.LOCK_KEY + ")");
            var sender = new FakeSender();
            assertFalse(mailer(new NotificationMail(NotificationMail.Mode.SMTP, sender, "worklog@example.test", URI.create("https://worklog.example"))).runOnce(List.of(tenant)));
            assertTrue(sender.sent.isEmpty());
            st.execute("SELECT pg_advisory_unlock(" + NotificationMailer.LOCK_KEY + ")");
        }
    }

    @Test void localModeKeepsMessagesInMemory() {
        insertNotification("APPROVAL_REQUESTED", "SUBMITTED", "합성");
        var mail = new NotificationMail(NotificationMail.Mode.LOCAL, null, null, URI.create("http://127.0.0.1:18080"));
        mailer(mail).runOnce(List.of(tenant));
        assertEquals(1, mail.messages().size());
        assertEquals("approver@example.test", mail.messages().get(0).to());
    }
```

`state(id)`는 `SELECT mail_state ...`를 돌려주는 도우미다. 도우미(`insertNotification`, `insertNotificationTo`, `state`)는 위 seed와 같은 SQL로 직접 작성한다.

- [ ] **Step 2: 실패 확인** — `-Dit.test=NotificationMailerIT` → 컴파일 실패.

- [ ] **Step 3: `NotificationMail` 완성**

```java
package kr.tna.notification;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.List;
import kr.tna.common.auth.InvitationDelivery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** 결재 알림 메일 전달 방식: 계정 메일과 같은 설정(ADR-0007)을 따른다. public-demo는 보내지 않는다. */
@Component
public class NotificationMail {
    public enum Mode { SMTP, LOCAL, OFF }
    public record Message(String to, String subject, String body) {
        @Override public String toString() { return "NotificationMail.Message[redacted]"; }
    }

    private final Mode mode;
    private final JavaMailSender sender;
    private final String from;
    private final URI origin;
    private final ArrayDeque<Message> messages = new ArrayDeque<>();

    @Autowired
    NotificationMail(Environment env, ObjectProvider<JavaMailSender> sender, @Value("${tna.mail.mode:}") String mode,
                     @Value("${tna.mail.from:}") String from, @Value("${tna.mail.public-origin:http://127.0.0.1:18080}") URI origin) {
        this(env.matchesProfiles("public-demo") ? Mode.OFF : "smtp".equals(mode) ? Mode.SMTP
                : mode.isEmpty() && env.matchesProfiles("local") ? Mode.LOCAL : Mode.OFF,
                sender.getIfAvailable(), from, origin);
    }

    public NotificationMail(Mode mode, JavaMailSender sender, String from, URI origin) {
        if (mode == Mode.SMTP && (sender == null || !InvitationDelivery.isSingleMailbox(from)))
            throw new IllegalArgumentException("SMTP notification mail needs a mail sender and a single sender mailbox");
        this.mode = mode; this.sender = sender; this.from = from; this.origin = origin;
    }

    public boolean enabled() { return mode != Mode.OFF; }
    public URI origin() { return origin; }

    /** @throws org.springframework.mail.MailException 전달을 확인하지 못했을 때 */
    public void send(Message message) {
        switch (mode) {
            case SMTP -> {
                var mail = new SimpleMailMessage();
                mail.setTo(message.to()); mail.setFrom(from);
                mail.setSubject(message.subject()); mail.setText(message.body());
                sender.send(mail);
            }
            case LOCAL -> { synchronized (messages) { if (messages.size() == 256) messages.removeFirst(); messages.addLast(message); } }
            case OFF -> throw new IllegalStateException("Notification mail is disabled");
        }
    }

    public List<Message> messages() { synchronized (messages) { return List.copyOf(messages); } }
}
```

- [ ] **Step 4: `NotificationMailer` 작성**

```java
package kr.tna.notification;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.common.tenant.TenantTransactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 1분마다 회사별 발송 대기 알림 메일을 보낸다. 여러 서버에서도 세션 잠금으로 한 대만 보낸다(설계 §5). */
@Component
@ConditionalOnProperty(name = "tna.notification-mail.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationMailer {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationMailer.class);
    static final long LOCK_KEY = 0x544E414E_4F544946L; // "TNANOTIF"
    private static final int BATCH = 100;

    private final DataSource dataSource;
    private final JdbcTemplate registry;
    private final TenantTransactions transactions;
    private final NotificationMapper mapper;
    private final NotificationMail mail;

    // 회사 목록은 회사 하나만 보이는 tna_app 대신 인증 연결(tna_auth)로 읽는다(DailyAttendanceBatch와 같다).
    public NotificationMailer(@Qualifier("dataSource") DataSource dataSource, @Qualifier("authDataSource") DataSource authDataSource,
            TenantTransactions transactions, NotificationMapper mapper, NotificationMail mail) {
        this.dataSource = dataSource; this.registry = new JdbcTemplate(authDataSource);
        this.transactions = transactions; this.mapper = mapper; this.mail = mail;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sweep() { runOnce(null); }

    /** tenants가 null이면 활성 회사 전체. @return 잠금을 얻어 실행했으면 true */
    public boolean runOnce(Collection<UUID> tenants) {
        try (var connection = dataSource.getConnection()) {
            var lock = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            if (!Boolean.TRUE.equals(lock.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY))) return false;
            try {
                for (UUID tenant : tenants == null ? activeTenants() : tenants) {
                    if (Thread.currentThread().isInterrupted()) break;
                    try { runTenant(tenant); }
                    catch (RuntimeException e) { LOG.warn("event=notification_mail outcome=tenant_failed tenant={} cause={}", tenant, e.getClass().getSimpleName()); }
                }
                return true;
            } finally {
                lock.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
            }
        } catch (java.sql.SQLException | DataAccessException e) {
            LOG.warn("event=notification_mail outcome=storage_unavailable cause={}", e.getClass().getSimpleName());
            return false;
        }
    }

    private List<UUID> activeTenants() {
        return registry.queryForList("SELECT id FROM platform.tenant_registry WHERE active ORDER BY id", UUID.class);
    }

    private void runTenant(UUID tenant) {
        var pending = transactions.execute(tenant, () -> mapper.pendingMail(tenant, BATCH));
        for (var p : pending) {
            if (!mail.enabled() || p.email() == null) {
                transactions.execute(tenant, () -> mapper.finishMail(tenant, p.id(), "SKIPPED"));
                continue;
            }
            var facts = new NotificationText.MailFacts(p.kind(), p.companyName(), p.requesterName(),
                    NotificationText.summary(p.requestKind(), p.typeName(), p.fromDate(), p.toDate()),
                    "REJECTED".equals(p.kind()) ? p.eventReason() : null, p.requestId());
            try {
                // 발송은 트랜잭션 밖: 발송 직후 서버가 멈추면 한 번 더 갈 수 있다(최소 1회 전달).
                mail.send(NotificationText.mail(p.email(), facts, mail.origin()));
                transactions.execute(tenant, () -> mapper.finishMail(tenant, p.id(), "SENT"));
            } catch (MailException e) {
                // 공급자 예외에는 받는 주소·본문이 들어 있을 수 있어 종류만 남긴다.
                transactions.execute(tenant, () -> mapper.retryMail(tenant, p.id(), p.mailAttempts() + 1));
                LOG.warn("event=notification_mail kind={} outcome=relay_unconfirmed attempt={}", p.kind(), p.mailAttempts() + 1);
            }
        }
    }
}
```

`TnaApplication`에 `@EnableScheduling`이 이미 있다(B 묶음). 없으면 추가한다.

- [ ] **Step 5: 설정**

`src/test/resources/config/application.yml`:

```yaml
# 시험 중에는 새벽 일괄 작업·알림 메일 발송 빈을 만들지 않는다. DailyAttendanceBatchIT만 켜고, NotificationMailerIT는 직접 만든다.
tna:
  daily-batch:
    enabled: false
  notification-mail:
    enabled: false
```

`src/main/resources/application.yml`의 `tna:` 아래 `daily-batch` 옆에 `notification-mail: { enabled: true }`를 같은 들여쓰기 형식으로 추가한다.

- [ ] **Step 6: 통과 확인** — `-Dit.test=NotificationMailerIT,ApprovalNotificationIT` → PASS. `-Dtest=NotificationTextTest,NotificationRulesTest` → PASS.

- [ ] **Step 7: 커밋** — `git add src/main/java/kr/tna/notification src/test/java/kr/tna/notification src/main/resources/application.yml src/test/resources/config/application.yml` → `git commit -m "feat: send approval notification mail every minute"`

---

### Task 5: 상단 종 아이콘과 알림 화면

**Files:**
- Create: `frontend/src/pages/NotificationsPage.tsx`
- Create: `frontend/src/pages/NotificationsPage.test.tsx`
- Modify: `frontend/src/App.tsx` (Icon `bell`, 상단 바 종, 페이지 연결, 제목 맵)
- Modify: `frontend/src/navigation.ts` (메뉴)
- Modify: `frontend/src/WorkspacePolish.css` (종 배지 스타일)
- Modify: `frontend/src/App.test.tsx` 또는 새 `frontend/src/App.notifications.test.tsx` (종 배지 시험)

- [ ] **Step 1: 실패하는 시험 작성**

`NotificationsPage.test.tsx` (`AttendanceExceptionsPage.test.tsx`의 렌더 방식 참고):

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { NotificationsPage } from './NotificationsPage';

const rows = [
  { id: 'n1', requestId: 'r1', kind: 'APPROVAL_REQUESTED' as const, requesterName: '홍길동', summary: '연차 12-01~12-02', createdAt: '2026-11-26 14:05', read: false },
  { id: 'n2', requestId: 'r2', kind: 'REJECTED' as const, requesterName: '홍길동', summary: '출퇴근 정정 11-20', createdAt: '2026-11-25 09:00', read: true },
];

describe('NotificationsPage', () => {
  it('lists notifications with links to the request detail and a read-all form', () => {
    render(<NotificationsPage rows={rows} />);
    expect(screen.getByRole('heading', { name: '알림' })).toBeInTheDocument();
    expect(screen.getByText('결재 요청')).toBeInTheDocument();
    expect(screen.getByText('반려')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '연차 12-01~12-02' })).toHaveAttribute('href', '/requests/r1');
    expect(screen.getByRole('button', { name: '모두 읽음' }).closest('form')).toHaveAttribute('action', '/me/notifications/read-all');
    expect(screen.getByText('안 읽음')).toBeInTheDocument();
  });

  it('shows an empty message and hides read-all without notifications', () => {
    render(<NotificationsPage rows={[]} />);
    expect(screen.getByText('받은 알림이 없습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '모두 읽음' })).toBeNull();
  });
});
```

종 배지 시험(App을 렌더하는 기존 시험 파일의 렌더 도우미를 그대로 쓴다. `data.workspaceNotifications`를 넣어 렌더):

```tsx
it('shows the unread notification count in the top bar', () => {
  renderWorkspace({ workspaceNotifications: 3 });   // 기존 App 시험의 렌더 도우미 이름에 맞춘다
  expect(screen.getByRole('link', { name: '알림 3건 안 읽음' })).toHaveAttribute('href', '/me/notifications');
  expect(screen.getByText('3')).toBeInTheDocument();
});
it('caps the badge at 99+ and hides it when everything is read', () => {
  renderWorkspace({ workspaceNotifications: 100 });
  expect(screen.getByText('99+')).toBeInTheDocument();
  cleanup();
  renderWorkspace({ workspaceNotifications: 0 });
  expect(screen.getByRole('link', { name: '알림' })).toBeInTheDocument();
});
```

- [ ] **Step 2: 실패 확인** — `cd frontend; npx vitest run src/pages/NotificationsPage.test.tsx <종 시험 파일>` → FAIL.

- [ ] **Step 3: 알림 화면 구현**

```tsx
import { Form } from '../ui';
import { PageHeader } from '../components/PageHeader';
import { WorklogSheet, sheetColumn } from '../components/WorklogSheet';

const kinds = { APPROVAL_REQUESTED: ['결재 요청', 'blue'], APPROVED: ['승인', 'green'], REJECTED: ['반려', 'amber'] } as const;

export interface NotificationRow {
  id: string; requestId: string; kind: keyof typeof kinds; requesterName: string; summary: string; createdAt: string; read: boolean;
}

export function NotificationsPage({ rows }: { rows: NotificationRow[] }) {
  const unread = rows.some(row => !row.read);
  return <>
    <PageHeader title="알림" actions={unread ? <Form action="/me/notifications/read-all"><button type="submit" className="t-btn">모두 읽음</button></Form> : undefined} />
    <section className="wl-section">
      <WorklogSheet
        label="알림 목록"
        title="최근 알림"
        rows={rows}
        getRowId={(row) => row.id}
        scope="all"
        emptyMessage="받은 알림이 없습니다."
        columns={[
          sheetColumn<NotificationRow>({ colId: 'kind', headerName: '구분', valueGetter: ({ data }) => data && kinds[data.kind][0], minWidth: 110 },
            (row) => <span className={`t-tag ${kinds[row.kind][1]}`}>{kinds[row.kind][0]}</span>),
          { colId: 'requesterName', headerName: '신청자', valueGetter: ({ data }) => data?.requesterName, minWidth: 110 },
          sheetColumn<NotificationRow>({ colId: 'summary', headerName: '신청 내용', valueGetter: ({ data }) => data?.summary, minWidth: 200, flex: 1 },
            (row) => <a href={`/requests/${row.requestId}`}>{row.summary}</a>),
          { colId: 'createdAt', headerName: '시각', valueGetter: ({ data }) => data?.createdAt, minWidth: 150 },
          sheetColumn<NotificationRow>({ colId: 'read', headerName: '읽음', valueGetter: ({ data }) => (data?.read ? '읽음' : '안 읽음'), minWidth: 90 },
            (row) => row.read ? <span className="t-subtle">읽음</span> : <strong>안 읽음</strong>),
        ]}
      />
    </section>
  </>;
}
```

`PageHeader`의 `actions` prop 이름·`sheetColumn`의 `flex` 지원 여부는 각 컴포넌트 정의를 열어 맞춘다. 시험이 WorklogSheet의 가상 렌더링 때문에 셀 텍스트를 못 찾으면 `AttendanceExceptionsPage.test.tsx`가 셀을 찾는 방법을 따른다.

- [ ] **Step 4: 앱 연결**

1. `App.tsx` `Icon`의 `paths`에 `bell: 'M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9 M10 21a2 2 0 0 0 4 0',`을 추가한다.
2. 상단 바 `<div className="t-profile">` 안 `<ThemeSwitcher />` 바로 다음에:
   ```tsx
   <a className="t-notification-bell" href="/me/notifications" aria-label={unread > 0 ? `알림 ${unread}건 안 읽음` : '알림'}>
     <Icon name="bell" />
     {unread > 0 && <span className="t-notification-count" aria-hidden="true">{unread > 99 ? '99+' : unread}</span>}
   </a>
   ```
   `unread`는 `employee`를 읽는 곳 근처에서 `const unread = Number(data.workspaceNotifications ?? 0);`로 정의한다.
3. 제목 맵(`'request/list': '신청 목록'`이 있는 객체)에 `'notification/list': '알림',`을 추가한다.
4. `Content`에 `{props.page === 'notification/list' && <NotificationsPage rows={(props.data.notifications ?? []) as NotificationRow[]} />}`를 추가하고 import한다.
5. `navigation.ts`의 `my-requests` 섹션 끝에 `item('notifications', '알림', '/me/notifications', employee, 'notification/list'),`를 추가한다. `navigation.test.ts`에 메뉴 개수·목록 고정 시험이 있으면 함께 고친다.
6. `WorkspacePolish.css`에 종 스타일을 추가한다(기존 상단 바 버튼 크기·색 토큰을 따른다):
   ```css
   .t-notification-bell { position: relative; display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; border-radius: 6px; color: var(--t-text-muted, inherit); }
   .t-notification-bell:hover { background: var(--t-surface-hover, rgba(0,0,0,.05)); }
   .t-notification-count { position: absolute; top: 1px; right: 0; min-width: 16px; height: 16px; padding: 0 4px; border-radius: 8px; background: #d92d20; color: #fff; font-size: 10px; font-weight: 700; line-height: 16px; text-align: center; }
   ```
   변수 이름은 `tokens.css`에 있는 실제 토큰으로 바꾼다.

- [ ] **Step 5: 통과 확인** — 위 vitest PASS, `npx vitest run src/navigation.test.ts src/App.test.tsx` PASS, `npm run build` 성공.

- [ ] **Step 6: 커밋** — `git add frontend/src` → `git commit -m "feat: show notification bell and notification list"`

---

### Task 6: 문서

**Files:**
- Modify: `docs/rules/workflow.md` (결재 알림 규칙 절 추가: 2절 표 요약, 메일 받는 주소, 재시도)
- Modify: `docs/design/approval-routes.md` 또는 `docs/design/leave-workflow.md` 중 결재 이력을 설명하는 문서 (알림 생성 위치 1~2줄)
- Modify: `docs/adr/0007-account-mail-delivery.md` (결재 알림 메일도 같은 설정을 따른다는 1~2줄)
- Modify: `README.md` (현재 상태에 E 묶음 1줄)

- [ ] **Step 1: 문서 반영** — 설계 문서의 결정만 옮긴다. 새 결정을 추가하지 않는다.
- [ ] **Step 2: 커밋** — `git commit -m "docs: describe approval notifications"`

---

### Task 7: 전체 회귀와 브라우저 확인 (컨트롤러가 수행)

- [ ] **Step 1:** 테스트 DB 컨테이너를 새로 띄운 상태에서 `mvn-test.ps1 verify`, `cd frontend; npx vitest run; npm run build`. 기대: 기존 알려진 오류 13건 외 실패 0건.
- [ ] **Step 2:** 로컬 데모로 브라우저 확인: 직원 휴가 신청 → 승인자 종 건수 1 → 알림 화면에서 상세 이동(건수 0) → 승인 → 직원 종 건수 1과 '승인' 알림 → 1분 뒤 DB에서 두 알림의 `mail_state='SENT'` → 모바일 390×844.
- [ ] **Step 3:** 결과 문서 `docs/quality/approval-notifications-20261126.md` 작성·커밋.
