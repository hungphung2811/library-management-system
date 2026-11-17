package lms.identity.auth.infrastructure.client;

import java.util.UUID;
import lms.identity.auth.application.SignupAccountPort;
import lms.identity.auth.domain.DuplicateUsernameException;
import lms.identity.auth.domain.InvalidSignupException;
import lms.identity.user.application.SelfRegisterUserUseCase;
import lms.identity.user.application.SelfRegistrationRejectedException;

/** Gọi tầng application của context user (đăng ký tự phục vụ), dịch lỗi sang lỗi của auth. */
public class InProcessSignupAccountAdapter implements SignupAccountPort {

    private final SelfRegisterUserUseCase selfRegister;

    public InProcessSignupAccountAdapter(SelfRegisterUserUseCase selfRegister) {
        this.selfRegister = selfRegister;
    }

    @Override
    public UUID register(String username, String rawPassword, String fullName, String email) {
        try {
            return selfRegister.execute(username, rawPassword, fullName, email);
        } catch (SelfRegistrationRejectedException e) {
            if (e.reason() == SelfRegistrationRejectedException.Reason.DUPLICATE) {
                throw DuplicateUsernameException.generic();
            }
            throw new InvalidSignupException(e.getMessage());
        }
    }
}
