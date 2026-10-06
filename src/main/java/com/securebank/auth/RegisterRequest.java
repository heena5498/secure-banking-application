package com.securebank.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 255) String email,
        // BCrypt only uses the first 72 bytes of the password.
        @NotBlank @Size(min = 8, max = 72) String password) {

    @Override
    public String toString() {
        return "RegisterRequest[firstName=" + firstName + ", lastName=" + lastName
                + ", email=" + email + ", password=****]";
    }
}
