package lms.customer.storefront;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import lms.customer.storefront.client.StorefrontAccessClient;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;

/**
 * Phân giải mã tenant thành storefront khả dụng + token dịch vụ, có đệm (ADR-0011 2.2, 2.5).
 *
 * <ul>
 *   <li>Khóa là code đã chuẩn hóa (trim, chữ thường, chỉ nhận [a-zA-Z0-9-]); sai định dạng thì 404 ngay, không gọi identity, không vào bộ đệm.</li>
 *   <li>Kết quả dương giữ đến khi token còn dưới {@code refreshMargin}; kết quả phủ định giữ {@code negativeTtl}.</li>
 *   <li>Nhiều request trượt cùng mã gộp thành một lời gọi (Cache.get với loader). Lỗi identity không bị đệm.</li>
 *   <li>Bộ đệm có giới hạn kích thước nên quét mã ngẫu nhiên không làm phình bộ nhớ.</li>
 *   <li>Kết quả phủ định nằm ở bộ đệm riêng, nhỏ ({@link #MAX_NEGATIVE_SIZE} mục): spam mã ngẫu nhiên chỉ đẩy nhau ra,
 *       không bao giờ đẩy tenant hợp lệ (kết quả dương) khỏi bộ đệm dương.</li>
 * </ul>
 */
public class StorefrontAccessCache {

    /** Chỉ ASCII: tránh hạ chữ theo Unicode biến ký tự lạ (Kelvin U+212A, İ...) thành ASCII rồi lọt qua. */
    private static final Pattern CODE = Pattern.compile("^[a-zA-Z0-9-]{2,50}$");
    /** Trần expiresIn nhận từ identity (24 giờ): chặn giá trị phi lý làm tràn số hoặc đệm vô hạn. */
    static final long MAX_EXPIRES_IN_SECONDS = Duration.ofHours(24).toSeconds();

    private final StorefrontAccessClient client;
    /** Trần bộ đệm phủ định: đủ chứa các mã sai đang lặp lại, nhỏ để spam không tốn bộ nhớ. */
    static final int MAX_NEGATIVE_SIZE = 1_000;

    private final Cache<String, StorefrontAccess> cache;
    private final Cache<String, Boolean> negative;

    /** Không giới hạn TTL theo origin (dùng khi không cần hiệu lực nhanh của danh sách origin). */
    private static final Duration NO_ORIGIN_CAP = Duration.ofDays(3650);

    public StorefrontAccessCache(StorefrontAccessClient client, int maxSize, Duration negativeTtl, Duration refreshMargin) {
        this(client, maxSize, negativeTtl, refreshMargin, Ticker.systemTicker());
    }

    public StorefrontAccessCache(StorefrontAccessClient client, int maxSize, Duration negativeTtl,
                                 Duration refreshMargin, Ticker ticker) {
        this(client, maxSize, negativeTtl, refreshMargin, NO_ORIGIN_CAP, ticker);
    }

    /**
     * @param originTtl trần TTL của mục dương (ADR-0013 2.3): danh sách origin của tenant đổi thì hiệu lực sau tối đa
     *                  khoảng này; TTL mục = min(token - lề, originTtl).
     */
    public StorefrontAccessCache(StorefrontAccessClient client, int maxSize, Duration negativeTtl,
                                 Duration refreshMargin, Duration originTtl, Ticker ticker) {
        this.client = client;
        this.cache = Caffeine.newBuilder().maximumSize(maxSize).recordStats().ticker(ticker)
                .expireAfter(new Expiry<String, StorefrontAccess>() {
                    @Override
                    public long expireAfterCreate(String key, StorefrontAccess access, long currentTime) {
                        return Math.min(positiveTtlNanos(access, refreshMargin),
                                originTtl == null ? Long.MAX_VALUE : originTtl.toNanos());
                    }

                    @Override
                    public long expireAfterUpdate(String key, StorefrontAccess access, long currentTime,
                                                  long currentDuration) {
                        return expireAfterCreate(key, access, currentTime);
                    }

                    @Override
                    public long expireAfterRead(String key, StorefrontAccess access, long currentTime,
                                                long currentDuration) {
                        return currentDuration;
                    }
                }).build();
        this.negative = Caffeine.newBuilder().maximumSize(Math.max(1, Math.min(maxSize, MAX_NEGATIVE_SIZE)))
                .expireAfterWrite(negativeTtl).recordStats().ticker(ticker).build();
    }

    private static long positiveTtlNanos(StorefrontAccess access, Duration margin) {
        long seconds = Math.min(Math.max(access.expiresIn(), 0), MAX_EXPIRES_IN_SECONDS);
        Duration lifetime = Duration.ofSeconds(seconds);
        // Lề làm mới tối đa một nửa đời token: token ngắn hơn lề vẫn được đệm một khoảng thay vì TTL 0.
        Duration effectiveMargin = margin.compareTo(lifetime.dividedBy(2)) > 0 ? lifetime.dividedBy(2) : margin;
        return Math.max(0, lifetime.minus(effectiveMargin).toNanos());
    }

    /** Đăng ký metric Caffeine (cache.gets, cache.size, cache.evictions...) với tag cache=customer.storefront.access. */
    public void bindMetrics(MeterRegistry registry) {
        CaffeineCacheMetrics.monitor(registry, cache, "customer.storefront.access");
        CaffeineCacheMetrics.monitor(registry, negative, "customer.storefront.access.negative");
    }

    /** Chuẩn hóa code; rỗng nếu sai định dạng. */
    public static Optional<String> normalize(String rawCode) {
        if (rawCode == null) {
            return Optional.empty();
        }
        String trimmed = rawCode.trim();
        // Kiểm ASCII TRƯỚC khi hạ chữ; lúc này toLowerCase chỉ đổi A-Z.
        return CODE.matcher(trimmed).matches() ? Optional.of(trimmed.toLowerCase(Locale.ROOT)) : Optional.empty();
    }

    /**
     * Như {@link #resolve} nhưng không ném: mã sai định dạng, storefront không khả dụng hoặc identity lỗi đều cho rỗng.
     * Dùng cho quyết định CORS (fail-closed); controller tự gọi {@link #resolve} để trả 404/503 đúng.
     */
    public Optional<StorefrontAccess> tryResolve(String rawCode) {
        try {
            return Optional.of(resolve(rawCode));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** @throws PublicApiException 404 SHOP_NOT_AVAILABLE (một mã chung), 503 khi identity lỗi. */
    public StorefrontAccess resolve(String rawCode) {
        String code = normalize(rawCode).orElseThrow(PublicApiException::shopNotAvailable);
        if (negative.getIfPresent(code) != null) {
            throw PublicApiException.shopNotAvailable();
        }
        // Loader trả null thì Caffeine không lưu gì; các request đang chờ cùng mã vào loader lần lượt và dừng ở lần kiểm
        // bộ đệm phủ định bên dưới, nên vẫn chỉ một lời gọi identity cho cả đợt (chống bão).
        StorefrontAccess access = cache.get(code, key -> {
            if (negative.getIfPresent(key) != null) {
                return null;
            }
            Optional<StorefrontAccess> issued = client.issue(key);
            if (issued.isEmpty()) {
                negative.put(key, Boolean.TRUE);
                return null;
            }
            return issued.get();
        });
        if (access == null) {
            throw PublicApiException.shopNotAvailable();
        }
        return access;
    }
}
