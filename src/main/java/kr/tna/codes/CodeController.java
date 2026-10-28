package kr.tna.codes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.codes.CodeData.*;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Controller
public final class CodeController {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> COMMON_PARAMETERS=Set.of("commandId","reason","rows","q","page","groupId","codeQ","codePage","_csrf");
    private static final Set<String> TYPE_PARAMETERS=Set.of("commandId","reason","rows","q","page","_csrf");
    private static final Set<String> GROUP_FIELDS=Set.of("clientRowId","id","expectedVersion","operation","code","name","description","sortOrder","active");
    private static final Set<String> CODE_FIELDS=Set.of("clientRowId","id","expectedVersion","operation","groupId","code","name","description","sortOrder","active");
    private static final Set<String> TYPE_FIELDS=Set.of("clientRowId","id","expectedVersion","operation","code","systemKind","effectiveFrom","enabled","name","unit","payKind","deductionRule","recognitionRule","basis");
    private final CodeManagementService service;
    public CodeController(CodeManagementService service){this.service=service;}

    @GetMapping("/admin/common-codes")
    ModelAndView common(@AuthenticationPrincipal TnaPrincipal actor,@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="0")int page,
            @RequestParam(required=false)UUID groupId,@RequestParam(defaultValue="")String codeQ,@RequestParam(defaultValue="0")int codePage){
        return commonPage(actor,new Context(q,page,groupId,codeQ,codePage),null,null,List.of());
    }
    @PostMapping("/admin/common-code-groups/bulk")
    ModelAndView groups(@AuthenticationPrincipal TnaPrincipal actor,HttpServletRequest request){
        parameters(request,COMMON_PARAMETERS);Context context=context(request);UUID command=command(request);String reason=parameter(request,"reason","");
        var rows=new ArrayList<GroupRow>();for(var n:rows(request,GROUP_FIELDS))rows.add(new GroupRow(client(n),uuid(n,"id"),version(n),operation(n),text(n,"code"),text(n,"name"),text(n,"description"),integer(n,"sortOrder"),bool(n,"active")));
        var form=new BulkData.Form<>(command,reason,List.copyOf(rows));commonPage(actor,context,null,null,List.of());
        try{service.saveGroups(actor,form);return redirect(commonUri(context));}
        catch(OrgException error){if(error.status()!=409&&error.status()!=422)throw error;return failure(commonPage(actor,context,form,null,errors(error)),error);}
    }
    @PostMapping("/admin/common-codes/bulk")
    ModelAndView codes(@AuthenticationPrincipal TnaPrincipal actor,HttpServletRequest request){
        parameters(request,COMMON_PARAMETERS);Context context=context(request);UUID command=command(request);String reason=parameter(request,"reason","");
        var rows=new ArrayList<CodeRow>();for(var n:rows(request,CODE_FIELDS))rows.add(new CodeRow(client(n),uuid(n,"id"),version(n),operation(n),uuid(n,"groupId"),text(n,"code"),text(n,"name"),text(n,"description"),integer(n,"sortOrder"),bool(n,"active")));
        var form=new BulkData.Form<>(command,reason,List.copyOf(rows));commonPage(actor,context,null,null,List.of());
        try{service.saveCodes(actor,context.groupId(),form);return redirect(commonUri(context));}
        catch(OrgException error){if(error.status()!=409&&error.status()!=422)throw error;return failure(commonPage(actor,context,null,form,errors(error)),error);}
    }
    @GetMapping("/admin/attendance-types")
    ModelAndView types(@AuthenticationPrincipal TnaPrincipal actor,@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="0")int page){return typePage(actor,q,page,null,List.of());}
    @PostMapping("/admin/attendance-types/bulk")
    ModelAndView types(@AuthenticationPrincipal TnaPrincipal actor,HttpServletRequest request){
        parameters(request,TYPE_PARAMETERS);UUID command=command(request);String reason=parameter(request,"reason",""),q=parameter(request,"q","");int page=page(request,"page");
        var rows=new ArrayList<TypeRow>();for(var n:rows(request,TYPE_FIELDS))rows.add(new TypeRow(client(n),uuid(n,"id"),version(n),operation(n),text(n,"code"),nullableText(n,"systemKind"),date(n,"effectiveFrom"),bool(n,"enabled"),text(n,"name"),text(n,"unit"),text(n,"payKind"),text(n,"deductionRule"),text(n,"recognitionRule"),text(n,"basis")));
        var form=new BulkData.Form<>(command,reason,List.copyOf(rows));typePage(actor,q,page,null,List.of());
        try{service.saveTypes(actor,form);return redirect(UriComponentsBuilder.fromPath("/admin/attendance-types").queryParam("q",q).queryParam("page",page).queryParam("saved","").build().encode().toUriString());}
        catch(OrgException error){if(error.status()!=409&&error.status()!=422)throw error;return failure(typePage(actor,q,page,form,errors(error)),error);}
    }
    private ModelAndView commonPage(TnaPrincipal actor,Context c,BulkData.Form<GroupRow> groups,BulkData.Form<CodeRow> codes,List<BulkData.FieldError> errors){return new ModelAndView("org/common-codes").addObject("commonCodes",service.common(actor,c.q(),c.page(),c.groupId(),c.codeQ(),c.codePage(),groups,codes,errors));}
    private ModelAndView typePage(TnaPrincipal actor,String q,int page,BulkData.Form<TypeRow> form,List<BulkData.FieldError> errors){return new ModelAndView("org/attendance-types").addObject("attendanceTypes",service.types(actor,q,page,form,errors));}
    private static List<JsonNode> rows(HttpServletRequest request,Set<String> fields){
        String raw=request.getParameter("rows");if(raw==null||raw.length()>200000)throw malformed();
        try{var tree=JSON.readTree(raw);if(tree==null||!tree.isArray())throw malformed();var result=new ArrayList<JsonNode>();
            for(var node:tree){if(!node.isObject())throw malformed();for(String field:node.propertyNames())if(!fields.contains(field))throw malformed();result.add(node);}return List.copyOf(result);
        }catch(JacksonException|IllegalArgumentException e){throw malformed();}
    }
    private static UUID command(HttpServletRequest r){try{return UUID.fromString(parameter(r,"commandId",""));}catch(IllegalArgumentException e){throw malformed();}}
    private static Context context(HttpServletRequest r){try{String group=parameter(r,"groupId","");return new Context(parameter(r,"q",""),page(r,"page"),group.isEmpty()?null:UUID.fromString(group),parameter(r,"codeQ",""),page(r,"codePage"));}catch(IllegalArgumentException e){throw malformed();}}
    private static int page(HttpServletRequest r,String name){try{return Integer.parseInt(parameter(r,name,"0"));}catch(IllegalArgumentException e){throw malformed();}}
    private static String commonUri(Context c){var uri=UriComponentsBuilder.fromPath("/admin/common-codes").queryParam("q",c.q()).queryParam("page",c.page()).queryParam("codeQ",c.codeQ()).queryParam("codePage",c.codePage()).queryParam("saved","");if(c.groupId()!=null)uri.queryParam("groupId",c.groupId());return uri.build().encode().toUriString();}
    private static List<BulkData.FieldError> errors(OrgException error){return error instanceof BulkException bulk?bulk.errors():List.of(new BulkData.FieldError("","rows",error.getMessage()));}
    private static ModelAndView failure(ModelAndView view,OrgException error){view.addObject("error",error.getMessage());view.setStatus(HttpStatusCode.valueOf(error.status()));return view;}
    private static ModelAndView redirect(String uri){var view=new RedirectView(uri,true);view.setStatusCode(HttpStatus.SEE_OTHER);return new ModelAndView(view);}
    private static void parameters(HttpServletRequest r,Set<String> allowed){r.getParameterMap().forEach((name,values)->{if(!allowed.contains(name)||values.length!=1)throw malformed();});}
    private static String client(JsonNode n){String value=text(n,"clientRowId");try{if(!UUID.fromString(value).toString().equals(value))throw malformed();return value;}catch(IllegalArgumentException e){throw malformed();}}
    private static UUID uuid(JsonNode n,String field){String value=nullableText(n,field);try{return value==null||value.isEmpty()?null:UUID.fromString(value);}catch(IllegalArgumentException e){throw malformed();}}
    private static String nullableText(JsonNode n,String field){var v=n.get(field);if(v==null||v.isNull())return null;if(!v.isString())throw malformed();return v.asString();}
    private static String text(JsonNode n,String field){String value=nullableText(n,field);return value==null?"":value;}
    private static Long version(JsonNode n){var v=n.get("expectedVersion");if(v==null||v.isNull())return null;if(!v.isIntegralNumber()||!v.canConvertToLong())throw malformed();return v.asLong();}
    private static int integer(JsonNode n,String field){var v=n.get(field);if(v==null||!v.isIntegralNumber()||!v.canConvertToInt())throw malformed();return v.asInt();}
    private static boolean bool(JsonNode n,String field){var v=n.get(field);if(v==null||!v.isBoolean())throw malformed();return v.asBoolean();}
    private static LocalDate date(JsonNode n,String field){try{return LocalDate.parse(text(n,field));}catch(java.time.DateTimeException e){throw malformed();}}
    private static BulkData.Operation operation(JsonNode n){try{return BulkData.Operation.valueOf(text(n,"operation"));}catch(IllegalArgumentException e){throw malformed();}}
    private static String parameter(HttpServletRequest r,String name,String fallback){String value=r.getParameter(name);return value==null?fallback:value;}
    private static OrgException malformed(){return new OrgException(400,"행의 필드·식별자·날짜·버전 입력 형식을 확인해 주세요.");}
    private record Context(String q,int page,UUID groupId,String codeQ,int codePage) { }
}
