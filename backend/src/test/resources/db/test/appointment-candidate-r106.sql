-- R1-06 test-only doctor consultation draft (not signed/finalized EMR).
-- INSTALLED ONLY in the existing automatically destroyed PostgreSQL 17
-- Testcontainers appointment candidate. Never install on customer/Windows DB.
-- All note texts in this fixture are FICTIONAL. No production role grants.

INSERT INTO public.auth_permission(code,description)
VALUES ('OPD_DRAFT_WRITE','Doctor-only test consultation draft read/write')
ON CONFLICT(code) DO NOTHING;

-- The appointment is the provisional encounter reference for this test
-- increment ONLY. The real production encounter identity is a future design.
ALTER TABLE appointment_proof.appointment
  ADD CONSTRAINT uq_r106_appointment_full_scope
  UNIQUE (id,tenant_id,branch_id,patient_id,doctor_membership_id);

-- Each save is an INSERT, never an UPDATE. Revision 1..n is immutable and
-- retains fictional prior note revisions for audit/recovery. These revisions
-- do not constitute a signed, finalized or legally validated medical record.
CREATE TABLE appointment_proof.consultation_revision (
  appointment_id UUID NOT NULL,
  tenant_id UUID NOT NULL,
  branch_id UUID NOT NULL,
  patient_id UUID NOT NULL,
  doctor_membership_id UUID NOT NULL,
  revision INTEGER NOT NULL CHECK (revision>=1 AND revision<=1000000000),
  consultation_note TEXT NOT NULL
    CHECK (char_length(consultation_note) BETWEEN 1 AND 2000
      AND char_length(btrim(consultation_note))>0),
  actor_membership_id UUID NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (appointment_id,revision),
  CONSTRAINT fk_r106_consultation_appointment FOREIGN KEY (
    appointment_id,tenant_id,branch_id,patient_id,doctor_membership_id)
    REFERENCES appointment_proof.appointment
      (id,tenant_id,branch_id,patient_id,doctor_membership_id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_r106_consultation_doctor FOREIGN KEY
    (tenant_id,actor_membership_id)
    REFERENCES public.user_tenant_membership(tenant_id,id)
    ON DELETE RESTRICT,
  CONSTRAINT ck_r106_actor_is_assigned_doctor
    CHECK (actor_membership_id=doctor_membership_id)
);
CREATE TRIGGER r106_revision_immutable
  BEFORE UPDATE OR DELETE ON appointment_proof.consultation_revision
  FOR EACH ROW EXECUTE FUNCTION appointment_proof.deny_event_change();

CREATE TABLE appointment_proof.consultation_read_audit (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  appointment_id UUID NOT NULL
    REFERENCES appointment_proof.appointment(id) ON DELETE RESTRICT,
  tenant_id UUID NOT NULL,
  branch_id UUID NOT NULL,
  actor_membership_id UUID NOT NULL,
  accessed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_r106_read_actor FOREIGN KEY(tenant_id,actor_membership_id)
    REFERENCES public.user_tenant_membership(tenant_id,id)
    ON DELETE RESTRICT
);
CREATE TRIGGER r106_read_audit_immutable
  BEFORE UPDATE OR DELETE ON appointment_proof.consultation_read_audit
  FOR EACH ROW EXECUTE FUNCTION appointment_proof.deny_event_change();

-- Prove no runtime direct grants are added to clinical note tables.
REVOKE ALL ON appointment_proof.consultation_revision,
  appointment_proof.consultation_read_audit FROM PUBLIC;
