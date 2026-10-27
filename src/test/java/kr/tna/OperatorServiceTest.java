package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.common.auth.InvitationDelivery;
import kr.tna.console.OperatorData.*;
import kr.tna.console.OperatorMapper;
import kr.tna.console.OperatorService;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class OperatorServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-26T03:00:00Z");
    private final OperatorMapper mapper = mock(OperatorMapper.class);
    private final OperatorPrincipal actor = new OperatorPrincipal(UUID.randomUUID(), "synthetic-operator", 1, NOW);
    private final List<Tenant> tenants = List.of(new Tenant(UUID.randomUUID(), "SYNTHETIC", "합성 회사", true, 1));
    private final List<Audit> audits = List.of(new Audit(UUID.randomUUID(), UUID.randomUUID(), "TENANT_ONBOARD",
            "합성 사유", "합성 관계 확인", "tna_operator", NOW));
    private OperatorService service;

    @BeforeEach
    void transactionWithoutAnExternalDatabase() throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(mock(Connection.class));
        service = new OperatorService(mapper, source, Clock.fixed(NOW, ZoneOffset.UTC), mock(InvitationDelivery.class));
        when(mapper.current(actor)).thenReturn(true);
    }

    @Test
    void tenantReadDoesNotReturnDataWhenTheOperatorIsRevokedDuringTheRead() {
        when(mapper.current(actor)).thenReturn(true, false);
        when(mapper.tenants()).thenReturn(tenants);
        assertStatus(403, () -> service.tenants(actor));
    }

    @Test
    void auditReadDoesNotReturnDataWhenTheOperatorIsRevokedDuringTheRead() {
        when(mapper.current(actor)).thenReturn(true, false);
        when(mapper.audit()).thenReturn(audits);
        assertStatus(403, () -> service.audit(actor));
    }

    @Test
    void currentOperatorReceivesTheTenantAndAuditResults() {
        when(mapper.tenants()).thenReturn(tenants);
        when(mapper.audit()).thenReturn(audits);
        assertEquals(tenants, service.tenants(actor));
        assertEquals(audits, service.audit(actor));
    }

    @Test
    void failedFinalValidationDoesNotReturnTheAlreadyReadData() {
        when(mapper.current(actor)).thenReturn(true).thenThrow(new DataAccessResourceFailureException("synthetic failure"));
        when(mapper.tenants()).thenReturn(tenants);
        var error = assertStatus(503, () -> service.tenants(actor));
        assertFalse(error.getMessage().contains("synthetic failure"));
    }

    @Test
    void failedMetadataReadDoesNotReturnData() {
        when(mapper.audit()).thenThrow(new DataAccessResourceFailureException("synthetic failure"));
        assertStatus(503, () -> service.audit(actor));
    }

    @Test
    void revokedOperatorCannotStartAMetadataRead() {
        when(mapper.current(actor)).thenReturn(false);
        assertStatus(403, () -> service.tenants(actor));
        verify(mapper, never()).tenants();
    }

    @Test
    void stateCommandsKeepTheirExistingAuthorizationAndCommitBoundary() {
        UUID tenant = UUID.randomUUID();
        var form = new StateForm(false, 1L, "합성 이용 중지", UUID.randomUUID());
        when(mapper.state(eq(actor), eq(tenant), any(), anyString())).thenReturn(tenant);
        when(mapper.current(actor)).thenReturn(true, false);
        assertEquals(tenant, service.state(actor, tenant, form));
        verify(mapper, times(1)).current(actor);
    }

    private static OrgException assertStatus(int expected, Runnable action) {
        var error = assertThrows(OrgException.class, action::run);
        assertEquals(expected, error.status());
        return error;
    }
}
