-- COMM-V1-T11 — ALL IDENTIFIERS AND TEXT ARE FICTIONAL.
-- Execute ONLY with Run-Disposable-Pg-Recovery.sh in NEW Docker containers.
-- This intentionally is NOT the AroNexa production schema or migration.
\set ON_ERROR_STOP on
CREATE TABLE proof_schema_history (
  version text PRIMARY KEY, description text NOT NULL
);
INSERT INTO proof_schema_history VALUES ('SYN-V1','Synthetic recovery-rehearsal fixture');
CREATE TABLE clinic_patient (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, display_name text NOT NULL,
  CONSTRAINT ck_fictional_identity CHECK (display_name LIKE 'SYNTHETIC%')
);
CREATE UNIQUE INDEX uq_patient_tenant_id ON clinic_patient(tenant_id,id);
CREATE TABLE clinical_encounter (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, patient_id uuid NOT NULL,
  doctor_id uuid NOT NULL, status text NOT NULL,
  CONSTRAINT fk_recovery_patient FOREIGN KEY (tenant_id,patient_id)
    REFERENCES clinic_patient(tenant_id,id)
);
CREATE UNIQUE INDEX uq_encounter_tenant_id ON clinical_encounter(tenant_id,id);
CREATE TABLE rx_draft (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, encounter_id uuid NOT NULL,
  draft_revision integer NOT NULL CHECK (draft_revision>=1),
  content text NOT NULL CHECK (content LIKE 'SYNTHETIC%'),
  CONSTRAINT fk_rx_encounter FOREIGN KEY (tenant_id,encounter_id)
    REFERENCES clinical_encounter(tenant_id,id)
);
CREATE TABLE billing_ledger (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, encounter_id uuid NOT NULL,
  amount_paise integer NOT NULL CHECK (amount_paise > 0),
  kind text NOT NULL CHECK (kind IN ('SYNTHETIC_CHARGE','SYNTHETIC_PAYMENT')),
  CONSTRAINT fk_billing_encounter FOREIGN KEY (tenant_id,encounter_id)
    REFERENCES clinical_encounter(tenant_id,id)
);
CREATE TABLE encounter_audit (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, encounter_id uuid NOT NULL,
  revision integer NOT NULL, action text NOT NULL,
  CONSTRAINT fk_audit_encounter FOREIGN KEY (tenant_id,encounter_id)
    REFERENCES clinical_encounter(tenant_id,id)
);
-- Review-only RLS proof: no real authentication system is attached.
ALTER TABLE clinic_patient ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinic_patient FORCE ROW LEVEL SECURITY;
ALTER TABLE clinical_encounter ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinical_encounter FORCE ROW LEVEL SECURITY;
CREATE POLICY synthetic_patient_scope ON clinic_patient
  FOR SELECT TO aronexa_proof_reader
  USING (tenant_id = NULLIF(current_setting('aronexa.tenant_id',true),'')::uuid);
CREATE POLICY synthetic_encounter_scope ON clinical_encounter
  FOR SELECT TO aronexa_proof_reader
  USING (tenant_id = NULLIF(current_setting('aronexa.tenant_id',true),'')::uuid);
GRANT USAGE ON SCHEMA public TO aronexa_proof_reader;
GRANT SELECT ON clinic_patient,clinical_encounter TO aronexa_proof_reader;

INSERT INTO clinic_patient VALUES
 ('11111111-1111-1111-1111-111111111111','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','SYNTHETIC PATIENT A'),
 ('22222222-2222-2222-2222-222222222222','bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','SYNTHETIC PATIENT B');
INSERT INTO clinical_encounter VALUES
 ('33333333-3333-3333-3333-333333333333','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  '11111111-1111-1111-1111-111111111111','55555555-5555-5555-5555-555555555555','SYNTHETIC_DRAFT'),
 ('44444444-4444-4444-4444-444444444444','bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  '22222222-2222-2222-2222-222222222222','66666666-6666-6666-6666-666666666666','SYNTHETIC_DRAFT');
INSERT INTO rx_draft VALUES
 ('77777777-7777-7777-7777-777777777777','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
 '33333333-3333-3333-3333-333333333333',1,'SYNTHETIC EXAMPLE DRAFT ONLY');
INSERT INTO billing_ledger VALUES
 ('88888888-8888-8888-8888-888888888888','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
 '33333333-3333-3333-3333-333333333333',12345,'SYNTHETIC_CHARGE');
INSERT INTO encounter_audit VALUES
 ('99999999-9999-9999-9999-999999999999','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
 '33333333-3333-3333-3333-333333333333',1,'SYNTHETIC_DRAFT_CREATED');
