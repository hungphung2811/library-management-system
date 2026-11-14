package lms.common.kernel;

import java.time.Instant;
import java.util.List;

/** Cấu trúc response lỗi thống nhất giữa các service. */
public record ErrorResponse(String code, String message, List<String> details, Instant timestamp) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, List.of(), Instant.now());
    }

    public static ErrorResponse of(String code, String message, List<String> details) {
        return new ErrorResponse(code, message, List.copyOf(details), Instant.now());
    }
}
