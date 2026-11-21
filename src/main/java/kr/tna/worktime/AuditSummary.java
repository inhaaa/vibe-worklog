package kr.tna.worktime;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.web.SeoulTimeDisplay;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Audit states are stored evidence, not an unrestricted JSON view of account or request data. */
final class AuditSummary {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private static final SeoulTimeDisplay TIME = new SeoulTimeDisplay();
    private static final Set<String> NUMBERS = names("version employeeVersion organizationVersion authVersion revision resultRevision inputRevision reviewRevision approverAssignmentVersion quantityDays balanceDays availableDays pendingDays futureApprovedDays signedQuantityDays dayCount returnedCount confirmedInputRevision baseInputRevision weekday sequence resultCount assignmentVersion organizationAssignmentVersion organizationHeadAssignmentVersion entitlementYear fiscalStartMonth year ruleVersion postedCount");
    private static final Set<String> LEAVE_DAYS = names("quantityDays balanceDays availableDays pendingDays futureApprovedDays signedQuantityDays grantDays carryLimitDays");
    private static final Set<String> FLAGS = names("active credentialSet eligible exceptional workday mondayWeekReviewed generalRuleReviewed presenceAsWork breaksMatch breaksConfirmed recalculationRequired current enabled systemOwned");
    private static final Set<String> IDS = names("id assignmentId employeeId accountId organizationId parentId approverEmployeeId scheduleVersionId scheduleAssignmentId calendarVersionId templateId versionId requestId originalRequestId predecessorRequestId approverAssignmentId currentApproverId authorAccountId targetEmployeeId leaveBucketId bucketId runId resultId requestEffectId reversesEntryId requestedBy createdBy updatedBy commandId requestTraceId previousApproverId newApproverId batchId clientRowId headEmployeeId routeId routeVersionId routeSnapshotId organizationAssignmentId organizationHeadAssignmentId resolvedApproverEmployeeId attendanceTypeId attendanceTypeVersionId groupId");
    private static final Set<String> DATES = names("validFrom validTo from to fromDate toDate workDate leaveDate baseDate effectiveOn joinedOn leftOn usableFrom usableTo expandedFrom expandedTo createdAt updatedAt occurredAt receivedAt submittedAt confirmedAt calculatedAt startedAt finishedAt supersededAt resolvedAt effectiveFrom periodStart periodEnd");
    private static final Set<String> LOCAL_TIMES = names("startTime endTime");
    private static final Set<String> INSTANTS = names("startAt endAt requestedInAt requestedOutAt");
    private static final Set<String> OMITTED = names("name displayName employeeNo code templateName organizationName employeeName approverName label companyName companyCode targetEmployeeName");
    private static final Set<String> OBJECTS = names("previous current template version account assignment employee schedule confirmation");
    private static final Set<String> OBJECT_ARRAYS = names("days breaks rows");
    private static final Set<String> ROLES = names("EMPLOYEE APPROVER ATTENDANCE_ADMIN");
    private static final Map<String,Set<String>> ENUMS = Map.ofEntries(
            Map.entry("status", names("PENDING APPROVED REJECTED WITHDRAWN RUNNING COMPLETED FAILED CALCULATED REVIEW_REQUIRED NO_SCHEDULED_WORK")),
            Map.entry("effectStatus", names("NONE EFFECTIVE CANCELED SUPERSEDED")),
            Map.entry("kind", names("LEAVE_DAY ATTENDANCE_CORRECTION CANCEL_LEAVE")),
            Map.entry("dayKind", names("WORKDAY HOLIDAY UNCONFIRMED")),
            Map.entry("entryKind", names("OPENING ADJUSTMENT USE RESTORE")),
            Map.entry("purpose", names("REVIEW INVITE RESET")),
            Map.entry("ruleVersion", names("CALC-1")),
            Map.entry("engineVersion", names("CALC-1")),
            Map.entry("approverKind", names("DIRECT_EMPLOYEE ORGANIZATION_HEAD")),
            Map.entry("scopeKind", names("EMPLOYEE ORGANIZATION")),
            Map.entry("systemKind", names("ANNUAL_LEAVE ATTENDANCE_CORRECTION")),
            Map.entry("unit", names("DAY HOUR NONE")),
            Map.entry("payKind", names("PAID UNPAID UNSPECIFIED")),
            Map.entry("deductionRule", names("LEAVE_DAY NONE")),
            Map.entry("recognitionRule", names("NOT_SUPPORTED")),
            Map.entry("classificationStatus", names("CONFIRMED UNCLASSIFIED")));

    private AuditSummary() { }

    static Map<String,String> project(String value) {
        var result = new LinkedHashMap<String,String>();
        try { collect(JSON.readTree(value), "", result, 0); }
        catch (RuntimeException invalid) { return Map.of(); }
        return Collections.unmodifiableMap(result);
    }

    private static void collect(JsonNode object, String prefix, Map<String,String> result, int depth) {
        if (object == null || !object.isObject() || depth > 6 || result.size() >= 200) return;
        for (String key : object.propertyNames()) {
            if (result.size() >= 200) return;
            JsonNode value = object.get(key);
            if (value == null) continue;
            String path = prefix + key;
            if (value.isNull()) {
                if (IDS.contains(key) || DATES.contains(key) || INSTANTS.contains(key)) result.put(path, "없음");
                continue;
            }
            if (value.isObject() && OBJECTS.contains(key)) {
                collect(value, path + ".", result, depth + 1);
            } else if (value.isArray() && OBJECT_ARRAYS.contains(key)) {
                int size = Math.min(value.size(), 50);
                for (int index = 0; index < size; index++)
                    collect(value.get(index), path + "[" + index + "].", result, depth + 1);
            } else if (key.equals("roles") && value.isArray() && value.size() <= ROLES.size()) {
                var roles = new java.util.ArrayList<String>();
                boolean valid = true;
                for (JsonNode role : value) {
                    if (!role.isString() || !ROLES.contains(role.asString())) { valid = false; break; }
                    roles.add(role.asString());
                }
                if (valid) result.put(path, String.join(", ", roles));
            } else if (NUMBERS.contains(key) && value.isIntegralNumber() && value.canConvertToLong()) {
                result.put(path, Long.toString(value.asLong()));
            } else if (LEAVE_DAYS.contains(key) && value.isNumber()) {
                var days = value.decimalValue().stripTrailingZeros();
                if (days.scale() <= 1 && days.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0
                        && days.remainder(BigDecimal.valueOf(5, 1)).signum() == 0) result.put(path, days.toPlainString());
            } else if (FLAGS.contains(key) && value.isBoolean()) {
                result.put(path, Boolean.toString(value.asBoolean()));
            } else if (OMITTED.contains(key)) {
                result.put(path, "값 생략");
            } else if (value.isString()) {
                String safe = typedText(key, value.asString());
                if (safe != null) result.put(path, safe);
            }
        }
    }

    private static String typedText(String key, String value) {
        try {
            if (IDS.contains(key)) {
                String id = UUID.fromString(value).toString();
                return id.equalsIgnoreCase(value) ? id : null;
            }
            if (DATES.contains(key)) {
                try { return TIME.date(LocalDate.parse(value)); }
                catch (java.time.DateTimeException notDate) { return TIME.instant(Instant.parse(value)); }
            }
            if (LOCAL_TIMES.contains(key)) return TIME.time(LocalTime.parse(value));
            if (INSTANTS.contains(key)) return TIME.instant(Instant.parse(value));
            if (ENUMS.containsKey(key) && ENUMS.get(key).contains(value)) return value;
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            return null;
        }
        return null;
    }

    private static Set<String> names(String value) { return Set.of(value.split(" ")); }
}
