package com.securebank.idempotency;

import com.securebank.common.BankingException;
import com.securebank.common.ErrorCode;
import com.securebank.transaction.TransactionType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A validated idempotency key plus a fingerprint of the logical request it was sent with.
 *
 * @param requestHash SHA-256 (hex) of the canonical request: operation type plus the request's
 *                    business parameters. Amounts are compared by value, so 250 and 250.00 match.
 */
public record IdempotentRequest(UUID userId, String key, TransactionType operationType, String requestHash) {

    public static final String HEADER = "Idempotency-Key";
    public static final int MAX_KEY_LENGTH = 100;

    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_.:-]{1," + MAX_KEY_LENGTH + "}");
    private static final String FINGERPRINT_VERSION = "v1";

    /**
     * @param parameters the request's business parameters, in a fixed order per operation type
     */
    public static IdempotentRequest of(UUID userId, String key, TransactionType operationType,
                                       Object... parameters) {
        return new IdempotentRequest(userId, requireValidKey(key), operationType,
                fingerprint(operationType, parameters));
    }

    static String requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BankingException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "The " + HEADER + " header is required for this operation");
        }
        if (!KEY_FORMAT.matcher(key).matches()) {
            throw new BankingException(ErrorCode.IDEMPOTENCY_KEY_INVALID, "The " + HEADER
                    + " header must be 1-" + MAX_KEY_LENGTH + " characters of letters, digits, '-', '_', '.' or ':'");
        }
        return key;
    }

    /**
     * Builds an unambiguous canonical string (each value is length-prefixed) and hashes it.
     * Only request content is included; nothing time-dependent.
     */
    static String fingerprint(TransactionType operationType, Object... parameters) {
        StringBuilder canonical = new StringBuilder(FINGERPRINT_VERSION).append(';');
        append(canonical, operationType.name());
        for (Object parameter : parameters) {
            append(canonical, canonicalValue(parameter));
        }
        return sha256(canonical.toString());
    }

    private static String canonicalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return value == null ? null : value.toString();
    }

    private static void append(StringBuilder canonical, String value) {
        if (value == null) {
            canonical.append("-;");
        } else {
            canonical.append(value.length()).append(':').append(value).append(';');
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
