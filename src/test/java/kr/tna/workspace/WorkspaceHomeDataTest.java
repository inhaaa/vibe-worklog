package kr.tna.workspace;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import kr.tna.clock.ClockData.Confirmation;
import kr.tna.clock.ClockData.DaySnapshot;
import kr.tna.clock.ClockData.Event;
import kr.tna.org.EmployeeOverview;
import kr.tna.request.RequestData.RequestView;
import kr.tna.schedule.ScheduleData;
import kr.tna.workspace.WorkspaceData.DayView;
import kr.tna.workspace.WorkspaceData.HomeView;
import kr.tna.workspace.WorkspaceData.RequestItem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceHomeDataTest {
    @Test
    void exposesOnlyDisplayDataForHome() {
        var date = LocalDate.of(2026, 11, 6);
        var now = Instant.parse("2026-11-06T00:00:00Z");
        var employee = UUID.randomUUID();
        var event = new Event(UUID.randomUUID(), "IN", now, date);
        var confirmation = new Confirmation(UUID.randomUUID(), 1L, 1L, true, true,
                "source-fingerprint", List.of(), now);
        var day = new DaySnapshot(employee, date, List.of(event), null, null, null, "WORKDAY",
                1L, 1L, confirmation, "source-fingerprint", List.of(event));
        var requestId = UUID.randomUUID();
        var request = new RequestView(requestId, employee, "가상 직원", UUID.randomUUID(), "LEAVE_DAY", "APPROVED",
                UUID.randomUUID(), "가상 승인자", null, null, now, "민감 신청 사유", null, null, null,
                date, date, false, 1L, "EFFECTIVE", 1, null, null);
        var item = new RequestItem(request, "종일 연차", "승인 유효", "green", true);
        var today = new DayView(day, List.of(item), true, true);
        var view = new HomeView(new EmployeeOverview("가상 직원", "가상 회사"), today, List.of(today), 0L, 1L, List.of(item));

        var json = JsonMapper.builder().build().valueToTree(WorkspaceHomeData.from(view));
        assertEquals(now.toString(), json.at("/today/inRecordedAt").asString());
        assertTrue(json.at("/today/outRecordedAt").isNull());
        assertEquals("현재 반영 출근", json.at("/today/timeline/0/title").asString());
        assertEquals(1, json.at("/today/snapshot").size());
        assertTrue(json.at("/week/0/requests/0/effectiveLeave").asBoolean());
        assertTrue(json.at("/week/0/timeline").isMissingNode());
        assertEquals(requestId.toString(), json.at("/recentRequests/0/request/id").asString());
        assertEquals(3, json.at("/recentRequests/0/request").size());
        assertFalse(json.toString().contains("source-fingerprint"));
        assertFalse(json.toString().contains("민감 신청 사유"));
        assertFalse(json.toString().contains(employee.toString()));
    }

    @Test
    void scheduleTextSeparatesStartAndEndWithATilde() {
        var date = LocalDate.of(2026, 11, 6);
        var schedule = new ScheduleData.Day(5, true, LocalTime.of(9, 30), LocalTime.of(15, 30), List.of());
        var day = new DaySnapshot(UUID.randomUUID(), date, List.of(), schedule, null, null, "WORKDAY",
                0L, 0L, null, "fixture", List.of());
        assertEquals("09:30 ~ 15:30", new DayView(day, List.of(), true, true).scheduleText());
    }
}
