-- COMM-V1-T14 / PR #102: disposable SQL appointment persistence ONLY.
-- Testcontainers executes this script AFTER standard V1-V9 Flyway migrations.
-- Never install via production Flyway or the original Windows PostgreSQL.
-- No public clinical route, app SQL grant, patient API or customer data.
CREATE SCHEMA appointment_proof;

-- Dedicated reader is restricted and cannot mutate appointments or identity.
-- GUC-based RLS alone is NOT authentic bearer identity verification.
CREATE ROLE aronexa_appointment_read_proof LOGIN
  PASSWORD 'disposable-appointment-proof-only'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
GRANT USAGE ON SCHEMA appointment_proof TO aronexa_appointment_read_proof;
GRANT USAGE ON SCHEMA public TO aronexa_appointment_read_proof;
GRANT EXECUTE ON FUNCTION public.aronexa_current_tenant_id()
  TO aronexa_appointment_read_proof;

-- Proof-only permission. No tenant receives a role grant automatically.
INSERT INTO public.auth_permission(code,description)
VALUES ('APPOINTMENT_BOOK','Synthetic appointment booking proof only')
ON CONFLICT (code) DO NOTHING;

CREATE TABLE appointment_proof.appointment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  branch_id uuid NOT NULL,
  patient_id uuid NOT NULL,
  doctor_membership_id uuid NOT NULL,
  booked_by_membership_id uuid NOT NULL,
  updated_by_membership_id uuid NOT NULL,
  visit_date date NOT NULL CHECK (visit_date BETWEEN DATE '2000-01-01' AND DATE '2100-12-31'),
  slot_start time(0) WITHOUT TIME ZONE NOT NULL
    CHECK (slot_start BETWEEN TIME '09:00' AND TIME '16:45'
      AND EXTRACT(MINUTE FROM slot_start)::int % 15 = 0
      AND EXTRACT(SECOND FROM slot_start)::int = 0),
  status varchar(12) NOT NULL DEFAULT 'BOOKED'
    CHECK (status IN ('BOOKED','CHECKED_IN','CANCELLED')),
  revision integer NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_proof_appointment_branch FOREIGN KEY (tenant_id,branch_id)
    REFERENCES public.branch(tenant_id,id) ON DELETE RESTRICT,
  CONSTRAINT fk_proof_appointment_patient FOREIGN KEY (tenant_id,patient_id)
    REFERENCES public.patient(tenant_id,id) ON DELETE RESTRICT,
  CONSTRAINT fk_proof_appointment_doctor FOREIGN KEY (tenant_id,doctor_membership_id)
    REFERENCES public.user_tenant_membership(tenant_id,id) ON DELETE RESTRICT,
  CONSTRAINT fk_proof_appointment_booker FOREIGN KEY (tenant_id,booked_by_membership_id)
    REFERENCES public.user_tenant_membership(tenant_id,id) ON DELETE RESTRICT,
  CONSTRAINT fk_proof_appointment_updater FOREIGN KEY (tenant_id,updated_by_membership_id)
    REFERENCES public.user_tenant_membership(tenant_id,id) ON DELETE RESTRICT
);

-- Database uniqueness survives concurrent writers, restart and local UI refresh.
-- CANCELLED slots become available again; CHECKED_IN appointments remain occupied.
CREATE UNIQUE INDEX uq_proof_active_doctor_slot
  ON appointment_proof.appointment
  (tenant_id,branch_id,doctor_membership_id,visit_date,slot_start)
  WHERE status IN ('BOOKED','CHECKED_IN');
CREATE INDEX ix_proof_patient_visits
  ON appointment_proof.appointment(tenant_id,patient_id,visit_date);

CREATE TABLE appointment_proof.event (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id uuid NOT NULL,
  appointment_id uuid NOT NULL REFERENCES appointment_proof.appointment(id)
    ON DELETE RESTRICT,
  revision integer NOT NULL,
  event_code varchar(12) NOT NULL CHECK
    (event_code IN ('BOOKED','RESCHEDULED','CHECKED_IN','CANCELLED')),
  actor_membership_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_proof_event_actor FOREIGN KEY (tenant_id,actor_membership_id)
    REFERENCES public.user_tenant_membership(tenant_id,id) ON DELETE RESTRICT,
  CONSTRAINT uq_proof_appointment_revision UNIQUE(appointment_id,revision)
);

CREATE FUNCTION appointment_proof.deny_event_change()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
BEGIN
 RAISE EXCEPTION 'Synthetic appointment event audit is append-only';
END;
$$;
CREATE TRIGGER event_immutable BEFORE UPDATE OR DELETE
 ON appointment_proof.event FOR EACH ROW
 EXECUTE FUNCTION appointment_proof.deny_event_change();

CREATE FUNCTION appointment_proof.authorize_and_version()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public,appointment_proof AS $$
DECLARE
  actor uuid;
BEGIN
 IF TG_OP='DELETE' THEN
   RAISE EXCEPTION 'Synthetic appointments cannot be deleted';
 END IF;
 actor := NEW.updated_by_membership_id;
 -- Verify CURRENT account, membership, branch and explicit permission at the
 -- time of every write. This proof still does not bind the actor to a real
 -- network session, so it CANNOT serve a production endpoint.
 IF NOT EXISTS (
   SELECT 1 FROM public.user_tenant_membership m
   JOIN public.app_user u ON u.id=m.user_id
   JOIN public.org_membership_branch_scope scope
        ON scope.tenant_id=m.tenant_id AND scope.membership_id=m.id
   JOIN public.auth_membership_role assigned
        ON assigned.tenant_id=m.tenant_id AND assigned.membership_id=m.id
   JOIN public.auth_role_permission perm
        ON perm.tenant_id=m.tenant_id AND perm.role_id=assigned.role_id
   WHERE m.id=actor AND m.tenant_id=NEW.tenant_id
     AND u.enabled AND m.enabled
     AND scope.branch_id=NEW.branch_id
     AND perm.permission_code='APPOINTMENT_BOOK'
 ) THEN
   RAISE EXCEPTION 'Synthetic appointment staff access denied';
 END IF;
 IF NOT EXISTS (
   SELECT 1 FROM public.module_registry registry
   JOIN public.tenant_module_state state ON state.module_code=registry.code
   WHERE registry.code='OPD' AND registry.implemented AND registry.released
     AND state.tenant_id=NEW.tenant_id AND state.entitled AND state.activated
     AND state.clinically_authorized
 ) THEN
   RAISE EXCEPTION 'Synthetic OPD entitlement disabled';
 END IF;
 IF NOT EXISTS (
   SELECT 1 FROM public.patient p
   JOIN public.patient_registration reg ON reg.tenant_id=p.tenant_id
      AND reg.patient_id=p.id AND reg.branch_id=NEW.branch_id
   WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.patient_id
     AND p.status='ACTIVE'
 ) THEN
   RAISE EXCEPTION 'Patient not registered at requested synthetic branch';
 END IF;
 IF NOT EXISTS (
   SELECT 1 FROM public.user_tenant_membership member
   JOIN public.app_user account ON account.id=member.user_id
   JOIN public.org_membership_branch_scope s
      ON s.tenant_id=member.tenant_id AND s.membership_id=member.id
   JOIN public.auth_membership_role mr
      ON mr.tenant_id=member.tenant_id AND mr.membership_id=member.id
   JOIN public.auth_role doctor_role
      ON doctor_role.tenant_id=member.tenant_id
      AND doctor_role.id=mr.role_id
   WHERE member.id=NEW.doctor_membership_id
      AND member.tenant_id=NEW.tenant_id AND member.enabled
      AND account.enabled AND s.branch_id=NEW.branch_id
      AND doctor_role.code='DOCTOR'
 ) THEN
   RAISE EXCEPTION 'Synthetic assigned doctor not authorized at branch';
 END IF;
 IF TG_OP='INSERT' THEN
   IF NEW.status <> 'BOOKED' OR NEW.revision <> 1
       OR NEW.booked_by_membership_id <> NEW.updated_by_membership_id THEN
     RAISE EXCEPTION 'New appointment must be a revision-one booking';
   END IF;
 ELSE
   IF NEW.id<>OLD.id OR NEW.tenant_id<>OLD.tenant_id
       OR NEW.branch_id<>OLD.branch_id OR NEW.patient_id<>OLD.patient_id
       OR NEW.booked_by_membership_id<>OLD.booked_by_membership_id
       OR NEW.created_at<>OLD.created_at THEN
     RAISE EXCEPTION 'Appointment patient/branch/identity is immutable';
   END IF;
   IF OLD.status <> 'BOOKED' OR NEW.revision <> OLD.revision + 1 THEN
     RAISE EXCEPTION 'Appointment status/revision conflict';
   END IF;
   IF NEW.status='BOOKED' THEN
     IF NEW.visit_date=OLD.visit_date AND NEW.slot_start=OLD.slot_start
       AND NEW.doctor_membership_id=OLD.doctor_membership_id THEN
       RAISE EXCEPTION 'Reschedule requires another doctor or slot';
     END IF;
   ELSIF NEW.status IN ('CHECKED_IN','CANCELLED') THEN
     IF NEW.visit_date<>OLD.visit_date OR NEW.slot_start<>OLD.slot_start
        OR NEW.doctor_membership_id<>OLD.doctor_membership_id THEN
       RAISE EXCEPTION 'Status transition must preserve booked slot';
     END IF;
   ELSE
     RAISE EXCEPTION 'Invalid appointment transition';
   END IF;
 END IF;
 NEW.updated_at := CURRENT_TIMESTAMP;
 RETURN NEW;
END;
$$;
CREATE TRIGGER appointment_write_guard BEFORE INSERT OR UPDATE OR DELETE
 ON appointment_proof.appointment FOR EACH ROW
 EXECUTE FUNCTION appointment_proof.authorize_and_version();

CREATE FUNCTION appointment_proof.append_event()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public,appointment_proof AS $$
DECLARE
 code varchar(12);
BEGIN
 IF TG_OP='INSERT' THEN code:='BOOKED';
 ELSIF NEW.status='BOOKED' THEN code:='RESCHEDULED';
 ELSE code:=NEW.status;
 END IF;
 INSERT INTO appointment_proof.event
   (tenant_id,appointment_id,revision,event_code,actor_membership_id)
 VALUES (NEW.tenant_id,NEW.id,NEW.revision,code,NEW.updated_by_membership_id);
 RETURN NEW;
END;
$$;
CREATE TRIGGER appointment_event AFTER INSERT OR UPDATE
 ON appointment_proof.appointment FOR EACH ROW
 EXECUTE FUNCTION appointment_proof.append_event();

-- Explicit read-only RLS proof. No patient identity table privileges.
ALTER TABLE appointment_proof.appointment ENABLE ROW LEVEL SECURITY;
ALTER TABLE appointment_proof.appointment FORCE ROW LEVEL SECURITY;
CREATE POLICY synthetic_reader_own_branch ON appointment_proof.appointment
  FOR SELECT TO aronexa_appointment_read_proof
  USING (
    tenant_id = public.aronexa_current_tenant_id()
    AND EXISTS (
      SELECT 1 FROM public.org_membership_branch_scope s
       WHERE s.tenant_id = appointment.tenant_id
         AND s.branch_id = appointment.branch_id
         AND s.membership_id =
           NULLIF(current_setting('aronexa.membership_id',true),'')::uuid
    )
  );
CREATE POLICY proof_branch_read ON public.org_membership_branch_scope
 FOR SELECT TO aronexa_appointment_read_proof
 USING (
   tenant_id=public.aronexa_current_tenant_id()
   AND membership_id =
       NULLIF(current_setting('aronexa.membership_id',true),'')::uuid
 );
GRANT SELECT ON public.org_membership_branch_scope
 TO aronexa_appointment_read_proof;
GRANT SELECT ON appointment_proof.appointment
 TO aronexa_appointment_read_proof;
-- No direct INSERT/UPDATE/DELETE and no audit SELECT privileges for runtime.
