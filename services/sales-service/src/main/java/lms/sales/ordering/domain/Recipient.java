package lms.sales.ordering.domain;

/** Người nhận của đơn online (dữ liệu cá nhân: toString không in). */
public record Recipient(String name, String phone, String address) {

    @Override
    public String toString() {
        return "Recipient[***]";
    }
}
