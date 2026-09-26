package com.chris64233.cc.forestquota.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record StatusChangeRequest(
        @NotNull(message = "事件号不能为空") @Positive(message = "事件号必须为正整数") Long eventNo,
        @Size(max = 512, message = "原因最长 512 个字符") String reason,
        Instant effectiveAt) {
}
