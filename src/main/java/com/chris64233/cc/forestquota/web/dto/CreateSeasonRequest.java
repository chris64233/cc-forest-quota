package com.chris64233.cc.forestquota.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreateSeasonRequest(
        @NotBlank(message = "林区名称不能为空") String forestArea,
        @NotNull(message = "许可季开始日期不能为空") LocalDate startDate,
        @NotNull(message = "许可季结束日期不能为空") LocalDate endDate) {
}
