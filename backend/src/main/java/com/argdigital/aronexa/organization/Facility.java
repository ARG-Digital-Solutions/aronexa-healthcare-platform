package com.argdigital.aronexa.organization;

import java.util.UUID;

/** Configurable clinic, hospital or other facility within a tenant. */
public record Facility(UUID id, UUID tenantId, String code, String displayName, String facilityKind) {}
