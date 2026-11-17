package lms.circulation.fine.infrastructure.demo;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineRepository;
import lms.circulation.fine.domain.FineStatus;
import lms.demo.plan.DemoPlan;
import lms.demo.plan.DemoPlan.BorrowingPlan;
import lms.demo.plan.DemoPlan.FinePlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seed phạt demo (ADR-0018): đúng {@link DemoPlan#UNPAID_FINES} phạt chưa thu và các phạt đã thu, đều phạt trả trễ
 * ({@code LATE_RETURN}) của phiếu đã trả theo kế hoạch. Ghi thẳng qua {@link FineRepository}, không phát event.
 * Idempotent theo khóa tự nhiên (phiếu, bản sao, lý do) đúng ràng buộc {@code uq_fines_item_reason}.
 * Chạy sau bước phiếu mượn (order lớn hơn). Chỉ tồn tại khi profile {@code demo-seed} bật.
 */
@Component
@Profile("demo-seed")
public class FineDemoSeedStep implements DemoSeedStep {

    private final FineRepository repository;

    public FineDemoSeedStep(FineRepository repository) {
        this.repository = repository;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public String name() {
        return "circulation.fines";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        Map<UUID, BorrowingPlan> borrowings = new HashMap<>();
        context.plan().borrowings().forEach(b -> borrowings.put(b.id(), b));
        int created = 0;
        int skipped = 0;
        for (FinePlan plan : context.plan().fines()) {
            UUID copyId = borrowings.get(plan.borrowingId()).items().get(0).copyId();
            if (repository.exists(plan.borrowingId(), copyId, FineReason.LATE_RETURN)) {
                skipped++;
                continue;
            }
            repository.save(toFine(plan, copyId, context.plan().today(), context.clock()));
            created++;
        }
        return SeedResult.of(created, skipped);
    }

    static Fine toFine(FinePlan plan, UUID copyId, LocalDate today, Clock clock) {
        boolean paid = plan.status() == DemoPlan.FineStatus.PAID;
        Instant createdAt = at(today.minusDays(plan.createdDaysAgo()), 9, clock);
        Instant paidAt = paid ? at(today.minusDays(plan.paidDaysAgo()), 16, clock) : null;
        return Fine.restore(plan.id(), plan.readerId(), plan.borrowingId(), copyId, FineReason.LATE_RETURN,
                BigDecimal.valueOf(plan.amount()), paid ? FineStatus.PAID : FineStatus.UNPAID, createdAt, paidAt);
    }

    /** Giờ cố định trong ngày (giờ của clock), không vượt quá hiện tại để ngày hôm nay không nằm ở tương lai. */
    private static Instant at(LocalDate day, int hour, Clock clock) {
        Instant t = day.atTime(hour, 0).atZone(clock.getZone()).toInstant();
        return t.isAfter(clock.instant()) ? clock.instant() : t;
    }
}
