package lms.catalog.inventory.domain;

import java.util.UUID;

public class ShelfLocationInactiveException extends RuntimeException {
    public ShelfLocationInactiveException(UUID id) {
        super("Không thể gán vị trí kệ đã ngừng sử dụng: " + id);
    }
}
