package com.chris64233.cc.forestquota.web.dto;

import com.chris64233.cc.forestquota.domain.AuditEventType;
import com.chris64233.cc.forestquota.domain.PermitStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PermitDtos {

    private PermitDtos() {
    }

    public record ApprovalLine(
            @NotBlank @Size(max = 64) String species,
            @NotNull @Positive @Digits(integer = 16, fraction = 3) BigDecimal volume) {
    }

    public record ApprovalRequest(
            @NotBlank @Size(max = 64) String applicationNo,
            @NotBlank @Size(max = 64) String forestArea,
            @NotBlank @Size(max = 64) String seasonCode,
            @NotNull LocalDate workStartDate,
            @NotNull LocalDate workEndDate,
            @NotEmpty List<@Valid ApprovalLine> lines) {
    }

    public record SettlementLine(
            @NotBlank @Size(max = 64) String species,
            @NotNull @PositiveOrZero @Digits(integer = 16, fraction = 3) BigDecimal actualVolume) {
    }

    public record SettlementRequest(
            @NotEmpty List<@Valid SettlementLine> lines) {
    }

    public record PermitLineView(
            String species,
            BigDecimal approvedVolume,
            BigDecimal harvestedVolume,
            BigDecimal releasedVolume) {
    }

    public record PermitResponse(
            String applicationNo,
            String forestArea,
            String seasonCode,
            LocalDate workStartDate,
            LocalDate workEndDate,
            PermitStatus status,
            List<PermitLineView> lines) {
    }

    public record AuditEventView(
            AuditEventType eventType,
            String detail,
            Instant occurredAt) {
    }
}
