package lms.sales.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.contact.domain.ContactTopic;
import lms.sales.contact.domain.ShopContactRequest;
import lms.sales.contact.infrastructure.web.ShopContactViews;
import lms.sales.ordering.application.OrderResponse;
import lms.sales.ordering.application.PlacePosOrderCommand;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.shared.application.PersonalDataVisibility;
import lms.sales.support.Harness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Cấu hình lms.privacy.mask-personal-data: mặc định che, tắt thì trả giá trị gốc (đơn và liên hệ). */
class PersonalDataVisibilityTest {

    @AfterEach
    void restoreDefault() {
        PersonalDataVisibility.configure(true);
    }

    @Test
    void defaultIsMasking() {
        assertTrue(PersonalDataVisibility.isMasking());
        assertEquals("N***** V** A*", PersonalDataVisibility.name("Nguyễn Văn An"));
        assertEquals("*******678", PersonalDataVisibility.phone("0912345678"));
        assertEquals("b***@example.vn", PersonalDataVisibility.email("binh@example.vn"));
        assertEquals("***, Quận 1, TP HCM", PersonalDataVisibility.address("12 Lê Lợi, Quận 1, TP HCM"));
    }

    @Test
    void disabledReturnsOriginalValuesAndNullForBlank() {
        PersonalDataVisibility.configure(false);
        assertEquals("Nguyễn Văn An", PersonalDataVisibility.name("Nguyễn Văn An"));
        assertEquals("0912345678", PersonalDataVisibility.phone("0912345678"));
        assertEquals("binh@example.vn", PersonalDataVisibility.email("binh@example.vn"));
        assertEquals("12 Lê Lợi, Quận 1, TP HCM", PersonalDataVisibility.address("12 Lê Lợi, Quận 1, TP HCM"));
        assertNull(PersonalDataVisibility.name(" "));
        assertNull(PersonalDataVisibility.phone(null));
    }

    private OrderResponse placePos(Harness h) {
        UUID a = h.book("A", 1_000, null, 5);
        return h.tx(() -> h.placePos.create(new PlacePosOrderCommand(h.tenant, h.user,
                List.of(new PlacePosOrderCommand.Line(a, 1, null)), PaymentMethod.CASH, null, "Nguyễn Văn An",
                "0912345678", null, UUID.randomUUID())));
    }

    @Test
    void orderResponseMaskedByDefaultAndFullWhenDisabled() {
        Harness h = new Harness();
        OrderResponse masked = placePos(h);
        assertEquals("N***** V** A*", masked.walkInNameMasked());
        assertEquals("*******678", masked.walkInPhoneMasked());

        PersonalDataVisibility.configure(false);
        OrderResponse full = placePos(new Harness());
        assertEquals("Nguyễn Văn An", full.walkInNameMasked());
        assertEquals("0912345678", full.walkInPhoneMasked());
        assertFalse(full.toString().isEmpty());
    }

    @Test
    void contactViewsMaskedByDefaultAndFullWhenDisabled() {
        ShopContactRequest r = ShopContactRequest.submit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ContactTopic.ORDER, "Nguyễn Văn Bình", "binh@example.vn", "0912345678", "Nội dung", true,
                Instant.parse("2026-11-17T03:00:00Z"));
        ShopContactViews.SummaryView masked = ShopContactViews.SummaryView.from(r);
        assertEquals("N***** V** B***", masked.contact().name());
        assertEquals("b***@example.vn", masked.contact().email());
        assertTrue(masked.contact().phone().startsWith("*"));

        PersonalDataVisibility.configure(false);
        ShopContactViews.SummaryView sv = ShopContactViews.SummaryView.from(r);
        ShopContactViews.DetailView dv = ShopContactViews.DetailView.from(r);
        assertEquals("Nguyễn Văn Bình", sv.contact().name());
        assertEquals("binh@example.vn", dv.contact().email());
        assertEquals(r.contactPhone(), dv.contact().phone());
        assertEquals("MaskedContact[***]", dv.contact().toString()); // toString vẫn không lộ
    }
}
