package lms.demo.runner;

/**
 * Một bước seed của một service, đặt ở {@code infrastructure/demo} của context sở hữu bảng. Phải idempotent: chỉ chèn
 * phần chưa có (khóa tất định từ {@code DemoIds} hoặc khóa tự nhiên), không cập nhật phần đã có.
 * Bước chạy trong {@code TenantContext.callAs(tenantId)} khi {@link DemoSeedContext#tenantId()} khác null.
 */
public interface DemoSeedStep {

    /** Thứ tự chạy trong service (nhỏ chạy trước). */
    default int order() {
        return 0;
    }

    String name();

    SeedResult run(DemoSeedContext context);

    /** Số bản ghi đã tạo / đã có sẵn nên bỏ qua. */
    record SeedResult(int created, int skipped) {
        public static SeedResult of(int created, int skipped) {
            return new SeedResult(created, skipped);
        }
    }
}
