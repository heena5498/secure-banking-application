package com.securebank.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-0.01", "-100", "1.001", "1000000000.01"})
    void rejectsInvalidAmounts(String amount) {
        assertThatThrownBy(() -> Money.requireValidAmount(new BigDecimal(amount)))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> Money.requireValidAmount(null)).isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void normalizesToTwoDecimalPlaces() {
        assertThat(Money.requireValidAmount(new BigDecimal("200"))).isEqualTo(new BigDecimal("200.00"));
        assertThat(Money.requireValidAmount(new BigDecimal("0.01"))).isEqualTo(new BigDecimal("0.01"));
        assertThat(Money.requireValidAmount(new BigDecimal("12.500"))).isEqualTo(new BigDecimal("12.50"));
        assertThat(Money.requireValidAmount(new BigDecimal("1E+3"))).isEqualTo(new BigDecimal("1000.00"));
    }
}
