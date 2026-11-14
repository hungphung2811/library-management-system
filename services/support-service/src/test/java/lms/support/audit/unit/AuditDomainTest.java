package lms.support.audit.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import lms.support.audit.domain.AuditLog;
import lms.support.audit.domain.AuditLogCriteria;
import lms.support.audit.domain.InvalidAuditEventException;
import lms.support.audit.domain.InvalidAuditQueryException;
import org.junit.jupiter.api.Test;

class AuditDomainTest {

    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void captureTaoBanGhiHopLe() {
        UUID eventId = UUID.randomUUID();

        AuditLog log = AuditLog.capture(eventId, "BookBorrowedEvent", "agg-1", now, "{\"a\":1}", now.plusSeconds(5));

        assertThat(log.id()).isNotNull();
        assertThat(log.eventId()).isEqualTo(eventId);
        assertThat(log.recordedAt()).isEqualTo(now.plusSeconds(5));
    }

    @Test
    void thieuTruongBatBuocBiTuChoi() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> AuditLog.capture(null, "T", "a", now, "{}", now)).isInstanceOf(InvalidAuditEventException.class);
        assertThatThrownBy(() -> AuditLog.capture(id, " ", "a", now, "{}", now)).isInstanceOf(InvalidAuditEventException.class);
        assertThatThrownBy(() -> AuditLog.capture(id, "T", "", now, "{}", now)).isInstanceOf(InvalidAuditEventException.class);
        assertThatThrownBy(() -> AuditLog.capture(id, "T", "a", null, "{}", now)).isInstanceOf(InvalidAuditEventException.class);
        assertThatThrownBy(() -> AuditLog.capture(id, "T", "a", now, null, now)).isInstanceOf(InvalidAuditEventException.class);
    }

    @Test
    void criteriaChuanHoaBoLocTrongVaGioiHanTrang() {
        AuditLogCriteria criteria = new AuditLogCriteria("  ", " BookReturnedEvent ", 2, 50);

        assertThat(criteria.aggregateId()).isNull();
        assertThat(criteria.eventType()).isEqualTo("BookReturnedEvent");
        assertThatThrownBy(() -> new AuditLogCriteria(null, null, -1, 20)).isInstanceOf(InvalidAuditQueryException.class);
        assertThatThrownBy(() -> new AuditLogCriteria(null, null, 0, 0)).isInstanceOf(InvalidAuditQueryException.class);
        assertThatThrownBy(() -> new AuditLogCriteria(null, null, 0, 101)).isInstanceOf(InvalidAuditQueryException.class);
    }
}
