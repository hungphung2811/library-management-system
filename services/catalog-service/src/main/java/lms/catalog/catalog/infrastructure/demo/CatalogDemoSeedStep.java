package lms.catalog.catalog.infrastructure.demo;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceExtras;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleExtras;
import lms.catalog.catalog.domain.TitleFormat;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.catalog.domain.Work;
import lms.catalog.catalog.domain.WorkKind;
import lms.catalog.catalog.domain.WorkRepository;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan;
import lms.demo.plan.DemoPlan.AuthorPlan;
import lms.demo.plan.DemoPlan.PublisherPlan;
import lms.demo.plan.DemoPlan.SeriesPlan;
import lms.demo.plan.DemoPlan.ShelfPlan;
import lms.demo.plan.DemoPlan.StockItemPlan;
import lms.demo.plan.DemoPlan.TitlePlan;
import lms.demo.plan.DemoPlan.WorkPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Seed demo (ADR-0018) cho context catalog: thể loại, tác giả chuẩn, bộ sách, tác phẩm và ấn bản (đầu sách) theo {@link DemoPlan}
 * (mô hình tác phẩm - ấn bản - bản sao, ADR-0026). Thứ tự ghi theo khóa ngoại: danh mục (kể cả bộ sách) rồi tác phẩm rồi ấn bản.
 * 64 ấn bản gốc giữ nguyên id và là tác phẩm 1:1 có id {@code md5('lms-work:'||id)} (khớp backfill của migration V10, nên môi
 * trường đã seed từ trước chỉ được điền thêm); bộ sách đã có cùng tên (backfill, id md5) được dùng lại thay vì tạo trùng. Tác phẩm,
 * ấn bản, bộ sách đã có chỉ được điền các trường còn null (không đè giá trị người dùng đã sửa).
 * Ghi qua repository của chính context dưới {@code TenantContext} của tenant demo (do runner đặt), nên @TenantId và RLS
 * của vai trò catalog_app vẫn áp dụng. Không phát event Kafka. Dùng id tất định ({@link DemoIds}) để chạy lại không nhân đôi:
 * mục đã có (theo id) thì bỏ qua. Ngoại lệ có điều kiện: đầu sách demo đã seed từ bản cũ (chưa có thông tin bổ sung nào) được
 * làm mới một lần theo kế hoạch (tên, tác giả, nhà xuất bản, thông tin bổ sung; tính vào {@code created}); sau đó
 * có thông tin bổ sung nên chạy lại chỉ còn bỏ qua. Đầu sách trùng ISBN với một đầu sách khác id (dữ liệu không phải demo)
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
    private final WorkRepository works;
    private final TransactionOperations tx;

    public CatalogDemoSeedStep(ReferenceRepository references, TitleRepository titles, WorkRepository works,
                               TransactionOperations tx) {
        this.references = references;
        this.titles = titles;
        this.works = works;
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
        Map<String, UUID> publisherIds = new LinkedHashMap<>();
        for (TitlePlan t : plan.titles()) {
            if (!categoryIds.containsKey(t.category())) categoryIds.put(t.category(), DemoIds.of("category", categoryIds.size()));
            // Tác giả dùng loại id riêng "author2": bản kế hoạch cũ đặt tác giả giả theo "author" nên không dùng lại.
            if (!authorIds.containsKey(t.author())) authorIds.put(t.author(), DemoIds.of("author2", authorIds.size()));
            if (t.publisher() != null && !publisherIds.containsKey(t.publisher())) {
                publisherIds.put(t.publisher(), DemoIds.of("publisher", publisherIds.size()));
            }
        }

        Map<String, AuthorPlan> authorPlans = new HashMap<>();
        plan.authors().forEach(a -> authorPlans.put(a.name(), a));
        Map<String, PublisherPlan> publisherPlans = new HashMap<>();
        plan.publishers().forEach(p -> publisherPlans.put(p.name(), p));

        for (Map.Entry<String, UUID> e : categoryIds.entrySet()) {
            if (saveReference(ReferenceKind.CATEGORY, e.getValue(), e.getKey(), ReferenceExtras.EMPTY)) created++; else skipped++;
        }
        for (Map.Entry<String, UUID> e : authorIds.entrySet()) {
            AuthorPlan a = authorPlans.get(e.getKey());
            ReferenceExtras x = a == null ? ReferenceExtras.EMPTY
                    : ReferenceExtras.author(a.birthYear(), a.deathYear(), a.nationality(), null, a.website());
            if (saveReference(ReferenceKind.AUTHOR, e.getValue(), e.getKey(), x)) created++; else skipped++;
        }
        for (Map.Entry<String, UUID> e : publisherIds.entrySet()) {
            PublisherPlan p = publisherPlans.get(e.getKey());
            ReferenceExtras x = p == null ? ReferenceExtras.EMPTY
                    : ReferenceExtras.publisher(p.country(), p.foundedYear(), p.website(), p.address());
            if (saveReference(ReferenceKind.PUBLISHER, e.getValue(), e.getKey(), x)) created++; else skipped++;
        }
        for (ShelfPlan s : plan.shelves()) {
            ReferenceExtras x = ReferenceExtras.shelf(s.floor(), s.zone(), s.capacity());
            if (saveReference(ReferenceKind.SHELF_LOCATION, s.id(), s.code(), x)) created++; else skipped++;
        }

        // Bộ sách: dùng lại bộ cùng tên đã có (backfill từ titles.series có id md5), chỉ tạo bộ còn thiếu.
        Map<String, UUID> seriesIds = new LinkedHashMap<>();
        Map<String, Reference> existingSeries = references.findByNameKeys(ReferenceKind.SERIES,
                plan.series().stream().map(x -> Reference.keyOf(x.name())).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(Reference::nameKey, r -> r, (a, b) -> a));
        for (SeriesPlan sp : plan.series()) {
            Reference found = existingSeries.get(Reference.keyOf(sp.name()));
            UUID id = found != null ? found.id() : sp.id();
            seriesIds.put(sp.name(), id);
            if (saveReference(ReferenceKind.SERIES, id, sp.name(), ReferenceExtras.EMPTY)) created++; else skipped++;
        }

        // Tác phẩm (trước ấn bản vì titles.work_id có khóa ngoại tới works)
        for (WorkPlan w : plan.works()) {
            UUID seriesId = w.seriesIndex() == null ? null : seriesIds.get(plan.series().get(w.seriesIndex()).name());
            if (saveWork(w, categoryIds.get(w.category()), seriesId, List.of(authorIds.get(w.author())))) created++;
            else skipped++;
        }

        Map<UUID, Long> prices = new HashMap<>();
        for (StockItemPlan s : plan.stockItems()) {
            prices.put(s.titleId(), s.unitPrice());
        }

        for (TitlePlan t : plan.titles()) {
            UUID publisherId = t.publisher() == null ? null : publisherIds.get(t.publisher());
            UUID categoryId = categoryIds.get(t.category());
            List<UUID> authors = List.of(authorIds.get(t.author()));
            TitleExtras extras = extrasOf(t);
            Title current = titles.findById(t.id()).orElse(null);
            if (current != null) {
                Title updated = current;
                if (current.extras().isEmpty() && !extras.isEmpty()) {
                    // Đầu sách seed từ bản cũ: làm mới một lần, giữ id/ISBN/giá bìa/mô tả; sau đó extras khác rỗng nên chỉ bỏ qua.
                    updated = current.update(t.name(), t.author(), current.isbn(), current.coverPrice())
                            .withClassification(publisherId, categoryId, authors).withExtras(extras);
                }
                // Mô hình tác phẩm: chỉ điền khi còn trống (ấn bản backfill đã có work_id thì giữ nguyên)
                if (updated.workId() == null) {
                    updated = updated.withWork(t.workId());
                }
                if (updated.partNumber() == null && t.partNumber() != null) {
                    updated = updated.withPart(t.partNumber(), t.partLabel());
                }
                if (updated != current) {
                    titles.save(updated);
                    created++;
                } else {
                    skipped++;
                }
                continue;
            }
            if (titles.existsByIsbn(t.isbn())) {
                log.warn("demo-seed: bỏ qua đầu sách '{}' vì ISBN {} đã thuộc đầu sách khác", t.name(), t.isbn());
                skipped++;
                continue;
            }
            long price = prices.getOrDefault(t.id(), BASE_COVER_PRICE + (t.index() % 7) * 10_000L);
            titles.save(Title.restore(t.id(), t.name(), t.author(), t.isbn(), BigDecimal.valueOf(price), null,
                    null, null, publisherId, categoryId, authors, extras, t.workId(), t.partNumber(), t.partLabel()));
            created++;
        }
        return SeedResult.of(created, skipped);
    }

    /**
     * Tạo tác phẩm theo kế hoạch, hoặc điền các trường còn null của tác phẩm đã có (cùng id).
     *
     * @return true nếu vừa tạo hoặc vừa điền thêm, false nếu đã đủ
     */
    private boolean saveWork(WorkPlan w, UUID categoryId, UUID seriesId, List<UUID> authors) {
        Work current = works.findById(w.id()).orElse(null);
        if (current == null) {
            works.save(Work.restore(w.id(), null, w.title(), w.originalTitle(), w.originalLanguage(), w.author(), null,
                    categoryId, seriesId, w.seriesNumber(), w.seriesLabel(), WorkKind.valueOf(w.kind().name()), authors));
            return true;
        }
        UUID mergedSeries = first(current.seriesId(), seriesId);
        Integer mergedNumber = mergedSeries == null ? null : first(current.seriesNumber(), w.seriesNumber());
        Work merged = current.update(current.title(), first(current.originalTitle(), w.originalTitle()),
                first(current.originalLanguage(), w.originalLanguage()), current.author(), current.description(),
                first(current.categoryId(), categoryId), mergedSeries, mergedNumber,
                mergedSeries == null ? null : first(current.seriesLabel(), w.seriesLabel()), current.kind(),
                current.authorIds().isEmpty() ? authors : current.authorIds());
        if (sameWork(current, merged)) return false;
        works.save(merged);
        return true;
    }

    private static boolean sameWork(Work a, Work b) {
        return Objects.equals(a.originalTitle(), b.originalTitle())
                && Objects.equals(a.originalLanguage(), b.originalLanguage())
                && Objects.equals(a.categoryId(), b.categoryId()) && Objects.equals(a.seriesId(), b.seriesId())
                && Objects.equals(a.seriesNumber(), b.seriesNumber()) && Objects.equals(a.seriesLabel(), b.seriesLabel())
                && Objects.equals(a.authorIds(), b.authorIds());
    }

    private static TitleExtras extrasOf(TitlePlan t) {
        return new TitleExtras(t.subtitle(), t.publishYear(), t.edition(), t.language(), t.pageCount(),
                t.format() == null ? null : TitleFormat.valueOf(t.format()), t.translator(), t.series(),
                t.seriesNumber(), t.coverImageUrl(), t.tags());
    }

    /** Giữ giá trị hiện có, chỉ lấy giá trị kế hoạch ở các trường hiện còn null. */
    static ReferenceExtras fillNulls(ReferenceExtras cur, ReferenceExtras plan) {
        return new ReferenceExtras(first(cur.birthYear(), plan.birthYear()), first(cur.deathYear(), plan.deathYear()),
                first(cur.nationality(), plan.nationality()), first(cur.photoUrl(), plan.photoUrl()),
                first(cur.website(), plan.website()), first(cur.country(), plan.country()),
                first(cur.foundedYear(), plan.foundedYear()), first(cur.address(), plan.address()),
                first(cur.floor(), plan.floor()), first(cur.zone(), plan.zone()),
                first(cur.capacity(), plan.capacity()));
    }

    private static <T> T first(T current, T planned) {
        return current != null ? current : planned;
    }

    /** @return true nếu vừa tạo hoặc vừa điền thông tin bổ sung, false nếu đã có đủ (cùng id). Cùng tên khác id nghĩa là tenant không sạch: lỗi rõ ràng. */
    private boolean saveReference(ReferenceKind kind, UUID id, String name, ReferenceExtras extras) {
        Reference existing = references.findById(kind, id).orElse(null);
        if (existing != null) {
            // Đã có: chỉ điền các trường bổ sung còn null, không đè giá trị đã có.
            ReferenceExtras merged = fillNulls(existing.extras(), extras);
            if (merged.equals(existing.extras())) return false;
            references.save(existing.withExtras(merged));
            return true;
        }
        Reference ref = Reference.restore(id, kind, null, name, null, null, true, extras);
        if (references.existsByNameKey(kind, ref.nameKey(), null)) {
            throw new IllegalStateException("Tenant đã có " + kind.label() + " '" + name
                    + "' nhưng không phải bản ghi demo; seed chỉ chạy trên tenant demo sạch");
        }
        references.save(ref);
        return true;
    }
}
