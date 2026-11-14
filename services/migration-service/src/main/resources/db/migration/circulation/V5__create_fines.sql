-- Phí phạt (QĐ3, QĐ4, QĐ8), thu ở UC09. Mỗi (lượt mượn, bản sao, lý do) chỉ có một khoản nên lập phạt idempotent.
CREATE TABLE fines (
    id           UUID          PRIMARY KEY,
    reader_id    UUID          NOT NULL,
    borrowing_id UUID          NOT NULL,
    book_copy_id UUID          NOT NULL,
    reason       VARCHAR(20)   NOT NULL CHECK (reason IN ('LATE_RETURN', 'LOST', 'DAMAGED')),
    amount       NUMERIC(14,2) NOT NULL CHECK (amount >= 0),
    status       VARCHAR(10)   NOT NULL CHECK (status IN ('UNPAID', 'PAID')),
    created_at   TIMESTAMPTZ   NOT NULL,
    paid_at      TIMESTAMPTZ,
    CONSTRAINT uq_fines_item_reason UNIQUE (borrowing_id, book_copy_id, reason),
    CONSTRAINT ck_fines_paid CHECK ((status = 'PAID') = (paid_at IS NOT NULL))
);

CREATE INDEX idx_fines_reader ON fines (reader_id, created_at DESC);
CREATE INDEX idx_fines_reader_unpaid ON fines (reader_id) WHERE status = 'UNPAID';
