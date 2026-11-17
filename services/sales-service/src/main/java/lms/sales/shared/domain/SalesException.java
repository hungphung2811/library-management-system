package lms.sales.shared.domain;

import java.util.Map;
import java.util.UUID;

/** Lỗi nghiệp vụ có mã. Chi tiết (details) chỉ chứa id/số, không chứa dữ liệu cá nhân. */
public class SalesException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public SalesException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public SalesException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static SalesException insufficientStock(UUID titleId, long available) {
        return new SalesException(ErrorCode.INSUFFICIENT_STOCK, "Không đủ tồn kho",
                Map.of("titleId", titleId.toString(), "available", available));
    }

    public static SalesException invalidQuantity(String message) {
        return new SalesException(ErrorCode.INVALID_QUANTITY, message);
    }

    public static SalesException invalidPrice(String message) {
        return new SalesException(ErrorCode.INVALID_PRICE, message);
    }

    public static SalesException invalidRequest(String message) {
        return new SalesException(ErrorCode.INVALID_REQUEST, message);
    }

    public static SalesException reasonRequired() {
        return new SalesException(ErrorCode.REASON_REQUIRED, "Bắt buộc nhập lý do");
    }

    public static SalesException notRegistered(UUID titleId) {
        return new SalesException(ErrorCode.STOCK_ITEM_NOT_REGISTERED, "Đầu sách chưa được đưa vào bán",
                Map.of("titleId", titleId.toString()));
    }

    public static SalesException titleNotAvailable(UUID titleId) {
        return new SalesException(ErrorCode.TITLE_NOT_AVAILABLE, "Đầu sách không có sẵn",
                Map.of("titleId", titleId.toString()));
    }
}
