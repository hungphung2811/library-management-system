package lms.identity.user.domain;

import java.util.List;

public record UserPage(List<User> items, long total, int page, int size) {
}
