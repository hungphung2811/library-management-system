package lms.catalog.catalog.domain;

import java.math.BigDecimal;
import java.text.Normalizer;
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

    private Title(UUID id, String name, String author, String isbn, BigDecimal coverPrice, UUID tenantId,
                  String description, String previewText) {
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
    }

    public static Title create(String name, String author, String isbn, BigDecimal coverPrice) {
        return new Title(UUID.randomUUID(), name, author, isbn, coverPrice, null, null, null);
    }

    public static Title restore(UUID id, String name, String author, String isbn, BigDecimal coverPrice) {
        return new Title(id, name, author, isbn, coverPrice, null, null, null);
    }

    public static Title restore(UUID id, String name, String author, String isbn, BigDecimal coverPrice, UUID tenantId) {
        return new Title(id, name, author, isbn, coverPrice, tenantId, null, null);
    }

    /** Dựng lại từ DB: dữ liệu đã được chuẩn hóa và kiểm khi ghi nên giữ nguyên. */
    public static Title restore(UUID id, String name, String author, String isbn, BigDecimal coverPrice, UUID tenantId,
                                String description, String previewText) {
        return new Title(id, name, author, isbn, coverPrice, tenantId, description, previewText);
    }

    /** Bản đã sửa (cùng id): thay name/author/isbn/coverPrice, kiểm quy tắc như khi tạo; giữ mô tả và đoạn đọc thử. */
    public Title update(String name, String author, String isbn, BigDecimal coverPrice) {
        return new Title(id, name, author, isbn, coverPrice, tenantId, description, previewText);
    }

    /**
     * Bản đã thay mô tả và đoạn đọc thử (cùng id, giữ các trường khác). Chuẩn hóa: Unicode NFC, CRLF và CR thành LF,
     * bỏ ký tự điều khiển trừ LF và TAB, cắt khoảng trắng đầu/cuối, rỗng thành null. Độ dài đếm theo code point;
     * vượt giới hạn thì ném {@link TitlePreviewTooLongException}.
     */
    public Title withPreview(String description, String previewText) {
        return new Title(id, name, author, isbn, coverPrice, tenantId,
                normalizeText(description, MAX_DESCRIPTION, "Mô tả"),
                normalizeText(previewText, MAX_PREVIEW_TEXT, "Đoạn đọc thử"));
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
    public boolean hasPreview() { return previewText != null; }
}
