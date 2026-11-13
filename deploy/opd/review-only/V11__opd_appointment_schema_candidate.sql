-- R1-10B1 REVIEW-ONLY PostgreSQL 17 appointment schema candidate.
-- NOT in backend/src/main/resources/db/migration; Flyway does NOT load it.
-- Run ONLY in an automatically disposable PostgreSQL Testcontainer after V1-V9.
-- Future migration depends on unapproved registration V10 / original DB hold.
-- This script DOES NOT create a SQL LOGIN, role GRANT, HTTP endpoint, or service.
-- The future authenticated booking writer MUST recheck current session, role,
-- branch, doctor, OPD entitlement and registration INSIDE its own transaction.
-- A client-selected tenant or a forged RLS GUC is NEVER sufficient authority.

CREATE SCHEMA opd_candidate;
REVOKE ALL ON SCHEMA opd_candidate FROM PUBLIC;

-- Composite registration identity; the V9 registration has tenant+branch+id.
ALTER TABLE public.patient_registration
  ADD CONSTRAINT uq_opd_candidate_registration_patient
  UNIQUE (tenant_id,branch_id,id,patient_id);

CREATE TABLE opd_candidate.appointment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  branch_id uuid NOT NULL,
  patient_id uuid NOT NULL,
  registration_id uuid NOT NULL,
  doctor_membership_id uuid NOT NULL,
  booking_request_id uuid NOT NULL,
  booked_by_membership_id uuid NOT NULL,
  updated_by_membership_id uuid NOT NULL,
  visit_date date NOT NULL
    CHECK (visit_date BETWEEN DATE '2000-01-01' AND DATE '2100-12-31'),
  slot_start time(0) without time zone NOT NULL
    CHECK (slot_start BETWEEN TIME '09:00' AND TIME '16:45'
      AND EXTRACT(MINUTE FROM slot_start)::int % 15 = 0
      AND EXTRACT(SECOND FROM slot_start)::int = 0),
  status varchar(12) NOT NULL
    CHECK (status IN ('BOOKED','CHECKED_IN','CANCELLED')),
  revision integer NOT NULL CHECK (revision >= 1),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_opd_candidate_visit_scope UNIQUE (id,tenant_id,branch_id),
  CONSTRAINT uq_opd_candidate_replay UNIQUE (tenant_id,booking_request_id),
  CONSTRAINT fk_opd_candidate_branch FOREIGN KEY(tenant_id,branch_id)
    REFERENCES public.branch(tenant_id,id) ON DELETE RESTRICT,
  CONSTRAINT fk_opd_candidate_registered_patient FOREIGN KEY(
      tenant_id,branch_id,registration_id,patient_id)
    REFERENCES public.patient_registration(tenant_id,branch_id,id,patient_id)
      ON DELETE RESTRICT,
  CONSTRAINT fk_opd_candidate_doctor FOREIGN KEY(
      tenant_id,doctor_membership_id)
    REFERENCES public.user_tenant_membership(tenant_id,id)
      ON DELETE RESTRICT,
  CONSTRAINT fk_opd_candidate_booker FOREIGN KEY(
      tenant_id,booked_by_membership_id)
    REFERENCES public.user_tenant_membership(tenant_id,id)
      ON DELETE RESTRICT,
  CONSTRAINT fk_opd_candidate_updater FOREIGN KEY(
      tenant_id,updated_by_membership_id)
    REFERENCES public.user_tenant_membership(tenant_id,id)
      ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uq_opd_candidate_live_doctor_slot
  ON opd_candidate.appointment
  (tenant_id,branch_id,doctor_membership_id,visit_date,slot_start)
  WHERE status IN ('BOOKED','CHECKED_IN');
CREATE INDEX ix_opd_candidate_branch_queue
  ON opd_candidate.appointment (tenant_id,branch_id,visit_date,slot_start);
CREATE INDEX ix_opd_candidate_patient_history
  ON opd_candidate.appointment (tenant_id,patient_id,visit_date);

CREATE TABLE opd_candidate.appointment_event (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id uuid NOT NULL,
  branch_id uuid NOT NULL,
  appointment_id uuid NOT NULL,
  revision integer NOT NULL CHECK (revision >= 1),
  action varchar(14) NOT NULL
    CHECK (action IN ('BOOKED','RESCHEDULED','CHECKED_IN','CANCELLED')),
  actor_membership_id uuid NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_opd_candidate_event_revision
    UNIQUE (appointment_id,revision),
  CONSTRAINT fk_opd_candidate_event_appointment FOREIGN KEY
    (appointment_id,tenant_id,branch_id)
    REFERENCES opd_candidate.appointment(id,tenant_id,branch_id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_opd_candidate_event_actor FOREIGN KEY
    (tenant_id,actor_membership_id)
    REFERENCES public.user_tenant_membership(tenant_id,id)
    ON DELETE RESTRICT
);

CREATE FUNCTION opd_candidate.deny_event_change()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp AS $$
BEGIN
  RAISE EXCEPTION 'OPD_APPOINTMENT_EVENT_IMMUTABLE';
END;
$$;
CREATE TRIGGER opd_candidate_event_immutable
  BEFORE UPDATE OR DELETE ON opd_candidate.appointment_event
  FOR EACH ROW EXECUTE FUNCTION opd_candidate.deny_event_change();

-- Structural transition checks do NOT authenticate the writer.
-- A dedicated least-privilege SQL command with live bearer/session proof,
-- RLS and role/branch/module revocation ordering is still mandatory.
CREATE FUNCTION opd_candidate.validate_visit_transition()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp AS $$
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'OPD_APPOINTMENT_DELETE_DENIED';
  END IF;
  IF TG_OP='INSERT' THEN
    IF NEW.status<>'BOOKED' OR NEW.revision<>1
       OR NEW.booked_by_membership_id<>NEW.updated_by_membership_id THEN
      RAISE EXCEPTION 'OPD_INVALID_INITIAL_BOOKING';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.id<>OLD.id OR NEW.tenant_id<>OLD.tenant_id
    OR NEW.branch_id<>OLD.branch_id OR NEW.patient_id<>OLD.patient_id
    OR NEW.registration_id<>OLD.registration_id
    OR NEW.booking_request_id<>OLD.booking_request_id
    OR NEW.booked_by_membership_id<>OLD.booked_by_membership_id
    OR NEW.created_at<>OLD.created_at THEN
    RAISE EXCEPTION 'OPD_VISIT_IDENTITY_IMMUTABLE';
  END IF;
  IF OLD.status<>'BOOKED' OR NEW.revision<>OLD.revision+1 THEN
    RAISE EXCEPTION 'OPD_VISIT_REVISION_CONFLICT';
  END IF;
  IF NEW.status='BOOKED' THEN
    IF NEW.visit_date=OLD.visit_date AND NEW.slot_start=OLD.slot_start
      AND NEW.doctor_membership_id=OLD.doctor_membership_id THEN
      RAISE EXCEPTION 'OPD_RESCHEDULE_MUST_CHANGE_SLOT';
    END IF;
  ELSIF NEW.status IN ('CHECKED_IN','CANCELLED') THEN
    IF NEW.visit_date<>OLD.visit_date OR NEW.slot_start<>OLD.slot_start
       OR NEW.doctor_membership_id<>OLD.doctor_membership_id THEN
      RAISE EXCEPTION 'OPD_STATUS_CHANGE_MUST_KEEP_SLOT';
    END IF;
  ELSE
    RAISE EXCEPTION 'OPD_VISIT_TRANSITION_DENIED';
  END IF;
  NEW.updated_at:=CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$;
CREATE TRIGGER opd_candidate_visit_guard
  BEFORE INSERT OR UPDATE OR DELETE ON opd_candidate.appointment
  FOR EACH ROW EXECUTE FUNCTION opd_candidate.validate_visit_transition();

CREATE FUNCTION opd_candidate.write_visit_event()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp AS $$
DECLARE event_code varchar(14);
BEGIN
  IF TG_OP='INSERT' THEN event_code:='BOOKED';
  ELSIF NEW.status='BOOKED' THEN event_code:='RESCHEDULED';
  ELSE event_code:=NEW.status;
  END IF;
  INSERT INTO opd_candidate.appointment_event
    (tenant_id,branch_id,appointment_id,revision,action,actor_membership_id)
  VALUES (NEW.tenant_id,NEW.branch_id,NEW.id,NEW.revision,
          event_code,NEW.updated_by_membership_id);
  RETURN NEW;
END;
$$;
CREATE TRIGGER opd_candidate_visit_event
  AFTER INSERT OR UPDATE ON opd_candidate.appointment
  FOR EACH ROW EXECUTE FUNCTION opd_candidate.write_visit_event();

-- FORCE RLS is deliberate; no production SQL login has been granted rights.
-- Final policies must authorize exact session/membership/branch/OPD rights
-- under independently reviewed app+DB proof; these tables deny by default.
ALTER TABLE opd_candidate.appointment ENABLE ROW LEVEL SECURITY;
ALTER TABLE opd_candidate.appointment FORCE ROW LEVEL SECURITY;
ALTER TABLE opd_candidate.appointment_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE opd_candidate.appointment_event FORCE ROW LEVEL SECURITY;
REVOKE ALL ON ALL TABLES IN SCHEMA opd_candidate FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA opd_candidate FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA opd_candidate FROM PUBLIC;
