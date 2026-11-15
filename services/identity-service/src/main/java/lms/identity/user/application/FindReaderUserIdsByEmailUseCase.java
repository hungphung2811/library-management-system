package lms.identity.user.application;

import lms.identity.user.domain.ReaderProfileRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * API của context user cho context khác (auth): tra userId theo email hồ sơ độc giả trong tenant hiện tại
 * (TenantContext). Transaction mới (REQUIRES_NEW) để tenant người gọi vừa đặt có hiệu lực kể cả khi đang ở trong
 * transaction của tenant khác (tenant của phiên Hibernate và RLS được chốt lúc transaction bắt đầu).
 */
public class FindReaderUserIdsByEmailUseCase {

    private final ReaderProfileRepository repository;

    public FindReaderUserIdsByEmailUseCase(ReaderProfileRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<UUID> execute(String email) {
        return repository.findUserIdsByEmail(email);
    }
}
