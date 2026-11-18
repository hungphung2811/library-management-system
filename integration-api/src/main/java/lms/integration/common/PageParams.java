package lms.integration.common;

import lms.integration.web.IntegrationApiException;
import org.springframework.http.HttpStatus;

/** Kiểm tham số phân trang v1: page &gt;= 0 (mặc định 0), size 1..100 (mặc định 20); ngoài khoảng là 400, không âm thầm cắt. */
public final class PageParams {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private PageParams() {
    }

    public static int page(Integer page) {
        if (page == null) return 0;
        if (page < 0) throw invalid("page phải >= 0");
        return page;
    }

    public static int size(Integer size) {
        if (size == null) return DEFAULT_SIZE;
        if (size < 1 || size > MAX_SIZE) throw invalid("size phải từ 1 đến " + MAX_SIZE);
        return size;
    }

    static IntegrationApiException invalid(String message) {
        return new IntegrationApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }
}
