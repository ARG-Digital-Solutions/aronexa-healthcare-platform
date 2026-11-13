-- R1-10B3B REVIEW-ONLY overlay - applies ONLY to disposable PG17.
-- Must execute after the existing review-only R1-10B1 V11 and R1-10B2
-- restricted writer candidate, never against a real clinic or original DB.
-- Does NOT provision any new role, endpoint, privilege, key or migration.
--
-- Safety fix: acquire a live-session row-share lock BEFORE reading volatile
-- staff role/branch/module permissions. If a controlling transaction holds a
-- conflicting session-row lock and revokes authorizations before commit, a
-- queued booking sees the new state, never stale permissions.
--
-- This alone does NOT linearize arbitrary concurrent direct changes to
-- role assignments, branch scopes, OPD state, doctor eligibility, key state,
-- or patient registration. Admin-side revocation protocols and independent
-- permission-row/lock-order review are still mandatory before production.

CREATE OR REPLACE FUNCTION opd_candidate.require_live_staff(
  p_tenant uuid,p_member uuid,p_session uuid,p_branch uuid,p_permission text)
RETURNS void LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp AS $$
BEGIN
  -- The command owner already has column-restricted UPDATE(revoked_at)
  -- for SELECT FOR SHARE, but the client LOGIN has NO table privileges.
  -- Lock first, then re-check all live permissions while lock is held.
  PERFORM 1 FROM public.auth_session s
   WHERE s.id=p_session AND s.tenant_id=p_tenant
     AND s.membership_id=p_member AND s.revoked_at IS NULL
     AND s.expires_at>clock_timestamp()
   FOR SHARE;
  IF NOT FOUND THEN RAISE EXCEPTION 'OPD_SESSION_REVOKED'; END IF;

  IF NOT EXISTS (
   SELECT 1 FROM public.auth_session s
   JOIN public.user_tenant_membership m
     ON m.tenant_id=s.tenant_id AND m.id=s.membership_id
   JOIN public.app_user u ON u.id=m.user_id
   JOIN public.org_membership_branch_scope scope
     ON scope.tenant_id=m.tenant_id AND scope.membership_id=m.id
   JOIN public.auth_membership_role assigned
     ON assigned.tenant_id=m.tenant_id AND assigned.membership_id=m.id
   JOIN public.auth_role_permission rp
     ON rp.tenant_id=assigned.tenant_id AND rp.role_id=assigned.role_id
   JOIN public.tenant_module_state state
     ON state.tenant_id=m.tenant_id AND state.module_code='OPD'
   JOIN public.module_registry registry ON registry.code=state.module_code
   WHERE s.id=p_session AND s.tenant_id=p_tenant
     AND s.membership_id=p_member
     AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp()
     AND m.enabled AND u.enabled AND NOT u.must_change_password
     AND scope.branch_id=p_branch AND rp.permission_code=p_permission
     AND state.entitled AND state.activated AND state.clinically_authorized
     AND registry.implemented AND registry.released
  ) THEN RAISE EXCEPTION 'OPD_ACCESS_DENIED'; END IF;
END $$;

-- CREATE OR REPLACE retains the existing owner and grants.
-- Refuse an unexpectedly privileged public function after overlay.
DO $$
DECLARE
 v_owner text;
 v_is_public boolean;
BEGIN
 SELECT p.proowner::regrole::text,
        EXISTS(SELECT 1 FROM pg_catalog.aclexplode(p.proacl) acl
               WHERE acl.grantee=0 AND acl.privilege_type='EXECUTE')
   INTO v_owner,v_is_public
   FROM pg_catalog.pg_proc p
  WHERE p.oid='opd_candidate.require_live_staff(
     uuid,uuid,uuid,uuid,text)'::regprocedure;
 IF v_owner='aronexa_opd_runtime' OR v_is_public THEN
   RAISE EXCEPTION 'OPD_INVALID_SESSION_GUARD_PRIVILEGES';
 END IF;
END $$;
