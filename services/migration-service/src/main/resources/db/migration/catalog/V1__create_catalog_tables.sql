CREATE TABLE titles (
    id          UUID          PRIMARY KEY,
    name        VARCHAR(255)  NOT NULL,
    author      VARCHAR(255)  NOT NULL,
    isbn        VARCHAR(20),
    cover_price NUMERIC(12,2) NOT NULL CHECK (cover_price >= 0),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_titles_isbn ON titles (isbn) WHERE isbn IS NOT NULL;
CREATE INDEX idx_titles_name_lower ON titles (lower(name));
CREATE INDEX idx_titles_author_lower ON titles (lower(author));

CREATE TABLE book_copies (
    id         UUID         PRIMARY KEY,
    title_id   UUID         NOT NULL REFERENCES titles (id),
    barcode    VARCHAR(64)  NOT NULL UNIQUE,
    status     VARCHAR(20)  NOT NULL CHECK (status IN ('AVAILABLE','LOANED','RESERVED','LOST','DAMAGED')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_book_copies_title_status ON book_copies (title_id, status);
