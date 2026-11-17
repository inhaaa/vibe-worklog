package kr.tna.org;

import java.util.List;
import java.util.UUID;

final class RoleMembershipData {
    private RoleMembershipData() { }

    enum Operation { GRANT, REVOKE }

    record Form(UUID commandId, String reason, List<Row> rows) { }

    record Row(UUID clientRowId, UUID employeeId, Long expectedVersion, Operation operation) { }

    record Outcome(boolean affectedSelf, List<RowResult> rows) { }

    record RowResult(String clientRowId, UUID employeeId, long version) { }

    record RowError(UUID clientRowId, String message) { }

    record Latest(UUID clientRowId, UUID employeeId, long version, boolean granted, boolean available) { }

    record Problem(String message, List<RowError> errors, List<Latest> latest) { }

    static final class Rejected extends OrgException {
        private final Problem problem;

        Rejected(int status, String message, List<RowError> errors, List<Latest> latest) {
            super(status, message);
            this.problem = new Problem(message, List.copyOf(errors), List.copyOf(latest));
        }

        Problem problem() { return problem; }
    }
}
