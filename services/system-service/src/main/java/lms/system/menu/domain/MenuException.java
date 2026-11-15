package lms.system.menu.domain;

public class MenuException extends RuntimeException {

    private final MenuErrorCode code;

    public MenuException(MenuErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public MenuErrorCode code() {
        return code;
    }
}
