package kr.tna.org;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.BulkData.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Controller
public final class OrgBulkController {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> PARAMETERS = Set.of("commandId", "reason", "rows", "q", "page", "organizationId", "status", "_csrf");
    private static final Set<String> ORGANIZATION_FIELDS = Set.of("clientRowId", "id", "expectedVersion", "operation", "code", "name", "parentId", "active");
    private static final Set<String> EMPLOYEE_FIELDS = Set.of("clientRowId", "id", "expectedVersion", "operation", "employeeNo", "displayName", "joinedOn", "leftOn", "active");
    private final OrgBulkService service;
    private final OrgAdminController pages;

    public OrgBulkController(OrgBulkService service, OrgAdminController pages) {
        this.service = service; this.pages = pages;
    }

    @PostMapping("/admin/organizations/bulk")
    ModelAndView organizations(@AuthenticationPrincipal TnaPrincipal actor, HttpServletRequest request) {
        Context context = context(request);
        var rows = new ArrayList<OrganizationRow>();
        for (JsonNode row : rows(request, ORGANIZATION_FIELDS)) {
            rows.add(new OrganizationRow(clientId(row), uuid(row, "id"), version(row), operation(row),
                    text(row, "code"), text(row, "name"), uuid(row, "parentId"), active(row)));
        }
        var form = new Form<>(context.commandId(), context.reason(), List.copyOf(rows));
        // Validate the navigation scope before committing a mutation that redirects into it.
        pages.organizationList(actor, context.q(), context.page(), null);
        try {
            service.organizations(actor, form);
            return redirect("organizations", context);
        } catch (OrgException error) {
            if (error.status() != 409 && error.status() != 422) throw error;
            var page = pages.organizationList(actor, context.q(), context.page(), null);
            var originals = service.organizationOriginals(actor, organizationRows(page), form.rows());
            return failed(page, "organizationSheet", BulkData.organizations(form.commandId(), form.reason(),
                    originals, form.rows(), errors(error)), error);
        }
    }

    @PostMapping("/admin/employees/bulk")
    ModelAndView employees(@AuthenticationPrincipal TnaPrincipal actor, HttpServletRequest request) {
        Context context = context(request);
        var rows = new ArrayList<EmployeeRow>();
        for (JsonNode row : rows(request, EMPLOYEE_FIELDS)) {
            rows.add(new EmployeeRow(clientId(row), uuid(row, "id"), version(row), operation(row),
                    text(row, "employeeNo"), text(row, "displayName"), date(row, "joinedOn"), date(row, "leftOn"), active(row)));
        }
        var form = new Form<>(context.commandId(), context.reason(), List.copyOf(rows));
        pages.employeeList(actor, context.q(), context.page(), null, context.organizationId(), context.status(), null);
        try {
            service.employees(actor, form);
            return redirect("employees", context);
        } catch (OrgException error) {
            if (error.status() != 409 && error.status() != 422) throw error;
            var page = pages.employeeList(actor, context.q(), context.page(), null, context.organizationId(), context.status(), null);
            var originals = service.employeeOriginals(actor, employeeRows(page), form.rows());
            pages.employeeContext(page, actor, originals);
            return failed(page, "employeeSheet", BulkData.employees(form.commandId(), form.reason(),
                    originals, form.rows(), errors(error)), error);
        }
    }

    private static Context context(HttpServletRequest request) {
        request.getParameterMap().forEach((key, values) -> {
            if (!PARAMETERS.contains(key) || values.length != 1) throw malformed();
        });
        try {
            String q = parameter(request, "q", "");
            if (q.codePointCount(0, q.length()) > 120) throw malformed();
            int page = Integer.parseInt(parameter(request, "page", "0"));
            if (page < 0 || page > 1_000_000) throw malformed();
            String status = parameter(request, "status", "all");
            if (!Set.of("all", "active", "inactive").contains(status)) throw malformed();
            return new Context(UUID.fromString(parameter(request, "commandId", "")),
                    parameter(request, "reason", ""), q, page,
                    optionalUuid(parameter(request, "organizationId", "")), status);
        } catch (IllegalArgumentException invalid) { throw malformed(); }
    }

    private static JsonNode rows(HttpServletRequest request, Set<String> allowed) {
        String raw = request.getParameter("rows");
        if (raw == null || raw.length() > 200_000) throw malformed();
        try {
            JsonNode rows = JSON.readTree(raw);
            if (rows == null || !rows.isArray()) throw malformed();
            for (JsonNode row : rows) {
                if (!row.isObject()) throw malformed();
                for (String field : row.propertyNames()) if (!allowed.contains(field)) throw malformed();
            }
            return rows;
        } catch (JacksonException invalid) { throw malformed(); }
    }

    private static String clientId(JsonNode row) {
        String value = text(row, "clientRowId");
        try {
            if (!UUID.fromString(value).toString().equals(value)) throw malformed();
            return value;
        } catch (IllegalArgumentException invalid) { throw malformed(); }
    }
    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isString()) throw malformed();
        return value.asString();
    }
    private static UUID uuid(JsonNode row, String field) {
        return optionalUuid(text(row, field));
    }
    private static UUID optionalUuid(String value) {
        try { return value.isEmpty() ? null : UUID.fromString(value); }
        catch (IllegalArgumentException invalid) { throw malformed(); }
    }
    private static Long version(JsonNode row) {
        JsonNode value = row.get("expectedVersion");
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw malformed();
        return value.asLong();
    }
    private static Operation operation(JsonNode row) {
        try { return Operation.valueOf(text(row, "operation")); }
        catch (IllegalArgumentException invalid) { throw malformed(); }
    }
    private static boolean active(JsonNode row) {
        JsonNode value = row.get("active");
        if (value == null || !value.isBoolean()) throw malformed();
        return value.asBoolean();
    }
    private static LocalDate date(JsonNode row, String field) {
        String value = text(row, field);
        try { return value.isEmpty() ? null : LocalDate.parse(value); }
        catch (java.time.DateTimeException invalid) { throw malformed(); }
    }
    private static String parameter(HttpServletRequest request, String field, String fallback) {
        String value = request.getParameter(field);
        return value == null ? fallback : value;
    }
    private static OrgException malformed() { return new OrgException(400, "행의 필드·식별자·날짜·버전 입력 형식을 확인해 주세요."); }
    private static List<FieldError> errors(OrgException error) {
        return error instanceof BulkException bulk ? bulk.errors() : List.of(new FieldError("", "rows", error.getMessage()));
    }
    private static ModelAndView failed(ModelAndView page, String name, Sheet sheet, OrgException error) {
        page.addObject(name, sheet).addObject("error", error.getMessage());
        page.setStatus(HttpStatusCode.valueOf(error.status()));
        return page;
    }
    private static ModelAndView redirect(String kind, Context context) {
        var uri = UriComponentsBuilder.fromPath("/admin/" + kind).queryParam("q", context.q())
                .queryParam("page", context.page()).queryParam("saved", "");
        if (kind.equals("employees")) {
            uri.queryParam("status", context.status());
            if (context.organizationId() != null) uri.queryParam("organizationId", context.organizationId());
        }
        var redirect = new RedirectView(uri.build().encode().toUriString(), true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return new ModelAndView(redirect);
    }
    @SuppressWarnings("unchecked")
    private static List<OrgData.Organization> organizationRows(ModelAndView page) {
        return (List<OrgData.Organization>) page.getModel().get("organizations");
    }
    @SuppressWarnings("unchecked")
    private static List<OrgData.Employee> employeeRows(ModelAndView page) {
        return (List<OrgData.Employee>) page.getModel().get("employees");
    }
    private record Context(UUID commandId, String reason, String q, int page, UUID organizationId, String status) { }
}
