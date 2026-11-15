package lms.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;

class ConsumerTenantContextTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void datDungTenantKhiXuLyVaXoaSauDo() {
        UUID tenant = UUID.randomUUID();
        List<Optional<UUID>> seen = new ArrayList<>();

        boolean handled = TenantAwareEventHandler.handle(tenant, "XEvent", UUID.randomUUID(),
                () -> seen.add(TenantContext.current()));

        assertThat(handled).isTrue();
        assertThat(seen).containsExactly(Optional.of(tenant));
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void thieuTenantIdThiKhongGoiXuLy() {
        List<String> calls = new ArrayList<>();

        boolean handled = TenantAwareEventHandler.handle((UUID) null, "XEvent", UUID.randomUUID(), () -> calls.add("x"));

        assertThat(handled).isFalse();
        assertThat(calls).isEmpty();
    }

    @Test
    void nhanEnvelopeVaDocTenantIdTuEnvelope() {
        UUID tenant = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), "XEvent", "a", Instant.now(), 1, null, tenant);
        List<Optional<UUID>> seen = new ArrayList<>();

        TenantAwareEventHandler.handle(envelope, () -> seen.add(TenantContext.current()));

        assertThat(seen).containsExactly(Optional.of(tenant));
    }

    @Test
    void envelopeThieuTenantIdKhongGoiXuLy() {
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), "XEvent", "a", Instant.now(), 1, null);
        List<String> calls = new ArrayList<>();

        assertThat(TenantAwareEventHandler.handle(envelope, () -> calls.add("x"))).isFalse();
        assertThat(calls).isEmpty();
    }

    @Test
    void docDuocTenantIdTuJsonVaJsonCuKhongCoTenantIdVanDocDuoc() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        String withTenant = "{\"eventId\":\"" + id + "\",\"eventType\":\"X\",\"aggregateId\":\"a\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"version\":1,\"payload\":{},\"tenantId\":\"" + tenant + "\"}";
        String legacy = "{\"eventId\":\"" + id + "\",\"eventType\":\"X\",\"aggregateId\":\"a\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"version\":1,\"payload\":{}}";

        assertThat(mapper.readValue(withTenant, EventEnvelope.class).tenantId()).isEqualTo(tenant);
        assertThat(mapper.readValue(legacy, EventEnvelope.class).tenantId()).isNull();
        assertThat(TenantAwareEventHandler.handle(mapper.readTree(withTenant), () -> { })).isTrue();
        assertThat(TenantAwareEventHandler.handle(mapper.readTree(legacy), () -> { })).isFalse();
    }
}
