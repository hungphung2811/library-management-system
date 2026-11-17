package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.ShelfLocationInactiveException;
import lms.catalog.inventory.domain.ShelfLocationNotFoundException;
import java.util.List;
import java.util.UUID;

/** Kiểm vị trí kệ khi gán cho bản sao: phải tồn tại cùng tenant và còn active (trừ khi giữ nguyên giá trị hiện có). */
final class ShelfLocationCheck {

    private ShelfLocationCheck() {
    }

    static void require(BookCopyRepository repository, UUID shelfLocationId, UUID currentShelfLocationId) {
        if (shelfLocationId == null) return;
        boolean active = repository.findShelfLocationActive(shelfLocationId)
                .orElseThrow(() -> new ShelfLocationNotFoundException(shelfLocationId));
        if (!active && !shelfLocationId.equals(currentShelfLocationId)) {
            throw new ShelfLocationInactiveException(shelfLocationId);
        }
    }

    static String code(BookCopyRepository repository, UUID shelfLocationId) {
        if (shelfLocationId == null) return null;
        return repository.findShelfLocationCodes(List.of(shelfLocationId)).get(shelfLocationId);
    }
}
