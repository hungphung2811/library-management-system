package lms.customer.web;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/** Cache-Control cho phản hồi công khai (ADR-0011 2.6): nội dung đọc được đệm ngắn, mọi thứ còn lại no-store. */
public final class PublicCache {

    private PublicCache() {
    }

    public static <T> ResponseEntity<T> publicFor(Duration maxAge, T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(maxAge).cachePublic()).body(body);
    }

    public static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
