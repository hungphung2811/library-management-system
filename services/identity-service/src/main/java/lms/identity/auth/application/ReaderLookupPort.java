package lms.identity.auth.application;

import java.util.List;
import java.util.UUID;

/**
 * Tra userId theo email của hồ sơ độc giả (context user) trên mọi tenant ACTIVE: dùng trước khi biết tenant (đăng nhập
 * ngoài tự liên kết). Kết quả không trùng lặp; tenant được chọn sau đó theo membership như đăng nhập thường.
 */
public interface ReaderLookupPort {
    List<UUID> findUserIdsByEmail(String email);
}
