package com.securebank.transaction;

import com.securebank.account.Account;
import com.securebank.common.Timestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A financial operation. Deposits have only a destination account, withdrawals only a source
 * account, and transfers have both.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, updatable = false, length = 20)
    private TransactionType type;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id", updatable = false)
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_account_id", updatable = false)
    private Account destinationAccount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(length = 255, updatable = false)
    private String description;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Transaction() {
    }

    private Transaction(TransactionType type, BigDecimal amount, Account sourceAccount,
                        Account destinationAccount, String description, TransactionStatus status) {
        this.type = type;
        this.amount = amount;
        this.sourceAccount = sourceAccount;
        this.destinationAccount = destinationAccount;
        this.description = description;
        this.status = status;
        this.createdAt = Timestamps.now();
    }

    public static Transaction pending(TransactionType type, BigDecimal amount, Account source,
                                      Account destination, String description) {
        return new Transaction(type, amount, source, destination, description, TransactionStatus.PENDING);
    }

    public static Transaction failed(TransactionType type, BigDecimal amount, Account source,
                                     Account destination, String description, String failureReason) {
        Transaction transaction = new Transaction(type, amount, source, destination, description,
                TransactionStatus.FAILED);
        transaction.failureReason = failureReason;
        return transaction;
    }

    public void markCompleted() {
        if (status != TransactionStatus.PENDING) {
            throw new IllegalStateException("Only pending transactions can be completed");
        }
        status = TransactionStatus.COMPLETED;
    }

    public UUID getId() {
        return id;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Account getSourceAccount() {
        return sourceAccount;
    }

    public Account getDestinationAccount() {
        return destinationAccount;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
