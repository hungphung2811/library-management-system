package lms.catalog.inventory.domain;

import java.util.UUID;

/** Bản sao vật lý của một đầu sách. Bản sao mới nhập kho luôn ở trạng thái AVAILABLE. */
public class BookCopy {

    private final UUID id;
    private final UUID titleId;
    private final String barcode;
    private CopyStatus status;
    /** Tenant sở hữu (chỉ để hiển thị khi đọc; null với đối tượng mới đăng ký chưa lưu). */
    private final UUID tenantId;
    /** Vị trí kệ chuẩn (tùy chọn, tham chiếu shelf_locations); null nếu chưa gán. */
    private UUID shelfLocationId;
    /** Thông tin nhập kho và tình trạng (không bao giờ null; CopyAcquisition.EMPTY nếu chưa nhập). */
    private CopyAcquisition acquisition;

    private BookCopy(UUID id, UUID titleId, String barcode, CopyStatus status, UUID tenantId, UUID shelfLocationId,
                     CopyAcquisition acquisition) {
        if (id == null) throw new InvalidCopyException("id không được null");
        if (titleId == null) throw new InvalidCopyException("titleId không được null");
        if (barcode == null || barcode.isBlank()) throw new InvalidCopyException("Mã vạch không được để trống");
        if (barcode.trim().length() > 64) throw new InvalidCopyException("Mã vạch tối đa 64 ký tự");
        if (status == null) throw new InvalidCopyException("Trạng thái không được null");
        this.id = id;
        this.titleId = titleId;
        this.barcode = barcode.trim();
        this.status = status;
        this.tenantId = tenantId;
        this.shelfLocationId = shelfLocationId;
        this.acquisition = acquisition == null ? CopyAcquisition.EMPTY : acquisition;
    }

    public static BookCopy register(UUID titleId, String barcode) {
        return new BookCopy(UUID.randomUUID(), titleId, barcode, CopyStatus.AVAILABLE, null, null, null);
    }

    public static BookCopy register(UUID titleId, String barcode, UUID shelfLocationId) {
        return new BookCopy(UUID.randomUUID(), titleId, barcode, CopyStatus.AVAILABLE, null, shelfLocationId, null);
    }

    public static BookCopy register(UUID titleId, String barcode, UUID shelfLocationId, CopyAcquisition acquisition) {
        return new BookCopy(UUID.randomUUID(), titleId, barcode, CopyStatus.AVAILABLE, null, shelfLocationId,
                acquisition);
    }

    public static BookCopy restore(UUID id, UUID titleId, String barcode, CopyStatus status) {
        return new BookCopy(id, titleId, barcode, status, null, null, null);
    }

    public static BookCopy restore(UUID id, UUID titleId, String barcode, CopyStatus status, UUID tenantId) {
        return new BookCopy(id, titleId, barcode, status, tenantId, null, null);
    }

    public static BookCopy restore(UUID id, UUID titleId, String barcode, CopyStatus status, UUID tenantId,
                                   UUID shelfLocationId) {
        return new BookCopy(id, titleId, barcode, status, tenantId, shelfLocationId, null);
    }

    public static BookCopy restore(UUID id, UUID titleId, String barcode, CopyStatus status, UUID tenantId,
                                   UUID shelfLocationId, CopyAcquisition acquisition) {
        return new BookCopy(id, titleId, barcode, status, tenantId, shelfLocationId, acquisition);
    }

    /** Thay toàn bộ thông tin nhập kho và tình trạng (null = xóa hết). */
    public void updateAcquisition(CopyAcquisition acquisition) {
        this.acquisition = acquisition == null ? CopyAcquisition.EMPTY : acquisition;
    }

    /** Chuyển bản sao sang vị trí kệ khác (null = gỡ vị trí). Đã kiểm tồn tại và active ở use case. */
    public void moveToShelfLocation(UUID shelfLocationId) {
        this.shelfLocationId = shelfLocationId;
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
    public UUID tenantId() { return tenantId; }
    public UUID shelfLocationId() { return shelfLocationId; }
    public CopyAcquisition acquisition() { return acquisition; }
}
