package lms.identity.user.application;

import lms.identity.user.domain.DuplicateReaderException;
import lms.identity.user.domain.ReaderProfile;
import lms.identity.user.domain.ReaderProfileRepository;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.transaction.annotation.Transactional;

/** UC10: cấp thẻ độc giả cho một tài khoản đã có. */
public class IssueReaderCardUseCase {

    private final ReaderProfileRepository repository;
    private final Clock clock;

    public IssueReaderCardUseCase(ReaderProfileRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public ReaderProfileResponse execute(IssueReaderCardCommand c) {
        LocalDate today = LocalDate.now(clock);
        ReaderProfile profile = ReaderProfile.issue(c.userId(), c.cardNo(), c.fullName(), c.email(),
                c.readerType(), c.cardExpiryDate(), today);
        if (repository.existsByUserId(profile.userId())) {
            throw new DuplicateReaderException("Người dùng đã có thẻ độc giả: " + profile.userId());
        }
        if (repository.existsByCardNo(profile.cardNo())) {
            throw new DuplicateReaderException("Số thẻ đã tồn tại: " + profile.cardNo());
        }
        return ReaderProfileResponse.from(repository.save(profile), today);
    }
}
