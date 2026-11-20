package kr.tna.org;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Shared transaction boundary for tenant administration, not a pre-authentication store. */
@Component
public final class OrgCommands {
    private final TenantTransactions transactions;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public OrgCommands(TenantTransactions transactions, @Qualifier("dataSource") DataSource source, Clock clock) {
        this.transactions = transactions;
        this.jdbc = new JdbcTemplate(source);
        this.clock = clock;
    }

    public LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }

    Instant creationTime() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }

    public <T> T read(TnaPrincipal actor, Supplier<T> action) {
        if (actor == null) throw OrgException.forbidden();
        return transactions.execute(actor.tenantId(), () -> {
            requireAdmin(actor);
            T result = action.get();
            // Administrative snapshots must not escape after a revocation committed while waiting.
            requireAdmin(actor);
            return result;
        });
    }

    public <T> T readShared(TnaPrincipal actor, Supplier<T> action) {
        return read(actor, () -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock_shared(hashtextextended(CAST(? AS text),0))",
                    Object.class, actor.tenantId());
            requireAdmin(actor);
            return action.get();
        });
    }

    public UUID execute(TnaPrincipal actor, UUID commandId, String action, String kind,
                        String inputHash, String reason, Supplier<Change> mutation) {
        if (actor == null) throw OrgException.forbidden();
        if (commandId == null) throw OrgException.invalid("명령 식별자가 필요합니다. 화면을 다시 열어 주세요.");
        String checkedReason = requiredText(reason, 500, "변경 사유");
        try {
            return transactions.execute(actor.tenantId(), () -> {
                // One lock order protects hierarchy and the last administrator across concurrent commands.
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))",
                        Object.class, actor.tenantId());
                requireAdmin(actor);
                var replay = jdbc.query("""
                        SELECT actor_account_id, action, target_kind, input_hash, target_id
                        FROM tna.org_audit_event WHERE tenant_id = ? AND command_id = ?
                        """, (row, i) -> new Replay(row.getObject(1, UUID.class), row.getString(2),
                        row.getString(3), row.getString(4), row.getObject(5, UUID.class)), actor.tenantId(), commandId);
                if (!replay.isEmpty()) {
                    Replay old = replay.getFirst();
                    if (!old.actor().equals(actor.accountId()) || !old.action().equals(action)
                            || !old.kind().equals(kind) || !old.hash().equals(inputHash)) {
                        throw OrgException.conflict("같은 명령 식별자로 다른 변경을 요청할 수 없습니다.");
                    }
                    return old.target();
                }
                Change changed = Objects.requireNonNull(mutation.get());
                jdbc.update("""
                        INSERT INTO tna.org_audit_event
                        (tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,input_hash,
                         reason,before_state,after_state,request_trace_id)
                        VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?)
                        """, actor.tenantId(), UUID.randomUUID(), commandId, actor.accountId(), action, kind,
                        changed.targetId(), inputHash, checkedReason, json.writeValueAsString(changed.before()),
                        json.writeValueAsString(changed.after()), UUID.randomUUID());
                return changed.targetId();
            });
        } catch (DataIntegrityViolationException exception) {
            throw OrgException.conflict("이미 사용 중인 값이나 다른 변경과 충돌합니다. 최신 내용을 확인해 주세요.");
        } catch (DataAccessException | JacksonException exception) {
            throw new OrgException(503, "변경을 완료하지 못했습니다. 같은 명령으로 다시 확인해 주세요.");
        }
    }

    public BulkData.Result executeBatch(TnaPrincipal actor, UUID commandId, String action, String kind,
            String inputHash, String reason, Supplier<List<BulkData.Change>> mutation) {
        if (actor == null) throw OrgException.forbidden();
        if (commandId == null) throw OrgException.invalid("명령 식별자가 필요합니다. 화면을 다시 열어 주세요.");
        String checkedReason = requiredText(reason, 500, "변경 사유");
        try {
            return transactions.execute(actor.tenantId(), () -> {
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),0))",
                        Object.class, actor.tenantId());
                requireAdmin(actor);
                var previous = jdbc.query("""
                        SELECT actor_account_id,action,target_kind,input_hash,target_id,after_state::text
                        FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?
                        """, (row, index) -> new BatchReplay(new Replay(row.getObject(1, UUID.class), row.getString(2),
                            row.getString(3), row.getString(4), row.getObject(5, UUID.class)), row.getString(6)),
                        actor.tenantId(), commandId);
                if (!previous.isEmpty()) {
                    var old = previous.getFirst();
                    if (!old.replay().actor().equals(actor.accountId()) || !old.replay().action().equals(action)
                            || !old.replay().kind().equals("BATCH") || !old.replay().hash().equals(inputHash)) {
                        throw OrgException.conflict("같은 명령 식별자로 다른 변경을 요청할 수 없습니다.");
                    }
                    var restored = json.readValue(old.result(), BulkData.Result.class);
                    if (restored.batchId() == null || !restored.batchId().equals(old.replay().target())
                            || restored.rows() == null || restored.rows().isEmpty() || restored.rows().size() > 50
                            || restored.rows().stream().anyMatch(row -> row == null || row.id() == null
                                || row.clientRowId() == null || row.version() < 0)) {
                        throw new OrgException(503, "저장 결과를 확인할 수 없습니다. 담당자에게 문의해 주세요.");
                    }
                    return restored;
                }
                List<BulkData.Change> changes = List.copyOf(Objects.requireNonNull(mutation.get()));
                if (changes.isEmpty() || changes.size() > 50) throw new IllegalStateException("A batch requires 1 to 50 changes");
                UUID batchId = UUID.randomUUID();
                var result = new BulkData.Result(batchId, changes.stream()
                        .map(change -> new BulkData.RowResult(change.clientRowId(), change.id(), change.version())).toList());
                for (var change : changes) {
                    batchAudit(actor, UUID.randomUUID(), action + "_ROW", kind, change.id(), inputHash,
                            checkedReason, change.before(), change.after(), batchId);
                }
                batchAudit(actor, commandId, action, "BATCH", batchId, inputHash, checkedReason, null, result, batchId);
                return result;
            });
        } catch (DataIntegrityViolationException conflict) {
            throw OrgException.conflict("이미 사용 중인 값이나 다른 변경과 충돌합니다. 최신 내용을 확인해 주세요.");
        } catch (DataAccessException | JacksonException unavailable) {
            throw new OrgException(503, "변경을 완료하지 못했습니다. 같은 명령으로 다시 확인해 주세요.");
        }
    }

    private void batchAudit(TnaPrincipal actor, UUID commandId, String action, String kind, UUID target,
            String hash, String reason, Object before, Object after, UUID batchId) {
        jdbc.update("""
                INSERT INTO tna.org_audit_event
                (tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,input_hash,
                 reason,before_state,after_state,request_trace_id)
                VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?)
                """, actor.tenantId(), UUID.randomUUID(), commandId, actor.accountId(), action, kind, target,
                hash, reason, json.writeValueAsString(before), json.writeValueAsString(after), batchId);
    }

    private void requireAdmin(TnaPrincipal actor) {
        if (!actor.roles().contains("ATTENDANCE_ADMIN")) throw OrgException.forbidden();
        int allowed = jdbc.queryForObject("""
                SELECT count(*) FROM tna.tenant_account a
                JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
                JOIN platform.tenant_registry r ON r.id=a.tenant_id
                JOIN tna.role_grant g ON g.tenant_id=a.tenant_id AND g.account_id=a.id
                WHERE a.tenant_id=? AND a.id=? AND a.employee_id=? AND a.auth_version=?
                  AND a.active AND e.active AND r.active AND g.active AND g.role='ATTENDANCE_ADMIN'
                  AND e.joined_on<=? AND (e.left_on IS NULL OR e.left_on>=?)
                """, Integer.class, actor.tenantId(), actor.accountId(), actor.employeeId(),
                actor.authVersion(), today(), today());
        if (allowed != 1) throw OrgException.forbidden();
    }

    public static String requiredText(String value, int maximum, String label) {
        if (value == null) throw OrgException.invalid(label + "을(를) 입력해 주세요.");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > maximum
                || normalized.codePoints().anyMatch(c -> Character.isISOControl(c)
                    || c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE)) {
            throw OrgException.invalid(label + "은(는) 공백이 아닌 " + maximum + "자 이내로 입력해 주세요.");
        }
        return normalized;
    }

    public static void checkVersion(Long expected, long actual) {
        if (expected == null || expected < 0) throw OrgException.invalid("현재 버전이 필요합니다. 화면을 다시 열어 주세요.");
        if (expected != actual) throw OrgException.conflict("다른 변경이 반영되었습니다. 최신 내용을 다시 확인해 주세요.");
    }

    public static int offset(int page) {
        if (page < 0 || page > 1_000_000) throw OrgException.invalid("페이지 범위가 올바르지 않습니다.");
        return page * 50;
    }

    public static String fingerprint(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                byte[] value = (part == null ? "" : part.toString()).getBytes(StandardCharsets.UTF_8);
                digest.update((part == null ? "N:" : "V:" + value.length + ":").getBytes(StandardCharsets.US_ASCII));
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record Change(UUID targetId, Object before, Object after) { }
    private record Replay(UUID actor, String action, String kind, String hash, UUID target) { }
    private record BatchReplay(Replay replay, String result) { }
}
