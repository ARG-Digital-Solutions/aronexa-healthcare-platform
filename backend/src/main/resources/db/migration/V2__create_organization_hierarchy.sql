-- P01-T03: organization hierarchy foundation only.
-- Apply forward using Flyway. Never modify V1 or an applied versioned migration.
-- No authentication, patient data, role tables, RLS policies, or clinical data here.
CREATE TABLE tenant (
    id UUID PRIMARY KEY,
    code VARCHAR(48) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_tenant_code CHECK (code ~ '^[a-z][a-z0-9_-]{1,47}$'),
    CONSTRAINT ck_tenant_name CHECK (length(btrim(display_name)) > 0)
);

CREATE TABLE facility (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE RESTRICT,
    code VARCHAR(48) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    facility_kind VARCHAR(48) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_facility_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_facility_code_in_tenant UNIQUE (tenant_id, code),
    CONSTRAINT ck_facility_code CHECK (code ~ '^[a-z][a-z0-9_-]{1,47}$'),
    CONSTRAINT ck_facility_name CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT ck_facility_kind CHECK (length(btrim(facility_kind)) > 0)
);

CREATE TABLE branch (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    facility_id UUID NOT NULL,
    code VARCHAR(48) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_branch_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_branch_code_in_facility UNIQUE (tenant_id, facility_id, code),
    CONSTRAINT fk_branch_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_branch_same_tenant_facility FOREIGN KEY (tenant_id, facility_id)
        REFERENCES facility(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_branch_code CHECK (code ~ '^[a-z][a-z0-9_-]{1,47}$'),
    CONSTRAINT ck_branch_name CHECK (length(btrim(display_name)) > 0)
);

CREATE TABLE department (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    code VARCHAR(48) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_department_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_department_code_in_branch UNIQUE (tenant_id, branch_id, code),
    CONSTRAINT fk_department_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_department_same_tenant_branch FOREIGN KEY (tenant_id, branch_id)
        REFERENCES branch(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_department_code CHECK (code ~ '^[a-z][a-z0-9_-]{1,47}$'),
    CONSTRAINT ck_department_name CHECK (length(btrim(display_name)) > 0)
);

-- Foreign key columns are also used by branch/department scoped lookups.
CREATE INDEX ix_facility_tenant ON facility(tenant_id);
CREATE INDEX ix_branch_tenant_facility ON branch(tenant_id, facility_id);
CREATE INDEX ix_department_tenant_branch ON department(tenant_id, branch_id);
