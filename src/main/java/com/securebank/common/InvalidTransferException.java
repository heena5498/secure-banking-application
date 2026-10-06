package com.securebank.common;

public class InvalidTransferException extends BankingException {

    public InvalidTransferException(String message) {
        super(ErrorCode.INVALID_TRANSFER, message);
    }
}
