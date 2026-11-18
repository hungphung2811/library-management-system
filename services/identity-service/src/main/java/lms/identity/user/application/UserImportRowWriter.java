package lms.identity.user.application;

import org.springframework.transaction.annotation.Transactional;

/**
 * Tạo MỘT dòng nhập Excel trong MỘT transaction riêng (bean tách khỏi {@link UserImportHandler} để lời gọi đi qua proxy
 * giao dịch): dòng lỗi chỉ rollback chính nó. Dùng lại {@link CreateUserUseCase} (hồ sơ + credential mustChangePassword
 * + membership) và {@link IssueReaderCardUseCase} (thẻ độc giả, số thẻ = tên đăng nhập) nên luật tạo không bị sao chép.
 * Không gán role, không gán phòng ban.
 */
public class UserImportRowWriter {

    private final CreateUserUseCase createUser;
    private final IssueReaderCardUseCase issueReaderCard;

    public UserImportRowWriter(CreateUserUseCase createUser, IssueReaderCardUseCase issueReaderCard) {
        this.createUser = createUser;
        this.issueReaderCard = issueReaderCard;
    }

    @Transactional
    public void create(ParsedUserRow row, String temporaryPassword) {
        var p = row.personal();
        UserResponse created = createUser.execute(new CreateUserRequest(null, row.username(), row.fullName(),
                row.email(), null, temporaryPassword, true, p.phone(), p.dateOfBirth(), p.gender(), p.address(), null,
                p.jobTitle(), p.employeeCode()));
        if (row.reader()) {
            issueReaderCard.execute(new IssueReaderCardCommand(created.id(), created.username(), created.fullName(),
                    created.email(), row.readerType(), row.cardExpiryDate(), null, null, row.readerInfo()));
        }
    }
}
