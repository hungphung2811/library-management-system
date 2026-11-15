package lms.api.catalog.dto;

/** Trạng thái bản sao theo hợp đồng FE (khớp enum của catalog-service). */
public enum CopyStatus {
    AVAILABLE, LOANED, RESERVED, LOST, DAMAGED
}
