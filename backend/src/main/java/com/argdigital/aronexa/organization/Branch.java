package com.argdigital.aronexa.organization;

import java.util.UUID;

/** Facility site; composite FK prevents linking it to another tenant's facility. */
public record Branch(UUID id, UUID tenantId, UUID facilityId, String code, String displayName) {}
