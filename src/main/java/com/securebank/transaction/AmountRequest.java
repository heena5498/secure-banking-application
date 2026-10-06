package com.securebank.transaction;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Deposit/withdrawal request. Amount rules (positive, at most two decimals) are enforced by the
 * service layer so they apply regardless of entry point.
 */
public record AmountRequest(@NotNull BigDecimal amount, @Size(max = 255) String description) {
}
