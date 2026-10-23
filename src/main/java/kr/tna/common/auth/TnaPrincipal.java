package kr.tna.common.auth;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Session identity contains no password hash or client-selected tenant. */
public record TnaPrincipal(UUID tenantId, UUID accountId, UUID employeeId, long authVersion,
                           Set<String> roles, Instant authenticatedAt) implements Principal, Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public TnaPrincipal {
        roles = Set.copyOf(roles);
    }

    @Override
    public String getName() {
        return accountId.toString();
    }
}
