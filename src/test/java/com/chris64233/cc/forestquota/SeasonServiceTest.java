package com.chris64233.cc.forestquota;

import com.chris64233.cc.forestquota.domain.HarvestSeason;
import com.chris64233.cc.forestquota.service.BusinessValidationException;
import com.chris64233.cc.forestquota.service.ConflictException;
import com.chris64233.cc.forestquota.service.QuotaLedgerEntry;
import com.chris64233.cc.forestquota.service.SeasonLedgerView;
import com.chris64233.cc.forestquota.service.SeasonService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class SeasonServiceTest {

    @Autowired
    private SeasonService seasonService;

    @Test
    void createSeasonRejectsInvalidDateRange() {
        assertThatThrownBy(() -> seasonService.createSeason("红山林区",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void addQuotaRejectsNonPositiveVolume() {
        HarvestSeason season = seasonService.createSeason("红山林区",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertThatThrownBy(() -> seasonService.addQuota(season.getId(), "落叶松", BigDecimal.ZERO))
                .isInstanceOf(BusinessValidationException.class);
        assertThatThrownBy(() -> seasonService.addQuota(season.getId(), "落叶松", new BigDecimal("-1")))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void addQuotaRejectsExcessivePrecision() {
        HarvestSeason season = seasonService.createSeason("红山林区",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertThatThrownBy(() -> seasonService.addQuota(season.getId(), "落叶松", new BigDecimal("1.0001")))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void addQuotaRejectsDuplicateSpecies() {
        HarvestSeason season = seasonService.createSeason("红山林区",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        seasonService.addQuota(season.getId(), "落叶松", new BigDecimal("100"));

        assertThatThrownBy(() -> seasonService.addQuota(season.getId(), "落叶松", new BigDecimal("50")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void ledgerShowsAvailableEqualToAuthorizedInitially() {
        HarvestSeason season = seasonService.createSeason("红山林区",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        seasonService.addQuota(season.getId(), "落叶松", new BigDecimal("100.5"));

        SeasonLedgerView ledger = seasonService.getLedger(season.getId());

        assertThat(ledger.quotas()).hasSize(1);
        QuotaLedgerEntry entry = ledger.quotas().get(0);
        assertThat(entry.authorizedVolume()).isEqualByComparingTo("100.500");
        assertThat(entry.occupiedVolume()).isEqualByComparingTo("0.000");
        assertThat(entry.harvestedVolume()).isEqualByComparingTo("0.000");
        assertThat(entry.availableVolume()).isEqualByComparingTo("100.500");
    }
}
