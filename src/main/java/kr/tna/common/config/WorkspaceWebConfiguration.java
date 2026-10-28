package kr.tna.common.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.tna.common.auth.AuthStore;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.EmployeeMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.view.RedirectView;

/** Adds display-only identity after successful customer-page reads, never to redirects or fragments. */
@Configuration(proxyBeanMethods = false)
public class WorkspaceWebConfiguration implements WebMvcConfigurer, HandlerInterceptor {
    private final TenantTransactions tenants;
    private final EmployeeMapper employees;
    private final AuthStore auth;
    private final LocalDemoLogin demo;

    public WorkspaceWebConfiguration(TenantTransactions tenants, EmployeeMapper employees,
                                     AuthStore auth, LocalDemoLogin demo) {
        this.tenants = tenants;
        this.employees = employees;
        this.auth = auth;
        this.demo = demo;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/me/**", "/admin/**", "/approvals", "/approvals/completed",
                "/requests/**", "/worktime/**", "/company/**");
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, ModelAndView view) {
        if (view == null || view.getView() instanceof RedirectView || view.getViewName() == null
                || view.getViewName().startsWith("redirect:") || view.getViewName().contains("::")) return;
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof TnaPrincipal principal)) return;
        try {
            var employee = tenants.execute(principal.tenantId(),
                    () -> employees.findSelf(principal.tenantId(), principal.employeeId()));
            if (employee == null || !auth.isCurrent(principal)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
            view.addObject("workspaceEmployee", employee);
            view.addObject("workspacePrincipal", principal);
            view.addObject("workspaceDemo", demo.enabled());
        } catch (DataAccessException | TransactionException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
