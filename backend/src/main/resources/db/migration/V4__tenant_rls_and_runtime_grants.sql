-- P01-T05-B, additive V4. Prerequisite: a separately bootstrapped
-- aronexa_app_runtime LOGIN role with NO SUPERUSER, NO BYPASSRLS and no table ownership.
-- Never create or store database passwords in Flyway migrations.
--
-- A tenant-scoped SQL operation MUST be in one transaction and set its
-- trusted tenant with set_config('aronexa.tenant_id', tenant_uuid, true).
-- A missing or empty setting returns NULL, therefore no rows match.
CREATE FUNCTION public.aronexa_current_tenant_id()
RETURNS uuid LANGUAGE sql STABLE
AS $$
    SELECT NULLIF(current_setting('aronexa.tenant_id', true), '')::uuid
$$;
REVOKE ALL ON FUNCTION public.aronexa_current_tenant_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.aronexa_current_tenant_id() TO aronexa_app_runtime;

-- The authentication login bootstrap needs to resolve a tenant code before
-- it can establish a verified tenant. This narrowly scoped function returns
-- ONLY a tenant UUID, not tenant names/metadata or other tenant-owned records.
-- Owner-run function bypasses tenant-table RLS, but runtime role itself does not.
-- Never use interpolated SQL or caller-chosen tenant IDs to set RLS context.
CREATE FUNCTION public.aronexa_login_tenant_id(tenant_code text)
RETURNS uuid LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT t.id FROM public.tenant AS t WHERE t.code = tenant_code
$$;
REVOKE ALL ON FUNCTION public.aronexa_login_tenant_id(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.aronexa_login_tenant_id(text) TO aronexa_app_runtime;

ALTER TABLE public.tenant ENABLE ROW LEVEL SECURITY;
-- No FORCE here: the narrowly scoped SECURITY DEFINER lookup intentionally
-- uses the migration/table owner to resolve tenant code at login.
CREATE POLICY tenant_runtime_scope ON public.tenant
    FOR ALL TO aronexa_app_runtime
    USING (id = public.aronexa_current_tenant_id())
    WITH CHECK (id = public.aronexa_current_tenant_id());

ALTER TABLE public.facility ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.facility FORCE ROW LEVEL SECURITY;
CREATE POLICY facility_runtime_scope ON public.facility
    FOR ALL TO aronexa_app_runtime
    USING (tenant_id = public.aronexa_current_tenant_id())
    WITH CHECK (tenant_id = public.aronexa_current_tenant_id());

ALTER TABLE public.branch ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.branch FORCE ROW LEVEL SECURITY;
CREATE POLICY branch_runtime_scope ON public.branch
    FOR ALL TO aronexa_app_runtime
    USING (tenant_id = public.aronexa_current_tenant_id())
    WITH CHECK (tenant_id = public.aronexa_current_tenant_id());

ALTER TABLE public.department ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.department FORCE ROW LEVEL SECURITY;
CREATE POLICY department_runtime_scope ON public.department
    FOR ALL TO aronexa_app_runtime
    USING (tenant_id = public.aronexa_current_tenant_id())
    WITH CHECK (tenant_id = public.aronexa_current_tenant_id());

GRANT USAGE ON SCHEMA public TO aronexa_app_runtime;
GRANT SELECT ON public.tenant TO aronexa_app_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON
    public.facility, public.branch, public.department TO aronexa_app_runtime;

-- Explicit authentication-bootstrap exception:
-- P01-T04 authenticates *before* an RLS tenant context exists, so its existing
-- session/identity SQL requires separate audited privileges. These tables are
-- NOT claimed RLS-protected in P01-T05-B. Reduce privileges and isolate the
-- authentication datasource/operations before any real patient or production use.
GRANT SELECT, UPDATE ON public.app_user TO aronexa_app_runtime;
GRANT SELECT ON public.user_tenant_membership, public.auth_role,
    public.auth_membership_role, public.auth_role_permission TO aronexa_app_runtime;
GRANT SELECT, INSERT, UPDATE ON public.auth_session TO aronexa_app_runtime;
GRANT INSERT ON public.auth_audit_event TO aronexa_app_runtime;
-- Normal users, role grants and tenant creation are never seeded here.
