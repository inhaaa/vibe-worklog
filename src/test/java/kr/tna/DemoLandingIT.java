package kr.tna;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "local-demo"})
class DemoLandingIT {
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> DatabaseFixture.start().jdbcUrl());
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("tna.auth.url", () -> DatabaseFixture.start().jdbcUrl());
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @Test
    void anonymousEntryRoutesLeadToPublicIntroductionWithoutAuthenticating() throws Exception {
        mvc.perform(get("/")).andExpect(redirectedUrl("/demo"));
        mvc.perform(get("/login")).andExpect(redirectedUrl("/demo"));
        mvc.perform(get("/demo")).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.page());
        mvc.perform(get("/me/today")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/me/schedule")).andExpect(redirectedUrl("/login"));
    }

    @Test
    void loginRedirectPreservesOnlyGenericFailureAndLogoutFlags() throws Exception {
        mvc.perform(get("/login").param("error", "").param("tenant_id", "untrusted"))
                .andExpect(redirectedUrl("/demo?error"));
        mvc.perform(get("/login").param("logout", ""))
                .andExpect(redirectedUrl("/demo?logout"));
        mvc.perform(get("/demo").param("error", ""))
                .andExpect(ReactPageAssertions.data("/flags/error", true));
        mvc.perform(get("/demo").param("logout", ""))
                .andExpect(ReactPageAssertions.data("/flags/logout", true));
    }

    @Test
    void introductionUsesExistingLoginPostCsrfAndSelfHostedAssets() throws Exception {
        mvc.perform(get("/demo")).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.page())
                .andExpect(ReactPageAssertions.csrf())
                .andExpect(ReactPageAssertions.data("/demoLogin", "admin"))
                .andExpect(ReactPageAssertions.data("/demoPassword", "admin"))
                .andExpect(content().string(containsString("/react/app.js")))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(content().string(not(anyOf(containsString("onclick="), containsString("style="), containsString("https://cdn")))));
        mvc.perform(get("/react/app.js")).andExpect(status().isOk());
        mvc.perform(post("/login").param("email", "admin").param("password", "admin"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/demo").with(csrf())).andExpect(redirectedUrl("/login"));
    }
}
