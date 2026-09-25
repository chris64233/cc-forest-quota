package com.chris64233.cc.forestquota.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record ApproveLicenseRequest(
        @NotBlank(message = "申请号不能为空") String applicationNo,
        @NotNull(message = "许可季不能为空") Long seasonId,
        @NotNull(message = "作业开始日期不能为空") LocalDate workStartDate,
        @NotNull(message = "作业结束日期不能为空") LocalDate workEndDate,
        @NotEmpty(message = "树种材积明细不能为空") List<@Valid SpeciesVolumeRequest> items) {
}
