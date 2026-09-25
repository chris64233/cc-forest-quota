package com.chris64233.cc.forestquota.service;

import java.math.BigDecimal;

public record QuotaLedgerEntry(String species, BigDecimal authorizedVolume, BigDecimal occupiedVolume,
                               BigDecimal harvestedVolume, BigDecimal availableVolume) {
}
