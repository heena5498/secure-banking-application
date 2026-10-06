package com.securebank.common;

public class AccountNotActiveException extends TransactionRejectedException {

    public AccountNotActiveException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
