package lms.observability.log;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogMaskTest {

    @Test
    void safeBoCrLfVaDoiKyTuLa() {
        assertThat(LogMask.safe("a\r\nb")).isEqualTo("ab");
        assertThat(LogMask.safe("x y?z&k=v")).isEqualTo("x_y_z_k=v");
        assertThat(LogMask.safe("Nguyễn Văn")).doesNotContain("ễ").doesNotContain(" ");
        assertThat(LogMask.safe("tok\u0000en")).isEqualTo("token");
        assertThat(LogMask.safe("{id}/a:b@c.d-e*")).isEqualTo("{id}/a:b@c.d-e*");
    }

    @Test
    void safeNullRongVaCatDoDai() {
        assertThat(LogMask.safe(null)).isEqualTo("-");
        assertThat(LogMask.safe("")).isEqualTo("-");
        assertThat(LogMask.safe("\r\n")).isEqualTo("-");
        assertThat(LogMask.safe("a".repeat(500))).hasSize(128);
    }

    @Test
    void referenceGiuBaDauBonCuoi() {
        assertThat(LogMask.reference("LMSABCDEFGHJK")).isEqualTo("LMS******GHJK");
        assertThat(LogMask.reference("LMSABCDEFG")).isEqualTo("LMS***DEFG");
    }

    @Test
    void referenceNganHon() {
        assertThat(LogMask.reference("ABCDEFGHI")).isEqualTo("*******HI");
        assertThat(LogMask.reference("ABCDEF")).isEqualTo("****EF");
        assertThat(LogMask.reference("ABCDE")).isEqualTo("***");
        assertThat(LogMask.reference(null)).isEqualTo("-");
        assertThat(LogMask.reference("  ")).isEqualTo("-");
    }

    @Test
    void soTaiKhoanBonSoCuoi() {
        assertThat(LogMask.lastDigits("0123456789")).isEqualTo("****6789");
        assertThat(LogMask.lastDigits("012 345 6789")).isEqualTo("****6789");
        assertThat(LogMask.lastDigits("1234567")).isEqualTo("****");
        assertThat(LogMask.lastDigits(null)).isEqualTo("-");
    }

    @Test
    void maGiaoDichCongGiuTienToVaSauKyTuCuoi() {
        assertThat(LogMask.providerTxnId("SEPAY-1234567a1b2c3")).isEqualTo("SEPAY-...a1b2c3");
        assertThat(LogMask.providerTxnId("MOMO-2718123456789")).isEqualTo("MOMO-...456789");
        assertThat(LogMask.providerTxnId("SEPAY-123")).isEqualTo("SEPAY-***");
        assertThat(LogMask.providerTxnId("0123456789abcdef")).isEqualTo("...abcdef");
        assertThat(LogMask.providerTxnId(null)).isEqualTo("-");
    }

    @Test
    void thongDiepDoiTacBoEmailVaDaySo() {
        String out = LogMask.partnerMessage("Gui a@b.vn so 0912345678\r\nloi 42");
        assertThat(out).contains("[email]").contains("[num]").contains("loi 42")
                .doesNotContain("a@b.vn").doesNotContain("0912345678").doesNotContain("\n");
        assertThat(LogMask.partnerMessage("x".repeat(300))).hasSize(100);
        assertThat(LogMask.partnerMessage(null)).isEmpty();
    }

    @Test
    void ipNguoiGoiBoOctetCuoiHoacGiuSau48() {
        assertThat(LogMask.clientIp("203.0.113.77")).isEqualTo("203.0.113.x");
        assertThat(LogMask.clientIp("2001:db8:abcd:12:1:2:3:4")).isEqualTo("2001:db8:abcd::/48");
        assertThat(LogMask.clientIp("::1")).isEqualTo("-");
        assertThat(LogMask.clientIp("evil\nhost")).isEqualTo("-");
        assertThat(LogMask.clientIp(null)).isEqualTo("-");
        assertThat(LogMask.clientIp("1.2.3")).isEqualTo("-");
    }
}
