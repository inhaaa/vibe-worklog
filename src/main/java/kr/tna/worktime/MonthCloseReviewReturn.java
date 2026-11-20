package kr.tna.worktime;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Limits detail-page return links to the company list and month that opened the detail. */
public final class MonthCloseReviewReturn {
    private static final String PATH = "/company/attendance";
    private static final Set<String> REVIEW = Set.of("view", "month", "organizationId", "q", "reason", "page");
    private static final Set<String> BLOCKERS = Set.of("view", "month", "kind", "page", "source");
    private static final Set<String> CHECKOUT = Set.of("view", "date", "organizationId", "q", "status", "page");

    private MonthCloseReviewReturn() { }

    public static String request(String value, LocalDate from, LocalDate through) {
        return validate(value, from, through, true);
    }

    static String day(String value, LocalDate date) {
        return validate(value, date, date, false);
    }

    private static String validate(String value, LocalDate from, LocalDate through, boolean request) {
        if (from == null || through == null || value == null || value.length() > 4096) return null;
        try {
            var query = parameters(value);
            String view = query.get("view");
            if (!request && "checkout".equals(view)) {
                return CHECKOUT.containsAll(query.keySet()) && from.toString().equals(query.get("date")) ? value : null;
            }
            String month = query.get("month");
            if (month == null || !month.matches("[0-9]{4}-(0[1-9]|1[0-2])") || month.startsWith("0000")) return null;
            YearMonth selected = YearMonth.parse(month);
            if (selected.atEndOfMonth().isBefore(from) || selected.atDay(1).isAfter(through)) return null;
            String source = query.remove("source");
            if (source != null && !review(parameters(source), month)) return null;
            if (!request && "month-end".equals(view)) return review(query, month) ? value : null;
            String kind = request ? "PENDING_REQUEST" : "OPEN_FOLLOW_UP";
            return "close-blockers".equals(view) && kind.equals(query.get("kind"))
                    && BLOCKERS.containsAll(query.keySet()) && page(query) ? value : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static boolean review(Map<String, String> query, String month) {
        if (!"month-end".equals(query.get("view")) || !month.equals(query.get("month"))
                || !REVIEW.containsAll(query.keySet()) || !page(query)) return false;
        String organization = query.getOrDefault("organizationId", "");
        return (organization.isEmpty() || UUID.fromString(organization).toString().equalsIgnoreCase(organization))
                && query.getOrDefault("q", "").length() <= 100
                && MonthEndReviewQueries.REASONS.contains(query.getOrDefault("reason", "ALL"));
    }

    private static boolean page(Map<String, String> query) {
        String page = query.getOrDefault("page", "0");
        return page.matches("[0-9]{1,6}") && Integer.parseInt(page) <= 100_000;
    }

    private static Map<String, String> parameters(String value) {
        URI uri = URI.create(value);
        if (!value.startsWith(PATH + "?") || !PATH.equals(uri.getRawPath()) || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("잘못된 목록 경로");
        }
        var query = new HashMap<String, String>();
        for (String pair : uri.getRawQuery().split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator < 1 || query.putIfAbsent(pair.substring(0, separator),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8)) != null) {
                throw new IllegalArgumentException("잘못된 목록 조건");
            }
        }
        return query;
    }
}
