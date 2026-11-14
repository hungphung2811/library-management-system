-- UC06/UC12: hạn mức mượn theo loại độc giả nằm trong CSDL để đổi mà không sửa code (QĐ1, yêu cầu "Bảo trì").
CREATE TABLE borrow_rules (
    reader_type VARCHAR(20) PRIMARY KEY CHECK (reader_type IN ('STUDENT', 'LECTURER')),
    max_items   INTEGER     NOT NULL CHECK (max_items > 0),
    loan_days   INTEGER     NOT NULL CHECK (loan_days > 0)
);

INSERT INTO borrow_rules (reader_type, max_items, loan_days) VALUES ('STUDENT', 5, 14), ('LECTURER', 10, 30);

-- Số ngày mượn của phiếu (theo loại độc giả lúc lập phiếu), dùng cho gia hạn.
ALTER TABLE borrowings ADD COLUMN loan_days INTEGER NOT NULL DEFAULT 14;

-- Tình trạng khi đóng lượt mượn: GOOD (nguyên vẹn), DAMAGED (hỏng), LOST (mất). NULL = đang mượn.
ALTER TABLE borrowing_items ADD COLUMN item_condition VARCHAR(10) CHECK (item_condition IN ('GOOD', 'DAMAGED', 'LOST'));
UPDATE borrowing_items SET item_condition = 'GOOD' WHERE return_date IS NOT NULL;
ALTER TABLE borrowing_items ADD CONSTRAINT ck_borrowing_items_closed
    CHECK ((return_date IS NULL) = (item_condition IS NULL));

-- Một bản sao không thể thuộc hai lượt mượn đang mở (yêu cầu "Tin cậy").
DROP INDEX IF EXISTS idx_borrowing_items_active;
CREATE UNIQUE INDEX uq_borrowing_items_open_copy ON borrowing_items (book_copy_id) WHERE return_date IS NULL;
