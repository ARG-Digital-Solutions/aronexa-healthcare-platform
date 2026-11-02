-- P01-T04: authentication and tenant memberships only.
-- No accounts, roles, passwords or entitlements are seeded automatically.
CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(120) NOT NULL UNIQUE,
    password_hash VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    failed_logins INTEGER NOT NULL DEFAULT 0 CHECK (failed_logins >= 0),
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    password_updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_username_normalized CHECK (username = lower(btrim(username)) AND length(username) >= 3)
);
CREATE TABLE user_tenant_membership (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE RESTRICT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_membership_user_tenant UNIQUE (user_id, tenant_id),
    CONSTRAINT uq_membership_tenant_id UNIQUE (tenant_id, id)
);
CREATE INDEX ix_membership_tenant ON user_tenant_membership(tenant_id);

CREATE TABLE auth_role (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE RESTRICT,
    code VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    CONSTRAINT uq_auth_role_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_auth_role_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_auth_role_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,79}$')
);
CREATE TABLE auth_permission (
    code VARCHAR(100) PRIMARY KEY,
    description VARCHAR(200) NOT NULL,
    CONSTRAINT ck_auth_permission_code CHECK (code ~ '^[A-Z][A-Z0-9_:.]{1,99}$')
);
CREATE TABLE auth_role_permission (
    tenant_id UUID NOT NULL,
    role_id UUID NOT NULL,
    permission_code VARCHAR(100) NOT NULL REFERENCES auth_permission(code) ON DELETE RESTRICT,
    PRIMARY KEY (tenant_id, role_id, permission_code),
    CONSTRAINT fk_auth_role_permission_tenant_role
        FOREIGN KEY (tenant_id, role_id) REFERENCES auth_role(tenant_id, id) ON DELETE RESTRICT
);
CREATE TABLE auth_membership_role (
    tenant_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    role_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, membership_id, role_id),
    CONSTRAINT fk_membership_role_tenant_member FOREIGN KEY (tenant_id, membership_id)
        REFERENCES user_tenant_membership(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_membership_role_tenant_role FOREIGN KEY (tenant_id, role_id)
        REFERENCES auth_role(tenant_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_auth_membership_role_member ON auth_membership_role(tenant_id, membership_id);

CREATE TABLE auth_session (
    id UUID PRIMARY KEY,
    membership_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    token_sha256 CHAR(64) NOT NULL UNIQUE,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT fk_auth_session_tenant_membership FOREIGN KEY (tenant_id, membership_id)
        REFERENCES user_tenant_membership(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_auth_session_expiry CHECK (expires_at > issued_at)
);
CREATE INDEX ix_auth_session_membership ON auth_session(membership_id, expires_at);
CREATE TABLE auth_audit_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id UUID REFERENCES app_user(id) ON DELETE SET NULL,
    tenant_id UUID REFERENCES tenant(id) ON DELETE SET NULL,
    event_code VARCHAR(40) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_auth_event_code CHECK (event_code IN
        ('LOGIN_SUCCESS', 'LOGIN_REJECTED', 'ACCOUNT_LOCKED', 'LOGOUT', 'PASSWORD_CHANGED'))
);
-- No cascade deletion of clinical/organization data and no implicit superadmin.
