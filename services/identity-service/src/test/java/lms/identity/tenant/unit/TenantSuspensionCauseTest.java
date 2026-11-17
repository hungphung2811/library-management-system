package lms.identity.tenant.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import lms.identity.tenant.domain.SuspensionCause;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantStatus;
import org.junit.jupiter.api.Test;

/** T1 (ADR-0012 mục 2.3.5): nguyên nhân tạm ngưng và điều kiện tự kích hoạt lại. */
class TenantSuspensionCauseTest {

    private static final UUID CONTRACT = UUID.randomUUID();

    private static Tenant active() {
        return Tenant.create("lib-x", "Thư viện X", null);
    }

    @Test
    void suspendTayGhiManualVaKhongCoHopDong() {
        Tenant t = active();
        t.suspend();
        assertThat(t.status()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(t.suspensionCause()).isEqualTo(SuspensionCause.MANUAL);
        assertThat(t.suspendedByContractId()).isNull();
    }

    @Test
    void suspendTayTrenTenantDangBiHopDongSuspendDoiThanhManual() {
        Tenant t = active();
        assertThat(t.suspendByContract(SuspensionCause.CONTRACT_EXPIRED, CONTRACT)).isTrue();
        assertThat(t.suspensionCause()).isEqualTo(SuspensionCause.CONTRACT_EXPIRED);
        assertThat(t.suspendedByContractId()).isEqualTo(CONTRACT);
        t.suspend();
        assertThat(t.suspensionCause()).isEqualTo(SuspensionCause.MANUAL);
        assertThat(t.suspendedByContractId()).isNull();
        assertThat(t.activateIfSuspendedBy(SuspensionCause.CONTRACT_EXPIRED)).as("leo thang: không tự mở").isFalse();
    }

    @Test
    void activateTayXoaNguyenNhan() {
        Tenant t = active();
        t.suspendByContract(SuspensionCause.CONTRACT_CANCELLED, CONTRACT);
        t.activate();
        assertThat(t.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(t.suspensionCause()).isNull();
        assertThat(t.suspendedByContractId()).isNull();
    }

    @Test
    void suspendByContractTrenTenantDaSuspendedKhongDoiNguyenNhan() {
        Tenant t = active();
        t.suspend();
        assertThat(t.suspendByContract(SuspensionCause.CONTRACT_EXPIRED, CONTRACT)).isFalse();
        assertThat(t.suspensionCause()).isEqualTo(SuspensionCause.MANUAL);
    }

    @Test
    void tuMoChiKhiDungNguyenNhanContractExpired() {
        Tenant expired = active();
        expired.suspendByContract(SuspensionCause.CONTRACT_EXPIRED, CONTRACT);
        assertThat(expired.activateIfSuspendedBy(SuspensionCause.CONTRACT_EXPIRED)).isTrue();
        assertThat(expired.isActive()).isTrue();
        assertThat(expired.suspensionCause()).isNull();

        Tenant manual = active();
        manual.suspend();
        assertThat(manual.activateIfSuspendedBy(SuspensionCause.CONTRACT_EXPIRED)).isFalse();
        assertThat(manual.status()).isEqualTo(TenantStatus.SUSPENDED);

        Tenant cancelled = active();
        cancelled.suspendByContract(SuspensionCause.CONTRACT_CANCELLED, CONTRACT);
        assertThat(cancelled.activateIfSuspendedBy(SuspensionCause.CONTRACT_EXPIRED)).isFalse();
        assertThat(cancelled.suspensionCause()).isEqualTo(SuspensionCause.CONTRACT_CANCELLED);

        assertThat(active().activateIfSuspendedBy(SuspensionCause.CONTRACT_EXPIRED)).as("đang ACTIVE").isFalse();
    }

    @Test
    void dungLaiDuLieuCuDangSuspendedKhongCoNguyenNhanThanhManual() {
        Tenant t = Tenant.restore(UUID.randomUUID(), "old", "Cũ", null, TenantStatus.SUSPENDED, Instant.now());
        assertThat(t.suspensionCause()).isEqualTo(SuspensionCause.MANUAL);
        Tenant byContract = Tenant.restore(UUID.randomUUID(), "new", "Mới", null, TenantStatus.SUSPENDED, Instant.now(),
                SuspensionCause.CONTRACT_EXPIRED, CONTRACT);
        assertThat(byContract.suspendedByContractId()).isEqualTo(CONTRACT);
    }

    @Test
    void suspendByContractVoiNguyenNhanManualBiTuChoi() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> active().suspendByContract(SuspensionCause.MANUAL, CONTRACT))
                .isInstanceOf(lms.identity.tenant.domain.InvalidTenantException.class);
    }
}
