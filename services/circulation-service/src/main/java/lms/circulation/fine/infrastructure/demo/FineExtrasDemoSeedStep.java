package lms.circulation.fine.infrastructure.demo;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.circulation.fine.domain.AdjustmentKind;
import lms.circulation.fine.domain.ApprovalStatus;
import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineAdjustmentRepository;
import lms.circulation.fine.domain.FineAdjustmentRequest;
import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineRefund;
import lms.circulation.fine.domain.FineRefundRepository;
import lms.circulation.fine.domain.FineRepository;
import lms.circulation.fine.domain.FineStatus;
import lms.circulation.fine.domain.RefundMethod;
import lms.circulation.fine.domain.RefundMode;
import lms.circulation.fine.domain.RestoreStatus;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan;
import lms.demo.plan.DemoPlan.BorrowingPlan;
import lms.demo.plan.DemoPlan.FinePlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seed phần phạt mở rộng của tenant demo (ADR-0018): để màn Phạt, Đề nghị miễn/giảm và Đền bù/hoàn khoản có đủ trạng
 * thái ngoài các phạt trả trễ của {@link FineDemoSeedStep}. Dùng phiếu đã trả hết của kế hoạch chưa có phạt:
 * <ul>
 *   <li>phạt hỏng sách đã thu (PAID), phạt hỏng sách đã được miễn (WAIVED) kèm đề nghị WAIVE đã duyệt;</li>
 *   <li>phạt mất sách đã thu (PAID) kèm đề nghị hoàn khoản đủ ba trạng thái PENDING, APPROVED, REJECTED;</li>
 *   <li>đề nghị miễn (PENDING) và giảm (REJECTED) trên hai khoản phạt trả trễ CHƯA THU của kế hoạch (khoản phạt giữ
 *       nguyên UNPAID, nên chỉ số dashboard 4 phạt chưa thu / 80.000 không đổi).</li>
 * </ul>
 * Không có khoản UNPAID hay REDUCED nào được thêm. Đề nghị hoàn APPROVED chỉ ghi dòng đề nghị (không gọi catalog khôi
 * phục bản sao, không phát event). Idempotent theo id tất định (phạt {@code fine-extra}, đề nghị {@code fine-adjustment}
 * và {@code fine-refund}) và ràng buộc (phiếu, bản sao, lý do) của phạt. Chạy sau {@link FineDemoSeedStep}.
 */
@Component
@Profile("demo-seed")
public class FineExtrasDemoSeedStep implements DemoSeedStep {

    private static final BigDecimal REFUND_FEE = BigDecimal.valueOf(15_000);

    private final FineRepository fines;
    private final FineAdjustmentRepository adjustments;
    private final FineRefundRepository refunds;

    public FineExtrasDemoSeedStep(FineRepository fines, FineAdjustmentRepository adjustments,
                                  FineRefundRepository refunds) {
        this.fines = fines;
        this.adjustments = adjustments;
        this.refunds = refunds;
    }

    @Override
    public int order() {
        return 25;
    }

    @Override
    public String name() {
        return "circulation.fine-extras";
    }

    /** Một khoản phạt mở rộng: lý do, số tiền, trạng thái cuối. */
    private record Extra(FineReason reason, long amount, FineStatus status) {
    }

    private static final List<Extra> EXTRAS = List.of(
            new Extra(FineReason.DAMAGED, 40_000, FineStatus.PAID),
            new Extra(FineReason.DAMAGED, 60_000, FineStatus.PAID),
            new Extra(FineReason.DAMAGED, 50_000, FineStatus.WAIVED),
            new Extra(FineReason.LOST, 150_000, FineStatus.PAID),
            new Extra(FineReason.LOST, 120_000, FineStatus.PAID),
            new Extra(FineReason.LOST, 100_000, FineStatus.PAID));

    @Override
    @Transactional
    public SeedResult run(DemoSeedContext context) {
        if (context.tenantId() == null) {
            throw new IllegalStateException("Seed lưu thông cần lms.demo-seed.tenant-id");
        }
        UUID tenantId = context.tenantId();
        DemoPlan plan = context.plan();
        Clock clock = context.clock();
        UUID librarian = plan.staff().get(1).id();
        UUID admin = plan.staff().get(0).id();
        int created = 0;
        int skipped = 0;

        Set<UUID> hasPlanFine = new HashSet<>();
        plan.fines().forEach(f -> hasPlanFine.add(f.borrowingId()));
        List<BorrowingPlan> eligible = plan.borrowings().stream()
                .filter(b -> !b.isActive() && !hasPlanFine.contains(b.id())).toList();
        if (eligible.size() < EXTRAS.size()) {
            throw new IllegalStateException("Kế hoạch demo thiếu phiếu đã trả để tạo phạt mở rộng");
        }

        Fine[] seeded = new Fine[EXTRAS.size()];
        for (int i = 0; i < EXTRAS.size(); i++) {
            Extra e = EXTRAS.get(i);
            BorrowingPlan b = eligible.get(i);
            UUID copyId = b.items().get(0).copyId();
            Instant createdAt = at(plan.today().minusDays(20L - i), 10, clock);
            Instant paidAt = e.status() == FineStatus.PAID ? at(plan.today().minusDays(18L - i), 15, clock) : null;
            BigDecimal amount = BigDecimal.valueOf(e.amount());
            BigDecimal payable = e.status() == FineStatus.WAIVED ? BigDecimal.ZERO : amount;
            Fine fine = Fine.restore(DemoIds.of("fine-extra", i + 1), b.readerId(), b.id(), copyId, e.reason(), amount,
                    payable, e.status(), createdAt, paidAt, null, tenantId);
            if (fines.exists(b.id(), copyId, e.reason())) {
                skipped++;
                seeded[i] = fines.findByItemAndReason(b.id(), copyId, e.reason()).orElse(fine);
            } else {
                seeded[i] = fines.save(fine);
                created++;
            }
        }

        // Miễn đã duyệt cho khoản phạt hỏng sách đã được miễn
        Fine waived = seeded[2];
        boolean[] saved = new boolean[3];
        saved[0] = saveAdjustment(DemoIds.of("fine-adjustment", 1), FineAdjustmentRequest.restore(
                DemoIds.of("fine-adjustment", 1), waived.id(), waived.readerId(), waived.reason(), waived.amount(),
                AdjustmentKind.WAIVE, BigDecimal.ZERO, "Độc giả khó khăn, bìa hỏng nhẹ (dữ liệu demo)",
                ApprovalStatus.APPROVED, librarian, waived.createdAt().plus(Duration.ofHours(2)), admin,
                waived.createdAt().plus(Duration.ofDays(1)), "Đồng ý miễn", tenantId));

        // Đề nghị trên hai khoản trả trễ chưa thu của kế hoạch (khoản phạt giữ nguyên UNPAID)
        List<FinePlan> unpaid = plan.fines().stream().filter(f -> f.status() == DemoPlan.FineStatus.UNPAID).toList();
        if (unpaid.size() >= 2) {
            Instant requestedAt = at(plan.today().minusDays(1), 11, clock);
            saved[1] = saveAdjustment(DemoIds.of("fine-adjustment", 2), pendingWaive(unpaid.get(0), tenantId,
                    librarian, requestedAt));
            saved[2] = saveAdjustment(DemoIds.of("fine-adjustment", 3), rejectedReduce(unpaid.get(1), tenantId,
                    librarian, admin, requestedAt.minus(Duration.ofDays(2))));
        }
        for (boolean b : saved) {
            if (b) created++;
            else skipped++;
        }

        // Đề nghị hoàn khoản cho ba khoản phạt mất sách đã thu: chờ duyệt, đã duyệt, từ chối
        ApprovalStatus[] refundStatuses = {ApprovalStatus.PENDING, ApprovalStatus.APPROVED, ApprovalStatus.REJECTED};
        for (int k = 0; k < refundStatuses.length; k++) {
            Fine lost = seeded[3 + k];
            UUID refundId = DemoIds.of("fine-refund", k + 1);
            if (refunds.findByIdForUpdate(refundId).isPresent()) {
                skipped++;
                continue;
            }
            refunds.save(refund(refundId, lost, refundStatuses[k], tenantId, librarian, admin, plan.today(), clock));
            created++;
        }
        return SeedResult.of(created, skipped);
    }

    private boolean saveAdjustment(UUID id, FineAdjustmentRequest request) {
        if (adjustments.findByIdForUpdate(id).isPresent()) {
            return false;
        }
        adjustments.save(request);
        return true;
    }

    private FineAdjustmentRequest pendingWaive(FinePlan f, UUID tenantId, UUID requester, Instant at) {
        return FineAdjustmentRequest.restore(DemoIds.of("fine-adjustment", 2), f.id(), f.readerId(),
                FineReason.LATE_RETURN, BigDecimal.valueOf(f.amount()), AdjustmentKind.WAIVE, BigDecimal.ZERO,
                "Độc giả báo trả muộn do ốm (dữ liệu demo)", ApprovalStatus.PENDING, requester, at, null, null, null,
                tenantId);
    }

    private FineAdjustmentRequest rejectedReduce(FinePlan f, UUID tenantId, UUID requester, UUID approver,
                                                 Instant at) {
        BigDecimal half = BigDecimal.valueOf(f.amount()).divide(BigDecimal.valueOf(2));
        return FineAdjustmentRequest.restore(DemoIds.of("fine-adjustment", 3), f.id(), f.readerId(),
                FineReason.LATE_RETURN, BigDecimal.valueOf(f.amount()), AdjustmentKind.REDUCE, half,
                "Xin giảm một nửa vì trả muộn ít ngày (dữ liệu demo)", ApprovalStatus.REJECTED, requester, at,
                approver, at.plus(Duration.ofHours(5)), "Không thuộc trường hợp được giảm", tenantId);
    }

    private FineRefund refund(UUID id, Fine lost, ApprovalStatus status, UUID tenantId, UUID requester, UUID approver,
                              LocalDate today, Clock clock) {
        LocalDate lostOn = today.minusDays(14);
        LocalDate foundOn = today.minusDays(9);
        Instant requestedAt = at(today.minusDays(8), 9, clock);
        BigDecimal collected = lost.payableAmount();
        boolean decided = status != ApprovalStatus.PENDING;
        return FineRefund.restore(id, lost.id(), lost.borrowingId(), lost.bookCopyId(), lost.readerId(), lostOn, foundOn,
                RestoreStatus.AVAILABLE, "Sách còn nguyên vẹn, nhập lại kho (dữ liệu demo)", RefundMethod.CASH,
                "DEMO-HK-" + id.toString().substring(0, 8), RefundMode.REFUND, FineStatus.PAID, collected, REFUND_FEE,
                collected.subtract(REFUND_FEE).max(BigDecimal.ZERO), status, requester, requestedAt,
                decided ? approver : null, decided ? requestedAt.plus(Duration.ofHours(4)) : null,
                status == ApprovalStatus.APPROVED ? "Đã kiểm tra, đồng ý hoàn"
                        : status == ApprovalStatus.REJECTED ? "Sách tìm lại quá hạn quy định" : null, tenantId);
    }

    private static Instant at(LocalDate day, int hour, Clock clock) {
        Instant t = day.atTime(hour, 0).atZone(clock.getZone()).toInstant();
        return t.isAfter(clock.instant()) ? clock.instant() : t;
    }
}
