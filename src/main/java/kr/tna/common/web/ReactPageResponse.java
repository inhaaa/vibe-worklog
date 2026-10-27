package kr.tna.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Safe transport errors for React requests, preserving the existing HTML security flow. */
public final class ReactPageResponse {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ReactPageResponse() { }

    public static boolean problem(HttpServletRequest request, HttpServletResponse response,
                                  int status, String message) throws IOException {
        return problem(request, response, status, message, false);
    }

    public static boolean problem(HttpServletRequest request, HttpServletResponse response,
                                  int status, String message, boolean reauthenticate) throws IOException {
        if (!ReactPageViewResolver.wantsJson(request)) return false;
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.addHeader("Vary", "Accept");
        response.setContentType(ReactPageViewResolver.MEDIA_TYPE + ";charset=UTF-8");
        var data = reauthenticate ? Map.of("status", status, "message", message, "reauthenticate", true)
                : Map.of("status", status, "message", message);
        response.getWriter().write(JSON.writeValueAsString(Map.of("page", "org/problem", "data", data, "csrf", Map.of())));
        return true;
    }
}
