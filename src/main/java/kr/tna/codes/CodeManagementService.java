package kr.tna.codes;

import static kr.tna.org.OrgCommands.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import kr.tna.codes.CodeData.*;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.*;
import kr.tna.org.BulkData.Operation;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class CodeManagementService {
    private final CodeMapper mapper;
    private final OrgCommands commands;
    private final JsonMapper json=JsonMapper.builder().build();
    public CodeManagementService(CodeMapper mapper,OrgCommands commands){this.mapper=mapper;this.commands=commands;}

    public CommonView common(TnaPrincipal actor,String q,int page,UUID groupId,String codeQ,int codePage,
            BulkData.Form<GroupRow> groupDraft,BulkData.Form<CodeRow> codeDraft,List<BulkData.FieldError> errors) {
        String query=query(q),codeQuery=query(codeQ);int offset=offset(page),codeOffset=offset(codePage);
        return read(actor,()->{
            UUID tenant=actor.tenantId();var found=mapper.groups(tenant,query,offset);var groups=page(found);
            UUID selected=groupId==null?(groups.isEmpty()?null:groups.getFirst().id()):groupId;
            Group group=selected==null?null:requireGroup(tenant,selected);
            var codeFound=selected==null?List.<Code>of():mapper.codes(tenant,selected,codeQuery,codeOffset);var codes=page(codeFound);
            return new CommonView(query,page,found.size()>50,groups,selected,group,codeQuery,codePage,codeFound.size()>50,codes,
                    sheet(groups,groupDraft,groupDraft==null?List.of():errors,g->new GroupRow(g.id().toString(),g.id(),g.version(),Operation.UPDATE,g.code(),g.name(),g.description(),g.sortOrder(),g.active()),id->requireGroup(tenant,id)),
                    sheet(codes,codeDraft,codeDraft==null?List.of():errors,c->new CodeRow(c.id().toString(),c.id(),c.version(),Operation.UPDATE,c.groupId(),c.code(),c.name(),c.description(),c.sortOrder(),c.active()),id->requireCode(tenant,id)));
        });
    }
    public TypesView types(TnaPrincipal actor,String q,int page,BulkData.Form<TypeRow> draft,List<BulkData.FieldError> errors) {
        String query=query(q);int offset=offset(page);
        return read(actor,()->{
            var found=mapper.types(actor.tenantId(),query,offset);var types=page(found);
            var sheet=sheet(types,draft,errors,t->new TypeRow(t.id().toString(),t.id(),t.version(),Operation.UPDATE,t.code(),t.systemKind(),t.effectiveFrom(),t.enabled(),t.name(),t.unit(),t.payKind(),t.deductionRule(),t.recognitionRule(),t.basis()),id->requireType(actor.tenantId(),id));
            var versions=new ArrayList<TypeVersion>();for(var original:sheet.latestRows())versions.addAll(mapper.versions(actor.tenantId(),((Type)original).id()));
            return new TypesView(commands.today(),query,page,found.size()>50,types,List.copyOf(versions),sheet);
        });
    }
    public BulkData.Result saveGroups(TnaPrincipal actor,BulkData.Form<GroupRow> form) {
        String reason=validate(form);
        var rows=form.rows().stream().map(r->new GroupRow(r.clientRowId(),r.id(),r.expectedVersion(),r.operation(),
                text(r,"code",r.code(),40,"그룹 코드"),text(r,"name",r.name(),120,"그룹 이름"),description(r,r.description()),order(r,r.sortOrder()),r.operation()!=Operation.DEACTIVATE&&r.active())).toList();
        return commands.executeBatch(actor,form.commandId(),"COMMON_GROUP_BULK","COMMON_CODE_GROUP",fingerprint("COMMON_GROUP_BULK",reason,json.writeValueAsString(rows)),reason,()->{
            var codes=new HashSet<String>();var before=new LinkedHashMap<String,Group>();var after=new LinkedHashMap<String,Group>();
            for(var row:rows){
                Group old=row.id()==null?null:requireGroup(actor.tenantId(),row.id());
                if(old!=null){version(row,old.version());if(old.systemOwned())throw issue(row,"code",422,"시스템 소유 그룹은 변경하거나 비활성화할 수 없습니다.");if(!old.code().equals(row.code()))throw issue(row,"code",422,"그룹 코드는 저장 후 변경할 수 없습니다.");}
                Group existing=mapper.groupCode(actor.tenantId(),row.code());unique(row,codes,existing==null?null:existing.id(),row.code());
                before.put(row.clientRowId(),old);after.put(row.clientRowId(),new Group(old==null?UUID.randomUUID():old.id(),row.code(),row.name(),row.description(),row.sortOrder(),row.active(),false,old==null?0:old.version()+1));
            }
            var changes=new ArrayList<BulkData.Change>();for(var row:rows){var old=before.get(row.clientRowId());var current=after.get(row.clientRowId());
                changed(old==null?mapper.insertGroup(actor.tenantId(),current,actor.accountId()):mapper.updateGroup(actor.tenantId(),current,old.version(),actor.accountId()));
                changes.add(new BulkData.Change(row.clientRowId(),current.id(),current.version(),old,current));}return changes;
        });
    }
    public BulkData.Result saveCodes(TnaPrincipal actor,UUID groupId,BulkData.Form<CodeRow> form) {
        String reason=validate(form);
        var rows=form.rows().stream().map(r->new CodeRow(r.clientRowId(),r.id(),r.expectedVersion(),r.operation(),r.groupId(),
                text(r,"code",r.code(),40,"상세 코드"),text(r,"name",r.name(),120,"코드 이름"),description(r,r.description()),order(r,r.sortOrder()),r.operation()!=Operation.DEACTIVATE&&r.active())).toList();
        return commands.executeBatch(actor,form.commandId(),"COMMON_CODE_BULK","COMMON_CODE",fingerprint("COMMON_CODE_BULK",groupId,reason,json.writeValueAsString(rows)),reason,()->{
            if(groupId==null)throw OrgException.invalid("코드그룹을 선택해 주세요.");var group=requireGroup(actor.tenantId(),groupId);
            var codes=new HashSet<String>();var before=new LinkedHashMap<String,Code>();var after=new LinkedHashMap<String,Code>();
            for(var row:rows){
                if(!groupId.equals(row.groupId()))throw issue(row,"groupId",422,"현재 선택한 코드그룹 안에서 저장해 주세요.");
                Code old=row.id()==null?null:requireCode(actor.tenantId(),row.id());
                if(group.systemOwned()||old!=null&&old.systemOwned())throw issue(row,"code",422,"시스템 소유 코드그룹과 코드는 편집할 수 없습니다.");
                if(old!=null){version(row,old.version());if(!old.groupId().equals(groupId))throw issue(row,"groupId",422,"저장한 코드의 그룹은 변경할 수 없습니다.");if(!old.code().equals(row.code()))throw issue(row,"code",422,"상세 코드는 저장 후 변경할 수 없습니다.");}
                Code existing=mapper.codeValue(actor.tenantId(),groupId,row.code());unique(row,codes,existing==null?null:existing.id(),row.code());
                before.put(row.clientRowId(),old);after.put(row.clientRowId(),new Code(old==null?UUID.randomUUID():old.id(),groupId,row.code(),row.name(),row.description(),row.sortOrder(),row.active(),false,old==null?0:old.version()+1));
            }
            var changes=new ArrayList<BulkData.Change>();for(var row:rows){var old=before.get(row.clientRowId());var current=after.get(row.clientRowId());
                changed(old==null?mapper.insertCode(actor.tenantId(),current,actor.accountId()):mapper.updateCode(actor.tenantId(),current,old.version(),actor.accountId()));
                changes.add(new BulkData.Change(row.clientRowId(),current.id(),current.version(),old,current));}return changes;
        });
    }
    public BulkData.Result saveTypes(TnaPrincipal actor,BulkData.Form<TypeRow> form) {
        String reason=validate(form);
        var rows=form.rows().stream().map(r->new TypeRow(r.clientRowId(),r.id(),r.expectedVersion(),r.operation(),text(r,"code",r.code(),40,"근태 코드"),r.systemKind(),r.effectiveFrom(),r.operation()!=Operation.DEACTIVATE&&r.enabled(),
                text(r,"name",r.name(),120,"근태 이름"),r.unit(),r.payKind(),r.deductionRule(),r.recognitionRule(),text(r,"basis",r.basis(),500,"정책 근거"))).toList();
        return commands.executeBatch(actor,form.commandId(),"ATTENDANCE_TYPE_BULK","ATTENDANCE_TYPE",fingerprint("ATTENDANCE_TYPE_BULK",reason,json.writeValueAsString(rows)),reason,()->{
            var codes=new HashSet<String>();var before=new LinkedHashMap<String,Type>();var after=new LinkedHashMap<String,Type>();
            for(var row:rows){
                Type old=row.id()==null?null:requireType(actor.tenantId(),row.id());
                String code;
                if (old == null) {
                    if (row.systemKind() != null) throw issue(row,"systemKind",422,"새 코드를 시스템 처리에 연결할 수 없습니다.");
                    code = row.code();
                    if (!code.matches("[A-Z0-9]{1,8}")) throw issue(row,"code",422,"근태코드는 영문 대문자와 숫자 1~8자로 입력해 주세요.");
                    if (mapper.typeCodeExists(actor.tenantId(),code)) throw issue(row,"code",409,"이미 사용 중인 코드입니다.");
                } else {
                    version(row,old.version());
                    if (!Objects.equals(old.systemKind(),row.systemKind())) throw issue(row,"systemKind",422,"시스템 처리 유형은 변경할 수 없습니다.");
                    if (!old.code().equals(row.code()) && !row.code().equals(old.systemKind()))
                        throw issue(row,"code",422,"근태 코드는 저장 후 변경할 수 없습니다.");
                    code = old.code();
                }
                unique(row,codes,null,code);
                if(row.effectiveFrom()==null||row.effectiveFrom().isBefore(commands.today())||row.effectiveFrom().getYear()>9999)throw issue(row,"effectiveFrom",422,"적용일은 서울 기준 오늘 이후의 날짜로 지정해 주세요.");
                policy(row);
                boolean companyLeave = CompanyLeavePolicy.RULE.equals(row.recognitionRule());
                String capability = row.systemKind() != null ? old.capabilityMessage()
                        : companyLeave ? "회사 휴가 · 대상일 정책 적용" : "초안 · 신청 전";
                before.put(row.clientRowId(),old);after.put(row.clientRowId(),new Type(old==null?UUID.randomUUID():old.id(),code,row.systemKind(),row.enabled(),old==null?0:old.version()+1,UUID.randomUUID(),old==null?1:old.revision()+1,
                        row.effectiveFrom(),row.enabled(),row.name(),row.unit(),row.payKind(),row.deductionRule(),row.recognitionRule(),row.basis(),row.systemKind()!=null || companyLeave,capability));
            }
            var changes=new ArrayList<BulkData.Change>();for(var row:rows){var old=before.get(row.clientRowId());var current=after.get(row.clientRowId());
                changed(old==null?mapper.insertType(actor.tenantId(),current,actor.accountId()):mapper.updateType(actor.tenantId(),current,old.version(),actor.accountId()));
                changed(mapper.insertTypeVersion(actor.tenantId(),current,reason,actor.accountId()));
                changes.add(new BulkData.Change(row.clientRowId(),current.id(),current.version(),old,current));}return changes;
        });
    }
    private static void policy(TypeRow row){
        if (row.systemKind() == null && CompanyLeavePolicy.RULE.equals(row.recognitionRule())) {
            if (CompanyLeavePolicy.supports(row.unit(), row.payKind(), row.deductionRule(), row.recognitionRule())) return;
            if (!"DAY".equals(row.unit()) && !"DAY_HALF".equals(row.unit()))
                throw issue(row,"unit",422,"회사 휴가는 종일 또는 종일·반일 단위를 선택해 주세요.");
            if (!"PAID".equals(row.payKind()) && !"UNPAID".equals(row.payKind()))
                throw issue(row,"payKind",422,"회사 휴가의 유급 또는 무급 여부를 선택해 주세요.");
            throw issue(row,"deductionRule",422,"연차 잔여 차감 여부를 선택해 주세요.");
        }
        if(row.unit()==null||!Set.of("DAY","HOUR","NONE").contains(row.unit()))throw issue(row,"unit",422,"지원하는 단위를 선택해 주세요.");
        if(row.payKind()==null||!Set.of("UNSPECIFIED","PAID","UNPAID").contains(row.payKind()))throw issue(row,"payKind",422,"유급 구분을 선택해 주세요.");
        if(!"NOT_SUPPORTED".equals(row.recognitionRule()))throw issue(row,"recognitionRule",422,"근무 인정시간 산정은 현재 지원하지 않습니다.");
        if(row.systemKind()==null){if(row.enabled())throw issue(row,"enabled",422,"계산·차감·결재 계약 미지원 코드는 초안으로만 등록할 수 있습니다.");if(!"NONE".equals(row.deductionRule()))throw issue(row,"deductionRule",422,"초안의 차감 방식은 미연결로 지정해 주세요.");}
        else if("ANNUAL_LEAVE".equals(row.systemKind())){if(!"DAY".equals(row.unit())||!"LEAVE_DAY".equals(row.deductionRule()))throw issue(row,"unit",422,"연차는 일 단위로 관리하고 최종 승인 시 차감합니다.");}
        else if("ATTENDANCE_CORRECTION".equals(row.systemKind())){if(!"NONE".equals(row.unit())||!"NONE".equals(row.deductionRule()))throw issue(row,"unit",422,"정정은 휴가 단위·차감 없이 기존 정정 절차로 처리합니다.");}
        else throw issue(row,"systemKind",422,"지원하지 않는 시스템 처리 유형입니다.");
    }
    private <T>T read(TnaPrincipal actor,Supplier<T> action){return commands.read(actor,()->{mapper.lock(actor.tenantId());return commands.read(actor,action);});}
    private Group requireGroup(UUID tenant,UUID id){var value=mapper.group(tenant,id);if(value==null)throw OrgException.notFound();return value;}
    private Code requireCode(UUID tenant,UUID id){var value=mapper.code(tenant,id);if(value==null)throw OrgException.notFound();return value;}
    private Type requireType(UUID tenant,UUID id){var value=mapper.type(tenant,id);if(value==null)throw OrgException.notFound();return value;}
    private static <T>List<T> page(List<T> rows){return List.copyOf(rows.subList(0,Math.min(50,rows.size())));}
    private static String query(String value){return value==null||value.isBlank()?"":requiredText(value,120,"검색어");}
    private static <T,R extends BulkData.Row>BulkData.Sheet sheet(List<T> latest,BulkData.Form<R> draft,List<BulkData.FieldError> errors,Function<T,R> convert,Function<UUID,T> load){
        var originals=new LinkedHashMap<UUID,T>();for(T value:latest)originals.put(convert.apply(value).id(),value);
        if(draft!=null&&draft.rows().size()<=50)for(var row:draft.rows())if(row.id()!=null&&!originals.containsKey(row.id()))originals.put(row.id(),load.apply(row.id()));
        var rows=new ArrayList<>(originals.values().stream().map(convert).toList());
        if(draft!=null){var replaced=new HashSet<UUID>();for(var row:draft.rows()){int index=-1;if(row.id()!=null&&replaced.add(row.id()))for(int i=0;i<rows.size();i++)if(row.id().equals(rows.get(i).id())){index=i;break;}if(index<0)rows.add(row);else rows.set(index,row);}}
        return new BulkData.Sheet(draft==null?UUID.randomUUID():draft.commandId(),draft==null?"":draft.reason(),List.copyOf(rows),List.copyOf(originals.values()),errors);
    }
    private static <R extends BulkData.Row>String validate(BulkData.Form<R> form){
        if(form==null||form.commandId()==null)throw new BulkException(422,"명령 식별자가 필요합니다.","","commandId");
        String reason;try{reason=requiredText(form.reason(),500,"변경 사유");}catch(OrgException e){throw new BulkException(e.status(),e.getMessage(),"","reason");}
        if(form.rows()==null||form.rows().isEmpty()||form.rows().size()>50)throw new BulkException(422,"한 번에 변경한 1~50행을 저장해 주세요.","","rows");
        var clients=new HashSet<String>();var ids=new HashSet<UUID>();for(var row:form.rows()){
            if(row==null||row.clientRowId()==null||row.operation()==null)throw new BulkException(422,"행 식별자와 작업이 필요합니다.","","rows");
            try{if(!UUID.fromString(row.clientRowId()).toString().equals(row.clientRowId()))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw issue(row,"clientRowId",422,"올바른 행 식별자가 필요합니다.");}
            if(!clients.add(row.clientRowId())||row.id()!=null&&!ids.add(row.id()))throw issue(row,"id",422,"같은 행이나 대상을 중복 저장할 수 없습니다.");
            if(row.operation()==Operation.CREATE&&(row.id()!=null||row.expectedVersion()!=null))throw issue(row,"id",422,"신규 행에 기존 식별자나 버전을 지정할 수 없습니다.");
            if(row.operation()!=Operation.CREATE&&(row.id()==null||row.expectedVersion()==null||row.expectedVersion()<0))throw issue(row,"expectedVersion",422,"기존 행의 식별자와 현재 버전이 필요합니다.");
        }return reason;
    }
    private static String text(BulkData.Row row,String field,String value,int max,String label){try{return requiredText(value,max,label);}catch(OrgException e){throw issue(row,field,e.status(),e.getMessage());}}
    private static String description(BulkData.Row row,String value){return value==null||value.isBlank()?"":text(row,"description",value,500,"설명");}
    private static int order(BulkData.Row row,int value){if(value<0||value>1000000)throw issue(row,"sortOrder",422,"정렬은 0~1,000,000 범위입니다.");return value;}
    private static void version(BulkData.Row row,long actual){try{checkVersion(row.expectedVersion(),actual);}catch(OrgException e){throw issue(row,"expectedVersion",e.status(),e.getMessage());}}
    private static void unique(BulkData.Row row,Set<String> codes,UUID existing,String code){if(!codes.add(code)||existing!=null&&!existing.equals(row.id()))throw issue(row,"code",409,"이미 사용 중인 코드입니다.");}
    private static BulkException issue(BulkData.Row row,String field,int status,String message){return new BulkException(status,message,row.clientRowId(),field);}
    private static void changed(int rows){if(rows!=1)throw OrgException.conflict("다른 변경이 반영되었습니다. 최신 내용을 확인해 주세요.");}
}
