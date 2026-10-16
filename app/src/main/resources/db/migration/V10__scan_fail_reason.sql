-- Slice-0 hardening: a short machine code explaining why a scan is in status 'failed' (e.g. HOST_NOT_ALLOWED,
-- SCANNER_IMAGE_MISSING, DOCKER_ERROR, WORK_ROOT_NOT_WRITABLE, SCANNER_TIMEOUT, NO_ACCESSIBLE_CONNECTION), so a
-- permanent failure is auditable and the M8 UI can explain it. Null for non-failed scans. Additive, nullable
-- (V1-V9 are immutable once applied; new behaviour is always a new migration).
alter table scan add column fail_reason text;
