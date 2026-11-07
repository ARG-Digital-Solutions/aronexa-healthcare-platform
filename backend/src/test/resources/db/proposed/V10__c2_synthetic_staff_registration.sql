-- P01-T07-C2 PROPOSED V10: disposable Testcontainers ONLY.
-- Located in src/test/resources, NOT production Flyway locations.
-- Never apply this file to an existing persistent database. No V1-V9 modifications.
-- Dedicated NOLOGIN function owner is pre-provisioned by synthetic test bootstrap.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles
      WHERE rolname='aronexa_registration_writer'
        AND NOT rolcanlogin AND NOT rolsuper AND NOT rolbypassrls
        AND NOT rolcreatedb AND NOT rolcreaterole) THEN
        RAISE EXCEPTION 'C2_RESTRICTED_WRITER_REQUIRED';
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO aronexa_registration_writer;
GRANT EXECUTE ON FUNCTION public.aronexa_current_tenant_id() TO aronexa_registration_writer;
GRANT EXECUTE ON FUNCTION public.aronexa_current_patient_membership() TO aronexa_registration_writer;

-- A NOLOGIN role holds narrow table rights. The LOGIN patient role receives only
-- EXECUTE on the exact procedure, not patient INSERT/UPDATE/DELETE or identity SELECT.
GRANT SELECT (id, user_id, tenant_id, enabled)
    ON public.user_tenant_membership TO aronexa_registration_writer;
GRANT SELECT (id, enabled, must_change_password)
    ON public.app_user TO aronexa_registration_writer;
GRANT SELECT ON public.auth_membership_role, public.auth_role_permission
    TO aronexa_registration_writer;
GRANT SELECT ON public.org_membership_branch_scope, public.tenant_module_state,
    public.module_registry TO aronexa_registration_writer;
GRANT SELECT (id, tenant_id, display_name, date_of_birth)
    ON public.patient TO aronexa_registration_writer;
GRANT SELECT (tenant_id, patient_id, branch_id)
    ON public.patient_registration TO aronexa_registration_writer;
GRANT INSERT ON public.patient, public.patient_identifier,
    public.patient_registration TO aronexa_registration_writer;

-- FORCE RLS is already enabled on V8 identity tables. Add dedicated owner policies
-- scoped to the transaction context and staff membership/branch. These policies
-- do not apply to the LOGIN patient runtime.
CREATE POLICY c2_writer_patient_read ON public.patient FOR SELECT
  TO aronexa_registration_writer
  USING (tenant_id=public.aronexa_current_tenant_id());
CREATE POLICY c2_writer_patient_insert ON public.patient FOR INSERT
  TO aronexa_registration_writer
  WITH CHECK (tenant_id=public.aronexa_current_tenant_id());
CREATE POLICY c2_writer_identifier_insert ON public.patient_identifier FOR INSERT
  TO aronexa_registration_writer
  WITH CHECK (tenant_id=public.aronexa_current_tenant_id());
CREATE POLICY c2_writer_registration_read ON public.patient_registration FOR SELECT
  TO aronexa_registration_writer
  USING (tenant_id=public.aronexa_current_tenant_id() AND EXISTS (
    SELECT 1 FROM public.org_membership_branch_scope s
     WHERE s.tenant_id=patient_registration.tenant_id
       AND s.branch_id=patient_registration.branch_id
       AND s.membership_id=public.aronexa_current_patient_membership()));
CREATE POLICY c2_writer_registration_insert ON public.patient_registration FOR INSERT
  TO aronexa_registration_writer
  WITH CHECK (tenant_id=public.aronexa_current_tenant_id() AND
    registered_by_membership_id=public.aronexa_current_patient_membership() AND EXISTS (
      SELECT 1 FROM public.org_membership_branch_scope s
       WHERE s.tenant_id=patient_registration.tenant_id
         AND s.branch_id=patient_registration.branch_id
         AND s.membership_id=public.aronexa_current_patient_membership()));
CREATE POLICY c2_writer_branch_scope ON public.org_membership_branch_scope FOR SELECT
  TO aronexa_registration_writer
  USING (tenant_id=public.aronexa_current_tenant_id() AND
         membership_id=public.aronexa_current_patient_membership());
CREATE POLICY c2_writer_module_scope ON public.tenant_module_state FOR SELECT
  TO aronexa_registration_writer
  USING (tenant_id=public.aronexa_current_tenant_id());

CREATE TABLE public.patient_registration_idempotency (
  tenant_id uuid NOT NULL,
  key_hash char(64) NOT NULL,
  payload_hash char(64) NOT NULL,
  actor_membership_id uuid NOT NULL,
  branch_id uuid NOT NULL,
  patient_id uuid,
  registration_id uuid,
  mrn varchar(48),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, key_hash),
  CONSTRAINT c2_idem_actor FOREIGN KEY (tenant_id, actor_membership_id)
      REFERENCES public.user_tenant_membership(tenant_id,id) ON DELETE RESTRICT,
  CONSTRAINT c2_idem_branch FOREIGN KEY (tenant_id, branch_id)
      REFERENCES public.branch(tenant_id,id) ON DELETE RESTRICT,
  CONSTRAINT c2_idem_key_format CHECK (key_hash ~ '^[0-9a-f]{64}$'
    AND payload_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT c2_idem_result_state CHECK (
    (patient_id IS NULL AND registration_id IS NULL AND mrn IS NULL)
    OR (patient_id IS NOT NULL AND registration_id IS NOT NULL AND mrn IS NOT NULL))
);
ALTER TABLE public.patient_registration_idempotency ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.patient_registration_idempotency FORCE ROW LEVEL SECURITY;
CREATE POLICY c2_writer_idempotency ON public.patient_registration_idempotency
  FOR ALL TO aronexa_registration_writer
  USING (tenant_id=public.aronexa_current_tenant_id() AND
         actor_membership_id=public.aronexa_current_patient_membership())
  WITH CHECK (tenant_id=public.aronexa_current_tenant_id() AND
              actor_membership_id=public.aronexa_current_patient_membership());
GRANT SELECT, INSERT, UPDATE ON public.patient_registration_idempotency
    TO aronexa_registration_writer;
-- No direct SELECT/INSERT access to login patient runtime.

CREATE TABLE public.patient_registration_write_audit (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  actor_membership_id uuid NOT NULL,
  branch_id uuid NOT NULL,
  patient_id uuid NOT NULL,
  registration_id uuid NOT NULL,
  action varchar(16) NOT NULL CHECK (action='REGISTER'),
  occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT c2_audit_actor FOREIGN KEY (tenant_id,actor_membership_id)
    REFERENCES public.user_tenant_membership(tenant_id,id) ON DELETE RESTRICT,
  CONSTRAINT c2_audit_registration FOREIGN KEY (tenant_id,branch_id,registration_id)
    REFERENCES public.patient_registration(tenant_id,branch_id,id) ON DELETE RESTRICT
);
CREATE TRIGGER patient_registration_write_audit_immutable
  BEFORE UPDATE OR DELETE ON public.patient_registration_write_audit
  FOR EACH ROW EXECUTE FUNCTION public.reject_patient_audit_mutation();
ALTER TABLE public.patient_registration_write_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.patient_registration_write_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY c2_writer_audit_insert ON public.patient_registration_write_audit
  FOR INSERT TO aronexa_registration_writer
  WITH CHECK (tenant_id=public.aronexa_current_tenant_id() AND
    actor_membership_id=public.aronexa_current_patient_membership() AND EXISTS (
      SELECT 1 FROM public.org_membership_branch_scope s
       WHERE s.tenant_id=patient_registration_write_audit.tenant_id
         AND s.membership_id=patient_registration_write_audit.actor_membership_id
         AND s.branch_id=patient_registration_write_audit.branch_id));
GRANT INSERT ON public.patient_registration_write_audit
    TO aronexa_registration_writer;

-- pg_catalog and explicit public-qualified references avoid search-path hijacking.
-- This is deliberately restricted to synthetic, staff-assisted CREATE_NEW only.
CREATE FUNCTION public.aronexa_c2_register_patient(
    p_branch uuid, p_name text, p_precision text, p_dob date,
    p_key_hash text, p_payload_hash text, p_mrn text)
RETURNS TABLE (patient_id uuid, registration_id uuid, mrn text, replayed boolean)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path=pg_catalog,public
AS $$
DECLARE
  v_tenant uuid;
  v_actor uuid;
  v_name text;
  v_key char(64);
  v_payload char(64);
  v_existing record;
  v_patient uuid;
  v_registration uuid;
  v_inserted integer;
BEGIN
  v_tenant := public.aronexa_current_tenant_id();
  v_actor := public.aronexa_current_patient_membership();
  IF v_tenant IS NULL OR v_actor IS NULL OR p_branch IS NULL THEN
    RAISE EXCEPTION 'C2_ACCESS_DENIED';
  END IF;
  IF p_name IS NULL OR p_precision NOT IN ('EXACT','ESTIMATED','UNKNOWN')
      OR length(btrim(p_name)) < 2 OR length(btrim(p_name)) > 180
      OR (p_precision='UNKNOWN' AND p_dob IS NOT NULL)
      OR (p_precision<>'UNKNOWN' AND (p_dob IS NULL OR p_dob>CURRENT_DATE))
      OR p_name LIKE '%<%' OR p_name LIKE '%>%' THEN
    RAISE EXCEPTION 'C2_INVALID_INPUT';
  END IF;
  IF p_key_hash !~ '^[0-9a-f]{64}$' OR p_payload_hash !~ '^[0-9a-f]{64}$'
      OR p_mrn !~ '^PAT-[0-9A-HJKMNP-TV-Z]{12}$' THEN
    RAISE EXCEPTION 'C2_INVALID_INPUT';
  END IF;
  -- Independent DB-side staff, role/permission, branch and entitlement checks.
  IF NOT EXISTS (
    SELECT 1 FROM public.user_tenant_membership m
    JOIN public.app_user u ON u.id=m.user_id
    JOIN public.auth_membership_role mr ON mr.tenant_id=m.tenant_id AND mr.membership_id=m.id
    JOIN public.auth_role_permission rp ON rp.tenant_id=mr.tenant_id AND rp.role_id=mr.role_id
    JOIN public.org_membership_branch_scope bs
      ON bs.tenant_id=m.tenant_id AND bs.membership_id=m.id AND bs.branch_id=p_branch
    JOIN public.tenant_module_state ts
      ON ts.tenant_id=m.tenant_id AND ts.module_code='PATIENT_REGISTRATION'
    JOIN public.module_registry catalog ON catalog.code=ts.module_code
    WHERE m.id=v_actor AND m.tenant_id=v_tenant AND m.enabled AND u.enabled
      AND NOT u.must_change_password AND rp.permission_code='PATIENT_REGISTER'
      AND catalog.implemented AND catalog.released AND ts.entitled AND ts.activated
      AND (NOT catalog.requires_clinical_authorization OR ts.clinically_authorized)
  ) THEN RAISE EXCEPTION 'C2_ACCESS_DENIED'; END IF;
  v_name := regexp_replace(btrim(p_name),'[[:space:]]+',' ','g');
  v_key := p_key_hash::char(64); v_payload := p_payload_hash::char(64);
  -- One key is claimed transactionally. Concurrent identical submissions wait
  -- on the unique conflict, then read the committed stable result.
  INSERT INTO public.patient_registration_idempotency
    (tenant_id,key_hash,payload_hash,actor_membership_id,branch_id)
    VALUES (v_tenant,v_key,v_payload,v_actor,p_branch)
    ON CONFLICT (tenant_id,key_hash) DO NOTHING;
  GET DIAGNOSTICS v_inserted=ROW_COUNT;
  IF v_inserted=0 THEN
    SELECT i.* INTO v_existing FROM public.patient_registration_idempotency i
      WHERE i.tenant_id=v_tenant AND i.key_hash=v_key FOR UPDATE;
    IF NOT FOUND OR v_existing.payload_hash<>v_payload
        OR v_existing.actor_membership_id<>v_actor OR v_existing.branch_id<>p_branch THEN
      RAISE EXCEPTION 'C2_IDEMPOTENCY_CONFLICT';
    END IF;
    IF v_existing.patient_id IS NULL THEN RAISE EXCEPTION 'C2_RETRY_LATER'; END IF;
    RETURN QUERY SELECT v_existing.patient_id, v_existing.registration_id,
                        v_existing.mrn::text, TRUE;
    RETURN;
  END IF;
  -- Serialize same normalized-name proposals at the assigned branch (not identity
  -- verification). Preserve separate patients with distinct verified DOB.
  PERFORM pg_advisory_xact_lock(hashtextextended(
      v_tenant::text||':'||p_branch::text||':'||lower(v_name), 0));
  IF EXISTS (
    SELECT 1 FROM public.patient_registration r
    JOIN public.patient p ON p.tenant_id=r.tenant_id AND p.id=r.patient_id
     WHERE r.tenant_id=v_tenant AND r.branch_id=p_branch
       AND lower(regexp_replace(btrim(p.display_name),'[[:space:]]+',' ','g'))=lower(v_name)
       AND (p.date_of_birth IS NULL OR p_dob IS NULL OR p.date_of_birth=p_dob)
  ) THEN RAISE EXCEPTION 'C2_DUPLICATE_REVIEW_REQUIRED'; END IF;
  v_patient := gen_random_uuid(); v_registration := gen_random_uuid();
  INSERT INTO public.patient (id,tenant_id,display_name,birth_date_precision,date_of_birth)
    VALUES (v_patient,v_tenant,v_name,p_precision,p_dob);
  INSERT INTO public.patient_identifier (id,tenant_id,patient_id,identifier_type,identifier_value)
    VALUES (gen_random_uuid(),v_tenant,v_patient,'MRN',p_mrn);
  INSERT INTO public.patient_registration
    (id,tenant_id,patient_id,branch_id,registration_source,
     registered_by_membership_id,idempotency_sha256)
    VALUES (v_registration,v_tenant,v_patient,p_branch,'RECEPTION',v_actor,v_key);
  INSERT INTO public.patient_registration_write_audit
    (id,tenant_id,actor_membership_id,branch_id,patient_id,registration_id,action)
    VALUES (gen_random_uuid(),v_tenant,v_actor,p_branch,v_patient,v_registration,'REGISTER');
  UPDATE public.patient_registration_idempotency SET patient_id=v_patient,
    registration_id=v_registration,mrn=p_mrn
    WHERE tenant_id=v_tenant AND key_hash=v_key;
  RETURN QUERY SELECT v_patient,v_registration,p_mrn, FALSE;
END $$;

-- PostgreSQL requires the new function owner to be CREATE-capable on the schema
-- at transfer time. Grant temporarily and revoke immediately after ownership.
GRANT CREATE ON SCHEMA public TO aronexa_registration_writer;
ALTER FUNCTION public.aronexa_c2_register_patient(uuid,text,text,date,text,text,text)
  OWNER TO aronexa_registration_writer;
REVOKE CREATE ON SCHEMA public FROM aronexa_registration_writer;
REVOKE ALL ON FUNCTION public.aronexa_c2_register_patient(
    uuid,text,text,date,text,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.aronexa_c2_register_patient(
    uuid,text,text,date,text,text,text) TO aronexa_patient_runtime;
