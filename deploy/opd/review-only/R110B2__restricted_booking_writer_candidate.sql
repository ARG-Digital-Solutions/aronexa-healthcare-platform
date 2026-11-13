-- R1-10B2 RESTRICTED SQL COMMAND CANDIDATE - DISPOSABLE PG17 ONLY.
-- NOT Flyway; DO NOT APPLY TO ORIGINAL WINDOWS / CUSTOMER DATABASE.
-- Prerequisite: review-only R1-10B1 appointment schema and V1-V9.
-- Key never seeded here; caller installs ephemeral key in test database.
-- NO live Spring HTTP endpoint or production datasource is activated.

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;
CREATE ROLE aronexa_opd_writer NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE aronexa_opd_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOINHERIT NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA public,opd_candidate TO aronexa_opd_writer;
GRANT USAGE ON SCHEMA opd_candidate TO aronexa_opd_runtime;
GRANT EXECUTE ON FUNCTION public.aronexa_current_tenant_id()
  TO aronexa_opd_writer;

CREATE TABLE opd_candidate.command_key (
  id integer PRIMARY KEY CHECK(id>0),
  secret bytea NOT NULL CHECK(octet_length(secret)=32),
  enabled boolean NOT NULL DEFAULT TRUE,
  expires_at timestamptz NOT NULL
);
CREATE TABLE opd_candidate.command_nonce (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  session_id uuid NOT NULL,
  used_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE opd_candidate.checkin_request (
  tenant_id uuid NOT NULL,
  branch_id uuid NOT NULL,
  appointment_id uuid NOT NULL,
  request_id uuid NOT NULL,
  actor_membership_id uuid NOT NULL,
  previous_revision integer NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id,request_id),
  UNIQUE (tenant_id,appointment_id),
  FOREIGN KEY(appointment_id,tenant_id,branch_id)
   REFERENCES opd_candidate.appointment(id,tenant_id,branch_id) ON DELETE RESTRICT,
  FOREIGN KEY(tenant_id,actor_membership_id)
   REFERENCES public.user_tenant_membership(tenant_id,id) ON DELETE RESTRICT
);
REVOKE ALL ON opd_candidate.command_key,opd_candidate.command_nonce,
  opd_candidate.checkin_request FROM PUBLIC;
GRANT SELECT ON opd_candidate.command_key TO aronexa_opd_writer;
GRANT INSERT ON opd_candidate.command_nonce TO aronexa_opd_writer;
GRANT SELECT,INSERT ON opd_candidate.checkin_request TO aronexa_opd_writer;

-- Function owner NOLOGIN has only the minimum data/authorization privileges.
-- The separate runtime login can ONLY invoke protected commands (no table DML).
GRANT SELECT ON public.auth_session,public.user_tenant_membership,
  public.app_user,public.auth_membership_role,
  public.auth_role_permission,public.auth_role,
  public.org_membership_branch_scope,public.tenant_module_state,
  public.module_registry,public.patient_registration,public.patient
  TO aronexa_opd_writer;
GRANT UPDATE (revoked_at) ON public.auth_session TO aronexa_opd_writer;
GRANT SELECT,INSERT,UPDATE ON opd_candidate.appointment TO aronexa_opd_writer;
GRANT INSERT ON opd_candidate.appointment_event TO aronexa_opd_writer;
GRANT USAGE ON SEQUENCE opd_candidate.appointment_event_id_seq
  TO aronexa_opd_writer;

-- FORCE RLS on existing V5/V7/V8 tables means the NOLOGIN function
-- owner still needs explicit narrowly scoped read policies.
CREATE POLICY opd_command_branch_scope ON public.org_membership_branch_scope
  FOR SELECT TO aronexa_opd_writer
  USING (tenant_id=public.aronexa_current_tenant_id());
CREATE POLICY opd_command_module_scope ON public.tenant_module_state
  FOR SELECT TO aronexa_opd_writer
  USING (tenant_id=public.aronexa_current_tenant_id());
CREATE POLICY opd_command_registration_scope ON public.patient_registration
  FOR SELECT TO aronexa_opd_writer
  USING (tenant_id=public.aronexa_current_tenant_id());
CREATE POLICY opd_command_patient_scope ON public.patient
  FOR SELECT TO aronexa_opd_writer
  USING (tenant_id=public.aronexa_current_tenant_id());

CREATE POLICY opd_command_appointment_read ON opd_candidate.appointment
  FOR SELECT TO aronexa_opd_writer
  USING (tenant_id=public.aronexa_current_tenant_id());
CREATE POLICY opd_command_appointment_insert ON opd_candidate.appointment
  FOR INSERT TO aronexa_opd_writer
  WITH CHECK (tenant_id=public.aronexa_current_tenant_id());
CREATE POLICY opd_command_appointment_update ON opd_candidate.appointment
  FOR UPDATE TO aronexa_opd_writer
  USING (tenant_id=public.aronexa_current_tenant_id())
  WITH CHECK (tenant_id=public.aronexa_current_tenant_id());
CREATE POLICY opd_command_event_append ON opd_candidate.appointment_event
  FOR INSERT TO aronexa_opd_writer
  WITH CHECK (tenant_id=public.aronexa_current_tenant_id());

-- Called only after HMAC verification. Never trust caller-controlled
-- PostgreSQL GUCs as authentication; authorization uses real auth_session.
CREATE FUNCTION opd_candidate.require_live_staff(
  p_tenant uuid,p_member uuid,p_session uuid,p_branch uuid,p_permission text)
RETURNS void LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp AS $$
BEGIN
  IF NOT EXISTS (
   SELECT 1 FROM public.auth_session s
   JOIN public.user_tenant_membership m
     ON m.tenant_id=s.tenant_id AND m.id=s.membership_id
   JOIN public.app_user u ON u.id=m.user_id
   JOIN public.org_membership_branch_scope scope
     ON scope.tenant_id=m.tenant_id AND scope.membership_id=m.id
   JOIN public.auth_membership_role assigned
     ON assigned.tenant_id=m.tenant_id AND assigned.membership_id=m.id
   JOIN public.auth_role_permission rp
     ON rp.tenant_id=assigned.tenant_id AND rp.role_id=assigned.role_id
   JOIN public.tenant_module_state state
     ON state.tenant_id=m.tenant_id AND state.module_code='OPD'
   JOIN public.module_registry registry ON registry.code=state.module_code
   WHERE s.id=p_session AND s.tenant_id=p_tenant AND s.membership_id=p_member
     AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp()
     AND m.enabled AND u.enabled AND NOT u.must_change_password
     AND scope.branch_id=p_branch AND rp.permission_code=p_permission
     AND state.entitled AND state.activated AND state.clinically_authorized
     AND registry.implemented AND registry.released
  ) THEN RAISE EXCEPTION 'OPD_ACCESS_DENIED'; END IF;
  -- Serialize active session with logout; requires NOLOGIN owner's
  -- narrowly scoped UPDATE revoked_at grant, not runtime LOGIN's.
  PERFORM 1 FROM public.auth_session s
   WHERE s.id=p_session AND s.tenant_id=p_tenant
     AND s.membership_id=p_member
     AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp()
   FOR SHARE;
  IF NOT FOUND THEN RAISE EXCEPTION 'OPD_SESSION_REVOKED'; END IF;
END $$;
REVOKE ALL ON FUNCTION opd_candidate.require_live_staff(
  uuid,uuid,uuid,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION opd_candidate.require_live_staff(
  uuid,uuid,uuid,uuid,text) TO aronexa_opd_writer;

CREATE FUNCTION opd_candidate.book_guarded(
 p_tenant uuid,p_member uuid,p_session uuid,p_branch uuid,
 p_registration uuid,p_patient uuid,p_doctor uuid,p_day date,p_slot time,
 p_request uuid,p_created_id uuid,p_key_id integer,p_nonce uuid,
 p_expires bigint,p_proof text)
RETURNS TABLE(result_appointment_id uuid,result_patient_id uuid,
 result_registration_id uuid,result_doctor_id uuid,
 result_stage text,result_revision integer,result_replayed boolean)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path=pg_catalog,pg_temp AS $$
DECLARE
 v_key bytea;
 v_message text;
 v_existing record;
BEGIN
 IF p_tenant IS NULL OR p_member IS NULL OR p_session IS NULL
   OR p_branch IS NULL OR p_registration IS NULL OR p_patient IS NULL
   OR p_doctor IS NULL OR p_day IS NULL OR p_slot IS NULL
   OR p_request IS NULL OR p_created_id IS NULL OR p_nonce IS NULL
   OR p_key_id IS NULL OR p_key_id<1
   OR p_expires IS NULL OR p_expires<=extract(epoch FROM clock_timestamp())
   OR p_expires>extract(epoch FROM clock_timestamp())+60
   OR p_proof IS NULL OR p_proof !~ '^[0-9a-f]{64}$'
   OR p_slot<time '09:00' OR p_slot>time '16:45'
   OR extract(minute FROM p_slot)::int%15<>0
   OR extract(second FROM p_slot)::int<>0
   THEN RAISE EXCEPTION 'OPD_INVALID_COMMAND'; END IF;
 SELECT k.secret INTO v_key FROM opd_candidate.command_key k
  WHERE k.id=p_key_id AND k.enabled AND k.expires_at>clock_timestamp();
 IF v_key IS NULL THEN RAISE EXCEPTION 'OPD_PROOF_DENIED'; END IF;
 v_message:=concat_ws(chr(10),'ARONEXA-OPD-BOOK-V1',p_key_id::text,
   p_tenant::text,p_member::text,p_session::text,p_branch::text,
   p_registration::text,p_patient::text,p_doctor::text,p_day::text,
   to_char(p_slot,'HH24:MI'),p_request::text,p_created_id::text,
   p_nonce::text,p_expires::text);
 IF encode(public.hmac(convert_to(v_message,'UTF8'),v_key,'sha256'),'hex')
   IS DISTINCT FROM p_proof THEN
   RAISE EXCEPTION 'OPD_PROOF_DENIED';
 END IF;
 PERFORM set_config('aronexa.tenant_id',p_tenant::text,true);
 PERFORM set_config('aronexa.membership_id',p_member::text,true);
 PERFORM opd_candidate.require_live_staff(
   p_tenant,p_member,p_session,p_branch,'APPOINTMENT_BOOK');
 IF NOT EXISTS (
    SELECT 1 FROM public.patient_registration r
    JOIN public.patient p
      ON p.tenant_id=r.tenant_id AND p.id=r.patient_id
    WHERE r.tenant_id=p_tenant AND r.branch_id=p_branch
      AND r.id=p_registration AND r.patient_id=p_patient
      AND p.status='ACTIVE'
 ) THEN RAISE EXCEPTION 'OPD_REGISTRATION_DENIED'; END IF;
 IF NOT EXISTS (
    SELECT 1 FROM public.user_tenant_membership m
    JOIN public.app_user u ON u.id=m.user_id
    JOIN public.org_membership_branch_scope scope
      ON scope.tenant_id=m.tenant_id AND scope.membership_id=m.id
    JOIN public.auth_membership_role mr
      ON mr.tenant_id=m.tenant_id AND mr.membership_id=m.id
    JOIN public.auth_role role
      ON role.tenant_id=mr.tenant_id AND role.id=mr.role_id
    WHERE m.tenant_id=p_tenant AND m.id=p_doctor
      AND m.enabled AND u.enabled AND scope.branch_id=p_branch
      AND role.code='DOCTOR'
 ) THEN RAISE EXCEPTION 'OPD_DOCTOR_DENIED'; END IF;
 -- A consumed proof nonce cannot be reused even for an idempotent retry.
 INSERT INTO opd_candidate.command_nonce(id,tenant_id,session_id)
 VALUES(p_nonce,p_tenant,p_session);
 SELECT * INTO v_existing FROM opd_candidate.appointment a
   WHERE a.tenant_id=p_tenant AND a.booking_request_id=p_request
   FOR SHARE;
 IF FOUND THEN
    IF v_existing.branch_id<>p_branch
      OR v_existing.registration_id<>p_registration
      OR v_existing.patient_id<>p_patient
      OR v_existing.doctor_membership_id<>p_doctor
      OR v_existing.visit_date<>p_day
      OR v_existing.slot_start<>p_slot
    THEN RAISE EXCEPTION 'OPD_IDEMPOTENCY_CONFLICT'; END IF;
    RETURN QUERY SELECT v_existing.id,v_existing.patient_id,
      v_existing.registration_id,v_existing.doctor_membership_id,
      v_existing.status::text,v_existing.revision,TRUE;
    RETURN;
 END IF;
 INSERT INTO opd_candidate.appointment
   (id,tenant_id,branch_id,patient_id,registration_id,
    doctor_membership_id,booking_request_id,booked_by_membership_id,
    updated_by_membership_id,visit_date,slot_start,status,revision)
 VALUES(p_created_id,p_tenant,p_branch,p_patient,p_registration,
    p_doctor,p_request,p_member,p_member,p_day,p_slot,'BOOKED',1);
 RETURN QUERY SELECT p_created_id,p_patient,p_registration,p_doctor,
   'BOOKED'::text,1,FALSE;
END $$;
ALTER FUNCTION opd_candidate.book_guarded(
 uuid,uuid,uuid,uuid,uuid,uuid,uuid,date,time,
 uuid,uuid,integer,uuid,bigint,text) OWNER TO aronexa_opd_writer;
REVOKE ALL ON FUNCTION opd_candidate.book_guarded(
 uuid,uuid,uuid,uuid,uuid,uuid,uuid,date,time,
 uuid,uuid,integer,uuid,bigint,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION opd_candidate.book_guarded(
 uuid,uuid,uuid,uuid,uuid,uuid,uuid,date,time,
 uuid,uuid,integer,uuid,bigint,text) TO aronexa_opd_runtime;

CREATE FUNCTION opd_candidate.checkin_guarded(
 p_tenant uuid,p_member uuid,p_session uuid,p_branch uuid,
 p_appointment uuid,p_expected integer,p_request uuid,p_key_id integer,
 p_nonce uuid,p_expires bigint,p_proof text)
RETURNS TABLE(result_appointment_id uuid,result_patient_id uuid,
 result_registration_id uuid,result_doctor_id uuid,
 result_stage text,result_revision integer,result_replayed boolean)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path=pg_catalog,pg_temp AS $$
DECLARE
 v_key bytea;
 v_message text;
 v_appt record;
 v_replay record;
BEGIN
 IF p_tenant IS NULL OR p_member IS NULL OR p_session IS NULL
   OR p_branch IS NULL OR p_appointment IS NULL OR p_request IS NULL
   OR p_nonce IS NULL OR p_key_id IS NULL OR p_key_id<1
   OR p_expected IS NULL OR p_expected<1 OR p_expected=2147483647
   OR p_expires IS NULL OR p_expires<=extract(epoch FROM clock_timestamp())
   OR p_expires>extract(epoch FROM clock_timestamp())+60
   OR p_proof IS NULL OR p_proof !~ '^[0-9a-f]{64}$'
   THEN RAISE EXCEPTION 'OPD_INVALID_COMMAND'; END IF;
 SELECT k.secret INTO v_key FROM opd_candidate.command_key k
   WHERE k.id=p_key_id AND k.enabled AND k.expires_at>clock_timestamp();
 IF v_key IS NULL THEN RAISE EXCEPTION 'OPD_PROOF_DENIED'; END IF;
 v_message:=concat_ws(chr(10),'ARONEXA-OPD-CHECKIN-V1',p_key_id::text,
   p_tenant::text,p_member::text,p_session::text,p_branch::text,
   p_appointment::text,p_expected::text,p_request::text,p_nonce::text,
   p_expires::text);
 IF encode(public.hmac(convert_to(v_message,'UTF8'),v_key,'sha256'),'hex')
   IS DISTINCT FROM p_proof THEN
   RAISE EXCEPTION 'OPD_PROOF_DENIED'; END IF;
 PERFORM set_config('aronexa.tenant_id',p_tenant::text,true);
 PERFORM set_config('aronexa.membership_id',p_member::text,true);
 PERFORM opd_candidate.require_live_staff(
   p_tenant,p_member,p_session,p_branch,'APPOINTMENT_CHECK_IN');
 INSERT INTO opd_candidate.command_nonce(id,tenant_id,session_id)
 VALUES(p_nonce,p_tenant,p_session);
 SELECT * INTO v_appt FROM opd_candidate.appointment a
   WHERE a.id=p_appointment AND a.tenant_id=p_tenant
     AND a.branch_id=p_branch FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'OPD_VISIT_NOT_FOUND'; END IF;
 SELECT * INTO v_replay FROM opd_candidate.checkin_request cr
    WHERE cr.tenant_id=p_tenant AND cr.request_id=p_request;
 IF FOUND THEN
   IF v_replay.appointment_id<>p_appointment
     OR v_replay.branch_id<>p_branch
     OR v_replay.actor_membership_id<>p_member
     OR v_replay.previous_revision<>p_expected
   THEN RAISE EXCEPTION 'OPD_CHECKIN_REPLAY_CONFLICT'; END IF;
   RETURN QUERY SELECT v_appt.id,v_appt.patient_id,
     v_appt.registration_id,v_appt.doctor_membership_id,
     v_appt.status::text,v_appt.revision,TRUE;
   RETURN;
 END IF;
 IF v_appt.status<>'BOOKED' OR v_appt.revision<>p_expected THEN
   RAISE EXCEPTION 'OPD_VISIT_REVISION_CONFLICT'; END IF;
 UPDATE opd_candidate.appointment a SET
   status='CHECKED_IN',revision=revision+1,
   updated_by_membership_id=p_member
   WHERE a.id=p_appointment AND a.tenant_id=p_tenant
     AND a.branch_id=p_branch;
 INSERT INTO opd_candidate.checkin_request
   (tenant_id,branch_id,appointment_id,request_id,actor_membership_id,
    previous_revision)
 VALUES(p_tenant,p_branch,p_appointment,p_request,p_member,p_expected);
 RETURN QUERY SELECT p_appointment,v_appt.patient_id,
   v_appt.registration_id,v_appt.doctor_membership_id,
   'CHECKED_IN'::text,p_expected+1,FALSE;
END $$;
ALTER FUNCTION opd_candidate.checkin_guarded(
 uuid,uuid,uuid,uuid,uuid,integer,uuid,integer,uuid,bigint,text)
 OWNER TO aronexa_opd_writer;
REVOKE ALL ON FUNCTION opd_candidate.checkin_guarded(
 uuid,uuid,uuid,uuid,uuid,integer,uuid,integer,uuid,bigint,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION opd_candidate.checkin_guarded(
 uuid,uuid,uuid,uuid,uuid,integer,uuid,integer,uuid,bigint,text)
 TO aronexa_opd_runtime;
-- CRITICAL: runtime has no SELECT/INSERT/UPDATE/DELETE on appointment tables.
-- Public function grants were revoked; only explicitly named commands execute.
-- Production role setup, key custody/rotation, privilege audit, revocation
-- linearization and independent code/security review remain NOT APPROVED.
