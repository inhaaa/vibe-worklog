package kr.tna.worktime;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import kr.tna.worktime.MonthCloseExportMapper.Department;
import kr.tna.worktime.MonthCloseExportMapper.Event;
import org.springframework.stereotype.Service;

@Service
public final class MonthCloseExportService {
    record Options(String month, long version, List<Department> departments) {}
    record File(String name, byte[] bytes) {}
    private final BusinessCommands commands;
    private final OrgCommands administration;
    private final MonthCloseExportMapper mapper;

    public MonthCloseExportService(BusinessCommands commands, OrgCommands administration, MonthCloseExportMapper mapper) {
        this.commands = commands;
        this.administration = administration;
        this.mapper = mapper;
    }

    Options options(TnaPrincipal actor, String month, long version) {
        return readClosed(actor, month, version, event ->
                new Options(month, version, mapper.departments(actor.tenantId(), event.id())));
    }

    File workbook(TnaPrincipal actor, String month, long version, String scope, UUID organizationId) {
        return readClosed(actor, month, version, event -> {
            boolean company = "COMPANY".equals(scope);
            boolean department = "DEPARTMENT".equals(scope);
            if ((!company && !department && !"UNASSIGNED".equals(scope)) || department != (organizationId != null)) {
                throw OrgException.invalid("다운로드 범위와 부서를 확인해 주세요.");
            }
            var rows = mapper.rows(actor.tenantId(), event.id(), company, organizationId);
            if (!company && rows.isEmpty()) throw OrgException.notFound();
            String label = company ? "회사 전체" : MonthCloseWorkbook.name(rows.getFirst());
            String suffix = company ? "company" : department ? "department-" + organizationId : "unassigned";
            String name = "worklog-close-" + month + "-v" + version + "-" + suffix + ".xlsx";
            return new File(name, MonthCloseWorkbook.write(month, event, label, rows));
        });
    }

    private <T> T readClosed(TnaPrincipal actor, String month, long version, Function<Event,T> action) {
        return commands.read(actor, () -> administration.readShared(actor, () -> {
            var first = AttendanceMonthCloseService.month(month).atDay(1);
            if (version < 0) throw OrgException.invalid("마감 버전을 확인해 주세요.");
            var event = mapper.latest(actor.tenantId(), first);
            if (event == null || !"CLOSED".equals(event.status()) || event.version() != version) {
                throw OrgException.conflict("마감 상태가 바뀌었습니다. 해당 월을 다시 조회해 주세요.");
            }
            return action.apply(event);
        }));
    }
}
