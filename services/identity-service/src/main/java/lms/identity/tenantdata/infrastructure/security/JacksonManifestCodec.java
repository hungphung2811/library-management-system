package lms.identity.tenantdata.infrastructure.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import lms.identity.tenantdata.application.ManifestCodec;
import lms.identity.tenantdata.application.ManifestModel.Manifest;

/** manifest.json: UTF-8 không BOM, thời điểm ISO-8601 (không dạng số), trường theo thứ tự khai báo của record. */
public class JacksonManifestCodec implements ManifestCodec {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public byte[] toJson(Manifest manifest) {
        try {
            return mapper.writeValueAsString(manifest).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không tuần tự hóa được manifest", e);
        }
    }
}
