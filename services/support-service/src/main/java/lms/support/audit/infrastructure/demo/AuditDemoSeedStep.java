package lms.support.audit.infrastructure.demo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan;
import lms.demo.plan.DemoPlan.BorrowingPlan;
import lms.demo.plan.DemoPlan.OrderPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import lms.support.audit.application.RecordAuditEventCommand;
import lms.support.audit.application.RecordAuditEventUseCase;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seed nhật ký audit của tenant demo (ADR-0018): khoảng 50 sự kiện nghiệp vụ trải trong 30 ngày (người dùng, phân
 * quyền, đầu sách, phiếu mượn/trả, đặt trước, phạt, đơn hàng, hóa đơn), cùng tên loại sự kiện mà các service thật phát,
 * để màn nhật ký có dữ liệu cho bộ lọc theo loại và đối tượng. Dựng từ kế hoạch demo (id đối tượng khớp dữ liệu đã
 * seed ở các service khác) và ghi qua {@link RecordAuditEventUseCase}, không qua Kafka.
 * <p>
 * Idempotent theo eventId tất định từ (loại, đối tượng, payload), use case bỏ qua eventId đã có. Payload chỉ
 * chứa mã/id, không có dữ liệu cá nhân.
 */
@Component
@Profile("demo-seed")
public class AuditDemoSeedStep implements DemoSeedStep {

    static final int MAX_BORROWINGS = 12;
    static final int MAX_ORDERS = 12;

    private final RecordAuditEventUseCase record;

    public AuditDemoSeedStep(RecordAuditEventUseCase record) {
        this.record = record;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public String name() {
        return "audit.logs";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        if (context.tenantId() == null) {
            throw new IllegalStateException("Seed audit cần lms.demo-seed.tenant-id");
        }
        DemoPlan plan = context.plan();
        Clock clock = context.clock();
        Seeder seeder = new Seeder(record, plan.today(), clock);

        for (int i = 0; i < plan.staff().size(); i++) {
            var s = plan.staff().get(i);
            seeder.add("UserCreated", s.id(), 29 - i, 540, "{\"username\":\"" + s.username() + "\"}");
            seeder.add("RoleAssigned", s.id(), 29 - i, 545, "{\"role\":\"" + s.role().name() + "\"}");
        }
        for (int i = 0; i < Math.min(plan.readers().size(), 10); i++) {
            var r = plan.readers().get(i);
            seeder.add("UserCreated", r.id(), 28 - i, 600 + i, "{\"cardNo\":\"" + r.cardNo() + "\"}");
        }
        seeder.add("UserStatusChanged", plan.readers().get(0).id(), 5, 700, "{\"status\":\"LOCKED\"}");
        seeder.add("UserStatusChanged", plan.readers().get(0).id(), 4, 710, "{\"status\":\"ACTIVE\"}");
        for (int i = 0; i < Math.min(plan.titles().size(), 4); i++) {
            seeder.add("TitleCreated", plan.titles().get(i).id(), 27 - i, 480, "{\"isbn\":\"" + plan.titles().get(i)
                    .isbn() + "\"}");
        }
        for (int i = 0; i < Math.min(plan.copies().size(), 3); i++) {
            seeder.add("BookCopyAdded", plan.copies().get(i).id(), 26, 490 + i,
                    "{\"barcode\":\"" + plan.copies().get(i).barcode() + "\"}");
        }

        // Một nửa phiếu đã trả hết, một nửa còn đang mượn (đủ BookReturned và BookRenewed)
        List<BorrowingPlan> picked = new ArrayList<>();
        picked.addAll(plan.borrowings().stream().filter(p -> !p.isActive()).limit(MAX_BORROWINGS / 2).toList());
        picked.addAll(plan.borrowings().stream().filter(BorrowingPlan::isActive).limit(MAX_BORROWINGS / 2).toList());
        int b = 0;
        for (BorrowingPlan p : picked) {
            b++;
            int daysAgo = Math.min(29, p.borrowDaysAgo());
            seeder.add("BorrowingCreated", p.id(), daysAgo, 600, "{\"readerId\":\"" + p.readerId() + "\"}");
            if (!p.isActive()) {
                seeder.add("BookReturned", p.id(), Math.max(0, daysAgo - 5), 900,
                        "{\"items\":" + p.items().size() + "}");
            } else if (b % 2 == 0) {
                seeder.add("BookRenewed", p.id(), Math.max(0, daysAgo - 3), 800, "{\"extraDays\":7}");
            }
        }
        for (var r : plan.reservations().stream().limit(4).toList()) {
            seeder.add("ReservationCreated", r.id(), Math.min(29, r.createdDaysAgo()), 620,
                    "{\"readerId\":\"" + r.readerId() + "\"}");
        }
        for (var f : plan.fines().stream().filter(x -> x.status() == DemoPlan.FineStatus.PAID).limit(4).toList()) {
            seeder.add("FinePaid", f.id(), Math.min(29, f.paidDaysAgo() == null ? 0 : f.paidDaysAgo()), 1000,
                    "{\"amount\":" + f.amount() + "}");
        }

        int o = 0;
        for (OrderPlan p : plan.orders()) {
            if (p.status() != DemoPlan.OrderStatus.COMPLETED || !p.isPaid()) {
                continue;
            }
            if (o++ >= MAX_ORDERS) {
                break;
            }
            int daysAgo = Math.min(29, p.createdDaysAgo());
            seeder.add("OrderPlaced", p.id(), daysAgo, p.minuteOfDay(), "{\"orderNo\":\"" + p.orderNo() + "\"}");
            seeder.add("OrderCompleted", p.id(), daysAgo, Math.min(1439, p.minuteOfDay() + 30),
                    "{\"orderNo\":\"" + p.orderNo() + "\",\"total\":" + p.total() + "}");
        }
        return SeedResult.of(seeder.created, seeder.skipped);
    }

    /** Ghi từng sự kiện với eventId tất định theo (loại, đối tượng, payload): kế hoạch đổi theo ngày vẫn không nhân đôi. */
    private static final class Seeder {
        private final RecordAuditEventUseCase record;
        private final LocalDate today;
        private final Clock clock;
        int created;
        int skipped;

        Seeder(RecordAuditEventUseCase record, LocalDate today, Clock clock) {
            this.record = record;
            this.today = today;
            this.clock = clock;
        }

        void add(String eventType, UUID aggregateId, int daysAgo, int minuteOfDay, String payload) {
            Instant at = today.minusDays(daysAgo).atStartOfDay(clock.getZone()).plus(Duration.ofMinutes(minuteOfDay))
                    .toInstant();
            if (at.isAfter(clock.instant())) {
                at = clock.instant();
            }
            UUID eventId = DemoIds.of("audit-event:" + eventType + ":" + aggregateId + ":" + payload, 0);
            boolean made = record.execute(new RecordAuditEventCommand(eventId, eventType, aggregateId.toString(), at,
                    payload));
            if (made) {
                created++;
            } else {
                skipped++;
            }
        }
    }
}
