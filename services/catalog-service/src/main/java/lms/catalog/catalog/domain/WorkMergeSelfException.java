package lms.catalog.catalog.domain;

import java.util.UUID;

/** Gộp tác phẩm vào chính nó (mã lỗi WORK_MERGE_SELF). */
public class WorkMergeSelfException extends RuntimeException {
    public WorkMergeSelfException(UUID id) {
        super("Không thể gộp tác phẩm vào chính nó: " + id);
    }
}
