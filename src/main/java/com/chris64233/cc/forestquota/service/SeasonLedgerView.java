package com.chris64233.cc.forestquota.service;

import java.time.LocalDate;
import java.util.List;

public record SeasonLedgerView(Long seasonId, String forestArea, LocalDate startDate, LocalDate endDate,
                               List<QuotaLedgerEntry> quotas) {
}
