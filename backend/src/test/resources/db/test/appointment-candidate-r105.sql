-- R1-05: opt-in disposable PostgreSQL appointment management and daily queue.
-- This script is installed ONLY inside the R1-04/R1-05 Testcontainers class.
-- NEVER migrate a customer, local Windows, or production database with it.
-- Extends existing T14/R1-04 audit/slot/revision proof, not immutable V1-V9 Flyway.
INSERT INTO public.auth_permission(code, description) VALUES
 ('APPOINTMENT_RESCHEDULE','Change an authorized booked visit in disposable test proof'),
 ('APPOINTMENT_CANCEL','Cancel an authorized booked visit in disposable test proof')
ON CONFLICT(code) DO NOTHING;

-- Cancelled appointments remain in the audit history but are absent from the active queue.
CREATE INDEX ix_r105_active_daily_queue
  ON appointment_proof.appointment
     (tenant_id, branch_id, visit_date, slot_start, id)
  WHERE status IN ('BOOKED','CHECKED_IN');

-- No additional runtime grants. Audit/event data remain append-only.
