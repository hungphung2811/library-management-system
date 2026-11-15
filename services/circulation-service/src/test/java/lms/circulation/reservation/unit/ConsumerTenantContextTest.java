package lms.circulation.reservation.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.reservation.application.HoldCopyForNextReservationUseCase;
import lms.circulation.reservation.infrastructure.messaging.InventoryEventConsumer;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;

class ConsumerTenantContextTest {

    private final HoldCopyForNextReservationUseCase hold = mock(HoldCopyForNextReservationUseCase.class);
    private final InventoryEventConsumer consumer = new InventoryEventConsumer(new ObjectMapper(), hold);

    private static String message(UUID titleId, UUID copyId, UUID tenantId) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"CopyAvailableEvent\",\"payload\":{\"copyId\":\""
                + copyId + "\",\"titleId\":\"" + titleId + "\"}"
                + (tenantId == null ? "" : ",\"tenantId\":\"" + tenantId + "\"") + "}";
    }

    @Test
    void datDungTenantKhiXuLyVaXoaSauDo() {
        UUID tenant = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID copyId = UUID.randomUUID();
        List<Optional<UUID>> seen = new ArrayList<>();
        doAnswer(i -> seen.add(TenantContext.current()) && false).when(hold).holdIfAnyoneWaiting(titleId, copyId);

        consumer.onEvent(message(titleId, copyId, tenant));

        assertThat(seen).containsExactly(Optional.of(tenant));
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void eventThieuTenantIdKhongGoiUseCase() {
        consumer.onEvent(message(UUID.randomUUID(), UUID.randomUUID(), null));

        verifyNoInteractions(hold);
    }
}
