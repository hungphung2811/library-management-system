package lms.tenant;

/** Thao tác cần tenant nhưng ngữ cảnh hiện tại không có. */
public class TenantRequiredException extends IllegalStateException {

    public TenantRequiredException() {
        super("Không có tenant trong ngữ cảnh hiện tại");
    }
}
