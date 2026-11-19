package lms.sales.contact.infrastructure.demo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan.ReaderPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import lms.sales.contact.application.ShopContactRepository;
import lms.sales.contact.domain.ContactStatus;
import lms.sales.contact.domain.ContactTopic;
import lms.sales.contact.domain.ShopContactRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Seed yêu cầu liên hệ của khách gửi cửa hàng (ADR-0015, ADR-0018): đủ ba trạng thái NEW, IN_PROGRESS, CLOSED và một
 * yêu cầu đã ẩn danh (quá hạn lưu trữ) để màn liên hệ có dữ liệu. Idempotent theo id tất định
 * {@code DemoIds.of("shop-contact", n)}; dữ liệu cá nhân là dữ liệu giả của độc giả demo.
 */
@Component
@Profile("demo-seed")
public class ContactDemoSeedStep implements DemoSeedStep {

    /** chủ đề, trạng thái, tuổi (ngày), có ẩn danh không, nội dung. */
    private record Spec(ContactTopic topic, ContactStatus status, int daysAgo, boolean anonymized, String message) {
    }

    private static final List<Spec> SPECS = List.of(
            new Spec(ContactTopic.ORDER, ContactStatus.NEW, 0, false, "Đơn của tôi bao giờ được giao ạ?"),
            new Spec(ContactTopic.PRODUCT, ContactStatus.NEW, 1, false, "Cửa hàng còn bản bìa cứng của sách này không?"),
            new Spec(ContactTopic.PAYMENT, ContactStatus.NEW, 2, false, "Tôi đã chuyển khoản nhưng đơn vẫn chưa xác nhận."),
            new Spec(ContactTopic.OTHER, ContactStatus.NEW, 3, false, "Cửa hàng có xuất hóa đơn công ty không?"),
            new Spec(ContactTopic.ORDER, ContactStatus.IN_PROGRESS, 5, false, "Tôi muốn đổi địa chỉ nhận hàng cho đơn đã đặt."),
            new Spec(ContactTopic.PRODUCT, ContactStatus.IN_PROGRESS, 8, false, "Sách nhận được bị rách bìa, xin đổi trả."),
            new Spec(ContactTopic.PAYMENT, ContactStatus.CLOSED, 15, false, "Hoàn tiền đơn hủy bao lâu thì về tài khoản?"),
            new Spec(ContactTopic.OTHER, ContactStatus.CLOSED, 25, false, "Giờ mở cửa của cửa hàng cuối tuần?"),
            new Spec(ContactTopic.ORDER, ContactStatus.CLOSED, 400, true, null));

    private final ShopContactRepository contacts;
    private final TransactionOperations tx;

    public ContactDemoSeedStep(ShopContactRepository contacts, TransactionOperations tx) {
        this.contacts = contacts;
        this.tx = tx;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public String name() {
        return "sales.contact";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        if (context.tenantId() == null) {
            throw new IllegalStateException("Seed bán hàng cần lms.demo-seed.tenant-id");
        }
        UUID tenantId = context.tenantId();
        Instant now = context.clock().instant();
        List<ReaderPlan> readers = context.plan().readers();
        int created = 0;
        int skipped = 0;
        for (int i = 0; i < SPECS.size(); i++) {
            Spec s = SPECS.get(i);
            UUID id = DemoIds.of("shop-contact", i + 1);
            if (contacts.findById(tenantId, id).isPresent()) {
                skipped++;
                continue;
            }
            ReaderPlan reader = readers.get(i % readers.size());
            Instant createdAt = now.minus(Duration.ofDays(s.daysAgo())).minus(Duration.ofMinutes(37L * (i + 1)));
            Instant closedAt = s.status() == ContactStatus.CLOSED ? createdAt.plus(Duration.ofHours(20)) : null;
            ShopContactRequest request = s.anonymized()
                    ? ShopContactRequest.restore(id, tenantId, null, s.topic(), null, null, null, null, createdAt,
                    s.status(), 0, createdAt, closedAt, closedAt.plus(Duration.ofDays(1)))
                    : ShopContactRequest.restore(id, tenantId, reader.id(), s.topic(), s.message(), reader.fullName(),
                    reader.email(), String.format("0902%06d", 200_000 + i), createdAt, s.status(), 0, createdAt,
                    closedAt, null);
            tx.executeWithoutResult(status -> contacts.insert(request));
            created++;
        }
        return SeedResult.of(created, skipped);
    }
}
