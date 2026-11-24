package kr.tna.org;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.stereotype.Service;

@Service
public class AccountDirectoryService {
    static final List<String> STATES = List.of("all", "NONE", "PENDING", "BIRTH_DATE_REQUIRED", "LOCKED", "EXPIRED", "ACTIVE", "PREVIOUS_INVITATION");

    public record Item(UUID employeeId, String employeeNo, String displayName, String organizationName,
                       String state, String label, boolean self) { }
    public record Option(String value, String label) { }
    public record Directory(List<Item> items, String q, String status, int page, boolean hasNext, List<Option> statuses) { }

    private final OrgCommands commands;
    private final AccountDirectoryMapper mapper;
    private final Clock clock;

    public AccountDirectoryService(OrgCommands commands, AccountDirectoryMapper mapper, Clock clock) {
        this.commands = commands;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Directory directory(TnaPrincipal actor, String q, String status, int page) {
        String query = q == null ? "" : q.strip();
        if (query.length() > 80) throw OrgException.invalid("검색어는 80자 이하로 입력해 주세요.");
        if (!STATES.contains(status)) throw new OrgException(400, "상태 값을 확인해 주세요.");
        int offset = OrgCommands.offset(page);
        String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return commands.readShared(actor, () -> {
            var rows = mapper.list(actor.tenantId(), pattern, status, commands.today(), clock.instant(), offset);
            var items = rows.stream().limit(50).map(row -> new Item(row.employeeId(), row.employeeNo(), row.displayName(),
                    row.organizationName(), row.state(), AccountStatus.valueOf(row.state()).label(),
                    row.employeeId().equals(actor.employeeId()))).toList();
            var statuses = STATES.stream().map(value -> new Option(value,
                    value.equals("all") ? "전체" : AccountStatus.valueOf(value).label())).toList();
            return new Directory(items, query, status, page, rows.size() > 50, statuses);
        });
    }
}
