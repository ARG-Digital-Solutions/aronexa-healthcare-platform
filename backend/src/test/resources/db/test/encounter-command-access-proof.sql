-- COMM-V1-T08: disposable Testcontainers ONLY, executed after Flyway & T07 proof.
-- Enables the T07 non-owner encounter proof login to READ CURRENT (no writes)
-- membership, branch assignment and OPD entitlement from the same transaction.
-- Never promote to src/main/resources/db/migration or to customer databases.
GRANT USAGE ON SCHEMA public TO aronexa_encounter_proof;

ALTER TABLE public.user_tenant_membership ENABLE ROW LEVEL SECURITY;
CREATE POLICY encounter_proof_membership_read ON public.user_tenant_membership
    FOR SELECT TO aronexa_encounter_proof
    USING (tenant_id = NULLIF(current_setting('aronexa.tenant_id',true),'')::uuid
           AND id = NULLIF(current_setting('aronexa.membership_id',true),'')::uuid);
GRANT SELECT (id,tenant_id,user_id,enabled) ON public.user_tenant_membership
    TO aronexa_encounter_proof;

ALTER TABLE public.app_user ENABLE ROW LEVEL SECURITY;
CREATE POLICY encounter_proof_user_read ON public.app_user
    FOR SELECT TO aronexa_encounter_proof
    USING (id = NULLIF(current_setting('aronexa.user_id',true),'')::uuid);
GRANT SELECT (id,enabled,must_change_password) ON public.app_user
    TO aronexa_encounter_proof;

CREATE POLICY encounter_proof_branch_read ON public.org_membership_branch_scope
    FOR SELECT TO aronexa_encounter_proof
    USING (tenant_id = NULLIF(current_setting('aronexa.tenant_id',true),'')::uuid
           AND membership_id = NULLIF(current_setting('aronexa.membership_id',true),'')::uuid);
GRANT SELECT (tenant_id,membership_id,branch_id)
    ON public.org_membership_branch_scope TO aronexa_encounter_proof;

CREATE POLICY encounter_proof_entitlement_read ON public.tenant_module_state
    FOR SELECT TO aronexa_encounter_proof
    USING (tenant_id = NULLIF(current_setting('aronexa.tenant_id',true),'')::uuid);
GRANT SELECT (tenant_id,module_code,entitled,activated,clinically_authorized)
    ON public.tenant_module_state TO aronexa_encounter_proof;
GRANT SELECT (code,implemented,released,requires_clinical_authorization)
    ON public.module_registry TO aronexa_encounter_proof;

-- No INSERT/UPDATE/DELETE/ALTER on access, identity, role, or module tables.
-- Transaction-local GUCs remain forgeable by a stolen SQL login: this is
-- defense-in-depth under server-only trusted datasource credentials, not
-- a proof of protection against arbitrary SQL execution.
