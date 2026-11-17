package lms.api.support;

/**
 * IP và user-agent của client (trình duyệt) gửi tới lms-api, chỉ để identity gắn nhãn thiết bị cho phiên (ADR-0017).
 * Là thông tin hiển thị, không dùng cho quyết định bảo mật nào. Thành phần nào không xác định được thì null.
 */
public record ClientInfo(String ip, String userAgent) {

    public static final ClientInfo NONE = new ClientInfo(null, null);
}
