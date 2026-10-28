package kr.tna.worktime;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuditSummaryBatchTest {
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
