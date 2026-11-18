package lms.catalog.inventory.infrastructure.demo;

import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.AcquisitionSource;
import lms.catalog.inventory.domain.ConditionGrade;
import lms.catalog.inventory.domain.CopyAcquisition;
import lms.catalog.inventory.domain.CopyStatus;
import java.util.UUID;
import lms.demo.plan.DemoIds;
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
 * mượn/trả) và không phát event Kafka. Bản sao kèm thông tin nhập kho (ngày nhập, nguồn, giá mua, tình trạng, kiểm kê) và
 * vị trí kệ (id kệ tất định do bước catalog.titles tạo). Bản sao đã có (theo id) chỉ được điền các trường nhập kho/kệ còn
 * null, không đè giá trị đã có và không đổi trạng thái; trùng mã vạch với bản sao khác thì bỏ qua.
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

    /** Điền thông tin nhập kho và kệ còn thiếu theo kế hoạch. @return true nếu có thay đổi. */
    private boolean fill(BookCopy copy, CopyPlan c) {
        boolean changed = false;
        CopyAcquisition cur = copy.acquisition();
        AcquisitionSource source = cur.acquisitionSource() != null ? cur.acquisitionSource()
                : c.acquisitionSource() == null ? null : AcquisitionSource.valueOf(c.acquisitionSource());
        CopyAcquisition merged = new CopyAcquisition(
                cur.acquiredDate() != null ? cur.acquiredDate() : c.acquiredDate(), source,
                // giá mua chỉ hợp lệ khi nguồn là PURCHASE
                cur.purchasePrice() != null ? cur.purchasePrice()
                        : source == AcquisitionSource.PURCHASE ? c.purchasePrice() : null,
                cur.conditionGrade() != null ? cur.conditionGrade()
                        : c.conditionGrade() == null ? null : ConditionGrade.valueOf(c.conditionGrade()),
                cur.conditionNote() != null ? cur.conditionNote() : c.conditionNote(),
                cur.lastInventoryDate() != null ? cur.lastInventoryDate() : c.lastInventoryDate());
        if (!merged.equals(cur)) {
            copy.updateAcquisition(merged);
            changed = true;
        }
        if (copy.shelfLocationId() == null && c.shelfIndex() != null) {
            UUID shelfId = DemoIds.of("shelf", c.shelfIndex());
            if (copies.findShelfLocationActive(shelfId).orElse(false)) {
                copy.moveToShelfLocation(shelfId);
                changed = true;
            }
        }
        return changed;
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
                BookCopy existing = copies.findById(c.id()).orElse(null);
                if (existing != null) {
                    if (fill(existing, c)) {
                        copies.save(existing);
                        created++;
                    } else {
                        skipped++;
                    }
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
                BookCopy copy = BookCopy.restore(c.id(), c.titleId(), c.barcode(), CopyStatus.valueOf(c.status().name()));
                fill(copy, c);
                copies.save(copy);
                created++;
            }
            return SeedResult.of(created, skipped);
        });
    }
}
