package lms.support.notification.domain;

/** Kênh gửi ra ngoài. IN_APP không đi qua provider (ghi thẳng bảng notifications). Giai đoạn này chỉ có EMAIL. */
public enum NotificationChannel {
    EMAIL
}
