package kr.tna.clock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** 연도별 휴일 표와 근태 달력 반영. 저장은 명령 한 번(감사 1건, HOLIDAY_SAVE)이다. */
@Service
public class HolidayService {
    static final String ACTION = "HOLIDAY_SAVE";
    static final int MAX_ROWS = 366;
    private static final Set<String> CATEGORIES = Set.of("PUBLIC", "COMPANY", "NON_WORKDAY");
    private static final String CLOSED = " 근태가 마감돼 바꿀 수 없습니다.";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Row(UUID id, LocalDate date, String name, String category, String basis) { }
    public record Applied(Instant at, String by) { }
    public record PageModel(int year, long version, Applied applied, List<Row> rows, List<String> closedMonths, boolean builtIn) { }
    public record Preview(int year, List<KoreanPublicHolidays.Holiday> holidays, String notice) { }
    public record RowInput(String clientRowId, String date, String name, String category, String basis) { }
    public record Form(UUID commandId, String reason, Integer year, Long expectedVersion, List<RowInput> rows) { }
    /** changed: 휴일 행이 바뀐 날짜, applied: 근태 달력에 새 반영분이 생긴 날짜. 감사 after_state에도 이대로 남는다. */
    public record Result(int year, long version, int added, int updated, int deleted, List<LocalDate> changed, List<LocalDate> applied) { }
    public record RowError(String clientRowId, String field, String message) { }
    record Parsed(String clientRowId, LocalDate date, String name, String category, String basis) { }

    public static final class HolidayProblem extends OrgException {
        private final List<RowError> errors;
        HolidayProblem(List<RowError> errors) {
            super(422, "행별 오류를 고친 뒤 다시 저장해 주세요.");
            this.errors = List.copyOf(errors);
        }
        public List<RowError> errors() { return errors; }
    }

    private final OrgCommands commands;
    private final HolidayMapper mapper;
    private final LocalDemoLogin localDemo;
    private final Clock clock;

    public HolidayService(OrgCommands commands, HolidayMapper mapper, LocalDemoLogin localDemo, Clock clock) {
        this.commands = commands;
        this.mapper = mapper;
        this.localDemo = localDemo;
        this.clock = clock;
    }

    public PageModel page(TnaPrincipal actor, Integer requested) {
        int year = requested == null ? commands.today().getYear() : year(requested);
        return commands.read(actor, () -> {
            UUID tenant = actor.tenantId();
            var stored = mapper.year(tenant, year);
            var closed = mapper.closedMonths(tenant, first(year), last(year)).stream()
                    .map(month -> YearMonth.from(month).toString()).toList();
            Applied applied = stored == null || stored.appliedAt() == null ? null : new Applied(stored.appliedAt(), stored.appliedBy());
            return new PageModel(year, stored == null ? 0 : stored.version(), applied,
                    mapper.rows(tenant, first(year), last(year)), closed, !KoreanPublicHolidays.of(year).isEmpty());
        });
    }

    /** 정적 목록이라 DB를 읽지 않는다. 경로 권한은 SecurityConfiguration이 담당자로 제한한다. */
    public Preview publicHolidays(Integer requested) {
        int year = year(requested);
        return new Preview(year, KoreanPublicHolidays.of(year), KoreanPublicHolidays.SMALL_WORKPLACE_NOTICE);
    }

    public Result save(TnaPrincipal actor, Form form) {
        if (localDemo.publicDemo()) throw new OrgException(403, "공개 데모에서는 저장할 수 없습니다.");
        if (form == null || form.commandId() == null) throw OrgException.invalid("명령 식별자가 필요합니다. 화면을 다시 열어 주세요.");
        int year = year(form.year());
        if (form.expectedVersion() == null || form.expectedVersion() < 0) throw OrgException.invalid("현재 버전이 필요합니다. 화면을 다시 열어 주세요.");
        String reason = OrgCommands.requiredText(form.reason(), 500, "저장 사유");
        List<RowInput> rows = form.rows() == null ? List.of() : form.rows();
        if (rows.size() > MAX_ROWS || rows.stream().anyMatch(Objects::isNull)) throw OrgException.invalid("한 해에 366행까지 저장할 수 있습니다.");
        long expected = form.expectedVersion();
        String hash = OrgCommands.fingerprint(ACTION, year, expected, reason, rows.stream()
                .map(row -> String.join("\u001f", String.valueOf(row.clientRowId()), String.valueOf(row.date()),
                        String.valueOf(row.name()), String.valueOf(row.category()), String.valueOf(row.basis())))
                .toList().toString());
        commands.execute(actor, form.commandId(), ACTION, "HOLIDAY_YEAR", hash, reason, () -> apply(actor, year, expected, reason, rows));
        // 재시도(같은 commandId)는 execute가 변경 없이 돌아오고, 처음 감사의 결과를 그대로 읽는다.
        return commands.read(actor, () -> JSON.readValue(mapper.result(actor.tenantId(), form.commandId()), Result.class));
    }

    private OrgCommands.Change apply(TnaPrincipal actor, int year, long expected, String reason, List<RowInput> rows) {
        UUID tenant = actor.tenantId();
        LocalDate from = first(year), through = last(year);
        var stored = mapper.year(tenant, year);
        if ((stored == null ? 0 : stored.version()) != expected) throw OrgException.conflict("최신 내용을 확인한 뒤 다시 저장하세요.");
        Set<YearMonth> closed = mapper.closedMonths(tenant, from, through).stream().map(YearMonth::from).collect(Collectors.toSet());
        var errors = new ArrayList<RowError>();
        Map<LocalDate, Parsed> wanted = check(year, rows, errors);
        var existing = new HashMap<LocalDate, Row>();
        for (var row : mapper.rows(tenant, from, through)) existing.put(row.date(), row);
        var added = new TreeSet<LocalDate>();
        var updated = new TreeSet<LocalDate>();
        var deleted = new TreeSet<LocalDate>();
        for (var row : wanted.values()) {
            var old = existing.get(row.date());
            if (old == null) added.add(row.date());
            else if (!old.name().equals(row.name()) || !old.category().equals(row.category()) || !Objects.equals(old.basis(), row.basis())) {
                updated.add(row.date());
            }
        }
        for (var date : existing.keySet()) if (!wanted.containsKey(date)) deleted.add(date);
        for (var date : added) closedRow(wanted.get(date), closed, errors);
        for (var date : updated) closedRow(wanted.get(date), closed, errors);
        // 오류가 난 행의 날짜는 지워지는 것이 아니라 고칠 행이므로 삭제 검사에서 뺀다.
        var submitted = rows.stream().map(row -> row.date() == null ? "" : row.date().strip()).collect(Collectors.toSet());
        for (var date : deleted) {
            if (!submitted.contains(date.toString()) && closed.contains(YearMonth.from(date))) errors.add(new RowError(null, "date", date + ": " + YearMonth.from(date) + CLOSED));
        }
        if (!errors.isEmpty()) throw new HolidayProblem(errors);

        for (var date : deleted) mapper.deleteHoliday(tenant, date);
        for (var date : updated) {
            var row = wanted.get(date);
            mapper.updateHoliday(tenant, date, row.name(), row.category(), row.basis(), actor.accountId());
        }
        for (var date : added) {
            var row = wanted.get(date);
            mapper.insertHoliday(tenant, UUID.randomUUID(), date, row.name(), row.category(), row.basis(), actor.accountId());
        }

        var holidays = new HashMap<LocalDate, HolidayReflection.Holiday>();
        for (var row : wanted.values()) holidays.put(row.date(), new HolidayReflection.Holiday(row.category(), row.name(), row.basis()));
        var effective = new HashMap<LocalDate, HolidayReflection.Effective>();
        for (var day : mapper.effective(tenant, from, through)) effective.put(day.date(), day);
        var plan = HolidayReflection.plan(year, holidays, effective, mapper.periods(tenant, from, through), closed);
        int revision = mapper.nextRevision(tenant);
        for (var run : plan.periods()) write(actor, revision++, "PERIOD", run, reason);
        for (var day : plan.days()) write(actor, revision++, "DAY", List.of(day), reason);
        // 달력 출처가 바뀌어도 사실 개정은 그대로라 새벽 작업(지난 7일·재집계 필요)이 오래된 날을 놓친다. 마감 월은 plan이 이미 뺐다.
        for (var date : plan.dates()) mapper.flagRecalculation(tenant, date);
        mapper.saveYear(tenant, year, clock.instant().truncatedTo(ChronoUnit.MILLIS), actor.accountId());

        var changed = new TreeSet<LocalDate>(added);
        changed.addAll(updated);
        changed.addAll(deleted);
        var result = new Result(year, mapper.year(tenant, year).version(), added.size(), updated.size(), deleted.size(),
                List.copyOf(changed), plan.dates());
        return new OrgCommands.Change(UUID.randomUUID(), null, result);
    }

    private void write(TnaPrincipal actor, int revision, String scope, List<HolidayReflection.Day> days, String reason) {
        UUID id = UUID.randomUUID();
        mapper.insertVersion(actor.tenantId(), id, revision, days.getFirst().date(), days.getLast().date().plusDays(1),
                scope, reason, actor.accountId());
        for (var day : days) mapper.insertDay(actor.tenantId(), id, day.date(), day.kind(), day.name(), day.basis());
    }

    private static void closedRow(Parsed row, Set<YearMonth> closed, List<RowError> errors) {
        var month = YearMonth.from(row.date());
        if (closed.contains(month)) errors.add(new RowError(row.clientRowId(), "date", month + CLOSED));
    }

    /** 행 안의 규칙과 날짜 중복을 모두 모은다. 오류가 없는 날짜만 결과에 넣는다. */
    static Map<LocalDate, Parsed> check(int year, List<RowInput> rows, List<RowError> errors) {
        var ids = new HashSet<String>();
        var counts = new HashMap<LocalDate, Integer>();
        var parsed = new ArrayList<Parsed>();
        for (var row : rows) {
            String id = row.clientRowId();
            if (id == null || id.isBlank() || !ids.add(id)) errors.add(new RowError(id, "clientRowId", "행 식별자가 없거나 겹칩니다."));
            LocalDate date = date(row, year, errors);
            String name = text(row.name(), 120, "휴일명", id, "name", errors, true);
            String category = row.category() == null ? null : row.category().strip();
            if (category == null || !CATEGORIES.contains(category)) {
                errors.add(new RowError(id, "category", "구분을 선택해 주세요."));
                category = null;
            }
            String basis = text(row.basis(), 500, "근거", id, "basis", errors, false);
            if (date != null) counts.merge(date, 1, Integer::sum);
            parsed.add(new Parsed(id, date, name, category, basis));
        }
        var result = new LinkedHashMap<LocalDate, Parsed>();
        for (var row : parsed) {
            if (row.date() == null) continue;
            if (counts.get(row.date()) > 1) errors.add(new RowError(row.clientRowId(), "date", "같은 날짜가 두 번 있습니다."));
            else if (row.name() != null && row.category() != null) result.put(row.date(), row);
        }
        return result;
    }

    private static LocalDate date(RowInput row, int year, List<RowError> errors) {
        String raw = row.date() == null ? "" : row.date().strip();
        if (raw.isEmpty()) {
            errors.add(new RowError(row.clientRowId(), "date", "날짜를 입력해 주세요."));
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(raw);
            if (date.getYear() == year) return date;
            errors.add(new RowError(row.clientRowId(), "date", year + "년 날짜만 입력할 수 있습니다."));
        } catch (DateTimeParseException invalid) {
            errors.add(new RowError(row.clientRowId(), "date", "YYYY-MM-DD 형식으로 입력해 주세요."));
        }
        return null;
    }

    private static String text(String raw, int maximum, String label, String id, String field, List<RowError> errors, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) errors.add(new RowError(id, field, label + "을 입력해 주세요."));
            return null;
        }
        try {
            return OrgCommands.requiredText(raw, maximum, label);
        } catch (OrgException invalid) {
            errors.add(new RowError(id, field, invalid.getMessage()));
            return null;
        }
    }

    private static int year(Integer year) {
        if (year == null || year < 2000 || year > 2100) throw OrgException.invalid("연도는 2000~2100 사이로 선택해 주세요.");
        return year;
    }

    private static LocalDate first(int year) { return LocalDate.of(year, 1, 1); }
    private static LocalDate last(int year) { return LocalDate.of(year, 12, 31); }
}
