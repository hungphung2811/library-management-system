package lms.identity.user.infrastructure.web;

import lms.identity.user.application.ChangeUserStatusRequest;
import lms.identity.user.application.ChangeUserStatusUseCase;
import lms.identity.user.application.CreateUserRequest;
import lms.identity.user.application.CreateUserUseCase;
import lms.identity.user.application.GetUserUseCase;
import lms.identity.user.application.ResetUserPasswordRequest;
import lms.identity.user.application.ResetUserPasswordUseCase;
import lms.identity.user.application.SearchUsersUseCase;
import lms.identity.user.application.UpdateUserRequest;
import lms.identity.user.application.UpdateUserUseCase;
import lms.identity.user.application.UserPageResponse;
import lms.identity.user.application.UserResponse;
import lms.identity.user.domain.UserStatus;
import lms.security.CurrentUser;
import lms.security.permission.AuthenticatedOnly;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

    private final CreateUserUseCase create;
    private final UpdateUserUseCase update;
    private final ChangeUserStatusUseCase changeStatus;
    private final GetUserUseCase get;
    private final SearchUsersUseCase search;
    private final ResetUserPasswordUseCase resetPassword;

    public UserController(CreateUserUseCase create, UpdateUserUseCase update, ChangeUserStatusUseCase changeStatus,
                          GetUserUseCase get, SearchUsersUseCase search, ResetUserPasswordUseCase resetPassword) {
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

    @GetMapping
    public UserPageResponse search(@RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) UUID departmentId,
                                   @RequestParam(required = false) UserStatus status,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return search.execute(keyword, departmentId, status, page, size);
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

    @PutMapping("/{id}/status")
    public UserResponse changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                     @Valid @RequestBody ChangeUserStatusRequest request) {
        return changeStatus.execute(CurrentUser.from(jwt).userId(), id, request.status());
    }
}
