package lms.system.cms.application;

import java.time.Clock;
import java.util.UUID;
import lms.system.cms.domain.BusinessType;
import lms.system.cms.domain.ContactRequest;
import org.springframework.transaction.annotation.Transactional;

/** Tiếp nhận form liên hệ từ trang công khai (honeypot đã lọc ở customer-api). */
public class SubmitContactRequestUseCase {

    private final ContactRequestRepositoryPort repository;
    private final Clock clock;

    public SubmitContactRequestUseCase(ContactRequestRepositoryPort repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** {@code toString} của record này có thể chứa dữ liệu cá nhân: không log. */
    public record Command(String fullName, String email, String phone, String organization,
                          BusinessType businessType, String message, boolean consent) {
        @Override
        public String toString() {
            return "SubmitContactRequestUseCase.Command[...]";
        }
    }

    @Transactional
    public UUID execute(Command command) {
        ContactRequest request = ContactRequest.submit(UUID.randomUUID(), command.fullName(), command.email(),
                command.phone(), command.organization(), command.businessType(), command.message(),
                command.consent(), clock.instant());
        return repository.save(request).id();
    }
}
