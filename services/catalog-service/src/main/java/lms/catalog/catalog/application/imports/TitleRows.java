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
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleExtras;
import lms.catalog.catalog.domain.TitleFormat;
import lms.catalog.catalog.domain.TitlePreviewTooLongException;
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

    /** Đầu sách đã dựng (chưa gắn phân loại chuẩn) cùng tên tham chiếu cần tra. */
    record TitleDraft(Title title, List<String> authorNames, String publisherName, String categoryName) {
    }

    /** Tham chiếu đã tra theo lô, khóa là {@link Reference#keyOf}. */
    record RefLookups(Map<String, Reference> authors, Map<String, Reference> publishers,
                      Map<String, Reference> categories, Map<String, Reference> shelves) {

        static RefLookups load(ReferenceRepository repository, Collection<TitleDraft> drafts,
                               Collection<String> shelfCodes) {
            Set<String> authorKeys = new HashSet<>();
            Set<String> publisherKeys = new HashSet<>();
            Set<String> categoryKeys = new HashSet<>();
            for (TitleDraft d : drafts) {
                d.authorNames().forEach(n -> authorKeys.add(Reference.keyOf(n)));
                if (d.publisherName() != null) publisherKeys.add(Reference.keyOf(d.publisherName()));
                if (d.categoryName() != null) categoryKeys.add(Reference.keyOf(d.categoryName()));
            }
            Set<String> shelfKeys = new HashSet<>();
            shelfCodes.forEach(c -> shelfKeys.add(Reference.keyOf(c)));
            return new RefLookups(fetch(repository, ReferenceKind.AUTHOR, authorKeys),
                    fetch(repository, ReferenceKind.PUBLISHER, publisherKeys),
                    fetch(repository, ReferenceKind.CATEGORY, categoryKeys),
                    fetch(repository, ReferenceKind.SHELF_LOCATION, shelfKeys));
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
        List<String> authors = ImportValues.list(row, "authors");
        if (new LinkedHashSet<>(authors.stream().map(Reference::keyOf).toList()).size()
                > TitleClassificationService.MAX_AUTHORS) {
            throw new RowProblem("authors", "TOO_MANY_AUTHORS",
                    "Tối đa " + TitleClassificationService.MAX_AUTHORS + " tác giả cho một đầu sách");
        }
        return new TitleDraft(title, authors, ImportValues.text(row, "publisher"), ImportValues.text(row, "category"));
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
                {"Tập thứ", "seriesNumber"}, {"Ảnh bìa", "coverImageUrl"}, {"Nhãn", "tags"}, {"Tối đa 10 nhãn", "tags"}};
        for (String[] e : map) if (message.startsWith(e[0])) return e[1];
        return null;
    }
}
