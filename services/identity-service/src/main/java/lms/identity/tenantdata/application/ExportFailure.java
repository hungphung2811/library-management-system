package lms.identity.tenantdata.application;

/** Lỗi dừng cả yêu cầu xuất, không thử lại (ví dụ SIZE_LIMIT). {@code code} lưu vào error_code, không có thông điệp thô. */
public class ExportFailure extends RuntimeException {

    private final String code;

    public ExportFailure(String code) {
        super(code, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
