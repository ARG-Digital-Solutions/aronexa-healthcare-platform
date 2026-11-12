-- R1-08: TEST-ONLY OPD consultation billing and daily collection ledger.
-- Installed only by disposable PostgreSQL 17 Testcontainers fixture.
-- NEVER install on a Windows clinic DB, SaaS database or production system.
-- No statutory tax invoice, valid receipt, verified payment or refund.

INSERT INTO public.auth_permission(code,description) VALUES
 ('OPD_BILL_CREATE','Create fictional consultation bill in disposable test DB'),
 ('OPD_BILL_READ','Read authorized fictional OPD bill'),
 ('OPD_PAYMENT_POST','Record a fictional cash collection event'),
 ('OPD_DAILY_REPORT_READ','Read branch-scoped fictional daily collections')
ON CONFLICT(code) DO NOTHING;

-- Fixture seeds authorized fees; create API does not accept client-set amounts.
CREATE TABLE appointment_proof.fee_schedule (
 tenant_id UUID NOT NULL,
 branch_id UUID NOT NULL,
 service_code VARCHAR(40) NOT NULL,
 service_label VARCHAR(100) NOT NULL CHECK(length(btrim(service_label))>0),
 amount_paise BIGINT NOT NULL CHECK(amount_paise BETWEEN 1 AND 10000000000),
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 PRIMARY KEY(tenant_id,branch_id,service_code)
);

CREATE TABLE appointment_proof.opd_invoice (
 id UUID PRIMARY KEY,
 invoice_number BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
 invoice_request_id UUID NOT NULL,
 tenant_id UUID NOT NULL,
 branch_id UUID NOT NULL,
 appointment_id UUID NOT NULL,
 patient_id UUID NOT NULL,
 doctor_membership_id UUID NOT NULL,
 service_code VARCHAR(40) NOT NULL,
 service_label VARCHAR(100) NOT NULL,
 total_paise BIGINT NOT NULL CHECK(total_paise BETWEEN 1 AND 10000000000),
 actor_membership_id UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uq_r108_invoice_appointment UNIQUE(tenant_id,branch_id,appointment_id),
 CONSTRAINT uq_r108_invoice_request UNIQUE(tenant_id,invoice_request_id),
 CONSTRAINT fk_r108_exact_visit FOREIGN KEY(
  appointment_id,tenant_id,branch_id,patient_id,doctor_membership_id)
 REFERENCES appointment_proof.appointment
  (id,tenant_id,branch_id,patient_id,doctor_membership_id)
 ON DELETE RESTRICT,
 CONSTRAINT fk_r108_bill_actor FOREIGN KEY(tenant_id,actor_membership_id)
 REFERENCES public.user_tenant_membership(tenant_id,id) ON DELETE RESTRICT,
 CONSTRAINT fk_r108_bill_fee FOREIGN KEY(tenant_id,branch_id,service_code)
 REFERENCES appointment_proof.fee_schedule(tenant_id,branch_id,service_code)
 ON DELETE RESTRICT
);
CREATE TRIGGER r108_invoice_immutable
 BEFORE UPDATE OR DELETE ON appointment_proof.opd_invoice
 FOR EACH ROW EXECUTE FUNCTION appointment_proof.deny_event_change();

-- Append-only event. No actual money movement occurs in this test fixture.
-- Payment request UUID uniquely identifies an idempotent POST attempt.
CREATE TABLE appointment_proof.opd_cash_event (
 id UUID PRIMARY KEY,
 payment_reference BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
 tenant_id UUID NOT NULL,
 branch_id UUID NOT NULL,
 invoice_id UUID NOT NULL REFERENCES appointment_proof.opd_invoice(id)
   ON DELETE RESTRICT,
 payment_request_id UUID NOT NULL,
 amount_paise BIGINT NOT NULL CHECK(amount_paise BETWEEN 1 AND 10000000000),
 actor_membership_id UUID NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uq_r108_payment_request UNIQUE(tenant_id,payment_request_id),
 CONSTRAINT fk_r108_cash_actor FOREIGN KEY(tenant_id,actor_membership_id)
 REFERENCES public.user_tenant_membership(tenant_id,id) ON DELETE RESTRICT
);
CREATE TRIGGER r108_cash_event_immutable
 BEFORE UPDATE OR DELETE ON appointment_proof.opd_cash_event
 FOR EACH ROW EXECUTE FUNCTION appointment_proof.deny_event_change();

-- Read-audit stores identifiers only; no notes or patient demographics.
CREATE TABLE appointment_proof.opd_billing_read_audit (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 tenant_id UUID NOT NULL,
 branch_id UUID NOT NULL,
 actor_membership_id UUID NOT NULL,
 appointment_id UUID,
 report_day DATE,
 accessed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT fk_r108_bill_read_actor FOREIGN KEY(tenant_id,actor_membership_id)
 REFERENCES public.user_tenant_membership(tenant_id,id) ON DELETE RESTRICT,
 CONSTRAINT ck_r108_one_read_scope CHECK (
  (appointment_id IS NOT NULL AND report_day IS NULL)
  OR (appointment_id IS NULL AND report_day IS NOT NULL))
);
CREATE TRIGGER r108_bill_read_audit_immutable
 BEFORE UPDATE OR DELETE ON appointment_proof.opd_billing_read_audit
 FOR EACH ROW EXECUTE FUNCTION appointment_proof.deny_event_change();

CREATE INDEX ix_r108_invoice_branch_day ON appointment_proof.opd_invoice
 (tenant_id,branch_id,created_at);
CREATE INDEX ix_r108_cash_branch_day ON appointment_proof.opd_cash_event
 (tenant_id,branch_id,recorded_at);
CREATE INDEX ix_r108_cash_invoice ON appointment_proof.opd_cash_event
 (invoice_id,tenant_id);

REVOKE ALL ON appointment_proof.fee_schedule,
 appointment_proof.opd_invoice,appointment_proof.opd_cash_event,
 appointment_proof.opd_billing_read_audit FROM PUBLIC;
