package lms.identity.user.application;

import lms.identity.user.domain.ReaderProfile;
import lms.identity.user.domain.ReaderProfileNotFoundException;
import lms.identity.user.domain.ReaderProfileRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Sửa thông tin cá nhân/học vụ của độc giả trong tenant hiện tại (không đổi số thẻ, hạn thẻ, trạng thái). */
public class UpdateReaderProfileUseCase {

    private final ReaderProfileRepository repository;
    private final Clock clock;

    public UpdateReaderProfileUseCase(ReaderProfileRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public ReaderProfileResponse execute(UUID userId, UpdateReaderProfileRequest request) {
        LocalDate today = LocalDate.now(clock);
        ReaderProfile profile = repository.findByUserId(userId)
                .orElseThrow(() -> new ReaderProfileNotFoundException(userId));
        if (profile.mergePersonalInfo(request.personalInfo(today))) repository.save(profile);
        return ReaderProfileResponse.from(profile, today);
    }
}
