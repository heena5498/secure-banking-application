-- Idempotency keys for financial write requests. The UNIQUE constraint on (user_id, idempotency_key)
-- is what guarantees a key can be claimed by only one request, even under concurrency.
CREATE TABLE idempotency_records (
    id              UUID PRIMARY KEY,
    idempotency_key VARCHAR(100)             NOT NULL,
    user_id         UUID                     NOT NULL,
    operation_type  VARCHAR(20)              NOT NULL,
    request_hash    VARCHAR(64)              NOT NULL,
    transaction_id  UUID,
    status          VARCHAR(20)              NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_idempotency_records_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_idempotency_records_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_idempotency_records_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT ck_idempotency_records_operation_type CHECK (operation_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    CONSTRAINT ck_idempotency_records_status CHECK (status IN ('PENDING', 'COMPLETED')),
    CONSTRAINT ck_idempotency_records_completed_has_transaction
        CHECK (status <> 'COMPLETED' OR transaction_id IS NOT NULL)
);
