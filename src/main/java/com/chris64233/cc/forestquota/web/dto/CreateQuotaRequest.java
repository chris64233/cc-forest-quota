package com.chris64233.cc.forestquota.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateQuotaRequest(
        @NotBlank(message = "树种不能为空") String species,
        @NotNull(message = "核准材积不能为空") BigDecimal authorizedVolume) {
}
