package lms.catalog.catalog.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import lms.catalog.catalog.domain.InvalidTitleException;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleExtras;
import lms.catalog.catalog.domain.TitleFormat;
import org.junit.jupiter.api.Test;

class TitleExtrasTest {

    private static TitleExtras of(String subtitle, Integer year, String language, Integer pages, String series,
                                  Integer seriesNo, String cover, List<String> tags) {
        return new TitleExtras(subtitle, year, null, language, pages, null, null, series, seriesNo, cover, tags);
    }

    @Test
    void macDinhLaRongVaTitleMoiKhongCoThongTinBoSung() {
        assertTrue(TitleExtras.EMPTY.isEmpty());
        Title t = Title.create("A", "B", null, BigDecimal.ONE);
        assertTrue(t.extras().isEmpty());
        assertEquals(List.of(), t.extras().tags());
    }

    @Test
    void chuanHoaChuoiRongThanhNullVaNgonNguThanhChuThuong() {
        TitleExtras x = new TitleExtras("  Phụ đề  ", 2019, "  ", " VI ", 320, TitleFormat.EBOOK, "", null, null, null,
                null);
        assertEquals("Phụ đề", x.subtitle());
        assertNull(x.edition());
        assertEquals("vi", x.language());
        assertNull(x.translator());
        assertEquals(TitleFormat.EBOOK, x.format());
        assertEquals(List.of(), x.tags());
    }

    @Test
    void namXuatBanTrongKhoang() {
        int max = Year.now().getValue() + 1;
        assertEquals(max, of(null, max, null, null, null, null, null, null).publicationYear());
        assertEquals(1000, of(null, 1000, null, null, null, null, null, null).publicationYear());
        assertThrows(InvalidTitleException.class, () -> of(null, max + 1, null, null, null, null, null, null));
        assertThrows(InvalidTitleException.class, () -> of(null, 999, null, null, null, null, null, null));
    }

    @Test
    void ngonNguPhaiLa2Den8ChuCai() {
        assertThrows(InvalidTitleException.class, () -> of(null, null, "v", null, null, null, null, null));
        assertThrows(InvalidTitleException.class, () -> of(null, null, "vietnamese", null, null, null, null, null));
        assertThrows(InvalidTitleException.class, () -> of(null, null, "v1", null, null, null, null, null));
        assertEquals("ja", of(null, null, "ja", null, null, null, null, null).language());
    }

    @Test
    void soTrangVaSoTap() {
        assertEquals(20000, of(null, null, null, 20000, null, null, null, null).pageCount());
        assertThrows(InvalidTitleException.class, () -> of(null, null, null, 0, null, null, null, null));
        assertThrows(InvalidTitleException.class, () -> of(null, null, null, 20001, null, null, null, null));
        assertThrows(InvalidTitleException.class, () -> of(null, null, null, null, "Bộ", 0, null, null));
    }

    @Test
    void soTapChiHopLeKhiCoBoSach() {
        assertThrows(InvalidTitleException.class, () -> of(null, null, null, null, null, 2, null, null));
        assertThrows(InvalidTitleException.class, () -> of(null, null, null, null, "  ", 2, null, null));
        TitleExtras x = of(null, null, null, null, "Harry Potter", 2, null, null);
        assertEquals(2, x.seriesNumber());
    }

    @Test
    void anhBiaChiNhanHttpHoacHttps() {
        assertEquals("https://x.example/a.jpg", of(null, null, null, null, null, null, " https://x.example/a.jpg ", null)
                .coverImageUrl());
        assertEquals("HTTP://x.example/a.jpg", of(null, null, null, null, null, null, "HTTP://x.example/a.jpg", null)
                .coverImageUrl());
        for (String bad : List.of("ftp://x.example/a.jpg", "javascript:alert(1)", "//x.example/a.jpg", "a.jpg",
                "https://", "data:image/png;base64,AAAA")) {
            assertThrows(InvalidTitleException.class, () -> of(null, null, null, null, null, null, bad, null), bad);
        }
        String longUrl = "https://x.example/" + "a".repeat(490);
        assertThrows(InvalidTitleException.class, () -> of(null, null, null, null, null, null, longUrl, null));
    }

    @Test
    void nhanCatKhoangTrangBoTrungKhongPhanBietHoaThuongVaGiuThuTu() {
        TitleExtras x = of(null, null, null, null, null, null, null,
                java.util.Arrays.asList("  Java ", "java", "JAVA", "Lập trình", " ", null, "lập trình", "Spring"));
        assertEquals(List.of("Java", "Lập trình", "Spring"), x.tags());
    }

    @Test
    void toiDa10NhanVaMoiNhanToiDa50KyTu() {
        List<String> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) ten.add("tag" + i);
        assertEquals(10, of(null, null, null, null, null, null, null, ten).tags().size());
        ten.add("tag10");
        assertThrows(InvalidTitleException.class, () -> of(null, null, null, null, null, null, null, ten));
        // trùng không tính vào giới hạn
        List<String> dup = new ArrayList<>();
        for (int i = 0; i < 12; i++) dup.add("tag" + (i % 3));
        assertEquals(3, of(null, null, null, null, null, null, null, dup).tags().size());
        assertEquals(50, of(null, null, null, null, null, null, null, List.of("a".repeat(50))).tags().get(0).length());
        assertThrows(InvalidTitleException.class,
                () -> of(null, null, null, null, null, null, null, List.of("a".repeat(51))));
    }

    @Test
    void doDaiChuoi() {
        assertThrows(InvalidTitleException.class,
                () -> new TitleExtras("a".repeat(256), null, null, null, null, null, null, null, null, null, null));
        assertThrows(InvalidTitleException.class,
                () -> new TitleExtras(null, null, "a".repeat(51), null, null, null, null, null, null, null, null));
        assertThrows(InvalidTitleException.class,
                () -> new TitleExtras(null, null, null, null, null, null, "a".repeat(256), null, null, null, null));
        assertThrows(InvalidTitleException.class,
                () -> new TitleExtras(null, null, null, null, null, null, null, "a".repeat(256), null, null, null));
    }

    @Test
    void titleGiuThongTinBoSungKhiSuaVaThayDuocBangWithExtras() {
        Title t = Title.create("A", "B", null, BigDecimal.ONE)
                .withExtras(new TitleExtras("Phụ", 2020, null, "vi", 100, null, null, null, null, null,
                        List.of("x")));
        Title updated = t.update("A2", "B2", null, BigDecimal.TEN);
        assertEquals("Phụ", updated.extras().subtitle());
        assertEquals(List.of("x"), updated.withPreview("d", "p").extras().tags());
        assertEquals(List.of("x"), updated.withClassification(null, null, List.of()).extras().tags());
        assertTrue(updated.withExtras(null).extras().isEmpty());
    }
}
