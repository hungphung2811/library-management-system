package lms.sales.shared.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyAndRoundingTest {

    @Test
    void divHalfUp() {
        assertEquals(1, Rounding.divHalfUp(1, 2));
        assertEquals(0, Rounding.divHalfUp(1, 3));
        assertEquals(1, Rounding.divHalfUp(2, 3));
        assertEquals(2, Rounding.divHalfUp(5, 3));
        assertEquals(3, Rounding.divHalfUp(5, 2));
        assertEquals(0, Rounding.divHalfUp(0, 7));
    }

    @Test
    void moneyRejectsNegativeAndOverflow() {
        assertThrows(SalesException.class, () -> Money.of(-1));
        assertThrows(SalesException.class, () -> Money.of(5).minus(Money.of(6)));
        assertThrows(SalesException.class, () -> Money.of(Long.MAX_VALUE).times(2));
        assertThrows(SalesException.class, () -> Money.of(Long.MAX_VALUE).plus(Money.of(1)));
    }

    @Test
    void coverPriceConvertedToDongHalfUp() {
        assertEquals(85_000, Money.fromCoverPrice(new BigDecimal("85000.00")).amount());
        assertEquals(85_001, Money.fromCoverPrice(new BigDecimal("85000.50")).amount());
        assertEquals(85_000, Money.fromCoverPrice(new BigDecimal("85000.49")).amount());
        assertEquals("VND", Money.CURRENCY);
    }

    @Test
    void rateBounds() {
        assertEquals(5, Rate.ofBps(500).percentOf(100));
        assertThrows(SalesException.class, () -> Rate.ofBps(-1));
        assertThrows(SalesException.class, () -> Rate.ofBps(10_001));
    }
}
