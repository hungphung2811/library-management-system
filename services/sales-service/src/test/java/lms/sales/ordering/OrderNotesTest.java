package lms.sales.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lms.sales.ordering.application.PlaceOnlineOrderCommand;
import lms.sales.ordering.application.PlacePosOrderCommand;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.PriceCalculator;
import lms.sales.ordering.domain.Recipient;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.Rate;
import lms.sales.shared.domain.SalesException;
import lms.sales.support.Harness;
import org.junit.jupiter.api.Test;

/** customerNote, staffNote, expectedDeliveryDate của đơn: miền, use case, audit, ẩn danh. */
class OrderNotesTest {

    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    private static final PriceCalculator.TaxPolicy TAX = new PriceCalculator.TaxPolicy(Rate.ofBps(500), true);

    private final Harness h = new Harness();
    private final UUID customer = UUID.randomUUID();

    private static SalesOrder.LineDraft draft() {
        return new SalesOrder.LineDraft(UUID.randomUUID(), "Sách", "ISBN", Money.of(100_000), 1, null);
    }

    private static SalesOrder online(Fulfilment f, PaymentMethod m, String customerNote) {
        return SalesOrder.placeOnline(UUID.randomUUID(), "DH-2", UUID.randomUUID(), T0, m, f,
                new Recipient("An", "0900000000", "1 A, Quận 1, HCM"), List.of(draft()), TAX, UUID.randomUUID(),
                customerNote);
    }

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    @Test
    void donMoiKhongCoGhiChu() {
        SalesOrder o = online(Fulfilment.PICKUP, PaymentMethod.CASH, null);
        assertNull(o.customerNote());
        assertNull(o.staffNote());
        assertNull(o.expectedDeliveryDate());
    }

    @Test
    void ghiChuKhachDuocChuanHoaVaGioiHan500() {
        assertEquals("Gói quà giúp mình", online(Fulfilment.PICKUP, PaymentMethod.CASH, "  Gói quà giúp mình ")
                .customerNote());
        assertNull(online(Fulfilment.PICKUP, PaymentMethod.CASH, "   ").customerNote());
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> online(Fulfilment.PICKUP, PaymentMethod.CASH,
                "x".repeat(501))));
        assertEquals(500, online(Fulfilment.PICKUP, PaymentMethod.CASH, "x".repeat(500)).customerNote().length());
    }

    @Test
    void updateNotesChiChoPickupVaDelivery() {
        SalesOrder delivery = online(Fulfilment.DELIVERY, PaymentMethod.COD, null);
        delivery.updateNotes("  Gọi trước khi giao ", LocalDate.parse("2026-11-05"));
        assertEquals("Gọi trước khi giao", delivery.staffNote());
        assertEquals(LocalDate.parse("2026-11-05"), delivery.expectedDeliveryDate());
        delivery.updateNotes(null, null); // thay toàn bộ: null là xóa
        assertNull(delivery.staffNote());
        assertNull(delivery.expectedDeliveryDate());

        SalesOrder pos = SalesOrder.createPos(UUID.randomUUID(), "DH-1", UUID.randomUUID(), T0, PaymentMethod.CASH,
                null, null, null, List.of(draft()), null, TAX, UUID.randomUUID());
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> pos.updateNotes(null, LocalDate.parse("2026-11-05"))));
        pos.updateNotes("Khách quen", null); // ghi chú nội bộ vẫn được
        assertEquals("Khách quen", pos.staffNote());
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> delivery.updateNotes("x".repeat(501), null)));
    }

    @Test
    void fillMissingNotesChiDienTruongTrong() {
        SalesOrder o = online(Fulfilment.DELIVERY, PaymentMethod.COD, "Của khách");
        assertTrue(o.fillMissingNotes("Khác", "Nội bộ", LocalDate.parse("2026-11-04")));
        assertEquals("Của khách", o.customerNote()); // không ghi đè
        assertEquals("Nội bộ", o.staffNote());
        assertEquals(LocalDate.parse("2026-11-04"), o.expectedDeliveryDate());
        assertFalse(o.fillMissingNotes("Khác", "Khác nữa", LocalDate.parse("2026-12-01"))); // chạy lại không đổi
    }

    @Test
    void datDonOnlineVaPosKemGhiChuKhach() {
        UUID a = h.book("A", 100_000, null, 5);
        var online = h.tx(() -> h.placeOnline.place(new PlaceOnlineOrderCommand(h.tenant, customer,
                List.of(new PlaceOnlineOrderCommand.Line(a, 1)), Fulfilment.PICKUP, PaymentMethod.CASH,
                new Recipient("Nguyễn Văn An", "0912345678", null), UUID.randomUUID(), "Giao sau 18h")));
        assertEquals("Giao sau 18h", online.order().customerNote());
        assertNull(online.order().expectedDeliveryDate());
        var pos = h.tx(() -> h.placePos.create(new PlacePosOrderCommand(h.tenant, h.user,
                List.of(new PlacePosOrderCommand.Line(a, 1, null)), PaymentMethod.CASH, null, null, null, null,
                UUID.randomUUID(), null, "Bọc bìa giúp")));
        assertEquals("Bọc bìa giúp", pos.customerNote());
        assertFalse(online.toString().contains("Giao sau 18h"));
    }

    @Test
    void capNhatGhiChuQuaUseCaseKiemPhienBanVaAuditKhongLoNoiDung() {
        UUID a = h.book("A", 100_000, null, 5);
        var order = h.tx(() -> h.placeOnline.place(new PlaceOnlineOrderCommand(h.tenant, customer,
                List.of(new PlaceOnlineOrderCommand.Line(a, 1)), Fulfilment.DELIVERY, PaymentMethod.COD,
                new Recipient("Nguyễn Văn An", "0912345678", "12 Lê Lợi"), UUID.randomUUID()))).order();

        var notes = h.tx(() -> h.workflow.updateNotes(h.tenant, h.user, order.id(), "Khách VIP, không nghe máy 2 lần",
                LocalDate.parse("2026-11-20"), null));
        assertEquals("Khách VIP, không nghe máy 2 lần", notes.staffNote());
        assertEquals(LocalDate.parse("2026-11-20"), notes.expectedDeliveryDate());
        assertEquals(notes.staffNote(), h.workflow.notes(h.tenant, order.id()).staffNote());
        assertFalse(notes.toString().contains("VIP"));

        long stale = notes.version() + 5;
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> h.tx(() -> h.workflow.updateNotes(h.tenant, h.user,
                order.id(), "x", null, stale))));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, codeOf(() -> h.workflow.notes(h.tenant, UUID.randomUUID())));

        String audits = h.audits().toString();
        assertTrue(h.audits().stream().anyMatch(e -> "ORDER_NOTES_UPDATED".equals(e.action())));
        assertFalse(audits.contains("VIP") || audits.contains("không nghe máy") || audits.contains("2026-11-20"),
                "audit không chứa nội dung ghi chú: " + audits);
    }
}
