package lms.imports.web;

import java.util.List;
import lms.common.kernel.ErrorResponse;
import lms.imports.core.ImportErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * {@code MaxUploadSizeExceededException} ném khi phân giải multipart, TRƯỚC khi chọn controller, nên advice giới hạn theo
 * controller không bắt được; advice này là toàn cục nhưng chỉ xử lý đúng ngoại lệ đó thành 413 {@code IMPORT_FILE_TOO_LARGE}
 * (hiện chỉ luồng nhập có upload multipart). Service cần cấu hình {@code spring.servlet.multipart.max-file-size: 5MB}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MultipartLimitHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> tooLarge(MaxUploadSizeExceededException e) {
        ImportErrorCode c = ImportErrorCode.FILE_TOO_LARGE;
        return ResponseEntity.status(c.httpStatus()).body(ErrorResponse.of(c.code(), c.defaultMessage(), List.of()));
    }
}
