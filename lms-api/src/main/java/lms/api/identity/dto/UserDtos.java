package lms.api.identity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Hợp đồng FE cho người dùng và hồ sơ độc giả. Trạng thái/loại để dạng chuỗi, service nội bộ tự kiểm giá trị. */
public final class UserDtos {

    private UserDtos() {
    }

    public record CreateUserRequest(UUID id,
                                    @NotBlank @Size(min = 3, max = 100) String username,
                                    @NotBlank @Size(max = 255) String fullName,
                                    @NotBlank @Email @Size(max = 255) String email,
                                    UUID departmentId) {
    }

    public record UpdateUserRequest(@NotBlank @Size(max = 255) String fullName,
                                    @NotBlank @Email @Size(max = 255) String email,
                                    UUID departmentId) {
    }

    public record ChangeStatusRequest(@NotBlank String status) {
    }

    /** Mật khẩu tạm do quản trị đặt (8-128 ký tự). Không log; toString bị che để tránh lộ qua log/exception. */
    public record ResetPasswordRequest(@NotBlank @Size(min = 8, max = 128) String temporaryPassword) {
        @Override
        public String toString() {
            return "ResetPasswordRequest[temporaryPassword=***]";
        }
    }

    /** tenantId: chỉ platform admin không tenant có tác dụng (ADR-0008); để chuỗi để service tự kiểm định dạng. */
    public record SearchUsersQuery(String keyword, UUID departmentId, String status, Integer page, Integer size,
                                   String tenantId) {
    }

    public record IssueReaderCardRequest(@NotBlank @Size(max = 32) String cardNo,
                                         @NotBlank @Size(max = 255) String fullName,
                                         @NotBlank @Email @Size(max = 255) String email,
                                         @NotBlank String readerType,
                                         @NotNull LocalDate cardExpiryDate) {
    }

    public record RenewReaderCardRequest(@NotNull LocalDate newExpiryDate) {
    }

    /**
     * tenantId: tenant của dòng (ADR-0008); null khi service chưa trả. locked/lockedUntil: tài khoản đang bị khóa do
     * đăng nhập sai nhiều lần (lockedUntil là thời điểm ISO-8601 hết khóa, null khi không bị khóa).
     */
    public record UserView(UUID id, String username, String fullName, String email, UUID departmentId, String status,
                           UUID tenantId, boolean locked, Instant lockedUntil) {

        public UserView(UUID id, String username, String fullName, String email, UUID departmentId, String status,
                        UUID tenantId) {
            this(id, username, fullName, email, departmentId, status, tenantId, false, null);
        }
    }

    /** Phiên của một người dùng trong tenant, cho quản trị viên xem (ADR-0017 mục 2.9); có IP đầy đủ. */
    public record UserSessionView(UUID sessionId, String channel, String deviceSummary, String ipAddress,
                                  Instant startedAt, Instant lastUsedAt, Instant expiresAt) {
    }

    /** Số refresh token đã thu hồi. */
    public record RevokedSessionsView(int revokedSessions) {
    }

    /** Bộ lọc xuất CSV, giống bộ lọc tìm kiếm người dùng. */
    public record ExportUsersQuery(String keyword, UUID departmentId, String status) {
    }

    /**
     * Nhập hàng loạt tối đa 500 dòng; dryRun=true chỉ kiểm tra. Từng trường của dòng kiểm ở identity và báo lỗi theo dòng.
     * toString che mật khẩu tạm.
     */
    public record ImportUsersRequest(boolean dryRun, @NotEmpty @Size(max = 500) List<@Valid ImportRow> rows) {

        @Override
        public String toString() {
            return "ImportUsersRequest[dryRun=" + dryRun + ", rows=" + (rows == null ? 0 : rows.size()) + "]";
        }
    }

    public record ImportRow(String username, String fullName, String email, String departmentCode,
                            String temporaryPassword, Boolean reader) {
        @Override
        public String toString() {
            return "ImportRow[username=" + username + ", temporaryPassword=***]";
        }
    }

    /** status: CREATED | VALID | FAILED. temporaryPassword chỉ có ở dòng CREATED mà hệ thống tự sinh mật khẩu (trả một lần). */
    public record ImportRowResult(int row, String username, String status, String errorCode, String message,
                                  String temporaryPassword) {
        @Override
        public String toString() {
            return "ImportRowResult[row=" + row + ", status=" + status + ", errorCode=" + errorCode + "]";
        }
    }

    public record ImportUsersResult(int total, int created, int failed, List<ImportRowResult> results) {
        @Override
        public String toString() {
            return "ImportUsersResult[total=" + total + ", created=" + created + ", failed=" + failed + "]";
        }
    }

    public record UserPageView(List<UserView> items, long total, int page, int size) {
    }

    public record ReaderProfileView(UUID userId, String cardNo, String fullName, String email, String readerType,
                                    LocalDate cardExpiryDate, String status, boolean cardValid) {
    }
}
