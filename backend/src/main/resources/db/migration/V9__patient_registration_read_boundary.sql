-- P01-T07-B2: review-only, forward-only patient registration read boundary.
-- WARNING: Do not apply to an existing Windows V8 DB without separate
-- owner-approved preflight, four-role provisioning, backup and V9 rehearsal.
-- V1..V8 checksums/DDL are immutable. No patient creation/write permissions.
--
-- The dedicated role MUST exist before Flyway begins (bootstrap is separate).
-- It cannot be created by this migration without independently provisioned
-- credentials and MUST NEVER inherit migration-owner privileges.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aronexa_patient_runtime'
        AND rolcanlogin AND NOT rolsuper AND NOT rolbypassrls
        AND NOT rolcreatedb AND NOT rolcreaterole) THEN
        RAISE EXCEPTION 'Missing or unsafe dedicated patient runtime role; stop V9 migration';
    END IF;
END
$$;

-- An independently scoped patient context, set only by server-side
-- authenticated principal in the same dedicated JDBC transaction.
-- Session variables are NOT tamper-proof against a stolen SQL login:
-- direct database compromise remains a separate threat/release blocker.
CREATE FUNCTION public.aronexa_current_patient_membership()
RETURNS uuid LANGUAGE sql STABLE
AS $$
    SELECT NULLIF(current_setting('aronexa.membership_id', true), '')::uuid
$$;
REVOKE ALL ON FUNCTION public.aronexa_current_patient_membership() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.aronexa_current_patient_membership()
    TO aronexa_patient_runtime;
GRANT EXECUTE ON FUNCTION public.aronexa_current_tenant_id()
    TO aronexa_patient_runtime;
GRANT USAGE ON SCHEMA public TO aronexa_patient_runtime;

-- Registration is a separate module, not implicitly enabled by OPD.
-- No tenant subscriptions or staff permissions are provisioned.
INSERT INTO public.module_registry
    (code,display_name,implemented,released,requires_clinical_authorization)
VALUES ('PATIENT_REGISTRATION','Patient registration',FALSE,FALSE,TRUE);
INSERT INTO public.auth_permission(code,description) VALUES
    ('PATIENT_REGISTER','Create a branch patient registration'),
    ('PATIENT_REGISTRATION_READ','Read authorized branch registration IDs'),
    ('PATIENT_CANDIDATE_SEARCH','Search authorized patient identity candidates'),
    ('PATIENT_LINK_EXISTING','Link verified existing patient to an assigned branch'),
    ('PATIENT_CONTACT_VERIFY','Verify an authorized patient contact'),
    ('PATIENT_GUARDIAN_REVIEW','Review documented guardianship evidence');
-- NO grants to auth_role_permission, no released/activated module state.

-- This role may see only *its own membership's* branch assignments.
ALTER TABLE public.org_membership_branch_scope
    ENABLE ROW LEVEL SECURITY;
CREATE POLICY patient_runtime_branch_membership_scope
    ON public.org_membership_branch_scope
    FOR SELECT TO aronexa_patient_runtime
    USING (tenant_id = public.aronexa_current_tenant_id()
           AND membership_id = public.aronexa_current_patient_membership());
GRANT SELECT ON public.org_membership_branch_scope TO aronexa_patient_runtime;

-- Reuse existing module rules on the SAME patient datasource/transaction.
-- Patient account cannot mutate module catalog or tenant entitlements.
ALTER TABLE public.tenant_module_state ENABLE ROW LEVEL SECURITY;
CREATE POLICY patient_runtime_entitlement_scope
    ON public.tenant_module_state FOR SELECT TO aronexa_patient_runtime
    USING (tenant_id = public.aronexa_current_tenant_id());
GRANT SELECT ON public.module_registry, public.tenant_module_state
    TO aronexa_patient_runtime;

-- Restrict even direct patient-role reads to one assigned branch of the
-- membership bound to this transaction, not merely the correct tenant.
ALTER TABLE public.patient_registration ENABLE ROW LEVEL SECURITY;
CREATE POLICY patient_runtime_registration_read
    ON public.patient_registration FOR SELECT TO aronexa_patient_runtime
    USING (
        tenant_id = public.aronexa_current_tenant_id()
        AND EXISTS (
            SELECT 1 FROM public.org_membership_branch_scope s
             WHERE s.tenant_id = patient_registration.tenant_id
               AND s.branch_id = patient_registration.branch_id
               AND s.membership_id = public.aronexa_current_patient_membership()
        )
    );
GRANT SELECT ON public.patient_registration TO aronexa_patient_runtime;
-- No GRANT on patient, patient_identifier, patient_contact,
-- patient_relationship or patient_identity_audit.
-- No INSERT/UPDATE/DELETE on patient_registration.

-- Explicit staff actor audit of successful registration-ID reads.
-- New immutable table; preserve V8 identity trigger audit unchanged.
ALTER TABLE public.patient_registration
    ADD CONSTRAINT uq_registration_tenant_branch_id
    UNIQUE (tenant_id, branch_id, id);
CREATE TABLE public.patient_registration_access_audit (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    registration_id UUID NOT NULL,
    action VARCHAR(12) NOT NULL CHECK (action = 'READ'),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_patient_access_actor FOREIGN KEY (tenant_id,membership_id)
        REFERENCES public.user_tenant_membership(tenant_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_patient_access_registration
        FOREIGN KEY (tenant_id,branch_id,registration_id)
        REFERENCES public.patient_registration(tenant_id,branch_id,id)
        ON DELETE RESTRICT
);
CREATE INDEX ix_patient_registration_access_audit_subject
    ON public.patient_registration_access_audit(tenant_id,registration_id,occurred_at);
CREATE TRIGGER patient_registration_access_audit_immutable
    BEFORE UPDATE OR DELETE ON public.patient_registration_access_audit
    FOR EACH ROW EXECUTE FUNCTION public.reject_patient_audit_mutation();

ALTER TABLE public.patient_registration_access_audit
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.patient_registration_access_audit
    FORCE ROW LEVEL SECURITY;
CREATE POLICY patient_runtime_registration_access_log
    ON public.patient_registration_access_audit
    FOR INSERT TO aronexa_patient_runtime
    WITH CHECK (
        tenant_id = public.aronexa_current_tenant_id()
        AND membership_id = public.aronexa_current_patient_membership()
        AND EXISTS (
            SELECT 1 FROM public.org_membership_branch_scope s
             WHERE s.tenant_id = patient_registration_access_audit.tenant_id
               AND s.membership_id = patient_registration_access_audit.membership_id
               AND s.branch_id = patient_registration_access_audit.branch_id
        )
    );
GRANT INSERT ON public.patient_registration_access_audit TO aronexa_patient_runtime;
-- No SELECT, UPDATE, DELETE, TRUNCATE on this audit table to runtime.
-- Existing app/auth runtime roles receive NO patient privileges.

-- Patient contact storage remains plaintext in V8. DO NOT insert real
-- contacts until a separately reviewed encryption and retention design.
