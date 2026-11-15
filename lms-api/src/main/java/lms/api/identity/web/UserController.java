package lms.api.identity.web;

import lms.api.identity.client.UserClient;
import lms.api.identity.dto.UserDtos.ChangeStatusRequest;
import lms.api.identity.dto.UserDtos.CreateUserRequest;
import lms.api.identity.dto.UserDtos.IssueReaderCardRequest;
import lms.api.identity.dto.UserDtos.ReaderProfileView;
import lms.api.identity.dto.UserDtos.RenewReaderCardRequest;
import lms.api.identity.dto.UserDtos.ResetPasswordRequest;
import lms.api.identity.dto.UserDtos.SearchUsersQuery;
import lms.api.identity.dto.UserDtos.UpdateUserRequest;
import lms.api.identity.dto.UserDtos.UserPageView;
import lms.api.identity.dto.UserDtos.UserView;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API người dùng và hồ sơ độc giả cho FE. Quyền do identity-service kiểm trên token được chuyển tiếp. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserClient users;

    public UserController(UserClient users) {
        this.users = users;
    }

    @PostMapping
    public ResponseEntity<UserView> create(@Valid @RequestBody CreateUserRequest request) {
        UserView created = users.create(request);
        return ResponseEntity.created(URI.create("/api/users/" + created.id())).body(created);
    }

    @GetMapping
    public UserPageView search(SearchUsersQuery query) {
        return users.search(query);
    }

    @GetMapping("/me")
    public UserView me() {
        return users.me();
    }

    @GetMapping("/me/reader-profile")
    public ReaderProfileView myReaderProfile() {
        return users.myReaderProfile();
    }

    @GetMapping("/{id}")
    public UserView get(@PathVariable UUID id) {
        return users.get(id);
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return users.update(id, request);
    }

    @PutMapping("/{id}/status")
    public UserView changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStatusRequest request) {
        return users.changeStatus(id, request);
    }

    /** Quản trị đặt mật khẩu tạm; thân yêu cầu chứa mật khẩu nên không được log. */
    @PostMapping("/{id}/password-reset")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id,
                                              @Valid @RequestBody ResetPasswordRequest request) {
        users.resetPassword(id, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{userId}/reader-profile")
    public ReaderProfileView readerProfile(@PathVariable UUID userId) {
        return users.readerProfile(userId);
    }

    @PostMapping("/{userId}/reader-profile")
    public ResponseEntity<ReaderProfileView> issueReaderCard(@PathVariable UUID userId,
                                                             @Valid @RequestBody IssueReaderCardRequest request) {
        ReaderProfileView created = users.issueReaderCard(userId, request);
        return ResponseEntity.created(URI.create("/api/users/" + userId + "/reader-profile")).body(created);
    }

    @PutMapping("/{userId}/reader-profile/card-expiry")
    public ReaderProfileView renewReaderCard(@PathVariable UUID userId,
                                             @Valid @RequestBody RenewReaderCardRequest request) {
        return users.renewReaderCard(userId, request);
    }

    @PutMapping("/{userId}/reader-profile/status")
    public ReaderProfileView changeReaderStatus(@PathVariable UUID userId,
                                                @Valid @RequestBody ChangeStatusRequest request) {
        return users.changeReaderStatus(userId, request);
    }
}
