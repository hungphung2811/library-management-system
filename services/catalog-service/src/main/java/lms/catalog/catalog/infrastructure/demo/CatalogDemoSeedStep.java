package lms.catalog.catalog.infrastructure.demo;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan;
import lms.demo.plan.DemoPlan.StockItemPlan;
import lms.demo.plan.DemoPlan.TitlePlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Seed demo (ADR-0018) cho context catalog: thể loại, tác giả chuẩn và đầu sách theo {@link DemoPlan}.
 * Ghi qua repository của chính context dưới {@code TenantContext} của tenant demo (do runner đặt), nên @TenantId và RLS
 * của vai trò catalog_app vẫn áp dụng. Không phát event Kafka. Dùng id tất định ({@link DemoIds}) để chạy lại không nhân đôi:
 * mục đã có (theo id) thì bỏ qua, không cập nhật. Đầu sách trùng ISBN với một đầu sách khác id (dữ liệu không phải demo)
 * cũng bị bỏ qua và ghi cảnh báo.
 */
@Component
@Profile("demo-seed")
public class CatalogDemoSeedStep implements DemoSeedStep {

    private static final Logger log = LoggerFactory.getLogger(CatalogDemoSeedStep.class);

    /** Giá bìa dự phòng cho đầu sách không có mặt hàng tồn kho trong kế hoạch. */
    static final long BASE_COVER_PRICE = 90_000L;

    private final ReferenceRepository references;
    private final TitleRepository titles;
    private final TransactionOperations tx;

    public CatalogDemoSeedStep(ReferenceRepository references, TitleRepository titles, TransactionOperations tx) {
        this.references = references;
        this.titles = titles;
        this.tx = tx;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public String name() {
        return "catalog.titles";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        return tx.execute(status -> seed(context.plan()));
    }

    private SeedResult seed(DemoPlan plan) {
        int created = 0;
        int skipped = 0;

        Map<String, UUID> categoryIds = new LinkedHashMap<>();
        Map<String, UUID> authorIds = new LinkedHashMap<>();
        for (TitlePlan t : plan.titles()) {
            if (!categoryIds.containsKey(t.category())) categoryIds.put(t.category(), DemoIds.of("category", categoryIds.size()));
            if (!authorIds.containsKey(t.author())) authorIds.put(t.author(), DemoIds.of("author", authorIds.size()));
        }

        for (Map.Entry<String, UUID> e : categoryIds.entrySet()) {
            if (saveReference(ReferenceKind.CATEGORY, e.getValue(), e.getKey())) created++; else skipped++;
        }
        for (Map.Entry<String, UUID> e : authorIds.entrySet()) {
            if (saveReference(ReferenceKind.AUTHOR, e.getValue(), e.getKey())) created++; else skipped++;
        }

        Map<UUID, Long> prices = new HashMap<>();
        for (StockItemPlan s : plan.stockItems()) {
            prices.put(s.titleId(), s.unitPrice());
        }

        for (TitlePlan t : plan.titles()) {
            if (titles.findById(t.id()).isPresent()) {
                skipped++;
                continue;
            }
            if (titles.existsByIsbn(t.isbn())) {
                log.warn("demo-seed: bỏ qua đầu sách '{}' vì ISBN {} đã thuộc đầu sách khác", t.name(), t.isbn());
                skipped++;
                continue;
            }
            long price = prices.getOrDefault(t.id(), BASE_COVER_PRICE + (t.index() % 7) * 10_000L);
            titles.save(Title.restore(t.id(), t.name(), t.author(), t.isbn(), BigDecimal.valueOf(price), null,
                    null, null, null, categoryIds.get(t.category()), List.of(authorIds.get(t.author()))));
            created++;
        }
        return SeedResult.of(created, skipped);
    }

    /** @return true nếu vừa tạo, false nếu đã có (cùng id). Cùng tên khác id nghĩa là tenant không sạch: lỗi rõ ràng. */
    private boolean saveReference(ReferenceKind kind, UUID id, String name) {
        if (references.findById(kind, id).isPresent()) return false;
        Reference ref = Reference.restore(id, kind, null, name, null, null, true);
        if (references.existsByNameKey(kind, ref.nameKey(), null)) {
            throw new IllegalStateException("Tenant đã có " + kind.label() + " '" + name
                    + "' nhưng không phải bản ghi demo; seed chỉ chạy trên tenant demo sạch");
        }
        references.save(ref);
        return true;
    }
}
