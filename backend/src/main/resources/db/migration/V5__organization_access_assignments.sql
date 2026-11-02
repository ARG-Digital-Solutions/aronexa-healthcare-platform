-- P01-T05-C: tenant-bound branch/department scope assignments.
-- Migration owner provisions assignments; runtime may ONLY SELECT.
-- No patient, doctor, encounter, clinical or public API tables are introduced.
-- Additive V5: V1..V4 remain immutable.
ALTER TABLE department ADD CONSTRAINT uq_department_scope_ref
    UNIQUE (tenant_id, branch_id, id);

CREATE TABLE org_membership_branch_scope (
    tenant_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, membership_id, branch_id),
    CONSTRAINT fk_org_branch_scope_membership FOREIGN KEY (tenant_id, membership_id)
        REFERENCES user_tenant_membership(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_org_branch_scope_branch FOREIGN KEY (tenant_id, branch_id)
        REFERENCES branch(tenant_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_org_branch_scope_branch
    ON org_membership_branch_scope(tenant_id, branch_id);

CREATE TABLE org_membership_department_scope (
    tenant_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    department_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, membership_id, branch_id, department_id),
    CONSTRAINT fk_org_department_scope_branch_assignment
        FOREIGN KEY (tenant_id, membership_id, branch_id)
        REFERENCES org_membership_branch_scope(tenant_id, membership_id, branch_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_org_department_scope_department
        FOREIGN KEY (tenant_id, branch_id, department_id)
        REFERENCES department(tenant_id, branch_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_org_department_scope_department
    ON org_membership_department_scope(tenant_id, branch_id, department_id);

ALTER TABLE org_membership_branch_scope ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_membership_branch_scope FORCE ROW LEVEL SECURITY;
CREATE POLICY org_branch_scope_runtime ON org_membership_branch_scope
    FOR SELECT TO aronexa_app_runtime
    USING (tenant_id = public.aronexa_current_tenant_id());

ALTER TABLE org_membership_department_scope ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_membership_department_scope FORCE ROW LEVEL SECURITY;
CREATE POLICY org_department_scope_runtime ON org_membership_department_scope
    FOR SELECT TO aronexa_app_runtime
    USING (tenant_id = public.aronexa_current_tenant_id());

GRANT SELECT ON org_membership_branch_scope,
    org_membership_department_scope TO aronexa_app_runtime;

-- No INSERT/UPDATE/DELETE grants to runtime; provisioning requires
-- a separately authorized and audited administrative workflow (future task).
