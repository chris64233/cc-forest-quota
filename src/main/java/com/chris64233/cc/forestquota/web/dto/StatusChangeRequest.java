package com.chris64233.cc.forestquota.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record StatusChangeRequest(
        @NotBlank(message = "事件号不能为空") String eventNo,
        String reason,
        @NotNull(message = "生效时间不能为空") Instant effectiveAt) {
}
