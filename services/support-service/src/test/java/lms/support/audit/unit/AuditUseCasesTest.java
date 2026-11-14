package lms.support.audit.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lms.support.audit.application.ArchiveRawEventCommand;
import lms.support.audit.application.ArchiveRawEventUseCase;
import lms.support.audit.application.AuditLogResponse;
import lms.support.audit.application.IngestAuditEventCommand;
import lms.support.audit.application.IngestAuditEventUseCase;
import lms.support.audit.application.PageResponse;
import lms.support.audit.application.QueryAuditLogsUseCase;
import lms.support.audit.application.RawEventArchivePort;
import lms.support.audit.application.RecordAuditEventCommand;
import lms.support.audit.application.RecordAuditEventUseCase;
import lms.support.audit.application.SaveAuditLogPort;
import lms.support.audit.domain.AuditLog;
import lms.support.audit.domain.AuditLogCriteria;
import lms.support.audit.domain.AuditLogPage;
import lms.support.audit.domain.AuditLogRepository;
import lms.support.audit.domain.InvalidAuditEventException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditUseCasesTest {

    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final SaveAuditLogPort savePort = mock(SaveAuditLogPort.class);
    private final RawEventArchivePort archivePort = mock(RawEventArchivePort.class);
    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final RecordAuditEventUseCase record = new RecordAuditEventUseCase(savePort, clock);
    private final ArchiveRawEventUseCase archive = new ArchiveRawEventUseCase(archivePort);
    private final IngestAuditEventUseCase ingest = new IngestAuditEventUseCase(record, archive, clock);

    private RecordAuditEventCommand recordCommand(UUID eventId) {
        return new RecordAuditEventCommand(eventId, "BookBorrowedEvent", "agg-1", now.minusSeconds(10), "{}");
    }

    private IngestAuditEventCommand ingestCommand(UUID eventId) {
        return new IngestAuditEventCommand(eventId, "borrowing.events", "BookBorrowedEvent", "agg-1",
                now.minusSeconds(10), "{}", "{\"raw\":true}");
    }

    @Test
    void recordLuuBanGhiVoiThoiGianTuClock() {
        when(savePort.saveIfAbsent(any())).thenReturn(true);
        UUID eventId = UUID.randomUUID();

        boolean created = record.execute(recordCommand(eventId));

        assertThat(created).isTrue();
        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(savePort).saveIfAbsent(saved.capture());
        assertThat(saved.getValue().eventId()).isEqualTo(eventId);
        assertThat(saved.getValue().recordedAt()).isEqualTo(now);
    }

    @Test
    void recordIdempotentKhiEventIdDaTonTai() {
        when(savePort.saveIfAbsent(any())).thenReturn(false);

        assertThat(record.execute(recordCommand(UUID.randomUUID()))).isFalse();
    }

    @Test
    void recordTuChoiCommandKhongHopLeVaKhongChamVaoPort() {
        assertThatThrownBy(() -> record.execute(new RecordAuditEventCommand(null, "T", "a", now, "{}")))
                .isInstanceOf(InvalidAuditEventException.class);
        verify(savePort, never()).saveIfAbsent(any());
    }

    @Test
    void ingestGhiPostgresTruocRoiMoiLuuBanThoVaoMongo() {
        when(savePort.saveIfAbsent(any())).thenReturn(true);

        assertThat(ingest.execute(ingestCommand(UUID.randomUUID()))).isTrue();

        var order = inOrder(savePort, archivePort);
        order.verify(savePort).saveIfAbsent(any());
        order.verify(archivePort).archive(any(ArchiveRawEventCommand.class));
    }

    @Test
    void loiMongoChiLogKhongLamMatAuditLog() {
        when(savePort.saveIfAbsent(any())).thenReturn(true);
        doThrow(new IllegalStateException("mongo down")).when(archivePort).archive(any());

        boolean recorded = ingest.execute(ingestCommand(UUID.randomUUID()));

        assertThat(recorded).isTrue();
        verify(savePort).saveIfAbsent(any());
    }

    @Test
    void loiPostgresThiKhongArchiveVaNemRaDeKafkaGiaoLai() {
        when(savePort.saveIfAbsent(any())).thenThrow(new IllegalStateException("postgres down"));

        assertThatThrownBy(() -> ingest.execute(ingestCommand(UUID.randomUUID()))).hasMessage("postgres down");
        verify(archivePort, never()).archive(any());
    }

    @Test
    void ingestTrungEventVanArchiveDeBuBanThoBiThieu() {
        when(savePort.saveIfAbsent(any())).thenReturn(false);

        assertThat(ingest.execute(ingestCommand(UUID.randomUUID()))).isFalse();
        verify(archivePort).archive(any());
    }

    @Test
    void queryChuyenKetQuaSangResponseVaGiuPhanTrang() {
        AuditLog log = AuditLog.capture(UUID.randomUUID(), "BookReturnedEvent", "agg-2", now, "{}", now);
        AuditLogCriteria criteria = new AuditLogCriteria("agg-2", null, 1, 5);
        when(repository.search(criteria)).thenReturn(new AuditLogPage(List.of(log), 11, 1, 5));

        PageResponse<AuditLogResponse> response = new QueryAuditLogsUseCase(repository).execute(criteria);

        assertThat(response.total()).isEqualTo(11);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.items()).singleElement().extracting(AuditLogResponse::eventType).isEqualTo("BookReturnedEvent");
    }
}
