package lms.catalog.catalog.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lms.catalog.catalog.application.imports.ReferenceCachePort;
import lms.catalog.catalog.domain.InactiveReferenceException;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceNotFoundException;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.catalog.domain.Work;
import lms.catalog.catalog.domain.WorkKind;
import lms.catalog.catalog.domain.WorkRepository;
import lms.catalog.inventory.application.CopyAvailabilityQuery;

/**
 * Phần dùng chung của các use case làm việc với tác phẩm (ADR-0026): tra tác phẩm + bộ sách theo lô (một truy vấn cho
 * cả danh sách, không N+1), phủ trường cấp tác phẩm lên ấn bản, tạo tác phẩm 1:1 cho ấn bản chưa gắn, ghi gương
 * trường cấp tác phẩm lên mọi ấn bản (giai đoạn chuyển tiếp R1 -> R2, mục 4.3) và đếm bản sao sẵn có theo tác phẩm.
 * POJO: bean tạo trong CatalogBeanConfiguration.
 */
public class WorkService {

    private final WorkRepository works;
    private final TitleRepository titles;
    private final ReferenceRepository references;
    private final CopyAvailabilityQuery availability;
    private final ReferenceCachePort referenceCache;

    public WorkService(WorkRepository works, TitleRepository titles, ReferenceRepository references,
                       CopyAvailabilityQuery availability, ReferenceCachePort referenceCache) {
        this.works = works;
        this.titles = titles;
        this.references = references;
        this.availability = availability;
        this.referenceCache = referenceCache;
    }

    /** Một tác phẩm kèm tên bộ sách đã tra. */
    public record WorkView(Work work, String seriesName) {
    }

    /** Thông tin tác phẩm gắn vào phản hồi của một ấn bản. */
    public record WorkInfo(UUID workId, String workTitle, String originalTitle, String originalLanguage, UUID seriesId,
                           String seriesName, long workAvailableCopies) {
    }

    /** Kết quả nạp cho một danh sách ấn bản: ấn bản đã phủ trường cấp tác phẩm và thông tin tác phẩm theo titleId. */
    public record Enriched(List<Title> titles, Map<UUID, WorkInfo> info) {
        public static final Enriched NONE = new Enriched(List.of(), Map.of());
    }

    /**
     * Dạng tra cứu cho service khác (sales, circulation): kèm workId, tên tác phẩm, bộ sách; một lô tác phẩm (+ bộ sách) cho cả danh sách.
     */
    public List<TitleLookupView> lookupViews(List<Title> source) {
        Map<UUID, WorkView> views = views(source.stream().map(Title::workId).filter(Objects::nonNull).toList());
        return source.stream().map(t -> TitleLookupView.from(t, t.workId() == null ? null : views.get(t.workId())))
                .toList();
    }

    /** Tác phẩm theo id kèm tên bộ sách: một truy vấn tác phẩm (+ tác giả) và một truy vấn bộ sách cho cả tập. */
    public Map<UUID, WorkView> views(Collection<UUID> workIds) {
        Set<UUID> ids = workIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) return Map.of();
        List<Work> found = works.findByIds(ids);
        Map<UUID, String> series = seriesNames(found.stream().map(Work::seriesId).filter(Objects::nonNull).toList());
        Map<UUID, WorkView> result = new HashMap<>();
        for (Work w : found) result.put(w.id(), new WorkView(w, w.seriesId() == null ? null : series.get(w.seriesId())));
        return result;
    }

    /** Tên bộ sách theo id, một truy vấn. */
    public Map<UUID, String> seriesNames(Collection<UUID> seriesIds) {
        Set<UUID> ids = new LinkedHashSet<>(seriesIds);
        if (ids.isEmpty()) return Map.of();
        return references.findByIds(ReferenceKind.SERIES, ids).stream()
                .collect(Collectors.toMap(Reference::id, Reference::name));
    }

    /**
     * Phủ trường cấp tác phẩm (thể loại, tác giả chuẩn, bộ sách + số tập) từ work lên từng ấn bản có {@code workId} và
     * tính {@link WorkInfo} (kèm số bản sao sẵn có của tác phẩm CÙNG NGÔN NGỮ với ấn bản, mục 3.5). Hằng số truy vấn
     * theo kích thước danh sách: tác phẩm (1) + tác giả (1) + bộ sách (1) + đếm bản sao (1).
     */
    public Enriched enrich(List<Title> source) {
        Set<UUID> workIds = source.stream().map(Title::workId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (workIds.isEmpty()) return new Enriched(source, Map.of());
        Map<UUID, WorkView> views = views(workIds);
        List<CopyAvailabilityQuery.WorkLanguageCount> counts = availability.countAvailableByWorkAndLanguage(workIds);
        List<Title> effective = new ArrayList<>(source.size());
        Map<UUID, WorkInfo> info = new HashMap<>();
        for (Title t : source) {
            WorkView v = t.workId() == null ? null : views.get(t.workId());
            if (v == null) {
                effective.add(t);
                continue;
            }
            Work w = v.work();
            effective.add(t.withWorkLevel(w.categoryId(), w.authorIds(), v.seriesName(), w.seriesNumber()));
            long sameLanguage = counts.stream().filter(c -> c.workId().equals(w.id()))
                    .filter(c -> t.extras().language() == null || t.extras().language().equals(c.language()))
                    .mapToLong(CopyAvailabilityQuery.WorkLanguageCount::count).sum();
            info.put(t.id(), new WorkInfo(w.id(), w.title(), w.originalTitle(), w.originalLanguage(), w.seriesId(),
                    v.seriesName(), sameLanguage));
        }
        return new Enriched(effective, info);
    }

    /** Tác phẩm 1:1 cho ấn bản chưa gắn tác phẩm (tên, tác giả, thể loại, tác giả chuẩn, bộ sách từ chuỗi series). */
    public Work createFromTitle(Title t) {
        String series = t.extras().series();
        UUID seriesId = series == null ? null : resolveSeriesIdByName(series);
        Work work = Work.create(t.name(), null, null, t.author(), t.description(), t.categoryId(), seriesId,
                seriesId == null ? null : t.extras().seriesNumber(), null, WorkKind.SINGLE, t.authorIds());
        return works.save(work);
    }

    /** Id bộ sách theo tên (so khóa chữ thường đã chuẩn hóa); chưa có thì tạo và xóa cache danh mục bộ sách. */
    public UUID resolveSeriesIdByName(String name) {
        String key = Reference.keyOf(name);
        List<Reference> found = references.findByNameKeys(ReferenceKind.SERIES, List.of(key));
        if (!found.isEmpty()) return found.get(0).id();
        Reference created = references.save(Reference.create(ReferenceKind.SERIES, name, null, null));
        if (referenceCache != null) referenceCache.evict(ReferenceKind.SERIES);
        return created.id();
    }

    /** Bộ sách phải tồn tại và đang hoạt động, trừ khi đó là giá trị đang có trên tác phẩm. */
    public void requireSeriesUsable(UUID seriesId, UUID currentSeriesId) {
        if (seriesId == null || seriesId.equals(currentSeriesId)) return;
        Reference r = references.findById(ReferenceKind.SERIES, seriesId)
                .orElseThrow(() -> new ReferenceNotFoundException(ReferenceKind.SERIES, seriesId));
        if (!r.active()) throw new InactiveReferenceException(ReferenceKind.SERIES, seriesId);
    }

    /** Thể loại phải tồn tại và đang hoạt động, trừ khi đó là giá trị đang có trên tác phẩm. */
    public void requireCategoryUsable(UUID categoryId, UUID currentCategoryId) {
        if (categoryId == null || categoryId.equals(currentCategoryId)) return;
        Reference r = references.findById(ReferenceKind.CATEGORY, categoryId)
                .orElseThrow(() -> new ReferenceNotFoundException(ReferenceKind.CATEGORY, categoryId));
        if (!r.active()) throw new InactiveReferenceException(ReferenceKind.CATEGORY, categoryId);
    }

    /** Tác giả chuẩn phải tồn tại và đang hoạt động, trừ khi đã có trên tác phẩm. */
    public void requireAuthorsUsable(List<UUID> authorIds, List<UUID> current) {
        if (authorIds == null || authorIds.isEmpty()) return;
        Map<UUID, Reference> found = references.findByIds(ReferenceKind.AUTHOR, authorIds).stream()
                .collect(Collectors.toMap(Reference::id, r -> r));
        Set<UUID> existing = Set.copyOf(current);
        for (UUID id : authorIds) {
            Reference r = found.get(id);
            if (r == null) throw new ReferenceNotFoundException(ReferenceKind.AUTHOR, id);
            if (!r.active() && !existing.contains(id)) throw new InactiveReferenceException(ReferenceKind.AUTHOR, id);
        }
    }

    /**
     * Chi tiết tác phẩm: ấn bản (1 + tác giả ấn bản 1), số bản sao sẵn có theo ấn bản (1), tên bộ sách (1), thể loại (1),
     * tác giả chuẩn (1): hằng số theo số ấn bản.
     */
    public WorkDtos.WorkDetail detail(Work w) {
        List<Title> editions = titles.findByWorkIds(List.of(w.id()));
        Map<UUID, Long> counts = availability.countAvailable(editions.stream().map(Title::id).toList());
        String seriesName = w.seriesId() == null ? null : seriesNames(List.of(w.seriesId())).get(w.seriesId());
        String categoryName = w.categoryId() == null ? null
                : references.findById(ReferenceKind.CATEGORY, w.categoryId()).map(Reference::name).orElse(null);
        Map<UUID, String> authorNames = w.authorIds().isEmpty() ? Map.of()
                : references.findByIds(ReferenceKind.AUTHOR, w.authorIds()).stream()
                        .collect(Collectors.toMap(Reference::id, Reference::name));
        List<AuthorRef> authors = w.authorIds().stream().filter(authorNames::containsKey)
                .map(id -> new AuthorRef(id, authorNames.get(id))).toList();
        List<WorkDtos.EditionSummary> items = editions.stream().map(t -> new WorkDtos.EditionSummary(t.id(), t.isbn(),
                t.name(), t.extras().format(), t.extras().language(), t.extras().translator(), t.extras().edition(),
                t.extras().publicationYear(), t.partNumber(), t.partLabel(), t.coverPrice(), t.extras().coverImageUrl(),
                counts.getOrDefault(t.id(), 0L))).toList();
        return new WorkDtos.WorkDetail(w.id(), w.title(), w.originalTitle(), w.originalLanguage(), w.author(), authors,
                w.description(), w.categoryId(), categoryName, w.seriesId(), seriesName, w.seriesNumber(),
                w.seriesLabel(), w.kind(), w.tenantId(), items);
    }

    /**
     * Dòng danh sách cho một trang tác phẩm: ấn bản của cả trang (1 + 1), đếm bản sao sẵn có theo tác phẩm (1), tên bộ sách
     * (1): không phụ thuộc số dòng.
     */
    public List<WorkDtos.WorkSummary> summaries(List<Work> page) {
        if (page.isEmpty()) return List.of();
        List<UUID> ids = page.stream().map(Work::id).toList();
        Map<UUID, List<Title>> editions = new HashMap<>();
        for (Title t : titles.findByWorkIds(ids)) editions.computeIfAbsent(t.workId(), k -> new ArrayList<>()).add(t);
        Map<UUID, Long> available = availability.countAvailableByWork(ids);
        Map<UUID, String> series = seriesNames(page.stream().map(Work::seriesId).filter(Objects::nonNull).toList());
        return page.stream().map(w -> {
            List<Title> eds = editions.getOrDefault(w.id(), List.of());
            String cover = eds.stream().map(t -> t.extras().coverImageUrl()).filter(Objects::nonNull).findFirst()
                    .orElse(null);
            return new WorkDtos.WorkSummary(w.id(), w.title(), w.originalTitle(), w.author(), w.seriesId(),
                    w.seriesId() == null ? null : series.get(w.seriesId()), w.seriesNumber(), w.kind(), eds.size(),
                    available.getOrDefault(w.id(), 0L), cover);
        }).toList();
    }

    /**
     * Ghi gương trường cấp tác phẩm lên MỌI ấn bản của tác phẩm (một UPDATE + thay title_authors theo lô), trong cùng
     * transaction với việc ghi work (ADR-0026 mục 4.3): rollback ứng dụng về R0 vẫn thấy dữ liệu đúng.
     */
    public void mirror(Work work) {
        String seriesName = work.seriesId() == null ? null : seriesNames(List.of(work.seriesId())).get(work.seriesId());
        titles.mirrorWorkFields(work.id(), work.categoryId(), seriesName, seriesName == null ? null : work.seriesNumber(),
                work.authorIds());
    }
}
