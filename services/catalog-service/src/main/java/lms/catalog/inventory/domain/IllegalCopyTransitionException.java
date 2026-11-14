package lms.catalog.inventory.domain;

public class IllegalCopyTransitionException extends RuntimeException {
    public IllegalCopyTransitionException(CopyStatus from, CopyStatus to) {
        super("Không thể chuyển bản sao từ " + from + " sang " + to);
    }
}
