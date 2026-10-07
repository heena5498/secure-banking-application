package com.securebank.ledger;

/**
 * Internal invariant violation: a posting's debits and credits differ. Thrown inside the financial
 * operation's database transaction, so the whole operation rolls back.
 */
public class UnbalancedLedgerException extends IllegalStateException {

    public UnbalancedLedgerException(String message) {
        super(message);
    }
}
