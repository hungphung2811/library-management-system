package lms.sales.shared.domain;

/** Tỷ lệ bằng basis point (500 = 5%), 0 đến 10000. */
public record Rate(int bps) {

    public static final int MAX_BPS = 10_000;

    public Rate {
        if (bps < 0 || bps > MAX_BPS) {
            throw SalesException.invalidRequest("Tỷ lệ phải trong khoảng 0 đến 10000 basis point");
        }
    }

    public static Rate ofBps(int bps) {
        return new Rate(bps);
    }

    /** round(base * bps / 10000) HALF_UP. */
    public long percentOf(long base) {
        return Rounding.mulDivHalfUp(base, bps, MAX_BPS);
    }
}
