package lms.identity.auth.infrastructure.client;

import java.util.Optional;
import java.util.UUID;
import lms.identity.auth.application.UserContactPort;
import lms.identity.user.application.GetUserContactUseCase;

/** Gọi tầng application của context user trong cùng service. */
public class InProcessUserContactAdapter implements UserContactPort {

    private final GetUserContactUseCase contacts;

    public InProcessUserContactAdapter(GetUserContactUseCase contacts) {
        this.contacts = contacts;
    }

    @Override
    public Optional<UUID> userIdByEmail(String email) {
        return contacts.userIdByEmail(email);
    }

    @Override
    public Optional<String> emailOf(UUID userId) {
        return contacts.emailOf(userId);
    }
}
