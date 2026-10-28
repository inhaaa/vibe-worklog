package kr.tna.org;

import java.util.List;

public final class BulkException extends OrgException {
    private final List<BulkData.FieldError> errors;

    public BulkException(int status, String message, String clientRowId, String field) {
        super(status, message);
        errors = List.of(new BulkData.FieldError(clientRowId, field, message));
    }

    public List<BulkData.FieldError> errors() { return errors; }
}
