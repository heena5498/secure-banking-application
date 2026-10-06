package com.securebank.common;

/**
 * Base class for expected business errors. The message is always safe to return to API clients.
 */
public class BankingException extends RuntimeException {

    private final ErrorCode errorCode;

    public BankingException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
