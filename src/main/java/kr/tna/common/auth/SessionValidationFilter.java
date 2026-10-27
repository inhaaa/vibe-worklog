package kr.tna.common.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import kr.tna.common.web.ReactPageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

public final class SessionValidationFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(SessionValidationFilter.class);
    private final AuthStore store;
    private final Clock clock;
    private final AuthenticationEntryPoint entryPoint;

    public SessionValidationFilter(AuthStore store, Clock clock, AuthenticationEntryPoint entryPoint) {
        this.store = store;
        this.clock = clock;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof TnaPrincipal principal) {
            try {
                Duration age = Duration.between(principal.authenticatedAt(), clock.instant());
                if (age.isNegative() || age.compareTo(Duration.ofHours(12)) >= 0 || !store.isCurrent(principal)) {
                    invalidate(request);
                    response.setHeader("Cache-Control", "no-store");
                    if (!request.getServletPath().equals("/login") && !request.getRequestURI().equals("/login")) {
                        entryPoint.commence(request, response, null);
                        return;
                    }
                }
            } catch (DataAccessException | TransactionException exception) {
                invalidate(request);
                LOG.error("event=session_validation outcome=storage_unavailable");
                if (ReactPageResponse.problem(request, response, 503, "일시적으로 요청을 확인할 수 없습니다. 잠시 후 다시 로그인해 주세요.", true)) return;
                response.setHeader("Cache-Control", "no-store");
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("일시적으로 요청을 확인할 수 없습니다. 잠시 후 다시 로그인해 주세요.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void invalidate(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
