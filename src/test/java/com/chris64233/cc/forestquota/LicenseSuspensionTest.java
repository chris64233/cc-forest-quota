package com.chris64233.cc.forestquota;

import com.chris64233.cc.forestquota.domain.AuditEventType;
import com.chris64233.cc.forestquota.domain.HarvestSeason;
import com.chris64233.cc.forestquota.domain.LicenseStatus;
import com.chris64233.cc.forestquota.service.ApproveCommand;
import com.chris64233.cc.forestquota.service.AuditEventView;
import com.chris64233.cc.forestquota.service.BusinessValidationException;
import com.chris64233.cc.forestquota.service.ConflictException;
import com.chris64233.cc.forestquota.service.LicenseService;
import com.chris64233.cc.forestquota.service.LicenseView;
import com.chris64233.cc.forestquota.service.QuotaLedgerEntry;
import com.chris64233.cc.forestquota.service.ResumeCheckView;
import com.chris64233.cc.forestquota.service.SeasonLedgerView;
import com.chris64233.cc.forestquota.service.SeasonService;
import com.chris64233.cc.forestquota.service.SpeciesQuotaDetailView;
import com.chris64233.cc.forestquota.service.SpeciesVolume;
import com.chris64233.cc.forestquota.service.StatusChangeCommand;
import com.chris64233.cc.forestquota.service.StatusEventView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LicenseSuspensionTest {

    private static final LocalDate SEASON_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate FAR_FUTURE = LocalDate.of(2099, 12, 31);
    private static final LocalDate WORK_START = LocalDate.of(2026, 3, 1);

    @Autowired
    private SeasonService seasonService;

    @Autowired
    private LicenseService licenseService;

    private Long newSeason(LocalDate endDate, String... speciesAndVolumes) {
        HarvestSeason season = seasonService.createSeason("红山林区", SEASON_START, endDate);
        for (int i = 0; i < speciesAndVolumes.length; i += 2) {
            seasonService.addQuota(season.getId(), speciesAndVolumes[i], new BigDecimal(speciesAndVolumes[i + 1]));
        }
        return season.getId();
    }

    private LicenseView approve(String applicationNo, Long seasonId, LocalDate workEnd, SpeciesVolume... items) {
        return licenseService.approve(new ApproveCommand(applicationNo, seasonId, WORK_START, workEnd,
                List.of(items)));
    }

    private SpeciesVolume item(String species, String volume) {
        return new SpeciesVolume(species, new BigDecimal(volume));
    }

    private StatusChangeCommand change(long eventNo, String reason) {
        return new StatusChangeCommand(eventNo, reason, Instant.parse("2026-09-27T08:00:00Z"));
    }

    private Map<String, QuotaLedgerEntry> ledgerBySpecies(Long seasonId) {
        SeasonLedgerView ledger = seasonService.getLedger(seasonId);
        return ledger.quotas().stream()
                .collect(Collectors.toMap(QuotaLedgerEntry::species, Function.identity()));
    }

    @Test
    void suspendBlocksSettlementAndKeepsQuotaOccupied() {
        Long seasonId = newSeason(FAR_FUTURE, "落叶松", "100");
        approve("SUS-001", seasonId, FAR_FUTURE, item("落叶松", "40"));

        LicenseView suspended = licenseService.suspend("SUS-001", change(1, "暴雨红色预警"));

        assertThat(suspended.status()).isEqualTo(LicenseStatus.SUSPENDED);
        assertThatThrownBy(() -> licenseService.settle("SUS-001", List.of(item("落叶松", "25"))))
                .isInstanceOf(ConflictException.class);
        // 暂停不释放额度，已确认的历史占用继续计入
        QuotaLedgerEntry quota = ledgerBySpecies(seasonId).get("落叶松");
        assertThat(quota.occupiedVolume()).isEqualByComparingTo("40.000");
        assertThat(quota.availableVolume()).isEqualByComparingTo("60.000");
    }

    @Test
    void suspendRecordsEffectiveTimeAndReasonInTimeline() {
        Long seasonId = newSeason(FAR_FUTURE, "落叶松", "100");
        approve("SUS-002", seasonId, FAR_FUTURE, item("落叶松", "40"));
        Instant effectiveAt = Instant.parse("2026-09-20T00:00:00Z");

        licenseService.suspend("SUS-002", new StatusChangeCommand(1L, "林区封闭", effectiveAt));

        List<StatusEventView> timeline = licenseService.getTimeline("SUS-002");
        assertThat(timeline).hasSize(1);
        StatusEventView event = timeline.get(0);
        assertThat(event.eventNo()).isEqualTo(1);
        assertThat(event.eventType()).isEqualTo(AuditEventType.SUSPENDED);
        assertThat(event.reason()).isEqualTo("林区封闭");
        assertThat(event.effectiveAt()).isEqualTo(effectiveAt);
    }

    @Test
    void resumeAfterSuspensionAllowsSettlement() {
        Long seasonId = newSeason(FAR_FUTURE, "落叶松", "100");
        approve("SUS-003", seasonId, FAR_FUTURE, item("落叶松", "40"));
        licenseService.suspend("SUS-003", change(1, "暴雨"));

        LicenseView resumed = licenseService.resume("SUS-003", change(2, "预警解除"));

        assertThat(resumed.status()).isEqualTo(LicenseStatus.APPROVED);
        LicenseView settled = licenseService.settle("SUS-003", List.of(item("落叶松", "25")));
        assertThat(settled.status()).isEqualTo(LicenseStatus.SETTLED);
        QuotaLedgerEntry quota = ledgerBySpecies(seasonId).get("落叶松");
        assertThat(quota.harvestedVolume()).isEqualByComparingTo("25.000");
        assertThat(quota.occupiedVolume()).isEqualByComparingTo("0.000");
    }

    @Test
    void resumeBlockedWhenSeasonAndWorkPeriodExpired() {
        Long seasonId = newSeason(LocalDate.of(2026, 6, 30), "落叶松", "100");
        approve("SUS-004", seasonId, LocalDate.of(2026, 5, 31), item("落叶松", "40"));
        licenseService.suspend("SUS-004", change(1, "违规作业"));

        assertThatThrownBy(() -> licenseService.resume("SUS-004", change(2, "整改完成")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("许可季已结束")
                .hasMessageContaining("作业期限已过");

        ResumeCheckView check = licenseService.getResumeBlockers("SUS-004");
        assertThat(check.resumable()).isFalse();
        assertThat(check.reasons()).anySatisfy(reason -> assertThat(reason).contains("许可季已结束"))
                .anySatisfy(reason -> assertThat(reason).contains("作业期限已过"));
        assertThat(licenseService.getLicense("SUS-004").status()).isEqualTo(LicenseStatus.SUSPENDED);
    }

    @Test
    void resumeBlockersEmptyForValidSuspendedLicense() {
        Long seasonId = newSeason(FAR_FUTURE, "落叶松", "100");
        approve("SUS-005", seasonId, FAR_FUTURE, item("落叶松", "40"));
        licenseService.suspend("SUS-005", change(1, "天气原因"));

        ResumeCheckView check = licenseService.getResumeBlockers("SUS-005");

        assertThat(check.resumable()).isTrue();
        assertThat(check.reasons()).isEmpty();
    }

    @Test
    void resumeRejectsLicenseNotSuspended() {
        Long seasonId = newSeason(FAR_FUTURE, "落叶松", "100");
        approve("SUS-006", seasonId, FAR_FUTURE, item("落叶松", "40"));

        assertThatThrownBy(() -> licenseService.resume("SUS-006", change(1, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void revokeReleasesAllUnusedOccupation() {
        Long seasonId = newSeason(FAR_FUTURE, "落叶松", "100", "云杉", "50");
        approve("SUS-007", seasonId, FAR_FUTURE, item("落叶松", "40"), item("云杉", "20"));
        licenseService.suspend("SUS-007", change(1, "严重违规"));

        LicenseView revoked = licenseService.revoke("SUS-007", change(2, "取消采伐资格"));

        assertThat(revoked.status()).isEqualTo(LicenseStatus.REVOKED);
        Map<String, QuotaLedgerEntry> ledger = ledgerBySpecies(seasonId);
        assertThat(ledger.get("落叶松").occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(ledger.get("落叶松").availableVolume()).isEqualByComparingTo("100.000");
        assertThat(ledger.get("云杉").occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(ledger.get("云杉").availableVolume()).isEqualByComparingTo("50.000");

        Map<String, SpeciesQuotaDetailView> details = licenseService.getQuotaDetails("SUS-007").stream()
                .collect(Collectors.toMap(SpeciesQuotaDetailView::species, Function.identity()));
        assertThat(details.get("落叶松").releasedVolume()).isEqualByComparingTo("40.000");
        assertThat(details.get("落叶松").occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(details.get("云杉").releasedVolume()).isEqualByComparingTo("20.000");
    }

    @Test
    void revokeIsTerminalAndRejectsFurtherOperations() {
        Long seasonId = newSeason(FAR_FUTURE, "落叶松", "100");
        approve("SUS-008", seasonId, FAR_FUTURE, item("落叶松", "40"));
        licenseService.revoke("SUS-008", change(1, "违规"));

        assertThatThrownBy(() -> licenseService.settle("SUS-008", List.of(item("落叶松", "10"))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> licenseService.suspend("SUS-008", change(2, "天气")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> licenseService.resume("SUS-008", change(2, "恢复")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> licenseService.revoke("SUS-008", change(2, "再次撤销")))
                .isInstanceOf(ConflictException.class);

        QuotaLedgerEntry quota = ledgerBySpecies(seasonId).get("落叶松");
        assertThat(quota.occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(quota.availableVolume()).isEqualByComparingTo("100.000");
    }

    @Test
    void revokeIsIdempotentWithSameEventNoAndDoesNotReleaseTwice() {
        Long seasonId = newSeason(FAR_FUTURE, "落叶松", "100");
        approve("SUS-009", seasonId, FAR_FUTURE, item("落叶松", "40"));
        StatusChangeCommand command = change(1, "违规");

        LicenseView first = licenseService.revoke("SUS-009", command);
        LicenseView second = licenseService.revoke("SUS-009", command);

        assertThat(second.licenseId()).isEqualTo(first.licenseId());
        assertThat(second.status()).isEqualTo(LicenseStatus.REVOKED);
        QuotaLedgerEntry quota = ledgerBySpecies(seasonId).get("落叶松");
        assertThat(quota.occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(quota.availableVolume()).isEqualByComparingTo("100.000");
        assertThat(licenseService.getTimeline("SUS-009")).hasSize(1);
    }

    @Test
    void revokeDoesNotReturnHarvestedAmountsOfOtherLicenses() {
        Long seasonId = newSeason(FAR_FUTURE, "落叶松", "100");
        approve("SUS-010", seasonId, FAR_FUTURE, item("落叶松", "40"));
        approve("SUS-011", seasonId, FAR_FUTURE, item("落叶松", "30"));
        licenseService.settle("SUS-010", List.of(item("落叶松", "25")));

        licenseService.revoke("SUS-011", change(1, "林区封闭"));

        QuotaLedgerEntry quota = ledgerBySpecies(seasonId).get("落叶松");
        assertThat(quota.harvestedVolume()).isEqualByComparingTo("25.000");
        assertThat(quota.occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(quota.availableVolume()).isEqualByComparingTo("75.000");
    }

    @Test
    void statusEventNoMustIncreaseToKeepTimeOrder() {
        Long seasonId = newSeason(FAR_FUTURE, "落叶松", "100");
        approve("SUS-012", seasonId, FAR_FUTURE, item("落叶松", "40"));
        licenseService.suspend("SUS-012", change(5, "天气"));

        assertThatThrownBy(() -> licenseService.resume("SUS-012", change(3, "恢复")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("递增");
        assertThatThrownBy(() -> licenseService.resume("SUS-012", change(5, "恢复")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void sameEventNoWithDifferentContentConflicts() {
        Long seasonId = newSeason(FAR_FUTURE, "落叶松", "100");
        approve("SUS-013", seasonId, FAR_FUTURE, item("落叶松", "40"));
        licenseService.suspend("SUS-013", change(1, "天气"));

        assertThatThrownBy(() -> licenseService.suspend("SUS-013", change(1, "违规")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> licenseService.revoke("SUS-013", change(1, "违规")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void timelineKeepsEventsInOrder() {
        Long seasonId = newSeason(FAR_FUTURE, "落叶松", "100");
        approve("SUS-014", seasonId, FAR_FUTURE, item("落叶松", "40"));
        licenseService.suspend("SUS-014", change(1, "天气"));
        licenseService.resume("SUS-014", change(2, "解除"));
        licenseService.suspend("SUS-014", change(3, "违规"));
        licenseService.revoke("SUS-014", change(4, "取消资格"));

        List<StatusEventView> timeline = licenseService.getTimeline("SUS-014");

        assertThat(timeline).extracting(StatusEventView::eventNo).containsExactly(1L, 2L, 3L, 4L);
        assertThat(timeline).extracting(StatusEventView::eventType)
                .containsExactly(AuditEventType.SUSPENDED, AuditEventType.RESUMED,
                        AuditEventType.SUSPENDED, AuditEventType.REVOKED);
    }

    @Test
    void quotaDetailsTrackOccupationAndRelease() {
        Long seasonId = newSeason(FAR_FUTURE, "落叶松", "100");
        approve("SUS-015", seasonId, FAR_FUTURE, item("落叶松", "40"));

        SpeciesQuotaDetailView before = licenseService.getQuotaDetails("SUS-015").get(0);
        assertThat(before.occupiedVolume()).isEqualByComparingTo("40.000");
        assertThat(before.releasedVolume()).isEqualByComparingTo("0.000");
        assertThat(before.harvestedVolume()).isEqualByComparingTo("0.000");

        licenseService.revoke("SUS-015", change(1, "违规"));

        SpeciesQuotaDetailView after = licenseService.getQuotaDetails("SUS-015").get(0);
        assertThat(after.occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(after.releasedVolume()).isEqualByComparingTo("40.000");
    }

    @Test
    void suspendAndRevokeRequireReason() {
        Long seasonId = newSeason(FAR_FUTURE, "落叶松", "100");
        approve("SUS-016", seasonId, FAR_FUTURE, item("落叶松", "40"));

        assertThatThrownBy(() -> licenseService.suspend("SUS-016", change(1, " ")))
                .isInstanceOf(BusinessValidationException.class);
        assertThatThrownBy(() -> licenseService.revoke("SUS-016", change(1, null)))
                .isInstanceOf(BusinessValidationException.class);
        assertThatThrownBy(() -> licenseService.suspend("SUS-016",
                new StatusChangeCommand(null, "天气", null)))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void auditEventsIncludeStatusChanges() {
        Long seasonId = newSeason(FAR_FUTURE, "落叶松", "100");
        approve("SUS-017", seasonId, FAR_FUTURE, item("落叶松", "40"));
        licenseService.suspend("SUS-017", change(1, "天气"));
        licenseService.revoke("SUS-017", change(2, "违规"));

        List<AuditEventView> events = licenseService.getAuditEvents("SUS-017");

        assertThat(events).extracting(AuditEventView::eventType)
                .containsExactly(AuditEventType.APPROVED, AuditEventType.SUSPENDED,
                        AuditEventType.RELEASED, AuditEventType.REVOKED);
    }
}
