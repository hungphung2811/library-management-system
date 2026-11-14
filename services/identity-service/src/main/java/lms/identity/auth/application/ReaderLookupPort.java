package lms.identity.auth.application;

import java.util.List;
import java.util.UUID;

/** Tra userId theo email của hồ sơ độc giả (context user). */
public interface ReaderLookupPort {
    List<UUID> findUserIdsByEmail(String email);
}
