package lms.circulation.fine.domain;

/**
 * Cách xử lý khoản đền bù mất khi tìm lại sách, chọn theo trạng thái khoản lúc đề nghị: REFUND (đã thu: ghi bút toán
 * hoàn), WAIVE (chưa thu: miễn toàn phần thay vì hoàn), RESTORE_ONLY (đã được miễn: chỉ đưa bản sao về kho).
 */
public enum RefundMode {
    REFUND, WAIVE, RESTORE_ONLY
}
