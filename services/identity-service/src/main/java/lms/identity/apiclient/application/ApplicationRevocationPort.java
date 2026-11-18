package lms.identity.apiclient.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Ghi dấu "token của application này cấp trước mốc {@code at} không còn hợp lệ" để integration-api từ chối ngay (thu hồi
 * tức thời, tùy chọn). Best-effort: lỗi kho dấu chỉ log, không làm hỏng thao tác quản trị; khi đó token đã cấp còn dùng
 * được tối đa TTL của token (mặc định 5 phút).
 */
public interface ApplicationRevocationPort {

    /** Không làm gì (cờ tắt hoặc test). */
    ApplicationRevocationPort NONE = (applicationId, at) -> { };

    void revokeTokensIssuedBefore(UUID applicationId, Instant at);
}
