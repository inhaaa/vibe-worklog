package kr.tna.common.config;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import kr.tna.common.auth.AuthStore;
import kr.tna.common.auth.CompanyAuthenticationDetails;
import kr.tna.common.auth.CompanyAuthenticationProvider;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.common.auth.SessionValidationFilter;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.web.ReactPageResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() { return PasswordPolicy.passwordEncoder(); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CompanyAuthenticationProvider provider,
                                          AuthStore authStore, Clock clock, LocalDemoLogin localDemo) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, exception) -> {
            response.setHeader("Cache-Control", "no-store");
            if (ReactPageResponse.problem(request, response, 401, "로그인이 필요합니다. 다시 로그인해 주세요.")) return;
            if ("true".equalsIgnoreCase(request.getHeader("HX-Request"))) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("HX-Redirect", request.getContextPath() + "/login");
            } else {
                response.sendRedirect(request.getContextPath() + "/login");
            }
        };
        // A parent-less manager prevents a rejected attempt invoking the same provider twice.
        http.authenticationManager(new ProviderManager(provider))
                .authorizeHttpRequests(authorize -> {
                    if (localDemo.publicDemo()) {
                        for (var employeeId : LocalDemoLogin.SHARED_EMPLOYEE_IDS) {
                            String identity = "/admin/employees/" + employeeId;
                            authorize.requestMatchers(new RegexRequestMatcher(identity
                                    + "/(changes|roles|invitations|personnel|personal-details|personal-entries)(?:\\?.*)?", "POST", true)).denyAll();
                        }
                    }
                    if (localDemo.enabled()) {
                        authorize.requestMatchers(HttpMethod.GET, "/", "/demo").permitAll();
                    }
                    authorize.requestMatchers(HttpMethod.GET, "/login", "/app.css", "/react/**",
                                "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/invite/*", "/password-reset").permitAll()
                        .requestMatchers(HttpMethod.POST, "/invite/*/accept", "/password-reset/request", "/password-reset/complete").permitAll()
                        .requestMatchers("/operator/**", "/actuator/**").denyAll()
                        .requestMatchers("/admin/organizations", "/admin/organizations/**",
                                "/admin/employees", "/admin/employees/**", "/admin/access",
                                "/admin/organization-heads", "/admin/organization-heads/**",
                                "/admin/approval-routes", "/admin/approval-routes/**",
                                "/admin/approval-route-assignments", "/admin/approval-route-assignments/**",
                                "/admin/common-code-groups/**", "/admin/common-codes", "/admin/common-codes/**",
                                "/admin/attendance-types", "/admin/attendance-types/**",
                                "/admin/approver-assignments", "/admin/schedules", "/admin/schedules/**", "/admin/networks", "/admin/networks/**", "/admin/calendar", "/admin/calendar/**", "/admin/requests", "/admin/requests/**", "/admin/leave-balances", "/admin/leave-balances/**", "/admin/attendance", "/admin/calculations", "/admin/work-policies", "/admin/work-assessments", "/admin/reports/**", "/admin/audit", "/admin/audit/**").hasRole("ATTENDANCE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/company/attendance", "/company/requests", "/company/organizations")
                            .hasAnyRole("ATTENDANCE_ADMIN", "COMPANY_REPRESENTATIVE")
                        .requestMatchers("/approvals", "/approvals/completed").hasRole("APPROVER")
                        .requestMatchers("/requests/**", "/me/requests", "/me/requests/**", "/worktime/results/*").authenticated()
                        .requestMatchers(HttpMethod.GET, "/me/schedule", "/me/profile", "/me/profile/photo").authenticated()
                        .requestMatchers("/", "/me/today", "/me/today/status", "/me/attendance", "/me/attendance/events", "/me/work-confirmations", "/me/worktime").authenticated()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll();
                })
                .formLogin(form -> form.loginPage("/login").usernameParameter("email")
                        .authenticationDetailsSource(CompanyAuthenticationDetails::new)
                        .successHandler((request, response, authentication) -> {
                            boolean approverPersona = localDemo.enabled()
                                    && authentication.getPrincipal() instanceof TnaPrincipal principal
                                    && LocalDemoLogin.TENANT_ID.equals(principal.tenantId())
                                    && LocalDemoLogin.APPROVER_ACCOUNT_ID.equals(principal.accountId());
                            response.sendRedirect(request.getContextPath() + (approverPersona ? "/approvals" : "/me/profile"));
                        })
                        .failureHandler((request, response, exception) -> {
                            response.setHeader("Cache-Control", "no-store");
                            if (exception instanceof CompanyAuthenticationProvider.LoginRateLimitedException) {
                                if (ReactPageResponse.problem(request, response, 429, "로그인 요청이 많습니다. 잠시 후 다시 시도해 주세요.")) {
                                    response.setHeader("Retry-After", "300"); return;
                                }
                                response.setStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS.value());
                                response.setHeader("Retry-After", "300");
                                response.setContentType("text/plain;charset=UTF-8");
                                response.getWriter().write("로그인 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
                            } else if (exception instanceof AuthenticationServiceException) {
                                if (ReactPageResponse.problem(request, response, 503, "일시적으로 로그인할 수 없습니다. 잠시 후 다시 시도해 주세요.")) return;
                                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                                response.setContentType("text/plain;charset=UTF-8");
                                response.getWriter().write("일시적으로 로그인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
                            } else {
                                if (ReactPageResponse.problem(request, response, 401, "로그인 정보를 확인해 주세요.")) return;
                                response.sendRedirect(request.getContextPath() + "/login?error");
                            }
                        }).permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout").deleteCookies("JSESSIONID"))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler((request, response, exception) -> {
                            if (ReactPageResponse.problem(request, response, 403, "요청을 처리할 권한이 없습니다. 화면을 새로 고침한 뒤 다시 시도해 주세요.")) return;
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setHeader("Cache-Control", "no-store");
                            response.setContentType("text/plain;charset=UTF-8");
                            response.getWriter().write("요청을 처리할 권한이 없습니다. 화면을 새로 고침한 뒤 다시 시도해 주세요.");
                        }))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
                                + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")))
                .addFilterAfter(new SessionValidationFilter(authStore, clock, entryPoint),
                        SecurityContextHolderFilter.class);
        // CSRF remains enabled, including login and logout. No remember-me or HTTP Basic.
        return http.build();
    }
}
