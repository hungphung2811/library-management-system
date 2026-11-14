package lms.identity.auth.infrastructure.messaging;

import lms.identity.auth.application.RevokeUserSessionsUseCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Khi access-control thu hồi role của người dùng, thu hồi mọi refresh token của họ để lần làm mới sau
 * buộc đăng nhập lại và nhận quyền mới (access token đang sống vẫn dùng được tới khi hết hạn ngắn).
 */
@Component
public class AccessEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccessEventConsumer.class);
    static final String ROLE_REVOKED = "RoleRevokedEvent";

    private final ObjectMapper objectMapper;
    private final RevokeUserSessionsUseCase revokeSessions;

    public AccessEventConsumer(ObjectMapper objectMapper, RevokeUserSessionsUseCase revokeSessions) {
        this.objectMapper = objectMapper;
        this.revokeSessions = revokeSessions;
    }

    @KafkaListener(topics = "${app.kafka.topics.access-events}")
    public void onAccessEvent(String message) throws Exception {
        JsonNode envelope = objectMapper.readTree(message);
        if (!ROLE_REVOKED.equals(envelope.path("eventType").asText())) return;

        UUID userId = UUID.fromString(envelope.path("payload").path("userId").asText());
        revokeSessions.execute(userId);
        log.info("Đã thu hồi phiên của user {} do role bị thu hồi (eventId={})", userId, envelope.path("eventId").asText());
    }
}
