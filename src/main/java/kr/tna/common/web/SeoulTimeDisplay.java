package kr.tna.common.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component("seoulTime")
public final class SeoulTimeDisplay {
    private static final String UNKNOWN = "미확인";
    private static final DateTimeFormatter INSTANT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneId.of("Asia/Seoul"));
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);
    private final JsonMapper json = JsonMapper.builder().build();

    public String instant(Instant value) { return value == null ? UNKNOWN : INSTANT.format(value); }
    public String date(LocalDate value) { return value == null ? UNKNOWN : DateTimeFormatter.ISO_LOCAL_DATE.format(value); }
    public String time(LocalTime value) { return value == null ? UNKNOWN : TIME.format(value); }

    public String intervals(String value) {
        if (value == null) return UNKNOWN;
        try {
            var root = json.readTree(value);
            if (root == null || !root.isArray()) return "확인 필요";
            var intervals = new ArrayList<String>();
            for (var interval : root) {
                if (!interval.isObject()) return "확인 필요";
                Instant start = Instant.parse(interval.get("startAt").asString());
                Instant end = Instant.parse(interval.get("endAt").asString());
                if (!start.isBefore(end)) return "확인 필요";
                intervals.add(instant(start) + " ~ " + instant(end));
            }
            return intervals.isEmpty() ? "입력된 휴게 없음" : String.join("; ", intervals);
        } catch (RuntimeException error) {
            // Invalid stored evidence is a review state; never reflect its raw JSON or values.
            return "확인 필요";
        }
    }
}
