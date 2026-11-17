package lms.support.audit.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.UUID;
import lms.support.audit.application.IngestAuditEventCommand;
import lms.support.audit.application.IngestAuditEventUseCase;
import lms.support.audit.infrastructure.messaging.AuditEventConsumer;
import lms.support.audit.infrastructure.messaging.AuditEventParser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditEventParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AuditEventParser parser = new AuditEventParser(objectMapper);
    private final UUID eventId = UUID.randomUUID();

    private final UUID tenantId = UUID.randomUUID();

    private String envelope(String occurredAt) {
        return envelope(occurredAt, tenantId);
    }

    private String envelope(String occurredAt, UUID tenant) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"BookBorrowedEvent\",\"aggregateId\":\"b-1\","
                + "\"occurredAt\":" + occurredAt + ",\"version\":1,\"payload\":{\"userId\":\"u-1\",\"n\":2}"
                + (tenant == null ? "" : ",\"tenantId\":\"" + tenant + "\"") + "}";
    }

    @Test
    void parseMangTenantIdVaConsumerGoiUseCaseTrongTenant() {
        assertThat(parser.parse("t", envelope("\"2026-01-01T00:00:00Z\"")).orElseThrow().tenantId()).isEqualTo(tenantId);

        IngestAuditEventUseCase ingest = mock(IngestAuditEventUseCase.class);
        java.util.List<java.util.Optional<UUID>> seen = new java.util.ArrayList<>();
        when(ingest.execute(any())).thenAnswer(i -> seen.add(lms.tenant.TenantContext.current()));
        new AuditEventConsumer(parser, ingest).onEvent(envelope("\"2026-01-01T00:00:00Z\""), "t");

        assertThat(seen).containsExactly(java.util.Optional.of(tenantId));
        assertThat(lms.tenant.TenantContext.current()).isEmpty();
    }

    @Test
    void eventThieuTenantIdKhongGoiUseCase() {
        IngestAuditEventUseCase ingest = mock(IngestAuditEventUseCase.class);

        new AuditEventConsumer(parser, ingest).onEvent(envelope("\"2026-01-01T00:00:00Z\"", null), "t");

        verify(ingest, never()).execute(any());
    }

    @Test
    void parseEnvelopeHopLeVoiThoiGianIso() {
        IngestAuditEventCommand command = parser.parse("borrowing.events", envelope("\"2026-01-01T00:00:00Z\"")).orElseThrow();

        assertThat(command.eventId()).isEqualTo(eventId);
        assertThat(command.eventType()).isEqualTo("BookBorrowedEvent");
        assertThat(command.aggregateId()).isEqualTo("b-1");
        assertThat(command.occurredAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(command.topic()).isEqualTo("borrowing.events");
        assertThat(command.payload()).contains("\"userId\":\"u-1\"");
        assertThat(command.rawEnvelope()).contains(eventId.toString());
    }

    @Test
    void parseThoiGianDangEpochSecond() {
        IngestAuditEventCommand command = parser.parse("auth.events", envelope("1767225600.5")).orElseThrow();

        assertThat(command.occurredAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00.500Z"));
    }

    @Test
    void messageHongHoacThieuTruongThiBoQuaKhongNemLoi() {
        assertThat(parser.parse("t", null)).isEmpty();
        assertThat(parser.parse("t", "  ")).isEmpty();
        assertThat(parser.parse("t", "không phải json")).isEmpty();
        assertThat(parser.parse("t", "{\"eventType\":\"X\"}")).isEmpty();
        assertThat(parser.parse("t", "{\"eventId\":\"không-phải-uuid\",\"eventType\":\"X\",\"aggregateId\":\"a\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\"}")).isEmpty();
    }

    @Test
    void consumerGoiUseCaseVoiMessageHopLeVaBoQuaMessageHong() {
        IngestAuditEventUseCase ingest = mock(IngestAuditEventUseCase.class);
        when(ingest.execute(any())).thenReturn(true);
        AuditEventConsumer consumer = new AuditEventConsumer(parser, ingest);

        consumer.onEvent("rác", "access-control.events");
        verify(ingest, never()).execute(any());

        consumer.onEvent(envelope("\"2026-01-01T00:00:00Z\""), "access-control.events");
        ArgumentCaptor<IngestAuditEventCommand> captured = ArgumentCaptor.forClass(IngestAuditEventCommand.class);
        verify(ingest).execute(captured.capture());
        assertThat(captured.getValue().topic()).isEqualTo("access-control.events");
    }

    // ===== sales.events (B1.7) =====

    private static String salesEnvelope(UUID eventId, String tenantField) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"OrderCompleted\",\"aggregateId\":\"o-1\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"version\":1,\"payload\":{\"eventId\":\"" + eventId
                + "\",\"eventType\":\"OrderCompleted\",\"aggregateId\":\"o-1\",\"action\":\"ORDER_CREATED\","
                + "\"aggregateType\":\"ORDER\",\"actorId\":\"" + UUID.randomUUID() + "\",\"data\":{\"total\":150000}}"
                + tenantField + "}";
    }

    @Test
    void salesEventDuocParseThanhCommandVoiPayloadGiuActorVaHanhDong() {
        IngestAuditEventCommand command = parser.parse("sales.events",
                salesEnvelope(eventId, ",\"tenantId\":\"" + tenantId + "\"")).orElseThrow();

        assertThat(command.topic()).isEqualTo("sales.events");
        assertThat(command.eventType()).isEqualTo("OrderCompleted");
        assertThat(command.aggregateId()).isEqualTo("o-1");
        assertThat(command.tenantId()).isEqualTo(tenantId);
        assertThat(command.payload()).contains("\"action\":\"ORDER_CREATED\"", "\"actorId\"", "\"total\":150000");
    }

    @Test
    void salesEventThieuTenantIdBiBoQuaKhongGoiUseCase() {
        IngestAuditEventUseCase ingest = mock(IngestAuditEventUseCase.class);

        new AuditEventConsumer(parser, ingest).onEvent(salesEnvelope(eventId, ""), "sales.events");

        verify(ingest, never()).execute(any());
    }

    @Test
    void salesEventGoiUseCaseTrongTenantCuaEvent() {
        IngestAuditEventUseCase ingest = mock(IngestAuditEventUseCase.class);
        java.util.List<java.util.Optional<UUID>> seen = new java.util.ArrayList<>();
        when(ingest.execute(any())).thenAnswer(i -> seen.add(lms.tenant.TenantContext.current()));

        new AuditEventConsumer(parser, ingest).onEvent(salesEnvelope(eventId, ",\"tenantId\":\"" + tenantId + "\""), "sales.events");

        assertThat(seen).containsExactly(java.util.Optional.of(tenantId));
    }

    @Test
    void consumerLangNgheTopicSalesEvents() throws Exception {
        var listener = AuditEventConsumer.class.getMethod("onEvent", String.class, String.class)
                .getAnnotation(org.springframework.kafka.annotation.KafkaListener.class);

        assertThat(listener.topics()).contains("${app.kafka.topics.sales-events}");
    }
}
