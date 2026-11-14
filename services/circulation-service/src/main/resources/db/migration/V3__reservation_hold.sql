-- Giữ bản sao cho người đầu hàng đợi (QĐ6, QĐ9): READY kèm bản sao được giữ và hạn giữ.
ALTER TABLE reservations ADD COLUMN copy_id UUID;
ALTER TABLE reservations ADD COLUMN hold_until DATE;

ALTER TABLE reservations ADD CONSTRAINT ck_reservations_ready_hold
    CHECK (status <> 'READY' OR (copy_id IS NOT NULL AND hold_until IS NOT NULL));

-- Một bản sao chỉ được giữ cho một đặt trước tại một thời điểm.
CREATE UNIQUE INDEX uq_reservations_ready_copy ON reservations (copy_id) WHERE status = 'READY';
CREATE INDEX idx_reservations_hold_expiry ON reservations (hold_until) WHERE status = 'READY';
