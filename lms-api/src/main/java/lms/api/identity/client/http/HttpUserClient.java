package lms.api.identity.client.http;

import lms.api.identity.client.UserClient;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Gọi API nội bộ /internal/users/** của identity-service (người dùng và hồ sơ độc giả). */
public class HttpUserClient implements UserClient {

    private final RestClient client;

    public HttpUserClient(RestClient client) {
        this.client = client;
    }

    @Override
    public UserView create(CreateUserRequest request) {
        return client.post().uri("/internal/users").body(request).retrieve().body(UserView.class);
    }

    @Override
    public UserPageView search(SearchUsersQuery q) {
        Map<String, Object> vars = new LinkedHashMap<>();
        if (q.keyword() != null && !q.keyword().isBlank()) vars.put("keyword", q.keyword());
        if (q.departmentId() != null) vars.put("departmentId", q.departmentId());
        if (q.status() != null && !q.status().isBlank()) vars.put("status", q.status());
        if (q.page() != null) vars.put("page", q.page());
        if (q.size() != null) vars.put("size", q.size());
        if (q.tenantId() != null && !q.tenantId().isBlank()) vars.put("tenantId", q.tenantId());

        StringBuilder template = new StringBuilder("/internal/users");
        String sep = "?";
        for (String key : vars.keySet()) {
            template.append(sep).append(key).append("={").append(key).append('}');
            sep = "&";
        }
        return client.get().uri(template.toString(), vars).retrieve().body(UserPageView.class);
    }

    @Override
    public UserView me() {
        return client.get().uri("/internal/users/me").retrieve().body(UserView.class);
    }

    @Override
    public UserView get(UUID id) {
        return client.get().uri("/internal/users/{id}", id).retrieve().body(UserView.class);
    }

    @Override
    public UserView update(UUID id, UpdateUserRequest request) {
        return client.put().uri("/internal/users/{id}", id).body(request).retrieve().body(UserView.class);
    }

    @Override
    public UserView changeStatus(UUID id, ChangeStatusRequest request) {
        return client.put().uri("/internal/users/{id}/status", id).body(request).retrieve().body(UserView.class);
    }

    @Override
    public void resetPassword(UUID id, ResetPasswordRequest request) {
        client.post().uri("/internal/users/{id}/password-reset", id).body(request).retrieve().toBodilessEntity();
    }

    @Override
    public ReaderProfileView myReaderProfile() {
        return client.get().uri("/internal/users/me/reader-profile").retrieve().body(ReaderProfileView.class);
    }

    @Override
    public ReaderProfileView readerProfile(UUID userId) {
        return client.get().uri("/internal/users/{id}/reader-profile", userId).retrieve().body(ReaderProfileView.class);
    }

    @Override
    public ReaderProfileView issueReaderCard(UUID userId, IssueReaderCardRequest request) {
        return client.post().uri("/internal/users/{id}/reader-profile", userId).body(request).retrieve()
                .body(ReaderProfileView.class);
    }

    @Override
    public ReaderProfileView updateReaderProfile(UUID userId, UpdateReaderProfileRequest request) {
        return client.put().uri("/internal/users/{id}/reader-profile", userId).body(request).retrieve()
                .body(ReaderProfileView.class);
    }

    @Override
    public ReaderProfileView renewReaderCard(UUID userId, RenewReaderCardRequest request) {
        return client.put().uri("/internal/users/{id}/reader-profile/card-expiry", userId).body(request).retrieve()
                .body(ReaderProfileView.class);
    }

    @Override
    public ReaderProfileView changeReaderStatus(UUID userId, ChangeStatusRequest request) {
        return client.put().uri("/internal/users/{id}/reader-profile/status", userId).body(request).retrieve()
                .body(ReaderProfileView.class);
    }

    @Override
    public UserView unlock(UUID id) {
        return client.post().uri("/internal/users/{id}/unlock", id).retrieve().body(UserView.class);
    }

    @Override
    public RevokedSessionsView revokeSessions(UUID id) {
        return client.post().uri("/internal/users/{id}/sessions/revocation", id).retrieve()
                .body(RevokedSessionsView.class);
    }

    @Override
    public List<UserSessionView> listSessions(UUID id) {
        return client.get().uri("/internal/users/{id}/sessions", id).retrieve()
                .body(new ParameterizedTypeReference<List<UserSessionView>>() { });
    }

    @Override
    public void revokeSession(UUID id, UUID sessionId) {
        client.delete().uri("/internal/users/{id}/sessions/{sid}", id, sessionId).retrieve().toBodilessEntity();
    }

    @Override
    public ImportUsersResult importUsers(ImportUsersRequest request) {
        return client.post().uri("/internal/users/imports").body(request).retrieve().body(ImportUsersResult.class);
    }

    @Override
    public byte[] exportUsers(ExportUsersQuery q) {
        Map<String, Object> vars = new LinkedHashMap<>();
        if (q.keyword() != null && !q.keyword().isBlank()) vars.put("keyword", q.keyword());
        if (q.departmentId() != null) vars.put("departmentId", q.departmentId());
        if (q.status() != null && !q.status().isBlank()) vars.put("status", q.status());
        StringBuilder template = new StringBuilder("/internal/users/export");
        String sep = "?";
        for (String key : vars.keySet()) {
            template.append(sep).append(key).append("={").append(key).append('}');
            sep = "&";
        }
        return client.get().uri(template.toString(), vars).retrieve().body(byte[].class);
    }
}
