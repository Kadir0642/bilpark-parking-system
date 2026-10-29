-- V6: client-generated check-in request ids (idempotent POST /api/parking/check-in).
CREATE TABLE IF NOT EXISTS check_in_requests (
    request_id   varchar(64) PRIMARY KEY,
    plate        varchar(20) NOT NULL,
    park_spot_id bigint,
    created_at   timestamp   NOT NULL
);
