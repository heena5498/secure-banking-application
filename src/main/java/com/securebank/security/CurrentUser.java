package com.securebank.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves the authenticated user's ID from the validated access token. Ownership checks must
 * always use this value, never a user ID supplied in the request.
 */
@Component
public class CurrentUser {

    public UUID id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return UUID.fromString(jwtAuthentication.getToken().getSubject());
        }
        throw new AuthenticationCredentialsNotFoundException("No authenticated user");
    }
}
