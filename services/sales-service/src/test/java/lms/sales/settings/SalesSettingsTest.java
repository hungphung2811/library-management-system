package lms.sales.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import lms.sales.settings.application.SalesSettingsView;
import lms.sales.settings.application.UpdateSalesSettingsCommand;
import lms.sales.settings.domain.SalesSettings;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.support.Harness;
import org.junit.jupiter.api.Test;

class SalesSettingsTest {

    private final UUID tenant = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-17T00:00:00Z");

    @Test
    void defaultsMatchDecision() {
        var s = SalesSettings.defaults(tenant);
        assertEquals(500, s.vatRateBps());
        assertTrue(s.pricesIncludeVat());
        assertEquals(7, s.returnWindowDays());
        assertEquals(5, s.lowStockThreshold());
        assertEquals(0, s.fixedShippingFee());
        assertEquals(12, s.walkInPiiRetentionMonths());
        assertEquals("VND", s.currency());
        assertEquals(0, s.version());
    }

    private SalesSettings upd(int vat, int days, long ship, int low, int months) {
        return SalesSettings.defaults(tenant).update(vat, true, days, ship, low, months, now, UUID.randomUUID());
    }

    @Test
    void acceptsBoundaryValues() {
        upd(0, 0, 0, 0, 1);
        upd(10_000, 365, 100_000_000L, 100_000, 120);
    }

    @Test
    void rejectsOutOfRange() {
        for (Runnable r : new Runnable[] {
                () -> upd(-1, 7, 0, 5, 12), () -> upd(10_001, 7, 0, 5, 12),
                () -> upd(500, -1, 0, 5, 12), () -> upd(500, 366, 0, 5, 12),
                () -> upd(500, 7, -1, 5, 12), () -> upd(500, 7, 100_000_001L, 5, 12),
                () -> upd(500, 7, 0, -1, 12), () -> upd(500, 7, 0, 100_001, 12),
                () -> upd(500, 7, 0, 5, 0), () -> upd(500, 7, 0, 5, 121)}) {
            var e = assertThrows(SalesException.class, r::run);
            assertEquals(ErrorCode.INVALID_SETTINGS, e.code());
        }
    }

    @Test
    void useCaseDefaultsThenUpdatesWithOptimisticVersion() {
        var h = new Harness();
        SalesSettingsView v0 = h.getSettings.execute(h.tenant);
        assertEquals(500, v0.vatRateBps());
        assertEquals(0, v0.version());

        var saved = h.updateSettings.execute(new UpdateSalesSettingsCommand(h.tenant, h.user, 800, false, 14,
                15_000, 3, 24, 0));
        assertEquals(1, saved.version());
        assertEquals(800, h.getSettings.execute(h.tenant).vatRateBps());
        assertFalse(h.getSettings.execute(h.tenant).pricesIncludeVat());
        assertEquals(1, h.audits().size());

        // người thứ hai còn giữ version cũ (0)
        var e = assertThrows(SalesException.class, () -> h.updateSettings.execute(new UpdateSalesSettingsCommand(
                h.tenant, h.user, 100, true, 7, 0, 5, 12, 0)));
        assertEquals(ErrorCode.SETTINGS_VERSION_CONFLICT, e.code());
        assertEquals(800, h.getSettings.execute(h.tenant).vatRateBps());
    }

    @Test
    void invalidUpdateIsRejectedBeforeSaving() {
        var h = new Harness();
        var e = assertThrows(SalesException.class, () -> h.updateSettings.execute(new UpdateSalesSettingsCommand(
                h.tenant, h.user, 20_000, true, 7, 0, 5, 12, 0)));
        assertEquals(ErrorCode.INVALID_SETTINGS, e.code());
        assertEquals(0, h.getSettings.execute(h.tenant).version());
    }
}
