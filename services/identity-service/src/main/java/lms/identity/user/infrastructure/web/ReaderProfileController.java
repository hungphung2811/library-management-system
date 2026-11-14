package lms.identity.user.infrastructure.web;

import lms.identity.user.application.ChangeReaderStatusRequest;
import lms.identity.user.application.ChangeReaderStatusUseCase;
import lms.identity.user.application.GetReaderProfileUseCase;
import lms.identity.user.application.IssueReaderCardCommand;
import lms.identity.user.application.IssueReaderCardRequest;
import lms.identity.user.application.IssueReaderCardUseCase;
import lms.identity.user.application.ReaderProfileResponse;
import lms.identity.user.application.RenewReaderCardRequest;
import lms.identity.user.application.RenewReaderCardUseCase;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class ReaderProfileController {

    private final IssueReaderCardUseCase issueCard;
    private final GetReaderProfileUseCase getProfile;
    private final RenewReaderCardUseCase renewCard;
    private final ChangeReaderStatusUseCase changeStatus;

    public ReaderProfileController(IssueReaderCardUseCase issueCard, GetReaderProfileUseCase getProfile,
                                   RenewReaderCardUseCase renewCard, ChangeReaderStatusUseCase changeStatus) {
        this.issueCard = issueCard;
        this.getProfile = getProfile;
        this.renewCard = renewCard;
        this.changeStatus = changeStatus;
    }

    /** Độc giả xem hồ sơ của chính mình; circulation gọi endpoint này (kèm token người dùng) để kiểm tra QĐ2. */
    @AuthenticatedOnly
    @GetMapping("/me/reader-profile")
    public ReaderProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        return getProfile.execute(CurrentUser.from(jwt).userId());
    }

    @GetMapping("/{userId}/reader-profile")
    public ReaderProfileResponse get(@PathVariable UUID userId) {
        return getProfile.execute(userId);
    }

    @PostMapping("/{userId}/reader-profile")
    public ResponseEntity<ReaderProfileResponse> issue(@PathVariable UUID userId,
                                                       @Valid @RequestBody IssueReaderCardRequest request) {
        ReaderProfileResponse response = issueCard.execute(new IssueReaderCardCommand(userId, request.cardNo(),
                request.fullName(), request.email(), request.readerType(), request.cardExpiryDate()));
        return ResponseEntity.created(URI.create("/api/users/" + userId + "/reader-profile")).body(response);
    }

    @PutMapping("/{userId}/reader-profile/card-expiry")
    public ReaderProfileResponse renew(@PathVariable UUID userId, @Valid @RequestBody RenewReaderCardRequest request) {
        return renewCard.execute(userId, request.newExpiryDate());
    }

    @PutMapping("/{userId}/reader-profile/status")
    public ReaderProfileResponse changeStatus(@PathVariable UUID userId,
                                              @Valid @RequestBody ChangeReaderStatusRequest request) {
        return changeStatus.execute(userId, request.status());
    }
}
