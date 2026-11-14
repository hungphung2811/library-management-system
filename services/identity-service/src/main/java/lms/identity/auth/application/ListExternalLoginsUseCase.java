package lms.identity.auth.application;

import lms.identity.auth.domain.ExternalLoginRepository;
import java.util.List;
import java.util.UUID;

public class ListExternalLoginsUseCase {

    private final ExternalLoginRepository logins;

    public ListExternalLoginsUseCase(ExternalLoginRepository logins) {
        this.logins = logins;
    }

    public List<ExternalLoginResponse> execute(UUID userId) {
        return logins.findByUserId(userId).stream().map(ExternalLoginResponse::from).toList();
    }
}
