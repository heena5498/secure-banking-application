package com.securebank.common;

/**
 * A financial operation on an account the caller owns was rejected by a business rule
 * (for example insufficient funds or an inactive account). These rejections are recorded
 * as FAILED transactions for auditability.
 */
public abstract class TransactionRejectedException extends BankingException {

    protected TransactionRejectedException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
