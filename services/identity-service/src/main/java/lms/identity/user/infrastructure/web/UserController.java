package lms.identity.user.infrastructure.web;

import lms.identity.user.application.ChangeUserStatusRequest;
import lms.identity.user.application.ChangeUserStatusUseCase;
import lms.identity.user.application.CreateUserRequest;
import lms.identity.user.application.CreateUserUseCase;
import lms.identity.user.application.GetUserUseCase;
import lms.identity.user.application.ExportUsersUseCase;
import lms.identity.user.application.ImportUsersRequest;
import lms.identity.user.application.ImportUsersResponse;
import lms.identity.user.application.ImportUsersUseCase;
import lms.identity.user.application.ListUserSessionsUseCase;
import lms.identity.user.application.RevokeUserSessionUseCase;
import lms.identity.user.application.RevokeUserSessionsInTenantUseCase;
import lms.identity.user.application.SessionCaller;
import lms.identity.user.application.UserSessionView;
import lms.identity.user.application.RevokedSessionsResponse;
import lms.identity.user.application.UnlockUserUseCase;
import lms.identity.user.application.UserExportQuery;
import lms.identity.user.application.ResetUserPasswordRequest;
import lms.identity.user.application.ResetUserPasswordUseCase;
import lms.identity.user.application.SearchUsersUseCase;
import lms.identity.user.application.UpdateUserRequest;
import lms.identity.user.application.UpdateUserUseCase;
import lms.identity.user.application.UserPageResponse;
import lms.identity.user.application.UserResponse;
import lms.identity.user.domain.UserStatus;
import lms.security.CurrentUser;
import lms.security.JwtClaims;
import lms.security.permission.AuthenticatedOnly;
import lms.security.permission.CrossTenantReadable;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quản lý người dùng (hồ sơ định danh). Không gắn annotation quyền (trừ /me): mỗi method dùng khóa tự sinh
 * {@code UserController/tênPhươngThức}. Hồ sơ độc giả nằm ở {@link ReaderProfileController} dưới /internal/users/{id}/reader-profile.
 * Mọi thao tác chỉ thấy/tác động người có membership ở tenant trong token (người ngoài tenant: 404).
 */
@RestController
@RequestMapping("/internal/users")
public class UserController {

    /** BOM UTF-8 để Excel nhận đúng tiếng Việt. */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final CreateUserUseCase create;
    private final UpdateUserUseCase update;
    private final ChangeUserStatusUseCase changeStatus;
    private final GetUserUseCase get;
    private final SearchUsersUseCase search;
    private final ResetUserPasswordUseCase resetPassword;
    private final UnlockUserUseCase unlock;
    private final RevokeUserSessionsInTenantUseCase revokeSessions;
    private final ListUserSessionsUseCase listSessions;
    private final RevokeUserSessionUseCase revokeSession;
    private final ImportUsersUseCase importUsers;
    private final ExportUsersUseCase exportUsers;
    private final lms.identity.user.application.LookupUsersUseCase lookupUsers;

    public UserController(CreateUserUseCase create, UpdateUserUseCase update, ChangeUserStatusUseCase changeStatus,
                          GetUserUseCase get, SearchUsersUseCase search, ResetUserPasswordUseCase resetPassword,
                          UnlockUserUseCase unlock, RevokeUserSessionsInTenantUseCase revokeSessions,
                          ImportUsersUseCase importUsers, ExportUsersUseCase exportUsers,
                          ListUserSessionsUseCase listSessions, RevokeUserSessionUseCase revokeSession,
                          lms.identity.user.application.LookupUsersUseCase lookupUsers) {
        this.lookupUsers = lookupUsers;
        this.listSessions = listSessions;
        this.revokeSession = revokeSession;
        this.unlock = unlock;
        this.revokeSessions = revokeSessions;
        this.importUsers = importUsers;
        this.exportUsers = exportUsers;
        this.create = create;
        this.update = update;
        this.changeStatus = changeStatus;
        this.get = get;
        this.search = search;
        this.resetPassword = resetPassword;
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse response = create.execute(request);
        return ResponseEntity.created(URI.create("/internal/users/" + response.id())).body(response);
    }

    @CrossTenantReadable
    @GetMapping
    public UserPageResponse search(@RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) UUID departmentId,
                                   @RequestParam(required = false) UserStatus status,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return search.execute(keyword, departmentId, status, page, size);
    }

    /**
     * Tra họ tên theo lô (tối đa 100 id) trong tenant hiện tại; id ngoài tenant không có trong kết quả (ADR-0018).
     * Khóa tự sinh {@code UserController/lookup}. Không trả email/điện thoại.
     */
    @PostMapping("/lookup")
    public List<lms.identity.user.application.UserNameView> lookup(
            @Valid @RequestBody lms.identity.user.application.LookupUsersRequest request) {
        return lookupUsers.execute(request.ids());
    }

    /** Người dùng xem hồ sơ của chính mình. */
    @AuthenticatedOnly
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return get.me(CurrentUser.from(jwt).userId());
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable UUID id) {
        return get.execute(id);
    }

    @PutMapping("/{id}")
    public UserResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                               @Valid @RequestBody UpdateUserRequest request) {
        return update.execute(CurrentUser.from(jwt).userId(), id, request);
    }

    /** Đặt mật khẩu tạm (buộc đổi ở lần đăng nhập sau); chỉ với tài khoản chỉ thuộc tenant hiện tại (spec 5.6). */
    @PostMapping("/{id}/password-reset")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id,
                                              @Valid @RequestBody ResetUserPasswordRequest request) {
        resetPassword.execute(id, request.temporaryPassword());
        return ResponseEntity.noContent().build();
    }

    /** Mở khóa tài khoản bị khóa do đăng nhập sai nhiều lần; trả hồ sơ với locked=false. */
    @PostMapping("/{id}/unlock")
    public UserResponse unlock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return unlock.execute(CurrentUser.from(jwt).userId(), id);
    }

    /**
     * Thu hồi mọi phiên (refresh token) của người dùng trong tenant hiện tại. Access token đã phát còn dùng được tối đa
     * đến hết TTL (mặc định 15 phút).
     */
    @PostMapping("/{id}/sessions/revocation")
    public RevokedSessionsResponse revokeSessions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return new RevokedSessionsResponse(revokeSessions.execute(caller(jwt), id));
    }

    /**
     * Phiên đang đăng nhập của một người dùng trong tenant hiện tại (ADR-0017 mục 2.9, quy tắc G1-G6). Khóa tự sinh
     * {@code UserController/listSessions}. Chứa IP đầy đủ nên {@code no-store}; xem phiên của người khác được ghi audit.
     */
    @GetMapping("/{id}/sessions")
    public ResponseEntity<List<UserSessionView>> listSessions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(listSessions.execute(caller(jwt), id));
    }

    /**
     * Thu hồi một phiên của người dùng trong tenant hiện tại. Khóa tự sinh {@code UserController/revokeSession}
     * (khác {@code revokeSessions} một chữ s). 404 SESSION_NOT_FOUND là mã chung cho mọi phiên không khớp.
     */
    @DeleteMapping("/{id}/sessions/{sessionId}")
    public ResponseEntity<Void> revokeSession(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                              @PathVariable UUID sessionId) {
        revokeSession.execute(caller(jwt), id, sessionId);
        return ResponseEntity.noContent().build();
    }

    /** Người gọi từ claim: userId (sub), admin và platformAdmin (chỉ true khi claim là true). */
    private static SessionCaller caller(Jwt jwt) {
        return new SessionCaller(CurrentUser.from(jwt).userId(),
                Boolean.TRUE.equals(jwt.getClaim(JwtClaims.ADMIN)),
                Boolean.TRUE.equals(jwt.getClaim(JwtClaims.PLATFORM_ADMIN)));
    }

    /** Nhập hàng loạt (tối đa 500 dòng). Response chứa mật khẩu tạm đã sinh nên không được cache. */
    @PostMapping("/imports")
    public ResponseEntity<ImportUsersResponse> importUsers(@Valid @RequestBody ImportUsersRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(importUsers.execute(request));
    }

    /** Xuất CSV (UTF-8 có BOM, tối đa 5000 dòng) theo cùng bộ lọc tìm kiếm. */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportUsers(UserExportQuery query) {
        String csv = exportUsers.execute(query.keyword(), query.departmentId(), query.status());
        byte[] csvBytes = csv.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[UTF8_BOM.length + csvBytes.length];
        System.arraycopy(UTF8_BOM, 0, body, 0, UTF8_BOM.length);
        System.arraycopy(csvBytes, 0, body, UTF8_BOM.length, csvBytes.length);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"users.csv\"")
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    @PutMapping("/{id}/status")
    public UserResponse changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                     @Valid @RequestBody ChangeUserStatusRequest request) {
        return changeStatus.execute(CurrentUser.from(jwt).userId(), id, request.status());
    }
}
