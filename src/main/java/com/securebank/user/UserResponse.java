package com.securebank.user;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record UserResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        Set<RoleName> roles,
        Instant createdAt) {

    public static UserResponse from(User user) {
        Set<RoleName> roles = user.getRoles().stream().map(Role::getName).collect(Collectors.toSet());
        return new UserResponse(user.getId(), user.getFirstName(), user.getLastName(), user.getEmail(),
                roles, user.getCreatedAt());
    }
}
