-- Existing deployed admin-panel schema; deliberately idempotent for baseline 0.
CREATE TABLE IF NOT EXISTS ci_token_policy (
    caller_repository VARCHAR(255) PRIMARY KEY,
    allowed_owner VARCHAR(255) NOT NULL,
    allowed_repositories TEXT NOT NULL,
    allowed_permissions TEXT NOT NULL
);

-- Alertmanager watchdog dead-man-switch ping, single row with id = 1.
CREATE TABLE IF NOT EXISTS watchdog_ping (
    id INT PRIMARY KEY,
    last_ping_at TIMESTAMP NOT NULL
);
