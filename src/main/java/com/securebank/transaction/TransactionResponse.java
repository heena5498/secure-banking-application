package com.securebank.transaction;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.securebank.account.Account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Accounts are identified by account number only, so a customer never learns the internal IDs of
 * other customers' accounts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionResponse(
        UUID id,
        TransactionType type,
        TransactionStatus status,
        BigDecimal amount,
        String sourceAccountNumber,
        String destinationAccountNumber,
        String description,
        String failureReason,
        Instant createdAt) {

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getType(),
                transaction.getStatus(),
                transaction.getAmount(),
                accountNumber(transaction.getSourceAccount()),
                accountNumber(transaction.getDestinationAccount()),
                transaction.getDescription(),
                transaction.getFailureReason(),
                transaction.getCreatedAt());
    }

    private static String accountNumber(Account account) {
        return account == null ? null : account.getAccountNumber();
    }
}
