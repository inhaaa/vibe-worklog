package kr.tna.worktime;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuditSummaryBatchTest {
    @Test
    void showsFractionalLeaveBalancesInTheAuditDetail() {
        var summary = AuditSummary.project("""
                {"balanceDays":7.5,"availableDays":7.0,"quantityDays":0.5,"signedQuantityDays":-0.5,"version":1.25}
                """);
        assertEquals("7.5", summary.get("balanceDays"));
        assertEquals("7", summary.get("availableDays"));
        assertEquals("0.5", summary.get("quantityDays"));
        assertEquals("-0.5", summary.get("signedQuantityDays"));
        assertFalse(summary.containsKey("version"));
    }
    @Test
    void attributionSummaryExposesClassificationAndYearWithoutFreeText() {
        var summary = AuditSummary.project("""
                {"classificationStatus":"CONFIRMED","entitlementYear":2026,"revision":2,
                 "basis":"PRIVATE_BASIS","reason":"PRIVATE_REASON"}
                """);
        assertEquals("CONFIRMED", summary.get("classificationStatus"));
        assertEquals("2026", summary.get("entitlementYear"));
        assertEquals("2", summary.get("revision"));
        assertFalse(summary.toString().contains("PRIVATE_"));
        assertTrue(AuditSummary.project("{\"classificationStatus\":\"UNTRUSTED\"}").isEmpty());
    }
    @Test
    void codePolicySummaryIncludesOnlyTypedPolicyMetadata() {
        UUID type = UUID.randomUUID(), version = UUID.randomUUID(), group = UUID.randomUUID();
        var summary = AuditSummary.project("""
                {"attendanceTypeId":"%s","attendanceTypeVersionId":"%s","groupId":"%s",
                 "effectiveFrom":"2026-10-28","enabled":false,"systemOwned":true,
                 "systemKind":"ANNUAL_LEAVE","unit":"DAY","payKind":"UNSPECIFIED",
                 "deductionRule":"LEAVE_DAY","recognitionRule":"NOT_SUPPORTED",
                 "basis":"NEVER_EXPOSE","description":"NEVER_EXPOSE"}
                """.formatted(type, version, group));
        assertEquals(type.toString(), summary.get("attendanceTypeId"));
        assertEquals(version.toString(), summary.get("attendanceTypeVersionId"));
        assertEquals(group.toString(), summary.get("groupId"));
        assertEquals("2026-10-28", summary.get("effectiveFrom"));
        assertEquals("false", summary.get("enabled"));
        assertEquals("true", summary.get("systemOwned"));
        assertEquals("ANNUAL_LEAVE", summary.get("systemKind"));
        assertEquals("DAY", summary.get("unit"));
        assertEquals("UNSPECIFIED", summary.get("payKind"));
        assertFalse(summary.toString().contains("NEVER_EXPOSE"));
        assertTrue(AuditSummary.project("{\"systemKind\":\"UNTRUSTED\",\"unit\":\"SCRIPT\"}").isEmpty());
    }
    @Test
    void approvalSummaryKeepsTypedReferencesWithoutExposingPrivateText() {
        UUID route = UUID.randomUUID(), snapshot = UUID.randomUUID();
        var summary = AuditSummary.project("""
                {"routeVersionId":"%s","routeSnapshotId":"%s","assignmentVersion":2,
                 "approverKind":"ORGANIZATION_HEAD","scopeKind":"EMPLOYEE",
                 "employeeName":"개인 이름","reason":"DO_NOT_EXPOSE",
                 "rawSnapshot":"DO_NOT_EXPOSE"}
                """.formatted(route, snapshot));
        assertEquals(route.toString(), summary.get("routeVersionId"));
        assertEquals(snapshot.toString(), summary.get("routeSnapshotId"));
        assertEquals("2", summary.get("assignmentVersion"));
        assertEquals("ORGANIZATION_HEAD", summary.get("approverKind"));
        assertEquals("EMPLOYEE", summary.get("scopeKind"));
        assertEquals("값 생략", summary.get("employeeName"));
        assertFalse(summary.toString().contains("DO_NOT_EXPOSE"));
        assertTrue(AuditSummary.project("{\"routeSnapshotId\":\"invalid\",\"approverKind\":\"UNTRUSTED\"}").isEmpty());
    }

    @Test
    void headSummaryKeepsAggregateVersionWithoutFreeText() {
        UUID head = UUID.randomUUID();
        var summary = AuditSummary.project("{\"organizationVersion\":3,\"headEmployeeId\":\"" + head
                + "\",\"privateReason\":\"DO_NOT_EXPOSE\"}");
        assertEquals("3", summary.get("organizationVersion"));
        assertEquals(head.toString(), summary.get("headEmployeeId"));
        assertEquals(2, summary.size());
    }

    @Test
    void batchSummaryAcceptsOnlyTypedIdsVersionsAndLimitedRows() {
        UUID batch = UUID.randomUUID(), row = UUID.randomUUID(), id = UUID.randomUUID();
        var summary = AuditSummary.project("""
                {"batchId":"%s","rows":[{"clientRowId":"%s","id":"%s","version":2,
                 "email":"private@example.test","password":"DO_NOT_EXPOSE"}],"raw":"DO_NOT_EXPOSE"}
                """.formatted(batch, row, id));
        assertEquals(batch.toString(), summary.get("batchId"));
        assertEquals(row.toString(), summary.get("rows[0].clientRowId"));
        assertEquals(id.toString(), summary.get("rows[0].id"));
        assertEquals("2", summary.get("rows[0].version"));
        assertEquals(4, summary.size());
        assertFalse(summary.toString().contains("DO_NOT_EXPOSE"));
        assertTrue(AuditSummary.project("{\"batchId\":\"invalid\",\"rows\":[{\"clientRowId\":\"invalid\"}]}").isEmpty());
    }
}
