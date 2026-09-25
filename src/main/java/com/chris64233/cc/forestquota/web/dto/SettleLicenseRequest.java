package com.chris64233.cc.forestquota.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SettleLicenseRequest(
        @NotEmpty(message = "树种实采明细不能为空") List<@Valid SpeciesVolumeRequest> items) {
}
