-- R1-07: structured Smart Rx draft, TEST-ONLY disposable Testcontainers PG17.
-- NEVER run against Windows clinic/pilot/SaaS/production PostgreSQL.
-- No finalization, clinical signature, PDF, pharmacy or medication advice.
-- No runtime role grants; the privileged test migrator only is used.

INSERT INTO public.auth_permission(code,description) VALUES
 ('OPD_RX_DRAFT_WRITE','Doctor-only synthetic prescription draft read/write')
ON CONFLICT (code) DO NOTHING;

-- Every update of a draft creates an immutable new full snapshot, including
-- its exact source consultation revision. No UPDATE/DELETE is permitted.
CREATE TABLE appointment_proof.rx_draft_revision (
  appointment_id UUID NOT NULL,
  tenant_id UUID NOT NULL,
  branch_id UUID NOT NULL,
  patient_id UUID NOT NULL,
  doctor_membership_id UUID NOT NULL,
  revision INTEGER NOT NULL CHECK(revision BETWEEN 1 AND 1000000000),
  consultation_revision INTEGER NOT NULL CHECK(consultation_revision>=1),
  actor_membership_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(appointment_id,revision),
  CONSTRAINT fk_r107_exact_appointment FOREIGN KEY (
    appointment_id,tenant_id,branch_id,patient_id,doctor_membership_id)
    REFERENCES appointment_proof.appointment
      (id,tenant_id,branch_id,patient_id,doctor_membership_id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_r107_exact_consultation_revision FOREIGN KEY (
    appointment_id,consultation_revision)
    REFERENCES appointment_proof.consultation_revision(appointment_id,revision)
    ON DELETE RESTRICT,
  CONSTRAINT fk_r107_actor FOREIGN KEY(tenant_id,actor_membership_id)
    REFERENCES public.user_tenant_membership(tenant_id,id) ON DELETE RESTRICT,
  CONSTRAINT ck_r107_author_is_doctor
    CHECK(actor_membership_id=doctor_membership_id)
);
CREATE TRIGGER r107_rx_revision_immutable
  BEFORE UPDATE OR DELETE ON appointment_proof.rx_draft_revision
  FOR EACH ROW EXECUTE FUNCTION appointment_proof.deny_event_change();

-- Medicines are stored as normalized snapshot lines: no JSON deserialization,
-- no unrelated patient identifier, and no medication suggestions.
CREATE TABLE appointment_proof.rx_draft_medicine (
  appointment_id UUID NOT NULL,
  revision INTEGER NOT NULL,
  line_order SMALLINT NOT NULL CHECK(line_order BETWEEN 1 AND 30),
  entry_id UUID NOT NULL,
  medicine VARCHAR(120) NOT NULL CHECK(length(btrim(medicine))>0),
  strength VARCHAR(80) NOT NULL CHECK(length(btrim(strength))>0),
  form VARCHAR(50) NOT NULL CHECK(length(btrim(form))>0),
  dose VARCHAR(120) NOT NULL CHECK(length(btrim(dose))>0),
  route VARCHAR(60) NOT NULL CHECK(length(btrim(route))>0),
  frequency VARCHAR(100) NOT NULL CHECK(length(btrim(frequency))>0),
  duration VARCHAR(100) NOT NULL CHECK(length(btrim(duration))>0),
  quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 10000),
  instructions VARCHAR(500) NOT NULL DEFAULT '',
  PRIMARY KEY(appointment_id,revision,line_order),
  UNIQUE(appointment_id,revision,entry_id),
  CONSTRAINT fk_r107_rx_snapshot FOREIGN KEY(appointment_id,revision)
    REFERENCES appointment_proof.rx_draft_revision(appointment_id,revision)
    ON DELETE RESTRICT
);
CREATE TRIGGER r107_rx_medicine_immutable
  BEFORE UPDATE OR DELETE ON appointment_proof.rx_draft_medicine
  FOR EACH ROW EXECUTE FUNCTION appointment_proof.deny_event_change();

-- Every successful read of a saved draft records who accessed the snapshot.
-- Never store medicine names, free text or notes in this audit log.
CREATE TABLE appointment_proof.rx_draft_read_audit (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  appointment_id UUID NOT NULL
    REFERENCES appointment_proof.appointment(id) ON DELETE RESTRICT,
  tenant_id UUID NOT NULL,
  branch_id UUID NOT NULL,
  actor_membership_id UUID NOT NULL,
  accessed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_r107_rx_read_actor FOREIGN KEY(tenant_id,actor_membership_id)
    REFERENCES public.user_tenant_membership(tenant_id,id)
    ON DELETE RESTRICT
);
CREATE TRIGGER r107_rx_read_immutable
  BEFORE UPDATE OR DELETE ON appointment_proof.rx_draft_read_audit
  FOR EACH ROW EXECUTE FUNCTION appointment_proof.deny_event_change();

REVOKE ALL ON appointment_proof.rx_draft_revision,
  appointment_proof.rx_draft_medicine,
  appointment_proof.rx_draft_read_audit FROM PUBLIC;
