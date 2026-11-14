package lms.identity.auth.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mã hóa trạng thái thành chuỗi "payload.chữ ký" (HMAC-SHA256) để lưu trong cookie, không cần lưu phía server.
 * Cookie là HttpOnly và chỉ chứa dữ liệu của chính lượt đăng nhập; chữ ký chống sửa, hạn sống chống dùng lại lâu.
 */
public class StateCookieCodec {

    private final ObjectMapper mapper;
    private final byte[] secret;

    public StateCookieCodec(ObjectMapper mapper, byte[] secret) {
        if (secret == null || secret.length < 16) throw new IllegalArgumentException("Khóa ký phải dài ít nhất 16 byte");
        this.mapper = mapper;
        this.secret = secret.clone();
    }

    public String encode(ExternalState state) {
        try {
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(state));
            return payload + "." + sign(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Không mã hóa được trạng thái đăng nhập ngoài", e);
        }
    }

    /** @return trạng thái nếu chữ ký đúng và chưa hết hạn; ngược lại rỗng. */
    public Optional<ExternalState> decode(String value, Instant now) {
        try {
            if (value == null) return Optional.empty();
            int dot = value.lastIndexOf('.');
            if (dot <= 0) return Optional.empty();
            String payload = value.substring(0, dot);
            if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.US_ASCII),
                    value.substring(dot + 1).getBytes(StandardCharsets.US_ASCII))) {
                return Optional.empty();
            }
            ExternalState state = mapper.readValue(Base64.getUrlDecoder().decode(payload), ExternalState.class);
            return state.expiresAt() > now.getEpochSecond() ? Optional.of(state) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
    }
}
