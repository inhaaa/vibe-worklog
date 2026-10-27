package kr.tna.worktime;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuditSummaryBatchTest {
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
