-- Đặt trước theo hàng đợi FIFO (QĐ5-QĐ7). Một độc giả chỉ có một đặt trước còn hiệu lực cho mỗi đầu sách.
CREATE TABLE reservations (
    id          UUID        PRIMARY KEY,
    reader_id   UUID        NOT NULL,
    title_id    UUID        NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL,
    status      VARCHAR(20) NOT NULL CHECK (status IN ('WAITING', 'READY', 'FULFILLED', 'EXPIRED', 'CANCELLED'))
);

CREATE UNIQUE INDEX uq_reservations_active ON reservations (reader_id, title_id) WHERE status IN ('WAITING', 'READY');
CREATE INDEX idx_reservations_queue ON reservations (title_id, reserved_at, id) WHERE status = 'WAITING';
CREATE INDEX idx_reservations_reader ON reservations (reader_id, reserved_at DESC);
