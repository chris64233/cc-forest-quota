package com.chris64233.cc.forestquota.service;

import org.springframework.http.HttpStatus;

public class QuotaExceededException extends BusinessException {

    public QuotaExceededException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
