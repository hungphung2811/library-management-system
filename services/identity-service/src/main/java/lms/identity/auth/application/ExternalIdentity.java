package lms.identity.auth.application;

/** Danh tính đã được nhà cung cấp xác thực (id_token đã kiểm chữ ký, issuer, audience, nonce). */
public record ExternalIdentity(String provider, String subject, String email, boolean emailVerified) {
}
