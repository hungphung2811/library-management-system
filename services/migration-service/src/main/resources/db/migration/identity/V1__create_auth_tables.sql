CREATE TABLE credentials (
    user_id         UUID                     PRIMARY KEY,
    username        VARCHAR(100)             NOT NULL UNIQUE,
    password_hash   VARCHAR(100)             NOT NULL,
    department_id   UUID,
    active          BOOLEAN                  NOT NULL,
    failed_attempts INTEGER                  NOT NULL DEFAULT 0,
    locked_until    TIMESTAMP WITH TIME ZONE
);

CREATE TABLE refresh_tokens (
    id         UUID                     PRIMARY KEY,
    user_id    UUID                     NOT NULL,
    token_hash VARCHAR(64)              NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked    BOOLEAN                  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
