package kr.tna.console;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import kr.tna.common.auth.LoginRateLimiter;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.console.OperatorData.OperatorPrincipal;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.context.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="tna.operator.enabled",havingValue="true")
public class OperatorSecurityConfiguration {
    public static final String CONTEXT_KEY="TNA_OPERATOR_SECURITY_CONTEXT";
    private static final Logger LOG=LoggerFactory.getLogger(OperatorSecurityConfiguration.class);
    @Bean HikariDataSource operatorDataSource(Environment environment){
        var c=new HikariConfig();c.setJdbcUrl(environment.getRequiredProperty("tna.operator.url"));c.setUsername("tna_operator");
        c.setPassword(environment.getRequiredProperty("tna.operator.password"));c.setPoolName("tna-operator");c.setMaximumPoolSize(2);c.setMinimumIdle(0);c.setConnectionTimeout(5000);return new HikariDataSource(c);
    }
    @Bean OperatorMapper operatorMapper(@Qualifier("operatorDataSource") javax.sql.DataSource source){return new OperatorMapper(source);}
    @Bean @Order(1) SecurityFilterChain operatorChain(HttpSecurity http,OperatorMapper mapper,PasswordEncoder encoder,Clock clock)throws Exception {
        var repository=new HttpSessionSecurityContextRepository();repository.setSpringSecurityContextKey(CONTEXT_KEY);
        var limiter=new LoginRateLimiter(clock);String dummy=encoder.encode("Unassigned operator credential comparison only");
        AuthenticationProvider provider=new AuthenticationProvider(){
            @Override public boolean supports(Class<?> type){return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);}
            @Override public Authentication authenticate(Authentication input){
                String login=input.getName()==null?"":input.getName().strip().toLowerCase(Locale.ROOT);
                String password=input.getCredentials() instanceof String value?value:"";
                String remote=input.getDetails() instanceof WebAuthenticationDetails details?details.getRemoteAddress():"";
                if(!limiter.tryAcquire(remote,"OPERATOR",login))throw new OperatorRateLimited();
                if(login.length()>120||login.isBlank()||!PasswordPolicy.isValid(password))throw new BadCredentialsException("Invalid operator credentials");
                try {
                    var row=mapper.credential(login);boolean match=encoder.matches(password,row==null?dummy:row.passwordHash());
                    if(row==null||!row.active()||!match)throw new BadCredentialsException("Invalid operator credentials");
                    LOG.info("event=operator_login outcome=success");
                    return UsernamePasswordAuthenticationToken.authenticated(new OperatorPrincipal(row.id(),row.loginId(),row.authVersion(),clock.instant()),null,List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR")));
                }catch(DataAccessException e){throw new AuthenticationServiceException("Operator storage unavailable");}
            }
        };
        http.securityMatcher("/operator/**").authenticationManager(new ProviderManager(provider))
            .securityContext(context->context.securityContextRepository(repository))
            .authorizeHttpRequests(auth->auth.requestMatchers("/operator/login").permitAll().anyRequest().hasRole("PLATFORM_OPERATOR"))
            .formLogin(form->form.loginPage("/operator/login").loginProcessingUrl("/operator/login").usernameParameter("loginId")
                .defaultSuccessUrl("/operator/tenants",true).failureHandler((req,res,error)->{
                    LOG.warn("event=operator_login outcome=rejected");res.setHeader("Cache-Control","no-store");
                    if(error instanceof OperatorRateLimited){res.setStatus(429);res.setHeader("Retry-After","300");res.setContentType("text/plain;charset=UTF-8");res.getWriter().write("운영자 로그인 요청이 많습니다. 잠시 후 다시 시도해 주세요.");}
                    else if(error instanceof AuthenticationServiceException){res.setStatus(503);res.setContentType("text/plain;charset=UTF-8");res.getWriter().write("운영자 인증을 확인할 수 없습니다.");}
                    else res.sendRedirect("/operator/login?error");
                }))
            .logout(logout->logout.logoutUrl("/operator/logout").logoutSuccessUrl("/operator/login?logout").invalidateHttpSession(false)
                .addLogoutHandler((req,res,auth)->{var session=req.getSession(false);if(session!=null)session.removeAttribute(CONTEXT_KEY);}))
            .requestCache(cache->cache.disable()).sessionManagement(session->session.sessionFixation(fixation->fixation.changeSessionId()))
            .exceptionHandling(errors->errors.authenticationEntryPoint((req,res,error)->res.sendRedirect("/operator/login")))
            .headers(headers->headers.contentSecurityPolicy(csp->csp.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")))
            .addFilterAfter(new OncePerRequestFilter(){
                @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws IOException,ServletException {
                    var auth=SecurityContextHolder.getContext().getAuthentication();
                    if(auth!=null&&auth.getPrincipal() instanceof OperatorPrincipal actor){
                        try{var age=Duration.between(actor.authenticatedAt(),clock.instant());
                            if(age.isNegative()||age.compareTo(Duration.ofHours(12))>=0||!mapper.current(actor)){
                                var session=req.getSession(false);if(session!=null)session.removeAttribute(CONTEXT_KEY);SecurityContextHolder.clearContext();res.sendRedirect("/operator/login");return;
                            }
                        }catch(DataAccessException e){LOG.error("event=operator_session outcome=storage_unavailable");SecurityContextHolder.clearContext();res.setStatus(503);res.setHeader("Cache-Control","no-store");return;}
                    }
                    chain.doFilter(req,res);
                }
            },SecurityContextHolderFilter.class);
        return http.build();
    }
    private static final class OperatorRateLimited extends AuthenticationException {OperatorRateLimited(){super("Operator rate limit");}}
}
