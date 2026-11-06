-- P01-T07-B2 W2C: Synthetic-only restored-V9 disposable PostgreSQL rehearsal.
-- EXPECT the Windows wrapper to verify target isolation before piping this SQL.
-- All synthetic fixture writes occur inside BEGIN...ROLLBACK; no COMMIT.
-- ON_ERROR_STOP=1 MUST be set by the wrapper. Do NOT run against original.
\set QUIET on
\set ON_ERROR_STOP on
BEGIN;

-- Capture uniquely generated fixture IDs privately in this psql session.
SELECT
  gen_random_uuid() AS ta, gen_random_uuid() AS tb,
  gen_random_uuid() AS ua, gen_random_uuid() AS ua2,
  gen_random_uuid() AS ua3, gen_random_uuid() AS ub,
  gen_random_uuid() AS ma, gen_random_uuid() AS ma2,
  gen_random_uuid() AS ma3, gen_random_uuid() AS mb,
  gen_random_uuid() AS fa, gen_random_uuid() AS fa2, gen_random_uuid() AS fb,
  gen_random_uuid() AS ba, gen_random_uuid() AS ba2, gen_random_uuid() AS bb,
  gen_random_uuid() AS pa, gen_random_uuid() AS pb,
  gen_random_uuid() AS ra, gen_random_uuid() AS ra2, gen_random_uuid() AS rb
\gset

-- Reuse the accepted Testcontainers' minimal synthetic schema fixture.
INSERT INTO public.tenant(id,code,display_name)
VALUES (:'ta','w2c_ta_'||left(:'ta',8),'W2C synthetic tenant A'),
       (:'tb','w2c_tb_'||left(:'tb',8),'W2C synthetic tenant B');

INSERT INTO public.app_user(id,username,password_hash)
VALUES (:'ua','w2c_a_'||left(:'ua',8),'synthetic-no-login'),
       (:'ua2','w2c_a2_'||left(:'ua2',8),'synthetic-no-login'),
       (:'ua3','w2c_a3_'||left(:'ua3',8),'synthetic-no-login'),
       (:'ub','w2c_b_'||left(:'ub',8),'synthetic-no-login');

INSERT INTO public.user_tenant_membership(id,user_id,tenant_id)
VALUES (:'ma',:'ua',:'ta'), (:'ma2',:'ua2',:'ta'),
       (:'ma3',:'ua3',:'ta'), (:'mb',:'ub',:'tb');

INSERT INTO public.facility(id,tenant_id,code,display_name,facility_kind)
VALUES (:'fa',:'ta','fa_'||left(:'fa',8),'W2C synthetic A facility','CLINIC'),
       (:'fa2',:'ta','fa_'||left(:'fa2',8),'W2C synthetic A other facility','CLINIC'),
       (:'fb',:'tb','fb_'||left(:'fb',8),'W2C synthetic B facility','CLINIC');

INSERT INTO public.branch(id,tenant_id,facility_id,code,display_name)
VALUES (:'ba',:'ta',:'fa','ba_'||left(:'ba',8),'W2C synthetic A branch'),
       (:'ba2',:'ta',:'fa2','ba_'||left(:'ba2',8),'W2C synthetic A other branch'),
       (:'bb',:'tb',:'fb','bb_'||left(:'bb',8),'W2C synthetic B branch');

-- Third member ma3 deliberately has no assigned branch.
INSERT INTO public.org_membership_branch_scope(tenant_id,membership_id,branch_id)
VALUES (:'ta',:'ma',:'ba'), (:'ta',:'ma2',:'ba2'), (:'tb',:'mb',:'bb');

INSERT INTO public.patient(id,tenant_id,display_name)
VALUES (:'pa',:'ta','W2C synthetic A patient'), (:'pb',:'tb','W2C synthetic B patient');

INSERT INTO public.patient_registration(
  id,tenant_id,patient_id,branch_id,registration_source,registered_by_membership_id)
VALUES (:'ra',:'ta',:'pa',:'ba','RECEPTION',:'ma'),
       (:'ra2',:'ta',:'pa',:'ba2','RECEPTION',:'ma2'),
       (:'rb',:'tb',:'pb',:'bb','RECEPTION',:'mb');

-- Switching roles in one session exercises the actual database policies.
SET LOCAL ROLE aronexa_patient_runtime;
DO $check$
BEGIN
  IF (SELECT count(*) FROM public.patient_registration) <> 0 THEN
    RAISE EXCEPTION 'STOP_W2C_NO_CONTEXT_READ_EXPOSED';
  END IF;
  RAISE NOTICE 'PASS: W2C_NO_CONTEXT_DENIED';
END $check$;

SELECT set_config('aronexa.tenant_id',:'ta',true);
DO $check$
BEGIN
  IF (SELECT count(*) FROM public.patient_registration) <> 0 THEN
    RAISE EXCEPTION 'STOP_W2C_TENANT_ONLY_READ_EXPOSED';
  END IF;
  RAISE NOTICE 'PASS: W2C_TENANT_ONLY_DENIED';
END $check$;

-- Correct tenant + assigned membership: exactly one A branch registration.
SELECT set_config('aronexa.membership_id',:'ma',true);
DO $check$
BEGIN
  IF (SELECT count(*) FROM public.patient_registration) <> 1 THEN
    RAISE EXCEPTION 'STOP_W2C_ASSIGNED_BRANCH_NOT_SCOPED';
  END IF;
  RAISE NOTICE 'PASS: W2C_ASSIGNED_BRANCH_VISIBLE';
END $check$;

-- Same tenant, different assigned membership: A-other branch visible,
-- A-first branch must be denied.
SELECT set_config('aronexa.membership_id',:'ma2',true);
DO $check$
BEGIN
  IF (SELECT count(*) FROM public.patient_registration) <> 1 THEN
    RAISE EXCEPTION 'STOP_W2C_WRONG_MEMBERSHIP_SCOPE';
  END IF;
  RAISE NOTICE 'PASS: W2C_OTHER_MEMBERSHIP_ISOLATED';
END $check$;

-- Same tenant, member without any branch assignment cannot see registrations.
SELECT set_config('aronexa.membership_id',:'ma3',true);
DO $check$
BEGIN
  IF (SELECT count(*) FROM public.patient_registration) <> 0 THEN
    RAISE EXCEPTION 'STOP_W2C_UNASSIGNED_MEMBERSHIP_EXPOSED';
  END IF;
  RAISE NOTICE 'PASS: W2C_UNASSIGNED_MEMBERSHIP_DENIED';
END $check$;

-- Other-tenant membership paired with tenant A is forbidden.
SELECT set_config('aronexa.membership_id',:'mb',true);
DO $check$
BEGIN
  IF (SELECT count(*) FROM public.patient_registration) <> 0 THEN
    RAISE EXCEPTION 'STOP_W2C_CROSS_TENANT_READ_EXPOSED';
  END IF;
  RAISE NOTICE 'PASS: W2C_CROSS_TENANT_DENIED';
END $check$;

-- Legitimate tenant B+membership B sees only tenant B registration.
SELECT set_config('aronexa.tenant_id',:'tb',true);
DO $check$
BEGIN
  IF (SELECT count(*) FROM public.patient_registration) <> 1 THEN
    RAISE EXCEPTION 'STOP_W2C_TENANT_B_SCOPE_INVALID';
  END IF;
  RAISE NOTICE 'PASS: W2C_TENANT_B_ISOLATED';
END $check$;

-- Negative SQL permissions: an unauthorized SELECT/WRITE must throw 42501.
-- Do NOT use SECURITY DEFINER, GRANT, or application-level privileged data source.
DO $denied$
BEGIN
  BEGIN
    PERFORM 1 FROM public.patient LIMIT 1;
    RAISE EXCEPTION 'STOP_W2C_MASTER_PATIENT_READ_ALLOWED' USING ERRCODE='P0001';
  EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS: W2C_MASTER_PATIENT_READ_DENIED';
  END;
  BEGIN
    INSERT INTO public.patient_registration DEFAULT VALUES;
    RAISE EXCEPTION 'STOP_W2C_REGISTRATION_INSERT_ALLOWED' USING ERRCODE='P0001';
  EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS: W2C_REGISTRATION_INSERT_DENIED';
  END;
  BEGIN
    UPDATE public.patient_registration SET patient_id=patient_id WHERE false;
    RAISE EXCEPTION 'STOP_W2C_REGISTRATION_UPDATE_ALLOWED' USING ERRCODE='P0001';
  EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS: W2C_REGISTRATION_UPDATE_DENIED';
  END;
  BEGIN
    DELETE FROM public.patient_registration WHERE false;
    RAISE EXCEPTION 'STOP_W2C_REGISTRATION_DELETE_ALLOWED' USING ERRCODE='P0001';
  EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS: W2C_REGISTRATION_DELETE_DENIED';
  END;
  BEGIN
    PERFORM 1 FROM public.patient_registration_access_audit LIMIT 1;
    RAISE EXCEPTION 'STOP_W2C_ACCESS_AUDIT_READ_ALLOWED' USING ERRCODE='P0001';
  EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS: W2C_ACCESS_AUDIT_READ_DENIED';
  END;
END $denied$;

-- Restore migrator identity and ensure that no synthetic fixture is retained.
SET LOCAL ROLE aronexa_dev;
ROLLBACK;

-- Same psql backend, NEW transaction, to test SET LOCAL context cleanup.
-- Source data from all synthetic fixtures has been rolled back automatically.
BEGIN;
DO $pool$
BEGIN
  IF NULLIF(current_setting('aronexa.tenant_id',true),'') IS NOT NULL
     OR NULLIF(current_setting('aronexa.membership_id',true),'') IS NOT NULL THEN
    RAISE EXCEPTION 'STOP_W2C_TRANSACTION_SCOPED_CONTEXT_LEAK';
  END IF;
  RAISE NOTICE 'PASS: W2C_SAME_CONNECTION_NEXT_TRANSACTION_CONTEXT_CLEARED';
END $pool$;
ROLLBACK;
\echo FINAL_STATUS: W2C_SYNTHETIC_POLICY_TRANSACTION_ROLLBACK_PASS
