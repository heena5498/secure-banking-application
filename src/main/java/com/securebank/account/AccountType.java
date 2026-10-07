package com.securebank.account;

public enum AccountType {
    CHECKING,
    SAVINGS,
    /** Internal, ownerless account representing money entering or leaving the bank. */
    SYSTEM_CLEARING;

    public boolean isCustomerType() {
        return this != SYSTEM_CLEARING;
    }
}
