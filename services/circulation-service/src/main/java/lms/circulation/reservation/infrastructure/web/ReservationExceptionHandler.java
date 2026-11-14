package lms.circulation.reservation.infrastructure.web;

import lms.circulation.reservation.application.ExternalServiceUnavailableException;
import lms.circulation.reservation.domain.AlreadyReservedException;
import lms.circulation.reservation.domain.CopyStillAvailableException;
import lms.circulation.reservation.domain.InvalidReservationException;
import lms.circulation.reservation.domain.ReaderNotEligibleException;
import lms.circulation.reservation.domain.ReservationNotFoundException;
import lms.circulation.reservation.domain.TitleNotFoundException;
import lms.common.kernel.ErrorResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "lms.circulation.reservation")
public class ReservationExceptionHandler {

    @ExceptionHandler(ReservationNotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(ReservationNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(TitleNotFoundException.class)
    ResponseEntity<ErrorResponse> handleTitleNotFound(TitleNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "TITLE_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(ReaderNotEligibleException.class)
    ResponseEntity<ErrorResponse> handleNotEligible(ReaderNotEligibleException ex) {
        return build(HttpStatus.CONFLICT, "READER_NOT_ELIGIBLE", ex.getMessage());
    }

    @ExceptionHandler(CopyStillAvailableException.class)
    ResponseEntity<ErrorResponse> handleCopyAvailable(CopyStillAvailableException ex) {
        return build(HttpStatus.CONFLICT, "COPY_AVAILABLE", ex.getMessage());
    }

    @ExceptionHandler(AlreadyReservedException.class)
    ResponseEntity<ErrorResponse> handleAlreadyReserved(AlreadyReservedException ex) {
        return build(HttpStatus.CONFLICT, "ALREADY_RESERVED", ex.getMessage());
    }

    @ExceptionHandler(InvalidReservationException.class)
    ResponseEntity<ErrorResponse> handleInvalid(InvalidReservationException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RESERVATION", ex.getMessage());
    }

    @ExceptionHandler(ExternalServiceUnavailableException.class)
    ResponseEntity<ErrorResponse> handleUnavailable(ExternalServiceUnavailableException ex) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_FAILED", "Dữ liệu không hợp lệ", details));
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}
