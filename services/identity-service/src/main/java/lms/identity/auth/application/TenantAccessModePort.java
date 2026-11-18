package lms.identity.auth.application;

import java.util.UUID;

/**
 * Chế độ truy cập hiện tại của một tenant, để {@link TokenService} ghi claim {@code tenantAccess} (ADR-0031 D4).
 * Mặc định ({@link #FULL_ALWAYS}) mọi tenant là {@link Mode#FULL}: chưa đổi hành vi. Pha vòng đời dữ liệu của gói G
 * thay bằng adapter đọc pha hợp đồng ({@code READ_ONLY} ở pha chỉ đọc).
 *
 * <p>Gọi ngoài transaction (bất biến R20 của TokenService); adapter tự mở transaction ngắn nếu cần. Lỗi (ném ngoại lệ)
 * làm việc cấp token thất bại, không rơi về {@code FULL} âm thầm.
 */
public interface TenantAccessModePort {

    enum Mode { FULL, READ_ONLY }

    /** Chế độ của tenant; không bao giờ trả null. */
    Mode modeOf(UUID tenantId);

    /** Mặc định: mọi tenant đầy đủ quyền (không ghi claim). */
    TenantAccessModePort FULL_ALWAYS = tenantId -> Mode.FULL;
}
