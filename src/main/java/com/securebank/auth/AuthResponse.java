package com.securebank.auth;

import java.time.Instant;

public record AuthResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt) {
}
