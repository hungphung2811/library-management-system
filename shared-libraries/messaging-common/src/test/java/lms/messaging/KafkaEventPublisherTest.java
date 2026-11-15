package lms.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import lms.common.kernel.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaEventPublisherTest {

    record SampleEvent(UUID eventId, String name, Instant occurredAt) implements DomainEvent {
        @Override
        public String aggregateId() {
            return "agg-1";
        }
    }

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final KafkaEventPublisher publisher = new KafkaEventPublisher(template, mapper, "topic.x");
    private final SampleEvent event = new SampleEvent(UUID.randomUUID(), "n", Instant.parse("2026-01-01T00:00:00Z"));

    @Test
    void guiEnvelopeVoiKeyLaAggregateId() {
        when(template.send(eq("topic.x"), eq("agg-1"), any())).thenReturn(new CompletableFuture<SendResult<String, String>>());

        publisher.publish(event);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq("topic.x"), eq("agg-1"), json.capture());
        assertThat(json.getValue()).contains("\"eventType\":\"SampleEvent\"", "\"aggregateId\":\"agg-1\"", "\"version\":1");
    }

    @Test
    void envelopeMangTenantIdCuaTenantDangChay() {
        when(template.send(eq("topic.x"), eq("agg-1"), any())).thenReturn(new CompletableFuture<SendResult<String, String>>());
        UUID tenant = UUID.randomUUID();

        lms.tenant.TenantContext.runAs(tenant, () -> publisher.publish(event));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq("topic.x"), eq("agg-1"), json.capture());
        assertThat(json.getValue()).contains("\"tenantId\":\"" + tenant + "\"");
    }

    @Test
    void khongCoTenantDangChayThiTenantIdLaNull() {
        when(template.send(eq("topic.x"), eq("agg-1"), any())).thenReturn(new CompletableFuture<SendResult<String, String>>());

        publisher.publish(event);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq("topic.x"), eq("agg-1"), json.capture());
        assertThat(json.getValue()).contains("\"tenantId\":null");
    }

    @Test
    void brokerKhongVoiTuocDuocThiKhongLamHongRequest() {
        when(template.send(any(String.class), any(String.class), any(String.class)))
                .thenThrow(new TimeoutException("Topic not present in metadata after 300 ms."));

        assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();
    }

    @Test
    void loiGuiBatDongBoChiDuocLog() {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("boom"));
        when(template.send(any(String.class), any(String.class), any(String.class))).thenReturn(failed);

        assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();
    }

    @Test
    void loiSerializeLaLoiLapTrinhNenVanNem() {
        ObjectMapper noJavaTime = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        KafkaEventPublisher strict = new KafkaEventPublisher(template, noJavaTime, "topic.x");

        assertThatThrownBy(() -> strict.publish(event)).isInstanceOf(IllegalStateException.class);
    }
}
