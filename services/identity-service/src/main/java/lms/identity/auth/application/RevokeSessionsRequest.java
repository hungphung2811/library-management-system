package lms.identity.auth.application;

/** POST /internal/auth/sessions/revocation: keepCurrent=true giữ lại phiên hiện tại (claim sid). */
public record RevokeSessionsRequest(boolean keepCurrent) {
}
