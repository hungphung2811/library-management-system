package lms.customer.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.springframework.web.client.RestClientResponseException;

/**
 * Đọc trường {@code code} của ErrorResponse từ phản hồi lỗi của service phía sau. Không bao giờ ném: thân rỗng, không
 * phải JSON, không có code hoặc code không phải chuỗi đều cho {@code null}. Đọc theo byte (Jackson tự nhận UTF-8) nên
 * không phụ thuộc charset trong Content-Type.
 */
public final class DownstreamErrorCode {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DownstreamErrorCode() {
    }

    public static String of(RestClientResponseException ex) {
        try {
            byte[] body = ex.getResponseBodyAsByteArray();
            if (body == null || body.length == 0) {
                return null;
            }
            JsonNode root = MAPPER.readTree(body);
            JsonNode code = root == null ? null : root.get("code");
            return code != null && code.isTextual() ? code.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code codes.contains(code)} an toàn với null ({@code Set.of(...).contains(null)} ném NPE). */
    public static boolean isOneOf(Set<String> codes, String code) {
        return code != null && codes.contains(code);
    }
}
