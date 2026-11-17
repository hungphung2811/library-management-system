package lms.identity.tenant.domain;

/** Mã module không thuộc tập {@link ModuleCode}. */
public class UnknownModuleException extends RuntimeException {
    public UnknownModuleException(String message) {
        super(message);
    }
}
