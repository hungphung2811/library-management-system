package lms.circulation.reservation.application;

import java.util.List;
import java.util.UUID;

/** Cổng lấy danh sách tenant đang ACTIVE để việc nền chạy lần lượt từng tenant. */
public interface ActiveTenantsPort {

    List<UUID> activeTenantIds();
}
