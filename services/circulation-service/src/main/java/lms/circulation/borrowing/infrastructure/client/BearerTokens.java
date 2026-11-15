package lms.circulation.borrowing.infrastructure.client;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/** Lấy access token của request hiện tại để chuyển tiếp khi gọi service khác thay mặt thủ thư. */
final class BearerTokens {

    private BearerTokens() {
    }

    static Optional<String> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof AbstractOAuth2TokenAuthenticationToken<?> token) {
            return Optional.of(token.getToken().getTokenValue());
        }
        return Optional.empty();
    }
}
