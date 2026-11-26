package kr.tna.notification;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import org.springframework.stereotype.Service;

/** 로그인한 사원 본인의 알림만 읽고 바꾼다(설계 §4). */
@Service
public class NotificationService {
    private static final DateTimeFormatter SEOUL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));
    public record Row(UUID id, UUID requestId, String kind, String requesterName, String summary, String createdAt, boolean read) { }

    private final BusinessCommands business;
    private final NotificationMapper mapper;

    public NotificationService(BusinessCommands business, NotificationMapper mapper) {
        this.business = business; this.mapper = mapper;
    }

    public List<Row> list(TnaPrincipal actor) {
        return business.read(actor, () -> mapper.recent(actor.tenantId(), actor.employeeId()).stream()
                .map(n -> new Row(n.id(), n.requestId(), n.kind(), n.requesterName(),
                        NotificationText.summary(n.requestKind(), n.typeName(), n.fromDate(), n.toDate()), SEOUL.format(n.createdAt()), n.read()))
                .toList());
    }

    /** 상단 종 배지: 100건에서 멈춘다(화면은 99+). 같은 요청에서 계정 확인을 이미 마친 공통 데이터용이다. */
    public int unreadCount(UUID tenantId, UUID employeeId) { return mapper.unreadCount(tenantId, employeeId); }

    public void markRequestRead(TnaPrincipal actor, UUID requestId) {
        business.read(actor, () -> mapper.markRequestRead(actor.tenantId(), actor.employeeId(), requestId));
    }

    public void markAllRead(TnaPrincipal actor) {
        business.read(actor, () -> mapper.markAllRead(actor.tenantId(), actor.employeeId()));
    }
}
