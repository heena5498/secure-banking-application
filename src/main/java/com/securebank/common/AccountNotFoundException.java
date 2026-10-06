package com.securebank.common;

public class AccountNotFoundException extends BankingException {

    public AccountNotFoundException() {
        super(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found");
    }

    public AccountNotFoundException(String message) {
        super(ErrorCode.ACCOUNT_NOT_FOUND, message);
    }
}
