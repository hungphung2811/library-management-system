package lms.identity.auth.application;

import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.PasswordHasherPort;
import lms.identity.auth.application.PublishAuthEventPort;
import lms.identity.auth.application.SaveCredentialPort;
import lms.identity.auth.domain.EmailNotVerifiedException;
import lms.identity.auth.domain.InvalidCredentialsException;
import lms.identity.auth.domain.TokenChannel;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.common.kernel.DomainEvent;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoginUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoginUseCase.class);

    private final UserCredentialRepository repository;
    private final SaveCredentialPort savePort;
    private final PasswordHasherPort hasher;
    private final TokenService tokenService;
    private final PublishAuthEventPort eventPort;
    private final Clock clock;
    private final TenantAccessPort tenantAccess;
    /** null = không kiểm xác minh email (hành vi cũ). */
    private final EmailVerificationUseCase emailVerification;

    public LoginUseCase(UserCredentialRepository repository, SaveCredentialPort savePort, PasswordHasherPort hasher,
                        TokenService tokenService, PublishAuthEventPort eventPort, Clock clock,
                        TenantAccessPort tenantAccess) {
        this(repository, savePort, hasher, tokenService, eventPort, clock, tenantAccess, null);
    }

    public LoginUseCase(UserCredentialRepository repository, SaveCredentialPort savePort, PasswordHasherPort hasher,
                        TokenService tokenService, PublishAuthEventPort eventPort, Clock clock,
                        TenantAccessPort tenantAccess, EmailVerificationUseCase emailVerification) {
        this.emailVerification = emailVerification;
        this.repository = repository;
        this.savePort = savePort;
        this.hasher = hasher;
        this.tokenService = tokenService;
        this.eventPort = eventPort;
        this.clock = clock;
        this.tenantAccess = tenantAccess;
    }

    public TokenResponse execute(String username, String rawPassword) {
        return execute(username, rawPassword, null);
    }

    /**
     * tenantCodeOrNull: mã tenant muốn vào (tùy chọn). Không truy cập được tenant (hoặc không còn tenant nào) thì ném
     * NoTenantAccessException của context tenant sau khi đã xác thực mật khẩu, và lần đăng nhập thành công không được
     * ghi nhận (ghi sau khi cấp token).
     *
     * <p>Không có transaction bao ngoài (R20): đọc credential, kiểm bcrypt, ghi số lần sai/thành công và cấp token là
     * các bước ngắn, mỗi bước tự có transaction, nên không giữ kết nối suốt bcrypt và TokenService không chạy trong
     * transaction đang mở. Sai mật khẩu: số lần thất bại được lưu ngay (trước khi ném InvalidCredentialsException).
     *
     * <p>Audit (support chỉ ghi sự kiện có tenantId trong envelope): sự kiện đăng nhập thành công phát dưới tenant vừa
     * chọn của phiên. Sự kiện sai mật khẩu/khóa tài khoản phát dưới tenant dùng gần nhất của tài khoản
     * (credentials.last_tenant_id); tài khoản chưa có tenant gần nhất (chưa từng vào tenant nào, hoặc platform admin)
     * thì KHÔNG phát sự kiện mà chỉ ghi log WARN, vì sự kiện không có tenant sẽ bị support bỏ qua. Giới hạn đã biết:
     * các lần sai mật khẩu đó không có trong audit của tenant nào. Tên đăng nhập không tồn tại không phát sự kiện.
     */
    public TokenResponse execute(String username, String rawPassword, String tenantCodeOrNull) {
        return execute(username, rawPassword, tenantCodeOrNull, TokenChannel.STAFF);
    }

    /**
     * Đăng nhập theo kênh. STOREFRONT (customer-api): bắt buộc có mã tenant (thiếu thì từ chối như sai thông tin, trước
     * khi đụng tới tài khoản), token bị thu hẹp (xem {@link TokenService#issueFor(UserCredential, String, TokenChannel)}).
     * Thứ tự kiểm tra giữ nguyên cho mọi kênh: tài khoản, khóa/vô hiệu, mật khẩu, tenant. Việc gộp mọi lỗi đăng nhập
     * (sai mật khẩu, không có tài khoản, không thuộc tenant, khóa, vô hiệu) thành {@code 401 LOGIN_FAILED} do customer-api
     * làm (HttpAuthClient), identity giữ mã riêng cho mọi kênh.
     */
    public TokenResponse execute(String username, String rawPassword, String tenantCodeOrNull, TokenChannel channel) {
        return execute(username, rawPassword, tenantCodeOrNull, channel, null);
    }

    /** Như trên, thêm thông tin client (IP, user-agent) để gắn nhãn thiết bị cho phiên mới (ADR-0017). */
    public TokenResponse execute(String username, String rawPassword, String tenantCodeOrNull, TokenChannel channel,
                                 ClientInfo client) {
        TokenChannel ch = channel == null ? TokenChannel.STAFF : channel;
        if (ch == TokenChannel.STOREFRONT && (tenantCodeOrNull == null || tenantCodeOrNull.isBlank())) {
            throw new InvalidCredentialsException();
        }
        Instant now = clock.instant();
        String normalized = username == null ? "" : username.trim().toLowerCase();
        UserCredential credential = repository.findByUsername(normalized).orElseThrow(InvalidCredentialsException::new);

        credential.ensureCanLogin(now);

        if (!hasher.matches(rawPassword, credential.passwordHash())) {
            credential.recordFailure(now);
            savePort.save(credential);
            publishUnderTenant(credential.pullEvents(), tenantAccess.lastTenant(credential.userId()),
                    credential.userId());
            throw new InvalidCredentialsException();
        }

        // Chỉ kênh storefront (khách tự đăng ký), và chỉ sau khi mật khẩu đã đúng: không lộ trạng thái cho người không có mật khẩu.
        if (ch == TokenChannel.STOREFRONT && emailVerification != null
                && !emailVerification.isVerified(credential.userId())) {
            throw new EmailNotVerifiedException();
        }

        TokenResponse response = tokenService.issueFor(credential, tenantCodeOrNull, ch, client);
        credential.recordSuccess(now);
        savePort.save(credential);
        publishUnderTenant(credential.pullEvents(), Optional.ofNullable(response.tenantId()), credential.userId());
        return response;
    }

    private void publishUnderTenant(List<DomainEvent> events, Optional<UUID> tenantId, UUID userId) {
        if (events.isEmpty()) return;
        if (tenantId.isEmpty()) {
            log.warn("Không phát audit đăng nhập (không xác định được tenant): userId={}, events={}", userId,
                    events.stream().map(e -> e.getClass().getSimpleName()).toList());
            return;
        }
        TenantContext.runAs(tenantId.get(), () -> events.forEach(eventPort::publish));
    }
}
