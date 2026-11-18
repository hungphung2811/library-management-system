package lms.sales.invoicing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Số tiền bằng chữ (ADR-0030 D1.7): giá trị biên, "linh", "mốt/tư/lăm", "mười", nhóm nghìn/triệu/tỷ, số lớn. */
class VietnameseAmountInWordsTest {

    private static Map<Long, String> cases() {
        Map<Long, String> m = new LinkedHashMap<>();
        m.put(0L, "Không đồng");
        m.put(1L, "Một đồng");
        m.put(4L, "Bốn đồng");
        m.put(5L, "Năm đồng");
        m.put(10L, "Mười đồng");
        m.put(11L, "Mười một đồng");
        m.put(14L, "Mười bốn đồng");
        m.put(15L, "Mười lăm đồng");
        m.put(20L, "Hai mươi đồng");
        m.put(21L, "Hai mươi mốt đồng");
        m.put(24L, "Hai mươi tư đồng");
        m.put(25L, "Hai mươi lăm đồng");
        m.put(34L, "Ba mươi tư đồng");
        m.put(99L, "Chín mươi chín đồng");
        m.put(100L, "Một trăm đồng");
        m.put(101L, "Một trăm linh một đồng");
        m.put(105L, "Một trăm linh năm đồng");
        m.put(110L, "Một trăm mười đồng");
        m.put(115L, "Một trăm mười lăm đồng");
        m.put(121L, "Một trăm hai mươi mốt đồng");
        m.put(999L, "Chín trăm chín mươi chín đồng");
        m.put(1_000L, "Một nghìn đồng");
        m.put(1_005L, "Một nghìn không trăm linh năm đồng");
        m.put(1_010L, "Một nghìn không trăm mười đồng");
        m.put(1_020L, "Một nghìn không trăm hai mươi đồng");
        m.put(1_100L, "Một nghìn một trăm đồng");
        m.put(1_234L, "Một nghìn hai trăm ba mươi tư đồng");
        m.put(10_000L, "Mười nghìn đồng");
        m.put(15_000L, "Mười lăm nghìn đồng");
        m.put(21_000L, "Hai mươi mốt nghìn đồng");
        m.put(100_000L, "Một trăm nghìn đồng");
        m.put(105_000L, "Một trăm linh năm nghìn đồng");
        m.put(1_000_000L, "Một triệu đồng");
        m.put(1_000_005L, "Một triệu không trăm linh năm đồng");
        m.put(1_001_000L, "Một triệu không trăm linh một nghìn đồng");
        m.put(1_000_100L, "Một triệu một trăm đồng");
        m.put(2_500_000L, "Hai triệu năm trăm nghìn đồng");
        m.put(12_345_678L, "Mười hai triệu ba trăm bốn mươi lăm nghìn sáu trăm bảy mươi tám đồng");
        m.put(1_000_000_000L, "Một tỷ đồng");
        m.put(1_000_000_001L, "Một tỷ không trăm linh một đồng");
        m.put(5_050_505_050L, "Năm tỷ không trăm năm mươi triệu năm trăm linh năm nghìn không trăm năm mươi đồng");
        m.put(1_000_000_000_000L, "Một nghìn tỷ đồng");
        m.put(1_000_000_000_000_000L, "Một triệu tỷ đồng");
        return m;
    }

    @Test
    void bangGiaTriBienDocDung() {
        cases().forEach((amount, words) -> assertThat(VietnameseAmountInWords.of(amount))
                .as("số " + amount).isEqualTo(words));
    }

    @Test
    void soLonNhatCuaLongDocDuocKhongNem() {
        assertThat(VietnameseAmountInWords.of(Long.MAX_VALUE))
                .startsWith("Chín tỷ tỷ")
                .endsWith("đồng");
    }

    @Test
    void chuDauVietHoaVaChiMotDauCachGiuaCacTu() {
        String s = VietnameseAmountInWords.of(2_500_000);
        assertThat(s.charAt(0)).isUpperCase();
        assertThat(s).doesNotContain("  ").isEqualTo(s.trim());
    }

    @Test
    void soAmBiTuChoi() {
        assertThatThrownBy(() -> VietnameseAmountInWords.of(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
