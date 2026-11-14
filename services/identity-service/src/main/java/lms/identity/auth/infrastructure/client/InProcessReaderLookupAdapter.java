package lms.identity.auth.infrastructure.client;

import lms.identity.auth.application.ReaderLookupPort;
import lms.identity.user.application.FindReaderUserIdsByEmailUseCase;
import java.util.List;
import java.util.UUID;

/** Gọi API (tầng application) của context user trong cùng service. */
public class InProcessReaderLookupAdapter implements ReaderLookupPort {

    private final FindReaderUserIdsByEmailUseCase findByEmail;

    public InProcessReaderLookupAdapter(FindReaderUserIdsByEmailUseCase findByEmail) {
        this.findByEmail = findByEmail;
    }

    @Override
    public List<UUID> findUserIdsByEmail(String email) {
        return findByEmail.execute(email);
    }
}
