package com.securebank.idempotency;

import com.securebank.common.Timestamps;
import com.securebank.transaction.Transaction;
import com.securebank.transaction.TransactionType;
import com.securebank.user.User;
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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_records_user_key",
                columnNames = {"user_id", "idempotency_key"}))
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, updatable = false, length = 20)
    private TransactionType operationType;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
    }

    private IdempotencyRecord(User user, String idempotencyKey, TransactionType operationType, String requestHash) {
        this.user = user;
        this.idempotencyKey = idempotencyKey;
        this.operationType = operationType;
        this.requestHash = requestHash;
        this.status = IdempotencyStatus.PENDING;
        this.createdAt = Timestamps.now();
    }

    public static IdempotencyRecord claim(User user, IdempotentRequest request) {
        return new IdempotencyRecord(user, request.key(), request.operationType(), request.requestHash());
    }

    public void complete(Transaction transaction) {
        if (status != IdempotencyStatus.PENDING) {
            throw new IllegalStateException("Idempotency record is already completed");
        }
        this.transaction = transaction;
        this.status = IdempotencyStatus.COMPLETED;
    }

    public boolean matches(IdempotentRequest request) {
        return requestHash.equals(request.requestHash());
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public TransactionType getOperationType() {
        return operationType;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
