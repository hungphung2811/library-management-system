package lms.customer.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/**
 * Chỉ nhận boolean JSON tường minh (true/false). Chuỗi "true", số 1... bị từ chối (Jackson mặc định ép kiểu), để
 * "đồng ý xử lý dữ liệu" không bao giờ được suy ra từ giá trị mơ hồ. null (hoặc thiếu) vẫn là null.
 */
public class StrictBooleanDeserializer extends JsonDeserializer<Boolean> {

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_TRUE) {
            return Boolean.TRUE;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return Boolean.FALSE;
        }
        return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
    }

    @Override
    public Boolean getNullValue(DeserializationContext context) {
        return null;
    }
}
