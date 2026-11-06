package kr.tna.common.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.AuthStore;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

class WorkspaceEntryTest {
    @Test void demoOffersBothFixedPersonasWithoutAuthenticatingOnGet() {
        var mode = mock(LocalDemoLogin.class);
        when(mode.enabled()).thenReturn(true);
        var model = new ExtendedModelMap();
        var store = mock(AuthStore.class);
        var accounts = java.util.List.of(new AuthStore.DemoAccount("99999", "담당자", java.util.Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), "admin"));
        when(store.demoAccounts()).thenReturn(accounts);
        assertEquals("demo", new DemoController(mode, store).demo(model));
        assertEquals(accounts, model.get("demoAccounts"));
        assertEquals("admin", model.get("demoLogin"));
        assertEquals("admin", model.get("demoPassword"));
        assertEquals("approver", model.get("demoApproverLogin"));
        assertEquals("approver", model.get("demoApproverPassword"));
    }

    @Test void oldTodayBookmarkRedirectsButStatusFragmentIsPreserved() {
        var controller = new TodayController();
        assertEquals("redirect:/me/attendance", controller.today());
        assertEquals("fragments/status :: status", controller.status());
    }

    @Test void normalIndexUsesHomeWhileLocalDemoKeepsItsLandingPage() {
        var demo = mock(LocalDemoLogin.class);
        var controller = new DemoController(demo, mock(AuthStore.class));
        assertEquals("redirect:/me/home", controller.index(null));
        when(demo.enabled()).thenReturn(true);
        assertEquals("redirect:/demo", controller.index(null));
    }
}
