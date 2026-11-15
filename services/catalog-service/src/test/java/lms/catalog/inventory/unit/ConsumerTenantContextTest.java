package lms.catalog.inventory.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.catalog.inventory.application.HoldCopyUseCase;
import lms.catalog.inventory.application.ReleaseCopyUseCase;
import lms.catalog.inventory.infrastructure.messaging.ReservationEventConsumer;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;

class ConsumerTenantContextTest {

    private final HoldCopyUseCase hold = mock(HoldCopyUseCase.class);
    private final ReleaseCopyUseCase release = mock(ReleaseCopyUseCase.class);
    private final ReservationEventConsumer consumer = new ReservationEventConsumer(new ObjectMapper(), hold, release);

    private static String message(String type, UUID copyId, UUID tenantId) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + type + "\",\"payload\":{\"copyId\":\""
                + copyId + "\"}" + (tenantId == null ? "" : ",\"tenantId\":\"" + tenantId + "\"") + "}";
    }

    @Test
    void datDungTenantKhiXuLyVaXoaSauDo() {
        UUID tenant = UUID.randomUUID();
        UUID copyId = UUID.randomUUID();
        List<Optional<UUID>> seen = new ArrayList<>();
        doAnswer(i -> seen.add(TenantContext.current())).when(hold).execute(copyId);

        consumer.onEvent(message("ReservationReadyEvent", copyId, tenant));

        verify(hold).execute(copyId);
        assertThat(seen).containsExactly(Optional.of(tenant));
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void releaseCungChayTrongTenant() {
        UUID tenant = UUID.randomUUID();
        UUID copyId = UUID.randomUUID();
        List<Optional<UUID>> seen = new ArrayList<>();
        doAnswer(i -> seen.add(TenantContext.current())).when(release).execute(copyId);

        consumer.onEvent(message("CopyReleasedEvent", copyId, tenant));

        assertThat(seen).containsExactly(Optional.of(tenant));
    }

    @Test
    void eventThieuTenantIdKhongGoiUseCase() {
        consumer.onEvent(message("ReservationReadyEvent", UUID.randomUUID(), null));

        verifyNoInteractions(hold, release);
    }

    @Test
    void messageHongKhongGoiUseCase() {
        consumer.onEvent("khong-phai-json");

        verifyNoInteractions(hold, release);
        verify(hold, org.mockito.Mockito.never()).execute(any());
    }
}
