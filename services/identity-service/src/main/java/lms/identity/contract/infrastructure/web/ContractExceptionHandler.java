package lms.identity.contract.infrastructure.web;

import lms.common.kernel.ErrorResponse;
import lms.identity.contract.domain.ContractException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Ánh xạ {@link ContractException} sang HTTP (ADR-0012 mục 2.5.3), định dạng {@code {code, message}} như hiện có. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ContractExceptionHandler {

    @ExceptionHandler(ContractException.class)
    ResponseEntity<ErrorResponse> handle(ContractException ex) {
        HttpStatus status = switch (ex.reason()) {
            case INVALID_MODULES, UNKNOWN_MODULE, INVALID_CONTRACT, INVALID_PAYMENT -> HttpStatus.BAD_REQUEST;
            case TENANT_NOT_FOUND, CONTRACT_NOT_FOUND, PAYMENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONTRACT_OVERLAP, CONTRACT_NOT_EDITABLE, CONTRACT_VERSION_CONFLICT, PAYMENT_NOT_ALLOWED,
                 PAYMENT_ALREADY_REVERSED, PAYMENT_NOT_REVERSIBLE -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(ErrorResponse.of(ex.reason().name(), ex.getMessage()));
    }
}
