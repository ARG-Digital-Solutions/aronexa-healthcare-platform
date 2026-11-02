-- P01-T02: nonclinical bootstrap marker only.
-- Immutable versioned migration; do not edit once applied in any environment.
-- Real tenant, patient, facility and clinical tables begin in separately approved tasks.
CREATE TABLE platform_schema_metadata (
    code VARCHAR(64) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
