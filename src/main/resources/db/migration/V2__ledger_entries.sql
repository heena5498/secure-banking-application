-- System clearing account: the other side of deposits and withdrawals (money entering or leaving
-- the bank). It has no owner, so it never appears in customer account APIs.
ALTER TABLE accounts ALTER COLUMN owner_id DROP NOT NULL;

ALTER TABLE accounts DROP CONSTRAINT ck_accounts_type;
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_type
    CHECK (account_type IN ('CHECKING', 'SAVINGS', 'SYSTEM_CLEARING'));
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_owner
    CHECK ((account_type = 'SYSTEM_CLEARING' AND owner_id IS NULL)
        OR (account_type <> 'SYSTEM_CLEARING' AND owner_id IS NOT NULL));

INSERT INTO accounts (id, account_number, account_type, balance, status, owner_id, created_at, version)
VALUES ('00000000-0000-0000-0000-000000000001', 'SYSTEM_CLEARING', 'SYSTEM_CLEARING', 0, 'ACTIVE', NULL,
        CURRENT_TIMESTAMP, 0);

CREATE TABLE ledger_entries (
    id             UUID PRIMARY KEY,
    transaction_id UUID                     NOT NULL,
    account_id     UUID                     NOT NULL,
    entry_type     VARCHAR(10)              NOT NULL,
    amount         NUMERIC(19, 2)           NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_ledger_entries_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_ledger_entries_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_ledger_entries_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_ledger_entries_transaction ON ledger_entries (transaction_id);
CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id, created_at);
