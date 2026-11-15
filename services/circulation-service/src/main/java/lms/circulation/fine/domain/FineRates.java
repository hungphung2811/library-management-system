package lms.circulation.fine.domain;

import java.math.BigDecimal;

/** Mức phạt lấy từ file cấu hình (yêu cầu "Bảo trì"): QĐ3 phí trễ mỗi cuốn mỗi ngày, QĐ4 tỉ lệ hỏng và mất theo giá bìa. */
public record FineRates(BigDecimal lateFeePerDay, BigDecimal damagedRate, BigDecimal lostRate) {

    public FineRates {
        if (lateFeePerDay == null || lateFeePerDay.signum() < 0) throw new InvalidFineException("Phí trễ mỗi ngày phải >= 0");
        if (damagedRate == null || damagedRate.signum() < 0) throw new InvalidFineException("Tỉ lệ phạt hỏng phải >= 0");
        if (lostRate == null || lostRate.signum() < 0) throw new InvalidFineException("Tỉ lệ bồi thường mất phải >= 0");
    }

    /** Mặc định theo tài liệu yêu cầu: 5.000đ/cuốn/ngày, hỏng 50%, mất 150% giá bìa. */
    public static FineRates standard() {
        return new FineRates(new BigDecimal("5000"), new BigDecimal("0.5"), new BigDecimal("1.5"));
    }
}
