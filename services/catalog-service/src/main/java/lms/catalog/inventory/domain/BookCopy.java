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

    /**
     * Đổi trạng thái theo vòng đời mượn/trả (UC06-UC08). Idempotent: đã ở trạng thái đích thì không làm gì.
     * Bảng chuyển hợp lệ: LOANED từ AVAILABLE/RESERVED; AVAILABLE từ LOANED/RESERVED/DAMAGED (trả về, nhả giữ, sửa xong);
     * RESERVED từ AVAILABLE/LOANED (giữ chỗ, hoặc hoàn tác một lần mượn lỗi); DAMAGED và LOST chỉ từ LOANED.
     * @return true nếu trạng thái thực sự đổi.
     */
    public boolean changeStatus(CopyStatus target) {
        if (target == null) throw new InvalidCopyException("Trạng thái đích không được null");
        if (status == target) return false;
        boolean allowed = switch (target) {
            case LOANED -> status == CopyStatus.AVAILABLE || status == CopyStatus.RESERVED;
            case AVAILABLE -> status == CopyStatus.LOANED || status == CopyStatus.RESERVED || status == CopyStatus.DAMAGED;
            case RESERVED -> status == CopyStatus.AVAILABLE || status == CopyStatus.LOANED;
            case DAMAGED, LOST -> status == CopyStatus.LOANED;
        };
        if (!allowed) throw new IllegalCopyTransitionException(status, target);
        status = target;
        return true;
    }

    public UUID id() { return id; }
    public UUID titleId() { return titleId; }
    public String barcode() { return barcode; }
    public CopyStatus status() { return status; }
}
