package com.chris64233.cc.forestquota.service;

import com.chris64233.cc.forestquota.domain.AuditEventType;
import com.chris64233.cc.forestquota.domain.PermitStatus;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.ApprovalLine;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.SettlementLine;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.ApprovalRequest;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.PermitResponse;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.SettlementRequest;
import com.chris64233.cc.forestquota.web.dto.SeasonDtos.QuotaSpec;
import com.chris64233.cc.forestquota.web.dto.SeasonDtos.SeasonCreateRequest;
import com.chris64233.cc.forestquota.web.dto.SeasonDtos.SeasonLedgerResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PermitServiceTest {

    @Autowired
    SeasonService seasonService;

    @Autowired
    PermitFacade permitFacade;

    @Autowired
    PermitService permitService;

    private String newArea() {
        return "AREA-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void createSeason(String area, String code, BigDecimal pine, BigDecimal fir) {
        seasonService.createSeason(new SeasonCreateRequest(
                area, code,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 6, 30),
                List.of(new QuotaSpec("PINE", pine), new QuotaSpec("FIR", fir))));
    }

    private ApprovalRequest approval(String no, String area, String code,
                                     BigDecimal pine, BigDecimal fir) {
        return new ApprovalRequest(no, area, code,
                LocalDate.of(2026, 3, 10), LocalDate.of(2026, 4, 10),
                List.of(new ApprovalLine("PINE", pine), new ApprovalLine("FIR", fir)));
    }

    private Map<String, BigDecimal> ledgerBySpecies(String area, String code,
                                                    Function<com.chris64233.cc.forestquota.web.dto.SeasonDtos.QuotaLedgerEntry, BigDecimal> picker) {
        SeasonLedgerResponse ledger = seasonService.getLedger(area, code);
        return ledger.quotas().stream()
                .collect(Collectors.toMap(
                        com.chris64233.cc.forestquota.web.dto.SeasonDtos.QuotaLedgerEntry::species, picker));
    }

    @Test
    void approveReservesAllSpeciesAtomically() {
        String area = newArea();
        createSeason(area, "S1", bd("1000"), bd("500"));

        PermitResponse permit = permitFacade.approve(approval("A-1", area, "S1", bd("100.5"), bd("50")));

        assertThat(permit.status()).isEqualTo(PermitStatus.APPROVED);
        assertThat(ledgerBySpecies(area, "S1", e -> e.reservedVolume()))
                .containsEntry("PINE", bd("100.500"))
                .containsEntry("FIR", bd("50.000"));
        assertThat(ledgerBySpecies(area, "S1", e -> e.availableVolume()))
                .containsEntry("PINE", bd("899.500"))
                .containsEntry("FIR", bd("450.000"));
        assertThat(permitService.getEvents("A-1"))
                .extracting(e -> e.eventType())
                .containsExactly(AuditEventType.APPROVED);
    }

    @Test
    void approveFailsEntirelyWhenAnySpeciesInsufficient() {
        String area = newArea();
        createSeason(area, "S1", bd("100"), bd("10"));

        assertThatThrownBy(() -> permitFacade.approve(approval("A-2", area, "S1", bd("50"), bd("20"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FIR");

        assertThat(ledgerBySpecies(area, "S1", e -> e.reservedVolume()))
                .containsEntry("PINE", bd("0.000"))
                .containsEntry("FIR", bd("0.000"));
        assertThatThrownBy(() -> permitService.getPermit("A-2"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void approveRejectsWorkDatesOutsideSeason() {
        String area = newArea();
        createSeason(area, "S1", bd("100"), bd("100"));

        ApprovalRequest request = new ApprovalRequest("A-3", area, "S1",
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 4, 1),
                List.of(new ApprovalLine("PINE", bd("10"))));

        assertThatThrownBy(() -> permitFacade.approve(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("许可季");
    }

    @Test
    void approveIsIdempotentForSameContent() {
        String area = newArea();
        createSeason(area, "S1", bd("100"), bd("100"));

        PermitResponse first = permitFacade.approve(approval("A-4", area, "S1", bd("10"), bd("5")));
        PermitResponse second = permitFacade.approve(approval("A-4", area, "S1", bd("10"), bd("5")));

        assertThat(second).isEqualTo(first);
        assertThat(ledgerBySpecies(area, "S1", e -> e.reservedVolume()))
                .containsEntry("PINE", bd("10.000"));
    }

    @Test
    void approveConflictsForSameNumberWithDifferentContent() {
        String area = newArea();
        createSeason(area, "S1", bd("100"), bd("100"));
        permitFacade.approve(approval("A-5", area, "S1", bd("10"), bd("5")));

        assertThatThrownBy(() -> permitFacade.approve(approval("A-5", area, "S1", bd("20"), bd("5"))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void settleReleasesUnusedAndCountsHarvested() {
        String area = newArea();
        createSeason(area, "S1", bd("100"), bd("100"));
        permitFacade.approve(approval("A-6", area, "S1", bd("60"), bd("40")));

        PermitResponse settled = permitFacade.settle("A-6", new SettlementRequest(List.of(
                new SettlementLine("PINE", bd("45")),
                new SettlementLine("FIR", bd("40")))));

        assertThat(settled.status()).isEqualTo(PermitStatus.SETTLED);
        SeasonLedgerResponse ledger = seasonService.getLedger(area, "S1");
        Map<String, com.chris64233.cc.forestquota.web.dto.SeasonDtos.QuotaLedgerEntry> bySpecies =
                ledger.quotas().stream().collect(Collectors.toMap(e -> e.species(), e -> e));
        assertThat(bySpecies.get("PINE").reservedVolume()).isEqualByComparingTo(bd("0"));
        assertThat(bySpecies.get("PINE").harvestedVolume()).isEqualByComparingTo(bd("45"));
        assertThat(bySpecies.get("PINE").availableVolume()).isEqualByComparingTo(bd("55"));
        assertThat(bySpecies.get("FIR").harvestedVolume()).isEqualByComparingTo(bd("40"));
        assertThat(bySpecies.get("FIR").availableVolume()).isEqualByComparingTo(bd("60"));

        assertThat(permitService.getEvents("A-6"))
                .extracting(e -> e.eventType())
                .containsExactly(AuditEventType.APPROVED, AuditEventType.SETTLED, AuditEventType.RELEASED);
    }

    @Test
    void settleRejectsActualAboveApproved() {
        String area = newArea();
        createSeason(area, "S1", bd("100"), bd("100"));
        permitFacade.approve(approval("A-7", area, "S1", bd("60"), bd("40")));

        assertThatThrownBy(() -> permitFacade.settle("A-7", new SettlementRequest(List.of(
                new SettlementLine("PINE", bd("61")),
                new SettlementLine("FIR", bd("40"))))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过批准量");

        assertThat(ledgerBySpecies(area, "S1", e -> e.reservedVolume()))
                .containsEntry("PINE", bd("60.000"));
    }

    @Test
    void settleRejectsNegativeActual() {
        String area = newArea();
        createSeason(area, "S1", bd("100"), bd("100"));
        permitFacade.approve(approval("A-8", area, "S1", bd("60"), bd("40")));

        assertThatThrownBy(() -> permitFacade.settle("A-8", new SettlementRequest(List.of(
                new SettlementLine("PINE", bd("-1")),
                new SettlementLine("FIR", bd("40"))))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不得为负");
    }

    @Test
    void repeatedSettlementReturnsOriginalWithoutDoubleRelease() {
        String area = newArea();
        createSeason(area, "S1", bd("100"), bd("100"));
        permitFacade.approve(approval("A-9", area, "S1", bd("60"), bd("40")));
        SettlementRequest declaration = new SettlementRequest(List.of(
                new SettlementLine("PINE", bd("45")),
                new SettlementLine("FIR", bd("30"))));

        PermitResponse first = permitFacade.settle("A-9", declaration);
        PermitResponse second = permitFacade.settle("A-9", declaration);

        assertThat(second).isEqualTo(first);
        SeasonLedgerResponse ledger = seasonService.getLedger(area, "S1");
        Map<String, com.chris64233.cc.forestquota.web.dto.SeasonDtos.QuotaLedgerEntry> bySpecies =
                ledger.quotas().stream().collect(Collectors.toMap(e -> e.species(), e -> e));
        assertThat(bySpecies.get("PINE").harvestedVolume()).isEqualByComparingTo(bd("45"));
        assertThat(bySpecies.get("PINE").availableVolume()).isEqualByComparingTo(bd("55"));
        assertThat(permitService.getEvents("A-9"))
                .extracting(e -> e.eventType())
                .containsExactly(AuditEventType.APPROVED, AuditEventType.SETTLED, AuditEventType.RELEASED);

        assertThatThrownBy(() -> permitFacade.settle("A-9", new SettlementRequest(List.of(
                new SettlementLine("PINE", bd("50")),
                new SettlementLine("FIR", bd("30"))))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void concurrentApprovalsNeverOversellQuota() throws InterruptedException {
        String area = newArea();
        createSeason(area, "S1", bd("100"), bd("1000000"));
        int threads = 10;
        BigDecimal each = bd("30");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        Map<Integer, Throwable> failures = new ConcurrentHashMap<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    permitFacade.approve(approval("C-" + index, area, "S1", each, bd("1")));
                    successes.incrementAndGet();
                } catch (BusinessException e) {
                    failures.put(index, e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(successes.get()).isEqualTo(3);
        assertThat(failures).hasSize(threads - 3);
        SeasonLedgerResponse ledger = seasonService.getLedger(area, "S1");
        var pine = ledger.quotas().stream().filter(q -> q.species().equals("PINE")).findFirst().orElseThrow();
        assertThat(pine.reservedVolume()).isEqualByComparingTo(bd("90"));
        assertThat(pine.availableVolume()).isEqualByComparingTo(bd("10"));
        assertThat(pine.reservedVolume().add(pine.harvestedVolume()))
                .isLessThanOrEqualTo(pine.authorizedVolume());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
