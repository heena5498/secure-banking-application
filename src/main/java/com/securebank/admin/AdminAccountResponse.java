package com.securebank.admin;

import com.securebank.account.Account;
import com.securebank.account.AccountStatus;
import com.securebank.account.AccountType;
import com.securebank.user.User;

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
        User owner = account.getOwner();
        return new AdminAccountResponse(account.getId(), account.getAccountNumber(), account.getType(),
                account.getBalance(), account.getStatus(), owner == null ? null : owner.getId(),
                owner == null ? null : owner.getEmail(), account.getCreatedAt());
    }
}
