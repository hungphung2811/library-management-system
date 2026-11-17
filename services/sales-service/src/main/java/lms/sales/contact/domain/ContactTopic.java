package lms.sales.contact.domain;

/** Chủ đề liên hệ (enum, không văn bản tự do, để giảm dữ liệu cá nhân nhập tự do; ADR-0015 mục 2.3). */
public enum ContactTopic {
    ORDER, PRODUCT, PAYMENT, OTHER
}
