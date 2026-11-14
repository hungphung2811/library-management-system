package lms.catalog.inventory.domain;

import java.util.UUID;

/** Bản sao vật lý của một đầu sách. Bản sao mới nhập kho luôn ở trạng thái AVAILABLE. */
public class BookCopy {

    private final UUID id;
    private final UUID titleId;
    private final String barcode;
    private CopyStatus status;

    private BookCopy(UUID id, UUID titleId, String barcode, CopyStatus status) {
        if (id == null) throw new InvalidCopyException("id không được null");
        if (titleId == null) throw new InvalidCopyException("titleId không được null");
        if (barcode == null || barcode.isBlank()) throw new InvalidCopyException("Mã vạch không được để trống");
        if (barcode.trim().length() > 64) throw new InvalidCopyException("Mã vạch tối đa 64 ký tự");
        if (status == null) throw new InvalidCopyException("Trạng thái không được null");
        this.id = id;
        this.titleId = titleId;
        this.barcode = barcode.trim();
        this.status = status;
    }

    public static BookCopy register(UUID titleId, String barcode) {
        return new BookCopy(UUID.randomUUID(), titleId, barcode, CopyStatus.AVAILABLE);
    }

    public static BookCopy restore(UUID id, UUID titleId, String barcode, CopyStatus status) {
        return new BookCopy(id, titleId, barcode, status);
    }

    public boolean isAvailable() {
        return status == CopyStatus.AVAILABLE;
    }

    /** QĐ6: giữ bản sao cho người đặt trước. @return false nếu bản sao không còn AVAILABLE (ví dụ đã được mượn). */
    public boolean hold() {
        if (status == CopyStatus.RESERVED) return true;
        if (status != CopyStatus.AVAILABLE) return false;
        status = CopyStatus.RESERVED;
        return true;
    }

    /** Nhả bản sao đang giữ về kho (hết hạn giữ, đặt trước bị hủy). @return true nếu trạng thái vừa đổi RESERVED -> AVAILABLE. */
    public boolean release() {
        if (status != CopyStatus.RESERVED) return false;
        status = CopyStatus.AVAILABLE;
        return true;
    }

    public UUID id() { return id; }
    public UUID titleId() { return titleId; }
    public String barcode() { return barcode; }
    public CopyStatus status() { return status; }
}
