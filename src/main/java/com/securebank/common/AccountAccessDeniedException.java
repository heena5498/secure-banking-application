package com.securebank.common;

public class AccountAccessDeniedException extends BankingException {

    public AccountAccessDeniedException() {
        super(ErrorCode.ACCOUNT_ACCESS_DENIED, "You do not have access to this account");
    }
}
