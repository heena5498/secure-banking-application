package com.securebank.idempotency;

import com.securebank.common.BankingException;
import com.securebank.common.ErrorCode;
import com.securebank.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotentRequestTest {

    private static final UUID SOURCE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static String transferHash(String amount, String description) {
        return IdempotentRequest.fingerprint(TransactionType.TRANSFER, SOURCE, "123456789012",
                new BigDecimal(amount), description);
    }

    @Test
    void sameLogicalRequestAlwaysProducesSameHash() {
        assertThat(transferHash("250.00", "rent")).isEqualTo(transferHash("250.00", "rent"));
        assertThat(transferHash("250", "rent")).isEqualTo(transferHash("250.00", "rent"));
        assertThat(transferHash("250.00", "rent")).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void anyChangedParameterProducesDifferentHash() {
        String original = transferHash("250.00", "rent");

        assertThat(transferHash("500.00", "rent")).isNotEqualTo(original);
        assertThat(transferHash("250.00", "food")).isNotEqualTo(original);
        assertThat(transferHash("250.00", null)).isNotEqualTo(original);
        assertThat(IdempotentRequest.fingerprint(TransactionType.TRANSFER, SOURCE, "123456789013",
                new BigDecimal("250.00"), "rent")).isNotEqualTo(original);
        assertThat(IdempotentRequest.fingerprint(TransactionType.DEPOSIT, SOURCE, "123456789012",
                new BigDecimal("250.00"), "rent")).isNotEqualTo(original);
    }

    @Test
    void fieldBoundariesAreUnambiguous() {
        assertThat(IdempotentRequest.fingerprint(TransactionType.DEPOSIT, "ab", "c"))
                .isNotEqualTo(IdempotentRequest.fingerprint(TransactionType.DEPOSIT, "a", "bc"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void missingKeyIsRejected(String key) {
        assertThatThrownBy(() -> IdempotentRequest.of(SOURCE, key, TransactionType.DEPOSIT))
                .isInstanceOf(BankingException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "semi;colon", "emoji-😀"})
    void malformedKeyIsRejected(String key) {
        assertThatThrownBy(() -> IdempotentRequest.of(SOURCE, key, TransactionType.DEPOSIT))
                .isInstanceOf(BankingException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.IDEMPOTENCY_KEY_INVALID);
    }

    @Test
    void overlongKeyIsRejected() {
        String key = "x".repeat(IdempotentRequest.MAX_KEY_LENGTH + 1);

        assertThatThrownBy(() -> IdempotentRequest.of(SOURCE, key, TransactionType.DEPOSIT))
                .isInstanceOf(BankingException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.IDEMPOTENCY_KEY_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"transfer-001", "550e8400-e29b-41d4-a716-446655440000", "order_42.v2:retry"})
    void wellFormedKeysAreAccepted(String key) {
        assertThat(IdempotentRequest.of(SOURCE, key, TransactionType.DEPOSIT).key()).isEqualTo(key);
    }
}
