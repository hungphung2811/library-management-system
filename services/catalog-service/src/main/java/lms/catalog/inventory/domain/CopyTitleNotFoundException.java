package lms.catalog.inventory.domain;

import java.util.UUID;

public class CopyTitleNotFoundException extends RuntimeException {
    public CopyTitleNotFoundException(UUID titleId) {
        super("Không tìm thấy đầu sách: " + titleId);
    }
}
