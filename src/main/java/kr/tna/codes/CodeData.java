package kr.tna.codes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.org.BulkData;

public final class CodeData {
    private CodeData() { }
    public record Group(UUID id,String code,String name,String description,int sortOrder,boolean active,boolean systemOwned,long version) { }
    public record Code(UUID id,UUID groupId,String code,String name,String description,int sortOrder,boolean active,boolean systemOwned,long version) { }
    public record GroupRow(String clientRowId,UUID id,Long expectedVersion,BulkData.Operation operation,String code,String name,String description,int sortOrder,boolean active) implements BulkData.Row { }
    public record CodeRow(String clientRowId,UUID id,Long expectedVersion,BulkData.Operation operation,UUID groupId,String code,String name,String description,int sortOrder,boolean active) implements BulkData.Row { }
    public record CommonView(String q,int page,boolean hasNext,List<Group> groups,UUID groupId,Group selectedGroup,
            String codeQ,int codePage,boolean codeHasNext,List<Code> codes,BulkData.Sheet groupSheet,BulkData.Sheet codeSheet) { }
    public record Type(UUID id,String code,String systemKind,boolean active,long version,UUID typeVersionId,long revision,
            LocalDate effectiveFrom,boolean enabled,String name,String unit,String payKind,String deductionRule,
            String recognitionRule,String basis,boolean supported,String capabilityMessage) { }
    public record TypeRow(String clientRowId,UUID id,Long expectedVersion,BulkData.Operation operation,String code,String systemKind,
            LocalDate effectiveFrom,boolean enabled,String name,String unit,String payKind,String deductionRule,String recognitionRule,String basis) implements BulkData.Row { }
    public record TypeVersion(UUID id,UUID typeId,String code,long revision,LocalDate effectiveFrom,boolean enabled,String name,
            String unit,String payKind,String deductionRule,String recognitionRule,String basis,String reason,Instant createdAt,UUID createdBy,String createdByName) { }
    public record TypesView(LocalDate today,String q,int page,boolean hasNext,List<Type> types,List<TypeVersion> versions,BulkData.Sheet sheet) { }
}
