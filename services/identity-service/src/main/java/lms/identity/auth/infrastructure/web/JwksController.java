package lms.identity.auth.infrastructure.web;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import lms.identity.auth.infrastructure.config.JwtKeyConfiguration.PreviousPublicKeys;
import lms.security.permission.PublicEndpoint;
import java.util.ArrayList;
import java.util.List;
import com.nimbusds.jose.jwk.JWK;
import org.springframework.beans.factory.ObjectProvider;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Công khai khóa public để các resource server tự xác minh token (không gọi lại auth-service mỗi request). */
@PublicEndpoint
@RestController
public class JwksController {

    private final RSAKey rsaKey;
    private final ObjectProvider<PreviousPublicKeys> previousKeys;

    public JwksController(RSAKey rsaKey, ObjectProvider<PreviousPublicKeys> previousKeys) {
        this.rsaKey = rsaKey;
        this.previousKeys = previousKeys;
    }

    /** Khóa đang ký trước, rồi (nếu đang trong cửa sổ xoay khóa) khóa công khai cũ; chỉ thành phần công khai. */
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        List<JWK> keys = new ArrayList<>();
        keys.add(rsaKey.toPublicJWK());
        PreviousPublicKeys previous = previousKeys.getIfAvailable();
        if (previous != null) previous.keys().forEach(k -> keys.add(k.toPublicJWK()));
        return new JWKSet(keys).toJSONObject();
    }
}
