package lms.sales.banner.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.sales.banner.domain.Banner;

/** Port lưu trữ banner. Mọi truy vấn có tenantId tường minh (RLS vẫn chặn ở DB). */
public interface BannerRepository {

    Banner insert(Banner banner);

    /** Ghi đè các trường sửa được; version lệch thì ném BANNER_VERSION_CONFLICT, không có thì BANNER_NOT_FOUND. */
    Banner update(Banner banner);

    Optional<Banner> findById(UUID tenantId, UUID id);

    /** Mọi banner của tenant, theo sortOrder, createdAt, id. */
    List<Banner> findAll(UUID tenantId);

    long count(UUID tenantId);

    /** Xóa cứng; true nếu có dòng bị xóa. */
    boolean delete(UUID tenantId, UUID id);

    /** Banner đang hiệu lực tại now, theo sortOrder, createdAt, id, tối đa limit. */
    List<Banner> findLive(UUID tenantId, Instant now, int limit);
}
