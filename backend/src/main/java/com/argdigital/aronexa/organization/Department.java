package com.argdigital.aronexa.organization;

import java.util.UUID;

/** Department lives within a branch, not a separate patient or clinician master. */
public record Department(UUID id, UUID tenantId, UUID branchId, String code, String displayName) {}
