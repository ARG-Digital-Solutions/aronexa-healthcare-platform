-- PILOT-08 RC02B SYNTHETIC TEST ONLY — NOT A FLYWAY MIGRATION.
-- DO NOT APPLY TO ORIGINAL WINDOWS V8/V9 DB OR CUSTOMER DATABASE.
-- This file is run solely by the disposable PostgreSQL 17 Testcontainers
-- acceptance suite. It proposes a narrow EXECUTE-only pattern for review.
-- No GRANT SELECT on patient / patient_identifier / patient_contact.
DO $guard$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname='aronexa_patient_runtime'
         AND rolcanlogin AND NOT rolsuper AND NOT rolbypassrls
         AND NOT rolcreatedb AND NOT rolcreaterole
    ) THEN RAISE EXCEPTION 'RC02B STOP: patient runtime not restricted'; END IF;
END
$guard$;

CREATE TABLE public.pilot08_patient_search_audit (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    membership_id uuid NOT NULL,
    branch_id uuid NOT NULL,
    mode varchar(12) NOT NULL CHECK(mode IN ('MRN','NAME_PREFIX')),
    result_count integer NOT NULL CHECK(result_count BETWEEN 0 AND 10),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (tenant_id,membership_id)
      REFERENCES public.user_tenant_membership(tenant_id,id),
    FOREIGN KEY (tenant_id,branch_id)
      REFERENCES public.branch(tenant_id,id)
);
CREATE TRIGGER pilot08_patient_search_audit_immutable
    BEFORE UPDATE OR DELETE ON public.pilot08_patient_search_audit
    FOR EACH ROW EXECUTE FUNCTION public.reject_patient_audit_mutation();
REVOKE ALL ON public.pilot08_patient_search_audit FROM PUBLIC;
REVOKE ALL ON public.pilot08_patient_search_audit
    FROM aronexa_patient_runtime,aronexa_app_runtime,aronexa_auth_runtime;

-- SECURITY DEFINER has access to private tables; do not treat it as RLS.
-- All security predicates are repeated INSIDE the definer, independently
-- of Java checks or untrusted frontend inputs. Signature is strictly fixed.
CREATE FUNCTION public.pilot08_find_patient_registration(
    p_branch uuid,p_mode text,p_term text
)
RETURNS TABLE (
    tenant_id uuid, branch_id uuid,registration_id uuid,patient_id uuid,
    display_name varchar, mrn varchar
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path=pg_catalog,public
AS $body$
DECLARE
    v_tenant uuid := public.aronexa_current_tenant_id();
    v_member uuid := public.aronexa_current_patient_membership();
    v_found integer := 0;
BEGIN
    IF v_tenant IS NULL OR v_member IS NULL OR p_branch IS NULL
       OR NOT EXISTS (
         SELECT 1 FROM public.org_membership_branch_scope s
         JOIN public.user_tenant_membership m
           ON m.tenant_id=s.tenant_id AND m.id=s.membership_id
         JOIN public.app_user u ON u.id=m.user_id
         WHERE s.tenant_id=v_tenant AND s.membership_id=v_member
           AND s.branch_id=p_branch AND m.enabled AND u.enabled
       )
       OR NOT EXISTS (
         SELECT 1 FROM public.tenant_module_state t
         JOIN public.module_registry r ON r.code=t.module_code
         WHERE t.tenant_id=v_tenant AND t.module_code='PATIENT_REGISTRATION'
           AND r.implemented AND r.released AND t.entitled AND t.activated
           AND (NOT r.requires_clinical_authorization OR t.clinically_authorized)
       )
       OR NOT EXISTS (
         SELECT 1 FROM public.auth_membership_role amr
         JOIN public.auth_role_permission arp
           ON arp.tenant_id=amr.tenant_id AND arp.role_id=amr.role_id
         WHERE amr.tenant_id=v_tenant AND amr.membership_id=v_member
           AND arp.permission_code='PATIENT_CANDIDATE_SEARCH'
       )
       OR NOT EXISTS (
         SELECT 1 FROM public.auth_membership_role amr
         JOIN public.auth_role_permission arp
           ON arp.tenant_id=amr.tenant_id AND arp.role_id=amr.role_id
         WHERE amr.tenant_id=v_tenant AND amr.membership_id=v_member
           AND arp.permission_code='PATIENT_REGISTRATION_READ'
       )
       OR p_mode NOT IN ('MRN','NAME_PREFIX') OR p_term IS NULL
       OR (p_mode='MRN'
           AND (p_term !~ '^[A-Z0-9][A-Z0-9-]{2,47}$'))
       OR (p_mode='NAME_PREFIX'
           AND (length(p_term)<3 OR length(p_term)>60
               OR p_term !~ '^[A-Za-z][A-Za-z .''-]{2,59}$'))
    THEN
        RETURN;
    END IF;

    -- Never return unregistered patients, unassigned branches, inactive
    -- patients, contacts or dates of birth. A fixed maximum of 10.
    RETURN QUERY
      SELECT r.tenant_id,r.branch_id,r.id,r.patient_id,
             p.display_name,i.identifier_value
        FROM public.patient_registration r
        JOIN public.patient p ON p.tenant_id=r.tenant_id
                             AND p.id=r.patient_id
                             AND p.status='ACTIVE'
        JOIN public.patient_identifier i
          ON i.tenant_id=p.tenant_id AND i.patient_id=p.id
         AND i.identifier_type='MRN'
       WHERE r.tenant_id=v_tenant
         AND r.branch_id=p_branch
         AND (
           (p_mode='MRN' AND i.identifier_value=p_term)
           OR
           (p_mode='NAME_PREFIX' AND lower(p.display_name)
                         LIKE lower(p_term)||'%')
         )
       ORDER BY p.display_name,r.id
       LIMIT 10;
    GET DIAGNOSTICS v_found=ROW_COUNT;
    INSERT INTO public.pilot08_patient_search_audit(
        id,tenant_id,membership_id,branch_id,mode,result_count)
    VALUES (pg_catalog.gen_random_uuid(),v_tenant,v_member,p_branch,
            p_mode,v_found);
END
$body$;

REVOKE ALL ON FUNCTION
    public.pilot08_find_patient_registration(uuid,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION
    public.pilot08_find_patient_registration(uuid,text,text)
    TO aronexa_patient_runtime;

-- No direct identity-table grants are introduced. No production migration.
