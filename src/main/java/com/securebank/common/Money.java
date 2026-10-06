package com.securebank.common;

import java.math.BigDecimal;

/**
 * Validation and normalization rules for monetary amounts.
 */
public final class Money {

    public static final int SCALE = 2;
    public static final BigDecimal MAX_TRANSACTION_AMOUNT = new BigDecimal("1000000000.00");

    private Money() {
    }

    /**
     * Returns the amount normalized to two decimal places, or throws if it is not a valid
     * transaction amount. Amounts with sub-cent precision are rejected rather than rounded.
     */
    public static BigDecimal requireValidAmount(BigDecimal amount) {
        if (amount == null) {
            throw new InvalidAmountException("Amount is required");
        }
        if (amount.signum() <= 0) {
            throw new InvalidAmountException("Amount must be greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw new InvalidAmountException("Amount cannot have more than " + SCALE + " decimal places");
        }
        if (amount.compareTo(MAX_TRANSACTION_AMOUNT) > 0) {
            throw new InvalidAmountException("Amount exceeds the maximum allowed per transaction");
        }
        return amount.setScale(SCALE);
    }
}
