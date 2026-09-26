package com.chris64233.cc.forestquota;

import com.chris64233.cc.forestquota.domain.HarvestSeason;
import com.chris64233.cc.forestquota.domain.LicenseStatus;
import com.chris64233.cc.forestquota.service.ApproveCommand;
import com.chris64233.cc.forestquota.service.ConflictException;
import com.chris64233.cc.forestquota.service.LicenseService;
import com.chris64233.cc.forestquota.service.LicenseView;
import com.chris64233.cc.forestquota.service.QuotaLedgerEntry;
import com.chris64233.cc.forestquota.service.SeasonLedgerView;
import com.chris64233.cc.forestquota.service.SeasonService;
import com.chris64233.cc.forestquota.service.SpeciesVolume;
import com.chris64233.cc.forestquota.service.StatusChangeCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ConcurrentStatusChangeTest {

    private static final LocalDate SEASON_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate SEASON_END = LocalDate.of(2027, 12, 31);
    private static final LocalDate WORK_START = LocalDate.of(2026, 3, 1);
    private static final LocalDate WORK_END = LocalDate.of(2027, 6, 30);
    private static final Instant T1 = Instant.parse("2026-04-01T00:00:00Z");

    @Autowired
    private SeasonService seasonService;

    @Autowired
    private LicenseService licenseService;

    private Long newSeasonWithQuota(String volume) {
        HarvestSeason season = seasonService.createSeason("红山林区", SEASON_START, SEASON_END);
        seasonService.addQuota(season.getId(), "落叶松", new BigDecimal(volume));
        return season.getId();
    }

    private void approve(String applicationNo, Long seasonId, String volume) {
        licenseService.approve(new ApproveCommand(applicationNo, seasonId, WORK_START, WORK_END,
                List.of(new SpeciesVolume("落叶松", new BigDecimal(volume)))));
    }

    private QuotaLedgerEntry quotaOf(Long seasonId) {
        SeasonLedgerView ledger = seasonService.getLedger(seasonId);
        return ledger.quotas().get(0);
    }

    @Test
    void concurrentSettleAndRevokeAreSerializedOnSameLicense() throws Exception {
        Long seasonId = newSeasonWithQuota("100");
        approve("RACE-SR", seasonId, "40");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = List.of(
                executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        licenseService.settle("RACE-SR",
                                List.of(new SpeciesVolume("落叶松", new BigDecimal("25"))));
                        return "SETTLED";
                    } catch (ConflictException e) {
                        return "CONFLICT";
                    }
                }),
                executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        licenseService.revoke("RACE-SR", new StatusChangeCommand("EVT-R", "吊销", T1));
                        return "REVOKED";
                    } catch (ConflictException e) {
                        return "CONFLICT";
                    }
                }));

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        String settleOutcome = futures.get(0).get();
        String revokeOutcome = futures.get(1).get();
        // 同一许可证版本上二者只能其一成功
        assertThat(settleOutcome).isNotEqualTo(revokeOutcome);

        LicenseView view = licenseService.getLicense("RACE-SR");
        QuotaLedgerEntry quota = quotaOf(seasonId);
        assertThat(quota.occupiedVolume()).isEqualByComparingTo("0.000");
        if ("SETTLED".equals(settleOutcome)) {
            // 核销胜出：实采计入，未用量释放一次，撤销不得再释放已消耗额度
            assertThat(view.status()).isEqualTo(LicenseStatus.SETTLED);
            assertThat(quota.harvestedVolume()).isEqualByComparingTo("25.000");
            assertThat(quota.availableVolume()).isEqualByComparingTo("75.000");
            assertThat(view.items().get(0).releasedVolume()).isEqualByComparingTo("15.000");
        } else {
            // 撤销胜出：全部占用释放，申报被拒绝
            assertThat(view.status()).isEqualTo(LicenseStatus.REVOKED);
            assertThat(quota.harvestedVolume()).isEqualByComparingTo("0.000");
            assertThat(quota.availableVolume()).isEqualByComparingTo("100.000");
            assertThat(view.items().get(0).releasedVolume()).isEqualByComparingTo("40.000");
        }
    }

    @Test
    void concurrentDuplicateRevokesReleaseOnlyOnce() throws Exception {
        Long seasonId = newSeasonWithQuota("100");
        approve("RACE-RR", seasonId, "40");

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        StatusChangeCommand command = new StatusChangeCommand("EVT-R", "吊销", T1);
        List<Future<LicenseView>> futures = IntStream.range(0, threads)
                .mapToObj(i -> executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return licenseService.revoke("RACE-RR", command);
                }))
                .toList();

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (Future<LicenseView> future : futures) {
            assertThat(future.get().status()).isEqualTo(LicenseStatus.REVOKED);
        }
        // 未实采占用只释放一次
        QuotaLedgerEntry quota = quotaOf(seasonId);
        assertThat(quota.occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(quota.availableVolume()).isEqualByComparingTo("100.000");
        assertThat(licenseService.getTimeline("RACE-RR")).hasSize(1);
    }

    @Test
    void concurrentSuspendAndSettleAreSerializedOnSameLicense() throws Exception {
        Long seasonId = newSeasonWithQuota("100");
        approve("RACE-SS", seasonId, "40");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = List.of(
                executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        licenseService.settle("RACE-SS",
                                List.of(new SpeciesVolume("落叶松", new BigDecimal("25"))));
                        return "SETTLED";
                    } catch (ConflictException e) {
                        return "CONFLICT";
                    }
                }),
                executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        licenseService.suspend("RACE-SS", new StatusChangeCommand("EVT-S", "暴雪", T1));
                        return "SUSPENDED";
                    } catch (ConflictException e) {
                        return "CONFLICT";
                    }
                }));

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        String settleOutcome = futures.get(0).get();
        String suspendOutcome = futures.get(1).get();
        assertThat(settleOutcome).isNotEqualTo(suspendOutcome);

        QuotaLedgerEntry quota = quotaOf(seasonId);
        if ("SETTLED".equals(settleOutcome)) {
            assertThat(quota.harvestedVolume()).isEqualByComparingTo("25.000");
            assertThat(quota.occupiedVolume()).isEqualByComparingTo("0.000");
        } else {
            assertThat(licenseService.getLicense("RACE-SS").status()).isEqualTo(LicenseStatus.SUSPENDED);
            assertThat(quota.occupiedVolume()).isEqualByComparingTo("40.000");
        }
    }
}
