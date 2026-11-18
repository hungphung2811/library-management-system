package lms.catalog.catalog.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Tác phẩm (ADR-0026): nội dung trừu tượng, gom nhiều ấn bản ({@link Title}). Giữ trường cấp tác phẩm: tên, tên gốc,
 * ngôn ngữ gốc, tác giả (chuỗi hiển thị và danh sách chuẩn), mô tả, thể loại, bộ sách + số tập + nhãn, loại.
 * Bất biến; sai quy tắc thì ném {@link InvalidWorkException}. Java thuần.
 */
public final class Work {

    public static final int MAX_TITLE = 255;
    public static final int MAX_AUTHOR = 255;
    public static final int MAX_DESCRIPTION = 500;
    public static final int MAX_SERIES_LABEL = 50;
    public static final int MAX_AUTHORS = 20;

    private final UUID id;
    private final UUID tenantId;
    private final String title;
    private final String originalTitle;
    private final String originalLanguage;
    private final String author;
    private final String description;
    private final UUID categoryId;
    private final UUID seriesId;
    private final Integer seriesNumber;
    private final String seriesLabel;
    private final WorkKind kind;
    private final List<UUID> authorIds;

    private Work(UUID id, UUID tenantId, String title, String originalTitle, String originalLanguage, String author,
                 String description, UUID categoryId, UUID seriesId, Integer seriesNumber, String seriesLabel,
                 WorkKind kind, List<UUID> authorIds) {
        if (id == null) throw new InvalidWorkException("id không được null");
        this.id = id;
        this.tenantId = tenantId;
        this.title = required(title, MAX_TITLE, "Tên tác phẩm");
        this.originalTitle = optional(originalTitle, MAX_TITLE, "Tên gốc");
        this.originalLanguage = language(originalLanguage);
        this.author = required(author, MAX_AUTHOR, "Tác giả");
        this.description = optional(description, MAX_DESCRIPTION, "Mô tả");
        this.categoryId = categoryId;
        this.seriesId = seriesId;
        if (seriesNumber != null && seriesNumber <= 0) throw new InvalidWorkException("Tập thứ phải > 0");
        if (seriesNumber != null && seriesId == null) {
            throw new InvalidWorkException("Tập thứ chỉ hợp lệ khi có bộ sách");
        }
        this.seriesNumber = seriesNumber;
        this.seriesLabel = optional(seriesLabel, MAX_SERIES_LABEL, "Nhãn tập");
        this.kind = kind == null ? WorkKind.SINGLE : kind;
        List<UUID> ids = authorIds == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(authorIds.stream().filter(Objects::nonNull).toList()));
        if (ids.size() > MAX_AUTHORS) {
            throw new InvalidWorkException("Tối đa " + MAX_AUTHORS + " tác giả cho một tác phẩm");
        }
        this.authorIds = List.copyOf(ids);
    }

    public static Work create(String title, String originalTitle, String originalLanguage, String author,
                              String description, UUID categoryId, UUID seriesId, Integer seriesNumber,
                              String seriesLabel, WorkKind kind, List<UUID> authorIds) {
        return new Work(UUID.randomUUID(), null, title, originalTitle, originalLanguage, author, description,
                categoryId, seriesId, seriesNumber, seriesLabel, kind, authorIds);
    }

    /** Dựng lại từ DB (đã kiểm khi ghi). */
    public static Work restore(UUID id, UUID tenantId, String title, String originalTitle, String originalLanguage,
                               String author, String description, UUID categoryId, UUID seriesId, Integer seriesNumber,
                               String seriesLabel, WorkKind kind, List<UUID> authorIds) {
        return new Work(id, tenantId, title, originalTitle, originalLanguage, author, description, categoryId,
                seriesId, seriesNumber, seriesLabel, kind, authorIds);
    }

    /** Bản đã thay toàn bộ trường (cùng id, tenant). */
    public Work update(String title, String originalTitle, String originalLanguage, String author, String description,
                       UUID categoryId, UUID seriesId, Integer seriesNumber, String seriesLabel, WorkKind kind,
                       List<UUID> authorIds) {
        return new Work(id, tenantId, title, originalTitle, originalLanguage, author, description, categoryId,
                seriesId, seriesNumber, seriesLabel, kind, authorIds);
    }

    /** Bản đã thay thể loại, tác giả chuẩn và bộ sách (đường tương thích: sửa trường cấp tác phẩm qua một ấn bản). */
    public Work withClassification(UUID categoryId, List<UUID> authorIds, UUID seriesId, Integer seriesNumber) {
        return new Work(id, tenantId, title, originalTitle, originalLanguage, author, description, categoryId,
                seriesId, seriesNumber, seriesId == null ? null : seriesLabel, kind, authorIds);
    }

    /** Bản đã hợp thêm tác giả chuẩn của tác phẩm nguồn (giữ thứ tự, bỏ trùng) khi gộp. */
    public Work withMergedAuthors(List<UUID> more) {
        List<UUID> all = new ArrayList<>(authorIds);
        for (UUID a : more) if (!all.contains(a)) all.add(a);
        return new Work(id, tenantId, title, originalTitle, originalLanguage, author, description, categoryId,
                seriesId, seriesNumber, seriesLabel, kind, all);
    }

    private static String required(String raw, int max, String field) {
        String s = optional(raw, max, field);
        if (s == null) throw new InvalidWorkException(field + " không được để trống");
        return s;
    }

    private static String optional(String raw, int max, String field) {
        if (raw == null) return null;
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
        if (s.isEmpty()) return null;
        if (s.codePointCount(0, s.length()) > max) throw new InvalidWorkException(field + " tối đa " + max + " ký tự");
        return s;
    }

    private static String language(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (!s.matches("[a-z]{2,8}")) {
            throw new InvalidWorkException("Ngôn ngữ gốc phải gồm 2 đến 8 chữ cái thường (vi, en, fr...)");
        }
        return s;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public String title() { return title; }
    public String originalTitle() { return originalTitle; }
    public String originalLanguage() { return originalLanguage; }
    public String author() { return author; }
    public String description() { return description; }
    public UUID categoryId() { return categoryId; }
    public UUID seriesId() { return seriesId; }
    public Integer seriesNumber() { return seriesNumber; }
    public String seriesLabel() { return seriesLabel; }
    public WorkKind kind() { return kind; }
    public List<UUID> authorIds() { return authorIds; }
}
