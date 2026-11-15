package lms.api.identity.client;

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

    ReaderProfileView renewReaderCard(UUID userId, RenewReaderCardRequest request);

    ReaderProfileView changeReaderStatus(UUID userId, ChangeStatusRequest request);
}
