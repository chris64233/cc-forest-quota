package com.chris64233.cc.forestquota.service;

import org.springframework.http.HttpStatus;

public class BusinessValidationException extends BusinessException {

    public BusinessValidationException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
