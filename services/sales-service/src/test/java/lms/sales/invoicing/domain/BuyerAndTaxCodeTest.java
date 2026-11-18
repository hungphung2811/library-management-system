package lms.sales.invoicing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.junit.jupiter.api.Test;

/** Mã số thuế (định dạng), người mua, cấu hình hóa đơn. */
class BuyerAndTaxCodeTest {

    private static ErrorCode codeOf(Runnable r) {
        return org.junit.jupiter.api.Assertions.assertThrows(SalesException.class, r::run).code();
    }

    @Test
    void mstNguoiBanMuoiSoHoacMuoiGachBaSo() {
        assertThat(TaxCode.seller("0123456789")).isEqualTo("0123456789");
        assertThat(TaxCode.seller(" 0123456789-001 ")).isEqualTo("0123456789-001");
        for (String bad : new String[]{"", null, "123", "012345678", "01234567890", "0123456789-01",
                "0123456789001", "012345678a", "0123456789 001", "0123456789-0011", "012345678901"}) {
            assertThat(codeOf(() -> TaxCode.seller(bad))).as(String.valueOf(bad)).isEqualTo(ErrorCode.INVALID_TAX_CODE);
        }
    }

    @Test
    void mstNguoiMuaChoPhepRongVaThemMuoiHaiSo() {
        assertThat(TaxCode.buyerOrNull(null)).isNull();
        assertThat(TaxCode.buyerOrNull("  ")).isNull();
        assertThat(TaxCode.buyerOrNull("012345678901")).isEqualTo("012345678901");
        assertThat(TaxCode.buyerOrNull("0123456789-002")).isEqualTo("0123456789-002");
        assertThat(codeOf(() -> TaxCode.buyerOrNull("12345"))).isEqualTo(ErrorCode.INVALID_TAX_CODE);
        assertThat(codeOf(() -> TaxCode.buyerOrNull("0123456789012"))).isEqualTo(ErrorCode.INVALID_TAX_CODE);
    }

    @Test
    void coMstThiBatBuocDonViVaDiaChi() {
        assertThat(codeOf(() -> BuyerInfo.of("An", null, "0123456789", null, null)))
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(codeOf(() -> BuyerInfo.of(null, "Công ty A", "0123456789", null, null)))
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        BuyerInfo ok = BuyerInfo.of(null, " Công ty A ", "0123456789", " 1 Nguyễn Huệ ", "ketoan@a.vn");
        assertThat(ok.company()).isEqualTo("Công ty A");
        assertThat(ok.taxCode()).isEqualTo("0123456789");
    }

    @Test
    void khachLeKhongLayHoaDonVaEmailSai() {
        BuyerInfo none = BuyerInfo.notRequested();
        assertThat(none.isNotRequested()).isTrue();
        assertThat(none.name()).isEqualTo("Người mua không lấy hóa đơn");
        assertThat(none.taxCode()).isNull();
        assertThat(codeOf(() -> BuyerInfo.of("An", null, null, null, "khong-phai-email")))
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(codeOf(() -> BuyerInfo.of(null, null, null, null, null).requireIdentified()))
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void buyerKhongInDuLieuCaNhan() {
        BuyerInfo b = BuyerInfo.of("Nguyễn Văn An", null, null, "12 Hai Bà Trưng", "an@x.vn");
        assertThat(b.toString()).doesNotContain("An").doesNotContain("Hai Bà Trưng").doesNotContain("an@x.vn");
    }

    @Test
    void cauHinhChuaDuThiChuaPhatHanhDuoc() {
        UUID t = UUID.randomUUID();
        InvoiceSettings d = InvoiceSettings.defaults(t);
        assertThat(d.isComplete()).isFalse();
        assertThat(d.version()).isZero();
        assertThat(codeOf(d::requireSeller)).isEqualTo(ErrorCode.INVOICE_SETTINGS_INCOMPLETE);
        InvoiceSettings s = d.update("Nhà sách A", "0123456789", "1 Lê Lợi", null, null, "1", "C26TAA", true,
                Instant.now(), UUID.randomUUID());
        assertThat(s.isComplete()).isTrue();
        assertThat(s.requireSeller().taxCode()).isEqualTo("0123456789");
    }

    @Test
    void cauHinhKiemMstMauSoKyHieuVaDoDaiTenBoDem() {
        InvoiceSettings d = InvoiceSettings.defaults(UUID.randomUUID());
        assertThat(codeOf(() -> d.update("A", "123", "x", null, null, "1", "C26TAA", false, Instant.now(), null)))
                .isEqualTo(ErrorCode.INVALID_TAX_CODE);
        assertThat(codeOf(() -> d.update("A", "0123456789", "x", null, null, "", "C26TAA", false, Instant.now(), null)))
                .isEqualTo(ErrorCode.INVALID_SETTINGS);
        assertThat(codeOf(() -> d.update("A", "0123456789", "x", null, null, "1234567", "C26TAA", false,
                Instant.now(), null))).isEqualTo(ErrorCode.INVALID_SETTINGS);
        assertThat(codeOf(() -> d.update("A", "0123456789", "x", null, null, "1", "C 26", false, Instant.now(), null)))
                .isEqualTo(ErrorCode.INVALID_SETTINGS);
        assertThat(codeOf(() -> d.update(" ", "0123456789", "x", null, null, "1", "C26TAA", false, Instant.now(), null)))
                .isEqualTo(ErrorCode.INVALID_SETTINGS);
        // mẫu số 6 + ký hiệu 8 là tối đa: tên bộ đếm vẫn vừa 30 ký tự
        assertThat(InvoiceNumbers.counterName("ABCDEF", "ABCDEFGH", 2026).length())
                .isLessThanOrEqualTo(InvoiceNumbers.MAX_COUNTER_NAME);
        assertThat(InvoiceNumbers.counterName("1", "C26TAA", 2026)).isEqualTo("INVOICE:1:C26TAA:2026");
    }

    @Test
    void soHienThiDem8ChuSoVaNamTheoGioVietNam() {
        assertThat(InvoiceNumbers.display(12)).isEqualTo("00000012");
        // 2026-12-31 18:00 UTC là 01:00 ngày 2027-01-01 giờ Việt Nam
        assertThat(InvoiceNumbers.fiscalYear(Instant.parse("2026-12-31T18:00:00Z"))).isEqualTo(2027);
        assertThat(InvoiceNumbers.fiscalYear(Instant.parse("2026-12-31T16:59:59Z"))).isEqualTo(2026);
    }
}
