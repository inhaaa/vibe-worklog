package kr.tna.schedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import kr.tna.schedule.WorkTimeCodeData.*;
import org.springframework.stereotype.Service;

@Service
public final class WorkTimeCodeService {
    private final OrgCommands commands;
    private final WorkTimeCodeMapper mapper;

    public WorkTimeCodeService(OrgCommands commands, WorkTimeCodeMapper mapper) {
        this.commands=commands;
        this.mapper=mapper;
    }

    public Catalog catalog(TnaPrincipal actor, LocalDate requestedDate) {
        return commands.read(actor, () -> {
            LocalDate date = requestedDate == null ? commands.today() : requestedDate;
            UUID tenant = actor.tenantId();
            mapper.lock(tenant);
            var pauses = mapper.breaks(tenant).stream().collect(Collectors.groupingBy(BreakRow::versionId,
                    Collectors.mapping(b -> new Break(b.kind(),b.startTime(),b.endTime()), Collectors.toList())));
            var uses = mapper.groupUses(tenant,date).stream().collect(Collectors.groupingBy(GroupUse::versionId));
            var revisions = new HashMap<UUID,List<Version>>();
            for (var row : mapper.versions(tenant)) {
                var breaks = pauses.getOrDefault(row.id(), List.of());
                var totals = WorkTimeCodeRules.validate(row,breaks);
                var version = new Version(row.id(),row.revision(),row.effectiveFrom(),row.enabled(),row.name(),
                        row.startTime(),row.endTime(),row.beforeStart(),row.beforeEnd(),row.afterStart(),row.afterEnd(),
                        breaks,totals.scheduledMinutes(),totals.overtimeMinutes(),row.reason(),uses.getOrDefault(row.id(),List.of()),
                        row.workMode(),row.coreStart(),row.coreEnd(),row.standardMinutes(),row.settlementPeriod());
                revisions.computeIfAbsent(row.codeId(),ignored -> new ArrayList<>()).add(version);
            }
            var codes = mapper.masters(tenant).stream().map(m -> new Code(m.id(),m.code(),m.version(),
                    revisions.getOrDefault(m.id(),List.of()))).toList();
            return new Catalog(date,codes);
        });
    }

    public UUID save(TnaPrincipal actor, Command c) {
        if (c == null) throw OrgException.invalid("근무시간코드를 입력해 주세요.");
        return commands.execute(actor,c.commandId(),"WORK_TIME_CODE_SAVE","WORK_TIME_CODE",
                fingerprint(c),c.reason(),() -> saveLocked(actor,c));
    }

    private static String fingerprint(Command c) {
        String fixed=OrgCommands.fingerprint(c.id(),c.expectedVersion(),c.code(),c.name(),c.effectiveFrom(),c.enabled(),
                c.startTime(),c.endTime(),c.beforeStart(),c.beforeEnd(),c.afterStart(),c.afterEnd(),
                c.breaks(),c.reason(),c.replaceVersionId());
        // Previously saved fixed commands must keep their original replay key.
        if ("FIXED".equals(c.workMode()) && c.coreStart()==null && c.coreEnd()==null
                && c.standardMinutes()==null && c.settlementPeriod()==null) return fixed;
        return OrgCommands.fingerprint(fixed,c.workMode(),c.coreStart(),c.coreEnd(),c.standardMinutes(),c.settlementPeriod());
    }

    private OrgCommands.Change saveLocked(TnaPrincipal actor, Command c) {
        if (c.code() == null || !c.code().matches("[A-Z0-9]{2,8}"))
            throw OrgException.invalid("근무시간코드는 영문 대문자·숫자 2~8자리로 입력해 주세요.");
        String name = OrgCommands.requiredText(c.name(),120,"코드명");
        if (c.effectiveFrom() == null || c.effectiveFrom().isBefore(commands.today()) || c.effectiveFrom().getYear()>9999)
            throw OrgException.invalid("사용 시작일은 서울 기준 오늘 이후 날짜로 입력해 주세요.");
        if (c.enabled() == null) throw OrgException.invalid("사용 여부를 선택해 주세요.");
        WorkTimeCodeRules.validate(c,c.breaks());
        UUID tenant = actor.tenantId(), codeId = c.id();
        Master old = null;
        long revision = 1;
        if (codeId == null) {
            if (c.expectedVersion() != null || c.replaceVersionId() != null) throw OrgException.invalid("새 코드의 입력값을 확인해 주세요.");
            codeId = UUID.randomUUID();
            mapper.create(tenant,codeId,c.code());
        } else {
            old = mapper.master(tenant,codeId);
            if (old == null) throw OrgException.notFound();
            if (!old.code().equals(c.code())) throw OrgException.invalid("저장한 코드는 변경할 수 없습니다.");
            OrgCommands.checkVersion(c.expectedVersion(),old.version());
            UUID reserved = mapper.scheduledVersion(tenant,codeId,c.effectiveFrom());
            if (!Objects.equals(reserved,c.replaceVersionId()))
                throw OrgException.conflict(c.effectiveFrom()+" 적용 예약을 확인하고 정정할 버전을 선택해 주세요.");
            if (mapper.advance(tenant,codeId,old.version()) != 1) throw OrgException.conflict("코드를 다시 조회해 주세요.");
            revision = old.version()+2;
        }
        UUID versionId = UUID.randomUUID();
        mapper.append(tenant,versionId,codeId,revision,name,c,actor.accountId());
        for (var pause : c.breaks()) mapper.addBreak(tenant,versionId,pause);
        return new OrgCommands.Change(codeId,old,new Saved(versionId,revision,c.effectiveFrom(),c.replaceVersionId()));
    }

    private record Saved(UUID versionId,long revision,LocalDate effectiveFrom,UUID replacedVersionId) {}
}
