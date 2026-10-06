package com.securebank.transaction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(
        @NotNull UUID sourceAccountId,
        @NotBlank @Pattern(regexp = "\\d{12}", message = "must be a 12-digit account number")
        String destinationAccountNumber,
        @NotNull BigDecimal amount,
        @Size(max = 255) String description) {
}
