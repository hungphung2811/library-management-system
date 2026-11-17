package lms.circulation.borrowing.infrastructure.demo;

import java.util.List;
import lms.circulation.borrowing.application.SaveBorrowingPort;
import lms.circulation.borrowing.domain.BorrowingId;
import lms.circulation.borrowing.domain.BorrowingItem;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingRepository;
import lms.circulation.borrowing.domain.ItemCondition;
import lms.demo.plan.DemoPlan.BorrowingPlan;
import lms.demo.plan.DemoPlan.LoanItemPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seed phiếu mượn demo (ADR-0018 mục 2.4). Ghi thẳng qua domain và cổng lưu của borrowing, KHÔNG qua BorrowBookUseCase
 * (use case đó gọi identity/catalog qua HTTP và phát event Kafka). Id phiếu, id bản sao, id đầu sách lấy từ kế hoạch
 * dùng chung (DemoIds) nên khớp catalog/identity mà không cần đọc DB của họ. Idempotent theo id phiếu: phiếu đã có thì bỏ qua.
 * Chỉ tồn tại khi profile {@code demo-seed} bật.
 */
@Component
@Profile("demo-seed")
public class BorrowingDemoSeedStep implements DemoSeedStep {

    private final BorrowingRepository repository;
    private final SaveBorrowingPort save;

    public BorrowingDemoSeedStep(BorrowingRepository repository, SaveBorrowingPort save) {
        this.repository = repository;
        this.save = save;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public String name() {
        return "circulation.borrowings";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        int created = 0;
        int skipped = 0;
        for (BorrowingPlan plan : context.plan().borrowings()) {
            if (repository.findById(new BorrowingId(plan.id())).isPresent()) {
                skipped++;
                continue;
            }
            save.save(toRecord(plan));
            created++;
        }
        return SeedResult.of(created, skipped);
    }

    /** Dựng phiếu ở trạng thái lịch sử (restore: không event, ngày mượn/hạn/trả theo kế hoạch). Trả nguyên vẹn (GOOD). */
    static BorrowingRecord toRecord(BorrowingPlan plan) {
        List<BorrowingItem> items = plan.items().stream().map(BorrowingDemoSeedStep::toItem).toList();
        return BorrowingRecord.restore(new BorrowingId(plan.id()), plan.readerId(), items, plan.borrowDate(),
                plan.dueDate(), 0, plan.loanDays());
    }

    private static BorrowingItem toItem(LoanItemPlan item) {
        return item.isReturned()
                ? new BorrowingItem(item.copyId(), item.titleId(), item.returnDate(), ItemCondition.GOOD)
                : new BorrowingItem(item.copyId(), item.titleId(), null, null);
    }
}
