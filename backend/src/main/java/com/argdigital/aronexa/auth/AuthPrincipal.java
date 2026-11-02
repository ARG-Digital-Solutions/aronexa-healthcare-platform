package com.argdigital.aronexa.auth;

import java.util.List;
import java.util.UUID;

/** A single authenticated tenant membership; not authorization for a clinical record. */
public record AuthPrincipal(UUID userId, UUID tenantId, UUID membershipId,
                            String username, boolean mustChangePassword,
                            List<String> roles, List<String> permissions) {}
