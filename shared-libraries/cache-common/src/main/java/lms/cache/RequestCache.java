package lms.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Wrapper cache dữ liệu theo request (cache-aside) lưu trong Redis.
 *
 * <pre>{@code
 * List<BookView> books = cache.get("books.search", new SearchKey(keyword, page), Duration.ofSeconds(30),
 *         new TypeReference<List<BookView>>() { }, () -> searchBooks.execute(keyword, page));
 * }</pre>
 *
 * <p>Quy tắc dùng:
 * <ul>
 *   <li>{@code key} phải gồm MỌI đầu vào làm đổi kết quả (từ khóa, trang, bộ lọc, và cả userId nếu kết quả theo người
 *   dùng). Dùng record hoặc List để {@code toString()} ổn định.</li>
 *   <li>{@link #get} luôn gắn khóa với tenant của request ({@code TenantContext}); không có tenant hoặc đang ở phạm vi đọc
 *   mọi tenant (platform admin) thì KHÔNG cache, chạy thẳng loader. Dữ liệu của nền tảng không theo tenant dùng
 *   {@link #getGlobal}.</li>
 *   <li>Lỗi Redis không làm hỏng request: coi như trượt cache và chạy loader. Lỗi của loader thì ném nguyên, không
 *   ghi cache. Kết quả {@code null} không được cache.</li>
 *   <li>Sau khi ghi dữ liệu nguồn, gọi {@link #evict} hoặc {@link #evictNamespace} (hoãn tới sau commit nếu đang trong
 *   giao dịch); nếu không có gọi thì dữ liệu cũ tồn tại tối đa bằng {@code ttl}.</li>
 * </ul>
 */
public interface RequestCache {

    /** Cache dữ liệu theo tenant hiện tại. {@code ttl} rỗng hoặc không dương thì không cache. */
    <T> T get(String namespace, Object key, Duration ttl, TypeReference<T> type, Supplier<T> loader);

    default <T> T get(String namespace, Object key, Duration ttl, Class<T> type, Supplier<T> loader) {
        return get(namespace, key, ttl, typeOf(type), loader);
    }

    /** Cache dữ liệu dùng chung toàn nền tảng, không theo tenant (ví dụ danh mục toàn cục). Không dùng cho dữ liệu của tenant. */
    <T> T getGlobal(String namespace, Object key, Duration ttl, TypeReference<T> type, Supplier<T> loader);

    default <T> T getGlobal(String namespace, Object key, Duration ttl, Class<T> type, Supplier<T> loader) {
        return getGlobal(namespace, key, ttl, typeOf(type), loader);
    }

    /** Xóa một mục của tenant hiện tại. Đang trong giao dịch thì xóa sau commit. */
    void evict(String namespace, Object key);

    /** Xóa mọi mục thuộc namespace của tenant hiện tại. Đang trong giao dịch thì xóa sau commit. */
    void evictNamespace(String namespace);

    /** Xóa một mục dữ liệu toàn nền tảng (cache của {@link #getGlobal}). Dùng được khi không có tenant (platform admin). */
    default void evictGlobal(String namespace, Object key) {
    }

    /** Xóa mọi mục toàn nền tảng thuộc namespace (cache của {@link #getGlobal}). Đang trong giao dịch thì xóa sau commit. */
    default void evictGlobalNamespace(String namespace) {
    }

    /** false khi cache bị tắt (no-op). */
    boolean isEnabled();

    private static <T> TypeReference<T> typeOf(Class<T> type) {
        return new TypeReference<>() {
            @Override
            public Type getType() {
                return type;
            }
        };
    }
}
