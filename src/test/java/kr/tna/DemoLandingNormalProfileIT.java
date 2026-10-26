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
@ActiveProfiles("local")
class DemoLandingNormalProfileIT {
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
    void localAloneKeepsCompanyLoginAndDoesNotExposeDemoEntry() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"companyCode\"")))
                .andExpect(content().string(containsString("type=\"email\"")))
                .andExpect(content().string(containsString("minlength=\"12\"")))
                .andExpect(content().string(not(containsString("데모로 입장"))));
        mvc.perform(get("/")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/demo")).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/login").with(csrf()).param("email", "admin").param("password", "admin"))
                .andExpect(redirectedUrl("/login?error"));
    }
}
