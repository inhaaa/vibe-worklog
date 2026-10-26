package kr.tna.common.auth;

import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class CompanyAuthenticationProvider implements AuthenticationProvider {
    private static final Logger LOG = LoggerFactory.getLogger(CompanyAuthenticationProvider.class);
    private final AuthStore store;
    private final PasswordEncoder encoder;
    private final LoginRateLimiter limiter;
    private final String dummyHash;
    private final LocalDemoLogin localDemo;

    public CompanyAuthenticationProvider(AuthStore store, PasswordEncoder encoder, Clock clock, LocalDemoLogin localDemo) {
        this.store = store;
        this.encoder = encoder;
        this.localDemo = localDemo;
        limiter = new LoginRateLimiter(clock);
        dummyHash = encoder.encode("Synthetic unmatched credential only");
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        if (!(authentication.getDetails() instanceof CompanyAuthenticationDetails details)) {
            throw rejected();
        }
        boolean demo = localDemo.enabled();
        String company = demo ? LocalDemoLogin.COMPANY_CODE : details.companyCode().toUpperCase(Locale.ROOT);
        String email = demo ? LocalDemoLogin.EMAIL : authentication.getName().length() <= 254
                ? authentication.getName().trim().toLowerCase(Locale.ROOT) : "";
        if (!limiter.tryAcquire(details.getRemoteAddress(), company, email)) {
            LOG.warn("event=login outcome=rate_limited");
            throw new LoginRateLimitedException();
        }
        String password = authentication.getCredentials() instanceof String value ? value : null;
        boolean validCredentials = demo
                ? LocalDemoLogin.LOGIN.equals(authentication.getName()) && LocalDemoLogin.PASSWORD.equals(password)
                : !company.isBlank() && !email.isBlank() && PasswordPolicy.isValid(password);
        if (!validCredentials) {
            throw rejected();
        }
        try {
            var identity = store.forLogin(company, email);
            String hash = identity.map(AuthStore.LoginIdentity::passwordHash).orElse(dummyHash);
            if (!encoder.matches(password, hash) || identity.isEmpty()) {
                throw rejected();
            }
            TnaPrincipal principal = identity.orElseThrow().principal();
            if (demo && (!LocalDemoLogin.TENANT_ID.equals(principal.tenantId())
                    || !LocalDemoLogin.ACCOUNT_ID.equals(principal.accountId())
                    || !LocalDemoLogin.EMPLOYEE_ID.equals(principal.employeeId())
                    || !principal.roles().equals(Set.of("EMPLOYEE", "ATTENDANCE_ADMIN")))) {
                throw rejected();
            }
            var authorities = principal.roles().stream()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role)).toList();
            LOG.info("event=login outcome=success");
            return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        } catch (DataAccessException | org.springframework.transaction.TransactionException exception) {
            LOG.error("event=login outcome=storage_unavailable");
            throw new AuthenticationServiceException("Authentication is temporarily unavailable");
        } catch (IllegalArgumentException exception) {
            // Malformed/unsupported stored hashes fail closed without reflecting their content.
            throw rejected();
        }
    }

    private BadCredentialsException rejected() {
        LOG.warn("event=login outcome=rejected");
        return new BadCredentialsException("Invalid credentials");
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    public static final class LoginRateLimitedException extends AuthenticationServiceException {
        public LoginRateLimitedException() { super("Login temporarily limited"); }
    }
}
