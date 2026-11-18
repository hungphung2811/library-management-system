package lms.identity.user.application;

import lms.identity.user.domain.DuplicateReaderException;
import lms.identity.user.domain.ReaderPersonalInfo;
import lms.identity.user.domain.ReaderProfile;
import lms.identity.user.domain.ReaderProfileRepository;
import lms.identity.user.domain.UserNotFoundException;
import lms.identity.user.domain.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC10: cấp thẻ độc giả trong tenant hiện tại (reader_profiles chịu @TenantId + RLS). Có mật khẩu tạm thì tạo cùng lúc
 * tài khoản online (spec 5.7: credential mật khẩu tạm + mustChangePassword, hồ sơ users, membership ở tenant hiện tại)
 * qua {@link CreateUserUseCase} trong cùng transaction. Không có mật khẩu: cấp thẻ cho thành viên tenant hoặc độc giả
 * chỉ dùng tại quầy (userId chưa gắn tài khoản); tài khoản của tenant khác: 404.
 */
public class IssueReaderCardUseCase {

    private final ReaderProfileRepository repository;
    private final Clock clock;
    private final UserRepository users;
    private final AccountCredentialPort credentials;
    private final CreateUserUseCase createUser;

    public IssueReaderCardUseCase(ReaderProfileRepository repository, Clock clock, UserRepository users,
                                  AccountCredentialPort credentials, CreateUserUseCase createUser) {
        this.repository = repository;
        this.clock = clock;
        this.users = users;
        this.credentials = credentials;
        this.createUser = createUser;
    }

    @Transactional
    public ReaderProfileResponse execute(IssueReaderCardCommand c) {
        UUID tenantId = TenantContext.require();
        LocalDate today = LocalDate.now(clock);
        ReaderProfile profile = ReaderProfile.issue(c.userId(), c.cardNo(), c.fullName(), c.email(),
                c.readerType(), c.cardExpiryDate(), today, c.personalInfo());
        if (repository.existsByUserId(profile.userId())) {
            throw new DuplicateReaderException("Người dùng đã có thẻ độc giả: " + profile.userId());
        }
        if (repository.existsByCardNo(profile.cardNo())) {
            throw new DuplicateReaderException("Số thẻ đã tồn tại: " + profile.cardNo());
        }
        if (c.temporaryPassword() != null) {
            String username = c.username() == null || c.username().isBlank() ? profile.cardNo() : c.username();
            ReaderPersonalInfo i = profile.personalInfo();
            createUser.execute(new CreateUserRequest(profile.userId(), username, profile.fullName(), profile.email(),
                    null, c.temporaryPassword(), true, i.phone(), i.dateOfBirth(), i.gender(), i.address(),
                    null, null, null));
        } else if ((users.existsById(profile.userId()) || credentials.exists(profile.userId()))
                && !users.hasMembership(profile.userId(), tenantId)) {
            throw new UserNotFoundException(profile.userId());
        }
        return ReaderProfileResponse.from(repository.save(profile), today);
    }
}
