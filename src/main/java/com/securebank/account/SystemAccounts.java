package com.securebank.account;

import java.util.UUID;

/**
 * Identifiers of internal accounts seeded by Flyway migration V2.
 */
public final class SystemAccounts {

    public static final UUID CLEARING_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final String CLEARING_ACCOUNT_NUMBER = "SYSTEM_CLEARING";

    private SystemAccounts() {
    }
}
