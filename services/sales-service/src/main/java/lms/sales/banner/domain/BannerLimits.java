package lms.sales.banner.domain;

/** Giới hạn banner (ADR-0019 2.5). */
public final class BannerLimits {

    /** Tối đa banner mỗi tenant, mọi trạng thái. */
    public static final int MAX_PER_TENANT = 20;
    /** Tối đa banner đang hiệu lực trả cho khách. */
    public static final int MAX_PUBLIC = 10;
    public static final int MAX_SORT_ORDER = 999;

    private BannerLimits() {
    }

    /** Đã đủ số banner thì không được tạo thêm. */
    public static boolean canCreate(long currentCount) {
        return currentCount < MAX_PER_TENANT;
    }
}
