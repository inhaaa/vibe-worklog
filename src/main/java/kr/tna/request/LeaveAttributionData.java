package kr.tna.request;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LeaveAttributionData {
    private LeaveAttributionData() { }
    public record Attribution(UUID id,UUID bucketId,long revision,UUID attendanceTypeId,String classificationStatus,
            Integer entitlementYear,String basis,String reason,UUID createdBy,String createdByName,Instant createdAt) { }
    public record Form(Long expectedRevision,String classificationStatus,Integer entitlementYear,Boolean singleYearConfirmed,
            String basis,String reason,UUID commandId) { }
    public record View(RequestData.Bucket bucket,List<Attribution> history,Form form) { }
}
