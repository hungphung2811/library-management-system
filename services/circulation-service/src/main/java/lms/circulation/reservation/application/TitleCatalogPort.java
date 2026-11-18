package lms.circulation.reservation.application;

import java.util.Optional;
import java.util.UUID;

/** Cổng sang catalog-service: thông tin ấn bản (đầu sách), tác phẩm của nó và số bản sao sẵn có. */
public interface TitleCatalogPort {

    Optional<TitleInfo> findTitle(UUID titleId);

    /**
     * @param availableCopies     bản sao sẵn có của chính ấn bản
     * @param workId              tác phẩm của ấn bản; null nếu catalog chưa gắn tác phẩm
     * @param language            ngôn ngữ ấn bản; null nếu chưa khai
     * @param workAvailableCopies bản sao sẵn có của mọi ấn bản cùng tác phẩm CÙNG ngôn ngữ; null nếu catalog chưa trả
     */
    record TitleInfo(UUID id, String name, long availableCopies, UUID workId, String language,
                     Long workAvailableCopies) {

        /** Dạng chưa có thông tin tác phẩm (catalog cũ): coi như tác phẩm 1:1 của ấn bản. */
        public TitleInfo(UUID id, String name, long availableCopies) {
            this(id, name, availableCopies, null, null, null);
        }

        /** Số bản sao sẵn có dùng cho điều kiện "chỉ đặt khi hết": cả tác phẩm cùng ngôn ngữ nếu biết. */
        public long availableForReservation() {
            return workAvailableCopies != null ? Math.max(workAvailableCopies, availableCopies) : availableCopies;
        }
    }
}
