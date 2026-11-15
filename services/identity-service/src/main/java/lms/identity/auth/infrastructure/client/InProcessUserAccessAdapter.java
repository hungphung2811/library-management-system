package lms.identity.auth.infrastructure.client;

import lms.identity.accesscontrol.application.AccessDtos.UserAccessResponse;
import lms.identity.accesscontrol.application.GetUserAccessUseCase;
import lms.identity.auth.application.UserAccessPort;
import lms.identity.auth.domain.AuthenticationUnavailableException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Lấy role và permission hiệu lực từ ngữ cảnh accesscontrol ngay trong cùng tiến trình.
 * Chỉ phụ thuộc vào tầng application của accesscontrol, không đụng domain hay infrastructure của nó.
 */
public class InProcessUserAccessAdapter implements UserAccessPort {

    private static final Logger log = LoggerFactory.getLogger(InProcessUserAccessAdapter.class);

    private final GetUserAccessUseCase getUserAccess;

    public InProcessUserAccessAdapter(GetUserAccessUseCase getUserAccess) {
        this.getUserAccess = getUserAccess;
    }

    @Override
    public UserAccess fetch(UUID userId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // Vi phạm bất biến của UserAccessPort (R20): vẫn đúng tenant nhờ REQUIRES_NEW nhưng giữ 2 kết nối cùng lúc
            log.warn("UserAccessPort.fetch được gọi trong transaction đang mở (giữ 2 kết nối); hãy gọi ngoài transaction");
        }
        try {
            UserAccessResponse response = getUserAccess.execute(userId);
            return new UserAccess(response.userId(), response.roles(), response.permissions(), response.admin());
        } catch (RuntimeException e) {
            throw new AuthenticationUnavailableException("Không tính được quyền của người dùng " + userId, e);
        }
    }
}
