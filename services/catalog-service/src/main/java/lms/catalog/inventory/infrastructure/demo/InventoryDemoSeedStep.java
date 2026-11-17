package lms.catalog.inventory.infrastructure.demo;

import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyStatus;
import lms.demo.plan.DemoPlan.CopyPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Seed demo (ADR-0018) cho context inventory: bản sao sách với đúng trạng thái trong {@code DemoPlan}
 * (LOANED khớp các dòng mượn chưa trả, RESERVED khớp đặt trước của circulation). Chạy sau bước đầu sách
 * (order 20, sau 10) vì bản sao có khóa ngoại tới đầu sách; inventory không biết context catalog nên chỉ kiểm
 * tồn tại bằng {@link BookCopyRepository#titleExists}. Trạng thái dựng lại trực tiếp (không đi qua use case vòng đời
 * mượn/trả) và không phát event Kafka. Bản sao đã có (theo id) hoặc trùng mã vạch thì bỏ qua, không cập nhật.
 */
@Component
@Profile("demo-seed")
public class InventoryDemoSeedStep implements DemoSeedStep {

    private static final Logger log = LoggerFactory.getLogger(InventoryDemoSeedStep.class);

    private final BookCopyRepository copies;
    private final TransactionOperations tx;

    public InventoryDemoSeedStep(BookCopyRepository copies, TransactionOperations tx) {
        this.copies = copies;
        this.tx = tx;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public String name() {
        return "inventory.copies";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        return tx.execute(status -> {
            int created = 0;
            int skipped = 0;
            for (CopyPlan c : context.plan().copies()) {
                if (copies.findById(c.id()).isPresent()) {
                    skipped++;
                    continue;
                }
                if (copies.existsByBarcode(c.barcode())) {
                    log.warn("demo-seed: bỏ qua bản sao {} vì mã vạch đã thuộc bản sao khác", c.barcode());
                    skipped++;
                    continue;
                }
                if (!copies.titleExists(c.titleId())) {
                    throw new IllegalStateException("Chưa có đầu sách " + c.titleId() + " cho bản sao " + c.barcode()
                            + ": bước catalog.titles phải chạy trước");
                }
                copies.save(BookCopy.restore(c.id(), c.titleId(), c.barcode(), CopyStatus.valueOf(c.status().name())));
                created++;
            }
            return SeedResult.of(created, skipped);
        });
    }
}
