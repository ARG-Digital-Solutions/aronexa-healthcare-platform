-- P01-T06: forward-only module gating foundation. No module is made usable by this migration.
-- Tenant assignments are owner-provisioned only; runtime identities are SELECT-only.
-- All gates fail closed in the absence of an explicit, appropriately authorized row.

CREATE TABLE public.module_registry (
    code VARCHAR(48) PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    implemented BOOLEAN NOT NULL DEFAULT FALSE,
    released BOOLEAN NOT NULL DEFAULT FALSE,
    requires_clinical_authorization BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_module_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,47}$'),
    CONSTRAINT ck_module_display CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT ck_module_release CHECK (NOT released OR implemented)
);

-- The catalog is discoverable internally, but every module is initially
-- unimplemented and unreleased. No default grants or tenant activation.
INSERT INTO public.module_registry
    (code, display_name, requires_clinical_authorization)
VALUES
    ('OPD','Outpatient care',TRUE),
    ('IPD','Inpatient care',TRUE),
    ('PHARMACY','Pharmacy',TRUE),
    ('BILLING','Billing',FALSE),
    ('LAB','Laboratory',TRUE),
    ('IUI','Intrauterine insemination',TRUE),
    ('IVF','In vitro fertilization',TRUE);

CREATE TABLE public.tenant_module_state (
    tenant_id UUID NOT NULL REFERENCES public.tenant(id) ON DELETE RESTRICT,
    module_code VARCHAR(48) NOT NULL REFERENCES public.module_registry(code) ON DELETE RESTRICT,
    entitled BOOLEAN NOT NULL DEFAULT FALSE,
    activated BOOLEAN NOT NULL DEFAULT FALSE,
    clinically_authorized BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, module_code)
);

-- Change history for controlled provisioning/revocation and platform releases.
-- Values contain configuration flags only, no patient or credential data.
CREATE TABLE public.module_configuration_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id UUID,
    module_code VARCHAR(48) NOT NULL,
    source_table VARCHAR(40) NOT NULL
        CHECK (source_table IN ('module_registry', 'tenant_module_state')),
    operation VARCHAR(6) NOT NULL CHECK (operation IN ('INSERT','UPDATE','DELETE')),
    old_state JSONB,
    new_state JSONB,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE FUNCTION public.audit_module_configuration()
RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    row_before JSONB;
    row_after JSONB;
    row_effective JSONB;
BEGIN
    row_before := CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE to_jsonb(OLD) END;
    row_after := CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE to_jsonb(NEW) END;
    row_effective := COALESCE(row_after, row_before);
    INSERT INTO public.module_configuration_audit
        (tenant_id, module_code, source_table, operation, old_state, new_state)
    VALUES
        (CASE WHEN TG_TABLE_NAME = 'module_registry' THEN NULL
              ELSE (row_effective ->> 'tenant_id')::uuid END,
         CASE WHEN TG_TABLE_NAME = 'module_registry'
              THEN row_effective ->> 'code'
              ELSE row_effective ->> 'module_code' END,
         TG_TABLE_NAME, TG_OP, row_before, row_after);
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER module_registry_audit_change
AFTER INSERT OR UPDATE OR DELETE ON public.module_registry
FOR EACH ROW EXECUTE FUNCTION public.audit_module_configuration();
CREATE TRIGGER tenant_module_state_audit_change
AFTER INSERT OR UPDATE OR DELETE ON public.tenant_module_state
FOR EACH ROW EXECUTE FUNCTION public.audit_module_configuration();

CREATE FUNCTION public.deny_module_audit_mutation()
RETURNS TRIGGER LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Module configuration audit is append-only';
END;
$$;
CREATE TRIGGER module_audit_immutable
BEFORE UPDATE OR DELETE ON public.module_configuration_audit
FOR EACH ROW EXECUTE FUNCTION public.deny_module_audit_mutation();

-- Tenant state always obeys verified, transaction-local tenant context.
ALTER TABLE public.tenant_module_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tenant_module_state FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_module_state_runtime_read ON public.tenant_module_state
    FOR SELECT TO aronexa_app_runtime
    USING (tenant_id = public.aronexa_current_tenant_id());

GRANT SELECT ON public.module_registry, public.tenant_module_state
    TO aronexa_app_runtime;
-- No INSERT/UPDATE/DELETE on registry, state or audit to either runtime role.
-- Auth runtime has NO SELECT access to module, entitlement or audit tables.
-- No runtime/granted admin API or trial subscription billing in P01-T06.
