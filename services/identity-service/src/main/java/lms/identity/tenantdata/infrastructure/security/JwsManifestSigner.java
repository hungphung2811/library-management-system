package lms.identity.tenantdata.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import lms.identity.tenantdata.application.ManifestSignerPort;

/**
 * Ký manifest bằng chính khóa RS256 đang ký JWT của identity (cùng {@code kid} với JWKS công khai), JWS compact TÁCH RỜI
 * (RFC 7515 phụ lục F): {@code base64url(header)..base64url(signature)}; payload là các byte của manifest.json. Người nhận
 * kiểm bằng khóa công khai trong JWKS theo {@code kid}. Khóa đã xoay vòng và gỡ khỏi JWKS thì không kiểm lại được gói cũ
 * (ADR-0031 D2, gói chỉ sống 72 giờ).
 */
public class JwsManifestSigner implements ManifestSignerPort {

    public static final String TYPE = "lms-manifest+json";

    private final JWSSigner signer;
    private final String keyId;

    public JwsManifestSigner(RSAKey rsaKey) {
        try {
            this.signer = new RSASSASigner(rsaKey);
        } catch (JOSEException e) {
            throw new IllegalStateException("Khóa ký manifest không hợp lệ", e);
        }
        this.keyId = rsaKey.getKeyID();
    }

    @Override
    public String signDetached(byte[] manifestJson) {
        try {
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId)
                    .type(new JOSEObjectType(TYPE)).build();
            JWSObject jws = new JWSObject(header, new Payload(manifestJson));
            jws.sign(signer);
            return jws.serialize(true);
        } catch (JOSEException e) {
            throw new IllegalStateException("Không ký được manifest", e);
        }
    }
}
