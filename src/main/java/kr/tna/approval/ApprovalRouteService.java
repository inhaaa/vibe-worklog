package kr.tna.approval;

import static kr.tna.org.OrgCommands.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import kr.tna.approval.ApprovalRouteData.*;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.*;
import kr.tna.org.BulkData.Operation;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class ApprovalRouteService {
    private final ApprovalRouteMapper mapper;
    private final OrgCommands commands;
    private final EmployeeAdminMapper employees;
    private final OrganizationAdminMapper organizations;
    private final ApproverMapper approvers;
    private final JsonMapper json = JsonMapper.builder().build();

    public ApprovalRouteService(ApprovalRouteMapper mapper, OrgCommands commands, EmployeeAdminMapper employees,
            OrganizationAdminMapper organizations, ApproverMapper approvers) {
        this.mapper=mapper; this.commands=commands; this.employees=employees; this.organizations=organizations; this.approvers=approvers;
    }

    public ManagementView management(TnaPrincipal actor, String q, int page, BulkData.Form<RouteRow> draft, List<BulkData.FieldError> errors) {
        String query = q == null || q.isBlank() ? "" : requiredText(q, 120, "검색어");
        int offset = offset(page);
        return read(actor, () -> {
            var found = mapper.list(actor.tenantId(), query, offset);
            var rows = List.copyOf(found.subList(0, Math.min(50, found.size())));
            var originals = new LinkedHashMap<UUID, Route>();
            rows.forEach(row -> originals.put(row.id(), row));
            if (draft != null && draft.rows().size() <= 50) for (var row : draft.rows()) {
                if (row.id() != null && !originals.containsKey(row.id())) originals.put(row.id(), requireRoute(actor.tenantId(), row.id()));
            }
            var edited = new ArrayList<>(originals.values().stream().map(row -> new RouteRow(row.id().toString(), row.id(), row.version(),
                    Operation.UPDATE, row.code(), row.name(), row.active(), row.approverKind(), row.employeeId())).toList());
            if (draft != null) for (var row : draft.rows()) {
                int index = -1;
                for (int i=0; i<edited.size(); i++) if (row.id()!=null && row.id().equals(edited.get(i).id())) { index=i; break; }
                if (index<0) edited.add(row); else edited.set(index,row);
            }
            var sheet = new BulkData.Sheet(draft == null ? UUID.randomUUID() : draft.commandId(), draft == null ? "" : draft.reason(),
                    List.copyOf(edited), List.copyOf(originals.values()), errors);
            return new ManagementView(query, page, found.size()>50, rows, List.copyOf(mapper.versions(actor.tenantId())),
                    List.copyOf(approvers.candidates(actor.tenantId(), commands.today())), sheet);
        });
    }

    public BulkData.Result save(TnaPrincipal actor, BulkData.Form<RouteRow> form) {
        String reason = validate(form);
        var rows = form.rows().stream().map(row -> new RouteRow(row.clientRowId(), row.id(), row.expectedVersion(), row.operation(),
                field(row, "code", () -> requiredText(row.code(),40,"결재선 코드")),
                field(row, "name", () -> requiredText(row.name(),120,"결재선 이름")),
                row.operation()!=Operation.DEACTIVATE && row.active(), row.approverKind(), row.employeeId())).toList();
        return commands.executeBatch(actor, form.commandId(), "APPROVAL_ROUTE_BULK", "APPROVAL_ROUTE",
                fingerprint("APPROVAL_ROUTE_BULK", reason, json.writeValueAsString(rows)), reason, () -> {
                    var codes = new HashSet<String>();
                    var before = new LinkedHashMap<String, Route>();
                    var after = new LinkedHashMap<String, Route>();
                    for (var row : rows) {
                        Route old = row.id()==null ? null : requireRoute(actor.tenantId(),row.id());
                        if (old!=null) {
                            field(row,"expectedVersion",()->{checkVersion(row.expectedVersion(),old.version());return old;});
                            if (!old.code().equals(row.code())) throw issue(row,"code",422,"결재선 코드는 저장 후 변경할 수 없습니다.");
                        }
                        var existing = mapper.code(actor.tenantId(), row.code());
                        if (!codes.add(row.code()) || existing!=null && !existing.id().equals(row.id())) throw issue(row,"code",409,"이미 사용 중인 결재선 코드입니다.");
                        if (!Set.of("DIRECT_EMPLOYEE","ORGANIZATION_HEAD").contains(row.approverKind())) throw issue(row,"approverKind",422,"지정 사원 또는 조직장 결재를 선택해 주세요.");
                        if (row.approverKind().equals("DIRECT_EMPLOYEE") != (row.employeeId()!=null)) throw issue(row,"employeeId",422,"지정 사원 결재에만 결재 사원을 선택해 주세요.");
                        boolean contentChanged = old==null || !old.name().equals(row.name()) || !old.approverKind().equals(row.approverKind()) || !Objects.equals(old.employeeId(),row.employeeId());
                        if (row.employeeId()!=null) {
                            if (employees.get(actor.tenantId(),row.employeeId())==null) throw OrgException.notFound();
                            if ((old==null || contentChanged || !old.active() && row.active())
                                    && !approvers.eligible(actor.tenantId(),row.employeeId(),commands.today())) throw issue(row,"employeeId",422,"수락 완료한 활성 재직 계정의 결재 담당 자격이 필요합니다.");
                        }
                        Route current = new Route(old==null?UUID.randomUUID():old.id(),row.code(),row.name(),row.active(),old==null?0:old.version()+1,
                                contentChanged?UUID.randomUUID():old.routeVersionId(),old==null?1:old.revision()+(contentChanged?1:0),row.approverKind(),row.employeeId(),null);
                        before.put(row.clientRowId(),old); after.put(row.clientRowId(),current);
                    }
                    var changes = new ArrayList<BulkData.Change>();
                    for (var row : rows) {
                        Route old=before.get(row.clientRowId()), current=after.get(row.clientRowId());
                        if (old==null) changed(mapper.insert(actor.tenantId(),current,actor.accountId()));
                        else changed(mapper.update(actor.tenantId(),current,old.version(),actor.accountId()));
                        if (old==null || !old.routeVersionId().equals(current.routeVersionId())) changed(mapper.insertVersion(actor.tenantId(),current,reason,actor.accountId()));
                        changes.add(new BulkData.Change(row.clientRowId(),current.id(),current.version(),old,current));
                    }
                    return changes;
                });
    }

    public AssignmentView assignments(TnaPrincipal actor, String requestedScope, UUID employeeId, UUID organizationId, AssignmentForm draft) {
        return read(actor, () -> {
            String scope = draft==null ? requestedScope : draft.scopeKind();
            UUID employee = draft==null ? employeeId : draft.employeeId(), organization = draft==null ? organizationId : draft.organizationId();
            var employeeOptions=List.copyOf(employees.options(actor.tenantId()));
            var organizationOptions=List.copyOf(organizations.options(actor.tenantId()));
            if (scope==null || scope.isBlank()) scope=organization!=null && employee==null ? "ORGANIZATION" : "EMPLOYEE";
            if (!Set.of("EMPLOYEE","ORGANIZATION").contains(scope)) throw OrgException.invalid("사원 또는 조직 배정을 선택해 주세요.");
            if (draft==null && employee==null && organization==null) {
                if (scope.equals("EMPLOYEE") && !employeeOptions.isEmpty()) employee=employeeOptions.getFirst().id();
                if (scope.equals("ORGANIZATION") && !organizationOptions.isEmpty()) organization=organizationOptions.getFirst().id();
            }
            UUID target=scope.equals("EMPLOYEE") ? employee : organization;
            Long version=target==null ? null : targetVersion(actor.tenantId(),scope,target);
            var history=target==null ? List.<RouteAssignment>of() : List.copyOf(mapper.history(actor.tenantId(),scope,target));
            var current=history.stream().filter(row->row.supersededAt()==null && !row.validFrom().isAfter(commands.today())
                    && (row.validTo()==null || row.validTo().isAfter(commands.today()))).findFirst().orElse(null);
            var form=draft==null ? new AssignmentForm(scope,employee,organization,current==null?null:current.routeVersionId(),commands.today(),version,"",UUID.randomUUID()) : draft;
            return new AssignmentView(commands.today(),scope,employee,organization,employeeOptions,organizationOptions,
                    List.copyOf(mapper.versions(actor.tenantId())),history,form);
        });
    }

    public UUID assign(TnaPrincipal actor, AssignmentForm form, boolean end) {
        scope(form);
        if (form.validFrom()==null || !end && form.routeVersionId()==null) throw OrgException.invalid("적용일과 결재선 버전을 선택해 주세요.");
        String reason=requiredText(form.reason(),500,"변경 사유"), action=end?"APPROVAL_ROUTE_END":"APPROVAL_ROUTE_ASSIGN";
        UUID target=form.scopeKind().equals("EMPLOYEE")?form.employeeId():form.organizationId();
        return commands.execute(actor,form.commandId(),action,form.scopeKind(),fingerprint(action,form.scopeKind(),target,
                end?null:form.routeVersionId(),form.validFrom(),form.expectedVersion(),reason),reason,()->{
                    UUID tenant=actor.tenantId();
                    long version=targetVersion(tenant,form.scopeKind(),target);
                    checkVersion(form.expectedVersion(),version);
                    LocalDate from=form.validFrom(),today=commands.today();
                    if (from.isBefore(today) || from.getYear()>9999) throw OrgException.invalid("적용일은 서울 기준 오늘 이후의 올바른 날짜로 지정해 주세요.");
                    if (!end) {
                        var selected=mapper.version(tenant,form.routeVersionId());
                        if (selected==null) throw OrgException.notFound();
                        if (!selected.active()) throw OrgException.invalid("활성 결재선의 버전을 선택해 주세요.");
                        if (form.scopeKind().equals("EMPLOYEE")) {
                            var employee=employees.get(tenant,target);
                            if (!employed(employee,today) || !employed(employee,from)) throw OrgException.invalid("현재와 적용일 모두 재직 중인 활성 사원에게 배정해 주세요.");
                            if (target.equals(selected.employeeId())) throw OrgException.invalid("자기 자신을 결재자로 지정할 수 없습니다.");
                        } else if (!organizations.find(tenant,target).active()) throw OrgException.invalid("활성 조직에만 결재선을 배정할 수 있습니다.");
                        if (selected.employeeId()!=null && (!approvers.eligible(tenant,selected.employeeId(),today)
                                || !employed(employees.get(tenant,selected.employeeId()),from))) throw OrgException.invalid("현재와 적용일 모두 재직하며 결재 담당 자격이 있는 사원이 필요합니다.");
                    }
                    var previous=mapper.covering(tenant,form.scopeKind(),target,from);
                    if (end && previous==null) throw OrgException.invalid("선택한 날짜에 종료할 결재선 배정이 없습니다.");
                    LocalDate to=previous==null?mapper.nextStart(tenant,form.scopeKind(),target,from):previous.validTo();
                    if (previous!=null) changed(mapper.close(tenant,previous.id(),from,previous.version(),actor.accountId()));
                    UUID assignment=null;
                    if (!end) { assignment=UUID.randomUUID(); changed(mapper.insertAssignment(tenant,assignment,form,to,reason,actor.accountId())); }
                    if (form.scopeKind().equals("EMPLOYEE")) changed(employees.advanceVersion(tenant,target,version,actor.accountId()));
                    else changed(mapper.advanceOrganization(tenant,target,version,actor.accountId()));
                    return new OrgCommands.Change(target,new AssignmentState(version,form.scopeKind(),previous==null?null:previous.id(),
                            previous==null?null:previous.routeVersionId(),previous==null?null:previous.validFrom(),previous==null?null:previous.validTo()),
                            new AssignmentState(version+1,form.scopeKind(),assignment,end?null:form.routeVersionId(),from,end?from:to));
                });
    }

    private long targetVersion(UUID tenant,String scope,UUID target) {
        if (scope.equals("EMPLOYEE")) { var employee=employees.get(tenant,target); if (employee==null) throw OrgException.notFound(); return employee.version(); }
        var organization=organizations.find(tenant,target); if (organization==null) throw OrgException.notFound(); return organization.version();
    }
    private static void scope(AssignmentForm form) {
        if (form==null || form.scopeKind()==null || !(form.scopeKind().equals("EMPLOYEE") && form.employeeId()!=null && form.organizationId()==null
                || form.scopeKind().equals("ORGANIZATION") && form.organizationId()!=null && form.employeeId()==null)) throw OrgException.invalid("사원과 조직 중 하나의 배정 대상을 선택해 주세요.");
    }
    private <T> T read(TnaPrincipal actor,Supplier<T> action) { return commands.read(actor,()->{mapper.lock(actor.tenantId());return commands.read(actor,action);}); }
    private Route requireRoute(UUID tenant,UUID id) { var route=mapper.get(tenant,id); if (route==null) throw OrgException.notFound(); return route; }
    private static boolean employed(OrgData.Employee employee,LocalDate date) { return employee!=null && employee.active() && !employee.joinedOn().isAfter(date) && (employee.leftOn()==null || !employee.leftOn().isBefore(date)); }
    private static String validate(BulkData.Form<RouteRow> form) {
        if (form==null || form.commandId()==null) throw new BulkException(422,"명령 식별자가 필요합니다.","","commandId");
        String reason;
        try { reason=requiredText(form.reason(),500,"변경 사유"); } catch (OrgException e) { throw new BulkException(e.status(),e.getMessage(),"","reason"); }
        if (form.rows()==null || form.rows().isEmpty() || form.rows().size()>50) throw new BulkException(422,"한 번에 변경한 1~50행을 저장해 주세요.","","rows");
        var clientIds=new HashSet<String>(); var ids=new HashSet<UUID>();
        for (var row:form.rows()) {
            if (row==null || row.clientRowId()==null || row.operation()==null) throw new BulkException(422,"행 식별자와 작업이 필요합니다.","","rows");
            try { if (!UUID.fromString(row.clientRowId()).toString().equals(row.clientRowId())) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException e) { throw issue(row,"clientRowId",422,"올바른 행 식별자가 필요합니다."); }
            if (!clientIds.add(row.clientRowId()) || row.id()!=null && !ids.add(row.id())) throw issue(row,"id",422,"같은 행이나 대상을 중복 저장할 수 없습니다.");
            if (row.operation()==Operation.CREATE && (row.id()!=null || row.expectedVersion()!=null)) throw issue(row,"id",422,"신규 행에 기존 식별자나 버전을 지정할 수 없습니다.");
            if (row.operation()!=Operation.CREATE && (row.id()==null || row.expectedVersion()==null || row.expectedVersion()<0)) throw issue(row,"expectedVersion",422,"기존 행의 식별자와 현재 버전이 필요합니다.");
        }
        return reason;
    }
    private static <T> T field(RouteRow row,String field,Supplier<T> action) { try { return action.get(); } catch (OrgException e) { throw issue(row,field,e.status(),e.getMessage()); } }
    private static BulkException issue(RouteRow row,String field,int status,String message) { return new BulkException(status,message,row.clientRowId(),field); }
    private static void changed(int rows) { if (rows!=1) throw OrgException.conflict("다른 변경이 반영되었습니다. 최신 내용을 확인해 주세요."); }
    private record AssignmentState(long version,String scopeKind,UUID assignmentId,UUID routeVersionId,LocalDate validFrom,LocalDate validTo) { }
}
