-- COMM-V1-T07: disposable PostgreSQL Testcontainers ONLY.
-- NEVER add this to src/main/resources/db/migration or run against original Windows DB.
-- This deliberately independent proof role is not provisioned for customers.
CREATE ROLE aronexa_encounter_proof LOGIN
    PASSWORD 'only-for-disposable-encounter-test'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;

CREATE SCHEMA encounter_proof;
REVOKE ALL ON SCHEMA encounter_proof FROM PUBLIC;
GRANT USAGE ON SCHEMA encounter_proof TO aronexa_encounter_proof;

CREATE TABLE encounter_proof.encounter_draft (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    doctor_membership_id UUID NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision BETWEEN 0 AND 1000000000),
    stage VARCHAR(24) NOT NULL DEFAULT 'CHECKED_IN'
        CHECK (stage IN ('CHECKED_IN','CONSULTATION_DRAFT','RX_DRAFT_LINKED','BILL_DRAFT_LINKED')),
    note VARCHAR(2000) NOT NULL DEFAULT '',
    rx_draft_ref UUID,
    bill_draft_ref UUID,
    CONSTRAINT uq_encounter_proof_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_encounter_proof_patient FOREIGN KEY (tenant_id,patient_id)
        REFERENCES public.patient(tenant_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_encounter_proof_branch FOREIGN KEY (tenant_id,branch_id)
        REFERENCES public.branch(tenant_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_encounter_proof_doctor FOREIGN KEY (tenant_id,doctor_membership_id)
        REFERENCES public.user_tenant_membership(tenant_id,id) ON DELETE RESTRICT,
    CONSTRAINT ck_encounter_proof_stage CHECK (
       (stage='CHECKED_IN' AND revision=0 AND length(btrim(note))=0
           AND rx_draft_ref IS NULL AND bill_draft_ref IS NULL) OR
       (stage='CONSULTATION_DRAFT' AND revision>0 AND length(btrim(note))>0
           AND rx_draft_ref IS NULL AND bill_draft_ref IS NULL) OR
       (stage='RX_DRAFT_LINKED' AND revision>0 AND length(btrim(note))>0
           AND rx_draft_ref IS NOT NULL AND bill_draft_ref IS NULL) OR
       (stage='BILL_DRAFT_LINKED' AND revision>0 AND length(btrim(note))>0
           AND rx_draft_ref IS NOT NULL AND bill_draft_ref IS NOT NULL))
);
CREATE INDEX ix_encounter_proof_tenant_patient
    ON encounter_proof.encounter_draft(tenant_id,patient_id,branch_id);

ALTER TABLE encounter_proof.encounter_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE encounter_proof.encounter_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY encounter_proof_doctor_scope ON encounter_proof.encounter_draft
    FOR ALL TO aronexa_encounter_proof
    USING (
        tenant_id = NULLIF(current_setting('aronexa.tenant_id', true),'')::uuid
        AND branch_id = NULLIF(current_setting('aronexa.branch_id', true),'')::uuid
        AND doctor_membership_id =
            NULLIF(current_setting('aronexa.doctor_membership_id', true),'')::uuid
    )
    WITH CHECK (
        tenant_id = NULLIF(current_setting('aronexa.tenant_id', true),'')::uuid
        AND branch_id = NULLIF(current_setting('aronexa.branch_id', true),'')::uuid
        AND doctor_membership_id =
            NULLIF(current_setting('aronexa.doctor_membership_id', true),'')::uuid
    );

-- Scoped column grants protect immutable identity linkage from direct UPDATE.
GRANT SELECT, INSERT ON encounter_proof.encounter_draft TO aronexa_encounter_proof;
GRANT UPDATE (stage,revision,note,rx_draft_ref,bill_draft_ref)
    ON encounter_proof.encounter_draft TO aronexa_encounter_proof;

-- Metadata-only audit proof: no note/body/demographics columns and no mutation grant.
CREATE TABLE encounter_proof.encounter_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    doctor_membership_id UUID NOT NULL,
    encounter_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision BETWEEN 0 AND 1000000000),
    action VARCHAR(28) NOT NULL CHECK (action IN ('OPEN','CONSULT_DRAFT','LINK_RX','LINK_BILL')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_encounter_proof_event FOREIGN KEY (tenant_id,encounter_id)
        REFERENCES encounter_proof.encounter_draft(tenant_id,id) ON DELETE RESTRICT
);
ALTER TABLE encounter_proof.encounter_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE encounter_proof.encounter_event FORCE ROW LEVEL SECURITY;
CREATE POLICY encounter_proof_event_scope ON encounter_proof.encounter_event
    FOR ALL TO aronexa_encounter_proof
    USING (
        tenant_id = NULLIF(current_setting('aronexa.tenant_id', true),'')::uuid
        AND branch_id = NULLIF(current_setting('aronexa.branch_id', true),'')::uuid
        AND doctor_membership_id =
            NULLIF(current_setting('aronexa.doctor_membership_id', true),'')::uuid
    )
    WITH CHECK (
        tenant_id = NULLIF(current_setting('aronexa.tenant_id', true),'')::uuid
        AND branch_id = NULLIF(current_setting('aronexa.branch_id', true),'')::uuid
        AND doctor_membership_id =
            NULLIF(current_setting('aronexa.doctor_membership_id', true),'')::uuid
    );
GRANT SELECT, INSERT ON encounter_proof.encounter_event TO aronexa_encounter_proof;
-- UPDATE, DELETE, TRUNCATE, ALTER and role escalation are never granted.
