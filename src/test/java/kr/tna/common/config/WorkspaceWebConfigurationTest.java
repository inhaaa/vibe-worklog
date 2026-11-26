package kr.tna.common.config;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.AuthStore;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.notification.NotificationService;
import kr.tna.org.EmployeeMapper;
import kr.tna.org.EmployeeOverview;
import kr.tna.org.EmployeePersonnelData.Header;
import kr.tna.org.EmployeePersonnelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.ModelAndView;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceWebConfigurationTest {
    private final TnaPrincipal principal = new TnaPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            1, Set.of("EMPLOYEE"), Instant.EPOCH);
    private final EmployeeOverview employee = new EmployeeOverview("합성사원", "합성회사");
    private final EmployeePersonnelService personnel = mock(EmployeePersonnelService.class);
    private final TenantTransactions tenants = mock(TenantTransactions.class);
    private final AuthStore auth = mock(AuthStore.class);
    private final WorkspaceWebConfiguration configuration = new WorkspaceWebConfiguration(tenants,
            mock(EmployeeMapper.class), auth, mock(LocalDemoLogin.class), personnel, mock(NotificationService.class));

    @BeforeEach
    void authenticate() {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
        when(tenants.execute(eq(principal.tenantId()), any())).thenReturn(employee);
        when(auth.isCurrent(principal)).thenReturn(true);
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void homeKeepsWorkspaceIdentityWithoutLoadingPersonnelHeader() {
        var view = load("/me/home", "workspace/home");
        assertSame(employee, view.getModel().get("workspaceEmployee"));
        assertSame(principal, view.getModel().get("workspacePrincipal"));
        assertFalse(view.getModel().containsKey("employeeHeader"));
        verifyNoInteractions(personnel);
    }

    @Test
    void profileKeepsThePersonnelHeader() {
        var header = mock(Header.class);
        when(personnel.selfHeader(principal)).thenReturn(header);

        assertSame(header, load("/me/profile", "workspace/profile").getModel().get("employeeHeader"));
    }

    private ModelAndView load(String path, String page) {
        var view = new ModelAndView(page);
        configuration.postHandle(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), this, view);
        return view;
    }
}
