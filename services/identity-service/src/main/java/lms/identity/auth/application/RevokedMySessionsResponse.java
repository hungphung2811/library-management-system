package lms.identity.auth.application;

/** Số phiên đã thu hồi (cùng hình dạng với {@code user.application.RevokedSessionsResponse}). */
public record RevokedMySessionsResponse(int revokedSessions) {
}
