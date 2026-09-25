package com.chris64233.cc.forestquota;

import com.chris64233.cc.forestquota.domain.HarvestSeason;
import com.chris64233.cc.forestquota.service.ApproveCommand;
import com.chris64233.cc.forestquota.service.LicenseService;
import com.chris64233.cc.forestquota.service.LicenseView;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ConcurrentApprovalTest {

    private static final LocalDate SEASON_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate SEASON_END = LocalDate.of(2026, 12, 31);

    @Autowired
    private SeasonService seasonService;

    @Autowired
    private LicenseService licenseService;

    @Test
    void concurrentApprovalsNeverOversellQuota() throws Exception {
        HarvestSeason season = seasonService.createSeason("红山林区", SEASON_START, SEASON_END);
        seasonService.addQuota(season.getId(), "落叶松", new BigDecimal("100"));

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = IntStream.range(0, threads)
                .mapToObj(i -> executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        licenseService.approve(new ApproveCommand("RACE-" + i, season.getId(),
                                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 31),
                                List.of(new SpeciesVolume("落叶松", new BigDecimal("30")))));
                        return true;
                    } catch (QuotaExceededException e) {
                        return false;
                    }
                }))
                .toList();

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long successes = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                successes++;
            }
        }
        assertThat(successes).isEqualTo(3);

        SeasonLedgerView ledger = seasonService.getLedger(season.getId());
        QuotaLedgerEntry quota = ledger.quotas().get(0);
        assertThat(quota.occupiedVolume()).isEqualByComparingTo("90.000");
        assertThat(quota.availableVolume()).isEqualByComparingTo("10.000");
    }

    @Test
    void concurrentIdenticalApprovalsAreIdempotent() throws Exception {
        HarvestSeason season = seasonService.createSeason("红山林区", SEASON_START, SEASON_END);
        seasonService.addQuota(season.getId(), "落叶松", new BigDecimal("100"));

        ApproveCommand command = new ApproveCommand("RACE-SAME", season.getId(),
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 31),
                List.of(new SpeciesVolume("落叶松", new BigDecimal("40"))));

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LicenseView>> futures = IntStream.range(0, threads)
                .mapToObj(i -> executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return licenseService.approve(command);
                }))
                .toList();

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        Long licenseId = futures.get(0).get().licenseId();
        for (Future<LicenseView> future : futures) {
            assertThat(future.get().licenseId()).isEqualTo(licenseId);
        }

        SeasonLedgerView ledger = seasonService.getLedger(season.getId());
        assertThat(ledger.quotas().get(0).occupiedVolume()).isEqualByComparingTo("40.000");
    }
}
