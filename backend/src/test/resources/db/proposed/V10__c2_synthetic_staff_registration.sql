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
-- Test-only secret store. A SQL LOGIN cannot read the verifier key.
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;
CREATE TABLE public.patient_registration_proof_key (
  id integer PRIMARY KEY CHECK (id>0),
  secret bytea NOT NULL CHECK (octet_length(secret)>=32),
  enabled boolean NOT NULL DEFAULT true,
  valid_from timestamptz NOT NULL DEFAULT '-infinity'::timestamptz,
  valid_until timestamptz NOT NULL DEFAULT 'infinity'::timestamptz,
  CONSTRAINT c2_proof_key_window CHECK (valid_from < valid_until)
);
REVOKE ALL ON public.patient_registration_proof_key FROM PUBLIC;
GRANT SELECT ON public.patient_registration_proof_key TO aronexa_registration_writer;
CREATE TABLE public.patient_registration_proof_nonce (
  nonce uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  actor_membership_id uuid NOT NULL,
  branch_id uuid NOT NULL,
  used_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT c2_proof_actor FOREIGN KEY (tenant_id,actor_membership_id)
    REFERENCES public.user_tenant_membership(tenant_id,id) ON DELETE RESTRICT,
  CONSTRAINT c2_proof_branch FOREIGN KEY (tenant_id,branch_id)
    REFERENCES public.branch(tenant_id,id) ON DELETE RESTRICT
);
ALTER TABLE public.patient_registration_proof_nonce ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.patient_registration_proof_nonce FORCE ROW LEVEL SECURITY;
CREATE POLICY c2_writer_proof_nonce_insert ON public.patient_registration_proof_nonce
  FOR INSERT TO aronexa_registration_writer
  WITH CHECK (tenant_id=public.aronexa_current_tenant_id()
    AND actor_membership_id=public.aronexa_current_patient_membership());
GRANT INSERT ON public.patient_registration_proof_nonce TO aronexa_registration_writer;
GRANT EXECUTE ON FUNCTION public.aronexa_current_tenant_id() TO aronexa_registration_writer;
GRANT EXECUTE ON FUNCTION public.aronexa_current_patient_membership() TO aronexa_registration_writer;

-- A NOLOGIN role holds narrow table rights. The LOGIN patient role receives only
-- EXECUTE on the exact procedure, not patient INSERT/UPDATE/DELETE or identity SELECT.
GRANT SELECT (id, user_id, tenant_id, enabled)
    ON public.user_tenant_membership TO aronexa_registration_writer;
GRANT SELECT (id, enabled, must_change_password)
    ON public.app_user TO aronexa_registration_writer;
-- NOLOGIN function owner gets a minimal auth_session row-lock capability.
-- FOR SHARE serializes registration with the auth runtime's UPDATE revoked_at
-- (logout and password changes). The SQL LOGIN still gets none of this grant.
GRANT SELECT (id,tenant_id,membership_id,revoked_at,expires_at),
      UPDATE (revoked_at) ON public.auth_session TO aronexa_registration_writer;
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

-- C5 synthetic revocation linearization: a tenant-scoped transaction
-- advisory lock is shared by the registration function and BEFORE row triggers
-- on all listed authorization-changing tables. No UPDATE permission is granted
-- to the NOLOGIN registration writer for these authorization tables.
-- This prototype is NOT comprehensive for app_user or global module_registry.
CREATE FUNCTION public.aronexa_c2_authz_change_guard()
RETURNS trigger LANGUAGE plpgsql VOLATILE
SET search_path=pg_catalog,public,pg_temp
AS $c2_guard$
BEGIN
  -- One key per tenant makes an earlier registration commit before revocation,
  -- or forces a later registration to observe the committed revocation.
  -- C6 synthetic-only global-before-tenant ordering. This is deliberately
  -- coarse and serializes unrelated tenants: NOT a production performance design.
  PERFORM pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended('ARONEXA-C2-AUTHZ-GLOBAL', 0));
  PERFORM pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended(
          'ARONEXA-C2-AUTHZ-TENANT:' || OLD.tenant_id::text, 0));
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END
$c2_guard$;
REVOKE ALL ON FUNCTION public.aronexa_c2_authz_change_guard() FROM PUBLIC;
CREATE TRIGGER c2_auth_membership_role_revocation_guard
    BEFORE UPDATE OR DELETE ON public.auth_membership_role
    FOR EACH ROW EXECUTE FUNCTION public.aronexa_c2_authz_change_guard();
CREATE TRIGGER c2_auth_role_permission_revocation_guard
    BEFORE UPDATE OR DELETE ON public.auth_role_permission
    FOR EACH ROW EXECUTE FUNCTION public.aronexa_c2_authz_change_guard();
CREATE TRIGGER c2_branch_scope_revocation_guard
    BEFORE UPDATE OR DELETE ON public.org_membership_branch_scope
    FOR EACH ROW EXECUTE FUNCTION public.aronexa_c2_authz_change_guard();
CREATE TRIGGER c2_tenant_module_revocation_guard
    BEFORE UPDATE OR DELETE ON public.tenant_module_state
    FOR EACH ROW EXECUTE FUNCTION public.aronexa_c2_authz_change_guard();
CREATE TRIGGER c2_membership_disable_guard
    BEFORE UPDATE OR DELETE ON public.user_tenant_membership
    FOR EACH ROW EXECUTE FUNCTION public.aronexa_c2_authz_change_guard();

-- C6: Global account-level and module catalog mutations lack a tenant_id.
-- Share the global transaction advisory lock with registration and C5 guards.
-- This proof-of-concept favors safe ordering over global throughput.
CREATE FUNCTION public.aronexa_c2_global_authz_change_guard()
RETURNS trigger LANGUAGE plpgsql VOLATILE
SET search_path=pg_catalog,public,pg_temp
AS $c2_global$
BEGIN
  PERFORM pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended('ARONEXA-C2-AUTHZ-GLOBAL', 0));
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END
$c2_global$;
REVOKE ALL ON FUNCTION public.aronexa_c2_global_authz_change_guard() FROM PUBLIC;
CREATE TRIGGER c2_app_user_authorization_guard
  BEFORE UPDATE OF enabled,must_change_password OR DELETE ON public.app_user
  FOR EACH ROW EXECUTE FUNCTION public.aronexa_c2_global_authz_change_guard();
CREATE TRIGGER c2_module_registry_authorization_guard
  BEFORE UPDATE OF implemented,released,requires_clinical_authorization
       OR DELETE ON public.module_registry
  FOR EACH ROW EXECUTE FUNCTION public.aronexa_c2_global_authz_change_guard();

-- pg_catalog and explicit public-qualified references avoid search-path hijacking.
-- This is deliberately restricted to synthetic, staff-assisted CREATE_NEW only.
CREATE FUNCTION public.aronexa_c2_register_patient(
    p_tenant uuid,p_actor uuid,p_session uuid,p_branch uuid,p_name text,p_precision text,p_dob date,
    p_key_hash text,p_payload_hash text,p_mrn text,
    p_key_id integer,p_nonce uuid,p_expires bigint,p_proof text)
RETURNS TABLE (patient_id uuid, registration_id uuid, mrn text, replayed boolean)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path=pg_catalog,public,pg_temp
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
  v_proof_secret bytea;
  v_proof_input text;
  v_now bigint;
  v_constraint text;
BEGIN
  v_tenant := public.aronexa_current_tenant_id();
  v_actor := public.aronexa_current_patient_membership();
  IF v_tenant IS NULL OR v_actor IS NULL OR p_session IS NULL OR p_branch IS NULL
    OR p_tenant IS DISTINCT FROM v_tenant OR p_actor IS DISTINCT FROM v_actor THEN
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
  -- Session GUCs are client-controllable. The HMAC binds exact verified
  -- tenant, membership, branch, payload, MRN, nonce and short expiry.
  v_now := floor(extract(epoch FROM clock_timestamp()))::bigint;
  IF p_key_id IS NULL OR p_key_id <= 0 OR p_nonce IS NULL
      OR p_expires IS NULL OR p_expires <= v_now
      OR p_expires > v_now + 60 OR p_proof IS NULL
      OR p_proof !~ '^[0-9a-f]{64}$' THEN
    RAISE EXCEPTION 'C2_PROOF_DENIED';
  END IF;
  SELECT k.secret INTO v_proof_secret
    FROM public.patient_registration_proof_key k
   WHERE k.id=p_key_id AND k.enabled
     AND k.valid_from <= clock_timestamp()
     AND k.valid_until > clock_timestamp();
  IF v_proof_secret IS NULL THEN RAISE EXCEPTION 'C2_PROOF_DENIED'; END IF;
  v_proof_input := array_to_string(ARRAY[
    'ARONEXA-C2-PROOF-V3',p_key_id::text,p_tenant::text,p_actor::text,
    p_session::text,p_branch::text,
    encode(convert_to(p_name,'UTF8'),'hex'),p_precision,
    COALESCE(p_dob::text,'-'),p_key_hash,p_payload_hash,p_mrn,
    p_nonce::text,p_expires::text], chr(10));
  IF encode(public.hmac(convert_to(v_proof_input,'UTF8'),
      v_proof_secret,'sha256'),'hex') IS DISTINCT FROM p_proof THEN
    RAISE EXCEPTION 'C2_PROOF_DENIED';
  END IF;
  -- C6: the same global guard must be taken FIRST, before the C5 tenant
  -- guard, to serialize global account/catalog updates with registration.
  PERFORM pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended('ARONEXA-C2-AUTHZ-GLOBAL', 0));
  -- C5: acquire the tenant's authorization commit-order lock before all
  -- session/permission/branch/module reads; hold it through COMMIT/ROLLBACK.
  -- All covered revocation UPDATE/DELETE paths acquire this SAME lock in a
  -- BEFORE ROW trigger, so committed revocations cannot overtake the write.
  PERFORM pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended(
          'ARONEXA-C2-AUTHZ-TENANT:' || v_tenant::text, 0));
  -- Recheck the exact bearer session at SQL execution, not just at HTTP
  -- filter entry. FOR SHARE prevents logout/password revocation UPDATE from
  -- committing before this registration transaction completes. Conversely,
  -- an already committed revocation is observed and denied. SQL LOGIN cannot
  -- supply a different session ID without forging the signed proof.
  PERFORM 1 FROM public.auth_session s
    WHERE s.id=p_session AND s.tenant_id=v_tenant AND s.membership_id=v_actor
      AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp()
    FOR SHARE;
  IF NOT FOUND THEN RAISE EXCEPTION 'C2_SESSION_REVOKED'; END IF;
  -- Verify actor, active role grants, branch, enabled module and entitlement.
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
  -- A plain INSERT avoids granting SELECT on nonce to the definer.
  -- Unique nonce violation is the definitive one-use replay signal.
  BEGIN
    INSERT INTO public.patient_registration_proof_nonce
       (nonce,tenant_id,actor_membership_id,branch_id)
      VALUES (p_nonce,v_tenant,v_actor,p_branch);
  EXCEPTION WHEN unique_violation THEN
    RAISE EXCEPTION 'C2_PROOF_REPLAY';
  END;
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
  -- Enforce a named MRN collision condition. Any unhandled UNIQUE failure
  -- is propagated; application opens an entirely new transaction for retry.
  BEGIN
    INSERT INTO public.patient_identifier (id,tenant_id,patient_id,identifier_type,identifier_value)
      VALUES (gen_random_uuid(),v_tenant,v_patient,'MRN',p_mrn);
  EXCEPTION WHEN unique_violation THEN
    GET STACKED DIAGNOSTICS v_constraint=CONSTRAINT_NAME;
    IF v_constraint='uq_patient_identifier_value' THEN
      RAISE EXCEPTION 'C2_MRN_COLLISION';
    END IF;
    RAISE;
  END;
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
ALTER FUNCTION public.aronexa_c2_register_patient(uuid,uuid,uuid,uuid,text,text,date,text,text,text,integer,uuid,bigint,text)
  OWNER TO aronexa_registration_writer;
REVOKE CREATE ON SCHEMA public FROM aronexa_registration_writer;
REVOKE ALL ON FUNCTION public.aronexa_c2_register_patient(
    uuid,uuid,uuid,uuid,text,text,date,text,text,text,integer,uuid,bigint,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.aronexa_c2_register_patient(
    uuid,uuid,uuid,uuid,text,text,date,text,text,text,integer,uuid,bigint,text) TO aronexa_patient_runtime;
