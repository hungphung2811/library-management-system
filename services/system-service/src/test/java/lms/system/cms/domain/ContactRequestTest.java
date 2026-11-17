package lms.system.cms.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContactRequestTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-02-01T00:00:00Z");
    private static final UUID ADMIN = UUID.randomUUID();

    private static ContactRequest valid() {
        return ContactRequest.submit(UUID.randomUUID(), "Nguyễn Văn A", "a@example.com", "0901234567",
                "Thư viện X", BusinessType.LIBRARY, "Muốn dùng thử", true, T0);
    }

    private static CmsErrorCode codeOf(Runnable action) {
        CmsException ex = (CmsException) assertThatThrownBy(action::run).isInstanceOf(CmsException.class)
                .actual();
        return ex.code();
    }

    @Test
    void tiepNhanTaoYeuCauMoi() {
        ContactRequest r = valid();

        assertThat(r.status()).isEqualTo(ContactStatus.NEW);
        assertThat(r.consentAt()).isEqualTo(T0);
        assertThat(r.createdAt()).isEqualTo(T0);
        assertThat(r.isAnonymized()).isFalse();
    }

    @Test
    void thieuDongYBiTuChoi() {
        assertThat(codeOf(() -> ContactRequest.submit(UUID.randomUUID(), "A", "a@b.co", null, "O",
                BusinessType.OTHER, "m", false, T0))).isEqualTo(CmsErrorCode.CONSENT_REQUIRED);
    }

    @Test
    void truongSaiBiTuChoi() {
        assertThat(codeOf(() -> ContactRequest.submit(UUID.randomUUID(), " ", "a@b.co", null, "O",
                BusinessType.OTHER, "m", true, T0))).isEqualTo(CmsErrorCode.CONTACT_REQUEST_INVALID);
        assertThat(codeOf(() -> ContactRequest.submit(UUID.randomUUID(), "A", "khong-phai-email", null, "O",
                BusinessType.OTHER, "m", true, T0))).isEqualTo(CmsErrorCode.CONTACT_REQUEST_INVALID);
        assertThat(codeOf(() -> ContactRequest.submit(UUID.randomUUID(), "A", "a@b.co", "1".repeat(21), "O",
                BusinessType.OTHER, "m", true, T0))).isEqualTo(CmsErrorCode.CONTACT_REQUEST_INVALID);
        assertThat(codeOf(() -> ContactRequest.submit(UUID.randomUUID(), "A", "a@b.co", null, "O",
                BusinessType.OTHER, "m".repeat(2001), true, T0))).isEqualTo(CmsErrorCode.CONTACT_REQUEST_INVALID);
        assertThat(codeOf(() -> ContactRequest.submit(UUID.randomUUID(), "A", "a@b.co", null, "O", null, "m", true,
                T0))).isEqualTo(CmsErrorCode.CONTACT_REQUEST_INVALID);
    }

    @Test
    void dienThoaiTrongLuuLaNull() {
        assertThat(ContactRequest.submit(UUID.randomUUID(), "A", "a@b.co", "  ", "O", BusinessType.OTHER, "m", true,
                T0).phone()).isNull();
    }

    @Test
    void anDanhXoaDuLieuCaNhanGiuThongKe() {
        ContactRequest r = valid().anonymize(T1);

        assertThat(r.fullName()).isNull();
        assertThat(r.email()).isNull();
        assertThat(r.phone()).isNull();
        assertThat(r.message()).isNull();
        assertThat(r.anonymizedAt()).isEqualTo(T1);
        assertThat(r.organization()).isEqualTo("Thư viện X");
        assertThat(r.businessType()).isEqualTo(BusinessType.LIBRARY);
        assertThat(r.status()).isEqualTo(ContactStatus.NEW);
        assertThat(r.createdAt()).isEqualTo(T0);
    }

    @Test
    void anDanhIdempotentKhongDoiMocThoiGian() {
        ContactRequest once = valid().anonymize(T1);

        assertThat(once.anonymize(Instant.parse("2027-01-01T00:00:00Z"))).isSameAs(once);
    }

    @Test
    void daAnDanhThiKhongDoiTrangThai() {
        ContactRequest r = valid().anonymize(T1);

        assertThat(codeOf(() -> r.changeStatus(ContactStatus.HANDLED, ADMIN, T1)))
                .isEqualTo(CmsErrorCode.CONTACT_REQUEST_ANONYMIZED);
    }

    @Test
    void chuyenTrangThaiTheoBuocChoPhep() {
        ContactRequest handled = valid().changeStatus(ContactStatus.IN_PROGRESS, ADMIN, T1)
                .changeStatus(ContactStatus.HANDLED, ADMIN, T1);

        assertThat(handled.status()).isEqualTo(ContactStatus.HANDLED);
        assertThat(handled.handledBy()).isEqualTo(ADMIN);
        assertThat(handled.handledAt()).isEqualTo(T1);
        assertThat(codeOf(() -> handled.changeStatus(ContactStatus.SPAM, ADMIN, T1)))
                .isEqualTo(CmsErrorCode.CONTACT_STATUS_TRANSITION_INVALID);
        assertThat(codeOf(() -> valid().changeStatus(ContactStatus.NEW, ADMIN, T1)))
                .isEqualTo(CmsErrorCode.CONTACT_STATUS_TRANSITION_INVALID);
    }

    @Test
    void quaHanTinhTheoNgayTaoVaChuaAnDanh() {
        ContactRequest r = valid();

        assertThat(r.isDueForAnonymization(T0.plusSeconds(1))).isTrue();
        assertThat(r.isDueForAnonymization(T0)).isFalse();
        assertThat(r.anonymize(T1).isDueForAnonymization(T1.plusSeconds(1))).isFalse();
    }

    @Test
    void toStringKhongInDuLieuCaNhan() {
        String text = valid().toString();

        assertThat(text).doesNotContain("Nguyễn", "a@example.com", "0901234567", "dùng thử");
    }
}
