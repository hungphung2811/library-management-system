package lms.system.cms.domain;

public class CmsException extends RuntimeException {

    private final CmsErrorCode code;

    public CmsException(CmsErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public CmsErrorCode code() {
        return code;
    }
}
