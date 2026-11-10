package com.validexia.platform.security;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** The verified contents of a Clerk session token that this application relies on. */
public record ClerkIdentity(
    String subject,
    String sessionId,
    Instant issuedAt,
    Instant expiresAt,
    @Nullable String email) {}
