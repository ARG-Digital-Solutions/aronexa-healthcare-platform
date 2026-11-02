-- P01-T05-D: Split org RLS runtime from authentication SQL privileges.
-- Prerequisite: the independently provisioned aronexa_auth_runtime LOGIN role.
-- V1–V5 are immutable. No passwords or customer data changes.
--
-- Existing pre-login auth workflows cannot safely take a tenant-scoped
-- application role. They now use a separate non-owner, non-superuser role.
-- Auth role has no SELECT/WRITE access on facility/branch/department.
-- This reduces compromised organization-connection blast radius but does
-- not yet make the global authentication role tenant-isolated.

REVOKE ALL ON public.app_user, public.user_tenant_membership,
    public.auth_role, public.auth_permission, public.auth_role_permission,
    public.auth_membership_role, public.auth_session, public.auth_audit_event
    FROM aronexa_app_runtime;
REVOKE EXECUTE ON FUNCTION public.aronexa_login_tenant_id(text)
    FROM aronexa_app_runtime;

GRANT USAGE ON SCHEMA public TO aronexa_auth_runtime;
GRANT EXECUTE ON FUNCTION public.aronexa_login_tenant_id(text)
    TO aronexa_auth_runtime;
GRANT SELECT, UPDATE ON public.app_user TO aronexa_auth_runtime;
GRANT SELECT ON public.user_tenant_membership, public.auth_role,
    public.auth_membership_role, public.auth_role_permission TO aronexa_auth_runtime;
GRANT SELECT, INSERT, UPDATE ON public.auth_session TO aronexa_auth_runtime;
GRANT INSERT ON public.auth_audit_event TO aronexa_auth_runtime;
-- No tenant/facility/branch/department table grants to the auth role.
-- No GRANT on auth_permission is needed for the existing queries.
