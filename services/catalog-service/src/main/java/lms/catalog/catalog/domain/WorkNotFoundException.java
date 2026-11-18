package lms.catalog.catalog.domain;

import java.util.UUID;

public class WorkNotFoundException extends RuntimeException {
    public WorkNotFoundException(UUID id) {
        super("Không tìm thấy tác phẩm: " + id);
    }
}
