package com.chris64233.cc.forestquota;

import com.chris64233.cc.forestquota.domain.HarvestSeason;
import com.chris64233.cc.forestquota.domain.LicenseStatus;
import com.chris64233.cc.forestquota.service.ApproveCommand;
import com.chris64233.cc.forestquota.service.ConflictException;
import com.chris64233.cc.forestquota.service.LicenseService;
import com.chris64233.cc.forestquota.service.LicenseView;
import com.chris64233.cc.forestquota.service.QuotaLedgerEntry;
import com.chris64233.cc.forestquota.service.SeasonService;
import com.chris64233.cc.forestquota.service.SpeciesVolume;
import com.chris64233.cc.forestquota.service.StatusChangeCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ConcurrentStatusChangeTest {

    private static final LocalDate SEASON_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate FAR_FUTURE = LocalDate.of(2099, 12, 31);
    private static final LocalDate WORK_START = LocalDate.of(2026, 3, 1);

    @Autowired
    private SeasonService seasonService;

    @Autowired
    private LicenseService licenseService;

    private Long newSeasonWithQuota(String volume) {
        HarvestSeason season = seasonService.createSeason("红山林区", SEASON_START, FAR_FUTURE);
        seasonService.addQuota(season.getId(), "落叶松", new BigDecimal(volume));
        return season.getId();
    }

    private void approve(String applicationNo, Long seasonId, String volume) {
        licenseService.approve(new ApproveCommand(applicationNo, seasonId, WORK_START, FAR_FUTURE,
                List.of(new SpeciesVolume("落叶松", new BigDecimal(volume)))));
    }

    private String runRace(Callable<String> first, Callable<String> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> wrappedFirst = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return first.call();
        };
        Callable<String> wrappedSecond = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return second.call();
        };
        Future<String> firstFuture = executor.submit(wrappedFirst);
        Future<String> secondFuture = executor.submit(wrappedSecond);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        return firstFuture.get() + "|" + secondFuture.get();
    }

    private String attempt(Runnable action, String successTag) {
        try {
            action.run();
            return successTag;
        } catch (ConflictException e) {
            return "CONFLICT";
        }
    }

    @Test
    void settleAndRevokeRaceKeepsQuotaConsistent() throws Exception {
        Long seasonId = newSeasonWithQuota("100");
        approve("RACE-SR", seasonId, "40");

        String outcome = runRace(
                () -> attempt(() -> licenseService.settle("RACE-SR",
                        List.of(new SpeciesVolume("落叶松", new BigDecimal("25")))), "SETTLED"),
                () -> attempt(() -> licenseService.revoke("RACE-SR",
                        new StatusChangeCommand(1L, "违规", null)), "REVOKED"));

        LicenseView view = licenseService.getLicense("RACE-SR");
        QuotaLedgerEntry quota = seasonService.getLedger(seasonId).quotas().get(0);
        assertThat(quota.occupiedVolume()).isEqualByComparingTo("0.000");
        if (outcome.contains("SETTLED")) {
            // 核销胜出：实采 25 永久计入，未用 15 释放，撤销不得再释放已消耗额度
            assertThat(outcome).isEqualTo("SETTLED|CONFLICT");
            assertThat(view.status()).isEqualTo(LicenseStatus.SETTLED);
            assertThat(quota.harvestedVolume()).isEqualByComparingTo("25.000");
            assertThat(quota.availableVolume()).isEqualByComparingTo("75.000");
        } else {
            // 撤销胜出：全部占用一次性释放，核销不得再计入实采
            assertThat(outcome).isEqualTo("CONFLICT|REVOKED");
            assertThat(view.status()).isEqualTo(LicenseStatus.REVOKED);
            assertThat(quota.harvestedVolume()).isEqualByComparingTo("0.000");
            assertThat(quota.availableVolume()).isEqualByComparingTo("100.000");
        }
    }

    @Test
    void suspendAndSettleRaceKeepsQuotaConsistent() throws Exception {
        Long seasonId = newSeasonWithQuota("100");
        approve("RACE-SS", seasonId, "40");

        String outcome = runRace(
                () -> attempt(() -> licenseService.suspend("RACE-SS",
                        new StatusChangeCommand(1L, "暴雨", null)), "SUSPENDED"),
                () -> attempt(() -> licenseService.settle("RACE-SS",
                        List.of(new SpeciesVolume("落叶松", new BigDecimal("25")))), "SETTLED"));

        LicenseView view = licenseService.getLicense("RACE-SS");
        QuotaLedgerEntry quota = seasonService.getLedger(seasonId).quotas().get(0);
        if (outcome.contains("SUSPENDED")) {
            // 暂停胜出：核销被拒绝，占用保留
            assertThat(outcome).isEqualTo("SUSPENDED|CONFLICT");
            assertThat(view.status()).isEqualTo(LicenseStatus.SUSPENDED);
            assertThat(quota.occupiedVolume()).isEqualByComparingTo("40.000");
            assertThat(quota.harvestedVolume()).isEqualByComparingTo("0.000");
        } else {
            // 核销胜出：暂停被拒绝（已核销不能暂停）
            assertThat(outcome).isEqualTo("CONFLICT|SETTLED");
            assertThat(view.status()).isEqualTo(LicenseStatus.SETTLED);
            assertThat(quota.occupiedVolume()).isEqualByComparingTo("0.000");
            assertThat(quota.harvestedVolume()).isEqualByComparingTo("25.000");
        }
    }

    @Test
    void concurrentIdenticalRevocationsAreIdempotent() throws Exception {
        Long seasonId = newSeasonWithQuota("100");
        approve("RACE-RV", seasonId, "40");
        StatusChangeCommand command = new StatusChangeCommand(1L, "违规", null);

        String outcome = runRace(
                () -> attempt(() -> licenseService.revoke("RACE-RV", command), "OK"),
                () -> attempt(() -> licenseService.revoke("RACE-RV", command), "OK"));

        assertThat(outcome).isEqualTo("OK|OK");
        QuotaLedgerEntry quota = seasonService.getLedger(seasonId).quotas().get(0);
        assertThat(quota.occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(quota.availableVolume()).isEqualByComparingTo("100.000");
        assertThat(licenseService.getTimeline("RACE-RV")).hasSize(1);
    }
}
