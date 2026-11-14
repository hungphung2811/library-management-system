CREATE TABLE borrowings (
    id          UUID    PRIMARY KEY,
    user_id     UUID    NOT NULL,
    borrow_date DATE    NOT NULL,
    due_date    DATE    NOT NULL,
    renew_count INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE borrowing_items (
    borrowing_id UUID NOT NULL REFERENCES borrowings (id) ON DELETE CASCADE,
    book_copy_id UUID NOT NULL,
    return_date  DATE
);

CREATE INDEX idx_borrowings_user_id ON borrowings (user_id);
CREATE INDEX idx_borrowing_items_borrowing_id ON borrowing_items (borrowing_id);
CREATE INDEX idx_borrowing_items_active ON borrowing_items (book_copy_id) WHERE return_date IS NULL;
