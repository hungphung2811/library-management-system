package lms.system.cms.domain;

import java.util.EnumSet;
import java.util.Set;

public enum ContactStatus {
    NEW, IN_PROGRESS, HANDLED, SPAM;

    /** Các trạng thái được chuyển tới từ trạng thái này. */
    public Set<ContactStatus> allowedNext() {
        return switch (this) {
            case NEW -> EnumSet.of(IN_PROGRESS, HANDLED, SPAM);
            case IN_PROGRESS -> EnumSet.of(NEW, HANDLED, SPAM);
            case HANDLED -> EnumSet.of(IN_PROGRESS);
            case SPAM -> EnumSet.of(NEW);
        };
    }
}
