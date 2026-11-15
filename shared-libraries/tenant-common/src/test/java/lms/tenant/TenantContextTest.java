package lms.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void runAsDatRoiKhoiPhucGiaTriCu() {
        TenantContext.set(A);

        TenantContext.runAs(B, () -> assertThat(TenantContext.current()).contains(B));

        assertThat(TenantContext.current()).contains(A);
    }

    @Test
    void runAsKhoiPhucKeCaKhiNemLoi() {
        TenantContext.set(A);

        assertThatThrownBy(() -> TenantContext.runAs(B, () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(TenantContext.current()).contains(A);
    }

    @Test
    void runAsXoaKhiTruocDoTrong() {
        TenantContext.runAs(B, () -> { });

        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void callAsTraGiaTriVaKhoiPhucKeCaKhiNemLoi() {
        TenantContext.set(A);

        UUID inside = TenantContext.callAs(B, TenantContext::require);
        assertThat(inside).isEqualTo(B);
        assertThat(TenantContext.current()).contains(A);

        assertThatThrownBy(() -> TenantContext.callAs(B, () -> { throw new IllegalArgumentException("x"); }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TenantContext.current()).contains(A);
    }

    @Test
    void requireNemLoiKhiTrong() {
        assertThatThrownBy(TenantContext::require).isInstanceOf(TenantRequiredException.class);
    }

    @Test
    void contextKhongRoSangThreadKhac() throws Exception {
        TenantContext.set(A);
        AtomicReference<Object> seen = new AtomicReference<>();

        Thread t = new Thread(() -> seen.set(TenantContext.current()));
        t.start();
        t.join();

        assertThat(seen.get()).isEqualTo(java.util.Optional.empty());
    }

    @Test
    void identifierResolverTraUuidNilKhiKhongCoTenant() {
        TenantIdentifierResolver resolver = new TenantIdentifierResolver();

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(new UUID(0, 0));
        assertThat(resolver.validateExistingCurrentSessions()).isTrue();

        TenantContext.set(A);
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(A);
    }
}
