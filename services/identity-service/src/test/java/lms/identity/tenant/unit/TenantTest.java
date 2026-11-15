package lms.identity.tenant.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import lms.identity.tenant.domain.InvalidTenantException;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TenantTest {

    @Test
    void taoMoiLaActiveVaGiuThongTin() {
        UUID org = UUID.randomUUID();
        Tenant t = Tenant.create("thu-vien-a1", "Thư viện A", org);

        assertThat(t.id()).isNotNull();
        assertThat(t.code()).isEqualTo("thu-vien-a1");
        assertThat(t.name()).isEqualTo("Thư viện A");
        assertThat(t.organizationId()).isEqualTo(org);
        assertThat(t.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(t.isActive()).isTrue();
    }

    @Test
    void organizationCoTheNull() {
        assertThat(Tenant.create("ab", "Tên", null).organizationId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "default", "a-b-1", "0123456789012345678901234567890123456789012345678"})
    void maHopLe(String code) {
        assertThat(Tenant.create(code, "Tên", null).code()).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a", "ABC", "có dấu", "a_b", "a b", "a.b",
            "01234567890123456789012345678901234567890123456789x"})
    void maKhongHopLeBiTuChoi(String code) {
        assertThatThrownBy(() -> Tenant.create(code, "Tên", null)).isInstanceOf(InvalidTenantException.class);
    }

    @Test
    void maNullVaTenRongBiTuChoi() {
        assertThatThrownBy(() -> Tenant.create(null, "Tên", null)).isInstanceOf(InvalidTenantException.class);
        assertThatThrownBy(() -> Tenant.create("ab", " ", null)).isInstanceOf(InvalidTenantException.class);
        assertThatThrownBy(() -> Tenant.create("ab", null, null)).isInstanceOf(InvalidTenantException.class);
    }

    @Test
    void suspendRoiActivate() {
        Tenant t = Tenant.create("ab", "Tên", null);

        t.suspend();
        assertThat(t.status()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(t.isActive()).isFalse();

        t.activate();
        assertThat(t.status()).isEqualTo(TenantStatus.ACTIVE);
    }
}
