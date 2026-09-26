package com.chris64233.cc.forestquota;

import com.chris64233.cc.forestquota.domain.HarvestSeason;
import com.chris64233.cc.forestquota.domain.LicenseStatus;
import com.chris64233.cc.forestquota.domain.StatusEventType;
import com.chris64233.cc.forestquota.service.ApproveCommand;
import com.chris64233.cc.forestquota.service.BusinessValidationException;
import com.chris64233.cc.forestquota.service.ConflictException;
import com.chris64233.cc.forestquota.service.LicenseItemView;
import com.chris64233.cc.forestquota.service.LicenseService;
import com.chris64233.cc.forestquota.service.LicenseView;
import com.chris64233.cc.forestquota.service.QuotaLedgerEntry;
import com.chris64233.cc.forestquota.service.ResumeCheckView;
import com.chris64233.cc.forestquota.service.SeasonLedgerView;
import com.chris64233.cc.forestquota.service.SeasonService;
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
class LicenseStatusFlowTest {

    private static final LocalDate SEASON_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate SEASON_END = LocalDate.of(2027, 12, 31);
    private static final LocalDate WORK_START = LocalDate.of(2026, 3, 1);
    private static final LocalDate WORK_END = LocalDate.of(2027, 6, 30);

    private static final Instant T1 = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-06-01T00:00:00Z");

    @Autowired
    private SeasonService seasonService;

    @Autowired
    private LicenseService licenseService;

    private Long newSeason(String... speciesAndVolumes) {
        return newSeason(SEASON_START, SEASON_END, speciesAndVolumes);
    }

    private Long newSeason(LocalDate start, LocalDate end, String... speciesAndVolumes) {
        HarvestSeason season = seasonService.createSeason("红山林区", start, end);
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

    private StatusChangeCommand event(String eventNo, String reason, Instant effectiveAt) {
        return new StatusChangeCommand(eventNo, reason, effectiveAt);
    }

    private Map<String, QuotaLedgerEntry> ledgerBySpecies(Long seasonId) {
        SeasonLedgerView ledger = seasonService.getLedger(seasonId);
        return ledger.quotas().stream()
                .collect(Collectors.toMap(QuotaLedgerEntry::species, Function.identity()));
    }

    @Test
    void suspendRecordsEffectiveTimeAndReasonWithoutTouchingLedger() {
        Long seasonId = newSeason("落叶松", "100");
        licenseService.approve(command("SUS-001", seasonId, item("落叶松", "40")));

        LicenseView view = licenseService.suspend("SUS-001", event("EVT-1", "暴雪封山", T1));

        assertThat(view.status()).isEqualTo(LicenseStatus.SUSPENDED);
        List<StatusEventView> timeline = licenseService.getTimeline("SUS-001");
        assertThat(timeline).hasSize(1);
        StatusEventView event = timeline.get(0);
        assertThat(event.eventNo()).isEqualTo("EVT-1");
        assertThat(event.eventType()).isEqualTo(StatusEventType.SUSPENDED);
        assertThat(event.reason()).isEqualTo("暴雪封山");
        assertThat(event.effectiveAt()).isEqualTo(T1);
        // 暂停不释放占用，额度台账保持不变
        assertThat(ledgerBySpecies(seasonId).get("落叶松").occupiedVolume())
                .isEqualByComparingTo("40.000");
    }

    @Test
    void suspendRequiresReason() {
        Long seasonId = newSeason("落叶松", "100");
        licenseService.approve(command("SUS-002", seasonId, item("落叶松", "40")));

        assertThatThrownBy(() -> licenseService.suspend("SUS-002", event("EVT-1", null, T1)))
                .isInstanceOf(BusinessValidationException.class);
        assertThatThrownBy(() -> licenseService.suspend("SUS-002", event("EVT-1", "  ", T1)))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void settleRejectedDuringSuspensionButHistoricalHarvestKept() {
        Long seasonId = newSeason("落叶松", "200");
        // 历史已核销许可证：实采继续计入额度
        licenseService.approve(command("SUS-003A", seasonId, item("落叶松", "40")));
        licenseService.settle("SUS-003A", List.of(item("落叶松", "25")));
        // 另一张许可证暂停后禁止新增实采申报
        licenseService.approve(command("SUS-003B", seasonId, item("落叶松", "40")));
        licenseService.suspend("SUS-003B", event("EVT-1", "违规作业", T1));

        assertThatThrownBy(() -> licenseService.settle("SUS-003B", List.of(item("落叶松", "10"))))
                .isInstanceOf(ConflictException.class);

        QuotaLedgerEntry quota = ledgerBySpecies(seasonId).get("落叶松");
        assertThat(quota.harvestedVolume()).isEqualByComparingTo("25.000");
        assertThat(quota.occupiedVolume()).isEqualByComparingTo("40.000");
    }

    @Test
    void resumeRestoresApprovedAndAllowsSettlement() {
        Long seasonId = newSeason("落叶松", "100");
        licenseService.approve(command("SUS-004", seasonId, item("落叶松", "40")));
        licenseService.suspend("SUS-004", event("EVT-1", "暴雪封山", T1));

        LicenseView resumed = licenseService.resume("SUS-004", event("EVT-2", null, T2));

        assertThat(resumed.status()).isEqualTo(LicenseStatus.APPROVED);
        assertThat(licenseService.getTimeline("SUS-004"))
                .extracting(StatusEventView::eventType)
                .containsExactly(StatusEventType.SUSPENDED, StatusEventType.RESUMED);

        LicenseView settled = licenseService.settle("SUS-004", List.of(item("落叶松", "30")));
        assertThat(settled.status()).isEqualTo(LicenseStatus.SETTLED);
    }

    @Test
    void resumeBlockedWhenWorkPeriodExpired() {
        Long seasonId = newSeason(SEASON_START, LocalDate.of(2026, 8, 31), "落叶松", "100");
        licenseService.approve(new ApproveCommand("SUS-005", seasonId,
                WORK_START, LocalDate.of(2026, 6, 30), List.of(item("落叶松", "40"))));
        licenseService.suspend("SUS-005", event("EVT-1", "林区封闭", T1));

        ResumeCheckView check = licenseService.getResumeCheck("SUS-005");
        assertThat(check.resumable()).isFalse();
        assertThat(check.blockers()).anySatisfy(blocker -> assertThat(blocker).contains("届满"));

        assertThatThrownBy(() -> licenseService.resume("SUS-005", event("EVT-2", null, T2)))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("恢复被阻止");
        assertThat(licenseService.getLicense("SUS-005").status()).isEqualTo(LicenseStatus.SUSPENDED);
    }

    @Test
    void resumeCheckReportsNotSuspendedStatus() {
        Long seasonId = newSeason("落叶松", "100");
        licenseService.approve(command("SUS-006", seasonId, item("落叶松", "40")));

        ResumeCheckView check = licenseService.getResumeCheck("SUS-006");

        assertThat(check.resumable()).isFalse();
        assertThat(check.blockers()).anySatisfy(blocker -> assertThat(blocker).contains("暂停"));
    }

    @Test
    void revokeReleasesAllRemainingOccupationPerSpecies() {
        Long seasonId = newSeason("落叶松", "100", "云杉", "50");
        licenseService.approve(command("SUS-007", seasonId, item("落叶松", "40"), item("云杉", "20")));
        licenseService.suspend("SUS-007", event("EVT-1", "违规作业", T1));

        LicenseView revoked = licenseService.revoke("SUS-007", event("EVT-2", "吊销许可", T2));

        assertThat(revoked.status()).isEqualTo(LicenseStatus.REVOKED);
        Map<String, LicenseItemView> items = revoked.items().stream()
                .collect(Collectors.toMap(LicenseItemView::species, Function.identity()));
        assertThat(items.get("落叶松").releasedVolume()).isEqualByComparingTo("40.000");
        assertThat(items.get("落叶松").actualVolume()).isNull();
        assertThat(items.get("云杉").releasedVolume()).isEqualByComparingTo("20.000");

        Map<String, QuotaLedgerEntry> ledger = ledgerBySpecies(seasonId);
        assertThat(ledger.get("落叶松").occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(ledger.get("落叶松").availableVolume()).isEqualByComparingTo("100.000");
        assertThat(ledger.get("云杉").occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(ledger.get("云杉").availableVolume()).isEqualByComparingTo("50.000");

        assertThat(licenseService.getTimeline("SUS-007"))
                .extracting(StatusEventView::eventType)
                .containsExactly(StatusEventType.SUSPENDED, StatusEventType.REVOKED);
    }

    @Test
    void revokeIsTerminal() {
        Long seasonId = newSeason("落叶松", "100");
        licenseService.approve(command("SUS-008", seasonId, item("落叶松", "40")));
        licenseService.revoke("SUS-008", event("EVT-1", "吊销许可", T1));

        assertThatThrownBy(() -> licenseService.settle("SUS-008", List.of(item("落叶松", "10"))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> licenseService.suspend("SUS-008", event("EVT-2", "暴雪", T2)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> licenseService.resume("SUS-008", event("EVT-3", null, T2)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> licenseService.revoke("SUS-008", event("EVT-4", "重复吊销", T2)))
                .isInstanceOf(ConflictException.class);

        // 终态后额度台账不变
        QuotaLedgerEntry quota = ledgerBySpecies(seasonId).get("落叶松");
        assertThat(quota.occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(quota.harvestedVolume()).isEqualByComparingTo("0.000");
    }

    @Test
    void settledLicenseCannotBeSuspendedOrRevoked() {
        Long seasonId = newSeason("落叶松", "100");
        licenseService.approve(command("SUS-009", seasonId, item("落叶松", "40")));
        licenseService.settle("SUS-009", List.of(item("落叶松", "25")));

        assertThatThrownBy(() -> licenseService.suspend("SUS-009", event("EVT-1", "暴雪", T1)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> licenseService.revoke("SUS-009", event("EVT-2", "吊销", T1)))
                .isInstanceOf(ConflictException.class);

        // 已实采量不得归还
        QuotaLedgerEntry quota = ledgerBySpecies(seasonId).get("落叶松");
        assertThat(quota.harvestedVolume()).isEqualByComparingTo("25.000");
        assertThat(quota.availableVolume()).isEqualByComparingTo("75.000");
    }

    @Test
    void statusEventsAreIdempotentByEventNo() {
        Long seasonId = newSeason("落叶松", "100");
        licenseService.approve(command("SUS-010", seasonId, item("落叶松", "40")));

        LicenseView first = licenseService.suspend("SUS-010", event("EVT-1", "暴雪封山", T1));
        LicenseView replay = licenseService.suspend("SUS-010", event("EVT-1", "暴雪封山", T1));

        assertThat(replay.status()).isEqualTo(LicenseStatus.SUSPENDED);
        assertThat(replay.licenseId()).isEqualTo(first.licenseId());
        assertThat(licenseService.getTimeline("SUS-010")).hasSize(1);

        assertThatThrownBy(() -> licenseService.suspend("SUS-010", event("EVT-1", "其他原因", T1)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> licenseService.resume("SUS-010", event("EVT-1", null, T2)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void revokeIdempotentReplayDoesNotReleaseTwice() {
        Long seasonId = newSeason("落叶松", "100");
        licenseService.approve(command("SUS-011", seasonId, item("落叶松", "40")));

        licenseService.revoke("SUS-011", event("EVT-1", "吊销许可", T1));
        LicenseView replay = licenseService.revoke("SUS-011", event("EVT-1", "吊销许可", T1));

        assertThat(replay.status()).isEqualTo(LicenseStatus.REVOKED);
        QuotaLedgerEntry quota = ledgerBySpecies(seasonId).get("落叶松");
        assertThat(quota.occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(quota.availableVolume()).isEqualByComparingTo("100.000");
        assertThat(licenseService.getTimeline("SUS-011")).hasSize(1);
    }

    @Test
    void statusEventsMustKeepTimeOrder() {
        Long seasonId = newSeason("落叶松", "100");
        licenseService.approve(command("SUS-012", seasonId, item("落叶松", "40")));
        licenseService.suspend("SUS-012", event("EVT-1", "暴雪封山", T2));

        assertThatThrownBy(() -> licenseService.resume("SUS-012", event("EVT-2", null, T1)))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("时间");

        assertThat(licenseService.getLicense("SUS-012").status()).isEqualTo(LicenseStatus.SUSPENDED);
        assertThat(licenseService.getTimeline("SUS-012")).hasSize(1);
    }
}
