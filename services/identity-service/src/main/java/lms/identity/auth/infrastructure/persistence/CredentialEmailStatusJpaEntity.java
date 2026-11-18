package lms.identity.auth.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Hình chiếu thứ hai của bảng credentials chỉ gồm cột email_verified_at (V37). Tách khỏi UserCredentialJpaEntity có chủ
 * đích: mọi lần lưu credential (đăng nhập, đổi mật khẩu) KHÔNG ghi đè cờ này, và các đường tạo tài khoản khác nhận mặc định
 * DB (đã xác minh). Chỉ cập nhật bằng câu UPDATE có điều kiện, không save() nguyên entity.
 */
@Entity(name = "CredentialEmailStatus")
@Table(name = "credentials")
class CredentialEmailStatusJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    protected CredentialEmailStatusJpaEntity() {
    }

    UUID getUserId() { return userId; }
    Instant getEmailVerifiedAt() { return emailVerifiedAt; }
}
