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
