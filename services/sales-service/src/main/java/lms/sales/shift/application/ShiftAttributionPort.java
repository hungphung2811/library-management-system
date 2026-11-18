package lms.sales.shift.application;

import java.util.UUID;

/**
 * Cổng để các use case của {@code ordering} gắn tiền vào ca thu ngân (ADR-0030 D3.3, D3.4). Chỉ trả id ca; ordering tự
 * ghi {@code shift_id} vào lệnh thanh toán, bản ghi thu và khoản hoàn của mình (shift không đọc/ghi bảng của ordering).
 */
public interface ShiftAttributionPort {

    /** Không dùng ca (test cũ, môi trường chưa nối): không gắn gì, không chặn gì. */
    ShiftAttributionPort NONE = (tenantId, userId, enforceWhenRequired) -> null;

    /**
     * Id ca đang MỞ của {@code userId}, hoặc null nếu người đó không có ca mở. Khi tenant bật cờ chặn bán
     * ({@code requireOpenShiftForPos}) và {@code enforceWhenRequired} là true mà không có ca mở thì ném 409
     * SHIFT_NOT_OPEN; cờ tắt thì không bao giờ ném (hành vi cũ: bán không có ca).
     */
    UUID resolve(UUID tenantId, UUID userId, boolean enforceWhenRequired);
}
