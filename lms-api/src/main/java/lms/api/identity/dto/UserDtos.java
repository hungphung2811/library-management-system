package lms.api.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

    public record SearchUsersQuery(String keyword, UUID departmentId, String status, Integer page, Integer size) {
    }

    public record IssueReaderCardRequest(@NotBlank @Size(max = 32) String cardNo,
                                         @NotBlank @Size(max = 255) String fullName,
                                         @NotBlank @Email @Size(max = 255) String email,
                                         @NotBlank String readerType,
                                         @NotNull LocalDate cardExpiryDate) {
    }

    public record RenewReaderCardRequest(@NotNull LocalDate newExpiryDate) {
    }

    public record UserView(UUID id, String username, String fullName, String email, UUID departmentId, String status) {
    }

    public record UserPageView(List<UserView> items, long total, int page, int size) {
    }

    public record ReaderProfileView(UUID userId, String cardNo, String fullName, String email, String readerType,
                                    LocalDate cardExpiryDate, String status, boolean cardValid) {
    }
}
