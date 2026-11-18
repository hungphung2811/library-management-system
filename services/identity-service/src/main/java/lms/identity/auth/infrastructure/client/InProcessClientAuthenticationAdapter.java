package lms.identity.auth.infrastructure.client;

import java.util.Optional;
import lms.identity.apiclient.application.AuthenticateClientUseCase;
import lms.identity.auth.application.ClientAuthenticationPort;

/** Gọi tầng application của context apiclient trong cùng service. */
public class InProcessClientAuthenticationAdapter implements ClientAuthenticationPort {

    private final AuthenticateClientUseCase authenticate;

    public InProcessClientAuthenticationAdapter(AuthenticateClientUseCase authenticate) {
        this.authenticate = authenticate;
    }

    @Override
    public Optional<ClientPrincipal> authenticate(String clientId, String clientSecret) {
        return authenticate.authenticate(clientId, clientSecret).map(c -> new ClientPrincipal(c.applicationId(),
                c.tenantId(), c.grantedScopes(), c.availableScopes(), c.modules()));
    }
}
