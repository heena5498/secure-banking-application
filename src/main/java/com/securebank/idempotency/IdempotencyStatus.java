package com.securebank.idempotency;

/**
 * A record is PENDING only inside the database transaction that claimed it; the claim and the
 * financial operation commit together, so every committed record is COMPLETED.
 */
public enum IdempotencyStatus {
    PENDING,
    COMPLETED
}
