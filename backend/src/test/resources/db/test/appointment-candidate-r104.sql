-- R1-04 disposable-PostgreSQL-only augmentation of the existing T14 proof.
-- Installed ONLY by AppointmentCandidateHttpAcceptanceTests in a Testcontainer.
-- NOT a production Flyway migration, NOT authorized for Windows/customer DB.
--
-- Existing appointment_proof SQL supplies doctor-slot uniqueness, optimistic
-- versioning, actor/branch/permission/module checks, and append-only events.
-- The following links the appointment to an exact V9 branch registration
-- and makes a client-generated UUID a bounded replay/idempotency identity.

ALTER TABLE appointment_proof.appointment
  ADD COLUMN registration_id uuid NOT NULL,
  ADD COLUMN booking_request_id uuid NOT NULL;
ALTER TABLE public.patient_registration
  ADD CONSTRAINT uq_r104_registration_patient
  UNIQUE (tenant_id,branch_id,id,patient_id);
ALTER TABLE appointment_proof.appointment
  ADD CONSTRAINT fk_r104_exact_registration
    FOREIGN KEY (tenant_id,branch_id,registration_id,patient_id)
    REFERENCES public.patient_registration(tenant_id,branch_id,id,patient_id)
    ON DELETE RESTRICT,
  ADD CONSTRAINT uq_r104_booking_request UNIQUE(tenant_id,booking_request_id);

-- No direct appointment writes are granted to any runtime SQL login.
-- Every write in this fixture uses an isolated test-only JDBC transaction
-- after re-checking the current bearer session at the database boundary.
-- Privileged test-datasource usage is NOT an approved production design.

CREATE TABLE appointment_proof.read_audit (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id uuid NOT NULL,
  branch_id uuid NOT NULL,
  appointment_id uuid NOT NULL,
  actor_membership_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_r104_read_appt FOREIGN KEY(appointment_id)
    REFERENCES appointment_proof.appointment(id) ON DELETE RESTRICT,
  CONSTRAINT fk_r104_read_actor FOREIGN KEY(tenant_id,actor_membership_id)
    REFERENCES public.user_tenant_membership(tenant_id,id) ON DELETE RESTRICT
);
CREATE TRIGGER r104_read_audit_immutable
  BEFORE UPDATE OR DELETE ON appointment_proof.read_audit
  FOR EACH ROW EXECUTE FUNCTION appointment_proof.deny_event_change();
REVOKE ALL ON appointment_proof.appointment, appointment_proof.event,
  appointment_proof.read_audit FROM PUBLIC;
