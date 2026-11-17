package lms.identity.auth.infrastructure.client;

import java.util.UUID;
import lms.identity.accesscontrol.application.SignupRoleUseCase;
import lms.identity.auth.application.SignupRolePort;

/** Gọi tầng application của context accesscontrol (role đăng ký mặc định). */
public class InProcessSignupRoleAdapter implements SignupRolePort {

    private final SignupRoleUseCase signupRole;

    public InProcessSignupRoleAdapter(SignupRoleUseCase signupRole) {
        this.signupRole = signupRole;
    }

    @Override
    public boolean available() {
        return signupRole.find().isPresent();
    }

    @Override
    public void assignTo(UUID userId) {
        signupRole.assignTo(userId);
    }
}
