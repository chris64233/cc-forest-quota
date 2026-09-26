package com.chris64233.cc.forestquota.service;

import java.math.BigDecimal;

public record SpeciesQuotaDetailView(String species, BigDecimal approvedVolume, BigDecimal occupiedVolume,
                                     BigDecimal harvestedVolume, BigDecimal releasedVolume) {
}
