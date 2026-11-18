package lms.catalog.catalog.application.imports;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.catalog.catalog.application.TitleClassificationService;
import lms.catalog.catalog.domain.InvalidTitleException;
import lms.catalog.catalog.domain.InvalidWorkException;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleExtras;
import lms.catalog.catalog.domain.TitleFormat;
import lms.catalog.catalog.domain.TitlePreviewTooLongException;
import lms.catalog.catalog.domain.Work;
import lms.catalog.catalog.domain.WorkKind;
import lms.catalog.inventory.application.CopyDraft;
import lms.imports.core.ImportRow;
import lms.imports.core.RowIssue;

/**
 * Dựng đầu sách/bản sao từ dòng nhập bằng đúng luật domain ({@link Title#create}, {@link TitleExtras},
 * {@link Title#withPreview}, và {@code RegisterCopiesInBatchUseCase#check} cho bản sao) và tra tham chiếu theo lô.
 * Dùng chung cho kiểm lúc upload và kiểm lại lúc confirm để hai nơi không lệch luật.
 */
final class TitleRows {

    static final String LENDING = "LENDING";
    static final String ALREADY_EXISTS = "ALREADY_EXISTS";

    private TitleRows() {
    }

    /** Cột cấp tác phẩm của dòng (v2, ADR-0026 mục 3.8); cột trống là null. {@code sameWorkAsIsbn} null = tác phẩm mới. */
    record WorkDraft(String sameWorkAsIsbn, String workTitle, String originalTitle, String originalLanguage,
                     String seriesLabel) {
        static final WorkDraft NONE = new WorkDraft(null, null, null, null, null);
    }

    /** Đầu sách đã dựng (chưa gắn phân loại chuẩn) cùng tên tham chiếu cần tra. */
    record TitleDraft(Title title, List<String> authorNames, String publisherName, String categoryName,
                      WorkDraft work) {

        TitleDraft(Title title, List<String> authorNames, String publisherName, String categoryName) {
            this(title, authorNames, publisherName, categoryName, WorkDraft.NONE);
        }

        /** Tên bộ sách ghi ở dòng (cột {@code series}), hoặc null. */
        String seriesName() {
            return title.extras().series();
        }
    }

    /**
     * Trường cấp tác phẩm đã quy về id để so sánh giữa dòng và tác phẩm đích (cảnh báo WORK_FIELD_IGNORED). Trường trống
     * (null / danh sách rỗng) ở phía dòng nghĩa là "không nêu", không tính là khác.
     */
    record WorkFacts(String title, String originalTitle, String originalLanguage, String author, UUID categoryId,
                     UUID seriesId, Integer seriesNumber, String seriesLabel, List<UUID> authorIds) {

        /** Tên các cột của dòng có giá trị khác {@code target}; chỉ so những cột dòng có nêu. */
        List<String> differencesFrom(WorkFacts target) {
            List<String> out = new ArrayList<>();
            if (title != null && !title.equals(target.title())) out.add("workTitle");
            if (originalTitle != null && !originalTitle.equals(target.originalTitle())) out.add("originalTitle");
            if (originalLanguage != null && !originalLanguage.equals(target.originalLanguage())) {
                out.add("originalLanguage");
            }
            if (author != null && !author.equals(target.author())) out.add("author");
            if (categoryId != null && !categoryId.equals(target.categoryId())) out.add("category");
            if (seriesId != null && !seriesId.equals(target.seriesId())) out.add("series");
            if (seriesNumber != null && !seriesNumber.equals(target.seriesNumber())) out.add("seriesNumber");
            if (seriesLabel != null && !seriesLabel.equals(target.seriesLabel())) out.add("seriesLabel");
            if (!authorIds.isEmpty() && !new HashSet<>(authorIds).equals(new HashSet<>(target.authorIds()))) {
                out.add("authors");
            }
            return out;
        }
    }

    /** Tham chiếu đã tra theo lô, khóa là {@link Reference#keyOf}. */
    record RefLookups(Map<String, Reference> authors, Map<String, Reference> publishers,
                      Map<String, Reference> categories, Map<String, Reference> shelves,
                      Map<String, Reference> series) {

        RefLookups(Map<String, Reference> authors, Map<String, Reference> publishers,
                   Map<String, Reference> categories, Map<String, Reference> shelves) {
            this(authors, publishers, categories, shelves, Map.of());
        }

        static RefLookups load(ReferenceRepository repository, Collection<TitleDraft> drafts,
                               Collection<String> shelfCodes) {
            Set<String> authorKeys = new HashSet<>();
            Set<String> publisherKeys = new HashSet<>();
            Set<String> categoryKeys = new HashSet<>();
            Set<String> seriesKeys = new HashSet<>();
            for (TitleDraft d : drafts) {
                if (d.seriesName() != null) seriesKeys.add(Reference.keyOf(d.seriesName()));
                d.authorNames().forEach(n -> authorKeys.add(Reference.keyOf(n)));
                if (d.publisherName() != null) publisherKeys.add(Reference.keyOf(d.publisherName()));
                if (d.categoryName() != null) categoryKeys.add(Reference.keyOf(d.categoryName()));
            }
            Set<String> shelfKeys = new HashSet<>();
            shelfCodes.forEach(c -> shelfKeys.add(Reference.keyOf(c)));
            return new RefLookups(fetch(repository, ReferenceKind.AUTHOR, authorKeys),
                    fetch(repository, ReferenceKind.PUBLISHER, publisherKeys),
                    fetch(repository, ReferenceKind.CATEGORY, categoryKeys),
                    fetch(repository, ReferenceKind.SHELF_LOCATION, shelfKeys),
                    fetch(repository, ReferenceKind.SERIES, seriesKeys));
        }

        private static Map<String, Reference> fetch(ReferenceRepository repository, ReferenceKind kind,
                                                    Set<String> keys) {
            Map<String, Reference> out = new HashMap<>();
            if (keys.isEmpty()) return out;
            for (Reference r : repository.findByNameKeys(kind, keys)) out.put(r.nameKey(), r);
            return out;
        }
    }

    static TitleDraft draft(ImportRow row) {
        BigDecimal price = ImportValues.decimal(row, "coverPrice");
        Title title;
        try {
            title = Title.create(row.value("name"), row.value("author"), ImportValues.text(row, "isbn"), price);
        } catch (InvalidTitleException e) {
            throw new RowProblem(columnOf(e.getMessage()), "INVALID_TITLE", e.getMessage());
        }
        TitleFormat format = null;
        String rawFormat = ImportValues.text(row, "format");
        if (rawFormat != null) {
            try {
                format = TitleFormat.valueOf(rawFormat.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new RowProblem("format", "INVALID_ENUM", "Định dạng không hợp lệ");
            }
        }
        try {
            TitleExtras extras = new TitleExtras(ImportValues.text(row, "subtitle"),
                    ImportValues.integer(row, "publicationYear"), ImportValues.text(row, "edition"),
                    ImportValues.text(row, "language"), ImportValues.integer(row, "pageCount"), format,
                    ImportValues.text(row, "translator"), ImportValues.text(row, "series"),
                    ImportValues.integer(row, "seriesNumber"), ImportValues.text(row, "coverImageUrl"),
                    ImportValues.list(row, "tags"));
            title = title.withExtras(extras);
        } catch (InvalidTitleException e) {
            throw new RowProblem(columnOf(e.getMessage()), "INVALID_TITLE", e.getMessage());
        }
        try {
            title = title.withPreview(ImportValues.text(row, "description"), null);
        } catch (TitlePreviewTooLongException e) {
            throw new RowProblem("description", "TITLE_PREVIEW_TOO_LONG", e.getMessage());
        }
        try {
            title = title.withPart(ImportValues.integer(row, "partNumber"), ImportValues.text(row, "partLabel"));
        } catch (InvalidTitleException e) {
            throw new RowProblem(columnOf(e.getMessage()), "INVALID_TITLE", e.getMessage());
        }
        WorkDraft work = new WorkDraft(ImportValues.text(row, "sameWorkAsIsbn"), ImportValues.text(row, "workTitle"),
                ImportValues.text(row, "originalTitle"), ImportValues.text(row, "originalLanguage"),
                ImportValues.text(row, "seriesLabel"));
        checkWorkColumns(title, work);
        List<String> authors = ImportValues.list(row, "authors");
        if (new LinkedHashSet<>(authors.stream().map(Reference::keyOf).toList()).size()
                > TitleClassificationService.MAX_AUTHORS) {
            throw new RowProblem("authors", "TOO_MANY_AUTHORS",
                    "Tối đa " + TitleClassificationService.MAX_AUTHORS + " tác giả cho một đầu sách");
        }
        return new TitleDraft(title, authors, ImportValues.text(row, "publisher"), ImportValues.text(row, "category"),
                work);
    }

    /** Kiểm các cột cấp tác phẩm bằng đúng luật của {@link Work} (dùng khung tạm); sai thì RowProblem INVALID_WORK. */
    private static void checkWorkColumns(Title title, WorkDraft w) {
        UUID series = title.extras().series() == null ? null : UUID.randomUUID();
        try {
            Work.create(w.workTitle() == null ? title.name() : w.workTitle(), w.originalTitle(), w.originalLanguage(),
                    title.author(), title.description(), null, series, title.extras().seriesNumber(), w.seriesLabel(),
                    WorkKind.SINGLE, List.of());
        } catch (InvalidWorkException e) {
            throw new RowProblem(workColumnOf(e.getMessage()), "INVALID_WORK", e.getMessage());
        }
    }

    private static String workColumnOf(String message) {
        if (message == null) return null;
        String[][] map = {{"Tên tác phẩm", "workTitle"}, {"Tên gốc", "originalTitle"},
                {"Ngôn ngữ gốc", "originalLanguage"}, {"Nhãn tập", "seriesLabel"}};
        for (String[] e : map) if (message.startsWith(e[0])) return e[1];
        return null;
    }

    /** Tác phẩm mới 1:1 cho dòng chưa gắn tác phẩm: tên, tác giả, thể loại, tác giả chuẩn, bộ sách từ ấn bản đã phân loại. */
    static Work newWork(TitleDraft d, Title classified, RefLookups refs) {
        WorkDraft w = d.work();
        Reference series = d.seriesName() == null ? null : refs.series().get(Reference.keyOf(d.seriesName()));
        UUID seriesId = series == null ? null : series.id();
        try {
            return Work.create(w.workTitle() == null ? classified.name() : w.workTitle(), w.originalTitle(),
                    w.originalLanguage(), classified.author(), classified.description(), classified.categoryId(),
                    seriesId, seriesId == null ? null : classified.extras().seriesNumber(), w.seriesLabel(),
                    WorkKind.SINGLE, classified.authorIds());
        } catch (InvalidWorkException e) {
            throw new RowProblem(workColumnOf(e.getMessage()), "INVALID_WORK", e.getMessage());
        }
    }

    /** Trường cấp tác phẩm của dòng (đã tra tham chiếu) để so với tác phẩm đích; cột không nêu là null hoặc rỗng. */
    static WorkFacts factsOf(TitleDraft d, RefLookups refs) {
        WorkDraft w = d.work();
        Reference category = d.categoryName() == null ? null : refs.categories().get(Reference.keyOf(d.categoryName()));
        Reference series = d.seriesName() == null ? null : refs.series().get(Reference.keyOf(d.seriesName()));
        List<UUID> authorIds = new ArrayList<>();
        for (String n : d.authorNames()) {
            Reference a = refs.authors().get(Reference.keyOf(n));
            if (a != null && !authorIds.contains(a.id())) authorIds.add(a.id());
        }
        return new WorkFacts(w.workTitle(), w.originalTitle(), w.originalLanguage(), d.title().author(),
                category == null ? null : category.id(), series == null ? null : series.id(),
                d.title().extras().seriesNumber(), w.seriesLabel(), authorIds);
    }

    /** Trường cấp tác phẩm hiệu lực của tác phẩm do dòng này tạo (tên tác phẩm mặc định là tên sách). */
    static WorkFacts effectiveFactsOf(TitleDraft d, RefLookups refs) {
        WorkFacts f = factsOf(d, refs);
        return new WorkFacts(f.title() == null ? d.title().name() : f.title(), f.originalTitle(), f.originalLanguage(),
                f.author(), f.categoryId(), f.seriesId(), f.seriesNumber(), f.seriesLabel(), f.authorIds());
    }

    static WorkFacts factsOf(Work w) {
        return new WorkFacts(w.title(), w.originalTitle(), w.originalLanguage(), w.author(), w.categoryId(),
                w.seriesId(), w.seriesNumber(), w.seriesLabel(), w.authorIds());
    }

    /** Vấn đề về tham chiếu chưa có hoặc đã ngừng dùng (không tự tạo, ADR-0024 Q2). Rỗng nếu ổn. */
    static List<RowIssue> refIssues(TitleDraft d, RefLookups refs) {
        List<RowIssue> issues = new ArrayList<>();
        for (String name : d.authorNames()) {
            check(issues, "authors", ReferenceKind.AUTHOR, name, refs.authors().get(Reference.keyOf(name)));
        }
        if (d.publisherName() != null) {
            check(issues, "publisher", ReferenceKind.PUBLISHER, d.publisherName(),
                    refs.publishers().get(Reference.keyOf(d.publisherName())));
        }
        if (d.categoryName() != null) {
            check(issues, "category", ReferenceKind.CATEGORY, d.categoryName(),
                    refs.categories().get(Reference.keyOf(d.categoryName())));
        }
        if (d.seriesName() != null) {
            check(issues, "series", ReferenceKind.SERIES, d.seriesName(),
                    refs.series().get(Reference.keyOf(d.seriesName())));
        }
        return issues;
    }

    /** Vấn đề về vị trí kệ của bản sao; rỗng nếu không ghi mã hoặc mã hợp lệ. */
    static List<RowIssue> shelfIssues(ImportRow row, RefLookups refs) {
        String code = ImportValues.text(row, "shelfLocationCode");
        List<RowIssue> issues = new ArrayList<>();
        if (code != null) {
            check(issues, "shelfLocationCode", ReferenceKind.SHELF_LOCATION, code,
                    refs.shelves().get(Reference.keyOf(code)));
        }
        return issues;
    }

    private static void check(List<RowIssue> issues, String column, ReferenceKind kind, String name, Reference found) {
        if (found == null) {
            issues.add(RowIssue.error(column, kind.notFoundCode(), "Không tìm thấy " + kind.label() + " \"" + name + "\""));
        } else if (!found.active()) {
            issues.add(RowIssue.error(column, kind.inactiveCode(), "Đã ngừng sử dụng " + kind.label() + " \"" + name + "\""));
        }
    }

    /** Đầu sách kèm phân loại chuẩn đã tra (đã kiểm bằng {@link #refIssues}). */
    static Title classified(TitleDraft d, RefLookups refs) {
        UUID publisherId = d.publisherName() == null ? null : refs.publishers().get(Reference.keyOf(d.publisherName())).id();
        UUID categoryId = d.categoryName() == null ? null : refs.categories().get(Reference.keyOf(d.categoryName())).id();
        List<UUID> authorIds = new ArrayList<>(new LinkedHashSet<>(
                d.authorNames().stream().map(n -> refs.authors().get(Reference.keyOf(n)).id()).toList()));
        return d.title().withClassification(publisherId, categoryId, authorIds);
    }

    /** Bản sao thô từ dòng (chưa kiểm domain). {@code shelfId} đã tra từ mã vị trí kệ (có thể null). */
    static CopyDraft copyDraft(ImportRow row, UUID shelfId) {
        return new CopyDraft(row.value("barcode").strip(), shelfId, ImportValues.date(row, "acquiredDate"),
                ImportValues.text(row, "acquisitionSource"), ImportValues.longValue(row, "purchasePrice"),
                ImportValues.text(row, "conditionGrade"), ImportValues.text(row, "conditionNote"),
                ImportValues.date(row, "lastInventoryDate"));
    }

    static UUID shelfId(ImportRow row, RefLookups refs) {
        String code = ImportValues.text(row, "shelfLocationCode");
        if (code == null) return null;
        Reference shelf = refs.shelves().get(Reference.keyOf(code));
        return shelf == null ? null : shelf.id();
    }

    /** Đoán cột từ thông điệp domain (domain chỉ ném thông điệp, không kèm tên cột). */
    private static String columnOf(String message) {
        if (message == null) return null;
        String[][] map = {
                {"Tên sách", "name"}, {"Tác giả", "author"}, {"Giá bìa", "coverPrice"}, {"Phụ đề", "subtitle"},
                {"Năm xuất bản", "publicationYear"}, {"Lần tái bản", "edition"}, {"Ngôn ngữ", "language"},
                {"Số trang", "pageCount"}, {"Dịch giả", "translator"}, {"Bộ sách", "series"},
                {"Tập thứ", "seriesNumber"}, {"Số phần", "partNumber"}, {"Nhãn phần", "partLabel"},
                {"Ảnh bìa", "coverImageUrl"}, {"Nhãn", "tags"}, {"Tối đa 10 nhãn", "tags"}};
        for (String[] e : map) if (message.startsWith(e[0])) return e[1];
        return null;
    }
}
