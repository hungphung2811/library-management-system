package lms.api.identity.client;

import lms.api.identity.dto.UserDtos.ChangeStatusRequest;
import lms.api.identity.dto.UserDtos.CreateUserRequest;
import lms.api.identity.dto.UserDtos.IssueReaderCardRequest;
import lms.api.identity.dto.UserDtos.ReaderProfileView;
import lms.api.identity.dto.UserDtos.RenewReaderCardRequest;
import lms.api.identity.dto.UserDtos.UpdateReaderProfileRequest;
import lms.api.identity.dto.UserDtos.ResetPasswordRequest;
import lms.api.identity.dto.UserDtos.SearchUsersQuery;
import lms.api.identity.dto.UserDtos.UpdateUserRequest;
import lms.api.identity.dto.UserDtos.UserPageView;
import lms.api.identity.dto.UserDtos.UserView;
import lms.api.identity.dto.UserDtos.ExportUsersQuery;
import lms.api.identity.dto.UserDtos.ImportUsersRequest;
import lms.api.identity.dto.UserDtos.ImportUsersResult;
import lms.api.identity.dto.UserDtos.RevokedSessionsView;
import lms.api.identity.dto.UserDtos.UserSessionView;
import java.util.List;
import java.util.UUID;

/** Cổng sang API người dùng và hồ sơ độc giả của identity-service. */
public interface UserClient {

    UserView create(CreateUserRequest request);

    UserPageView search(SearchUsersQuery query);

    UserView me();

    UserView get(UUID id);

    UserView update(UUID id, UpdateUserRequest request);

    UserView changeStatus(UUID id, ChangeStatusRequest request);

    void resetPassword(UUID id, ResetPasswordRequest request);

    ReaderProfileView myReaderProfile();

    ReaderProfileView readerProfile(UUID userId);

    ReaderProfileView issueReaderCard(UUID userId, IssueReaderCardRequest request);

    ReaderProfileView updateReaderProfile(UUID userId, UpdateReaderProfileRequest request);

    ReaderProfileView renewReaderCard(UUID userId, RenewReaderCardRequest request);

    ReaderProfileView changeReaderStatus(UUID userId, ChangeStatusRequest request);

    /** Mở khóa tài khoản bị khóa do đăng nhập sai; trả hồ sơ với locked=false. */
    UserView unlock(UUID id);

    /** Thu hồi mọi refresh token của người dùng trong tenant hiện tại. */
    RevokedSessionsView revokeSessions(UUID id);

    /** Phiên của người dùng trong tenant hiện tại (có IP đầy đủ; identity ghi audit mỗi lần xem). */
    List<UserSessionView> listSessions(UUID id);

    /** Thu hồi một phiên của người dùng; 404 SESSION_NOT_FOUND/USER_NOT_FOUND. */
    void revokeSession(UUID id, UUID sessionId);

    ImportUsersResult importUsers(ImportUsersRequest request);

    /** Nội dung CSV UTF-8 (đã có BOM) của identity. */
    byte[] exportUsers(ExportUsersQuery query);
}
