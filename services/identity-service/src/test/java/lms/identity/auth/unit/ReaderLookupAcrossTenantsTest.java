package lms.identity.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.identity.auth.infrastructure.client.InProcessReaderLookupAdapter;
import lms.identity.tenant.application.ListActiveTenantIdsUseCase;
import lms.identity.user.application.FindReaderUserIdsByEmailUseCase;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;

/**
 * Đăng nhập ngoài tra hồ sơ độc giả theo email trước khi biết tenant: hồ sơ độc giả thuộc tenant (RLS), nên adapter tra
 * lần lượt trong ngữ cảnh của từng tenant ACTIVE, gộp không trùng lặp và trả lại ngữ cảnh rỗng.
 */
class ReaderLookupAcrossTenantsTest {

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final List<Optional<UUID>> seen = new ArrayList<>();

    private InProcessReaderLookupAdapter adapter(Map<UUID, List<UUID>> readersByTenant) {
        FindReaderUserIdsByEmailUseCase find = mock(FindReaderUserIdsByEmailUseCase.class);
        when(find.execute(anyString())).thenAnswer(inv -> {
            Optional<UUID> tenant = TenantContext.current();
            seen.add(tenant);
            return tenant.map(t -> readersByTenant.getOrDefault(t, List.of())).orElse(List.of());
        });
        ListActiveTenantIdsUseCase active = mock(ListActiveTenantIdsUseCase.class);
        when(active.execute()).thenReturn(List.of(tenantA, tenantB));
        return new InProcessReaderLookupAdapter(find, active);
    }

    @Test
    void traTrongTungTenantVaGopKhongTrungLap() {
        List<UUID> found = adapter(Map.of(tenantA, List.of(alice), tenantB, List.of(alice))).findUserIdsByEmail("a@x.vn");

        assertThat(found).containsExactly(alice);
        assertThat(seen).containsExactly(Optional.of(tenantA), Optional.of(tenantB));
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void haiNguoiKhacNhauOHaiTenantThiTraCaHai() {
        List<UUID> found = adapter(Map.of(tenantA, List.of(alice), tenantB, List.of(bob))).findUserIdsByEmail("x@x.vn");

        assertThat(found).containsExactlyInAnyOrder(alice, bob);
    }
}
