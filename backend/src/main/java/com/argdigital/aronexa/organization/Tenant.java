package com.argdigital.aronexa.organization;

import java.util.UUID;

/** Organization boundary; not an authenticated user or a SaaS subscription. */
public record Tenant(UUID id, String code, String displayName) {}
