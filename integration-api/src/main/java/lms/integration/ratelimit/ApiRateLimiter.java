package lms.integration.ratelimit;

import java.util.Optional;

/** Giới hạn tốc độ theo (tenant, application, nhóm endpoint). Rỗng nghĩa là không áp dụng (fail-open hoặc tắt). */
public interface ApiRateLimiter {

    /** Quyết định cho một request; kèm ghi bộ đếm sử dụng theo ngày. */
    Optional<Decision> check(ApiCaller caller, String group);

    /**
     * @param limit            hạn mức mỗi phút
     * @param remaining        còn lại trong cửa sổ hiện tại (không âm)
     * @param retryAfterSeconds số giây tới cửa sổ kế (tối thiểu 1)
     */
    record Decision(boolean allowed, long limit, long remaining, long retryAfterSeconds) {
    }
}
