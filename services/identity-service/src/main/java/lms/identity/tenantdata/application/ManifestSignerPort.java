package lms.identity.tenantdata.application;

/**
 * Ký manifest bằng khóa ký JWT hiện hành của identity (RS256, {@code kid} trong header). Trả JWS compact tách rời
 * (RFC 7515 phụ lục F: {@code header..signature}, payload là chính các byte manifest.json); kiểm bằng JWKS công khai.
 */
public interface ManifestSignerPort {

    String signDetached(byte[] manifestJson);
}
