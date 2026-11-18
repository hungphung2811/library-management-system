package lms.support.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.support.notification.domain.AddressMasker;
import lms.support.notification.domain.Delivery;
import lms.support.notification.domain.DeliveryStatus;
import lms.support.notification.domain.InvalidTemplateException;
import lms.support.notification.domain.MessageTemplate;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;
import lms.support.notification.domain.TemplateParamNotAllowedException;
import lms.support.notification.domain.TemplateRenderer;
import lms.support.notification.infrastructure.messaging.EventNotificationConsumer;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Mẫu nội dung (D6), che địa chỉ, ánh xạ sự kiện sales/borrowing/fine/direct thành thông báo (D10). */
class NotificationTemplateAndMappingTest {

    private final NotificationKit kit = new NotificationKit();
    private final EventNotificationConsumer consumer = new EventNotificationConsumer(new ObjectMapper(), kit.enqueue);
    private final UUID tenant = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private final UUID customer = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    // ---- che địa chỉ ----

    @Test
    void cheDiaChiEmail() {
        assertThat(AddressMasker.maskEmail("nguyen.van.a@gmail.com")).isEqualTo("n***@gmail.com");
        assertThat(AddressMasker.maskEmail("a@b.vn")).isEqualTo("a***@b.vn");
        assertThat(AddressMasker.maskEmail(null)).isEqualTo("***");
        assertThat(AddressMasker.maskEmail("khong-co-a-cong")).isEqualTo("***");
        assertThat(AddressMasker.emailKey("A@B.vn")).isEqualTo(AddressMasker.emailKey(" a@b.vn ")).hasSize(64);
    }

    // ---- render ----

    @Test
    void renderThayThamSoVaBoXuongDongOTieuDe() {
        MessageTemplate t = new MessageTemplate("Đơn {{order_no}}\nBcc: x@y.z", "Chào {{ recipient_name }}, mã {{order_no}}",
                MessageTemplate.DEFAULT, 1);
        MessageTemplate out = TemplateRenderer.render(t, Map.of("order_no", "DH-1\r\nBcc: evil@x.com",
                "recipient_name", "An"));
        assertThat(out.subject()).doesNotContain("\n").doesNotContain("\r");
        assertThat(out.body()).isEqualTo("Chào An, mã DH-1\r\nBcc: evil@x.com");
    }

    @Test
    void thamSoThieuGiaTriThanhRongVaGiaTriKhongBiHieuLaMau() {
        MessageTemplate t = new MessageTemplate("S", "A {{order_no}} B {{grand_total}}", MessageTemplate.DEFAULT, 1);
        assertThat(TemplateRenderer.render(t, Map.of("order_no", "{{grand_total}} $1 \\")).body())
                .isEqualTo("A {{grand_total}} $1 \\ B ");
    }

    @Test
    void luuMauChanThamSoLaVaCuPhapSai() {
        assertThatThrownBy(() -> TemplateRenderer.validate(NotificationType.ORDER_PAID, "S {{password}}", "x"))
                .isInstanceOf(TemplateParamNotAllowedException.class).hasMessageContaining("password");
        assertThatThrownBy(() -> TemplateRenderer.validate(NotificationType.ORDER_PAID, "S", "x {{buyer_address}}"))
                .isInstanceOf(TemplateParamNotAllowedException.class);
        // tham số của loại khác không dùng được cho loại này
        assertThatThrownBy(() -> TemplateRenderer.validate(NotificationType.ORDER_SHIPPING, "S", "{{grand_total}}"))
                .isInstanceOf(TemplateParamNotAllowedException.class);
        assertThatThrownBy(() -> TemplateRenderer.validate(NotificationType.ORDER_PAID, "S {{order_no", "x"))
                .isInstanceOf(InvalidTemplateException.class);
        TemplateRenderer.validate(NotificationType.ORDER_PAID, "S {{order_no}}", "{{recipient_name}} {{tenant_name}}");
    }

    @Test
    void mauMacDinhTrongClasspathDayDuChoMoiLoaiVaChiDungThamSoChoPhep() {
        for (NotificationType type : NotificationType.values()) {
            MessageTemplate t = kit.defaults.find(type, NotificationChannel.EMAIL)
                    .orElseThrow(() -> new AssertionError("Thiếu mẫu mặc định " + type));
            assertThat(t.source()).isEqualTo(MessageTemplate.DEFAULT);
            assertThat(t.version()).isEqualTo(1);
            assertThat(t.subject()).isNotBlank().doesNotContain("\n");
            TemplateRenderer.validate(type, t.subject(), t.body());
            // tiêu đề được dùng làm nội dung thông báo trong ứng dụng nên không được phụ thuộc tên người nhận
            assertThat(TemplateRenderer.paramsOf(t.subject())).doesNotContain("recipient_name", "tenant_name");
        }
    }

    @Test
    void tenantGhiDeMauMoiLanLaPhienBanMoiVaChiTenantDoThayDoi() {
        UUID tenantB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        UUID admin = UUID.randomUUID();
        TenantContext.runAs(tenant, () -> {
            var v1 = kit.templates.save(NotificationType.ORDER_PAID, NotificationChannel.EMAIL, "Đã thu {{grand_total}}",
                    "Cảm ơn {{recipient_name}}", admin);
            var v2 = kit.templates.save(NotificationType.ORDER_PAID, NotificationChannel.EMAIL, "Đã thu tiền {{order_no}}",
                    "Cảm ơn bạn", admin);
            assertThat(v1.source()).isEqualTo("TENANT");
            assertThat(v1.version()).isEqualTo(1);
            assertThat(v2.version()).isEqualTo(2);
            var cur = kit.templates.get(NotificationType.ORDER_PAID, NotificationChannel.EMAIL).orElseThrow();
            assertThat(cur.subject()).isEqualTo("Đã thu tiền {{order_no}}");
            assertThatThrownBy(() -> kit.templates.save(NotificationType.ORDER_PAID, NotificationChannel.EMAIL,
                    "x {{secret}}", "y", admin)).isInstanceOf(TemplateParamNotAllowedException.class);
            assertThat(kit.templates.get(NotificationType.ORDER_PAID, NotificationChannel.EMAIL).orElseThrow().version())
                    .as("lưu lỗi không đổi mẫu hiện hành").isEqualTo(2);
            var preview = kit.templates.preview(NotificationType.ORDER_PAID, NotificationChannel.EMAIL, null, null);
            assertThat(preview.subject()).isEqualTo("Đã thu tiền [order_no]");
        });
        TenantContext.runAs(tenantB, () -> {
            var other = kit.templates.get(NotificationType.ORDER_PAID, NotificationChannel.EMAIL).orElseThrow();
            assertThat(other.source()).isEqualTo("DEFAULT");
        });
    }

    @Test
    void thuDungMauGhiDeCuaTenantVaGhiNhanNguonVaPhienBan() {
        UUID user = UUID.randomUUID();
        kit.directory.add(user, "kh@example.com", "Khách", true);
        TenantContext.runAs(tenant, () -> kit.templates.save(NotificationType.ORDER_PAID, NotificationChannel.EMAIL,
                "Tiền đã về {{order_no}}", "Alo {{recipient_name}} từ {{tenant_name}}", UUID.randomUUID()));
        consumer.onSales(salesEvent("OrderPaid", UUID.randomUUID(), tenant,
                "{\"customerUserId\":\"" + user + "\",\"orderNo\":\"DH-7\",\"channel\":\"ONLINE\",\"grandTotal\":125000}"));
        kit.dispatch.dispatchTenant(tenant, 100);
        assertThat(kit.provider.messages).hasSize(1);
        assertThat(kit.provider.messages.get(0).subject()).isEqualTo("Tiền đã về DH-7");
        assertThat(kit.provider.messages.get(0).body()).isEqualTo("Alo Khách từ Hiệu sách Mẫu");
        Delivery d = kit.deliveries.all(tenant).get(0);
        assertThat(d.templateSource()).isEqualTo("TENANT");
        assertThat(d.templateVersion()).isEqualTo(1);
        assertThat(d.recipientAddressMasked()).isEqualTo("k***@example.com");
    }

    // ---- ánh xạ sự kiện ----

    private static String salesEvent(String eventType, UUID eventId, UUID tenantId, String dataJson) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType + "\",\"aggregateId\":\"" + UUID.randomUUID()
                + "\",\"occurredAt\":\"2026-11-19T03:00:00Z\",\"version\":1,"
                + (tenantId == null ? "" : "\"tenantId\":\"" + tenantId + "\",")
                + "\"payload\":{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType
                + "\",\"action\":\"X\",\"aggregateType\":\"SalesOrder\",\"data\":" + dataJson + "}}";
    }

    private List<Delivery> all() {
        return kit.deliveries.all(tenant);
    }

    @Test
    void donOnlineSinhThongBaoTheoLoaiVaChiMangThamSoChoPhep() {
        String data = "{\"customerUserId\":\"" + customer + "\",\"orderNo\":\"DH-000123\",\"channel\":\"ONLINE\","
                + "\"grandTotal\":1250000,\"shippingAddress\":\"12 Phố Cổ\",\"phone\":\"0909\",\"note\":\"bí mật\","
                + "\"cancelKind\":\"EXPIRED\"}";
        for (String type : new String[] {"OrderPlaced", "OrderPaid", "OrderReadyForPickup", "OrderShipping",
                "OrderCompleted", "OrderCancelled"}) {
            consumer.onSales(salesEvent(type, UUID.randomUUID(), tenant, data));
        }
        assertThat(all()).extracting(Delivery::type).containsExactlyInAnyOrder(NotificationType.ORDER_PLACED,
                NotificationType.ORDER_PAID, NotificationType.ORDER_READY_FOR_PICKUP, NotificationType.ORDER_SHIPPING,
                NotificationType.ORDER_COMPLETED, NotificationType.ORDER_CANCELLED);
        Delivery placed = all().stream().filter(d -> d.type() == NotificationType.ORDER_PLACED).findFirst().get();
        assertThat(placed.params()).containsEntry("order_no", "DH-000123").containsEntry("grand_total", "1.250.000 đ");
        Delivery cancelled = all().stream().filter(d -> d.type() == NotificationType.ORDER_CANCELLED).findFirst().get();
        assertThat(cancelled.params()).containsEntry("cancel_reason", "do hết thời hạn giữ hàng");
        // dữ liệu cá nhân của payload không được lọt vào delivery
        assertThat(all().toString() + all().stream().map(Delivery::params).toList())
                .doesNotContain("Phố Cổ", "0909", "bí mật");
        all().forEach(d -> assertThat(d.recipientUserId()).isEqualTo(customer));
        // thông báo trong ứng dụng cũng được ghi (tiêu đề mẫu)
        TenantContext.runAs(tenant, () -> assertThat(kit.inApp.countUnread(customer)).isEqualTo(6));
    }

    @Test
    void donPosVaSuKienThieuKhachHoacLoaiKhacBiBoQua() {
        consumer.onSales(salesEvent("OrderCompleted", UUID.randomUUID(), tenant,
                "{\"customerUserId\":\"" + customer + "\",\"orderNo\":\"DH-1\",\"channel\":\"POS\"}"));
        consumer.onSales(salesEvent("OrderPaid", UUID.randomUUID(), tenant, "{\"orderNo\":\"DH-1\",\"channel\":\"ONLINE\"}"));
        consumer.onSales(salesEvent("StockAdjusted", UUID.randomUUID(), tenant,
                "{\"customerUserId\":\"" + customer + "\"}"));
        consumer.onSales(salesEvent("OrderPaid", UUID.randomUUID(), null,
                "{\"customerUserId\":\"" + customer + "\",\"channel\":\"ONLINE\"}"));
        consumer.onSales("không phải json");
        consumer.onSales("{\"eventId\":\"khong-uuid\",\"eventType\":\"OrderPaid\",\"tenantId\":\"" + tenant + "\"}");
        assertThat(all()).isEmpty();
    }

    @Test
    void hoanTienVaHoaDonGuiKeCaDonPosVaKhongBatBuocChannel() {
        consumer.onSales(salesEvent("RefundCompleted", UUID.randomUUID(), tenant,
                "{\"customerUserId\":\"" + customer + "\",\"orderNo\":\"DH-9\",\"channel\":\"POS\",\"amount\":50000}"));
        consumer.onSales(salesEvent("InvoiceIssued", UUID.randomUUID(), tenant,
                "{\"customerUserId\":\"" + customer + "\",\"orderNo\":\"DH-9\",\"invoiceNo\":\"C26TAA-000007\","
                        + "\"grandTotal\":50000}"));
        Delivery refund = all().stream().filter(d -> d.type() == NotificationType.REFUND_COMPLETED).findFirst().get();
        assertThat(refund.params()).containsEntry("amount", "50.000 đ").containsEntry("order_no", "DH-9");
        Delivery invoice = all().stream().filter(d -> d.type() == NotificationType.INVOICE_ISSUED).findFirst().get();
        assertThat(invoice.params()).containsEntry("invoice_no", "C26TAA-000007")
                .containsEntry("issued_at", "19/11/2026"); // lấy từ occurredAt (UTC+7 vẫn cùng ngày)
    }

    @Test
    void nhacHanVaPhatMoiTuCirculation() {
        UUID e1 = UUID.randomUUID();
        String due = "{\"eventId\":\"" + e1 + "\",\"eventType\":\"LoanDueSoon\",\"aggregateId\":\"b\",\"version\":1,"
                + "\"tenantId\":\"" + tenant + "\",\"payload\":{\"borrowingId\":\"" + UUID.randomUUID()
                + "\",\"readerId\":\"" + customer + "\",\"dueDate\":\"2026-11-21\",\"daysBefore\":2,\"itemCount\":3}}";
        consumer.onBorrowing(due);
        consumer.onBorrowing(due);
        consumer.onBorrowing("{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"LoanOverdueReminder\","
                + "\"tenantId\":\"" + tenant + "\",\"payload\":{\"readerId\":\"" + customer
                + "\",\"dueDate\":\"2026-11-10\",\"daysOverdue\":9,\"reminderNo\":2,\"maxReminders\":3,\"itemCount\":1}}");
        consumer.onBorrowing("{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"BookBorrowedEvent\","
                + "\"tenantId\":\"" + tenant + "\",\"payload\":{\"userId\":\"" + customer + "\"}}");
        consumer.onFine("{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"FineIssuedEvent\",\"tenantId\":\""
                + tenant + "\",\"payload\":{\"readerId\":\"" + customer + "\",\"reason\":\"LATE_RETURN\",\"amount\":15000}}");

        assertThat(all()).hasSize(3);
        Delivery soon = all().stream().filter(d -> d.type() == NotificationType.LOAN_DUE_SOON).findFirst().get();
        assertThat(soon.params()).containsEntry("due_date", "21/11/2026").containsEntry("days_before", "2")
                .containsEntry("item_count", "3");
        Delivery overdue = all().stream().filter(d -> d.type() == NotificationType.LOAN_OVERDUE).findFirst().get();
        assertThat(overdue.params()).containsEntry("days_overdue", "9").containsEntry("reminder_no", "2")
                .containsEntry("max_reminders", "3");
        Delivery fine = all().stream().filter(d -> d.type() == NotificationType.FINE_ISSUED).findFirst().get();
        assertThat(fine.params()).containsEntry("amount", "15.000 đ").containsEntry("reason", "trả sách trễ hạn");
    }

    @Test
    void notificationDirectDungEmailTrongPayloadVaKhongTraDiaChiRaNgoai() {
        UUID e = UUID.randomUUID();
        String msg = "{\"eventId\":\"" + e + "\",\"tenantId\":\"" + tenant + "\",\"notificationType\":\"INVOICE_ISSUED\","
                + "\"recipientEmail\":\"khach.le@example.com\",\"params\":{\"invoiceNo\":\"C26TAA-000009\","
                + "\"orderNo\":\"DH-5\",\"grandTotal\":99000,\"issuedAt\":\"2026-11-19T01:00:00Z\",\"buyerTaxCode\":\"0101\"}}";
        consumer.onDirect(msg);
        consumer.onDirect(msg);
        assertThat(all()).hasSize(1);
        Delivery d = all().get(0);
        assertThat(d.recipientUserId()).isNull();
        assertThat(d.params()).containsEntry("invoice_no", "C26TAA-000009").containsEntry("grand_total", "99.000 đ")
                .containsEntry("issued_at", "19/11/2026").doesNotContainKey("buyer_tax_code");
        kit.dispatch.dispatchTenant(tenant, 100);
        assertThat(kit.provider.sentTo).containsExactly("khach.le@example.com");
        assertThat(d.status()).isEqualTo(DeliveryStatus.SENT);
        // khách lẻ không có tài khoản nên không có thông báo trong ứng dụng
        TenantContext.runAs(tenant, () -> assertThat(kit.inApp.countUnread(customer)).isZero());
    }

    @Test
    void notificationDirectEmailHongHoacLoaiLaBiBoQua() {
        for (String email : new String[] {"khong-phai-email", "a@b", "x y@example.com", ""}) {
            consumer.onDirect("{\"eventId\":\"" + UUID.randomUUID() + "\",\"tenantId\":\"" + tenant
                    + "\",\"notificationType\":\"INVOICE_ISSUED\",\"recipientEmail\":\"" + email + "\"}");
        }
        consumer.onDirect("{\"eventId\":\"" + UUID.randomUUID() + "\",\"tenantId\":\"" + tenant
                + "\",\"notificationType\":\"KHONG_CO\",\"recipientEmail\":\"a@example.com\"}");
        consumer.onDirect("{\"eventId\":\"" + UUID.randomUUID() + "\",\"notificationType\":\"INVOICE_ISSUED\","
                + "\"recipientEmail\":\"a@example.com\"}"); // thiếu tenantId
        assertThat(all()).isEmpty();
    }
}
