package lms.identity.accesscontrol.infrastructure.security;

import lms.identity.accesscontrol.application.AccessCommands.RegisterPermissionKey;
import lms.identity.accesscontrol.application.RegisterPermissionKeyUseCase;
import lms.security.permission.CachingPermissionKeyRegistrar;
import lms.security.permission.EndpointDescriptor;
import java.time.Clock;
import java.time.Duration;

/** identity-service sở hữu danh mục permission nên đăng ký khóa trực tiếp, không gọi REST tới chính mình. */
public class LocalPermissionKeyRegistrar extends CachingPermissionKeyRegistrar {

    private final RegisterPermissionKeyUseCase useCase;
    private final Clock clock;

    public LocalPermissionKeyRegistrar(RegisterPermissionKeyUseCase useCase, Clock clock, Duration cooldown) {
        super(CachingPermissionKeyRegistrar.backgroundExecutor(), clock, cooldown);
        this.useCase = useCase;
        this.clock = clock;
    }

    @Override
    protected void doRegister(EndpointDescriptor descriptor) {
        useCase.execute(new RegisterPermissionKey(descriptor.service(), descriptor.key(), descriptor.source(),
                descriptor.httpMethod(), descriptor.pathPattern(), descriptor.description(), clock.instant()));
    }
}
