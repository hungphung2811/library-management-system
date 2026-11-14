package lms.catalog.catalog.domain;

import java.util.UUID;

public class TitleNotFoundException extends RuntimeException {
    public TitleNotFoundException(UUID id) {
        super("Không tìm thấy đầu sách: " + id);
    }
}
