package com.securebank.account;

import jakarta.validation.constraints.NotNull;

public record CreateAccountRequest(@NotNull AccountType type) {
}
