package lms.support.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.support.notification.application.DeliveryPort.DeliveryCriteria;
import lms.support.notification.application.DispatchNotificationsUseCase;
import lms.support.notification.application.NotificationProvider.PermanentSendException;
import lms.support.notification.application.NotificationProvider.TransientSendException;
import lms.support.notification.application.NotificationSettingsUseCases.ChannelSettingChange;
import lms.support.notification.application.NotificationSettingsUseCases.PreferenceChange;
import lms.support.notification.application.NotificationTrigger;
import lms.support.notification.domain.Delivery;
import lms.support.notification.domain.DeliveryNotRetryableException;
import lms.support.notification.domain.DeliveryStatus;
import lms.support.notification.domain.NotificationCategory;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;
import lms.support.notification.domain.PreferenceMandatoryException;
import lms.support.notification.domain.RetryPolicy;
import lms.support.notification.domain.SkipReason;
import lms.support.notification.infrastructure.messaging.ReservationReadyConsumer;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-0030 gói C: khử trùng khi Kafka giao lại, thử lại có lùi thời gian rồi DEAD và gửi lại, giới hạn tốc độ theo
 * tenant và toàn cục, quyết định gửi (provider, kênh, tùy chọn, địa chỉ), cách ly tenant.
 */
class NotificationPipelineTest {

    private final NotificationKit kit = new NotificationKit();
    private final UUID tenantA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private final UUID tenantB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private final UUID reader = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private NotificationTrigger readerEvent(UUID eventId) {
        return NotificationTrigger.forUser(eventId, "ReservationReadyEvent", NotificationType.RESERVATION_READY, reader,
                Map.of("hold_until", "04/01/2026"));
    }

    private void enqueue(UUID tenant, NotificationTrigger t) {
        TenantContext.runAs(tenant, () -> kit.enqueue.execute(t));
    }

    private List<Delivery> deliveries(UUID tenant) {
        return kit.deliveries.all(tenant);
    }

    // ---- khử trùng ----

    @Test
    void giaoLaiCungMessageBaLanChiRaMotDeliveryVaMotThu() throws Exception {
        kit.directory.add(reader, "doc.gia@example.com", "Độc Giả", true);
        ReservationReadyConsumer consumer = new ReservationReadyConsumer(new ObjectMapper(), kit.enqueue);
        UUID eventId = UUID.randomUUID();
        String msg = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"ReservationReadyEvent\",\"aggregateId\":\"x\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"version\":1,\"tenantId\":\"" + tenantA + "\","
                + "\"payload\":{\"readerId\":\"" + reader + "\",\"holdUntil\":\"2026-01-04\"}}";
        consumer.onEvent(msg);
        consumer.onEvent(msg);
        consumer.onEvent(msg);

        assertThat(deliveries(tenantA)).hasSize(1);
        assertThat(kit.dispatch.dispatchTenant(tenantA, 100)).isEqualTo(1);
        // Dispatcher chạy thêm lần nữa hay Kafka giao tiếp thì vẫn một thư
        consumer.onEvent(msg);
        assertThat(kit.dispatch.dispatchTenant(tenantA, 100)).isZero();
        assertThat(kit.provider.sentTo).containsExactly("doc.gia@example.com");
        assertThat(deliveries(tenantA).get(0).status()).isEqualTo(DeliveryStatus.SENT);
        assertThat(kit.provider.messages.get(0).body()).contains("Độc Giả", "Hiệu sách Mẫu", "04/01/2026");
    }

    @Test
    void khoaKhuTrungTheoSuKienKenhVaNguoiNhan() {
        UUID e = UUID.randomUUID();
        enqueue(tenantA, readerEvent(e));
        enqueue(tenantA, NotificationTrigger.forUser(e, "X", NotificationType.RESERVATION_READY, UUID.randomUUID(),
                Map.of()));
        enqueue(tenantA, readerEvent(UUID.randomUUID()));
        enqueue(tenantB, readerEvent(e)); // cùng eventId nhưng tenant khác: khóa kèm tenant
        assertThat(deliveries(tenantA)).hasSize(3);
        assertThat(deliveries(tenantB)).hasSize(1);
    }

    @Test
    void nguoiNhanTrucTiepKhuTrungTheoEmailVaXoaDiaChiKhiKetThuc() {
        UUID e = UUID.randomUUID();
        NotificationTrigger t = NotificationTrigger.forAddress(e, "NotificationDirect", NotificationType.INVOICE_ISSUED,
                "Khach.Le@Example.com", Map.of("invoice_no", "C26TAA-000001"));
        enqueue(tenantA, t);
        enqueue(tenantA, NotificationTrigger.forAddress(e, "NotificationDirect", NotificationType.INVOICE_ISSUED,
                " khach.le@example.com ", Map.of("invoice_no", "C26TAA-000001")));
        assertThat(deliveries(tenantA)).hasSize(1);
        Delivery d = deliveries(tenantA).get(0);
        assertThat(d.recipientAddress()).isEqualTo("Khach.Le@Example.com");
        assertThat(d.recipientAddressMasked()).isEqualTo("K***@Example.com").doesNotContain("Khach");

        kit.dispatch.dispatchTenant(tenantA, 100);
        assertThat(d.status()).isEqualTo(DeliveryStatus.SENT);
        assertThat(d.recipientAddress()).as("địa chỉ đầy đủ phải bị xóa khi kết thúc").isNull();
        assertThat(kit.provider.sentTo).containsExactly("Khach.Le@Example.com");
    }

    // ---- thử lại và DEAD ----

    @Test
    void loiTamThoiThuLaiDungLichRoiDeadVaQuanTriGuiLaiDuoc() {
        kit.directory.add(reader, "a@example.com", "A", true);
        enqueue(tenantA, readerEvent(UUID.randomUUID()));
        Delivery d = deliveries(tenantA).get(0);
        Duration[] expected = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
                Duration.ofHours(2), Duration.ofHours(6)};

        for (int i = 0; i < 5; i++) {
            kit.provider.failures.add(new TransientSendException("SMTP_4XX", null));
            assertThat(kit.dispatch.dispatchTenant(tenantA, 100)).isZero();
            assertThat(d.status()).isEqualTo(DeliveryStatus.RETRY);
            assertThat(d.attempts()).isEqualTo(i + 1);
            assertThat(d.lastErrorCode()).isEqualTo("SMTP_4XX");
            assertThat(d.nextAttemptAt()).isEqualTo(kit.clock.instant().plus(expected[i]));
            // Chưa đến hạn thì dispatcher không lấy
            int callsBefore = kit.provider.calls.get();
            kit.dispatch.dispatchTenant(tenantA, 100);
            assertThat(kit.provider.calls.get()).isEqualTo(callsBefore);
            kit.clock.advance(expected[i]);
        }
        // Lần thất bại thứ 6 (1 lần đầu + 5 lần thử lại) thì DEAD
        kit.provider.failures.add(new TransientSendException("TIMEOUT", null));
        kit.dispatch.dispatchTenant(tenantA, 100);
        assertThat(d.status()).isEqualTo(DeliveryStatus.DEAD);
        assertThat(d.attempts()).isEqualTo(6);
        assertThat(d.recipientAddress()).isNull();

        // Chỉ DEAD mới gửi lại được; gửi lại mở đợt thử mới và giữ attempts
        UUID admin = UUID.randomUUID();
        TenantContext.runAs(tenantA, () -> {
            var view = kit.deliveryUseCases.retry(d.id(), admin);
            assertThat(view.status()).isEqualTo("PENDING");
            assertThat(view.lastRetryBy()).isEqualTo(admin);
            assertThat(view.attempts()).isEqualTo(6);
            assertThatThrownBy(() -> kit.deliveryUseCases.retry(d.id(), admin))
                    .isInstanceOf(DeliveryNotRetryableException.class);
        });
        assertThat(kit.dispatch.dispatchTenant(tenantA, 100)).isEqualTo(1);
        assertThat(d.status()).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    void sauKhiGuiLaiMatLuotThuMoiVanCoDayDuNamLanThuLai() {
        kit.directory.add(reader, "a@example.com", "A", true);
        enqueue(tenantA, readerEvent(UUID.randomUUID()));
        Delivery d = deliveries(tenantA).get(0);
        for (int i = 0; i < 6; i++) {
            kit.provider.failures.add(new TransientSendException("SMTP_4XX", null));
            kit.dispatch.dispatchTenant(tenantA, 100);
            kit.clock.advance(Duration.ofHours(7));
        }
        assertThat(d.status()).isEqualTo(DeliveryStatus.DEAD);
        TenantContext.runAs(tenantA, () -> kit.deliveryUseCases.retry(d.id(), UUID.randomUUID()));
        kit.provider.failures.add(new TransientSendException("SMTP_4XX", null));
        kit.dispatch.dispatchTenant(tenantA, 100);
        assertThat(d.status()).as("đợt mới được thử lại, không chết ngay").isEqualTo(DeliveryStatus.RETRY);
    }

    @Test
    void loiVinhVienLaDeadNgayKhongThuLai() {
        kit.directory.add(reader, "sai@example.com", "A", true);
        enqueue(tenantA, readerEvent(UUID.randomUUID()));
        kit.provider.failures.add(new PermanentSendException("SMTP_5XX", null));
        kit.dispatch.dispatchTenant(tenantA, 100);
        Delivery d = deliveries(tenantA).get(0);
        assertThat(d.status()).isEqualTo(DeliveryStatus.DEAD);
        assertThat(d.attempts()).isEqualTo(1);
        assertThat(d.lastErrorCode()).isEqualTo("SMTP_5XX");
    }

    @Test
    void identityKhongTraLoiThiThuLaiVoiMaLoiRieng() {
        enqueue(tenantA, readerEvent(UUID.randomUUID()));
        kit.directory.down = true;
        kit.dispatch.dispatchTenant(tenantA, 100);
        Delivery d = deliveries(tenantA).get(0);
        assertThat(d.status()).isEqualTo(DeliveryStatus.RETRY);
        assertThat(d.lastErrorCode()).isEqualTo("RECIPIENT_LOOKUP_FAILED");
        assertThat(kit.provider.calls.get()).isZero();
    }

    @Test
    void loiKhongLuongTruocDuocChanLaiThanhThuLaiKhongLamHongCaLo() {
        UUID other = UUID.randomUUID();
        kit.directory.add(reader, "a@example.com", "A", true);
        kit.directory.add(other, "b@example.com", "B", true);
        enqueue(tenantA, readerEvent(UUID.randomUUID()));
        enqueue(tenantA, NotificationTrigger.forUser(UUID.randomUUID(), "X", NotificationType.RESERVATION_READY, other,
                Map.of()));
        kit.provider.failures.add(new IllegalStateException("boom"));
        kit.dispatch.dispatchTenant(tenantA, 100);
        assertThat(deliveries(tenantA)).extracting(Delivery::status)
                .containsExactlyInAnyOrder(DeliveryStatus.RETRY, DeliveryStatus.SENT);
        assertThat(deliveries(tenantA).stream().filter(x -> x.status() == DeliveryStatus.RETRY).findFirst().get()
                .lastErrorCode()).isEqualTo("UNEXPECTED_ERROR");
    }

    @Test
    void dongKetSendingQuaNamPhutDuocDuaLaiRetry() {
        kit.directory.add(reader, "a@example.com", "A", true);
        enqueue(tenantA, readerEvent(UUID.randomUUID()));
        Delivery d = deliveries(tenantA).get(0);
        TenantContext.runAs(tenantA, () -> kit.tx.lease(10, kit.clock.instant())); // instance chết sau khi lấy lô
        assertThat(d.status()).isEqualTo(DeliveryStatus.SENDING);
        kit.dispatch.dispatchTenant(tenantA, 100);
        assertThat(d.status()).isEqualTo(DeliveryStatus.SENDING);
        kit.clock.advance(Duration.ofMinutes(6));
        assertThat(kit.dispatch.dispatchTenant(tenantA, 100)).isEqualTo(1);
        assertThat(d.status()).isEqualTo(DeliveryStatus.SENT);
    }

    // ---- giới hạn tốc độ ----

    @Test
    void gioiHanTheoTenantGiuPendingChoLuotSau() {
        NotificationKit k = new NotificationKit(new DispatchNotificationsUseCase.Settings(50, 3, 600,
                Duration.ofMinutes(5), RetryPolicy.DEFAULT), true);
        for (int i = 0; i < 5; i++) {
            UUID u = UUID.randomUUID();
            k.directory.add(u, "u" + i + "@example.com", "U" + i, true);
            TenantContext.runAs(tenantA, () -> k.enqueue.execute(NotificationTrigger.forUser(UUID.randomUUID(), "X",
                    NotificationType.RESERVATION_READY, u, Map.of())));
        }
        assertThat(k.dispatch.dispatchTenant(tenantA, 100)).isEqualTo(3);
        assertThat(k.dispatch.dispatchTenant(tenantA, 100)).as("đã chạm 3 thư/phút").isZero();
        assertThat(k.deliveries.all(tenantA)).filteredOn(d -> d.status() == DeliveryStatus.PENDING).hasSize(2);

        k.clock.advance(Duration.ofSeconds(61));
        assertThat(k.dispatch.dispatchTenant(tenantA, 100)).isEqualTo(2);
        assertThat(k.deliveries.all(tenantA)).allMatch(d -> d.status() == DeliveryStatus.SENT);
    }

    @Test
    void gioiHanTenantNaoTenantDoKhongAnhHuongTenantKhac() {
        NotificationKit k = new NotificationKit(new DispatchNotificationsUseCase.Settings(50, 1, 600,
                Duration.ofMinutes(5), RetryPolicy.DEFAULT), true);
        UUID ua = UUID.randomUUID();
        UUID ub = UUID.randomUUID();
        k.directory.add(ua, "a@example.com", "A", true);
        k.directory.add(ub, "b@example.com", "B", true);
        for (int i = 0; i < 3; i++) {
            TenantContext.runAs(tenantA, () -> k.enqueue.execute(NotificationTrigger.forUser(UUID.randomUUID(), "X",
                    NotificationType.RESERVATION_READY, ua, Map.of())));
        }
        TenantContext.runAs(tenantB, () -> k.enqueue.execute(NotificationTrigger.forUser(UUID.randomUUID(), "X",
                NotificationType.RESERVATION_READY, ub, Map.of())));
        assertThat(k.dispatch.dispatchAll()).as("mỗi tenant được 1 thư").isEqualTo(2);
        assertThat(k.deliveries.all(tenantB)).allMatch(d -> d.status() == DeliveryStatus.SENT);
    }

    @Test
    void gioiHanToanCucDungLayThemKhiDatTran() {
        NotificationKit k = new NotificationKit(new DispatchNotificationsUseCase.Settings(50, 60, 2,
                Duration.ofMinutes(5), RetryPolicy.DEFAULT), true);
        for (UUID tenant : new UUID[] {tenantA, tenantB}) {
            for (int i = 0; i < 3; i++) {
                UUID u = UUID.randomUUID();
                k.directory.add(u, "u" + i + tenant.toString().charAt(35) + "@example.com", "U", true);
                TenantContext.runAs(tenant, () -> k.enqueue.execute(NotificationTrigger.forUser(UUID.randomUUID(), "X",
                        NotificationType.RESERVATION_READY, u, Map.of())));
            }
        }
        assertThat(k.dispatch.dispatchAll()).isEqualTo(2);
        assertThat(k.dispatch.dispatchAll()).as("đã đủ 2 thư/phút toàn hệ thống").isZero();
        k.clock.advance(Duration.ofSeconds(61));
        assertThat(k.dispatch.dispatchAll()).isEqualTo(2);
    }

    // ---- quyết định gửi ----

    @Test
    void khongCoProviderThiSkippedNoProvider() {
        NotificationKit k = new NotificationKit(DispatchNotificationsUseCase.Settings.defaults(), false);
        TenantContext.runAs(tenantA, () -> k.enqueue.execute(readerEvent(UUID.randomUUID())));
        Delivery d = k.deliveries.all(tenantA).get(0);
        assertThat(d.status()).isEqualTo(DeliveryStatus.SKIPPED);
        assertThat(d.skipReason()).isEqualTo(SkipReason.NO_PROVIDER);
    }

    @Test
    void tenantTatKenhThiSkippedChannelDisabledVaChiTenantDo() {
        TenantContext.runAs(tenantA, () -> kit.settings.updateChannels(
                List.of(new ChannelSettingChange(NotificationType.RESERVATION_READY, NotificationChannel.EMAIL, false)),
                UUID.randomUUID()));
        enqueue(tenantA, readerEvent(UUID.randomUUID()));
        enqueue(tenantB, readerEvent(UUID.randomUUID()));
        assertThat(deliveries(tenantA).get(0).skipReason()).isEqualTo(SkipReason.CHANNEL_DISABLED);
        assertThat(deliveries(tenantB).get(0).status()).isEqualTo(DeliveryStatus.PENDING);
        // Thông báo trong ứng dụng vẫn ghi dù kênh email bị tắt
        TenantContext.runAs(tenantA, () -> assertThat(kit.inApp.countUnread(reader)).isEqualTo(1));
    }

    @Test
    void nguoiDungTatNhomThiSkippedOptedOutNhungKhongTatDuocNhomBatBuoc() {
        TenantContext.runAs(tenantA, () -> {
            kit.settings.updatePreferences(reader, List.of(
                    new PreferenceChange(NotificationCategory.RESERVATION, NotificationChannel.EMAIL, false)));
            assertThatThrownBy(() -> kit.settings.updatePreferences(reader, List.of(
                    new PreferenceChange(NotificationCategory.ACCOUNT, NotificationChannel.EMAIL, false))))
                    .isInstanceOf(PreferenceMandatoryException.class);
            assertThatThrownBy(() -> kit.settings.updatePreferences(reader, List.of(
                    new PreferenceChange(NotificationCategory.REFUND, NotificationChannel.EMAIL, false))))
                    .isInstanceOf(PreferenceMandatoryException.class);
            // Cả yêu cầu bị từ chối thì không ghi gì: nhóm hợp lệ đi kèm cũng không đổi
            assertThatThrownBy(() -> kit.settings.updatePreferences(reader, List.of(
                    new PreferenceChange(NotificationCategory.FINE, NotificationChannel.EMAIL, false),
                    new PreferenceChange(NotificationCategory.ACCOUNT, NotificationChannel.EMAIL, false))))
                    .isInstanceOf(PreferenceMandatoryException.class);
            assertThat(kit.settings.preferences(reader)).filteredOn(p -> p.category().equals("FINE"))
                    .allMatch(p -> p.enabled());
            assertThat(kit.settings.preferences(reader)).filteredOn(p -> p.category().equals("ACCOUNT"))
                    .allMatch(p -> p.enabled() && p.mandatory());
        });
        enqueue(tenantA, readerEvent(UUID.randomUUID())); // RESERVATION đã tắt
        assertThat(deliveries(tenantA).get(0).skipReason()).isEqualTo(SkipReason.OPTED_OUT);
        // Hợp đồng sắp hết hạn (ACCOUNT) vẫn xếp hàng dù người dùng có dòng tắt cũ trong DB
        TenantContext.runAs(tenantA, () -> kit.config.upsert(reader, NotificationCategory.ACCOUNT,
                NotificationChannel.EMAIL, false, kit.clock.instant()));
        enqueue(tenantA, NotificationTrigger.forUser(UUID.randomUUID(), "TenantContractExpiringSoon",
                NotificationType.CONTRACT_EXPIRING, reader, Map.of("days", "30")));
        assertThat(deliveries(tenantA)).filteredOn(d -> d.type() == NotificationType.CONTRACT_EXPIRING)
                .allMatch(d -> d.status() == DeliveryStatus.PENDING);
        // Người dùng khác tenant không bị ảnh hưởng bởi tùy chọn này
        enqueue(tenantB, readerEvent(UUID.randomUUID()));
        assertThat(deliveries(tenantB).get(0).status()).isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    void khongCoTaiKhoanHoacKhongCoEmailThiSkippedLyDoDung() {
        UUID noEmail = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        kit.directory.add(noEmail, " ", "Không Email", true);
        kit.directory.add(inactive, "x@example.com", "Đã Khóa", false);
        for (UUID u : new UUID[] {noEmail, inactive, unknown}) {
            enqueue(tenantA, NotificationTrigger.forUser(UUID.randomUUID(), "X", NotificationType.RESERVATION_READY, u,
                    Map.of()));
        }
        kit.dispatch.dispatchTenant(tenantA, 100);
        Map<UUID, Delivery> byUser = new java.util.HashMap<>();
        deliveries(tenantA).forEach(d -> byUser.put(d.recipientUserId(), d));
        assertThat(byUser.get(noEmail).skipReason()).isEqualTo(SkipReason.NO_ADDRESS);
        assertThat(byUser.get(inactive).skipReason()).isEqualTo(SkipReason.INACTIVE_USER);
        assertThat(byUser.get(unknown).skipReason()).as("id ngoài tenant không có trong kết quả tra")
                .isEqualTo(SkipReason.INACTIVE_USER);
        assertThat(kit.provider.calls.get()).isZero();
    }

    // ---- cách ly và dữ liệu cá nhân ----

    @Test
    void lichSuCuaToiChiCoDeliveryCuaMinhTrongTenantMinhVaDiaChiDaChe() {
        kit.directory.add(reader, "doc.gia@example.com", "A", true);
        enqueue(tenantA, readerEvent(UUID.randomUUID()));
        enqueue(tenantB, readerEvent(UUID.randomUUID()));
        kit.dispatch.dispatchTenant(tenantA, 100);
        TenantContext.runAs(tenantA, () -> {
            var mine = kit.deliveryUseCases.mine(reader);
            assertThat(mine).hasSize(1);
            assertThat(mine.get(0).status()).isEqualTo("SENT");
            assertThat(mine.get(0).toString()).doesNotContain("doc.gia@example.com");
            assertThat(kit.deliveryUseCases.mine(UUID.randomUUID())).isEmpty();
            var page = kit.deliveryUseCases.search(new DeliveryCriteria(null, null, null, null, null, 0, 20));
            assertThat(page.total()).isEqualTo(1);
            assertThat(page.items().toString()).doesNotContain("doc.gia@example.com");
        });
    }

    @Test
    void dispatcherChiXaHangCuaTenantDangChay() {
        kit.directory.add(reader, "a@example.com", "A", true);
        enqueue(tenantA, readerEvent(UUID.randomUUID()));
        enqueue(tenantB, readerEvent(UUID.randomUUID()));
        assertThat(kit.dispatch.dispatchTenant(tenantA, 100)).isEqualTo(1);
        assertThat(deliveries(tenantB).get(0).status()).isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    void donDepXoaLuotKetThucQuaHanLuuGiu() {
        kit.directory.add(reader, "a@example.com", "A", true);
        enqueue(tenantA, readerEvent(UUID.randomUUID()));
        kit.dispatch.dispatchTenant(tenantA, 100);
        enqueue(tenantA, readerEvent(UUID.randomUUID())); // còn PENDING, không bị xóa
        kit.clock.advance(Duration.ofDays(181));
        assertThat(kit.dispatch.purgeAll(Duration.ofDays(180))).isEqualTo(1);
        assertThat(deliveries(tenantA)).hasSize(1).allMatch(d -> d.status() == DeliveryStatus.PENDING);
    }

    @Test
    void enqueueGhiTenantVaoDanhSachDeDispatcherLap() {
        enqueue(tenantA, readerEvent(UUID.randomUUID()));
        enqueue(tenantB, readerEvent(UUID.randomUUID()));
        assertThat(kit.config.allTenantIds()).containsExactlyInAnyOrder(tenantA, tenantB);
    }
}
