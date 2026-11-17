package lms.catalog.inventory.domain;

import java.util.UUID;

public class ShelfLocationNotFoundException extends RuntimeException {
    public ShelfLocationNotFoundException(UUID id) {
        super("Không tìm thấy vị trí kệ: " + id);
    }
}
