CREATE TABLE audit_logs (
    id          UUID                     PRIMARY KEY,
    event_id    UUID                     NOT NULL UNIQUE,
    event_type  VARCHAR(100)             NOT NULL,
    aggregate_id VARCHAR(100)            NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload     TEXT                     NOT NULL
);

CREATE INDEX idx_audit_logs_aggregate ON audit_logs (aggregate_id, occurred_at DESC);
CREATE INDEX idx_audit_logs_event_type ON audit_logs (event_type, occurred_at DESC);
