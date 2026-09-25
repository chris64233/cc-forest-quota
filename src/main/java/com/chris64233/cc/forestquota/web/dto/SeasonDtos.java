package com.chris64233.cc.forestquota.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class SeasonDtos {

    private SeasonDtos() {
    }

    public record QuotaSpec(
            @NotBlank @Size(max = 64) String species,
            @NotNull @Positive @Digits(integer = 16, fraction = 3) BigDecimal authorizedVolume) {
    }

    public record SeasonCreateRequest(
            @NotBlank @Size(max = 64) String forestArea,
            @NotBlank @Size(max = 64) String seasonCode,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotEmpty List<@Valid QuotaSpec> quotas) {
    }

    public record QuotaLedgerEntry(
            String species,
            BigDecimal authorizedVolume,
            BigDecimal reservedVolume,
            BigDecimal harvestedVolume,
            BigDecimal availableVolume) {
    }

    public record SeasonLedgerResponse(
            String forestArea,
            String seasonCode,
            LocalDate startDate,
            LocalDate endDate,
            List<QuotaLedgerEntry> quotas) {
    }
}
