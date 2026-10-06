package com.securebank.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<FieldViolation> fieldErrors) {

    public record FieldViolation(String field, String message) {
    }

    public static ErrorResponse of(ErrorCode code, String message, String path) {
        return of(code, message, path, List.of());
    }

    public static ErrorResponse of(ErrorCode code, String message, String path, List<FieldViolation> fieldErrors) {
        return new ErrorResponse(Instant.now(), code.status().value(), code.status().getReasonPhrase(),
                code.name(), message, path, fieldErrors);
    }
}
