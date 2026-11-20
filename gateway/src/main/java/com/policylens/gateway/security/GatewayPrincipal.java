package com.policylens.gateway.security;

import java.util.UUID;

/** Authenticated gateway identity with tenant scope. */
public record GatewayPrincipal(UUID userId, UUID tenantId, String email) { }
