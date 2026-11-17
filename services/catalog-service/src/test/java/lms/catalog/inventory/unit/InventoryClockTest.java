package lms.catalog.inventory.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import lms.catalog.inventory.infrastructure.config.InventoryBeanConfiguration;
import org.junit.jupiter.api.Test;

/** Clock của catalog phải theo giờ Việt Nam, không theo múi giờ máy (container thường là UTC). */
class InventoryClockTest {

    @Test
    void clockDungMuiGioVietNam() throws Exception {
        Method method = InventoryBeanConfiguration.class.getDeclaredMethod("clock");
        method.setAccessible(true);
        Clock clock = (Clock) method.invoke(new InventoryBeanConfiguration());

        assertEquals(ZoneId.of("Asia/Ho_Chi_Minh"), clock.getZone());
        // 17:30 UTC ngày 16 đã là 00:30 ngày 17 giờ VN
        assertEquals(LocalDate.of(2026, 11, 17),
                LocalDate.ofInstant(Instant.parse("2026-11-16T17:30:00Z"), clock.getZone()));
    }
}
