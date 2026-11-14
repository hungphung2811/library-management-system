package lms.database.api;

/** Kết quả kiểm tra sức khỏe của một connection. */
public record HealthStatus(Status status, String detail) {

    public enum Status {
        UP,
        DOWN,
        /** Connection bị tắt bằng cấu hình: không phải lỗi. */
        DISABLED
    }

    public static HealthStatus up(String detail) {
        return new HealthStatus(Status.UP, detail);
    }

    public static HealthStatus down(String detail) {
        return new HealthStatus(Status.DOWN, detail);
    }

    public static HealthStatus disabled() {
        return new HealthStatus(Status.DISABLED, "Connection bị tắt bằng cấu hình");
    }

    public boolean isUp() {
        return status == Status.UP;
    }
}
