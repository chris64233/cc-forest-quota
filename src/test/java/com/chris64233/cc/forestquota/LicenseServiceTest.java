package com.chris64233.cc.forestquota;

import com.chris64233.cc.forestquota.domain.AuditEventType;
import com.chris64233.cc.forestquota.domain.HarvestSeason;
import com.chris64233.cc.forestquota.domain.LicenseStatus;
import com.chris64233.cc.forestquota.service.ApproveCommand;
import com.chris64233.cc.forestquota.service.AuditEventView;
import com.chris64233.cc.forestquota.service.BusinessValidationException;
import com.chris64233.cc.forestquota.service.ConflictException;
import com.chris64233.cc.forestquota.service.LicenseItemView;
import com.chris64233.cc.forestquota.service.LicenseService;
import com.chris64233.cc.forestquota.service.LicenseView;
import com.chris64233.cc.forestquota.service.NotFoundException;
import com.chris64233.cc.forestquota.service.QuotaExceededException;
import com.chris64233.cc.forestquota.service.QuotaLedgerEntry;
import com.chris64233.cc.forestquota.service.SeasonLedgerView;
import com.chris64233.cc.forestquota.service.SeasonService;
import com.chris64233.cc.forestquota.service.SpeciesVolume;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LicenseServiceTest {

    private static final LocalDate SEASON_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate SEASON_END = LocalDate.of(2026, 12, 31);
    private static final LocalDate WORK_START = LocalDate.of(2026, 3, 1);
    private static final LocalDate WORK_END = LocalDate.of(2026, 5, 31);

    @Autowired
    private SeasonService seasonService;

    @Autowired
    private LicenseService licenseService;

    private Long newSeason(String... speciesAndVolumes) {
        HarvestSeason season = seasonService.createSeason("红山林区", SEASON_START, SEASON_END);
        for (int i = 0; i < speciesAndVolumes.length; i += 2) {
            seasonService.addQuota(season.getId(), speciesAndVolumes[i], new BigDecimal(speciesAndVolumes[i + 1]));
        }
        return season.getId();
    }

    private ApproveCommand command(String applicationNo, Long seasonId, SpeciesVolume... items) {
        return new ApproveCommand(applicationNo, seasonId, WORK_START, WORK_END, List.of(items));
    }

    private SpeciesVolume item(String species, String volume) {
        return new SpeciesVolume(species, new BigDecimal(volume));
    }

    private Map<String, QuotaLedgerEntry> ledgerBySpecies(Long seasonId) {
        SeasonLedgerView ledger = seasonService.getLedger(seasonId);
        return ledger.quotas().stream()
                .collect(Collectors.toMap(QuotaLedgerEntry::species, Function.identity()));
    }

    @Test
    void approveOccupiesAllSpeciesAtomically() {
        Long seasonId = newSeason("落叶松", "100", "云杉", "50");

        LicenseView view = licenseService.approve(command("APP-001", seasonId,
                item("落叶松", "40"), item("云杉", "20")));

        assertThat(view.status()).isEqualTo(LicenseStatus.APPROVED);
        assertThat(view.items()).hasSize(2);
        Map<String, QuotaLedgerEntry> ledger = ledgerBySpecies(seasonId);
        assertThat(ledger.get("落叶松").occupiedVolume()).isEqualByComparingTo("40.000");
        assertThat(ledger.get("落叶松").availableVolume()).isEqualByComparingTo("60.000");
        assertThat(ledger.get("云杉").occupiedVolume()).isEqualByComparingTo("20.000");
        assertThat(ledger.get("云杉").availableVolume()).isEqualByComparingTo("30.000");
    }

    @Test
    void approveFailsEntirelyWhenAnySpeciesInsufficient() {
        Long seasonId = newSeason("落叶松", "100", "云杉", "10");

        assertThatThrownBy(() -> licenseService.approve(command("APP-002", seasonId,
                item("落叶松", "40"), item("云杉", "20"))))
                .isInstanceOf(QuotaExceededException.class);

        Map<String, QuotaLedgerEntry> ledger = ledgerBySpecies(seasonId);
        assertThat(ledger.get("落叶松").occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(ledger.get("云杉").occupiedVolume()).isEqualByComparingTo("0.000");
        assertThatThrownBy(() -> licenseService.getLicense("APP-002"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void approveRejectsDatesOutsideSeason() {
        Long seasonId = newSeason("落叶松", "100");

        assertThatThrownBy(() -> licenseService.approve(new ApproveCommand("APP-003", seasonId,
                LocalDate.of(2025, 12, 1), WORK_END, List.of(item("落叶松", "10")))))
                .isInstanceOf(BusinessValidationException.class);
        assertThatThrownBy(() -> licenseService.approve(new ApproveCommand("APP-004", seasonId,
                WORK_START, LocalDate.of(2027, 1, 1), List.of(item("落叶松", "10")))))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void approveRejectsUnknownSpeciesAndDuplicates() {
        Long seasonId = newSeason("落叶松", "100");

        assertThatThrownBy(() -> licenseService.approve(command("APP-005", seasonId, item("红松", "10"))))
                .isInstanceOf(BusinessValidationException.class);
        assertThatThrownBy(() -> licenseService.approve(command("APP-006", seasonId,
                item("落叶松", "10"), item("落叶松", "20"))))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void approveRejectsNonPositiveVolume() {
        Long seasonId = newSeason("落叶松", "100");

        assertThatThrownBy(() -> licenseService.approve(command("APP-007", seasonId, item("落叶松", "0"))))
                .isInstanceOf(BusinessValidationException.class);
        assertThatThrownBy(() -> licenseService.approve(command("APP-008", seasonId, item("落叶松", "-5"))))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void approveIsIdempotentForSameContent() {
        Long seasonId = newSeason("落叶松", "100");
        ApproveCommand command = command("APP-009", seasonId, item("落叶松", "40"));

        LicenseView first = licenseService.approve(command);
        LicenseView second = licenseService.approve(command);

        assertThat(second.licenseId()).isEqualTo(first.licenseId());
        assertThat(ledgerBySpecies(seasonId).get("落叶松").occupiedVolume())
                .isEqualByComparingTo("40.000");
    }

    @Test
    void approveConflictsForSameNumberWithDifferentContent() {
        Long seasonId = newSeason("落叶松", "100");
        licenseService.approve(command("APP-010", seasonId, item("落叶松", "40")));

        assertThatThrownBy(() -> licenseService.approve(command("APP-010", seasonId, item("落叶松", "50"))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void settleReleasesUnusedAndCountsHarvestPermanently() {
        Long seasonId = newSeason("落叶松", "100");
        licenseService.approve(command("APP-011", seasonId, item("落叶松", "40")));

        LicenseView settled = licenseService.settle("APP-011", List.of(item("落叶松", "25")));

        assertThat(settled.status()).isEqualTo(LicenseStatus.SETTLED);
        LicenseItemView item = settled.items().get(0);
        assertThat(item.approvedVolume()).isEqualByComparingTo("40.000");
        assertThat(item.actualVolume()).isEqualByComparingTo("25.000");
        assertThat(item.releasedVolume()).isEqualByComparingTo("15.000");

        QuotaLedgerEntry quota = ledgerBySpecies(seasonId).get("落叶松");
        assertThat(quota.occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(quota.harvestedVolume()).isEqualByComparingTo("25.000");
        assertThat(quota.availableVolume()).isEqualByComparingTo("75.000");
    }

    @Test
    void settleIsIdempotentAndDoesNotReleaseTwice() {
        Long seasonId = newSeason("落叶松", "100");
        licenseService.approve(command("APP-012", seasonId, item("落叶松", "40")));
        List<SpeciesVolume> report = List.of(item("落叶松", "25"));

        LicenseView first = licenseService.settle("APP-012", report);
        LicenseView second = licenseService.settle("APP-012", report);

        assertThat(second.licenseId()).isEqualTo(first.licenseId());
        assertThat(second.items().get(0).releasedVolume()).isEqualByComparingTo("15.000");
        QuotaLedgerEntry quota = ledgerBySpecies(seasonId).get("落叶松");
        assertThat(quota.occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(quota.harvestedVolume()).isEqualByComparingTo("25.000");
    }

    @Test
    void settleConflictsWhenContentDiffersFromOriginal() {
        Long seasonId = newSeason("落叶松", "100");
        licenseService.approve(command("APP-013", seasonId, item("落叶松", "40")));
        licenseService.settle("APP-013", List.of(item("落叶松", "25")));

        assertThatThrownBy(() -> licenseService.settle("APP-013", List.of(item("落叶松", "30"))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void settleRejectsInvalidReports() {
        Long seasonId = newSeason("落叶松", "100", "云杉", "50");
        licenseService.approve(command("APP-014", seasonId,
                item("落叶松", "40"), item("云杉", "20")));

        assertThatThrownBy(() -> licenseService.settle("APP-014",
                List.of(item("落叶松", "41"), item("云杉", "20"))))
                .isInstanceOf(BusinessValidationException.class);
        assertThatThrownBy(() -> licenseService.settle("APP-014",
                List.of(item("落叶松", "-1"), item("云杉", "20"))))
                .isInstanceOf(BusinessValidationException.class);
        assertThatThrownBy(() -> licenseService.settle("APP-014",
                List.of(item("落叶松", "25"))))
                .isInstanceOf(BusinessValidationException.class);

        Map<String, QuotaLedgerEntry> ledger = ledgerBySpecies(seasonId);
        assertThat(ledger.get("落叶松").occupiedVolume()).isEqualByComparingTo("40.000");
        assertThat(ledger.get("云杉").occupiedVolume()).isEqualByComparingTo("20.000");
    }

    @Test
    void settleUnknownLicenseReturnsNotFound() {
        assertThatThrownBy(() -> licenseService.settle("APP-404", List.of(item("落叶松", "1"))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void auditEventsAreRecordedWithApproveSettleAndRelease() {
        Long seasonId = newSeason("落叶松", "100");
        licenseService.approve(command("APP-015", seasonId, item("落叶松", "40")));
        licenseService.settle("APP-015", List.of(item("落叶松", "25")));

        List<AuditEventView> events = licenseService.getAuditEvents("APP-015");

        assertThat(events).extracting(AuditEventView::eventType)
                .containsExactly(AuditEventType.APPROVED, AuditEventType.RELEASED, AuditEventType.SETTLED);
    }
}
