package com.securebank.admin;

import com.securebank.account.Account;
import com.securebank.account.AccountStatus;
import com.securebank.account.AccountType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdminAccountResponse(
        UUID id,
        String accountNumber,
        AccountType type,
        BigDecimal balance,
        AccountStatus status,
        UUID ownerId,
        String ownerEmail,
        Instant createdAt) {

    public static AdminAccountResponse from(Account account) {
        return new AdminAccountResponse(account.getId(), account.getAccountNumber(), account.getType(),
                account.getBalance(), account.getStatus(), account.getOwner().getId(),
                account.getOwner().getEmail(), account.getCreatedAt());
    }
}
