package lms.catalog.catalog.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.text.Normalizer;
import lms.catalog.catalog.domain.InvalidTitleException;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitlePreviewTooLongException;
import org.junit.jupiter.api.Test;

/** Quy tắc mô tả (500) và đoạn đọc thử (2000) của Title: chuẩn hóa NFC, ký tự điều khiển, rỗng, code point. */
class TitlePreviewTest {

    private static Title title() {
        return Title.create("Clean Code", "Martin", "111", BigDecimal.TEN);
    }

    @Test
    void mauMoiChuaCoMoTaVaPreview() {
        Title t = title();
        assertNull(t.description());
        assertNull(t.previewText());
        assertFalse(t.hasPreview());
    }

    @Test
    void preview2000KyTuLuuDuocVa2001BiTuChoi() {
        assertEquals(2000, title().withPreview(null, "a".repeat(2000)).previewText().length());
        TitlePreviewTooLongException ex = assertThrows(TitlePreviewTooLongException.class,
                () -> title().withPreview(null, "a".repeat(2001)));
        assertTrue(ex.getMessage().contains("2000"));
    }

    @Test
    void moTa500KyTuLuuDuocVa501BiTuChoi() {
        assertEquals(500, title().withPreview("b".repeat(500), null).description().length());
        assertThrows(TitlePreviewTooLongException.class, () -> title().withPreview("b".repeat(501), null));
    }

    @Test
    void loiQuaDaiKhongPhaiInvalidTitleException() {
        // InvalidTitleException bị ánh xạ 422; quá dài phải là 400 TITLE_PREVIEW_TOO_LONG
        RuntimeException ex = assertThrows(RuntimeException.class, () -> title().withPreview("b".repeat(501), null));
        assertFalse(ex instanceof InvalidTitleException);
    }

    @Test
    void demTheoCodePointKhongTheoUtf16() {
        String emoji = new String(Character.toChars(0x1F600));
        assertEquals(2, emoji.length());
        // 2000 emoji = 4000 đơn vị UTF-16 nhưng đúng 2000 code point: hợp lệ
        Title ok = title().withPreview(null, emoji.repeat(2000));
        assertEquals(2000, ok.previewText().codePointCount(0, ok.previewText().length()));
        assertThrows(TitlePreviewTooLongException.class, () -> title().withPreview(null, emoji.repeat(2001)));
        assertEquals(500, title().withPreview(emoji.repeat(500), null).description().codePointCount(0, 1000));
        assertThrows(TitlePreviewTooLongException.class, () -> title().withPreview(emoji.repeat(501), null));
    }

    @Test
    void chuanHoaNfcTruocKhiDem() {
        // "ệ" dạng tổ hợp (e + dấu mũ + dấu nặng = 3 code point) gộp về 1 code point sau NFC
        String decomposed = Normalizer.normalize("ệ", Normalizer.Form.NFD);
        assertEquals(3, decomposed.codePointCount(0, decomposed.length()));
        Title t = title().withPreview(null, decomposed.repeat(2000));
        assertEquals(2000, t.previewText().length());
        assertEquals(Normalizer.normalize("ệ", Normalizer.Form.NFC), t.previewText().substring(0, 1));
        assertTrue(Normalizer.isNormalized(t.previewText(), Normalizer.Form.NFC));
    }

    @Test
    void boKyTuDieuKhienGiuXuongDongVaTab() {
        Title t = title().withPreview("a\u0000b\u0007c\u001Bd\u007F", "x\u0000y\ttab\nline\u0085z");
        assertEquals("abcd", t.description());
        assertEquals("xy\ttab\nlinez", t.previewText());
    }

    @Test
    void crlfVaCrThanhLf() {
        assertEquals("a\nb\nc", title().withPreview(null, "a\r\nb\rc").previewText());
    }

    @Test
    void catKhoangTrangDauCuoi() {
        assertEquals("noi dung", title().withPreview("  noi dung \n", null).description());
    }

    @Test
    void rongHoacChiKhoangTrangHoacChiKyTuDieuKhienThanhNull() {
        for (String blank : new String[] {"", "   ", "\n\t \r\n", "\u0000\u0001", " "}) {
            Title t = title().withPreview(blank, blank);
            assertNull(t.description(), "mô tả [" + blank.length() + "]");
            assertNull(t.previewText());
            assertFalse(t.hasPreview());
        }
        assertNull(title().withPreview(null, null).previewText());
    }

    @Test
    void doDaiTinhSauKhiCatVaBoKyTuDieuKhien() {
        String padded = "  " + "a".repeat(2000) + "\u0000 \n";
        assertEquals(2000, title().withPreview(null, padded).previewText().length());
    }

    @Test
    void vanBanThuanTraNguyenVanKhongEscapeHtml() {
        String raw = "<script>alert(1)</script> & \"quote\" 'q'";
        assertEquals(raw, title().withPreview(raw, raw).previewText());
    }

    @Test
    void withPreviewGiuCacTruongKhacVaKhongDoiBanGoc() {
        Title original = title();
        Title withPreview = original.withPreview("mo ta", "doc thu");
        assertEquals(original.id(), withPreview.id());
        assertEquals("Clean Code", withPreview.name());
        assertEquals("111", withPreview.isbn());
        assertNull(original.previewText());
        assertTrue(withPreview.hasPreview());
    }

    @Test
    void updateThongTinChinhGiuMoTaVaPreview() {
        Title t = title().withPreview("mo ta", "doc thu").update("Clean Code 2", "Martin", "111", BigDecimal.ONE);
        assertEquals("mo ta", t.description());
        assertEquals("doc thu", t.previewText());
        assertEquals("Clean Code 2", t.name());
    }

    @Test
    void thayBangNullXoaCaHaiTruong() {
        Title t = title().withPreview("mo ta", "doc thu").withPreview(null, "");
        assertNull(t.description());
        assertNull(t.previewText());
    }
}
