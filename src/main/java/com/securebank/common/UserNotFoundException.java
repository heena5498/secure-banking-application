package com.securebank.common;

public class UserNotFoundException extends BankingException {

    public UserNotFoundException() {
        super(ErrorCode.USER_NOT_FOUND, "User not found");
    }
}
