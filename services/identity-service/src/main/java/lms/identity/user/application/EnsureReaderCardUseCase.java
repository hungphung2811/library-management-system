package lms.identity.user.application;

import java.time.LocalDate;
import java.util.UUID;
import lms.identity.user.domain.ReaderProfileRepository;
import lms.identity.user.domain.ReaderType;

/**
 * Cấp thẻ độc giả cho người dùng đã có trong tenant hiện tại nếu chưa có thẻ, qua {@link IssueReaderCardUseCase}.
 * Dùng để seed dữ liệu demo idempotent (ADR-0018).
 */
public class EnsureReaderCardUseCase {

    private final ReaderProfileRepository profiles;
    private final IssueReaderCardUseCase issueCard;

    public EnsureReaderCardUseCase(ReaderProfileRepository profiles, IssueReaderCardUseCase issueCard) {
        this.profiles = profiles;
        this.issueCard = issueCard;
    }

    /** @return true nếu vừa cấp thẻ, false nếu người dùng đã có thẻ */
    public boolean execute(UUID userId, String cardNo, String fullName, String email, boolean lecturer,
                           LocalDate cardExpiryDate) {
        if (profiles.existsByUserId(userId)) return false;
        issueCard.execute(new IssueReaderCardCommand(userId, cardNo, fullName, email,
                lecturer ? ReaderType.LECTURER : ReaderType.STUDENT, cardExpiryDate));
        return true;
    }
}
