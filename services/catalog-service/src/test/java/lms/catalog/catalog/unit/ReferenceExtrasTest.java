package lms.catalog.catalog.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Year;
import lms.catalog.catalog.domain.InvalidReferenceException;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceExtras;
import lms.catalog.catalog.domain.ReferenceKind;
import org.junit.jupiter.api.Test;

class ReferenceExtrasTest {

    private static Reference author(ReferenceExtras x) {
        return Reference.create(ReferenceKind.AUTHOR, "A", null, null, x);
    }

    private static Reference publisher(ReferenceExtras x) {
        return Reference.create(ReferenceKind.PUBLISHER, "P", null, null, x);
    }

    private static Reference shelf(ReferenceExtras x) {
        return Reference.create(ReferenceKind.SHELF_LOCATION, "S-01", null, null, x);
    }

    @Test
    void macDinhLaRong() {
        assertTrue(Reference.create(ReferenceKind.AUTHOR, "A", null, null).extras().isEmpty());
        assertTrue(Reference.create(ReferenceKind.CATEGORY, "C", null, null, null).extras().isEmpty());
    }

    @Test
    void tacGiaHopLeVaChuanHoa() {
        Reference r = author(ReferenceExtras.author(1952, 2018, "  Anh  ", " https://x.example/a.jpg ", "  "));
        assertEquals(1952, r.extras().birthYear());
        assertEquals("Anh", r.extras().nationality());
        assertEquals("https://x.example/a.jpg", r.extras().photoUrl());
        assertNull(r.extras().website());
    }

    @Test
    void namSinhVaNamMat() {
        int now = Year.now().getValue();
        assertEquals(now, author(ReferenceExtras.author(now, now, null, null, null)).extras().deathYear());
        assertThrows(InvalidReferenceException.class, () -> author(ReferenceExtras.author(0, null, null, null, null)));
        assertThrows(InvalidReferenceException.class,
                () -> author(ReferenceExtras.author(now + 1, null, null, null, null)));
        assertThrows(InvalidReferenceException.class,
                () -> author(ReferenceExtras.author(1900, 1899, null, null, null)));
        assertThrows(InvalidReferenceException.class,
                () -> author(ReferenceExtras.author(1900, now + 1, null, null, null)));
        assertEquals(1900, author(ReferenceExtras.author(1900, 1900, null, null, null)).extras().deathYear());
        assertEquals(1700, author(ReferenceExtras.author(null, 1700, null, null, null)).extras().deathYear());
    }

    @Test
    void urlChiNhanHttpHttpsVaToiDa500() {
        for (String bad : new String[] {"ftp://x.example/a", "javascript:alert(1)", "//x.example", "a.jpg", "https://"}) {
            assertThrows(InvalidReferenceException.class,
                    () -> author(ReferenceExtras.author(null, null, null, bad, null)), bad);
            assertThrows(InvalidReferenceException.class,
                    () -> publisher(ReferenceExtras.publisher(null, null, bad, null)), bad);
        }
        String longUrl = "https://x.example/" + "a".repeat(490);
        assertThrows(InvalidReferenceException.class,
                () -> author(ReferenceExtras.author(null, null, null, null, longUrl)));
    }

    @Test
    void nhaXuatBan() {
        int now = Year.now().getValue();
        Reference r = publisher(ReferenceExtras.publisher("Việt Nam", 1957, "https://nxb.example", "Hà Nội"));
        assertEquals("Việt Nam", r.extras().country());
        assertEquals(1957, r.extras().foundedYear());
        assertEquals(1400, publisher(ReferenceExtras.publisher(null, 1400, null, null)).extras().foundedYear());
        assertEquals(now, publisher(ReferenceExtras.publisher(null, now, null, null)).extras().foundedYear());
        assertThrows(InvalidReferenceException.class, () -> publisher(ReferenceExtras.publisher(null, 1399, null, null)));
        assertThrows(InvalidReferenceException.class,
                () -> publisher(ReferenceExtras.publisher(null, now + 1, null, null)));
        assertThrows(InvalidReferenceException.class,
                () -> publisher(ReferenceExtras.publisher("a".repeat(101), null, null, null)));
        assertThrows(InvalidReferenceException.class,
                () -> publisher(ReferenceExtras.publisher(null, null, null, "a".repeat(501))));
    }

    @Test
    void viTriKe() {
        Reference r = shelf(ReferenceExtras.shelf(" Tầng 2 ", "Khu Văn học", 100000));
        assertEquals("Tầng 2", r.extras().floor());
        assertEquals(100000, r.extras().capacity());
        assertThrows(InvalidReferenceException.class, () -> shelf(ReferenceExtras.shelf(null, null, 0)));
        assertThrows(InvalidReferenceException.class, () -> shelf(ReferenceExtras.shelf(null, null, 100001)));
        assertThrows(InvalidReferenceException.class, () -> shelf(ReferenceExtras.shelf("a".repeat(21), null, 1)));
        assertThrows(InvalidReferenceException.class, () -> shelf(ReferenceExtras.shelf(null, "a".repeat(51), 1)));
    }

    @Test
    void truongKhongThuocLoaiBiTuChoi() {
        assertThrows(InvalidReferenceException.class,
                () -> publisher(ReferenceExtras.author(1900, null, null, null, null)));
        assertThrows(InvalidReferenceException.class,
                () -> author(ReferenceExtras.publisher("VN", null, null, null)));
        assertThrows(InvalidReferenceException.class, () -> author(ReferenceExtras.shelf("T1", null, null)));
        assertThrows(InvalidReferenceException.class,
                () -> shelf(ReferenceExtras.author(null, null, null, null, "https://x.example")));
        assertThrows(InvalidReferenceException.class,
                () -> Reference.create(ReferenceKind.CATEGORY, "C", null, null, ReferenceExtras.shelf("T1", null, null)));
    }

    @Test
    void suaThayToanBoNhungBanCuGiuNguyen() {
        Reference r = author(ReferenceExtras.author(1900, null, "Pháp", null, null));
        assertEquals("Pháp", r.update("B", null, null).extras().nationality());
        assertEquals("B", r.update("B", null, null).name());
        Reference replaced = r.update("A", null, null, ReferenceExtras.author(null, null, "Anh", null, null));
        assertNull(replaced.extras().birthYear());
        assertEquals("Anh", replaced.extras().nationality());
        assertTrue(r.update("A", null, null, null).extras().isEmpty());
        assertEquals("Pháp", r.withActive(false).extras().nationality());
    }
}
