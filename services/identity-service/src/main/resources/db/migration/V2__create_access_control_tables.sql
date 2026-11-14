CREATE TABLE permissions (
    code        VARCHAR(100) PRIMARY KEY,
    description VARCHAR(255) NOT NULL
);

CREATE TABLE roles (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL
);

CREATE TABLE role_permissions (
    role_id         UUID         NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_code VARCHAR(100) NOT NULL REFERENCES permissions (code),
    PRIMARY KEY (role_id, permission_code)
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL REFERENCES roles (id),
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_user_id ON user_roles (user_id);
