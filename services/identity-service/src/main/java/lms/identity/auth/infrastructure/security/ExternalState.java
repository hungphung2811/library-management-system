package lms.identity.auth.infrastructure.security;

import java.util.UUID;

/** Trạng thái một lượt đăng nhập ngoài, giữ trong cookie đã ký giữa lúc đi và về từ nhà cung cấp. */
public record ExternalState(String provider, String state, String nonce, String codeVerifier, String returnUrl,
                            Mode mode, UUID linkUserId, long expiresAt) {

    public enum Mode { LOGIN, LINK }
}
