package kr.tna.common.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.util.ArrayList;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData;
import kr.tna.org.EmployeeOverview;
import kr.tna.request.RequestData;
import kr.tna.workspace.WorkspaceData;
import org.springframework.core.Ordered;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;
import tools.jackson.databind.json.JsonMapper;

/** Existing controllers keep their view/form contracts; only explicit page data reaches React. */
@Component
public final class ReactPageViewResolver implements ViewResolver, Ordered {
    public static final String MEDIA_TYPE = "application/vnd.worklog.page+json";
    private static final Set<String> COMMON = Set.of("error", "workspaceEmployee", "workspacePrincipal", "workspaceDemo", "workspaceNotifications", "employeeHeader");
    private static final Map<String, Set<String>> PAGES = Map.ofEntries(
            page("notification/list", "notifications"),
            page("login", "localDemo"), page("demo", "demoLogin", "demoPassword", "demoApproverLogin", "demoApproverPassword", "demoAccounts", "publicDemo"),
            page("invite", "companyCode", "inviteByCompany"), page("password-reset"), page("first-login", "companyCode", "employeeNo"),
            page("operator/login"),
            page("operator/tenants", "tenants", "audit", "form"),
            page("today", "employee", "workspaceHome", "attendanceAdmin", "approver", "today", "day",
                    "eventTimes", "confirmationForm", "inCommand", "outCommand"),
            page("overtime/employee", "overtime"), page("overtime/admin", "overtime"),
            page("fragments/status"), page("workspace/schedule", "workspaceCalendar", "selectedDay"),
            page("workspace/profile", "profile", "personalDetails"), page("workspace/home", "workspaceHome", "homeWidgetScope",
                    "clockInCommand", "clockOutCommand", "clockError", "checkoutFollowUps"),
            page("attendance/follow-ups", "checkoutFollowUps"),
            page("company/attendance", "companyAttendance", "checkoutOperations", "monthEndReview", "monthCloseBlockers", "attendanceExceptions", "weeklyHours", "canViewCheckout"), page("company/requests", "companyRequests"),
            page("org/access-list", "roleDirectory"), page("org/accounts", "accountDirectory"),
            page("org/problem", "status", "message", "traceId"),
            page("org/organizations", "organizations", "hasNext", "q", "page", "form", "options", "orgNames",
                    "organizationSheet", "organizationHeadsById", "organizationCreateFailed", "createdOrganizations"),
            page("org/organization", "organization", "form", "options", "orgNames"),
            page("org/employees", "employees", "employeeOrganizations", "selectedEmployee", "pageEmployeeCount",
                    "pageActiveCount", "pageInactiveCount", "organizationId", "status", "hasNext", "q", "page", "form", "options", "orgNames", "employeeSheet", "employeeContext", "personnelBasics", "personnelAsOf", "publicDemo"),
            page("org/onboarding", "onboarding"),
            page("org/employee-import", "employeeImport"),
            page("org/organization-heads", "organizationHeads"),
            page("org/approval-routes", "approvalRoutes"),
            page("org/request-forms", "requestForms", "draft", "routePreview"),
            page("org/approval-route-assignments", "approvalRouteAssignments"),
            page("org/common-codes", "commonCodes"),
            page("org/attendance-types", "attendanceTypes"),
            page("company/organizations", "companyOrganizations", "headManagementAllowed", "headOverview", "organizationHeads",
                    "headChartUrl", "headCommand", "headPreview", "headError", "headConflict", "headSaved"),
            page("org/employee", "employee", "assignments", "form", "assignmentForm", "options", "orgNames"),
            page("org/personnel-home"),
            page("org/personnel", "personnel", "today", "personalDetails", "adminPersonnel", "tabDraft", "tabConflict"),
            page("org/access", "employee", "account", "accountStatus", "accountState", "ownAccount", "eligibleEmployee", "resetForm", "rolesForm",
                    "approverForm", "assignments", "currentAssignment", "candidates", "selectedApproverMissing"),
            page("schedule/list", "templates", "form", "today"),
            page("schedule/detail", "template", "versions", "versionDays", "today", "form"),
            page("schedule/assignment", "employee", "assignments", "versions", "templateNames", "today", "form"),
            page("clock/networks", "pageCommand", "networks", "form"),
            page("clock/holidays", "holidays"),
            page("clock/period", "attendancePeriod"),
            page("clock/history", "day", "eventTimes", "today", "isToday", "inCommand", "outCommand", "actualWorkMillis", "correctionSuggested", "settlementReturn"),
            page("request/mine", "requests", "buckets", "form", "annual", "compose"),
            page("request/attributions", "leaveAttributions"), page("request/list", "title", "requests", "page", "hasNext", "completed", "requestScope", "leaveOperations"),
            page("request/detail", "pageCommand", "detail", "request", "admin", "ownRequest", "canDecide", "canWithdraw", "canCancel", "canCancelException", "candidates",
                    "leaveReturnTo", "monthCloseReturnTo", "settlementReturn", "leaveDecidedByName", "cancelApproval", "cancelReason", "exceptionCancelReason"),
            page("request/balances", "employee", "employees", "buckets", "form"),
            page("request/annual-cycle", "policy", "year", "yearSelected", "today", "publicDemo"),
            page("request/employment-leave", "q", "page", "employees", "hasNext", "today", "publicDemo", "monthly", "exit"),
            page("request/personal-ledger", "ledger"),
            page("request/ledger", "ledger", "bucketId", "adjustment"),
            page("request/correction", "actions", "day", "exceptional", "eventTimes", "form", "checkoutOnly", "pendingCorrectionId", "approval", "settlementReturn"),
            page("worktime/month", "overview", "employeeId", "admin", "employees", "from", "to", "calculateCommand", "exportCommand", "checkoutWorkDate", "checkoutReturnTo", "settlementWorkDate", "settlementReturn"),
            page("worktime/result", "detail", "times", "month", "admin", "settlementReturn"),
            page("worktime/audit", "page", "audits"), page("worktime/audit-detail", "audit"),
            page("worktime/policies", "employees", "policies", "assessments", "employeeId", "policyForm", "assessmentForm"));
    private final JsonMapper json;

    public ReactPageViewResolver(JsonMapper json) {
        this.json = json.rebuild().addMixIn(WorkspaceData.DayView.class, DayViewProperties.class).build();
    }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public View resolveViewName(String viewName, Locale locale) {
        String page = viewName.split("\\s*::", 2)[0].strip();
        if (!PAGES.containsKey(page)) return null;
        return (model, request, response) -> render(page, model, request, response);
    }

    private void render(String page, Map<String, ?> model, HttpServletRequest request,
                        HttpServletResponse response) throws Exception {
        var data = new LinkedHashMap<String, Object>();
        if (model != null) model.forEach((key, value) -> {
            if (COMMON.contains(key) || PAGES.get(page).contains(key)) data.put(key, safeIdentity(value));
        });
        var flags = new LinkedHashMap<String, Boolean>();
        for (String flag : List.of("error", "logout", "invited", "reset", "requested", "saved", "activated")) {
            if (request.getParameterMap().containsKey(flag)) flags.put(flag, true);
        }
        data.put("flags", flags);
        var token = request.getAttribute(CsrfToken.class.getName());
        if (!(token instanceof CsrfToken)) token = request.getAttribute("_csrf");
        Map<String, String> csrf = token instanceof CsrfToken value
                ? Map.of("token", value.getToken(), "parameterName", value.getParameterName(), "headerName", value.getHeaderName())
                : Map.of();
        String payload = json.writeValueAsString(Map.of("page", page, "data", data, "csrf", csrf));
        response.setHeader("Cache-Control", "no-store");
        response.addHeader("Vary", "Accept");
        response.setCharacterEncoding("UTF-8");
        if (wantsJson(request)) {
            response.setContentType(MEDIA_TYPE + ";charset=UTF-8");
            response.getWriter().write(payload);
        } else {
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<!DOCTYPE html><html lang=\"ko\"><head><meta charset=\"UTF-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><meta name=\"referrer\" content=\"no-referrer\"><title>WorkLog</title>"
                    + "<link rel=\"stylesheet\" href=\"/app.css\"><link rel=\"stylesheet\" href=\"/react/app.css\"><link rel=\"stylesheet\" href=\"/react/sheet.css\"></head><body><div id=\"root\"></div>"
                    + "<noscript>WorkLog 화면을 사용하려면 JavaScript를 켜 주세요.</noscript>"
                    + "<script id=\"worklog-page\" type=\"application/json\">" + escapeBoot(payload) + "</script>"
                    + "<script type=\"module\" src=\"/react/app.js\"></script></body></html>");
        }
    }

    private Object safeIdentity(Object value) {
        if (value instanceof TnaPrincipal principal) return Map.of("roles", principal.roles());
        if (value instanceof EmployeeOverview employee) return Map.of("employeeName", employee.employeeName(), "companyName", employee.companyName());
        if (value instanceof AccountData.Account account) {
            // Pending accounts have no email yet, so the map must accept null.
            var safe = new java.util.LinkedHashMap<String, Object>();
            safe.put("id", account.id());
            safe.put("employeeId", account.employeeId());
            safe.put("email", account.email());
            safe.put("active", account.active());
            safe.put("credentialSet", account.credentialSet());
            safe.put("roles", account.roles());
            return safe;
        }
        if (value instanceof RequestData.RequestDetail detail) {
            var safe = (tools.jackson.databind.node.ObjectNode) json.valueToTree(detail);
            if (detail.correction() != null) {
                var correction = (tools.jackson.databind.node.ObjectNode) safe.get("correction");
                correction.remove("beforeSnapshot");
                correction.put("actualBreaks", safeIntervals(detail.correction().actualBreaks()));
            }
            return safe;
        }
        return value;
    }

    private String safeIntervals(String stored) {
        if (stored == null) return null;
        try {
            var root = json.readTree(stored);
            if (!root.isArray()) return null;
            var intervals = new ArrayList<Map<String, String>>();
            for (var item : root) {
                Instant start = Instant.parse(item.get("startAt").asString());
                Instant end = Instant.parse(item.get("endAt").asString());
                if (!start.isBefore(end)) return null;
                intervals.add(Map.of("startAt", start.toString(), "endAt", end.toString()));
            }
            return json.writeValueAsString(intervals);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    public static boolean wantsJson(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept == null) return false;
        for (String type : accept.split(",")) {
            if (MEDIA_TYPE.equalsIgnoreCase(type.split(";", 2)[0].strip())) return true;
        }
        return false;
    }

    private static String escapeBoot(String json) {
        return json.replace("&", "\\u0026").replace("<", "\\u003c").replace(">", "\\u003e")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    private static Map.Entry<String, Set<String>> page(String name, String... keys) {
        return Map.entry(name, Set.of(keys));
    }

    private abstract static class DayViewProperties {
        @JsonProperty abstract List<WorkspaceData.TimelineEntry> timeline();
        @JsonProperty abstract String scheduleText();
        @JsonProperty abstract String calendarText();
        @JsonProperty abstract String confirmationText();
        @JsonProperty abstract String inTime();
        @JsonProperty abstract String outTime();
    }
}
