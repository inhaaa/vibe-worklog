package kr.tna.request;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import kr.tna.request.LeaveOperationsData.Counts;
import kr.tna.request.LeaveOperationsData.Page;
import kr.tna.request.LeaveOperationsData.Search;
import kr.tna.workspace.ProfileCompanyMapper;
import org.springframework.stereotype.Service;

@Service
final class LeaveOperationsQueries {
    static final String PATH = "/admin/requests";
    static final String DEFAULT_URL = PATH + "?scope=LEAVE";
    private static final Set<String> STATUS = Set.of("PENDING", "APPROVED", "REJECTED", "ALL");
    private static final Set<String> KINDS = Set.of("", "LEAVE_DAY", "CANCEL_LEAVE");
    private static final Set<String> PARAMETERS = Set.of("scope", "status", "leaveTypeCode", "requestKind",
            "from", "to", "organizationId", "q", "page");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int PAGE_SIZE = 50;

    private final OrgCommands administration;
    private final LeaveWorkflowMapper workflow;
    private final ProfileCompanyMapper company;
    private final LeaveOperationsMapper mapper;
    private final Clock clock;

    LeaveOperationsQueries(OrgCommands administration, LeaveWorkflowMapper workflow, ProfileCompanyMapper company,
            LeaveOperationsMapper mapper, Clock clock) {
        this.administration = administration;
        this.workflow = workflow;
        this.company = company;
        this.mapper = mapper;
        this.clock = clock;
    }

    Page read(TnaPrincipal actor, String status, String code, String kind, String from, String to,
            UUID organizationId, String q, int page) {
        return administration.read(actor, () -> {
            workflow.readLock(actor.tenantId());
            return administration.read(actor,
                    () -> load(actor.tenantId(), status, code, kind, from, to, organizationId, q.strip(), page));
        });
    }

    String decidedByName(TnaPrincipal actor, UUID requestId) {
        return administration.read(actor, () -> mapper.decidedByName(actor.tenantId(), requestId));
    }

    private Page load(UUID tenantId, String status, String code, String kind, String from, String to,
            UUID organizationId, String q, int page) {
        if (organizationId != null && !company.organizationExists(tenantId, organizationId)) throw OrgException.notFound();
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        var leaveTypes = List.copyOf(mapper.leaveTypes(tenantId, today));
        var organizations = List.copyOf(company.organizations(tenantId));
        Search search;
        try {
            String selected = code.isEmpty() ? "" : leaveTypes.stream()
                    .filter(type -> code.equalsIgnoreCase(type.code()) || code.equalsIgnoreCase(type.systemKind()))
                    .map(LeaveOperationsData.LeaveType::code).findFirst()
                    .orElseThrow(() -> OrgException.invalid("휴가 종류를 확인해 주세요."));
            search = search(status, selected, kind, from, to, organizationId, q, page);
        } catch (OrgException error) {
            return new Page(status, code, kind, from, to, organizationId, q, page, false, today, leaveTypes, organizations,
                    new Counts(0, 0, 0, 0).byStatus(), List.of(), error.getMessage());
        }
        var counts = mapper.counts(tenantId, today, search).byStatus();
        var found = mapper.rows(tenantId, today, search);
        return new Page(status, search.leaveTypeCode(), kind, from, to, organizationId, q, page, found.size() > PAGE_SIZE,
                today, leaveTypes, organizations, counts, found.stream().limit(PAGE_SIZE).toList(), null);
    }

    private static Search search(String status, String code, String kind, String from, String to,
            UUID organizationId, String q, int page) {
        if (!STATUS.contains(status)) throw OrgException.invalid("신청 상태를 확인해 주세요.");
        if (!KINDS.contains(kind)) throw OrgException.invalid("휴가 신청 구분을 확인해 주세요.");
        if (q.length() > 100 || q.codePoints().anyMatch(Character::isISOControl)) {
            throw OrgException.invalid("사번 또는 이름을 100자 이내로 입력해 주세요.");
        }
        if (page < 0 || page > 100_000) throw OrgException.invalid("올바른 조회 페이지를 선택해 주세요.");
        if (from.isEmpty() != to.isEmpty()) throw OrgException.invalid("휴가 대상 기간의 시작일과 종료일을 모두 입력해 주세요.");
        LocalDate start = from.isEmpty() ? null : date(from);
        LocalDate end = to.isEmpty() ? null : date(to);
        if (start != null && end.isBefore(start)) throw OrgException.invalid("종료일은 시작일 이후로 입력해 주세요.");
        return new Search(status, code, kind, start, end, organizationId, q, page * PAGE_SIZE);
    }

    private static LocalDate date(String text) {
        try {
            if (DATE.matcher(text).matches()) {
                var date = LocalDate.parse(text);
                if (date.getYear() >= 1) return date;
            }
        } catch (DateTimeParseException invalid) {
            throw OrgException.invalid("휴가 대상 기간의 날짜를 확인해 주세요.");
        }
        throw OrgException.invalid("휴가 대상 기간의 날짜를 확인해 주세요.");
    }

    static String validReturnTo(String value, RequestData.AttendancePolicy policy) {
        if (value == null || value.length() > 2048 || !value.startsWith(PATH + "?")) return null;
        try {
            URI uri = URI.create(value);
            if (!PATH.equals(uri.getRawPath()) || uri.getFragment() != null || uri.getRawAuthority() != null) return null;
            var query = new HashMap<String, String>();
            for (String pair : uri.getRawQuery().split("&", -1)) {
                int separator = pair.indexOf('=');
                if (separator < 1) return null;
                String key = pair.substring(0, separator);
                if (!PARAMETERS.contains(key) || query.putIfAbsent(key,
                        URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8)) != null) return null;
            }
            if (!"LEAVE".equals(query.get("scope"))) return null;
            String code = query.getOrDefault("leaveTypeCode", "");
            if (!code.isEmpty() && (policy == null ||
                    !(code.equalsIgnoreCase(policy.code()) || code.equalsIgnoreCase(policy.systemKind())))) return null;
            String org = query.getOrDefault("organizationId", "");
            UUID organizationId = org.isEmpty() ? null : UUID.fromString(org);
            if (organizationId != null && !org.equalsIgnoreCase(organizationId.toString())) return null;
            search(query.getOrDefault("status", "PENDING"), code, query.getOrDefault("requestKind", ""),
                    query.getOrDefault("from", ""), query.getOrDefault("to", ""), organizationId,
                    query.getOrDefault("q", ""), Integer.parseInt(query.getOrDefault("page", "0")));
            return value;
        } catch (IllegalArgumentException | OrgException invalid) {
            return null;
        }
    }
}
