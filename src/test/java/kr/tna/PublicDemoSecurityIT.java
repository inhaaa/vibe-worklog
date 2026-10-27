package kr.tna;

import kr.tna.common.auth.LocalDemoLogin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Reuses the real DB/hash/current-role/CSRF regression against the external demo profile. */
@ActiveProfiles(value = "public-demo", inheritProfiles = false)
class PublicDemoSecurityIT extends LocalDemoWebIT {
    @Autowired MockMvc publicMvc;

    @Test
    void publicLandingIdentifiesTheSharedSyntheticEnvironment() throws Exception {
        publicMvc.perform(get("/demo"))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/publicDemo", true));
    }

    @Test
    void encodedWhitespaceCannotChangeTheSharedAccountRoles() throws Exception {
        var session = (MockHttpSession) publicMvc.perform(post("/login").with(csrf())
                        .param("email", "admin").param("password", "admin")
                        .with(request -> { request.setRemoteAddr("198.51.100.251"); return request; }))
                .andExpect(redirectedUrl("/me/today"))
                .andReturn().getRequest().getSession(false);
        var path = java.net.URI.create("/admin/employees/%20" + LocalDemoLogin.EMPLOYEE_ID + "%20/roles");
        publicMvc.perform(post(path).session(session).with(csrf())
                        .param("roles", "EMPLOYEE", "ATTENDANCE_ADMIN", "APPROVER")
                        .param("expectedVersion", "1").param("reason", "공개 합성 데모 계정 보호 검증")
                        .param("commandId", java.util.UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        publicMvc.perform(get("/me/today").session(session)).andExpect(status().isOk());
    }

    @Test
    void visitorCannotDisableTheSharedDemoIdentity() throws Exception {
        var session = (MockHttpSession) publicMvc.perform(post("/login").with(csrf())
                        .param("email", "admin").param("password", "admin")
                        .with(request -> { request.setRemoteAddr("198.51.100.250"); return request; }))
                .andExpect(redirectedUrl("/me/today"))
                .andReturn().getRequest().getSession(false);
        String employee = "/admin/employees/" + LocalDemoLogin.EMPLOYEE_ID;
        for (String suffix : new String[]{"/changes", "/roles", "/invitations"}) {
            publicMvc.perform(post(employee + suffix).session(session).with(csrf()))
                    .andExpect(status().isForbidden());
            publicMvc.perform(post("/admin/employees/" + LocalDemoLogin.EMPLOYEE_ID.toString()
                            .toUpperCase(java.util.Locale.ROOT) + suffix + "?demo=1")
                            .session(session).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        publicMvc.perform(get("/me/today").session(session)).andExpect(status().isOk());
        publicMvc.perform(get("/operator/tenants").session(session)).andExpect(status().isForbidden());
    }
}
