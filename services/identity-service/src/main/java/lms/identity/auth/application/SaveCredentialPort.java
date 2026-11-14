package lms.identity.auth.application;

import lms.identity.auth.domain.UserCredential;

public interface SaveCredentialPort {
    UserCredential save(UserCredential credential);
}
