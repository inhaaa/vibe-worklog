package kr.tna.clock;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public final class AttendanceSettingsData {
    private AttendanceSettingsData() { }
    public record Network(UUID id,String network,String label,Instant validFrom,Instant validTo,String reason,long version) { }
    public record NetworkForm(String network,String label,boolean wideConfirmed,String reason,UUID commandId) { }
    public record CalendarVersion(UUID id,int revision,LocalDate validFrom,LocalDate validTo,String reason) { }
    public record CalendarForm(@DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,boolean reviewed,String basis,
            String exceptions,String reason,UUID commandId) { }
}
