package lms.identity.auth.infrastructure.client;

import lms.identity.auth.application.UserDirectoryPort;
import lms.identity.user.application.GetUserDirectoryInfoUseCase;
import java.util.Optional;
import java.util.UUID;

/** Gọi API (tầng application) của context user trong cùng service. */
public class InProcessUserDirectoryAdapter implements UserDirectoryPort {

    private final GetUserDirectoryInfoUseCase getInfo;

    public InProcessUserDirectoryAdapter(GetUserDirectoryInfoUseCase getInfo) {
        this.getInfo = getInfo;
    }

    @Override
    public Optional<UserInfo> find(UUID userId) {
        return getInfo.execute(userId).map(i -> new UserInfo(i.disabled()));
    }
}
