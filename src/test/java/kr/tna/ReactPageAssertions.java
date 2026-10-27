package kr.tna;

import java.nio.charset.StandardCharsets;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Server tests assert the boot contract; React tests own rendered controls and copy. */
final class ReactPageAssertions {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ReactPageAssertions() { }

    static ResultMatcher page() {
        return result -> {
            var page = envelope(result);
            assertTrue(page.get("page").isString());
            assertTrue(page.get("data").isObject());
            assertTrue(result.getResponse().getHeader("Cache-Control").contains("no-store"));
            var view = result.getModelAndView();
            if (view != null && view.getViewName() != null) {
                assertEquals(view.getViewName().split("\\s*::", 2)[0].strip(), page.get("page").asString());
            }
        };
    }

    static ResultMatcher csrf() {
        return result -> {
            var csrf = envelope(result).get("csrf");
            assertFalse(csrf.get("token").asString().isBlank());
            assertEquals("_csrf", csrf.get("parameterName").asString());
        };
    }

    static ResultMatcher data(String pointer, Object expected) {
        return result -> assertEquals(JSON.valueToTree(expected), envelope(result).at("/data" + pointer));
    }

    static ResultMatcher dataPresent(String pointer) {
        return result -> {
            var value = envelope(result).at("/data" + pointer);
            assertFalse(value.isMissingNode(), pointer);
            assertFalse(value.isNull(), pointer);
        };
    }

    static ResultMatcher roles(String... expected) {
        return result -> {
            var actual = new java.util.HashSet<String>();
            envelope(result).at("/data/workspacePrincipal/roles").forEach(role -> actual.add(role.asString()));
            assertEquals(java.util.Set.of(expected), actual);
            assertEquals(1, envelope(result).at("/data/workspacePrincipal").size());
        };
    }

    static JsonNode envelope(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (body.startsWith("{")) return JSON.readTree(body);
        String marker = "<script id=\"worklog-page\" type=\"application/json\">";
        int start = body.indexOf(marker);
        assertTrue(start >= 0, "React boot 데이터가 없습니다.");
        return JSON.readTree(body.substring(start + marker.length(), body.indexOf("</script>", start)));
    }
}
