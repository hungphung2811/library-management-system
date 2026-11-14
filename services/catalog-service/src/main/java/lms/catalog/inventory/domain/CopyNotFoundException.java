package lms.catalog.inventory.domain;

import java.util.UUID;

public class CopyNotFoundException extends RuntimeException {
    public CopyNotFoundException(String key) {
        super("Không tìm thấy bản sao: " + key);
    }

    public CopyNotFoundException(UUID id) {
        this(String.valueOf(id));
    }
}
