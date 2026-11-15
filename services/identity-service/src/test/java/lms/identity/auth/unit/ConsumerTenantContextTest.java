package lms.identity.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.auth.application.RevokeUserSessionsUseCase;
import lms.identity.auth.infrastructure.messaging.AccessEventConsumer;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;

class ConsumerTenantContextTest {

    private final RevokeUserSessionsUseCase revoke = mock(RevokeUserSessionsUseCase.class);
    private final AccessEventConsumer consumer = new AccessEventConsumer(new ObjectMapper(), revoke);

    private static String message(UUID userId, UUID tenantId) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"RoleRevokedEvent\",\"payload\":{\"userId\":\""
                + userId + "\"}" + (tenantId == null ? "" : ",\"tenantId\":\"" + tenantId + "\"") + "}";
    }

    @Test
    void datDungTenantKhiXuLyVaXoaSauDo() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        List<Optional<UUID>> seen = new ArrayList<>();
        doAnswer(i -> seen.add(TenantContext.current())).when(revoke).execute(userId);

        consumer.onAccessEvent(message(userId, tenant));

        assertThat(seen).containsExactly(Optional.of(tenant));
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void eventThieuTenantIdKhongGoiUseCase() throws Exception {
        consumer.onAccessEvent(message(UUID.randomUUID(), null));

        verifyNoInteractions(revoke);
    }
}
