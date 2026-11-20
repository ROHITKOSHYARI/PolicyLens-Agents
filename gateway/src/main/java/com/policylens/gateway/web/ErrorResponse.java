package com.policylens.gateway.web;

import java.time.Instant;

/** Consistent JSON error envelope returned by gateway endpoints. */
public record ErrorResponse(int status, String message, Instant timestamp) { }
