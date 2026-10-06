package com.securebank.common;

public class DuplicateEmailException extends BankingException {

    public DuplicateEmailException() {
        super(ErrorCode.DUPLICATE_EMAIL, "An account with this email already exists");
    }
}
