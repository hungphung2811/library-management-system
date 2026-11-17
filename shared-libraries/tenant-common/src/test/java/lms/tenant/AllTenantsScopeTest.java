package lms.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Phạm vi đọc ALL của TenantContext và TenantIdentifierResolver (ADR-0008). */
class AllTenantsScopeTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void bindAllTenantsDatPhamViAllVaXoaTenantCuThe() {
        TenantContext.set(A);

        TenantContext.bindAllTenants();

        assertThat(TenantContext.isAllTenants()).isTrue();
        assertThat(TenantContext.current()).isEmpty();
        assertThat(TenantContext.readFilter()).isEmpty();
    }

    @Test
    void requireVanNemLoiOPhamViAllDeChanGhi() {
        TenantContext.bindAllTenants();

        assertThatThrownBy(TenantContext::require).isInstanceOf(TenantRequiredException.class);
    }

    @Test
    void setMotTenantXoaPhamViAll() {
        TenantContext.bindAllTenants();

        TenantContext.set(A);

        assertThat(TenantContext.isAllTenants()).isFalse();
        assertThat(TenantContext.readFilter()).contains(A);
        assertThat(TenantContext.require()).isEqualTo(A);
    }

    @Test
    void setNullXoaCaPhamViAll() {
        TenantContext.bindAllTenants();

        TenantContext.set(null);

        assertThat(TenantContext.isAllTenants()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void readFilterNemLoiKhiKhongCoTenantLanPhamViAll() {
        assertThatThrownBy(TenantContext::readFilter).isInstanceOf(TenantRequiredException.class);
    }

    @Test
    void clearXoaCaPhamViAll() {
        TenantContext.bindAllTenants();

        TenantContext.clear();

        assertThat(TenantContext.isAllTenants()).isFalse();
    }

    @Test
    void callAsTrongPhamViAllDungSingleRoiKhoiPhucAll() {
        TenantContext.bindAllTenants();

        UUID inside = TenantContext.callAs(B, TenantContext::require);

        assertThat(inside).isEqualTo(B);
        assertThat(TenantContext.isAllTenants()).isTrue();
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void runAsKhoiPhucAllKeCaKhiNemLoi() {
        TenantContext.bindAllTenants();

        assertThatThrownBy(() -> TenantContext.runAs(B, () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(TenantContext.isAllTenants()).isTrue();
    }

    @Test
    void phamViAllKhongRoSangThreadKhac() throws Exception {
        TenantContext.bindAllTenants();
        boolean[] seen = new boolean[1];

        Thread t = new Thread(() -> seen[0] = TenantContext.isAllTenants());
        t.start();
        t.join();

        assertThat(seen[0]).isFalse();
    }

    @Test
    void resolverTraRootOPhamViAllVaIsRootChiDungVoiRoot() {
        TenantIdentifierResolver resolver = new TenantIdentifierResolver();
        TenantContext.bindAllTenants();

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(TenantIdentifierResolver.ROOT);
        assertThat(TenantIdentifierResolver.ROOT).isNotEqualTo(TenantIdentifierResolver.NIL_TENANT);
        assertThat(resolver.isRoot(TenantIdentifierResolver.ROOT)).isTrue();
        assertThat(resolver.isRoot(TenantIdentifierResolver.NIL_TENANT)).isFalse();
        assertThat(resolver.isRoot(A)).isFalse();
    }

    @Test
    void resolverGiuHanhViCuKhiCoMotTenantHoacKhongCo() {
        TenantIdentifierResolver resolver = new TenantIdentifierResolver();

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(TenantIdentifierResolver.NIL_TENANT);
        TenantContext.set(A);
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(A);
        assertThat(resolver.isRoot(resolver.resolveCurrentTenantIdentifier())).isFalse();
    }
}
