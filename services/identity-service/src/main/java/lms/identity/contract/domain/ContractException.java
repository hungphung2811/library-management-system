package lms.identity.contract.domain;

/** Lỗi nghiệp vụ của hợp đồng; {@link Reason} là mã lỗi trả về FE (ADR-0012 mục 2.5.3). */
public class ContractException extends RuntimeException {

    public enum Reason {
        INVALID_MODULES, UNKNOWN_MODULE, INVALID_CONTRACT, INVALID_PAYMENT,
        TENANT_NOT_FOUND, CONTRACT_NOT_FOUND, PAYMENT_NOT_FOUND,
        CONTRACT_OVERLAP, CONTRACT_NOT_EDITABLE, CONTRACT_VERSION_CONFLICT,
        PAYMENT_NOT_ALLOWED, PAYMENT_ALREADY_REVERSED, PAYMENT_NOT_REVERSIBLE
    }

    private final Reason reason;

    public ContractException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
