package com.chris64233.cc.forestquota.web.dto;

import java.time.Instant;

public record ErrorResponse(int status, String message, Instant timestamp) {

    public static ErrorResponse of(int status, String message) {
        return new ErrorResponse(status, message, Instant.now());
    }
}
