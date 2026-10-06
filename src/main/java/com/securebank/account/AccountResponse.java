package com.securebank.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String accountNumber,
        AccountType type,
        BigDecimal balance,
        AccountStatus status,
        Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getAccountNumber(), account.getType(),
                account.getBalance(), account.getStatus(), account.getCreatedAt());
    }
}
