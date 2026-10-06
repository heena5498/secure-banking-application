package com.securebank.common;

public class InsufficientFundsException extends TransactionRejectedException {

    public InsufficientFundsException() {
        super(ErrorCode.INSUFFICIENT_FUNDS, "Insufficient funds");
    }
}
