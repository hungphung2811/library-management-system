package lms.circulation.reservation.application;

import java.util.Optional;
import java.util.UUID;

/** Cổng sang catalog-service: thông tin đầu sách và số bản sao sẵn có. */
public interface TitleCatalogPort {

    Optional<TitleInfo> findTitle(UUID titleId);

    record TitleInfo(UUID id, String name, long availableCopies) {
    }
}
