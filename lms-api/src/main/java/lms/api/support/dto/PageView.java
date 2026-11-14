package lms.api.support.dto;

import java.util.List;

public record PageView<T>(List<T> items, long total, int page, int size) {
}
