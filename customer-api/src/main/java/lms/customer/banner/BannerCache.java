package lms.customer.banner;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lms.customer.banner.dto.BannerDtos.BannerView;

/**
 * Đệm banner theo tenant (ADR-0019 2.6): giống hệt cho mọi khách của tenant nên một lời gọi sales phục vụ cả cửa sổ
 * TTL (mặc định 30 giây, tối đa 10 000 tenant). Đệm cả danh sách rỗng; lỗi (ngoại lệ của loader) không bị đệm.
 */
public class BannerCache {

    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);
    public static final int DEFAULT_MAX_SIZE = 10_000;

    private final Cache<UUID, List<BannerView>> cache;

    public BannerCache() {
        this(DEFAULT_TTL, DEFAULT_MAX_SIZE, Ticker.systemTicker());
    }

    public BannerCache(Duration ttl, int maxSize, Ticker ticker) {
        this.cache = Caffeine.newBuilder().maximumSize(maxSize).expireAfterWrite(ttl).ticker(ticker).build();
    }

    public List<BannerView> get(UUID tenantId, Supplier<List<BannerView>> loader) {
        return cache.get(tenantId, key -> List.copyOf(loader.get()));
    }
}
