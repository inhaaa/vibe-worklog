package kr.tna.common.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import kr.tna.common.auth.LocalDemoLogin;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

class WorkspaceEntryTest {
    @Test void demoOffersBothFixedPersonasWithoutAuthenticatingOnGet() {
        var mode = mock(LocalDemoLogin.class);
        when(mode.enabled()).thenReturn(true);
        var model = new ExtendedModelMap();
        assertEquals("demo", new DemoController(mode).demo(model));
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

    @Test void normalIndexUsesProfileWhileLocalDemoKeepsItsLandingPage() {
        var demo = mock(LocalDemoLogin.class);
        var controller = new DemoController(demo);
        assertEquals("redirect:/me/profile", controller.index(null));
        when(demo.enabled()).thenReturn(true);
        assertEquals("redirect:/demo", controller.index(null));
    }
}
