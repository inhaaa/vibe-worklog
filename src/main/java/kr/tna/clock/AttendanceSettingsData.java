package kr.tna.clock;

import java.time.Instant;
import java.util.UUID;

public final class AttendanceSettingsData {
    private AttendanceSettingsData() { }
    public record Network(UUID id,String network,String label,Instant validFrom,Instant validTo,String reason,long version) { }
    public record NetworkForm(String network,String label,boolean wideConfirmed,String reason,UUID commandId) { }
}
