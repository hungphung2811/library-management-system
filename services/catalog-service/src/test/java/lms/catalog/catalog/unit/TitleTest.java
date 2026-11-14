package lms.catalog.catalog.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import lms.catalog.catalog.domain.InvalidTitleException;
import lms.catalog.catalog.domain.Title;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TitleTest {

    @Test
    void taoDauSachHopLeVaCatKhoangTrang() {
        Title t = Title.create("  Clean Code ", " Robert C. Martin ", "  ", new BigDecimal("250000"));
        assertEquals("Clean Code", t.name());
        assertEquals("Robert C. Martin", t.author());
        assertNull(t.isbn());
    }

    @Test
    void tuChoiTenRongVaGiaAm() {
        assertThrows(InvalidTitleException.class, () -> Title.create(" ", "A", null, BigDecimal.ONE));
        assertThrows(InvalidTitleException.class, () -> Title.create("A", "", null, BigDecimal.ONE));
        assertThrows(InvalidTitleException.class, () -> Title.create("A", "B", null, new BigDecimal("-1")));
        assertThrows(InvalidTitleException.class, () -> Title.create("A", "B", null, null));
    }
}
