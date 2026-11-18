package lms.imports.web;

import java.util.List;
import lms.common.kernel.ErrorResponse;
import lms.imports.core.ImportErrorCode;
import lms.imports.core.ImportException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Chuyển lỗi của các controller nhập thành {@link ErrorResponse} chuẩn với mã {@code IMPORT_*}. */
@RestControllerAdvice(assignableTypes = AbstractImportController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ImportExceptionHandler {

    @ExceptionHandler(ImportException.class)
    public ResponseEntity<ErrorResponse> handle(ImportException e) {
        ImportErrorCode c = e.errorCode();
        return ResponseEntity.status(c.httpStatus()).body(ErrorResponse.of(c.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class,
            MultipartException.class})
    public ResponseEntity<ErrorResponse> missingFile(Exception e) {
        ImportErrorCode c = ImportErrorCode.FILE_REQUIRED;
        return ResponseEntity.status(c.httpStatus()).body(ErrorResponse.of(c.code(), c.defaultMessage(), List.of()));
    }

    /** importId không phải UUID: cùng mã với phiên không tồn tại. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> badId(MethodArgumentTypeMismatchException e) {
        ImportErrorCode c = ImportErrorCode.NOT_FOUND;
        return ResponseEntity.status(c.httpStatus()).body(ErrorResponse.of(c.code(), c.defaultMessage(), List.of()));
    }
}
