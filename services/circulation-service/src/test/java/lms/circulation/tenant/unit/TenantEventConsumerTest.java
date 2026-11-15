package lms.circulation.tenant.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.borrowing.application.ProvisionDefaultBorrowRulesUseCase;
import lms.circulation.borrowing.domain.BorrowRule;
import lms.circulation.borrowing.domain.BorrowRuleRepository;
import lms.circulation.borrowing.domain.ReaderType;
import lms.circulation.tenant.application.ChangeTenantStatusUseCase;
import lms.circulation.tenant.application.KnownTenantsPort;
import lms.circulation.tenant.application.RegisterTenantUseCase;
import lms.circulation.tenant.domain.KnownTenantStatus;
import lms.circulation.tenant.infrastructure.messaging.TenantEventConsumer;
import lms.tenant.TenantContext;
import lms.tenant.TenantRequiredException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Consumer TenantCreated/TenantStatusChanged (hợp đồng Task 6, chưa có producer thật): chạy trong tenant của envelope,
 * thiếu tenant thì bỏ qua; use case tạo quy tắc mượn mặc định idempotent. Không cần Docker/Kafka.
 */
class TenantEventConsumerTest {

    private final RegisterTenantUseCase register = mock(RegisterTenantUseCase.class);
    private final ChangeTenantStatusUseCase changeStatus = mock(ChangeTenantStatusUseCase.class);
    private final TenantEventConsumer consumer = new TenantEventConsumer(new ObjectMapper(), register, changeStatus);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static String created(UUID envelopeTenant, UUID payloadTenant) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"TenantCreated\""
                + (envelopeTenant == null ? "" : ",\"tenantId\":\"" + envelopeTenant + "\"")
                + ",\"payload\":{" + (payloadTenant == null ? "" : "\"tenantId\":\"" + payloadTenant + "\"") + "}}";
    }

    private static String statusChanged(UUID tenant, String status) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"TenantStatusChanged\",\"tenantId\":\""
                + tenant + "\",\"payload\":{\"tenantId\":\"" + tenant + "\",\"status\":\"" + status + "\"}}";
    }

    @Test
    void tenantCreatedDangKyTrongDungTenantVaXoaNguCanhSauDo() {
        UUID tenant = UUID.randomUUID();
        List<Optional<UUID>> seen = new ArrayList<>();
        doAnswer(i -> {
            seen.add(TenantContext.current());
            return 2;
        }).when(register).execute(tenant);

        consumer.onEvent(created(tenant, tenant));

        assertThat(seen).containsExactly(Optional.of(tenant));
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void envelopeThieuTenantIdThiBoQua() {
        consumer.onEvent(created(null, UUID.randomUUID()));

        verifyNoInteractions(register, changeStatus);
    }

    @Test
    void payloadKhacTenantCuaEnvelopeThiBoQua() {
        consumer.onEvent(created(UUID.randomUUID(), UUID.randomUUID()));

        verifyNoInteractions(register);
    }

    @Test
    void messageHongHoacLoaiKhacThiBoQua() {
        consumer.onEvent("không phải json");
        consumer.onEvent("{\"eventType\":\"UserCreated\",\"tenantId\":\"" + UUID.randomUUID() + "\"}");
        consumer.onEvent(statusChanged(UUID.randomUUID(), "DELETED"));

        verifyNoInteractions(register, changeStatus);
    }

    @Test
    void tenantStatusChangedCapNhatDanhBa() {
        UUID tenant = UUID.randomUUID();

        consumer.onEvent(statusChanged(tenant, "SUSPENDED"));

        verify(changeStatus).execute(tenant, KnownTenantStatus.SUSPENDED);
        verify(register, never()).execute(any());
    }

    // ---- use case ----

    /** Kho quy tắc trong bộ nhớ theo tenant hiện tại (như adapter + RLS). */
    static class FakeRules implements BorrowRuleRepository {
        final java.util.Map<UUID, List<BorrowRule>> byTenant = new java.util.HashMap<>();

        public List<BorrowRule> findAll() {
            return TenantContext.current().map(t -> byTenant.getOrDefault(t, List.of())).orElse(List.of());
        }

        public boolean addIfAbsent(BorrowRule rule) {
            List<BorrowRule> rules = byTenant.computeIfAbsent(TenantContext.require(), t -> new ArrayList<>());
            if (rules.stream().anyMatch(r -> r.readerType() == rule.readerType())) return false;
            return rules.add(rule);
        }
    }

    @Test
    void dangKyTenantTaoQuyTacMacDinhIdempotentVaGhiDanhBaActive() {
        FakeRules rules = new FakeRules();
        KnownTenantsPort known = mock(KnownTenantsPort.class);
        RegisterTenantUseCase useCase = new RegisterTenantUseCase(known, new ProvisionDefaultBorrowRulesUseCase(rules));
        UUID tenant = UUID.randomUUID();

        int first = TenantContext.callAs(tenant, () -> useCase.execute(tenant));
        int again = TenantContext.callAs(tenant, () -> useCase.execute(tenant));

        assertThat(first).isEqualTo(2);
        assertThat(again).isZero();
        assertThat(TenantContext.callAs(tenant, rules::findAll)).containsExactlyInAnyOrder(
                new BorrowRule(ReaderType.STUDENT, 5, 14), new BorrowRule(ReaderType.LECTURER, 10, 30));
        assertThat(TenantContext.callAs(UUID.randomUUID(), rules::findAll)).isEmpty();
        verify(known, org.mockito.Mockito.times(2)).upsert(tenant, KnownTenantStatus.ACTIVE);
    }

    @Test
    void dangKyTenantKhacNguCanhHoacKhongCoTenantBiTuChoi() {
        KnownTenantsPort known = mock(KnownTenantsPort.class);
        ProvisionDefaultBorrowRulesUseCase provision = mock(ProvisionDefaultBorrowRulesUseCase.class);
        when(provision.execute()).thenReturn(0);
        RegisterTenantUseCase useCase = new RegisterTenantUseCase(known, provision);
        UUID tenant = UUID.randomUUID();

        assertThatThrownBy(() -> useCase.execute(tenant)).isInstanceOf(TenantRequiredException.class);
        assertThatThrownBy(() -> TenantContext.callAs(UUID.randomUUID(), () -> useCase.execute(tenant)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(known, provision);
    }
}
