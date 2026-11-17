package lms.identity.user.application;

import lms.identity.user.domain.ReaderType;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tạo MỘT dòng nhập trong MỘT transaction riêng (bean tách khỏi ImportUsersUseCase để lời gọi đi qua proxy giao dịch):
 * dòng lỗi chỉ rollback chính nó. reader=true thì tạo luôn hồ sơ độc giả cùng transaction (số thẻ = tên đăng nhập,
 * loại STUDENT, hạn thẻ 1 năm kể từ hôm nay).
 */
public class ImportUserRowExecutor {

    public static final int DEFAULT_CARD_VALID_YEARS = 1;

    private final CreateUserUseCase createUser;
    private final IssueReaderCardUseCase issueReaderCard;
    private final Clock clock;

    public ImportUserRowExecutor(CreateUserUseCase createUser, IssueReaderCardUseCase issueReaderCard, Clock clock) {
        this.createUser = createUser;
        this.issueReaderCard = issueReaderCard;
        this.clock = clock;
    }

    @Transactional
    public void create(String username, String fullName, String email, UUID departmentId, String password,
                       boolean reader) {
        UserResponse created = createUser.execute(new CreateUserRequest(null, username, fullName, email, departmentId,
                password, true));
        if (reader) {
            issueReaderCard.execute(new IssueReaderCardCommand(created.id(), created.username(), created.fullName(),
                    created.email(), ReaderType.STUDENT, LocalDate.now(clock).plusYears(DEFAULT_CARD_VALID_YEARS)));
        }
    }
}
