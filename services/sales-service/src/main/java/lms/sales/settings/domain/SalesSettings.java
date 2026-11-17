package lms.sales.settings.domain;

import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.Rate;
import lms.sales.shared.domain.SalesException;

/**
 * Cấu hình bán hàng theo tenant (tạo lười: chưa lưu thì dùng {@link #defaults(UUID)}, version 0).
 * Thuế suất là basis point; một thuế suất cho cả tenant.
 */
public final class SalesSettings {

    public static final int DEFAULT_VAT_BPS = 500;
    public static final int DEFAULT_RETURN_WINDOW_DAYS = 7;
    public static final int DEFAULT_LOW_STOCK_THRESHOLD = 5;
    public static final long DEFAULT_SHIPPING_FEE = 0;
    public static final int DEFAULT_RETENTION_MONTHS = 12;

    public static final int MAX_RETURN_WINDOW_DAYS = 365;
    public static final int MAX_LOW_STOCK_THRESHOLD = 100_000;
    public static final long MAX_SHIPPING_FEE = 100_000_000L;
    public static final int MIN_RETENTION_MONTHS = 1;
    public static final int MAX_RETENTION_MONTHS = 120;

    private final UUID tenantId;
    private final int vatRateBps;
    private final boolean pricesIncludeVat;
    private final int returnWindowDays;
    private final long fixedShippingFee;
    private final int lowStockThreshold;
    private final int walkInPiiRetentionMonths;
    private final long version;
    private final Instant updatedAt;
    private final UUID updatedBy;

    private SalesSettings(UUID tenantId, int vatRateBps, boolean pricesIncludeVat, int returnWindowDays,
                          long fixedShippingFee, int lowStockThreshold, int walkInPiiRetentionMonths,
                          long version, Instant updatedAt, UUID updatedBy) {
        this.tenantId = tenantId;
        this.vatRateBps = vatRateBps;
        this.pricesIncludeVat = pricesIncludeVat;
        this.returnWindowDays = returnWindowDays;
        this.fixedShippingFee = fixedShippingFee;
        this.lowStockThreshold = lowStockThreshold;
        this.walkInPiiRetentionMonths = walkInPiiRetentionMonths;
        this.version = version;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public static SalesSettings defaults(UUID tenantId) {
        return new SalesSettings(tenantId, DEFAULT_VAT_BPS, true, DEFAULT_RETURN_WINDOW_DAYS,
                DEFAULT_SHIPPING_FEE, DEFAULT_LOW_STOCK_THRESHOLD, DEFAULT_RETENTION_MONTHS, 0, null, null);
    }

    /** Dựng lại từ lưu trữ (đã validate lúc ghi), vẫn kiểm khoảng để bắt dữ liệu hỏng. */
    public static SalesSettings restore(UUID tenantId, int vatRateBps, boolean pricesIncludeVat,
                                        int returnWindowDays, long fixedShippingFee, int lowStockThreshold,
                                        int walkInPiiRetentionMonths, long version, Instant updatedAt,
                                        UUID updatedBy) {
        validate(vatRateBps, returnWindowDays, fixedShippingFee, lowStockThreshold, walkInPiiRetentionMonths);
        return new SalesSettings(tenantId, vatRateBps, pricesIncludeVat, returnWindowDays, fixedShippingFee,
                lowStockThreshold, walkInPiiRetentionMonths, version, updatedAt, updatedBy);
    }

    /** Trả bản mới (cùng version; adapter tăng version khi lưu). */
    public SalesSettings update(int vatRateBps, boolean pricesIncludeVat, int returnWindowDays,
                                long fixedShippingFee, int lowStockThreshold, int walkInPiiRetentionMonths,
                                Instant now, UUID actor) {
        validate(vatRateBps, returnWindowDays, fixedShippingFee, lowStockThreshold, walkInPiiRetentionMonths);
        return new SalesSettings(tenantId, vatRateBps, pricesIncludeVat, returnWindowDays, fixedShippingFee,
                lowStockThreshold, walkInPiiRetentionMonths, version, now, actor);
    }

    private static void validate(int vatBps, int windowDays, long shipping, int threshold, int retention) {
        require(vatBps >= 0 && vatBps <= Rate.MAX_BPS, "Thuế suất phải trong 0 đến 10000 basis point");
        require(windowDays >= 0 && windowDays <= MAX_RETURN_WINDOW_DAYS, "Số ngày trả hàng phải trong 0 đến 365");
        require(shipping >= 0 && shipping <= MAX_SHIPPING_FEE, "Phí ship phải trong 0 đến 100000000");
        require(threshold >= 0 && threshold <= MAX_LOW_STOCK_THRESHOLD, "Ngưỡng tồn thấp phải trong 0 đến 100000");
        require(retention >= MIN_RETENTION_MONTHS && retention <= MAX_RETENTION_MONTHS,
                "Thời hạn lưu giữ phải trong 1 đến 120 tháng");
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new SalesException(ErrorCode.INVALID_SETTINGS, message);
        }
    }

    public UUID tenantId() { return tenantId; }
    public int vatRateBps() { return vatRateBps; }
    public Rate vatRate() { return Rate.ofBps(vatRateBps); }
    public boolean pricesIncludeVat() { return pricesIncludeVat; }
    public int returnWindowDays() { return returnWindowDays; }
    public long fixedShippingFee() { return fixedShippingFee; }
    public Money fixedShippingMoney() { return Money.of(fixedShippingFee); }
    public int lowStockThreshold() { return lowStockThreshold; }
    public int walkInPiiRetentionMonths() { return walkInPiiRetentionMonths; }
    public long version() { return version; }
    public Instant updatedAt() { return updatedAt; }
    public UUID updatedBy() { return updatedBy; }
    public String currency() { return Money.CURRENCY; }
}
