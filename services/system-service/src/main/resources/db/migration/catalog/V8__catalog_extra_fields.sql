-- Thông tin bổ sung tùy chọn (không backfill) cho bản sao, tác giả, nhà xuất bản, vị trí kệ. Chỉ thêm cột (không ghi
-- dữ liệu nên không cần app.tenant_id); RLS, policy và @TenantId của các bảng giữ nguyên. CHECK giữ ràng buộc ở DB kể cả
-- khi chèn thẳng. "Không ở tương lai" và "<= năm hiện tại" do ứng dụng kiểm (CHECK không dùng được hàm không bất biến).

-- ===== book_copies =====
ALTER TABLE book_copies
    ADD COLUMN acquired_date       DATE,
    ADD COLUMN acquisition_source  VARCHAR(20),
    ADD COLUMN purchase_price      BIGINT,
    ADD COLUMN condition_grade     VARCHAR(10),
    ADD COLUMN condition_note      VARCHAR(300),
    ADD COLUMN last_inventory_date DATE;

ALTER TABLE book_copies
    ADD CONSTRAINT ck_book_copies_acquisition_source
        CHECK (acquisition_source IS NULL OR acquisition_source IN ('PURCHASE', 'DONATION', 'EXCHANGE', 'OTHER')),
    ADD CONSTRAINT ck_book_copies_purchase_price
        CHECK (purchase_price IS NULL OR (purchase_price >= 0 AND COALESCE(acquisition_source, '') = 'PURCHASE')),
    ADD CONSTRAINT ck_book_copies_condition_grade
        CHECK (condition_grade IS NULL OR condition_grade IN ('NEW', 'GOOD', 'FAIR', 'WORN')),
    ADD CONSTRAINT ck_book_copies_condition_note
        CHECK (condition_note IS NULL OR btrim(condition_note) <> ''),
    ADD CONSTRAINT ck_book_copies_inventory_after_acquired
        CHECK (acquired_date IS NULL OR last_inventory_date IS NULL OR last_inventory_date >= acquired_date);

-- ===== authors =====
ALTER TABLE authors
    ADD COLUMN birth_year  INTEGER,
    ADD COLUMN death_year  INTEGER,
    ADD COLUMN nationality VARCHAR(100),
    ADD COLUMN photo_url   VARCHAR(500),
    ADD COLUMN website     VARCHAR(500);

ALTER TABLE authors
    ADD CONSTRAINT ck_authors_birth_year CHECK (birth_year IS NULL OR birth_year BETWEEN 1 AND 3000),
    ADD CONSTRAINT ck_authors_death_year CHECK (death_year IS NULL OR death_year BETWEEN 1 AND 3000),
    ADD CONSTRAINT ck_authors_death_after_birth
        CHECK (birth_year IS NULL OR death_year IS NULL OR death_year >= birth_year),
    ADD CONSTRAINT ck_authors_nationality CHECK (nationality IS NULL OR btrim(nationality) <> ''),
    ADD CONSTRAINT ck_authors_photo_url CHECK (photo_url IS NULL OR photo_url ~* '^https?://'),
    ADD CONSTRAINT ck_authors_website CHECK (website IS NULL OR website ~* '^https?://');

-- ===== publishers =====
ALTER TABLE publishers
    ADD COLUMN country      VARCHAR(100),
    ADD COLUMN founded_year INTEGER,
    ADD COLUMN website      VARCHAR(500),
    ADD COLUMN address      VARCHAR(500);

ALTER TABLE publishers
    ADD CONSTRAINT ck_publishers_founded_year CHECK (founded_year IS NULL OR founded_year BETWEEN 1400 AND 3000),
    ADD CONSTRAINT ck_publishers_country CHECK (country IS NULL OR btrim(country) <> ''),
    ADD CONSTRAINT ck_publishers_website CHECK (website IS NULL OR website ~* '^https?://'),
    ADD CONSTRAINT ck_publishers_address CHECK (address IS NULL OR btrim(address) <> '');

-- ===== shelf_locations =====
ALTER TABLE shelf_locations
    ADD COLUMN floor    VARCHAR(20),
    ADD COLUMN zone     VARCHAR(50),
    ADD COLUMN capacity INTEGER;

ALTER TABLE shelf_locations
    ADD CONSTRAINT ck_shelf_locations_floor CHECK (floor IS NULL OR btrim(floor) <> ''),
    ADD CONSTRAINT ck_shelf_locations_zone CHECK (zone IS NULL OR btrim(zone) <> ''),
    ADD CONSTRAINT ck_shelf_locations_capacity CHECK (capacity IS NULL OR capacity BETWEEN 1 AND 100000);
