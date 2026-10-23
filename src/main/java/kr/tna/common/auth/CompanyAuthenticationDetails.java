package kr.tna.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.io.Serial;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

public final class CompanyAuthenticationDetails extends WebAuthenticationDetails {
    @Serial private static final long serialVersionUID = 1L;
    private final String companyCode;

    public CompanyAuthenticationDetails(HttpServletRequest request) {
        super(request);
        String supplied = request.getParameter("companyCode");
        companyCode = supplied != null && supplied.length() <= 80 ? supplied.trim() : "";
    }

    public String companyCode() { return companyCode; }

    @Override public String toString() { return "CompanyAuthenticationDetails[redacted]"; }
}
