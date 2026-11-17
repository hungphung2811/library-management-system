package lms.api.dashboard.dto;

/** Query top đầu sách: khoảng ngày và limit (1..50) do service kiểm. */
public record TopQuery(String from, String to, String limit) {
}
