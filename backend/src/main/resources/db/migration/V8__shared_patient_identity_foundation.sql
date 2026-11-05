-- P01-T07-A: shared patient identity schema only (no public patient APIs).
-- Forward-only V8; applied V1-V7 files are immutable. All data stays tenant-scoped.
-- Staff/patient identity grants, audited actor binding, onboarding and APIs are later tasks.

CREATE TABLE public.patient (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES public.tenant(id) ON DELETE RESTRICT,
    display_name VARCHAR(180) NOT NULL,
    date_of_birth DATE,
    birth_date_precision VARCHAR(10) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (birth_date_precision IN ('EXACT', 'ESTIMATED', 'UNKNOWN')),
    status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_patient_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_patient_display_name CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT ck_patient_birth_date CHECK (
        (birth_date_precision = 'UNKNOWN' AND date_of_birth IS NULL)
        OR (birth_date_precision <> 'UNKNOWN' AND date_of_birth IS NOT NULL))
);
CREATE INDEX ix_patient_tenant_name ON public.patient(tenant_id, lower(display_name));

-- One organization-scoped MRN per patient; UUID is the immutable technical key.
-- The bounded registration service in T07-B must generate MRNs and insert both
-- patient and patient_identifier atomically. Do not expose unassigned patients.
-- Additional identifier systems (e.g., ABHA) require separate clinical/privacy review.
CREATE TABLE public.patient_identifier (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    identifier_type VARCHAR(16) NOT NULL DEFAULT 'MRN'
        CHECK (identifier_type = 'MRN'),
    identifier_value VARCHAR(48) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_patient_identifier_patient FOREIGN KEY (tenant_id, patient_id)
        REFERENCES public.patient(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_patient_identifier_patient_type UNIQUE
        (tenant_id, patient_id, identifier_type),
    CONSTRAINT uq_patient_identifier_value UNIQUE
        (tenant_id, identifier_type, identifier_value),
    CONSTRAINT ck_patient_identifier_value CHECK (
        identifier_value = upper(btrim(identifier_value))
        AND identifier_value ~ '^[A-Z0-9][A-Z0-9-]{2,47}$')
);
CREATE INDEX ix_patient_identifier_patient ON public.patient_identifier(tenant_id, patient_id);

-- A family contact may legitimately belong to several different patients.
-- Contact verification does NOT establish legal guardianship or portal access.
CREATE TABLE public.patient_contact (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    contact_type VARCHAR(12) NOT NULL CHECK (contact_type IN ('MOBILE','EMAIL')),
    contact_value VARCHAR(254) NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_patient_contact_patient FOREIGN KEY (tenant_id, patient_id)
        REFERENCES public.patient(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_patient_contact_per_patient UNIQUE
        (tenant_id, patient_id, contact_type, contact_value),
    CONSTRAINT ck_patient_contact_value CHECK (
        contact_value = btrim(contact_value) AND length(contact_value) > 0)
);
CREATE UNIQUE INDEX uq_patient_primary_contact
    ON public.patient_contact(tenant_id, patient_id, contact_type) WHERE is_primary;
CREATE INDEX ix_patient_contact_patient ON public.patient_contact(tenant_id, patient_id);

-- Relationships are assertions about people, NOT authorization grants.
-- An unregistered guardian/partner may be represented by a name until verified.
CREATE TABLE public.patient_relationship (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    related_patient_id UUID,
    related_person_name VARCHAR(180),
    relationship_type VARCHAR(16) NOT NULL
        CHECK (relationship_type IN ('PARENT','GUARDIAN','SPOUSE','PARTNER','OTHER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_patient_relationship_subject FOREIGN KEY (tenant_id, patient_id)
        REFERENCES public.patient(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_patient_relationship_related FOREIGN KEY (tenant_id, related_patient_id)
        REFERENCES public.patient(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_patient_relationship_not_self CHECK (
        related_patient_id IS NULL OR related_patient_id <> patient_id),
    CONSTRAINT ck_patient_relationship_identity CHECK (
        (related_patient_id IS NULL) <> (related_person_name IS NULL)),
    CONSTRAINT ck_patient_relationship_name CHECK (
        related_person_name IS NULL OR length(btrim(related_person_name)) > 0)
);
CREATE INDEX ix_patient_relationship_patient
    ON public.patient_relationship(tenant_id, patient_id);

-- Registration takes place at a branch; facility is derived from branch.facility_id,
-- preventing contradictory facility/branch mappings. Multiple branches may
-- register the SAME patient without creating a new master identity.
CREATE TABLE public.patient_registration (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    registration_source VARCHAR(12) NOT NULL
        CHECK (registration_source IN ('RECEPTION','SELF','GUARDIAN')),
    registered_by_membership_id UUID,
    idempotency_sha256 CHAR(64),
    registered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_patient_registration_patient FOREIGN KEY (tenant_id, patient_id)
        REFERENCES public.patient(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_patient_registration_branch FOREIGN KEY (tenant_id, branch_id)
        REFERENCES public.branch(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_patient_registration_actor FOREIGN KEY
        (tenant_id, registered_by_membership_id)
        REFERENCES public.user_tenant_membership(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_patient_registration_idempotency CHECK (
        idempotency_sha256 IS NULL OR
        idempotency_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE UNIQUE INDEX uq_patient_registration_idempotency
    ON public.patient_registration(tenant_id, idempotency_sha256)
    WHERE idempotency_sha256 IS NOT NULL;
CREATE INDEX ix_patient_registration_patient
    ON public.patient_registration(tenant_id, patient_id);
CREATE INDEX ix_patient_registration_branch
    ON public.patient_registration(tenant_id, branch_id);

-- Metadata-only identity audit (no names, contact values, DOB or request bodies).
-- Actor attribution remains an explicit T07-B prerequisite before any runtime writes.
CREATE TABLE public.patient_identity_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    object_type VARCHAR(40) NOT NULL CHECK (object_type IN
        ('patient','patient_identifier','patient_contact',
         'patient_relationship','patient_registration')),
    operation VARCHAR(6) NOT NULL CHECK (operation IN ('INSERT','UPDATE','DELETE')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_patient_identity_audit_patient FOREIGN KEY (tenant_id, patient_id)
        REFERENCES public.patient(tenant_id, id) ON DELETE RESTRICT
);
CREATE INDEX ix_patient_identity_audit_patient
    ON public.patient_identity_audit(tenant_id, patient_id, occurred_at);

-- Ownership and patient identity cannot be silently reassigned or deleted.
CREATE FUNCTION public.reject_patient_identity_reassignment()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR
       NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
        RAISE EXCEPTION 'Patient record identity and tenant are immutable';
    END IF;
    IF TG_TABLE_NAME <> 'patient' THEN
        IF NEW.patient_id IS DISTINCT FROM OLD.patient_id THEN
            RAISE EXCEPTION 'Patient association is immutable';
        END IF;
    END IF;
    IF TG_TABLE_NAME = 'patient_identifier' THEN
        IF NEW.identifier_type IS DISTINCT FROM OLD.identifier_type OR
           NEW.identifier_value IS DISTINCT FROM OLD.identifier_value THEN
            RAISE EXCEPTION 'Patient identifiers cannot be reassigned';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER patient_identity_no_reassignment
    BEFORE UPDATE ON public.patient
    FOR EACH ROW EXECUTE FUNCTION public.reject_patient_identity_reassignment();
CREATE TRIGGER patient_identifier_no_reassignment
    BEFORE UPDATE ON public.patient_identifier
    FOR EACH ROW EXECUTE FUNCTION public.reject_patient_identity_reassignment();
CREATE TRIGGER patient_contact_no_reassignment
    BEFORE UPDATE ON public.patient_contact
    FOR EACH ROW EXECUTE FUNCTION public.reject_patient_identity_reassignment();
CREATE TRIGGER patient_relationship_no_reassignment
    BEFORE UPDATE ON public.patient_relationship
    FOR EACH ROW EXECUTE FUNCTION public.reject_patient_identity_reassignment();

CREATE FUNCTION public.reject_patient_identity_deletion()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Patient identity/registration deletion requires a separately approved retention process';
END;
$$;
CREATE TRIGGER patient_no_delete BEFORE DELETE ON public.patient
    FOR EACH ROW EXECUTE FUNCTION public.reject_patient_identity_deletion();
CREATE TRIGGER patient_identifier_no_delete BEFORE DELETE ON public.patient_identifier
    FOR EACH ROW EXECUTE FUNCTION public.reject_patient_identity_deletion();
CREATE TRIGGER patient_registration_append_only BEFORE UPDATE OR DELETE
    ON public.patient_registration FOR EACH ROW
    EXECUTE FUNCTION public.reject_patient_identity_deletion();

CREATE FUNCTION public.record_patient_identity_audit()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, public AS $$
DECLARE
    patient_owner UUID;
    subject_patient UUID;
BEGIN
    IF TG_TABLE_NAME = 'patient' THEN
        patient_owner := CASE WHEN TG_OP = 'DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
        subject_patient := CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END;
    ELSE
        patient_owner := CASE WHEN TG_OP = 'DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
        subject_patient := CASE WHEN TG_OP = 'DELETE' THEN OLD.patient_id ELSE NEW.patient_id END;
    END IF;
    INSERT INTO public.patient_identity_audit
        (tenant_id, patient_id, object_type, operation)
    VALUES (patient_owner, subject_patient, TG_TABLE_NAME, TG_OP);
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.record_patient_identity_audit() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.reject_patient_identity_reassignment() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.reject_patient_identity_deletion() FROM PUBLIC;

CREATE TRIGGER patient_audit AFTER INSERT OR UPDATE ON public.patient
    FOR EACH ROW EXECUTE FUNCTION public.record_patient_identity_audit();
CREATE TRIGGER patient_identifier_audit AFTER INSERT OR UPDATE ON public.patient_identifier
    FOR EACH ROW EXECUTE FUNCTION public.record_patient_identity_audit();
CREATE TRIGGER patient_contact_audit AFTER INSERT OR UPDATE OR DELETE ON public.patient_contact
    FOR EACH ROW EXECUTE FUNCTION public.record_patient_identity_audit();
CREATE TRIGGER patient_relationship_audit AFTER INSERT OR UPDATE OR DELETE ON public.patient_relationship
    FOR EACH ROW EXECUTE FUNCTION public.record_patient_identity_audit();
CREATE TRIGGER patient_registration_audit AFTER INSERT ON public.patient_registration
    FOR EACH ROW EXECUTE FUNCTION public.record_patient_identity_audit();

CREATE FUNCTION public.reject_patient_audit_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Patient identity audit is append-only';
END;
$$;
REVOKE ALL ON FUNCTION public.reject_patient_audit_mutation() FROM PUBLIC;
CREATE TRIGGER patient_audit_immutable BEFORE UPDATE OR DELETE
    ON public.patient_identity_audit FOR EACH ROW
    EXECUTE FUNCTION public.reject_patient_audit_mutation();

-- Data tables: forced RLS by verified transaction-local tenant context.
-- No patient table privilege is granted to either runtime identity in T07-A:
-- T07-B must design and test branch/role/object-scoped access BEFORE any grants.
ALTER TABLE public.patient ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.patient FORCE ROW LEVEL SECURITY;
CREATE POLICY patient_tenant_scope ON public.patient FOR ALL TO aronexa_app_runtime
    USING (tenant_id = public.aronexa_current_tenant_id())
    WITH CHECK (tenant_id = public.aronexa_current_tenant_id());

ALTER TABLE public.patient_identifier ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.patient_identifier FORCE ROW LEVEL SECURITY;
CREATE POLICY patient_identifier_tenant_scope ON public.patient_identifier FOR ALL TO aronexa_app_runtime
    USING (tenant_id = public.aronexa_current_tenant_id())
    WITH CHECK (tenant_id = public.aronexa_current_tenant_id());

ALTER TABLE public.patient_contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.patient_contact FORCE ROW LEVEL SECURITY;
CREATE POLICY patient_contact_tenant_scope ON public.patient_contact FOR ALL TO aronexa_app_runtime
    USING (tenant_id = public.aronexa_current_tenant_id())
    WITH CHECK (tenant_id = public.aronexa_current_tenant_id());

ALTER TABLE public.patient_relationship ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.patient_relationship FORCE ROW LEVEL SECURITY;
CREATE POLICY patient_relationship_tenant_scope ON public.patient_relationship FOR ALL TO aronexa_app_runtime
    USING (tenant_id = public.aronexa_current_tenant_id())
    WITH CHECK (tenant_id = public.aronexa_current_tenant_id());

ALTER TABLE public.patient_registration ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.patient_registration FORCE ROW LEVEL SECURITY;
CREATE POLICY patient_registration_tenant_scope ON public.patient_registration FOR ALL TO aronexa_app_runtime
    USING (tenant_id = public.aronexa_current_tenant_id())
    WITH CHECK (tenant_id = public.aronexa_current_tenant_id());

-- Audit writes happen through an owner-run, metadata-only SECURITY DEFINER
-- trigger; FORCE RLS is intentionally not applied to that audit table.
ALTER TABLE public.patient_identity_audit ENABLE ROW LEVEL SECURITY;
CREATE POLICY patient_audit_tenant_scope ON public.patient_identity_audit
    FOR SELECT TO aronexa_app_runtime
    USING (tenant_id = public.aronexa_current_tenant_id());

-- Explicit deny-by-absence: no SELECT/INSERT/UPDATE/DELETE/sequence grants on
-- patient tables for aronexa_app_runtime or aronexa_auth_runtime in this task.
-- No patient self-service/verification/OTP/duplicate-matching endpoints.
