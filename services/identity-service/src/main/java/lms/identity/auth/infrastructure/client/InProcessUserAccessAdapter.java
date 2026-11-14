package lms.identity.auth.infrastructure.client;

import lms.identity.accesscontrol.application.AccessDtos.UserAccessResponse;
import lms.identity.accesscontrol.application.GetUserAccessUseCase;
import lms.identity.auth.application.UserAccessPort;
import lms.identity.auth.domain.AuthenticationUnavailableException;
import java.util.UUID;

/**
 * Lấy role và permission hiệu lực từ ngữ cảnh accesscontrol ngay trong cùng tiến trình.
 * Chỉ phụ thuộc vào tầng application của accesscontrol, không đụng domain hay infrastructure của nó.
 */
public class InProcessUserAccessAdapter implements UserAccessPort {

    private final GetUserAccessUseCase getUserAccess;

    public InProcessUserAccessAdapter(GetUserAccessUseCase getUserAccess) {
        this.getUserAccess = getUserAccess;
    }

    @Override
    public UserAccess fetch(UUID userId) {
        try {
            UserAccessResponse response = getUserAccess.execute(userId);
            return new UserAccess(response.userId(), response.roles(), response.permissions(), response.admin());
        } catch (RuntimeException e) {
            throw new AuthenticationUnavailableException("Không tính được quyền của người dùng " + userId, e);
        }
    }
}
