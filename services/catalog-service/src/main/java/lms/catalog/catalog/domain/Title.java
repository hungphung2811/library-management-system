package lms.catalog.catalog.domain;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.UUID;

/** Đầu sách. Giá bìa là cơ sở tính phí bồi thường/hỏng sách (QĐ4) ở context fine. */
public class Title {

    public static final int MAX_DESCRIPTION = 500;
    public static final int MAX_PREVIEW_TEXT = 2000;

    private final UUID id;
    private final String name;
    private final String author;
    private final String isbn;
    private final BigDecimal coverPrice;
    /** Tenant sở hữu (chỉ để hiển thị khi đọc; null với đối tượng mới tạo chưa lưu). */
    private final UUID tenantId;
    /** Mô tả ngắn (văn bản thuần, tối đa 500 code point), null nếu chưa nhập. */
    private final String description;
    /** Đoạn đọc thử (văn bản thuần, tối đa 2000 code point), null nếu chưa nhập. */
    private final String previewText;
    /** Nhà xuất bản chuẩn (tùy chọn, tham chiếu publishers). */
    private final UUID publisherId;
    /** Thể loại chuẩn (tùy chọn, tham chiếu categories). */
    private final UUID categoryId;
    /** Tác giả chuẩn (tùy chọn, nhiều tác giả theo thứ tự); không đổi chuỗi {@code author} hiển thị cũ. */
    private final List<UUID> authorIds;
    /** Thông tin bổ sung tùy chọn (không bao giờ null; TitleExtras.EMPTY nếu chưa nhập). */
    private final TitleExtras extras;

    private Title(UUID id, String name, String author, String isbn, BigDecimal coverPrice, UUID tenantId,
                  String description, String previewText, UUID publisherId, UUID categoryId, List<UUID> authorIds,
                  TitleExtras extras) {
        if (id == null) throw new InvalidTitleException("id không được null");
        if (name == null || name.isBlank()) throw new InvalidTitleException("Tên sách không được để trống");
        if (author == null || author.isBlank()) throw new InvalidTitleException("Tác giả không được để trống");
        if (coverPrice == null || coverPrice.signum() < 0) throw new InvalidTitleException("Giá bìa phải >= 0");
        this.id = id;
        this.name = name.trim();
        this.author = author.trim();
        this.isbn = (isbn == null || isbn.isBlank()) ? null : isbn.trim();
        this.coverPrice = coverPrice;
        this.tenantId = tenantId;
        this.description = description;
        this.previewText = previewText;
        this.publisherId = publisherId;
        this.categoryId = categoryId;
        this.authorIds = authorIds == null ? List.of() : List.copyOf(authorIds);
        this.extras = extras == null ? TitleExtras.EMPTY : extras;
    }

    public static Title create(String name, String author, String isbn, BigDecimal coverPrice) {
        return new Title(UUID.randomUUID(), name, author, isbn, coverPrice, null, null, null, null, null, null, null);
    }

    public static Title restore(UUID id, String name, String author, String isbn, BigDecimal coverPrice) {
        return new Title(id, name, author, isbn, coverPrice, null, null, null, null, null, null, null);
    }

    public static Title restore(UUID id, String name, String author, String isbn, BigDecimal coverPrice, UUID tenantId) {
        return new Title(id, name, author, isbn, coverPrice, tenantId, null, null, null, null, null, null);
    }

    /** Dựng lại từ DB: dữ liệu đã được chuẩn hóa và kiểm khi ghi nên giữ nguyên. */
    public static Title restore(UUID id, String name, String author, String isbn, BigDecimal coverPrice, UUID tenantId,
                                String description, String previewText) {
        return new Title(id, name, author, isbn, coverPrice, tenantId, description, previewText, null, null, null, null);
    }

    /** Dựng lại từ DB kèm phân loại chuẩn (nhà xuất bản, thể loại, tác giả). */
    public static Title restore(UUID id, String name, String author, String isbn, BigDecimal coverPrice, UUID tenantId,
                                String description, String previewText, UUID publisherId, UUID categoryId,
                                List<UUID> authorIds) {
        return new Title(id, name, author, isbn, coverPrice, tenantId, description, previewText, publisherId,
                categoryId, authorIds, null);
    }

    /** Dựng lại từ DB kèm cả thông tin bổ sung. */
    public static Title restore(UUID id, String name, String author, String isbn, BigDecimal coverPrice, UUID tenantId,
                                String description, String previewText, UUID publisherId, UUID categoryId,
                                List<UUID> authorIds, TitleExtras extras) {
        return new Title(id, name, author, isbn, coverPrice, tenantId, description, previewText, publisherId,
                categoryId, authorIds, extras);
    }

    /** Bản đã thay thông tin bổ sung (cùng id, giữ các trường khác); null nghĩa là xóa hết. */
    public Title withExtras(TitleExtras extras) {
        return new Title(id, name, author, isbn, coverPrice, tenantId, description, previewText, publisherId,
                categoryId, authorIds, extras);
    }

    /** Bản đã thay phân loại chuẩn (cùng id, giữ các trường khác). Đã kiểm tồn tại ở use case. */
    public Title withClassification(UUID publisherId, UUID categoryId, List<UUID> authorIds) {
        return new Title(id, name, author, isbn, coverPrice, tenantId, description, previewText, publisherId,
                categoryId, authorIds, extras);
    }

    /** Bản đã sửa (cùng id): thay name/author/isbn/coverPrice, kiểm quy tắc như khi tạo; giữ mô tả, đoạn đọc thử và phân loại. */
    public Title update(String name, String author, String isbn, BigDecimal coverPrice) {
        return new Title(id, name, author, isbn, coverPrice, tenantId, description, previewText, publisherId,
                categoryId, authorIds, extras);
    }

    /**
     * Bản đã thay mô tả và đoạn đọc thử (cùng id, giữ các trường khác). Chuẩn hóa: Unicode NFC, CRLF và CR thành LF,
     * bỏ ký tự điều khiển trừ LF và TAB, cắt khoảng trắng đầu/cuối, rỗng thành null. Độ dài đếm theo code point;
     * vượt giới hạn thì ném {@link TitlePreviewTooLongException}.
     */
    public Title withPreview(String description, String previewText) {
        return new Title(id, name, author, isbn, coverPrice, tenantId,
                normalizeText(description, MAX_DESCRIPTION, "Mô tả"),
                normalizeText(previewText, MAX_PREVIEW_TEXT, "Đoạn đọc thử"), publisherId, categoryId, authorIds, extras);
    }

    static String normalizeText(String raw, int max, String field) {
        if (raw == null) return null;
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC).replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (Character.getType(cp) == Character.CONTROL && cp != '\n' && cp != '\t') return;
            sb.appendCodePoint(cp);
        });
        String result = sb.toString().strip();
        if (result.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp))) return null;
        if (result.codePointCount(0, result.length()) > max) throw new TitlePreviewTooLongException(field, max);
        return result;
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public String author() { return author; }
    public String isbn() { return isbn; }
    public BigDecimal coverPrice() { return coverPrice; }
    public UUID tenantId() { return tenantId; }
    public String description() { return description; }
    public String previewText() { return previewText; }
    public UUID publisherId() { return publisherId; }
    public UUID categoryId() { return categoryId; }
    public List<UUID> authorIds() { return authorIds; }
    public TitleExtras extras() { return extras; }
    public boolean hasPreview() { return previewText != null; }
}
