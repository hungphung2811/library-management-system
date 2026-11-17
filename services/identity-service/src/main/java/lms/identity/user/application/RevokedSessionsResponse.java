package lms.identity.user.application;

/** Kết quả thu hồi phiên: số refresh token đã thu hồi (0 nếu người dùng không còn phiên nào). */
public record RevokedSessionsResponse(int revokedSessions) {
}
