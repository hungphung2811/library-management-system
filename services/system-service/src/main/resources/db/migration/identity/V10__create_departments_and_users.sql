-- Phòng ban (cây tổ chức) và người dùng. users.id trùng credentials.user_id (credential tạo riêng bằng POST /api/auth/credentials).
CREATE TABLE departments (
    id         UUID                     PRIMARY KEY,
    code       VARCHAR(50)              NOT NULL UNIQUE,
    name       VARCHAR(255)             NOT NULL,
    parent_id  UUID                     REFERENCES departments (id),
    status     VARCHAR(20)              NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_departments_parent ON departments (parent_id);

CREATE TABLE users (
    id            UUID                     PRIMARY KEY,
    username      VARCHAR(100)             NOT NULL UNIQUE,
    full_name     VARCHAR(255)             NOT NULL,
    email         VARCHAR(255)             NOT NULL,
    department_id UUID                     REFERENCES departments (id),
    status        VARCHAR(20)              NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_users_email ON users (lower(email));
CREATE INDEX idx_users_department ON users (department_id);
CREATE INDEX idx_users_full_name ON users (lower(full_name));
