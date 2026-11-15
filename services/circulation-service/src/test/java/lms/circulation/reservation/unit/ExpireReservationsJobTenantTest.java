package lms.circulation.reservation.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.reservation.application.ActiveTenantsPort;
import lms.circulation.reservation.application.ExpireOverdueReservationsUseCase;
import lms.circulation.reservation.infrastructure.messaging.ExpireReservationsJob;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;

class ExpireReservationsJobTenantTest {

    private final ExpireOverdueReservationsUseCase expire = mock(ExpireOverdueReservationsUseCase.class);

    private ExpireReservationsJob job(List<UUID> tenants) {
        ActiveTenantsPort port = () -> tenants;
        return new ExpireReservationsJob(expire, port);
    }

    @Test
    void chayLanLuotTungTenantTrongDungNguCanh() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<Optional<UUID>> seen = new ArrayList<>();
        doAnswer(i -> {
            seen.add(TenantContext.current());
            return 0;
        }).when(expire).execute();

        job(List.of(a, b)).run();

        assertThat(seen).containsExactly(Optional.of(a), Optional.of(b));
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void khongCoTenantNaoThiKhongChayVaKhongDocToanBang() {
        job(List.of()).run();

        verify(expire, never()).execute();
    }

    @Test
    void motTenantLoiKhongDungCacTenantKhac() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<Optional<UUID>> seen = new ArrayList<>();
        doAnswer(i -> {
            seen.add(TenantContext.current());
            if (TenantContext.current().equals(Optional.of(a))) {
                throw new IllegalStateException("boom");
            }
            return 1;
        }).when(expire).execute();

        job(List.of(a, b)).run();

        verify(expire, times(2)).execute();
        assertThat(seen).containsExactly(Optional.of(a), Optional.of(b));
        assertThat(TenantContext.current()).isEmpty();
    }
}
