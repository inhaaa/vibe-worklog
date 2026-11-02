package kr.tna;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.web.ReactPageViewResolver;
import kr.tna.org.EmployeeOverview;
import kr.tna.request.RequestData;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.validation.BeanPropertyBindingResult;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class ReactPageViewTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ReactPageViewResolver resolver = new ReactPageViewResolver(json);

    @Test
    void annualAndAttributionPagesKeepTheirExplicitModelsWithoutPrivateSources() throws Exception {
        for (var entry : Map.of("request/mine", "annual", "request/attributions", "leaveAttributions").entrySet()) {
            var view = resolver.resolveViewName(entry.getKey(), Locale.KOREA);
            assertNotNull(view, entry.getKey());
            var response = new MockHttpServletResponse();
            view.render(Map.of(entry.getValue(), Map.of("year", 2026), "privateSource", "NEVER_EXPOSE"), request(true), response);
            assertEquals(2026, json.readTree(response.getContentAsString()).at("/data/" + entry.getValue() + "/year").asInt());
            assertFalse(response.getContentAsString().contains("NEVER_EXPOSE"));
        }
        var response = render("request/mine", Map.of("compose", Map.of("kind", "LEAVE_DAY", "mode", "MODAL")), request(true));
        assertEquals("MODAL", json.readTree(response.getContentAsString()).at("/data/compose/mode").asString());
    }

    @Test
    void codeManagementPagesExposeOnlyTheirNamedModels() throws Exception {
        for (var entry : Map.of("org/common-codes", "commonCodes", "org/attendance-types", "attendanceTypes").entrySet()) {
            var view = resolver.resolveViewName(entry.getKey(), Locale.KOREA);
            assertNotNull(view, entry.getKey());
            var response = new MockHttpServletResponse();
            view.render(Map.of(entry.getValue(), Map.of("code", "SAFE"), "privateSource", "NEVER_EXPOSE"), request(true), response);
            assertEquals("SAFE", json.readTree(response.getContentAsString()).at("/data/" + entry.getValue() + "/code").asString());
            assertFalse(response.getContentAsString().contains("NEVER_EXPOSE"));
        }
    }

    @Test
    void jsonUsesOnlyThePageAllowlistAndMinimalIdentity() throws Exception {
        var request = request(true);
        var model = new LinkedHashMap<String, Object>();
        model.put("employee", new EmployeeOverview("직원", "합성 회사"));
        model.put("workspacePrincipal", new TnaPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                99, Set.of("EMPLOYEE"), Instant.now()));
        model.put("workspaceEmployee", new EmployeeOverview("직원", "합성 회사"));
        model.put("password", "private-password");
        model.put("token", "private-token");
        model.put("org.springframework.validation.BindingResult.employee", new BeanPropertyBindingResult(model, "employee"));
        var response = render("today", model, request);
        var page = json.readTree(response.getContentAsString());
        assertEquals("today", page.get("page").asString());
        assertEquals("직원", page.at("/data/employee/employeeName").asString());
        assertEquals("EMPLOYEE", page.at("/data/workspacePrincipal/roles/0").asString());
        assertEquals(1, page.at("/data/workspacePrincipal").size());
        assertFalse(response.getContentAsString().contains("authVersion"));
        assertFalse(response.getContentAsString().contains("private-"));
        assertFalse(response.getContentAsString().contains("BindingResult"));
        assertEquals("csrf-value", page.at("/csrf/token").asString());
        assertEquals("_csrf", page.at("/csrf/parameterName").asString());
        assertEquals("X-CSRF-TOKEN", page.at("/csrf/headerName").asString());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("Accept", response.getHeader("Vary"));
        assertTrue(response.getContentType().startsWith(ReactPageViewResolver.MEDIA_TYPE));
    }

    @Test
    void shellBootDataCannotCloseItsNonExecutableScript() throws Exception {
        String malicious = "</script><img src=x onerror=alert(1)>&\u2028\u2029";
        var response = render("org/problem", Map.of("message", malicious, "status", 422), request(false));
        String body = response.getContentAsString();
        assertTrue(body.contains("<div id=\"root\"></div>"));
        assertTrue(body.contains("<script id=\"worklog-page\" type=\"application/json\">"));
        assertTrue(body.contains("src=\"/react/app.js\""));
        assertTrue(body.contains("href=\"/app.css\""));
        assertTrue(body.contains("href=\"/react/app.css\""));
        assertTrue(body.contains("href=\"/react/sheet.css\""));
        assertTrue(body.indexOf("href=\"/app.css\"") < body.indexOf("href=\"/react/sheet.css\""));
        assertFalse(body.contains("<img"));
        assertTrue(body.contains("\\u003c/script\\u003e"));
        assertTrue(body.contains("\\u0026\\u2028\\u2029"));
        String boot = body.substring(body.indexOf("application/json\">") + "application/json\">".length(), body.indexOf("</script>"));
        assertEquals(malicious, json.readTree(boot).at("/data/message").asString());
    }

    @Test
    void fragmentsNormalizeAndRedirectsAndUnknownViewsAreNeverResolved() throws Exception {
        assertEquals("fragments/status", json.readTree(render("fragments/status :: status", Map.of(), request(true))
                .getContentAsString()).get("page").asString());
        assertNull(resolver.resolveViewName("redirect:/me/today", Locale.KOREA));
        assertNull(resolver.resolveViewName("forward:/private", Locale.KOREA));
        assertNull(resolver.resolveViewName("unknown/private", Locale.KOREA));
    }

    @Test
    void errorStatusAndRequestFlagsSurviveRenderingWithoutReflectingQuerySecrets() throws Exception {
        var request = request(true);
        request.addParameter("error", "");
        request.addParameter("token", "never-reflect");
        var response = new MockHttpServletResponse();
        response.setStatus(422);
        resolver.resolveViewName("invite", Locale.KOREA).render(Map.of("companyCode", "SYNTHETIC"), request, response);
        assertEquals(422, response.getStatus());
        assertTrue(json.readTree(response.getContentAsString()).at("/data/flags/error").asBoolean());
        assertFalse(response.getContentAsString().contains("never-reflect"));
    }

    @Test
    void correctionEvidenceExposesOnlyValidatedBreakIntervalsAndNeverStoredSnapshots() throws Exception {
        var correction = new RequestData.CorrectionDetail(java.time.LocalDate.of(2026, 1, 1), 1, "fingerprint",
                "KEEP", "KEEP", null, null, true, true,
                "[{\"startAt\":\"2026-01-01T03:00:00Z\",\"endAt\":\"2026-01-01T04:00:00Z\",\"secret\":\"PRIVATE_EXTRA\"}]",
                null, "PRIVATE_SNAPSHOT");
        var detail = new RequestData.RequestDetail(null, java.util.List.of(), correction, java.util.List.of());
        var response = render("request/detail", Map.of("detail", detail), request(true));
        assertFalse(response.getContentAsString().contains("PRIVATE_"));
        assertFalse(response.getContentAsString().contains("beforeSnapshot"));
        assertEquals("2026-01-01T03:00:00Z", json.readTree(json.readTree(response.getContentAsString())
                .at("/data/detail/correction/actualBreaks").asString()).get(0).get("startAt").asString());
    }

    @Test
    void identityAndCompanyPagesExposeOnlyTheirExplicitSafeModels() throws Exception {
        var pages = Map.of("workspace/profile", "profile", "org/access-list", "accessRows",
                "company/attendance", "companyAttendance", "company/requests", "companyRequests");
        for (var page : pages.entrySet()) {
            var view = resolver.resolveViewName(page.getKey(), Locale.KOREA);
            assertNotNull(view, page.getKey() + " must have an explicit React page contract");
            var response = new MockHttpServletResponse();
            view.render(Map.of(page.getValue(), Map.of("label", "합성 조회"),
                    "privateSource", "NEVER_EXPOSE_PRIVATE_SOURCE"), request(true), response);
            var data = json.readTree(response.getContentAsString()).get("data");
            assertEquals("합성 조회", data.at("/" + page.getValue() + "/label").asString());
            assertFalse(response.getContentAsString().contains("NEVER_EXPOSE_PRIVATE_SOURCE"));
            assertEquals("no-store", response.getHeader("Cache-Control"));
        }
    }

    @Test
    void sheetAndOrganizationPagesHaveExplicitSafeModels() throws Exception {
        var pages = Map.of("org/organizations", "organizationSheet", "org/employees", "employeeSheet",
                "org/organization-heads", "organizationHeads", "company/organizations", "companyOrganizations");
        for (var page : pages.entrySet()) {
            var view = resolver.resolveViewName(page.getKey(), Locale.KOREA);
            assertNotNull(view, page.getKey());
            var response = new MockHttpServletResponse();
            view.render(Map.of(page.getValue(), Map.of("commandId", "safe-model"),
                    "privateSource", "DO_NOT_EXPOSE_SHEET_SOURCE"), request(true), response);
            assertEquals("safe-model", json.readTree(response.getContentAsString())
                    .at("/data/" + page.getValue() + "/commandId").asString());
            assertFalse(response.getContentAsString().contains("DO_NOT_EXPOSE_SHEET_SOURCE"));
        }
    }

    @Test
    void approvalManagementPagesExposeOnlyTheirNamedModels() throws Exception {
        for (var page : Map.of("org/approval-routes", "approvalRoutes",
                "org/approval-route-assignments", "approvalRouteAssignments").entrySet()) {
            var view = resolver.resolveViewName(page.getKey(), Locale.KOREA);
            assertNotNull(view, page.getKey());
            var response = new MockHttpServletResponse();
            view.render(Map.of(page.getValue(), Map.of("label", "합성 결재선"),
                    "privateSource", "DO_NOT_EXPOSE_APPROVAL_SOURCE"), request(true), response);
            assertEquals("합성 결재선", json.readTree(response.getContentAsString())
                    .at("/data/" + page.getValue() + "/label").asString());
            assertFalse(response.getContentAsString().contains("DO_NOT_EXPOSE_APPROVAL_SOURCE"));
        }
    }

    private MockHttpServletRequest request(boolean api) {
        var request = new MockHttpServletRequest();
        if (api) request.addHeader("Accept", "application/vnd.worklog.page+json");
        request.setAttribute("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "csrf-value"));
        return request;
    }

    private MockHttpServletResponse render(String page, Map<String, ?> model, MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        resolver.resolveViewName(page, Locale.KOREA).render(model, request, response);
        return response;
    }
}
