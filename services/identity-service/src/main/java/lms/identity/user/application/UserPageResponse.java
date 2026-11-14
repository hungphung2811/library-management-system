package lms.identity.user.application;

import java.util.List;

public record UserPageResponse(List<UserResponse> items, long total, int page, int size) {
}
