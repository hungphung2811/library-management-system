package lms.sales.contact.domain;

/** Trạng thái xử lý (R6): NEW sang IN_PROGRESS sang CLOSED, cho phép NEW sang CLOSED; không mở lại CLOSED. */
public enum ContactStatus {
    NEW, IN_PROGRESS, CLOSED;

    public boolean canTransitionTo(ContactStatus to) {
        return switch (this) {
            case NEW -> to == IN_PROGRESS || to == CLOSED;
            case IN_PROGRESS -> to == CLOSED;
            case CLOSED -> false;
        };
    }
}
